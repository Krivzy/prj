# Ore mining observation

The Fair play page counts successfully broken ore **blocks** in 5-, 30- and 60-minute views, persisted all-time history and a global top five. All time means **since MCC observation first began**, not the lifetime of the Minecraft world. MCC does not reconstruct older mining from vanilla statistics.

This is passive observation. It never bans, kicks, cancels mining, changes blocks or certifies that a player is cheating or cheat-free. Owner and Admin may read the authenticated endpoints. Disabling the module unregisters the listener, cancels sampling and closes the active database worker.

## Counting and provenance

The main-thread listener observes native BlockBreakEvent at MONITOR without changing it. A bounded FIFO checks the event on a later server tick, checks late cancellation, requires Survival at the original event time and confirms that the original material has changed. It reads only a chunk already reported as loaded; it never requests chunk loading or tickets. Unloaded chunks and unverifiable changes are skipped and reported.

Diamond, gold, redstone, coal, lapis, emerald, iron and copper include deepslate variants. Nether quartz, Nether gold and ancient debris have separate counters: eleven families in total. One accepted break counts as one block. Fortune, drop quantities, XP, storage blocks, Creative, Spectator, Adventure, cancelled breaks and explosions do not add or multiply counts.

Successful native placements, including Creative placements, enter the same FIFO. A placement followed by a break in the same tick therefore excludes the known placed ore. The registry uses exact world UUID, coordinates and material, lasts only for the current enabled session, and is bounded to 65,536 positions and 24 hours. Expiration is incremental, at most 256 removals per snapshot; mining independently checks the exact TTL. A restart or disable/re-enable resets it. Older placements, expired/evicted records, pistons and third-party edits have unknown provenance. MCC cannot prove that every counted block was naturally generated.

## Windows, history and storage

The selected windows are exactly 300, 1800, 3600 seconds and ALL_TIME. Recent counts use original occurrence time aggregated into sparse player/second groups, with **one-second resolution** and an exclusive lower boundary. The last hour is retained in SQLite; no raw hour-long event list is kept in RAM.

A dedicated ore-history.sqlite stores eleven per-player totals, total ore blocks, recent groups and coverage metadata. Player identity is the UUID; current name/world metadata can change without splitting the total. All-time rows have no automatic expiration. The top five are ordered by the aggregate of all eleven families, then UUID for a deterministic tie break. Recent inactivity removes a player from recent views after an hour; all-time history and ranking remain.

A single background worker handles SQLite, transactions and paging. Main-thread observations only enter a bounded coalescing buffer. Every 100 ticks a flush is requested; low TPS increases this wall-clock interval. A transaction atomically updates recent counts, all-time totals and an idempotent batch sequence. A failed transaction retains the same bounded batch for retry, avoiding duplicate additions. Accepted buffer data is not described as durable until its transaction completes.

SQLite uses WAL and synchronous=FULL, a suggested 1 MiB page cache, disabled memory mapping and file-based temporary storage. These policies do not establish a total JVM/native memory ceiling. Normal disable/close requests a final flush. Plugin shutdown waits at most three seconds for confirmation; failed/unconfirmed completion is logged and exposed as incomplete coverage. A crash can lose the unflushed buffer; a durable active-session marker exposes possible loss on the next opening. It cannot recover or invent the exact missing count. Reopening after an observation gap reports that gap. Verification skips, queue drops and placement-capacity losses are persisted cumulatively, so a clean restart does not erase previous incomplete coverage. Backups must include this database and any live WAL/SHM sidecars; use a normal closed backup or SQLite backup API. See the [SQLite PRAGMA contract](https://www.sqlite.org/pragma.html).

| Resource | Default bound |
|---|---:|
| Deferred verification FIFO | 2,048 observations |
| Verification started per tick | 128, with approximately 0.75 ms start budget |
| Coalesced current buffer | 2,048 player/second groups |
| Retained retry batch | another 2,048 groups |
| Database worker / queue | 1 thread / 8 tasks, including reserved close capacity |
| Recent SQLite rows | 131,072 |
| Active players in cached snapshot | 256 |
| All-time page | 1–50 players; UI uses 12 |
| Global leaders | 5 |
| Known placed ore | 65,536 positions |

The time check bounds operations started, not the worst-case duration of one Bukkit read. Row/player truncation affects recent views and reports partial coverage; it does not erase all-time totals. Totals grow on disk with tracked UUIDs, while returned pages and RAM stay bounded. No CPU benchmark or uninterrupted durability guarantee is claimed.

## Five-minute pace indicators and research

The colour **always uses only the absolute count over the latest 300 seconds**, including when an hour or all-time view is selected. It does not extrapolate a short vein. Green means the configured threshold was not reached. Yellow and red prompt contextual inspection; they are not probabilities or verdicts.

Official sources do not establish a universal maximum number of ore blocks a legitimate player can mine in five minutes:

- [Mojang's 1.18 distribution notes](https://www.minecraft.net/en-us/article/caves---cliffs--part-ii-out-today-java) describe ore-specific depths, biome differences and large rare copper/iron veins. These are historical mechanics notes, not current measured rates.
- [Java 1.20.2 release notes](https://feedback.minecraft.net/hc/en-us/articles/19703470383757-Minecraft-Java-Edition-1-20-2) increased deep diamond generation. Generation changes invalidate fixed assumptions based on older versions.
- [Java 26.3 release notes](https://www.minecraft.net/en-us/article/minecraft-java-edition-26-3) document data-driven ore-vein density, richness and filler parameters; customized generation can change available ore.
- [Paper's block-break-speed contract](https://jd.papermc.io/paper/26.3/org/bukkit/block/Block.html#getBreakSpeed(org.bukkit.entity.Player)) accounts for tools, effects, water and enchantments. Sufficient progress can break a block in one tick; this is breaking progress, not a bound on finding ore.
- [Mojang's lapis article](https://www.minecraft.net/en-us/article/lapis-lazuli-ore) distinguishes multiple drops and Fortune from the single ore block. This monitor counts that block once.

The following are **engineering sensitivity defaults**, informed by those mechanics and the requested lower thresholds. They are not experimental percentile estimates or values published by Mojang. Rarer families have smaller thresholds; abundant ores and large-vein families have larger ones. Rich veins, efficient routes, Haste, Efficiency, exposed ores and custom generation can legitimately trigger them. Calibrate against your server and inspect context manually.

| Ore | Yellow / 5 min | Red / 5 min |
|---|---:|---:|
| Diamond | 24 | 64 |
| Gold | 72 | 192 |
| Redstone | 128 | 320 |
| Coal | 256 | 640 |
| Lapis | 32 | 80 |
| Emerald | 12 | 32 |
| Iron | 160 | 400 |
| Copper | 384 | 1,024 |
| Nether quartz | 256 | 640 |
| Nether gold | 160 | 400 |
| Ancient debris | 8 | 24 |

Every new default is lower than half its previous ten-minute threshold. Sensitivity is lower even after accounting for the shorter interval, rather than merely relabelling old numbers.

Configure ore-monitor.thresholds.<ore-id>.yellow/red before constructing the monitor. Missing values use the table above; explicit existing settings are retained. Yellow must be positive and red greater than yellow, at most 1,000,000. Player details expose applied thresholds. There is no browser threshold-editing action in this release.

## Lifecycle and API contract

Construct OreMonitor(plugin, configuration) on the server main thread. enabled(true) starts store/listener/task; enabled(false) cancels observation, clears volatile placement/snapshot data, and requests asynchronous final persistence. A fresh enable waits for the preceding store closure off-main. Disabled startup does not open a database. Call close() on the server main thread before shutdown.

GET /api/ore-monitor returns a published immutable cached DTO: trackingSince, windows=[300,1800,3600], windowResolutionSeconds=1, indicatorWindow=300, applied thresholds, recent players, topPlayers, persistence, coverage and limits. Player counts contain all three windows and ALL_TIME. Severity and flaggedOres always use 300 seconds.

GET /api/ore-monitor/history?query=&offset=0&limit=12 provides asynchronous server-side paging. Names/worlds are matched as literal case-folded search with SQL wildcard escaping and prepared parameters. Ranking uses all eleven ore totals. Query length is at most 64, offset at most 1,000,000 and limit 1–50. Both endpoints require the current authenticated administrative role and enabled module. No IP, credentials, seed, inventory or coordinates are exposed.

The page supports four windows, server-side all-time search/paging, global top five, recent client-side ore/pace filters, player details, paused visible-only polling and explicit loading/error/stale/unsaved/partial states. Colours have readable text. The all-time view disables recent filter controls instead of pretending that one page represents all historical matches. The optional antiCheatPanel slot remains unchanged. Tables scroll locally while the surrounding page reflows.

## Verification scope

Source includes eleven placement/threshold ledger regressions, fifteen SQLite history regressions and four Node display regressions. SQLite cases cover restart persistence, boundaries, all-eleven aggregation/top five, literal search/paging, five-minute severity, buffer coalescing, recent caps without all-time loss, transactional retry, close flush, unclean markers, immutable DTOs, predecessor closure and durable coverage without double addition. Java execution belongs to the coordinated application suite. Four Node tests and TypeScript no-emit were executed during implementation.

The earlier 0.7 ten-JUnit/three-Node pass described a session-only ten-minute monitor; it does not establish this persistent implementation's runtime behavior. Native populated mining, dynamic toggles, real restart durability, viewport/focus and performance checks require final assembled release evidence. Seeded database UI fixtures are distinguished from actual player mining.

Primary listener references: [BlockBreakEvent](https://jd.papermc.io/paper/26.3/org/bukkit/event/block/BlockBreakEvent.html), [BlockPlaceEvent](https://jd.papermc.io/paper/26.3/org/bukkit/event/block/BlockPlaceEvent.html), [MONITOR priority](https://jd.papermc.io/paper/26.3/org/bukkit/event/EventPriority.html) and [World.isChunkLoaded](https://jd.papermc.io/paper/26.3/org/bukkit/World.html#isChunkLoaded(int,int)). Build compatibility is checked against declared Spigot API; Paper documentation does not permit a Paper-only binary dependency.
