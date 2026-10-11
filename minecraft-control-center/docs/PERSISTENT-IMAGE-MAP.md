# Persistent image map — MCC 0.7

The map displays previously rendered isometric PNGs for the entire saved world. Panning, zooming and opening another browser do not enqueue terrain work, load Paper chunks, add plugin tickets or save/generate the world. Player locations are a separate live overlay. Persistent image delivery was introduced in MCC 0.6; the cache optimization and recovery protocol below are the MCC 0.7 source update. Its exact artifact, isolated checks and production status are recorded separately in the [current audit](VISUAL-AUDIT-0.7.md).

## Architecture and Dynmap reference

Dynmap uses image-coordinate map tiles and persistent image storage, with separate queues for rendering and zoomed-out imagery. Its official configuration exposes render intervals, update delays and zoom-out processing. MCC follows that image-delivery architecture with a built-in, disk-only surface renderer. See the primary sources: [HDMapTile](https://github.com/webbukkit/dynmap/blob/v3.0/DynmapCore/src/main/java/org/dynmap/hdmap/HDMapTile.java), [FileTreeMapStorage](https://github.com/webbukkit/dynmap/blob/v3.0/DynmapCore/src/main/java/org/dynmap/storage/filetree/FileTreeMapStorage.java) and [configuration](https://github.com/webbukkit/dynmap/blob/v3.0/spigot/src/main/resources/configuration.txt).

The official [Dynmap repository](https://github.com/webbukkit/dynmap) does not establish a released Paper 26.3 integration, and [26.2 support](https://github.com/webbukkit/dynmap/pull/4271) was still an open pull request during this task. MCC therefore does not introduce Dynmap as a required runtime dependency.

`MapEngine` samples only main-thread world descriptors, spawn positions and TPS/MSPT. `SavedImageMap` owns one daemon worker at minimum thread priority. `SavedSurfaceReader` reads persisted Anvil records using ordinary read-only file access. It has no Bukkit dependency. No loaded-chunk snapshots or Bukkit terrain APIs are used by the new runtime.

Each FULL chunk becomes a small persisted surface record with 256 heights and colors. Base PNGs render shaded diamond tops and short shaded sides. This is a fixed surface illustration using material colors; it is not a full texture-pack renderer, cave map or editable block scene. Nether maps show the upper saved surface, including its roof.

The worker traverses every saved region file and slot incrementally. There is no spawn radius, 49-chunk/49-tile terrain limit, 2048-region inventory limit or requirement to open a location to render it. Bounds include successfully decoded FULL surfaces. Header slot counts also include proto records and are deliberately separate from `renderedChunks`. Successfully identified non-FULL records keep an in-memory location/timestamp stamp: unchanged proto records are skipped in normal passes, retried after header changes or during a forced rescan, and pruned when absent from a completed scan.

## Storage and projection

Cache root: `<plugin data>/map-images/v1/<world UUID>/`.

- `surfaces/<region-x>_<region-z>/<chunk-x>_<chunk-z>.bin`: compact surface records, no block entities/player data/seed.
- `tiles/<level>/<tile-x>_<tile-y>.png`: 256×256 images, signed coordinates.
- `manifest.json`: last published bounds, count, revision and timestamps, restored after restart even while rendering is paused.
- `images-dirty`: durable indication that surface changes or interrupted image recovery still require coherent base/parent PNGs.

Projection is `u = 2*(x-z)`, `v = x+z-y`. A level-L image covers `256*2^L` base image pixels. Each parent downsamples four children. Base images are retained for the complete rendered saved area; ancestors are persisted up to the supported level 20. The manifest's `maxLevel` is derived from the current saved extent, capped at 20. The browser chooses an appropriate cached level for the current scale, rather than defaulting to level 20.

Cache files are replaced through temporary files and atomic rename where supported. Identical PNG hashes skip the write. The image LRU is limited to 64 entries / 8 MiB; decoded surfaces to 32 chunks. Metadata, seen-record tracking and the non-FULL stamp memo scale with the number of saved records; this does not promise unlimited RAM. Disk storage scales with the rendered world. Region traversal and restoration use streaming directory iterators. Dirty image batches drain around 2048 keys; this is a work queue budget, not a world-size restriction.

## Coalescing and coherent recovery

Normal and forced content scans compare the decoded 256 heights, 256 image colors and projected bounds with the cached surface. Forced scans still read and validate persisted Anvil contents. An identical surface with the same header stamp rewrites neither its surface file nor its PNGs. A changed header stamp with identical imagery updates the persisted surface stamp without dirtying images. Actual height/color/bounds changes dirty the old and new image areas. Shared image tiles are coalesced during a saved-record scan, then drain when that scan finishes or the dirty-key budget is reached. This avoids repeatedly sorting and encoding the same PNG after each FULL record; it does not remove decoding or guarantee a particular CPU percentage.

Before a changed surface is persisted or an old surface is deleted, the worker writes `images-dirty`. On restart, a marker, an incomplete manifest, a restored-count mismatch or a legacy manifest without `imageCoherenceVersion:1` triggers incremental image recovery. Restored surface bounds and existing base-tile filenames are queued, so a surface removed just before interruption also clears its old imagery. While recovering, an identical base or parent PNG still propagates its parent work; an unchanged base therefore cannot hide a missing or stale ancestor.

Recovery also requires a startup saved-record scan. Only after restoration, recovery, that scan and all dirty image queues drain does the worker persist the clean manifest with `imageCoherenceVersion:1` and remove the marker. The clean manifest is written before marker removal, so interruption between those steps conservatively repeats recovery. A complete verification requires this committed version, a finished scan and zero pending tiles/errors. Cache recovery reads world records and rewrites derived cache files; it never restores Minecraft world data. This is an interruption-recovery protocol using atomic file replacement where supported, not a filesystem-wide transaction or a power-loss durability guarantee.

## API

All routes retain session authentication and map-module access checks in WebServer. PNG responses use private caching and SHA-256 ETags; matching conditional requests return 304. The backend methods are cache/file-only and safe to invoke from HTTP worker threads.

| Route | Result | Terrain work triggered by request |
| --- | --- | --- |
| `GET /api/map/images?world=…` | Cached manifest JSON | None |
| `GET /api/map/images/tile?world=…&level=…&x=…&y=…` | Persisted PNG, 304 or absent image | None |
| `GET /api/map/players?world=…` | `{world,worldId,players:[{uuid,name,x,y,z}],timestamp}` | None; lightweight main-thread player DTO |

Manifest fields: `world`, `worldId`, `tileSize`, `maxLevel`, `projection`, nullable image `bounds:{minU,minV,maxU,maxV}`, `spawn`, `status`, `renderedChunks`, `savedSlots`, `imageCoherenceVersion`, `scannedRecords`, `decodedRecords`, `nonFullRecords`, `revision`, `pendingTiles`, `updatedAt`, `scannedAt`, `failures`, `scanFailures`, `message`, `generatedOnly:true`, `backgroundOnly:true`, `limits`.

`status` is `rendering` while initial restoration, recovery, startup scan or image queues remain, `ready` when completed, `empty` when no FULL surface records exist, `paused` while TPS protection is active, or `error` after an operational background error. Full verification also checks the committed coherence version and scan/error counters. A completed pass with failed saved records and no usable surfaces is `error`, with a public explanation, rather than silently claiming an empty world. Partial failures keep usable cached surfaces and report counts. Existing cached imagery remains readable during pause/update/error. Initial rendering does not make viewers synchronously wait for terrain. Missing PNGs remain transparent until a later background revision exists.

The legacy `/api/map`, `/api/map/index` and `/api/map/terrain` endpoints are retired with HTTP 410. Building remains retired. `GameService` compatibility signatures do not instantiate the old terrain engine; `WorldTerrainEngine`/`SavedChunkStatus` remain historical implementation/test sources, outside the 0.6 map execution path.

## CPU, disk and game isolation

One worker processes up to four scan/restore records per soft 8 ms quantum or one image render. Its next delay is approximately nine times the preceding quantum, at least 50 ms and at most 5 s, targeting a low duty cycle. An individual bounded disk read, decompression, sort or ImageIO operation can exceed 8 ms; this is not a hard real-time CPU guarantee. CPU and disk throughput require native measurement on the actual server/world.

TPS below 18 or average tick time above 45 ms pauses saved-world reads and rendering. Cached manifest restoration and PNG delivery remain possible. Descriptors are refreshed once per second. The worker examines saved headers approximately every 60 seconds after a pass finishes and forces a content rescan every 300 seconds, including unchanged location/timestamp entries. Long initial passes can take longer than those intervals. Changes become visible after normal Minecraft world saving, the next applicable scan and queued rendering; the viewer never requests a world save.

Modern Paper shared-world paths are checked per dimension under `dimensions/minecraft/{overworld,the_nether,the_end}/region`. An absent modern dimension cannot fall back to overworld terrain. Legacy separate world/DIM-1/DIM1 paths are also supported.

Anvil records are accepted only when persisted `Status` is `full` or `minecraft:full`. The reader bounds record bytes at 1 MiB, decompressed NBT at 2 MiB, depth at 32, tags at 131072, arrays/lists at 262144, sections at 256 and palettes at 4096. Chunk coordinates and packed palette indexes are validated. Both legacy `{Name:…}` palette compounds and Paper 26.3 constant string / `{id:…,properties:…}` entries are supported, including singleton-empty-key wrappers used by heterogeneous NBT lists. Block identifiers remain strictly validated; properties do not affect this color-based surface renderer. Gzip, zlib and raw records are supported; external `.mcc` chunks and unsupported codecs fail closed. Concurrent region modification is retried during a later background pass. Unsupported/corrupt records count as failures and retain any last good image; large/modded chunks may therefore be absent or stale.

## Browser behavior

`MapPage` displays native `<img>` tiles, using stable URLs and conditional cache revalidation. Pan and zoom change their positions/scale and the small visible image window; they do not request terrain JSON or calculate block geometry. There is no Canvas, WebGL, continuous animation/render loop or rotating camera. The DOM window is bounded to 96 images and may select a coarser pyramid level for a large display. This limits view memory, not saved-world coverage.

The initial camera fits the saved extent after its first scan; users can choose Whole world, Spawn, coordinates or an online player. Manifest polling is 5 seconds while rendering and 60 seconds when settled. Player DTO polling is 2 seconds. Hidden tabs stop JSON polling. Unchanged player positions preserve overlay state. Pointer capture is cleared on world switch, hidden tab, wheel and navigation, preventing an old drag from restoring another world's camera. Controls are below the viewport, keyboard accessible and at least 44 px.

## Verification and rollback

Targeted tests include 10 `SavedSurfaceReaderTest` cases, 18 `SavedImageMapTest` cases and 7 image-coordinate frontend cases. Tests use disposable temporary Anvil fixtures and verify source bytes remain unchanged. They cover persisted proto exclusion, shared-dimension isolation, old/new palette storage, compression/palette/size bounds, independent image rendering, persisted PNG/hash restoration while paused, failed-only world reporting, unchanged proto skip / changed stamp / forced-rescan promotion, far-coordinate access, signed projection, whole-world fit, zoom anchoring and tiny-world minimum-zoom iteration bounds. Exact run receipts and full build results belong to the parent task.

The 10 new image-map regressions cover: unchanged forced scans without surface/PNG writes; changed stamp with unchanged imagery; changed height despite an unchanged header stamp; saved-chunk removal; scan-wide PNG coalescing; interrupted scans with missing images; interrupted height changes with stale base and parent PNGs; identical bases with missing parents; interrupted FULL-to-proto removal; and legacy ready manifests with unchanged surface counts but an untrusted image generation. These establish the isolated cache lifecycle, not production throughput, TPS or a JVM stability soak.

Native investigation on the closed disposable 26.3 fixture initially found 138 FULL decode failures because it used the newer palette format, not a wrong directory. After the decoder correction, a read-only scan of both shared-root and direct-dimension paths decoded all 138 FULL records, skipped 1883 proto records and reported zero errors among 2021 saved slots. This decoder observation is separate from the subsequent exact-JAR PNG/browser verification.

The parent task owns full Java/frontend builds and isolated native/browser checks. Unit tests do not establish production TPS, visual fidelity on a real world or all supported chunk formats. Record exact candidate artifact/native results separately.

Rollback installs the previously verified plugin JAR through the task's guarded deployment process after explicit restart authorization. The cache consists only of derived files under the plugin data folder; older image-map versions do not use the new coherence marker protocol. Minecraft region files and databases are not migrated by this renderer. A background cache verification deadline remains a pending result and does not by itself authorize stopping a healthy installed server.
