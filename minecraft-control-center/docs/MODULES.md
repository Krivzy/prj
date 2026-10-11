# Optional MCC work modules

MCC 0.8 persists twelve independent boolean flags in its existing runtime-settings document. Defaults are true; existing settings with four flags retain those choices and receive safe defaults for the new flags. Admin/Owner uses `GET/POST /api/settings`; ordinary users cannot change lifecycle policy. Validation accepts only known keys and booleans. Module switches do not run `/reload`, replace a kernel or disable third-party plugins.

| Key | Optional work and state affected by OFF |
| --- | --- |
| `chat` | Stops panel chat capture and clears panel history; chat routes/topics and panel chat actions reject work. Minecraft chat still operates. |
| `events` | Closes the owned event engine, cancels its bounded active event work and releases event caches. Event catalog/start routes stop; `event.stop` remains a core cleanup action. |
| `automations` | Disables graph admission, cancels old runs/signals and closes owned high-frequency listeners. Persistent profiles/rules remain; enabling recreates listeners. Lifecycle epochs reject a signal polled before OFF even after ON. |
| `map` | Pauses saved-region scan/PNG work and viewer routes. Persistent surface/PNG files and world metadata remain for reuse; the lightweight worker exists until plugin shutdown. Viewing never requests Bukkit chunk loading. |
| `oreMonitor` | Stops ore listener/sampling, clears transient placement/snapshot state and drains already accepted durable deltas. The dedicated store closes asynchronously; ON waits for the old close before opening again. OFF at startup does not create/open its SQLite store. |
| `integrations` | Rejects optional discovery/query/mutation, cancels queued work and clears permission caches. Idle workers may time out. Lightweight external punishment presence remains enabled. |
| `metrics` | Skips optional host CPU/RAM/disk measurement, metric-history writes and loaded-chunk enumeration; removes metric fields from cached/published state. Core readiness/tick safety continues. |
| `playerSnapshots` | Stops periodic player/inventory/inspector capture, clears pending inventory observations and gates panel player routes/actions/topics. It also stops only the map's player overlay; the base PNG map follows `map` independently. |
| `entitySnapshots` | Stops periodic entity enumeration and pending panel spawn jobs. It gates panel entity/spawn actions plus game item/effect/sound/mob catalogs (`/api/catalogs`); the UI calls this **Entities and game catalogs**. |
| `timeline` | Stops dashboard audit reading/live delivery. Required durable security/change audit remains active. |
| `catalog` | Closes optional plugin marketplace search/installation-plan workers and caches. Installed third-party plugins continue running. |
| `console` | Releases the optional dashboard log tail/history and gates panel console commands. The kernel's own console/logging remains active. |

Observation modules are not global permissions that prohibit every internal game action. An enabled automation can still execute its typed player/entity action while those observations are OFF; this does not reactivate periodic capture. The panel's HTTP controls and dependent pages follow their module flags. Disabling the automation module stops that graph work itself.

## Core work and external ownership

Authentication/CSRF/RBAC, health, settings, private backups, retention cleanup, confirmed-change audit and external punishment ownership remain core. `/api/punishments/provider` remains available. When integrations are OFF and a known punishment provider is enabled, provider ownership is unavailable to panel tools while builtin moderation remains blocked. Disabling optional integrations never selects builtin as a fallback. Existing queued native punishment callbacks still recheck the independent presence signal on the tick thread.

## API, WebSocket and browser lifecycle

Disabled optional routes reject with HTTP 503. Policy is checked before admission and again after asynchronous read completion; normal cleanup and auth errors retain their existing status. HTTP action gates operate independently of navigation visibility.

WebSocket module changes remove blocked subscriptions and queued payloads, send the actual remaining subscription list and reject re-subscription. Optional events are not encoded without interested clients. An epoch is checked after JSON encoding and again under the client queue lock, so a state captured before OFF cannot be queued after the transition. A network frame already being sent cannot be retroactively recalled. Core `state`, `world` and `punishments` subscriptions remain; expensive fields in `state` are scrubbed.

The browser removes dependent navigation/pages immediately, stops their polling and clears relevant live caches. State responses are committed only at the accepted timestamp/lifecycle epoch. Delayed player responses cannot republish an old snapshot. Chat/console history also checks module and connection epochs, so OFF→ON/reconnect cannot restore stale history. Giving an item without the game catalog requires a manually typed ID; catalog-dependent controls are disabled with an explanation.

## Measurements and limits

`/api/settings.performance` exposes actual snapshot/action/HTTP/queue/publication counters and durations. A local equivalent workload can compare enabled and disabled task/encode/queue counts. CPU and RAM values belong to the entire JVM, including the kernel and other plugins; they are not MCC-only measurements or an A/B RAM guarantee. Turning a module OFF releases or pauses its owned work as described above, rather than promising immediate garbage collection.

The mandatory snapshot/cleanup task remains. Existing bounded queues, retention and per-tick limits continue to apply. Saved image metadata grows with saved records and its disk cache is retained; OFF is not cache deletion. Kernel presets and MCC polling profiles are separate: `eco/balanced/performance` change MCC snapshot cadence, while `maximum-optimized` changes a typed kernel allowlist and preserves distances.

## Checks

The coordinated 0.8 candidate passed 269 Java, 78 Node and 11 Python tests, TypeScript and bundle/JAR checks. Meaningful regressions include cached deep metric scrubbing, zero encoding/publication for 20,000 disabled/unobserved signals, OFF during a blocked state encoder, old accepted snapshot/player-response races, stale chat/console history across OFF→ON, base map independence from player observations, ore transient cleanup/durable coverage and automation polled-signal epochs. Isolated native Paper 26.3 build 169 and Purpur 26.3 build 2647 both passed full profile/module/map suites, seven boots each and observed final normal exit 0 with owned processes and ports absent. Each core confirmed twelve module switches, sixteen disabled HTTP routes, nine removed WebSocket topics and complete Normal saved-image coverage of 138 FULL chunks/2021 saved records with coherence version 1 and zero MCC chunk loads/plugin tickets. Paper also confirmed that AdvancedBanZ ownership remains protected when optional integrations are OFF; the bare Purpur fixture correctly reported builtin only after verified absence of a known external provider.

Two actual dynamic HTTP state bodies measured 2812→2208 UTF-8 bytes on Paper and 2823→2203 on Purpur when optional work was disabled. These samples include changing timestamps/counters and do not establish an MCC-only CPU/RAM comparison. Root separately observed populated feature screens and a normal Overview UI restart. An external runtime interruption left the earlier Paper holder incomplete; its raw receipt remains historical and the complete Paper suite was repeated under a new owned supervisor. No old normal exit or database byte identity is claimed. No 0.8 deployment or new preset was applied to Main by these tests.

Primary references: [Bukkit plugin lifecycle](https://hub.spigotmc.org/javadocs/spigot/org/bukkit/plugin/java/JavaPlugin.html), [Bukkit scheduler](https://hub.spigotmc.org/javadocs/spigot/org/bukkit/scheduler/BukkitScheduler.html), [Paper configuration](https://docs.papermc.io/paper/reference/configuration/) and [Purpur 26.3 configuration implementation](https://github.com/PurpurMC/Purpur/blob/ver/26.3/purpur-server/src/main/java/org/purpurmc/purpur/PurpurConfig.java).
