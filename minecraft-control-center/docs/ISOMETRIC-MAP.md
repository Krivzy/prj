# MCC0.5 — fixed isometric world map

The world map is a read-only Canvas2D surface, using a fixed isometric projection. Dragging pans; the wheel, buttons and keyboard change scale or position. There is no orbit control, construction tool, preview, clipboard, undo or redo. Spawn/player buttons and the X/Z form provide keyboard and single-pointer navigation.

The concept follows [Dynmap HD map configuration](https://github.com/webbukkit/dynmap/wiki/HD-Map-Configuration): a fixed isometric view gives terrain depth without an interactive 3D camera. MCC uses its own bounded surface sampler and renderer; it does not install or implement the full Dynmap plugin, offline tile pyramid, biome shaders, caves, interiors or arbitrary views.

## Real data and limits

- GET /api/map/terrain reads the top non-air block of each of256 columns in an existing FULL chunk. It sends heights, palette indices and material data, without12 voxel layers.
- X is the fastest cell index: x=chunkX*16+index%16, z=chunkZ*16+floor(index/16). Negative chunk coordinates use floor division.
- Before loading, a bounded disk-only worker checks the persisted NBT status: only FULL records are admitted. Missing/incomplete records remain missing; corrupt or unsupported records return error without loading. Paper asynchronous loads also use generate=false. No world-wide scan or bulk rendering job is started.
- One viewport requests radius1–3 (9–49chunks). Server cache/pending64, one asynchronous load and TPS Guard remain bounded. At most one main-thread snapshot is attempted per tick at TPS≥18; its thread-safe data is sampled by the worker. The snapshot is captured inside the load callback, before Paper releases its transient CHUNK_LOAD ticket. MCC adds no plugin chunk tickets and does not carry a mutable Chunk into the next tick. Former actor leases and ENTITY_TICKING ticket retention are absent.
- Client cache64, textures96, render bitmap at most8,000,000pixels and display DPR at most1.5. Cached surface is painted as a bitmap during pan/zoom.
- Rendering runs on changes through requestAnimationFrame; draw does not schedule itself. Requests stop in a hidden tab and on unmount. Pending areas poll every1.8s, with16attempts before the120s refresh interval.

Temporary refresh retains complete tiles. Missing results invalidate tiles; a world switch clears the surface and markers. Errors and TPS deferral have distinct visible states, with retry. Selected height/material is refreshed against current surface data.

Navigation by X/Z resolves its exact saved top column once, then uses that surface height as the view reference plane. This keeps distant hills visible without rotating the camera. Pending/missing/error columns do not borrow an adjacent height. Automatic focus waits until a pan gesture ends; explicit spawn/player Y cancels it. A completed focus stays stable across refreshes. Switching worlds releases captured pointers and cancels the former world's drag/pinch before applying the new centre, so a held gesture cannot restore the old coordinates.

Surface shading represents exposed height differences; it is an approximate outdoor surface, without interior geometry. Textures come from the owner's locally extracted assets. Palette colours remain usable if a texture is absent or the96texture cap is reached.

The persisted-status preflight uses the same single worker with an eight-task queue. A region record is limited to1MiB, decompressed NBT to2MiB, nesting to32 and array length to262144. Gzip, zlib and uncompressed records are supported; external `.mcc` records and other codecs fail closed. These compatibility limits can make an unusual saved chunk unavailable to this map without changing it. The saved-area overview counts nonempty Anvil locations, including incomplete records; it does not count only ready FULL terrain. This bounds MCC requests, without promising that Paper's unrelated startup or background work never changes a world.

## Navigation lifecycle regression

The held-pointer regression is reproducible with [isometric-lifecycle.test.mjs](../frontend/tests/isometric-lifecycle.test.mjs):

1. Set a synthetic first-world centre to X5000/Z6000 and hold a primary pointer on the canvas.
2. Clear the renderer for a second world and set its centre to X0/Z0/Y70.
3. Move the still-held pointer160px. The emitted `onView` centre must remain X0/Z0/Y70; the old drag origin must not return.

`clear(world)` releases pointer capture, clears pointer state, resets drag/pinch and removes the dragging class before the new world is shown. The test uses Node's test runner, the existing TypeScript dev dependency and in-memory mocked DOM/RAF; it neither creates a world nor contacts a server. Run `npm test` in frontend after `npm ci`. Surface-focus tests cover negative coordinates, delayed/nonready columns, explicit Y, completed-focus stability and former-world responses. These are regressions for source behavior, not native browser performance measurements.

## Retired editing API

Authenticated former /api/build/*, /api/map/detail and /api/map/region requests return410 before body parsing or worker scheduling. Unauthenticated calls remain401. BuildEngine runtime and editor sources/tests are removed. Existing SQL history is retained as an archive, with no destructive migration.

## Build and recovery

Use Java25, the checked-in Gradle wrapper and the frontend lockfile. In frontend run npm ci, npm test and npm run build; in the project run gradlew test shadowJar -PskipFrontend=true. Restore optional local game assets using scripts/Extract-Assets.py as documented in README.md before packaging a private runnable build. Public GitHub contains code, tests and font licensing, not extracted Minecraft images, worlds, credentials or runtime data.

Updating the installed Paper plugin requires a normal server stop and a verified private backup first. Restore only the previous JAR for an application rollback, keeping the current worlds, DB and configuration. The older build history is not replayed.
