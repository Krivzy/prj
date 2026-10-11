# Portable server supervisor

`scripts/Server-Supervisor.py` is a Python 3 launcher for a local Minecraft server. It supports normal stop, requested restart and limited recovery after an unexpected nonzero Java exit. The script is portable source; it is not installed automatically by the plugin. Replacing an existing production launcher requires a normal stop, verified backup and explicit deployment approval.

## Starting a managed server

Run the launcher with the server owner's account, an explicit server directory and a supported Java executable. The server JAR must already exist inside that directory, and `eula.txt` must already contain `eula=true`.

```sh
python3 scripts/Server-Supervisor.py --java /path/to/java --server-root /path/to/server --server-jar paper.jar --memory-mib 1536 --kernel-name Paper --minecraft-version 26.3
```

PowerShell uses the equivalent argument list with its Python executable and local paths. Defaults preserve the previous `--java`, `--memory-mib` and `--run-id` interface; use `--server-root` explicitly for a checkout whose scripts directory is not the server directory. `--server-jar` accepts one `.jar` filename. Heap limits are owned by the launcher: minimum defaults to 512 MiB, maximum to 1536 MiB. `--min-memory-mib` changes the minimum. Maximum accepted heap is 32768 MiB.

Repeat `--jvm-arg=...` for reviewed JVM options. These options remain identical across profile hooks and server boots, except that the hook uses a smaller heap. Launcher control/run/profile properties and heap overrides cannot be supplied through this option. For a Microsoft JDK affected by the documented G1 issue, pass the separately verified mitigation explicitly:

```sh
--jvm-arg=-XX:+UnlockDiagnosticVMOptions --jvm-arg=-XX:-G1UseTimeBasedHeapSizing
```

The launcher does not assume every vendor/version supports those diagnostic flags, change the collector or claim that they fix CPU load. See [JVM mitigation evidence and limits](JVM-G1-MITIGATION.md).

## Retained lease and profile hook

The launcher holds `.mcc-launcher/daemon.lock` across the entire daemon lifetime, including every Java exit, profile hook and subsequent boot. Windows uses byte-zero `msvcrt` locking; Unix uses `flock`. An offline deployment tool must acquire the same lock using the matching platform mechanism. Java file locks used elsewhere are not automatically interchangeable with Unix `flock`.

Before its first boot, it also checks the previous status file's Java PID. A live or inaccessible PID blocks startup, even if the old daemon lease was released. A recycled PID may require manual inspection. This guard does not forcibly stop any process and cannot prove ownership of an arbitrary PID. A daemon killed between creating its child and publishing the child's PID still requires external process inspection before recovery.

Before every managed Java boot, if `plugins/MinecraftControlCenter.jar` exists, the launcher runs:

```sh
java -cp plugins/MinecraftControlCenter.jar dev.mcc.server.ProfileBootstrap --offline --server-root /path/to/server --plugin-data /path/to/server/plugins/MinecraftControlCenter --kernel Paper --minecraft-version 26.3
```

The actual executable and kernel/version come from the launcher arguments. The hook runs under the retained offline lease and must exit zero. A failure blocks the server boot. There is no forced hook timeout: profile writes must finish or be resolved manually. A successful hook adds `-Dmcc.launcher.profile-hook=true` to the server JVM. If the MCC JAR is absent, the hook is skipped and this property is omitted; absence is not presented as successful profile application.

## Protocol 2

The launcher passes these system properties to the server:

```text
mcc.launcher.control=<absolute server directory>/.mcc-launcher
mcc.launcher.run-id=<canonical UUID of this Java boot>
```

`status.json` is atomically replaced and contains `protocolVersion:2`, the real `daemonPid`, current `javaPid`, equal `runId`/`currentRunId`, readiness, lifecycle state, startup reason and request status. The plugin must verify the current Java PID, actual parent daemon PID and exact run ID before accepting launcher control. Panel restart requires both kernel and MCC API readiness. Readiness is derived from the supported kernel's normal ready line and MCC's `REST API: Ready` line; a kernel with different messages requires an adapter before it can advertise panel restart.

The plugin appends one UTF-8 JSON line to `commands.ndjson`, using a file lock while writing:

```json
{"command":"restart","runId":"current-canonical-UUID","requestId":"request-canonical-UUID"}
```

Only `stop` and `restart` are allowed. Each complete line is at most 4096 bytes including newline. Duplicate/extra JSON keys, invalid UTF-8, oversized records, incomplete records and stale/noncanonical identifiers are rejected. A partial line waits for completion; an oversized line is discarded through its newline. The reader handles at most 64 records per poll. The command history budget is approximately 1 MiB; archive it while offline before it exceeds the budget. Existing records are skipped when a new daemon starts. The control directory and command-file identity must remain unchanged.

One request is accepted per Java identity. The accepted `requestId` and, for restart, `restartRequestId` are echoed in status. After acceptance, the launcher waits at least one second so the HTTP 202 response can be delivered, then writes the native `stop` command. It waits for Java to exit normally and for output to close. It never uses a forced kill. A requested restart proceeds only after exit zero and gives the next Java a fresh run UUID, the same heap/JVM arguments and `startupReason:requested-restart`. The request remains associated with that boot; `restartRequestStatus` becomes `completed` only after both services are ready. A nonzero requested stop/restart is terminal and does not trigger automatic recovery.

An unexpected nonzero exit permits at most three recovery boots in a rolling ten-minute window, with delays of 2, 10 and 30 seconds. Recovery is marked separately as `startupReason:crash-recovery` with its cause and attempt count, rather than inventing a panel restart request. The fourth failure is terminal. `--max-crash-restarts 0` disables automatic recovery. An unrequested exit zero, an accepted stop, or a stop received during recovery never launches a new Java process. SIGINT/SIGTERM requests normal shutdown; a server that refuses normal stop requires manual intervention.

## Permissions, logs and verification

The private control directory is restricted to its owner on Unix and the current Windows user plus SYSTEM on Windows. Symlinks/reparse points and nonregular control files are refused. Server output is preserved in the private append-only console log across boots. Logs and control state may contain operational data; they must not be published as source or embedded in public reports.

Run the pure regression suite from the repository:

```sh
python3 -B scripts/tests/test_server_supervisor.py
```

The suite exercises command validation, bounded queues, startup arguments, prior-PID guards and the actual supervisor state machine with mocked processes, clocks and leases. It starts no Java/server process and changes no production files. This is not native Windows/Unix lock interoperability evidence, a server restart proof or a stability soak. An isolated native fixture must verify the launcher, profile hook, ready lines and panel restart before deployment.
