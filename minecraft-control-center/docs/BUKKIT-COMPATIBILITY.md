# Bukkit portability and native moderation

MCC 0.7 compiles against the official Spigot API for Minecraft 26.3: `org.spigotmc:spigot-api:26.3-R0.1-20261004.024323-9`. This provides the common Bukkit surface used by Spigot, Paper and compatible forks. Compilation is evidence of API portability; it is not a claim that every server distribution or optional feature has been exercised natively.

## Public API boundary

`ServerPlatform` is the adapter for actual kernel identity, optional public metrics and optional public world methods. The game services use public Bukkit events, registries, legacy String messages and `AsyncPlayerChatEvent`. They contain no static Paper, Adventure or NMS class dependencies. The common Spigot action-bar API remains available through `Player.spigot()`.

Optional methods are invoked through public reflection without private access. An absent API is explicit: per-world distance setters reject an unsupported server instead of changing an unrelated global setting. The historical, unwired terrain renderer rejects an absent asynchronous chunk method; it never falls back to synchronous chunk loading. The current PNG map uses its independent saved-region renderer.

The two current 26.3 APIs also differ in the binary shape of `GameRule`: the pinned Spigot API declares an interface while actual Paper build 169 declares an abstract class. `GameRules` binds the runtime public type/World methods by reflection and reads registry keys through the stable `Keyed` interface. Worlds and typed rule edits therefore avoid a compiled class/interface invocation mismatch. Existing Boolean/Integer validation and conservative numeric limits remain in GameService; no command fallback or private API is used.

Moderation commands resolve online names or a public existing-profile cache when available. On a server without that cache API, an offline target requires a canonical UUID. This path neither fetches remote profiles nor scans every disk-backed offline player on the main thread.

Paper covariantly changes the return type of `OfflinePlayer.getPlayerProfile()`. Calling this getter through its public runtime method and validating the common Bukkit profile interface avoids a Paper/Spigot method-descriptor mismatch. Inventory revision hashing uses the public native byte serialization when offered, otherwise serializes the owned ItemStack with Bukkit's object output stream. It does not deserialize browser-supplied objects.

Names, lore, titles, chat and MOTD use the common String API. Editing an item preserves its existing name/lore when the displayed plain text is unchanged. Explicitly changing rich text through these fields produces legacy text, rather than claiming preservation of every custom component. Command capture reports `outputCapture=bukkit-string-messages`: it captures bounded legacy String output; messages emitted through another sender API or directly to the server console may appear only in the console log.

## Truthful identity and metrics

The server brand comes from `Bukkit.getName()`. Minecraft version comes from a public `getMinecraftVersion()` when present, otherwise the exact version prefix of `Bukkit.getBukkitVersion()`. MCC's own version is not substituted for either value.

TPS uses the server's public `getTPS()` if it returns valid finite samples. Otherwise the already registered game tick task measures scheduler throughput with fixed one-second buckets. This fallback includes scheduling pauses, has a five-second warm-up, caps display samples at twenty and exposes its source as `scheduler-observation`. Short histories use the time actually observed for the 1/5/15-minute values. It is an observation of callbacks, not a claim to reproduce a kernel's internal TPS estimator.

MSPT uses public `getAverageTickTime()` if offered. Without that API its value is `null` and `msptAvailable=false`; tick spacing is not fabricated into an MSPT measurement. The immutable `platformMetrics` DTO exposes availability and source for both metrics. Entity counts prefer a native public getter; the common loaded-entity fallback is cached for 500 ms and does not load chunks.

## AdvancedBan ownership

`GameService.externalManaged(BooleanSupplier)` and `PunishmentEngine.externalManaged(BooleanSupplier)` receive `IntegrationService.externalPunishments()`. This is a cached, memory-only signal set for **any enabled AdvancedBan**, including an enabled version with an unsupported query API. The integration must be created before native moderation startup and the suppliers installed before restoration. Supplier errors are treated as externally managed.

While AdvancedBan is enabled, native MCC moderation cannot create, revoke, restore, expire, kick, mute, ban or unban through these game paths. Login/chat enforcement is paused too. Queued native callbacks recheck the signal on the server main thread; a provider that becomes enabled after a database request was queued cannot publish a new native ban. Existing histories remain queryable, and existing native bans are not automatically removed on provider activation. Shutdown also rejects queued enforcement.

The durable history can record a failed request or a pending native reconciliation when provider ownership changes during an asynchronous operation. This preserves evidence and avoids a hidden second enforcement layer. When AdvancedBan is disabled, native MCC behavior can resume and reconcile MCC-owned records. MCC does not pardon a separate native ban owned by another source.

## Verification performed

The whole Java source compiled against the pinned Spigot API during the targeted Gradle run. `OreLedgerTest` passed 10 tests; `PunishmentTest` passed 13, including provider activation, fail-closed behavior, retained histories and queued shutdown; `ServerPlatformTest` passed 4, covering exact version parsing, unavailable metrics, scheduler pauses, warm-up and immutable sample copies. Failures, errors and skipped tests were all zero. Three ore-view Node tests and TypeScript `--noEmit` also passed.

`GameRulesTest` passed three class/interface dispatch and typed rejection regressions. A standalone, non-server probe bound the public methods using both exact Spigot snapshot and actual Paper 26.3 build 169 API JARs. It did not initialize a server, change a world or replace the required new-JAR native world/API smoke. The subsequent complete Java test XML records 195 tests with zero failures, errors and skips.

These checks do not replace native Paper/Spigot startup, command/action smoke tests, listener event tests or browser visual inspection. Final assembled release checks are recorded separately.

Primary references: [official Spigot snapshot metadata](https://hub.spigotmc.org/nexus/content/repositories/snapshots/org/spigotmc/spigot-api/26.3-R0.1-SNAPSHOT/maven-metadata.xml), [Bukkit Server API](https://hub.spigotmc.org/javadocs/spigot/org/bukkit/Server.html), [OfflinePlayer API](https://hub.spigotmc.org/javadocs/spigot/org/bukkit/OfflinePlayer.html), [AttributeModifier API](https://hub.spigotmc.org/javadocs/spigot/org/bukkit/attribute/AttributeModifier.html) and [Paper's public profile interface](https://jd.papermc.io/paper/26.2/com/destroystokyo/paper/profile/PlayerProfile.html).
