"""Pure launcher protocol/lifecycle tests. Never starts Java or modifies a server."""
import copy
import importlib.util
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import uuid

SOURCE = Path(__file__).resolve().parents[1] / 'Server-Supervisor.py'
SPEC = importlib.util.spec_from_file_location('mcc_supervisor', SOURCE)
launcher = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(launcher)
RUN = '00000000-0000-4000-8000-000000000001'
REQUEST = '00000000-0000-4000-8000-000000000002'


def args(*extra):
    return launcher.parse_args(['--java', 'java', '--server-root', str(SOURCE.parent), '--run-id', RUN, *extra])


def record(command='restart', run=RUN, request=REQUEST):
    return (json.dumps({'command': command, 'runId': run, 'requestId': request}) + '\n').encode()


class Clock:
    def __init__(self):
        self.now = 0.0

    def monotonic(self):
        return self.now

    def sleep(self, seconds):
        self.now += seconds


class FakeLease:
    def __init__(self):
        self.closed = 0

    def close(self):
        self.closed += 1


class FakeProcess:
    def __init__(self, exit_after_stop=0, immediate_exit=None):
        self.code = immediate_exit
        self.exit_after_stop = exit_after_stop
        self.writes = []
        self.pid = 99
        self.stdin = self

    def poll(self):
        return self.code

    def wait(self):
        if self.code is None:
            raise AssertionError('A native stop must precede waiting on this mock')
        return self.code

    def write(self, data):
        self.writes.append(data)
        self.code = self.exit_after_stop

    def flush(self):
        pass


class FakeSupervisor(launcher.Supervisor):
    """Runs the real state machine; only native processes/files are replaced."""
    def __init__(self, modes, commands, clock, maximum=3):
        super().__init__(args('--max-crash-restarts', str(maximum)))
        self.modes = modes
        self.commands = commands
        self.clock = clock
        self.boots = []
        self.states = []
        self.sent = set()
        self.mock_lease = FakeLease()
        self.stop_times = []

    def prepare(self):
        self.lease = self.mock_lease
        self.prepared = True

    def save(self):
        if self.lease is not None:
            if self.lease.closed:
                raise AssertionError('Lease released during a boot or transition')
        self.states.append(copy.deepcopy(self.status))

    def boot(self, run_id, reason, restart_id=None):
        self.stopping = self.stop_due = None
        self.status.update(runId=run_id, currentRunId=run_id, state='running', javaPid=99,
                           paperReady=True, dashboardReady=True, startupReason=reason,
                           requestId=restart_id, restartRequestId=restart_id,
                           restartRequestStatus='completed' if restart_id else None,
                           bootNumber=self.status['bootNumber'] + 1)
        mode = self.modes[len(self.boots)]
        self.process = FakeProcess(mode[1], mode[0])
        self.boots.append((run_id, reason, restart_id, self.process,
                           launcher.server_command(self.args, self.root, self.control, run_id, True)))
        self.save()

    def poll_commands(self):
        key = (len(self.boots), self.status['state'])
        if key in self.commands and key not in self.sent:
            self.sent.add(key)
            item = launcher.decode_command(record(self.commands[key], self.status['runId']), self.status['runId'])
            self.accept(item, self.clock.monotonic())

    def wait_exit(self):
        return self.process.wait()

    def normal_stop(self):
        self.stop_times.append(self.clock.monotonic())
        super().normal_stop()


class ProtocolTests(unittest.TestCase):
    def test_only_exact_stop_restart_with_canonical_ids(self):
        for command in ('stop', 'restart'):
            self.assertEqual(command, launcher.decode_command(record(command), RUN)['command'])
        cases = [record('say'), record(run=str(uuid.uuid4())), record(request=REQUEST.upper().replace('0', 'A', 1)),
                 record(request='1-1-1-1-1'), record()[:-1], b'[]\n', b'\xff\n',
                 b'{"command":"stop","command":"restart","runId":"' + RUN.encode() +
                 b'","requestId":"' + REQUEST.encode() + b'"}\n',
                 record().replace(b'"restart"', b'"restart", "console":"op someone"')]
        for line in cases:
            with self.subTest(line=line[:32]):
                self.assertIsNone(launcher.decode_command(line, RUN))

    def test_oversized_and_deep_json_records_are_rejected(self):
        self.assertIsNone(launcher.decode_command(b' ' * 4096 + b'\n', RUN))
        self.assertIsNone(launcher.decode_command(b'[' * 1800 + b'0' + b']' * 1800 + b'\n', RUN))

    def test_partial_record_waits_then_delivers_once(self):
        reader = launcher.QueueReader()
        complete = record()
        self.assertEqual([], reader.read(io.BytesIO(complete[:-1])))
        self.assertEqual(0, reader.position)
        self.assertEqual([complete], reader.read(io.BytesIO(complete)))
        self.assertEqual([], reader.read(io.BytesIO(complete)))

    def test_oversize_discard_cannot_become_a_suffix_command(self):
        reader = launcher.QueueReader()
        first = b'x' * 9000
        self.assertEqual([], reader.read(io.BytesIO(first)))
        self.assertTrue(reader.discarding)
        final = first + record() + record('stop')
        self.assertEqual([record('stop')], reader.read(io.BytesIO(final)))
        self.assertFalse(reader.discarding)

    def test_queue_is_batched_without_losing_records(self):
        reader = launcher.QueueReader()
        source = record() * 65
        self.assertEqual(64, len(reader.read(io.BytesIO(source))))
        self.assertEqual([record()], reader.read(io.BytesIO(source)))
        self.assertEqual(len(source), reader.position)

    def test_crash_budget_backoff_and_rolling_window(self):
        policy = launcher.CrashPolicy()
        self.assertEqual([2, 10, 30, None], [policy.delay(i) for i in range(4)])
        self.assertEqual(2, policy.delay(603))
        self.assertIsNone(launcher.CrashPolicy(0).delay(0))


class ArgumentsTests(unittest.TestCase):
    def test_heap_flags_managed_properties_and_same_jvm_options(self):
        options = args('--server-jar', 'spigot.jar', '--memory-mib', '2048',
                       '--jvm-arg=-XX:+UnlockDiagnosticVMOptions', '--jvm-arg=-XX:-G1UseTimeBasedHeapSizing')
        root = SOURCE.parent.resolve()
        command = launcher.server_command(options, root, root / '.mcc-launcher', RUN, True)
        self.assertIn('-Xmx2048M', command)
        self.assertIn('-Dmcc.launcher.run-id=' + RUN, command)
        self.assertIn('-Dmcc.launcher.control=' + str(root / '.mcc-launcher'), command)
        self.assertIn('-Dmcc.launcher.profile-hook=true', command)
        self.assertEqual(['-jar', str(root / 'spigot.jar'), '--nogui', '--nojline'], command[-4:])
        self.assertIn('-XX:-G1UseTimeBasedHeapSizing', command)
        self.assertNotIn('-Dmcc.launcher.profile-hook=true', launcher.server_command(options, root, root / '.mcc-launcher', RUN, False))

    def test_untrusted_cli_cannot_override_heap_run_or_jar_scope(self):
        invalid = [('--server-jar', '../outside.jar'), ('--server-jar', '/outside.jar'),
                   ('--server-jar', 'nested/server.jar'), ('--server-jar', 'paper.zip'),
                   ('--memory-mib', '511'), ('--memory-mib', '33000'), ('--min-memory-mib', '2000'),
                   ('--run-id', '1-1-1-1-1'), ('--kernel-name', 'Paper\nexit'),
                   ('--jvm-arg=-Dmcc.launcher.run-id=other',), ('--jvm-arg=-Xmx5G',),
                   ('--jvm-arg=-jar',), ('--max-crash-restarts', '4')]
        for value in invalid:
            with self.subTest(value=value), patch('sys.stderr', io.StringIO()):
                with self.assertRaises(SystemExit):
                    args(*value)

    def test_profile_hook_is_exact_and_absence_is_not_invented_success(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self.assertIsNone(launcher.profile_command(args(), root))
            (root / 'plugins').mkdir()
            (root / 'plugins' / 'MinecraftControlCenter.jar').write_bytes(b'disposable-test')
            command = launcher.profile_command(args('--kernel-name', 'Spigot', '--minecraft-version', '26.3'), root)
            self.assertEqual('dev.mcc.server.ProfileBootstrap', command[5])
            self.assertEqual(['--offline', '--server-root', str(root), '--plugin-data',
                              str(root / 'plugins' / 'MinecraftControlCenter'), '--kernel', 'Spigot',
                              '--minecraft-version', '26.3'], command[6:])

    def test_prior_java_liveness_blocks_orphans_without_overwriting_status(self):
        with tempfile.TemporaryDirectory() as temporary:
            control = Path(temporary)
            status = control / 'status.json'
            raw = b'{"javaPid":123,"state":"running"}'
            status.write_bytes(raw)
            with patch.object(launcher, 'process_alive', return_value=True) as probe:
                with self.assertRaises(RuntimeError):
                    launcher.prior_java_offline(control)
                probe.assert_called_once_with(123)
            self.assertEqual(raw, status.read_bytes())
            with patch.object(launcher, 'process_alive', return_value=False):
                launcher.prior_java_offline(control)

    def test_prior_status_rejects_malformed_pid_duplicate_keys_and_oversize(self):
        with tempfile.TemporaryDirectory() as temporary:
            control = Path(temporary)
            for data in (b'{"javaPid":true}', b'{"javaPid":-1}', b'{"javaPid":"123"}',
                         b'{"javaPid":1,"javaPid":null}', b'[]', b'x' * 16385):
                (control / 'status.json').write_bytes(data)
                with self.subTest(data=data[:32]), patch.object(launcher, 'process_alive') as probe:
                    with self.assertRaises(RuntimeError):
                        launcher.prior_java_offline(control)
                    probe.assert_not_called()
            (control / 'status.json').write_bytes(b'{"javaPid":null,"state":"stopped"}')
            with patch.object(launcher, 'process_alive') as probe:
                launcher.prior_java_offline(control)
                probe.assert_not_called()


class LifecycleTests(unittest.TestCase):
    def execute(self, supervisor, clock):
        with patch.object(launcher.time, 'monotonic', clock.monotonic), patch.object(launcher.time, 'sleep', clock.sleep):
            return supervisor.run()

    def test_requested_restart_stops_then_new_identity_same_options_retained_lease(self):
        clock = Clock()
        supervisor = FakeSupervisor([(None, 0), (None, 0)], {(1, 'running'): 'restart', (2, 'running'): 'stop'}, clock)
        self.assertEqual(0, self.execute(supervisor, clock))
        self.assertEqual(2, len(supervisor.boots))
        before, after = supervisor.boots
        self.assertNotEqual(before[0], after[0])
        self.assertEqual(('requested-restart', REQUEST), after[1:3])
        self.assertEqual([b'stop\n'], before[3].writes)
        self.assertEqual([b'stop\n'], after[3].writes)
        self.assertGreaterEqual(supervisor.stop_times[0], launcher.RESTART_RESPONSE_DELAY)
        self.assertEqual(1, supervisor.mock_lease.closed)
        self.assertEqual([item for item in before[4] if not item.startswith('-Dmcc.launcher.run-id=')],
                         [item for item in after[4] if not item.startswith('-Dmcc.launcher.run-id=')])

    def test_abnormal_requested_restart_does_not_recover(self):
        clock = Clock()
        supervisor = FakeSupervisor([(None, 1)], {(1, 'running'): 'restart'}, clock)
        self.assertEqual(1, self.execute(supervisor, clock))
        self.assertEqual(1, len(supervisor.boots))
        self.assertEqual('failed', supervisor.status['restartRequestStatus'])

    def test_unexpected_exit_has_only_three_new_boots_and_distinct_reason(self):
        clock = Clock()
        supervisor = FakeSupervisor([(1, 0)] * 4, {}, clock)
        self.assertEqual(1, self.execute(supervisor, clock))
        self.assertEqual(4, len(supervisor.boots))
        self.assertEqual(4, len({boot[0] for boot in supervisor.boots}))
        self.assertEqual(['initial'] + ['crash-recovery'] * 3, [boot[1] for boot in supervisor.boots])
        self.assertTrue(supervisor.status['recoveryExhausted'])
        self.assertTrue(all(boot[2] is None for boot in supervisor.boots))
        self.assertGreaterEqual(clock.now, sum(launcher.CRASH_DELAYS))
        self.assertEqual(1, supervisor.mock_lease.closed)

    def test_stop_during_recovery_is_terminal(self):
        clock = Clock()
        supervisor = FakeSupervisor([(1, 0)], {(1, 'recovering'): 'stop'}, clock)
        self.assertEqual(0, self.execute(supervisor, clock))
        self.assertEqual(1, len(supervisor.boots))
        self.assertTrue(supervisor.status['recoveryCancelled'])
        self.assertEqual('stopped', supervisor.status['state'])

    def test_normal_unrequested_exit_and_disabled_recovery_do_not_restart(self):
        for code, maximum in ((0, 3), (1, 0)):
            clock = Clock()
            supervisor = FakeSupervisor([(code, 0)], {}, clock, maximum)
            self.assertEqual(code, self.execute(supervisor, clock))
            self.assertEqual(1, len(supervisor.boots))

    def test_status_failure_cannot_prevent_native_stop(self):
        supervisor = launcher.Supervisor(args())
        supervisor.process = FakeProcess()
        with patch.object(supervisor, 'save', side_effect=OSError('mock status unavailable')):
            supervisor.normal_stop()
        self.assertEqual([b'stop\n'], supervisor.process.writes)

    def test_readiness_echoes_request_only_when_both_services_are_ready(self):
        supervisor = launcher.Supervisor(args())
        supervisor.status.update(restartRequestId=REQUEST, restartRequestStatus='restarting', profileHook='passed')
        observed = []
        process = SimpleNamespace(stdout=io.BytesIO(b'Done (1.2s)! For help, type help\nREST API: Ready\n'))
        with patch.object(supervisor, 'write_log'), patch.object(supervisor, 'save', side_effect=lambda: observed.append(copy.deepcopy(supervisor.status))):
            supervisor.drain(process, RUN)
        self.assertFalse(observed[0]['dashboardReady'])
        self.assertEqual('starting', observed[0]['state'])
        self.assertEqual('running', observed[1]['state'])
        self.assertEqual('completed', observed[1]['restartRequestStatus'])

    def test_temporary_writer_lock_is_retried_without_changing_state(self):
        supervisor = launcher.Supervisor(args())
        supervisor.queue_identity = (1, 2)
        supervisor.queue = launcher.QueueReader()
        with patch.object(launcher, 'ordinary', return_value=SimpleNamespace(st_dev=1, st_ino=2, st_size=0)), \
                patch.object(launcher, 'open_regular', side_effect=PermissionError('mock active writer lock')):
            supervisor.poll_commands()
        self.assertIsNone(supervisor.stopping)
        self.assertEqual('starting', supervisor.status['state'])


if __name__ == '__main__':
    unittest.main()
