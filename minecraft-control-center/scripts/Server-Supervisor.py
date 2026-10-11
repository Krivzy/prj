#!/usr/bin/env python3
"""Portable MCC launcher v2: normal stop/restart and bounded unexpected-exit recovery."""
from __future__ import annotations

import argparse
from collections import deque
import datetime
import json
import os
from pathlib import Path
import signal
import stat
import subprocess
import sys
import threading
import time
import uuid

PROTOCOL = 2
MAX_COMMAND_LINE = 4096
MAX_QUEUE_BYTES = 1024 * 1024 + MAX_COMMAND_LINE
MAX_BATCH_LINES = 64
RESTART_RESPONSE_DELAY = 1.0
CRASH_WINDOW_SECONDS = 600
CRASH_DELAYS = (2, 10, 30)


def utc():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def canonical_uuid(value):
    try:
        return isinstance(value, str) and len(value) == 36 and str(uuid.UUID(value)) == value
    except (ValueError, AttributeError):
        return False


def unique_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError('Duplicate JSON key')
        result[key] = value
    return result


def decode_command(line, run_id):
    """No arbitrary console input, duplicate keys, stale runs or noncanonical IDs."""
    if not line.endswith(b'\n') or len(line) > MAX_COMMAND_LINE:
        return None
    try:
        item = json.loads(line.decode('utf-8'), object_pairs_hook=unique_object)
        if not isinstance(item, dict) or set(item) != {'command', 'runId', 'requestId'}:
            return None
        if item['command'] not in ('stop', 'restart') or item['runId'] != run_id:
            return None
        if not canonical_uuid(item['runId']) or not canonical_uuid(item['requestId']):
            return None
        return item
    except (ValueError, UnicodeDecodeError, TypeError, AttributeError, RecursionError):
        return None


class QueueReader:
    """Incremental bounded reads; partial records wait, oversized records are discarded."""
    def __init__(self, position=0):
        self.position = position
        self.discarding = False

    def read(self, stream):
        stream.seek(self.position)
        lines = []
        for _ in range(MAX_BATCH_LINES):
            beginning = stream.tell()
            line = stream.readline(MAX_COMMAND_LINE + 1)
            if not line:
                break
            if self.discarding:
                self.position = stream.tell()
                if line.endswith(b'\n'):
                    self.discarding = False
                continue
            if len(line) > MAX_COMMAND_LINE:
                self.position = stream.tell()
                self.discarding = not line.endswith(b'\n')
                continue
            if not line.endswith(b'\n'):
                stream.seek(beginning)
                break
            self.position = stream.tell()
            lines.append(line)
        return lines


class CrashPolicy:
    def __init__(self, maximum=3):
        self.maximum = maximum
        self.failures = deque()

    def delay(self, now):
        while self.failures and now - self.failures[0] >= CRASH_WINDOW_SECONDS:
            self.failures.popleft()
        self.failures.append(now)
        count = len(self.failures)
        return CRASH_DELAYS[count - 1] if count <= self.maximum else None


def ordinary(path, directory=False):
    info = path.lstat()
    if stat.S_ISLNK(info.st_mode) or getattr(info, 'st_file_attributes', 0) & 0x400:
        raise RuntimeError('Launcher paths must not be links or reparse points')
    if not (stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode)):
        raise RuntimeError('Launcher path has an unexpected file type')
    return info


def restrict(path, directory=False):
    ordinary(path, directory)
    if os.name != 'nt':
        if path.stat().st_uid != os.geteuid():
            raise RuntimeError('Launcher files must belong to the current user')
        path.chmod(0o700 if directory else 0o600)
        return
    literal = str(path).replace("'", "''")
    kind = 'DirectorySecurity' if directory else 'FileSecurity'
    inheritance = "'ContainerInherit,ObjectInherit','None'," if directory else ''
    script = ("$ErrorActionPreference='Stop';"
              "$env:PSModulePath=[IO.Path]::Combine($PSHOME,'Modules');"
              "Import-Module Microsoft.PowerShell.Security -ErrorAction Stop;"
              "$p='" + literal + "';"
              "$a=New-Object System.Security.AccessControl." + kind + ";"
              "$a.SetAccessRuleProtection($true,$false);"
              "$u=[System.Security.Principal.WindowsIdentity]::GetCurrent().User;"
              "$s=New-Object System.Security.Principal.SecurityIdentifier('S-1-5-18');"
              "foreach($id in @($u,$s)){$a.AddAccessRule((New-Object "
              "System.Security.AccessControl.FileSystemAccessRule($id,'FullControl'," +
              inheritance + "'Allow')))};Set-Acl -LiteralPath $p -AclObject $a")
    subprocess.run(['powershell.exe', '-NoProfile', '-NonInteractive', '-Command', script],
                   check=True, capture_output=True, creationflags=subprocess.CREATE_NO_WINDOW)


def open_regular(path, flags):
    if path.exists() or path.is_symlink():
        ordinary(path)
    fd = os.open(path, flags | getattr(os, 'O_NOFOLLOW', 0) | getattr(os, 'O_BINARY', 0), 0o600)
    info = os.fstat(fd)
    if not stat.S_ISREG(info.st_mode):
        os.close(fd)
        raise RuntimeError('Launcher file is not regular')
    return fd


def process_alive(pid):
    """Read-only PID liveness. Never send os.kill(..., 0) on Windows."""
    if os.name != 'nt':
        try:
            os.kill(pid, 0)
            return True
        except ProcessLookupError:
            return False
        except PermissionError:
            return True
    import ctypes
    from ctypes import wintypes
    kernel = ctypes.WinDLL('kernel32', use_last_error=True)
    kernel.OpenProcess.argtypes = (wintypes.DWORD, wintypes.BOOL, wintypes.DWORD)
    kernel.OpenProcess.restype = wintypes.HANDLE
    kernel.GetExitCodeProcess.argtypes = (wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD))
    kernel.GetExitCodeProcess.restype = wintypes.BOOL
    kernel.CloseHandle.argtypes = (wintypes.HANDLE,)
    kernel.CloseHandle.restype = wintypes.BOOL
    handle = kernel.OpenProcess(0x1000, False, pid)  # PROCESS_QUERY_LIMITED_INFORMATION
    if not handle:
        error = ctypes.get_last_error()
        if error == 87:  # ERROR_INVALID_PARAMETER: no such process.
            return False
        if error == 5:  # An inaccessible PID is not evidence of offline state.
            return True
        raise RuntimeError('Prior Java PID could not be inspected')
    try:
        code = wintypes.DWORD()
        if not kernel.GetExitCodeProcess(handle, ctypes.byref(code)):
            raise RuntimeError('Prior Java exit status could not be inspected')
        return code.value == 259  # STILL_ACTIVE
    finally:
        kernel.CloseHandle(handle)


def prior_java_offline(control):
    path = control / 'status.json'
    if not path.exists() and not path.is_symlink():
        return
    ordinary(path)
    fd = open_regular(path, os.O_RDONLY)
    with os.fdopen(fd, 'rb') as stream:
        raw = stream.read(16385)
    if len(raw) > 16384:
        raise RuntimeError('Prior launcher status exceeds budget')
    try:
        previous = json.loads(raw.decode('utf-8'), object_pairs_hook=unique_object)
        if not isinstance(previous, dict):
            raise ValueError('Expected status object')
        pid = previous.get('javaPid')
        if pid is not None and (type(pid) is not int or not 0 < pid <= 2147483647):
            raise ValueError('Invalid Java PID')
    except (ValueError, UnicodeDecodeError, RecursionError) as error:
        raise RuntimeError('Prior launcher status is invalid') from error
    if pid is not None and process_alive(pid):
        raise RuntimeError('Prior Java PID is still alive; inspect it before starting')


class OfflineLease:
    """One retained daemon.lock lease across every profile hook and JVM boot."""
    def __init__(self, path):
        self.fd = open_regular(path, os.O_CREAT | os.O_RDWR)
        try:
            if os.name == 'nt':
                import msvcrt
                if os.fstat(self.fd).st_size == 0:
                    os.write(self.fd, b'0')
                os.lseek(self.fd, 0, os.SEEK_SET)
                msvcrt.locking(self.fd, msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BaseException:
            os.close(self.fd)
            self.fd = None
            raise

    def close(self):
        if self.fd is None:
            return
        try:
            if os.name == 'nt':
                import msvcrt
                os.lseek(self.fd, 0, os.SEEK_SET)
                msvcrt.locking(self.fd, msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.fd, fcntl.LOCK_UN)
        finally:
            os.close(self.fd)
            self.fd = None


def validate_jvm_arguments(values):
    for value in values:
        if (not isinstance(value, str) or not value.startswith(('-X', '-D')) or
                len(value) > 512 or any(ord(c) < 32 for c in value) or
                value.startswith(('-Xms', '-Xmx', '-Dmcc.launcher.'))):
            raise ValueError('Unsupported JVM argument or managed-property override')
    return list(values)


def server_command(args, root, control, run_id, hook_passed):
    command = [str(Path(args.java).resolve()), f'-Xms{args.min_memory_mib}M',
               f'-Xmx{args.memory_mib}M', '-Dterminal.ansi=false', *args.jvm_arg,
               '-Dmcc.launcher.control=' + str(control), '-Dmcc.launcher.run-id=' + run_id]
    if hook_passed:
        command.append('-Dmcc.launcher.profile-hook=true')
    return command + ['-jar', str(root / args.server_jar), '--nogui', '--nojline']


def profile_command(args, root):
    if (root / 'plugins').exists() or (root / 'plugins').is_symlink():
        ordinary(root / 'plugins', True)
    plugin = root / 'plugins' / 'MinecraftControlCenter.jar'
    if not plugin.exists() and not plugin.is_symlink():
        return None
    ordinary(root / 'plugins', True)
    ordinary(plugin)
    return [str(Path(args.java).resolve()), '-Xms64M', '-Xmx256M', *args.jvm_arg,
            '-cp', str(plugin), 'dev.mcc.server.ProfileBootstrap', '--offline',
            '--server-root', str(root), '--plugin-data', str(root / 'plugins' / 'MinecraftControlCenter'),
            '--kernel', args.kernel_name, '--minecraft-version', args.minecraft_version]


class Supervisor:
    def __init__(self, args):
        self.args = args
        self.root = Path(args.server_root).resolve(strict=True)
        self.control = self.root / '.mcc-launcher'
        self.status = {'protocolVersion': PROTOCOL, 'daemonPid': os.getpid(), 'javaPid': None,
                       'runId': args.run_id, 'currentRunId': args.run_id, 'state': 'starting',
                       'paperReady': False, 'dashboardReady': False, 'startedUtc': utc(),
                       'memoryMiB': args.memory_mib, 'kernelName': args.kernel_name,
                       'minecraftVersion': args.minecraft_version, 'bootNumber': 0,
                       'startupReason': 'initial', 'restartRequestId': None}
        self.mutex = threading.RLock()
        self.log_mutex = threading.Lock()
        self.process = self.reader = self.log = self.lease = None
        self.stopping = None
        self.stop_due = None
        self.interrupted = False
        self.prepared = False
        self.crashes = CrashPolicy(args.max_crash_restarts)

    def save(self):
        with self.mutex:
            target = self.control / 'status.json'
            if target.exists() or target.is_symlink():
                ordinary(target)
            temporary = self.control / ('status-' + uuid.uuid4().hex + '.tmp')
            fd = open_regular(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL)
            try:
                with os.fdopen(fd, 'wb') as stream:
                    stream.write(json.dumps(self.status, ensure_ascii=False, indent=2).encode('utf-8'))
                    stream.flush()
                    os.fsync(stream.fileno())
                for attempt in range(20):
                    try:
                        os.replace(temporary, target)
                        return
                    except PermissionError:
                        if attempt == 19:
                            raise
                        time.sleep(0.05)
            finally:
                if temporary.exists():
                    temporary.unlink()

    def write_log(self, data):
        with self.log_mutex:
            self.log.write(data)
            self.log.flush()

    def prepare(self):
        ordinary(self.root, True)
        if not Path(self.args.java).resolve(strict=True).is_file():
            raise RuntimeError('Java executable is missing')
        ordinary(self.root / self.args.server_jar)
        ordinary(self.root / 'eula.txt')
        if not any(line.strip() == 'eula=true' for line in (self.root / 'eula.txt').read_text(encoding='utf-8-sig').splitlines()):
            raise RuntimeError('Minecraft EULA has not been accepted')
        self.control.mkdir(mode=0o700, exist_ok=True)
        ordinary(self.control, True)
        restrict(self.control, True)
        self.lease = OfflineLease(self.control / 'daemon.lock')
        restrict(self.control / 'daemon.lock')
        prior_java_offline(self.control)
        command_file = self.control / 'commands.ndjson'
        fd = open_regular(command_file, os.O_CREAT | os.O_WRONLY | os.O_APPEND)
        os.close(fd)
        restrict(command_file)
        info = ordinary(command_file)
        if info.st_size > MAX_QUEUE_BYTES:
            raise RuntimeError('Command history exceeds budget; archive it offline')
        self.queue_identity = (info.st_dev, info.st_ino)
        self.queue = QueueReader(info.st_size)  # Ignore entries from previous daemon runs.
        log_path = self.control / 'console.log'
        fd = open_regular(log_path, os.O_CREAT | os.O_WRONLY | os.O_APPEND)
        restrict(log_path)
        self.log = os.fdopen(fd, 'ab', buffering=0)
        self.prepared = True
        self.save()

    def prestart(self):
        command = profile_command(self.args, self.root)
        if command is None:
            self.status['profileHook'] = 'plugin-absent'
            self.save()
            return False
        self.status.update(state='prelaunch', profileHook='running')
        self.save()
        # The retained daemon lease ensures no server JVM is running during this hook.
        result = subprocess.run(command, cwd=self.root, stdout=self.log, stderr=self.log,
                                **self.process_flags())
        self.status['profileHookExitCode'] = result.returncode
        self.status['profileHook'] = 'passed' if result.returncode == 0 else 'failed'
        self.save()
        if result.returncode != 0:
            raise RuntimeError('Managed profile prelaunch refused')
        return True

    @staticmethod
    def process_flags():
        return {'creationflags': subprocess.CREATE_NO_WINDOW | subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == 'nt' else {}

    def boot(self, run_id, reason, restart_id=None):
        self.stopping = None
        self.stop_due = None
        self.status.update(runId=run_id, currentRunId=run_id, javaPid=None, state='starting',
                           paperReady=False, dashboardReady=False, startedUtc=utc(),
                           startupReason=reason, restartRequestId=restart_id,
                           requestId=restart_id, requestedCommand='restart' if restart_id else None, exitCode=None,
                           restartRequestStatus='restarting' if restart_id else None,
                           profileHookExitCode=None,
                           bootNumber=self.status['bootNumber'] + 1)
        if reason != 'crash-recovery':
            self.status['recovery'] = None
        if reason != 'requested-restart':
            self.status.pop('restartFromRunId', None)
        self.save()
        hook = self.prestart()
        if self.interrupted:
            raise RuntimeError('Launcher shutdown requested during prelaunch')
        self.write_log(('\n--- MCC launcher boot ' + run_id + ' at ' + utc() + ' ---\n').encode('utf-8'))
        self.process = subprocess.Popen(server_command(self.args, self.root, self.control, run_id, hook),
                                        cwd=self.root, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                        stderr=subprocess.STDOUT, bufsize=0, **self.process_flags())
        self.status.update(javaPid=self.process.pid, state='starting')
        self.reader = threading.Thread(target=self.drain, args=(self.process, run_id), name='MCC-launcher-output', daemon=True)
        self.reader.start()
        self.save()

    def drain(self, process, run_id):
        try:
            for raw in iter(lambda: process.stdout.readline(16384), b''):
                try:
                    self.write_log(raw)
                except Exception:
                    self.interrupted = True
                text = raw.decode('utf-8', errors='replace')
                with self.mutex:
                    if self.status['runId'] != run_id:
                        continue
                    changed = False
                    if 'Done (' in text and 'For help' in text:
                        self.status['paperReady'] = True
                        self.status['readyUtc'] = utc()
                        changed = True
                    if 'REST API: Ready' in text:
                        self.status['dashboardReady'] = True
                        changed = True
                    if changed:
                        if self.status['paperReady'] and (self.status['dashboardReady'] or self.status.get('profileHook') == 'plugin-absent') and self.stopping is None:
                            self.status['state'] = 'running'
                            if self.status.get('restartRequestId'):
                                self.status['restartRequestStatus'] = 'completed'
                        try:
                            self.save()
                        except Exception:
                            self.interrupted = True
        except BaseException:
            self.interrupted = True  # A failed output/status reader requests normal stop, not recovery.

    def poll_commands(self):
        path = self.control / 'commands.ndjson'
        info = ordinary(path)
        if (info.st_dev, info.st_ino) != self.queue_identity or info.st_size < self.queue.position or info.st_size > MAX_QUEUE_BYTES:
            raise RuntimeError('Launcher command file identity or size changed')
        try:
            fd = open_regular(path, os.O_RDONLY)
            with os.fdopen(fd, 'rb') as stream:
                opened = os.fstat(stream.fileno())
                if (opened.st_dev, opened.st_ino) != self.queue_identity:
                    raise RuntimeError('Launcher command file was replaced')
                for line in self.queue.read(stream):
                    item = decode_command(line, self.status['runId'])
                    if item is not None:
                        self.accept(item, time.monotonic())
        except PermissionError:
            # Java briefly holds a mandatory FileLock while appending on Windows.
            # Retry next poll; never stop a server merely because its request writer is active.
            return

    def accept(self, item, now):
        if self.stopping is not None:
            return False  # One stop/restart per current Java identity.
        if self.status['state'] != 'running' or not self.status['paperReady'] or not self.status['dashboardReady']:
            # A terminal stop remains valid during startup; restart requires ready plugin delivery.
            if item['command'] != 'stop':
                return False
        self.stopping = item
        self.stop_due = now + RESTART_RESPONSE_DELAY
        self.status.update(state='restart-requested' if item['command'] == 'restart' else 'stop-requested',
                           requestId=item['requestId'], requestedCommand=item['command'], requestedUtc=utc())
        if item['command'] == 'restart':
            self.status.update(restartRequestId=item['requestId'], restartRequestStatus='stopping')
        self.save()
        return True

    def normal_stop(self):
        if self.process is not None and self.process.poll() is None:
            self.status['state'] = 'stopping'
            try:
                self.save()
            except Exception:
                pass  # Native stop must still be sent when status delivery itself failed.
            try:
                self.process.stdin.write(b'stop\n')
                self.process.stdin.flush()
            except (BrokenPipeError, OSError):
                pass
        self.stop_due = None

    def wait_exit(self):
        code = self.process.wait()
        self.reader.join(timeout=10)
        if self.reader.is_alive():
            raise RuntimeError('Server output did not close after exit')
        self.process.stdout.close()
        self.process.stdin.close()
        return code

    def run(self):
        try:
            self.prepare()
            self.boot(self.args.run_id, 'initial')
            while True:
                while self.process.poll() is None:
                    self.poll_commands()
                    if self.interrupted and self.stopping is None:
                        self.stopping = {'command': 'stop', 'requestId': None}
                        self.stop_due = time.monotonic()
                        self.status['stopReason'] = 'launcher-shutdown'
                    if self.stop_due is not None and time.monotonic() >= self.stop_due:
                        self.normal_stop()
                    time.sleep(0.1)
                code = self.wait_exit()
                previous_run = self.status['runId']
                self.status.update(exitCode=code, paperReady=False, dashboardReady=False,
                                   state='stopped' if code == 0 else 'failed', stoppedUtc=utc())
                if self.stopping is not None and self.stopping['command'] == 'restart' and code != 0:
                    self.status['restartRequestStatus'] = 'failed'
                self.save()
                if self.stopping is not None:
                    if self.stopping['command'] == 'restart' and code == 0 and not self.interrupted:
                        request_id = self.stopping['requestId']
                        self.status['restartFromRunId'] = previous_run
                        self.boot(str(uuid.uuid4()), 'requested-restart', request_id)
                        continue
                    return code  # Requested stop is terminal; abnormal requested restart never retries.
                if code == 0 or self.interrupted:
                    return code
                delay = self.crashes.delay(time.monotonic())
                if delay is None:
                    self.status.update(state='failed', recoveryExhausted=True)
                    self.save()
                    return code
                self.status.update(state='recovering', startupReason='crash-recovery', restartRequestId=None,
                                   recovery={'kind': 'unexpected-exit', 'attempt': len(self.crashes.failures),
                                             'delaySeconds': delay, 'previousRunId': previous_run, 'exitCode': code})
                self.save()
                until = time.monotonic() + delay
                while time.monotonic() < until and not self.interrupted:
                    self.poll_commands()
                    if self.stopping is not None:
                        self.status.update(state='stopped', recoveryCancelled=True, stoppedUtc=utc())
                        self.save()
                        return 0  # Stop during recovery cannot create another Java process.
                    time.sleep(0.1)
                if self.interrupted:
                    self.status.update(state='stopped', recoveryCancelled=True, stopReason='launcher-shutdown', stoppedUtc=utc())
                    self.save()
                    return code
                self.boot(str(uuid.uuid4()), 'crash-recovery')
        except BaseException as error:
            if self.process is not None and self.process.poll() is None:
                try:
                    self.normal_stop()
                finally:
                    self.process.wait()  # Save worlds; never kill a server that has not exited normally.
            self.status.update(state='failed', paperReady=False, dashboardReady=False,
                               error=type(error).__name__, stoppedUtc=utc())
            if self.status.get('restartRequestId') or self.stopping is not None and self.stopping['command'] == 'restart':
                self.status['restartRequestStatus'] = 'failed'
            if self.prepared and self.lease is not None:
                try:
                    self.save()
                except Exception:
                    pass
            print('MCC launcher refused or failed: ' + type(error).__name__, file=sys.stderr)
            return 1
        finally:
            if self.reader is not None:
                self.reader.join(timeout=10)
            if self.log is not None:
                self.log.close()
            if self.lease is not None:
                self.lease.close()


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', required=True)
    parser.add_argument('--server-root', default=str(Path(__file__).resolve().parent))
    parser.add_argument('--server-jar', default='paper.jar')
    parser.add_argument('--memory-mib', type=int, default=1536)
    parser.add_argument('--min-memory-mib', type=int, default=512)
    parser.add_argument('--run-id', default=str(uuid.uuid4()))
    parser.add_argument('--kernel-name', default='Paper')
    parser.add_argument('--minecraft-version', default='26.3')
    parser.add_argument('--jvm-arg', action='append', default=[])
    parser.add_argument('--max-crash-restarts', type=int, choices=range(4), default=3)
    args = parser.parse_args(argv)
    try:
        if not 512 <= args.memory_mib <= 32768 or not 64 <= args.min_memory_mib <= args.memory_mib:
            raise ValueError('Heap range is invalid')
        if not canonical_uuid(args.run_id):
            raise ValueError('Canonical run UUID required')
        jar = Path(args.server_jar)
        if jar.is_absolute() or '..' in jar.parts or len(jar.parts) != 1 or jar.suffix.lower() != '.jar':
            raise ValueError('Server jar must be a jar filename inside server-root')
        if not args.kernel_name or len(args.kernel_name) > 32 or any(not (c.isalnum() or c in '_.-') for c in args.kernel_name):
            raise ValueError('Invalid kernel name')
        if not args.minecraft_version or len(args.minecraft_version) > 32 or any(not (c.isalnum() or c in '_.-') for c in args.minecraft_version):
            raise ValueError('Invalid Minecraft version')
        args.jvm_arg = validate_jvm_arguments(args.jvm_arg)
    except (ValueError, AttributeError) as error:
        parser.error(str(error))
    return args


def main(argv=None):
    args = parse_args(argv)
    try:
        supervisor = Supervisor(args)
    except Exception as error:
        print('MCC launcher refused: ' + type(error).__name__, file=sys.stderr)
        return 1
    def terminate(_signum, _frame):
        supervisor.interrupted = True
    signal.signal(signal.SIGTERM, terminate)
    signal.signal(signal.SIGINT, terminate)
    return supervisor.run()


if __name__ == '__main__':
    raise SystemExit(main())
