# MCC 0.9 plugin catalog and lifecycle

The Plugins page contains a live, searchable Modrinth catalog, a curated collection of familiar server projects, the currently loaded plugins, and catalog installations awaiting restart. The catalog does not execute plugin classes, hot-load plugins, or restart the server.

## Source and compatibility

The backend uses the public [Modrinth search API](https://docs.modrinth.com/api/operations/searchprojects/) and [project version API](https://docs.modrinth.com/api/operations/getprojectversions/). Search is paginated and can sort by downloads, followers, recent updates, newest projects, or relevance. Download totals are the publisher registry's project totals, including other platforms where a project supports several loaders; they are not Paper-only download measurements.

The server supplies its actual Minecraft version. A release is eligible only when the same version object declares all of the following:

- The exact Minecraft version, such as `26.3`.
- At least one of `paper`, `bukkit`, or `spigot`.
- `version_type: release`, with listed or archived status.
- A supported JAR with bounded size and a valid published SHA-512; the primary artifact is preferred when available.

An aggregate search result can contain a Minecraft version from one loader and Paper from another release. MCC therefore checks version metadata separately for each result. Beta releases, snapshots, Forge/Fabric-only releases, older releases and unknown compatibility cannot enable installation. `plugin.yml`'s `api-version` is a minimum API requirement, not proof of exact-version compatibility; see [Paper's descriptor documentation](https://docs.papermc.io/paper/dev/plugin-yml/).

The featured collection pins known Modrinth project IDs rather than searching by a potentially similar name. Familiar projects without an eligible artifact remain visible with an unavailable status and an official project link. Vault and ProtocolLib have informational featured entries; MCC does not infer compatibility from an unversioned GitHub release or automatically download a substitute.

Compatibility is declared by the release publisher, not certified by MCC. Hash verification establishes correspondence with registry metadata; it is not malware scanning or a guarantee that arbitrary plugin code works correctly. Runtime loading and behavior must be checked after restarting Paper.

## Installation flow

1. Owner requests an installation plan. The plan resolves required Modrinth dependencies, pins exact version IDs and SHA-512 values, and lists the complete file set before confirmation. Optional dependencies are listed but omitted. Unverifiable external dependencies, incompatible dependencies, cycles and conflicting pinned versions block the plan.
2. The plan belongs to the requesting account, expires after five minutes and can be consumed once. The root HTTP endpoint requires Owner, CSRF protection, explicit confirmation, request tracking and rate limiting. Admin may browse the catalog but cannot install server code.
3. Downloads use HTTPS only, pinned `api.modrinth.com` / `cdn.modrinth.com` hosts and no redirects, cookies or credentials. Arbitrary URLs, user information, custom ports, unsafe paths and project/version mismatches are rejected.
4. Every JAR is downloaded outside `plugins/` and checked for exact size, SHA-512, its own SHA-256, bounded ZIP structure and safe Paper/Bukkit YAML metadata. The main class must exist. YAML aliases, object construction and duplicate keys are rejected. MCC's identity and main package are protected. A newer API requirement and unresolved JAR-declared dependencies block publication.
5. An existing required dependency can be reused only when its actual JAR identity, version and SHA-512 match the planned release. All planned metadata, including reused JARs, still participates in required-dependency validation. Existing plugin JARs, duplicate names and conflicting `provides` capabilities are not overwritten.
6. After all downloads pass, each new JAR is published by an atomic same-filesystem hard link with create-only semantics, then unlinked from staging. Filesystem hard-link support is required. A database transaction records the complete result. Ordinary download, validation, permission or database failures roll back only new files created by this installation; rollback verifies their hashes before deletion.
7. Owner authority is checked when work starts, before publication, for every file and before database commit. Logout or revoked authority observed at these checks rejects queued or in-flight installation and rolls back new files. This is not a promise to interrupt a download at the instant of logout. A successful new-file result is `installed-pending-restart`; an exact reused release returns `already-installed`. There is no dynamic reload or automatic restart.

The manual upload workflow remains separate: its existing 16 MiB limit and inert staging behavior do not change. Catalog installation supports verified registry artifacts up to 64 MiB. Updating a different existing release is not implicit: existing JARs are never overwritten. A repeated exact release with the same descriptor identity, version and SHA-512 is an idempotent result; actual files are preserved. Catalog cards reconcile project records with real plugin identities after a restart and show an installed state. Missing files do not remain falsely pending. A verified manual copy can be recognised during install without guessing a project from a similar display name.

For manually installed LuckPerms, ViaVersion and Chunky, known project IDs also map to exact verified descriptor names or `provides` aliases. Their cards report the actual plugin name, version and loading state, with `source: manual-identity`; this does not claim that the installed artifact came from Modrinth or matches the selected release's bytes. Similar display names from another project do not match. Other manual plugins still undergo descriptor/version/hash checks if installation is requested; an unverified project-to-plugin mapping does not enable silent replacement.

Multi-file publication cannot be a filesystem-wide atomic transaction. A process crash or power loss between individual publications can leave a partial pending set, and failure of a rollback filesystem operation can require manual repair. The catalog does not promise crash-proof installation across several JARs. Inspect pending files before restarting after an interrupted installation.

## Bounded background work

- All network, hashing, ZIP and database work runs outside Minecraft's tick thread. Installed snapshots are supplied from the root's main-thread cache.
- Four concurrent upstream HTTP requests; two catalog operation workers with a24-entry queue; four metadata workers with a48-entry queue.
- Search has a15-second metadata deadline, with unknown cards when individual checks cannot finish. Its queued operation is capped at17 seconds; an installation plan is capped at15 seconds. Cancellation interrupts the underlying worker.
- Each API response is capped at8 MiB. Metadata cache: at most512 keys /16 MiB of source JSON, five-minute freshness and30-minute read-only stale fallback. Stale metadata cannot create an installation plan.
- Each JAR: at most64 MiB; one dependency plan: at most eight JARs /128 MiB. ZIP inspection caps25,000 entries,256 MiB declared expansion, per-entry size, descriptor64 KiB and suspicious compression ratios.
- Immediate HTTP shutdown, worker interruption, terminal completion of pending futures and a bounded worker shutdown wait avoid waiting for a full network download on Paper shutdown.

## HTTP contract

| Endpoint | Permission | Result |
| --- | --- | --- |
| `GET /api/plugins/catalog?query=&sort=downloads&offset=0&limit=12&compatible=true&featured=false` | Admin | Actual server version, total projects, pagination, per-project compatibility, selected release and stale flag |
| `GET /api/plugins/catalog/plan?projectId=<Modrinth ID>` | Owner | Actor-bound expiring plan, dependency-ordered file list, hashes and total size |
| `POST /api/plugins/catalog/install` | Owner + CSRF | `{id, planId, confirmed:true}`; tracked install result or pending action |
| `GET /api/plugins/catalog/installed` | Admin | Catalog records matched to active snapshots; loaded, disabled or awaiting restart |
| `GET /api/plugins` | Admin | Existing runtime plugin list |
| `GET /api/plugins/manage` | Admin + catalog module | Bounded real JAR inventory, runtime state, dependencies, protection, prelaunch capability and reversible removal records |
| `GET /api/plugins/remove/plan?name=<descriptor name>` | Owner + catalog module | Session-bound five-minute plan with actual filename, SHA-256 and dependency checks |
| `POST /api/plugins/remove` | Owner + CSRF + catalog module | `{id,planId,confirmed:true}`; persistent removal intent, JAR remains in place until offline prelaunch |
| `POST /api/plugins/restore` | Owner + CSRF + catalog module | `{id,removalId,confirmed:true}`; cancel pending removal or publish an independent retained JAR copy for next restart |

Implementation: `PluginCatalog.java`, `CatalogHttp.java`, `PluginJar.java`, `PluginsPage.tsx`, `PluginsPage.css` and `plugin-catalog-view.ts`. Root wiring resides in `MccPlugin`, `WebServer` and `Management`.

## Verification

`PluginCatalogTest` covers exact same-release compatibility, stable-only selection, dependency ordering and actual file installation, byte-identical dependency reuse, missing dependencies even on reused JARs, same-version different bytes, tampered hashes, MCC protection, actor/TTL/single-use plans, no-overwrite identity, database-failure rollback, SSRF/source guards, safe YAML, newer API rejection, disabled runtime status, canceled shutdown, authority revocation, source identity, caller cancellation, stale-cache install rejection and bounded page concurrency. Manual identity regressions cover pending and loaded state after a catalog restart, provided aliases and rejection of unrelated projects with the same title. Frontend regressions cover install eligibility, runtime/file identity merging, removal eligibility and literal search parameter encoding.

An isolated scratch-tree smoke downloaded and installed the real Modrinth Chunky1.5.3 release declaring26.3 and Paper/Bukkit/Spigot support. Its304,616-byte artifact matched SHA-512 and SHA-256 `530d2c7430a96a39957391b7088be144daa3108f7665896d1c23aa8dd4af32f3`. This test did not execute the plugin or touch a running server. Native startup verification is a separate deployment check.

## Reversible removal (0.9)

Removal is available only with the successful MCC offline prelaunch hook (`mcc.launcher.profile-hook=true`). The existing supervisor invokes `ProfileBootstrap` before every core boot while retaining its daemon lease. Plugin maintenance runs before managed kernel reconciliation. Generic hosting needs the same real prelaunch step; setting a JVM flag alone is not a substitute. Unsupported launchers show an unavailable removal state.

The Owner confirms a plan bound to a hash of the current session identity. No cookie or token is persisted in quarantine metadata. Queueing removal writes only private intent metadata. MCC never calls `disablePlugin`, dynamic loading, `/reload`, reflective unloading or a forced server restart. Before the next boot, maintenance checks the original filename, exact SHA-256, descriptor name/main, MCC/self aliases and required dependencies again, then atomically moves the JAR into `plugins/MinecraftControlCenter/plugin-quarantine/<removalId>/plugin.jar`. Settings and plugin data directories are preserved. AdvancedBanZ and other manually installed plugins are matched by their own descriptor names, rather than catalogue titles.

Required Bukkit `depend`, Paper bootstrap/server dependencies, runtime `required` metadata and `provides` aliases participate in removal checks. Soft dependencies do not block removal. Ambiguous identities, unsafe paths, symlinks, unverifiable descriptors and external file drift fail closed. Remove a hard-dependent plugin first and restart before removing its provider. Inventory is capped at512 JARs of64 MiB each; plan cache64 entries, quarantine512 retained operation directories. Installed-file management is fetched on tab entry, manual refresh or an operation result, without periodic file-inventory polling.

A pending removal can be cancelled without moving or rewriting its source JAR. After removal, restoration verifies the private retained bytes and publishes an independent copy with create-only semantics, so changes to the restored JAR cannot alter the backup. Existing names/provides/filenames and absent required dependencies block restoration. A restored runtime plugin clears its pending-restart state after the next actual load. Backups are retained; MCC does not automatically purge them or remove plugin data.

A changed queued JAR or a newly added dependent blocks the prelaunch hook, and the existing supervisor refuses to start the core. This protects files rather than silently deleting an unreviewed build. If the panel is unavailable, cancel only the pending intent offline; this command requires both the supervisor daemon lease and MCC runtime lease to be free and never changes plugin JARs or data:

```powershell
# Run from the stopped server folder, using its configured Java executable.
java -cp plugins/MinecraftControlCenter.jar dev.mcc.core.PluginMaintenanceBootstrap --offline --server-root "$PWD" --plugin-data "$PWD/plugins/MinecraftControlCenter" --cancel-removal "<removal UUID>"
```

Inspect and repair the changed file/dependency explicitly, then start through the normal supervisor. The standalone cancellation path requires the existing `.mcc-launcher/daemon.lock` and `kernel-profiles/runtime.lock`; it refuses a running supervisor/plugin. The main prelaunch path holds the runtime lease in addition to its caller's retained daemon lease. If interruption occurs after an atomic JAR move but before metadata publication, prelaunch validates the retained bytes and repairs that operation's completion record. Unknown or damaged quarantine metadata requires offline operator review. Neither an ordinary filesystem failure nor a power loss is described as a successful removal.

New regressions cover inert live requests, exact offline move, data preservation, independent restoration backup, hard/soft/provided and Paper dependencies, self aliases, missing prelaunch support, revoked/session-bound authority, TTL/single-use plans, changed files/new dependencies, interrupted move recovery, occupied/unsatisfied restoration and guarded offline cancellation. Native verification is scoped to disposable fixtures; no production plugin removal is part of implementation tests.

The complete 0.9 source build passed 303 Java, 111 Node and 12 Python tests, plus TypeScript/Vite/Gradle. Native remove/cancel/quarantine/independent-copy restore and dependency/self guards passed on fresh isolated Paper and Purpur for the exact final 0.9 JAR, with four boots per core and normal closure. The final browser inspection confirmed available removal actions and disabled reinstallation of the already installed LuckPerms after three restarts. The earlier Chunky scratch-tree smoke and older runtime receipts remain historical evidence; they do not certify the new lifecycle implementation or arbitrary plugin compatibility.
