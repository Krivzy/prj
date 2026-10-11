# Optional plugin integrations

MCC discovers enabled LuckPerms, CoreProtect, AdvancedBan, AdvancedBanZ and GrimAC at request time. Missing or disabled plugins produce no integration section. An enabled plugin with an unsupported public API is reported as unavailable; MCC does not substitute invented data or console commands. IntegrationPresence observes plugin enable/disable events separately for the moderation ownership guard. Enabled LiteBans, BanManager and LibertyBans also own moderation: their entries are explicitly unavailable because this release has no adapters for their APIs.

The adapters use reflection exclusively against the plugin's public API, through its own class loader. They never open private members and require no bundled optional API dependencies. Add `LuckPerms`, `CoreProtect`, `AdvancedBan`, `AdvancedBanZ`, `LiteBans`, `BanManager`, `LibertyBans`, `GrimAC` to the existing plugin `softdepend` list so their providers are available before MCC initializes. Actual plugin/game compatibility remains the plugin publisher's responsibility; a matching API shape is not a Minecraft compatibility guarantee.

## Wiring contract

Create `IntegrationService(JavaPlugin, MainThread, BiPredicate<Auth.Session,Boolean>)` on the server thread. The predicate must check the exact active Auth session identity; its Boolean argument is true for a write. The service additionally enforces Admin/Owner reads and Owner writes. Close the service before closing MainThread. Queries and mutations return CompletableFuture results and must never be awaited on the server thread.

Suggested routes:

| Route | Service method | Access |
| --- | --- | --- |
| GET `/api/integrations` | `discover(actor)` | active Admin/Owner |
| POST `/api/integrations/{id}/query` | `query(actor,id,body)` | active Admin/Owner, normal CSRF checks |
| POST `/api/integrations/{id}/mutate` | `mutate(actor,id,body)` | active Owner, CSRF, tracked action, `confirmed:true` |

The write route must use MCC's existing tracked action IDs and audit handling, so a started provider save is not submitted twice when HTTP delivery times out. Do not treat a request timeout as evidence that a started write failed. The worker pool has two workers and sixteen queued operations; queued cancellation prevents execution. A provider future retains its worker reservation until it completes. This bounds concurrent provider operations even when an optional plugin stalls, but cannot forcibly terminate a plugin's own database/network task. Plugin availability and actor authority are rechecked after asynchronous loading and immediately before mutation. Reads recheck authority/availability before releasing data.

`discover` returns `{items:[{id,title,version,available,message,capabilities}],punishmentProvider}`. Adapter IDs are `luckperms`, `coreprotect`, `advancedban`, `grim`; ownership-only entries use `litebans`, `banmanager`, `libertybans`. AdvancedBanZ shares `advancedban` routes, with its actual title/version displayed. The standalone React `IntegrationsPage` consumes the routes above and existing MCC context/components; root navigation should hide its entry when no enabled integrations are discovered. The in-page buttons contain only discovered integrations.

## LuckPerms

Supported boundary: the LuckPerms 5 public provider/managers and PermissionNode/InheritanceNode builders. Real group storage loading, loaded users, offline user loading, normal permission nodes, contexts, expiry and inheritance are exposed.

Query bodies:

```json
{"action":"groups"}
{"action":"users"}
{"action":"holder","holderType":"group","target":"default"}
{"action":"holder","holderType":"user","target":"verified-player-uuid-or-name"}
```

Group and loaded-user lists return at most 200 rows and explicitly disclose truncation. The loaded-user list is not presented as all saved users. A name lookup uses LuckPerms' UUID cache; an unknown name requires a verified UUID instead of manufacturing an identity. Holder views return at most 500 normal nodes, their exact contexts/expiry/value, and an optimistic revision. Larger holders are read-only. LuckPerms itself loads all groups through its API; the output cap is not a storage-query cap.

Mutation examples, in addition to the root tracked action ID:

```json
{"action":"createGroup","target":"builders","confirmed":true}
{"action":"setNode","holderType":"group","target":"builders","expectedRevision":"holder-revision","nodeType":"permission","key":"minecraft.command.help","value":true,"contexts":{},"expiresAt":0,"confirmed":true}
{"action":"setNode","holderType":"user","target":"verified-player-uuid","expectedRevision":"holder-revision","nodeType":"inheritance","key":"builders","value":true,"contexts":{},"expiresAt":0,"confirmed":true}
{"action":"removeNode","holderType":"group","target":"builders","expectedRevision":"holder-revision","nodeType":"permission","key":"minecraft.command.help","contexts":{},"expiresAt":0,"confirmed":true}
```

`expiresAt` is UTC epoch seconds; zero means permanent. Up to eight single-value contexts are editable. Existing multi-value contexts remain visible and are not replaced by this editor. Edits affect only the matching key/context/expiry entry, preserving other contextual, temporary and transient nodes. Metadata/prefix/suffix/weight nodes are read-only. Primary group is displayed; primary-group selection, group deletion, bulk updates and track operations are outside this initial scope.

The revision rejects changes observed since the holder was opened, and bounded traversal rejects inheritance cycles. Local writes to a holder are serialized. LuckPerms does not expose a transaction shared with other editors: a simultaneous external edit can still race the revision check. Official save completion is awaited; a storage failure may leave LuckPerms' memory cache changed. Refresh/inspect the holder before retrying, rather than claiming an atomic rollback.

## CoreProtect

Supported boundary: enabled API version 9 or newer that actually exposes the public ten-argument `performPartialLookup` and `parseResult`. If bounded pagination is absent, this integration is unavailable; it does not fall back to unrestricted lookup.

```json
{"world":"loaded-world","x":0,"y":64,"z":0,"radius":8,"seconds":3600,"player":"","block":"STONE","action":"all","offset":0,"limit":50}
```

The world/location is required. Radius is 1–32 blocks, history range 1–86400 seconds, page size 1–100 and offset 0–10000. Optional player, Material block and removal/placement/interaction filters are validated. A location object is created on the server thread without accessing or loading a chunk. Database lookup/parsing runs off-main. The provider receives limit+1 records for a real `hasMore` receipt; the response contains at most limit rows with player, world, block, coordinates, UTC millisecond timestamp, action and rollback state.

This initial integration is read-only: no purge, rollback or restore endpoints are exposed. CoreProtect controls its own database query execution; MCC does not promise a hard query-duration limit.

## AdvancedBan and moderation ownership

Supported boundary: AdvancedBan 2 (`me.leoko.advancedban`) and AdvancedBanZ 4.0.1 (`net.hnt8.advancedban`) public PunishmentManager, UUIDManager, TimeManager, PunishmentType and Punishment constructor/create/delete methods. Only these exact plugin names and namespaces are accepted; other API shapes remain unavailable. AdvancedBanZ 4.0.1's public signatures were checked in the publisher's source and by offline signature inspection of the installed public JAR; this did not initialize the plugin or read/write its punishments. The adapter lists active punishments for a chosen player and actually creates/revokes ban, temporary ban, mute, temporary mute, warning, temporary warning and note records. IP sanctions, kicks, mass clear and history purge are unavailable in this initial scope.

```json
{"player":"KnownPlayer","uuid":"verified-player-uuid"}
{"action":"issue","player":"KnownPlayer","uuid":"verified-player-uuid","type":"mute","seconds":600,"reason":"Moderation reason","confirmed":true}
{"action":"revoke","player":"KnownPlayer","uuid":"verified-player-uuid","punishmentId":123,"confirmed":true}
```

UUIDs must be canonical. A mutation requires the name and UUID to match an online server player, or the public nonblocking cached-player API when the kernel provides it. On Spigot without that API, identity validation is online-only; MCC does not load all offline profiles or initiate a name lookup over the network. AdvancedBan's cached identity must also agree. Its official disabled UUID-fetcher mode uses lowercase player-name keys; other modes use the verified UUID without initiating provider REST requests. Duration zero is permanent; maximum ten years. Plain reasons exclude control characters and AdvancedBan layout substitutions. The operator is recorded as `MCC:<actor>`.

The provider performs actual storage/enforcement/history/event work. Positive record ID and a follow-up active record confirm creation; a follow-up absence confirms revocation. AdvancedBan itself may process expired records while serving its public lookup. The response list is capped at 100; its public player query has no database pagination contract. MCC does not access AdvancedBan's SQL store or rewrite provider data directly.

**Required root guard:** pass `service.externalPunishments()` to GameService and PunishmentEngine. This memory-only supplier is true for any enabled AdvancedBan, AdvancedBanZ, LiteBans, BanManager or LibertyBans, including unsupported API versions, and is updated by PluginEnable/PluginDisable events. Its enabled-name set prevents disabling one provider from unblocking MCC while another remains enabled. It must suppress built-in punishment initialization, periodic enforcement, login/chat enforcement and mutation routes while the external provider owns moderation. Preserve MCC's existing historical records without reapplying them. Existing builtin routes must reroute only explicitly supported actions to AdvancedBan/AdvancedBanZ or report external-provider ownership; never fall back silently. An unsupported enabled provider yields `punishmentProvider:"unavailable"`, empty capabilities and rejects integration reads/writes. Two or more enabled providers also make MCC moderation unavailable, rather than selecting a mutation target arbitrarily; both AdvancedBan names merge into a single unavailable UI entry with both actual titles/versions. Availability is checked again immediately before a supported mutation, including whether it is still the only enabled known punishment provider.

This allowlist cannot discover an arbitrary renamed/custom moderation plugin or a proxy-only provider outside the Bukkit process. Such providers need an explicit reviewed identity/API addition; MCC does not guess ownership from `/ban` command registration or plugin-name substrings. Disabling the final known provider permits native moderation again; old MCC records are preserved and are never migrated or copied into the external provider by this change.

## GrimAC

The reputable anti-cheat boundary is the current official GrimAPI provider, started API, GrimUser `runSafely`, `getChecks` and public AbstractCheck name/violation methods. Other versions are unavailable. No private Grim implementation internals or NoCheatPlus internals are inspected.

Query `{ "uuid":"verified-player-uuid" }` returns at most 128 real check names/violation readings, or `tracked:false` when Grim has no such user. The snapshot executes on the player's packet thread through the public `runSafely` contract. These readings are diagnostics rather than proof of cheating. This initial scope provides a per-player read-only snapshot; it does not claim a historical alerts feed, change checks/setbacks or automatically punish players. Root OreMonitor can link/open the same player diagnostic view when this integration is available.

## Verification and limits

Contract tests exercise public-interface dispatch, specialized static builders, discovery absence/disable, unavailable external-provider ownership, multi-provider lifecycle/ambiguity, AdvancedBanZ namespace selection and class-loader failures, role/confirmation checks, queued cancellation/capacity, revoked actors, actual paged CoreProtect parsing, LuckPerms revision preservation and inheritance rejection. The relocated AdvancedBanZ adapter is exercised with isolated in-memory public-contract fixtures for query/create/delete; these fixtures are test-only and are never bundled with MCC. Passing these tests is not evidence that optional plugin versions were installed or their mutations were exercised in production. Native optional-plugin checks and browser pixel checks must be recorded separately after root wiring.

Official sources checked for this implementation:

- [LuckPerms UserManager asynchronous API](https://github.com/LuckPerms/LuckPerms/blob/master/api/src/main/java/net/luckperms/api/model/user/UserManager.java)
- [LuckPerms GroupManager](https://github.com/LuckPerms/LuckPerms/blob/master/api/src/main/java/net/luckperms/api/model/group/GroupManager.java)
- [LuckPerms Node contract](https://github.com/LuckPerms/LuckPerms/blob/master/api/src/main/java/net/luckperms/api/node/Node.java)
- [CoreProtect API documentation](https://docs.coreprotect.net/api/version/v11/)
- [CoreProtect public paged lookup implementation](https://github.com/PlayPro/CoreProtect/blob/master/src/main/java/net/coreprotect/CoreProtectAPI.java)
- [AdvancedBan Punishment API](https://github.com/DevLeoko/AdvancedBan/blob/master/core/src/main/java/me/leoko/advancedban/utils/Punishment.java)
- [AdvancedBan UUID modes](https://github.com/DevLeoko/AdvancedBan/blob/master/core/src/main/java/me/leoko/advancedban/manager/UUIDManager.java)
- [AdvancedBanZ official source and API](https://github.com/At87668/AdvancedBanZ)
- [AdvancedBanZ namespace/version](https://github.com/At87668/AdvancedBanZ/blob/master/gradle.properties)
- [AdvancedBanZ Bukkit plugin identity](https://github.com/At87668/AdvancedBanZ/blob/master/bukkit/src/main/resources/plugin.yml)
- [AdvancedBanZ public Punishment API](https://github.com/At87668/AdvancedBanZ/blob/master/core/src/main/java/net/hnt8/advancedban/utils/Punishment.java)
- [LiteBans official configuration and moderation ownership](https://gitlab.com/ruany/LiteBans/-/blob/master/config.yml)
- [BanManager official source](https://github.com/BanManagement/BanManager)
- [LibertyBans official command ownership](https://github.com/A248/LibertyBans/blob/master/docs/Commands.md)
- [GrimAPI](https://github.com/GrimAnticheat/GrimAPI)
- [Paper nonblocking cached player lookup](https://jd.papermc.io/paper/26.2/org/bukkit/Bukkit.html#getOfflinePlayerIfCached(java.lang.String))
