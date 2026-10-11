# MCC0.6 plugin catalog

The Plugins page contains a live, searchable Modrinth catalog, a curated collection of familiar server projects, the currently loaded plugins, and catalog installations awaiting restart. The catalog does not execute plugin classes, hot-load plugins, or restart the server.

## Source and compatibility

The backend uses the public [Modrinth search API](https://docs.modrinth.com/api/operations/searchprojects/) and [project version API](https://docs.modrinth.com/api/operations/getprojectversions/). Search is paginated and can sort by downloads, followers, recent updates, newest projects, or relevance. Download totals are the publisher registry's project totals, including other platforms where a project supports several loaders; they are not Paper-only download measurements.

The server supplies its actual Minecraft version. A release is eligible only when the same version object declares all of the following:

- The exact Minecraft version, such as `26.3`.
- At least one of `paper`, `bukkit`, or `spigot`.
- `version_type: release`, with listed or archived status.
- A primary JAR, bounded size and a valid published SHA-512.

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
7. Owner authority is checked when work starts, before publication, for every file and before database commit. Logout or revoked authority cancels queued or in-flight installation and rolls back new files. A successful result is `installed-pending-restart`. There is no dynamic reload or automatic restart.

The manual upload workflow remains separate: its existing16 MiB limit and inert staging behavior do not change. Catalog installation supports verified registry artifacts up to64 MiB. Updating or removing an existing JAR still requires an explicit offline maintenance operation; the catalog does not silently replace it.

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

Implementation: `PluginCatalog.java`, `CatalogHttp.java`, `PluginJar.java`, `PluginsPage.tsx`, `PluginsPage.css` and `plugin-catalog-view.ts`. Root wiring resides in `MccPlugin`, `WebServer` and `Management`.

## Verification

`PluginCatalogTest` covers exact same-release compatibility, stable-only selection, dependency ordering and actual file installation, byte-identical dependency reuse, missing dependencies even on reused JARs, same-version different bytes, tampered hashes, MCC protection, actor/TTL/single-use plans, no-overwrite identity, database-failure rollback, SSRF/source guards, safe YAML, newer API rejection, disabled runtime status, canceled shutdown, authority revocation, source identity, caller cancellation, stale-cache install rejection and bounded page concurrency. Frontend regressions cover install eligibility and literal search parameter encoding.

An isolated scratch-tree smoke downloaded and installed the real Modrinth Chunky1.5.3 release declaring26.3 and Paper/Bukkit/Spigot support. Its304,616-byte artifact matched SHA-512 and SHA-256 `530d2c7430a96a39957391b7088be144daa3108f7665896d1c23aa8dd4af32f3`. This test did not execute the plugin or touch a running server. Native startup verification is a separate deployment check.
