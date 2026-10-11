# Ore mining observation

The Fair play page observes successfully broken ore **blocks** over the last ten minutes. It is not an anti-cheat and never bans, kicks, cancels mining, changes blocks or certifies that a player is cheating or cheat-free. Owner and Admin may view player names and recent mining counts; the endpoint must use the same authenticated role checks as other administrative observations.

## Counting and windows

The listener observes native `BlockBreakEvent` at `MONITOR`, without changing it. Cancelled events are ignored. A bounded FIFO checks the event again on a later server tick, confirms that its original material has changed, and reads only a chunk already reported as loaded. A late cancellation, unloaded world/chunk or identical-material replacement produces no count. Survival at the time of the event is required; Creative, Spectator and Adventure are excluded. Drop quantities, Fortune, XP, storage blocks and explosions do not multiply or create counts.

Diamond, gold, redstone, coal, lapis, emerald, iron and copper include their deepslate variants. Nether quartz, Nether gold and ancient debris have separate counters. The one-, five- and ten-minute windows are computed from the original observation time, with an exclusive lower boundary. An event expires exactly ten minutes after it was observed. The clock is anchored to monotonic elapsed time for this server session. Names and UUIDs expire with the last event.

Native placement events, including Creative placement, enter the same FIFO. Placement before a break in the same tick therefore excludes that newly placed ore. The registry stores exact world UUID, block coordinates and material for up to 24 hours, at most 65,536 positions, in memory only. A restart resets it. Old placements, evicted/expired records, piston movements and changes by other plugins have unknown provenance. This feature cannot prove that every counted block is naturally generated. Unknown provenance is displayed in the UI, and capacity/verification losses set `coverage.partial`.

## Pace indicators

The indicator always uses the absolute count of each ore over ten minutes, even if the user selects a shorter display window. A short vein is not extrapolated into an hourly or ten-minute rate. Green means that configured counts have not been reached; yellow and red mean a high and very high observed pace. None is a verdict.

Initial heuristic thresholds, in blocks per ten minutes:

| Ore | Yellow | Red |
|---|---:|---:|
| Diamond | 64 | 160 |
| Gold | 192 | 512 |
| Redstone | 320 | 800 |
| Coal | 640 | 1,536 |
| Lapis | 80 | 200 |
| Emerald | 32 | 96 |
| Iron | 384 | 1,000 |
| Copper | 1,024 | 3,072 |
| Nether quartz | 640 | 1,536 |
| Nether gold | 384 | 1,024 |
| Ancient debris | 24 | 64 |

These defaults are deliberately above small-vein counts, not scientifically calibrated probabilities or universal physical limits. Rich veins, mining routes, beacon Haste, Efficiency, tool speed, custom ore generation and ore exposed by explosions can produce legitimate high counts. Fortune changes drops, not this block count. Operators should calibrate thresholds to their server and inspect context manually. This first version does not inspect inventory, infer mining routes, track movement distance or compare beacon/enchantment state.

Thresholds may be configured in the `ore-monitor` section before constructing the monitor. For example:

```yaml
ore-monitor:
  thresholds:
    diamond:
      yellow: 64
      red: 160
    ancient_debris:
      yellow: 24
      red: 64
```

Missing values use the table above. Yellow must be positive and red strictly greater than yellow, at most 1,000,000. Invalid settings are rejected, rather than silently represented as active. The page exposes the applied thresholds in player details. Threshold editing is not a browser action in this version.

## Resource and lifecycle contract

Create `new OreMonitor(plugin, plugin.getConfig().getConfigurationSection("ore-monitor"))` on the server main thread, call `start()` there, and call `close()` there before shutdown. `snapshot()` is safe for HTTP workers: it returns an already published deeply immutable map and does not call Bukkit. The listener registers once; close cancels its task, unregisters its handlers and clears pending event references.

An event callback performs constant work and only appends a bounded observation. Pending capacity is 2,048; deferred verification processes at most 128 observations and approximately 0.75 ms of verification work per tick, without taking a chunk ticket or requesting a chunk load. The time check bounds the number of operations started, not the worst-case duration of one Bukkit read. Every 100 ticks the ledger builds a bounded snapshot; this scans at most 32,768 recent ore events and 65,536 tracked placements, and sorts at most 256 players. Snapshot work is separately bounded by record counts and is not part of the verification time allowance. Low TPS also makes tick-based refresh slower than five wall-clock seconds. No CPU benchmark is claimed.

At capacity the oldest recent event/player or placement is evicted. The snapshot reports eviction/skipped/dropped counters and partial coverage, so truncated histories are not presented as complete. Data is session-local and is not written to the database or disk.

## API and UI integration

`GET /api/ore-monitor` returns `generatedAt`, `startedAt`, `observedSeconds`, `windowSeconds=600`, `windows=[60,300,600]`, `ores`, `players`, `coverage` and `limits`. Player rows contain `id`, `name`, `world`, `online`, `lastMinedAt`, `counts` keyed by window seconds, ten-minute `total`, `severity` (`GREEN`, `YELLOW`, `RED`) and `flaggedOres`. No IP address, credentials, seed, inventory or block coordinates are exposed.

`OreMonitorPage` is an independent default export with an optional `antiCheatPanel: ReactNode` slot. It polls the cached snapshot while visible, provides an auto-refresh pause checkbox, client-side name/world/ore/pace filters, twelve players per page, and a details modal containing all eleven ores and applied thresholds. Readable text accompanies every colour. Empty, loading, stale/error and partial states are explicit. Details use a locally scrollable table with keyboard focus; surrounding page content reflows. Actual viewport, focus and screenshot checks remain part of the assembled application's visual QA.

## Verified checks

The targeted `OreLedgerTest` run against the pinned Spigot 26.3 API passed ten JUnit tests: ore families/deepslate grouping, exact rolling boundaries, placed exclusion/world identity, placement TTL/evictions, replacement, thresholds without extrapolation, player/event caps, immutable snapshots, deferred occurrence time/clock regression and invalid settings. Three Node tests cover independent count-window/status behavior, filtering/sorting without source mutation and malformed display counts. TypeScript `--noEmit` passed. These checks do not substitute for a native listener integration or real browser visual inspection.

Primary API references: [Paper BlockBreakEvent](https://jd.papermc.io/paper/26.3/org/bukkit/event/block/BlockBreakEvent.html), [BlockPlaceEvent](https://jd.papermc.io/paper/26.3/org/bukkit/event/block/BlockPlaceEvent.html), [MONITOR priority](https://jd.papermc.io/paper/26.3/org/bukkit/event/EventPriority.html) and [World.isChunkLoaded](https://jd.papermc.io/paper/26.3/org/bukkit/World.html#isChunkLoaded(int,int)). Bukkit compatibility must also be checked against the build's declared API before release.
