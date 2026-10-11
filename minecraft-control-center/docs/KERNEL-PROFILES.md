# Kernel profiles

MCC provides `custom`, `vanilla`, `vanilla-performance`, `optimized` and `maximum-optimized` profiles for an existing kernel. It does not replace kernel JARs, migrate worlds, run `/reload`, change exploit/security switches, or promise exact Mojang behavior. Paper itself documents that exact vanilla equivalence is not attainable. The default is **custom**: MCC leaves current kernel files byte-identical, including its preboot hook. The kernel can still rewrite its own formatting, defaults or timestamps during boot. MCC's own private profile metadata may be persisted when a user explicitly selects a mode.

## Managed settings and compatibility

Only an enum profile can be supplied to the service. Arbitrary config paths/values are never accepted from HTTP. Known kernels are the names `Paper`, `Purpur`, and `Spigot`, compared case-insensitively for capability discovery. Folia and unknown forks are excluded from managed writes. Actual persisted kernel name and Minecraft version must remain identical at managed startup; changing them requires a new explicit profile plan.

Configuration support is intentionally versioned and fails closed:

| File/schema | Managed keys |
| --- | --- |
| `server.properties` with explicit valid distance keys | `view-distance`, `simulation-distance` |
| `spigot.yml`, `config-version: 12` or `13` | distances, spawn radius, tick-inactive-villagers, nerf-spawner-mobs, item merge radius, bounded maximum entity collisions, seven entity activation categories in `world-settings.default` **and every existing per-world mapping** |
| `config/paper-world-defaults.yml` and existing loaded-world `paper-world.yml`, `_version: 31` | redstone implementation, pathfinding update behavior, explosion lookup optimization, paced chunk saving, unload delay, horizontal-only item merge and `collisions.max-entity-collisions` |
| `purpur.yml`, `config-version: 49` | alternate keepalive disabled; TPS catchup enabled except for optimized and maximum-optimized |

Paper's schema 31 and Spigot's schema 13 were observed in the closed owned Paper 26.3 fixture. Purpur schema 49 was checked against the official `ver/26.3` configuration implementation and the isolated native Purpur fixture. Structural parsing and unit fixtures cover these supported schemas. The completed native Paper/Purpur 26.3 suite for 0.8 is described below; native Spigot and arbitrary future schema compatibility are not claimed. Public runtime methods are detected against the **loaded** Bukkit `World` interface; compiling against Paper does not imply that Spigot provides its methods.

| Profile | View / simulation | Additional intent |
| --- | --- | --- |
| custom | From existing files | No kernel file writes and no live setters |
| vanilla | 10 / 10 | Activation throttling disabled in loaded entities, vanilla redstone, item merge 0.5, spawn radius 8, immediate unload, max 200 autosave chunks per tick |
| vanilla-performance | 10 / 8 | Same managed behavior keys, with explosion lookup optimization, 10-second unload delay, max 24 autosave chunks per tick |
| optimized | 8 / 6 | Activation ranges 32/32/64/16/16/32/32, spawn radius 6, item merge 2.5, Alternate Current redstone, pathfinding block updates disabled; farms/redstone and Purpur tick catchup can behave differently |
| maximum-optimized | Existing per-world values retained | Activation ranges 16/16/32/8/8/16/16, spawn radius 4, item merge 3.0, max entity collisions 2, inactive villager ticking disabled and spawner AI disabled; retains optimized Paper/Purpur behavior switches |

`maximum-optimized` excludes `server.properties` and all view/simulation-distance YAML keys from the plan, prelaunch reconcile and live setters. Current loaded-world distances therefore remain unchanged. Its entity throttling, collision, merging, villager, spawner and redstone choices alter gameplay; crowded farms, restocking and dust update order must be tested. Spigot has no supported redstone implementation key, so that optimization is explicitly skipped. It is a stronger tradeoff preset, not a claim of globally optimal settings.

Every other managed preset restores max entity collisions to the documented default 8 in Spigot mappings and Paper/Purpur global/world overrides. Thus maximum→vanilla does not retain maximum-only collision throttling. Custom retains the current files.

Performance gains are workload-dependent and have not been benchmarked. High full-vanilla activation/save limits can cost CPU/IO. Presets retain every setting outside this small allowlist, including plugin behavior, network/auth settings, world seed and unsupported exploit switches. `vanilla` therefore means this managed collection of vanilla-like settings. Missing typed scalar keys can be added to known mappings; incompatible scalar types, unknown config versions and unsupported layouts are refused.

## Files, plans and rollback

All IO uses one bounded worker (queue 8), never the Bukkit tick thread. A file is at most 1 MiB, total planned before+after bytes at most 8 MiB, config files at most 132 and worlds at most 128. Up to eight plans are kept for five minutes and bound to the initiating actor. YAML safe loading refuses collection aliases, duplicate keys, arbitrary object tags, nesting beyond 24 and excessive nodes. UTF-8 must be valid. Properties changes retain all unrelated line bytes; YAML changes retain unknown values semantically but can normalize comments/formatting.

An apply requires explicit confirmation plus an authority supplier that checks the **current active Owner**, not a cached decision made when the HTTP request arrived. Authority is checked before every backup/config/metadata write and immediately after each replace. The file and profile SHA values must still match the reviewed plan before commit and at the individual replace. Paths must be canonical regular files inside the server directory; symbolic links and ancestor paths escaping it are refused.

Original bytes, their inventory hashes and previous profile metadata are backed up to `plugins/MinecraftControlCenter/kernel-profiles/backups/<id>/`. These files can contain kernel secrets and must remain private, excluded from GitHub and ordinary memory. New files are owner+SYSTEM on Windows, 0600/0700 on POSIX. A flushed and hashed same-directory temporary file is renamed with `ATOMIC_MOVE`; there is no non-atomic fallback. Existing config ACL/permissions are retained. The transaction restores completed replaces on exception or authority loss only when the current bytes still match MCC's attempted change; external edits are preserved and reported for manual reconciliation.

This is per-file atomic replacement with an exact private backup, not a promise of atomic multi-file recovery after power loss. A supervisor must not start the kernel concurrently with application or manual recovery. Backups are retained without automatic deletion; operators control private retention. Selecting custom after a managed preset preserves the then-current bytes instead of silently restoring an old configuration.

Live changes are limited to public per-world view/simulation distance setters, run entirely on the server tick thread. Both getters and setters must exist. Active Owner authority is rechecked between setters/worlds. Partial failures restore distances changed by MCC while preserving a differing external value; rollback errors are explicit. File intent remains durably saved for restart if the live API fails, and the response carries `liveError`. Other keys remain `pendingRestart`. Setter rollback covers settings, not any chunks that the kernel may have loaded while applying a distance.

## Startup

The reliable startup path is an offline supervisor hook **before every server JVM launch**, including automatic restarts:

```text
java -cp plugins/MinecraftControlCenter.jar dev.mcc.server.ProfileBootstrap --offline --server-root <absolute-server-root> --plugin-data <absolute-server-root>/plugins/MinecraftControlCenter --kernel Paper --minecraft-version 26.3
```

The launcher must already hold its normal exclusive offline lease and verify the server has exited; `--offline` is an assertion, not an independent port/process detector. The CLI holds MCC's runtime/config locks, validates saved kernel/version and world paths, reapplies typed managed values and only then clears pending restart. It needs no Bukkit runtime. Its stdout contains profile/change counts/backup ID only, never config contents; failures return exit code 2 with a sanitized message. Root integration must bundle SnakeYAML (`implementation`) and relocate its package in the shadow JAR so this CLI works on a generic host.

Without a prelaunch hook, `reconcileStartup()` is invoked asynchronously from `onEnable`. It leaves custom files untouched. For managed drift, it restores configuration files **after the kernel has already read them**, applies supported distance setters and keeps `pendingRestart=true`. It does not pretend this late write changed all running kernel settings. If files already match at the next startup, pending is cleared. Known loaded-world overrides are included; a newly introduced world override discovered only after kernel initialization can need one extra restart. Relocating a server with absolute saved world paths also requires a fresh explicit plan.

`close()` stops admission, interrupts queued IO, and keeps the runtime lease until the worker and any rollback actually finish. It returns without blocking the server tick thread on queued Bukkit work. Startup must not bypass a still-held lease.

## Integration contract

Only the independent new classes own profile behavior; shared routing/lifecycle is integrated separately.

```java
KernelProfiles profiles = new KernelProfiles(
    serverRoot, getDataFolder().toPath(), new BukkitKernelRuntime(main), startupHookConfigured);
profiles.reconcileStartup().whenComplete((state, error) -> { /* report sanitized startup state */ });
// During disable, before main.close():
profiles.close();
```

| Route | Authorization | Service |
| --- | --- | --- |
| `GET /api/server/profiles` | Admin/Owner | `profiles.status()` |
| `POST /api/server/profiles/plan` `{profile}` | active Owner + CSRF | `profiles.plan(actorId, profileId, currentOwnerSupplier)` |
| `POST /api/server/profiles/apply` `{id,planId,confirmed:true}` | active Owner + CSRF; normal tracked action/idempotency wrapper | `profiles.apply(actorId, planId, true, currentOwnerSupplier)` |

All service methods return `CompletableFuture<Map<String,Object>>`; routing should await only its normal short HTTP budget then expose the actual pending tracked result. Status includes kernel/version/profile, file revision hashes, supported schemas/public setters, hook capability, pending restart and actual world distances. Plans expose only allowlisted key diffs and file SHA values, never full configs. Apply adds `saved`, `backupId`, `liveApplied` and `liveError`.

The standalone React/Vite `KernelProfiles` component can be embedded in the existing administration/settings page. Admin can inspect and Owner can mutate; it includes loading/error/empty/unsupported states, review/confirmation, pending restart and current per-world distances. Requests are on demand, with no automatic polling, renderer, or large config contents. Buttons use 44px targets and the existing modal focus trap. Server authorization and conflict guards remain authoritative.

## 0.8 verification

The coordinated exact-candidate build passed 269 Java, 78 Node and 11 Python tests, TypeScript, Vite and Shadow JAR assembly. The build includes collision-transition and typed-path regressions for Spigot plus Paper/Purpur defaults and world overrides. New profile regressions verify distance preservation in maximum plans, live application and offline drift restoration; the Spigot plan explicitly skips unsupported redstone configuration. Isolated native Paper 26.3 build 169 and Purpur 26.3 build 2647 both passed all five profile kinds/six applications, including maximum→vanilla, six requested normal restarts and a final observed normal exit 0. Their prelaunch hooks restored managed file drift before kernel startup, source conflicts rejected before writes, and private backups matched the exact planned before hashes. Maximum retained the tested per-world view/simulation distances of 8/6; returning to vanilla restored max-entity-collisions from 2 to 8 in Spigot, Paper defaults and all three world overrides. Custom applied zero kernel file edits and preserved manual server.properties values 5/4; inherited per-world YAML overrides retained actual runtime distances 10/10. This is a distinction between file values and world overrides, not a claim that Custom forces distances. Production has not received these profiles through the native fixtures. Native Spigot remains untested, and the official Purpur build is labelled experimental.

## Historical 0.7 checks

The assembled Gradle test XML on 2026-10-11 records 25 profile/config/public-distance unit tests (16 profile, 5 config, 4 distance) plus 7 restart tests, with zero failures, errors or skipped tests. The whole Java suite records 215 passing tests. Profile regressions cover default/custom byte preservation, per-world merges, source conflicts, actor/TTL/authority, exact rollback on Owner loss, unknown schemas/kernels, Spigot without setters, visible live failure, late-startup drift, offline prelaunch lease/identity, typed YAML hazards and partial live rollback. Three Node helper tests and `tsc -b` passed. No preset was applied to production and no benchmark/native kernel preset claim is made by these checks.

## Primary references

- [Paper vanilla differences](https://docs.papermc.io/paper/vanilla/)
- [Paper configuration inheritance and layouts](https://docs.papermc.io/paper/reference/configuration/)
- [Paper world configuration](https://docs.papermc.io/paper/reference/world-configuration/)
- [Paper 26.3 World public API](https://jd.papermc.io/paper/26.3/org/bukkit/World.html)
- [Spigot configuration reference](https://www.spigotmc.org/wiki/spigot-configuration/)
- [Spigot World public API](https://hub.spigotmc.org/javadocs/spigot/org/bukkit/World.html)
- [Purpur 26.3 config implementation](https://github.com/PurpurMC/Purpur/blob/ver/26.3/purpur-server/src/main/java/org/purpurmc/purpur/PurpurConfig.java)
- [Paper migration/layout constraints](https://docs.papermc.io/paper/migration/)
