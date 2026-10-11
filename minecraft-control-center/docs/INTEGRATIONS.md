# Optional plugin integrations

MCC discovers enabled LuckPerms, CoreProtect, AdvancedBan, AdvancedBanZ and GrimAC at request time. Missing or disabled plugins produce no integration section. An enabled plugin with an unsupported public API is reported as unavailable; MCC does not substitute invented data or console commands. IntegrationPresence observes plugin enable/disable events separately for the moderation ownership guard. Enabled LiteBans, BanManager and LibertyBans also own moderation: their entries are explicitly unavailable because this release has no adapters for their APIs.

The adapters use reflection exclusively against the plugin's public API, through its own class loader. They never open private members and require no bundled optional API dependencies. Add `LuckPerms`, `CoreProtect`, `AdvancedBan`, `AdvancedBanZ`, `LiteBans`, `BanManager`, `LibertyBans`, `GrimAC` to the existing plugin `softdepend` list so their providers are available before MCC initializes. Actual plugin/game compatibility remains the plugin publisher's responsibility; a matching API shape is not a Minecraft compatibility guarantee.

## Wiring contract

Create `IntegrationService(JavaPlugin, MainThread, BiPredicate<Auth.Session,Boolean>)` on the server thread. The predicate must check the exact active Auth session identity; its Boolean argument is true for a write. The service additionally enforces Admin/Owner reads and Owner writes. Close the service before closing MainThread. Queries and mutations return CompletableFuture results and must never be awaited on the server thread.

Implemented routes:

| Route | Service method | Access |
| --- | --- | --- |
| GET `/api/integrations` | `discover(actor)` | active Admin/Owner |
| POST `/api/integrations/{id}/query` | `query(actor,id,body)` | active Admin/Owner, normal CSRF checks |
| POST `/api/integrations/{id}/mutate` | `mutate(actor,id,body)` | active Owner, CSRF, tracked action, `confirmed:true` |

The write route must use MCC's existing tracked action IDs and audit handling, so a started provider save is not submitted twice when HTTP delivery times out. Do not treat a request timeout as evidence that a started write failed. The worker pool has two workers and sixteen queued operations; queued cancellation prevents execution. A provider future retains its worker reservation until it completes. This bounds concurrent provider operations even when an optional plugin stalls, but cannot forcibly terminate a plugin's own database/network task. Plugin availability and actor authority are rechecked after asynchronous loading and immediately before mutation. Reads recheck authority/availability before releasing data.

`discover` returns `{items:[{id,title,version,available,message,capabilities}],punishmentProvider}`. Adapter IDs are `luckperms`, `coreprotect`, `advancedban`, `grim`; ownership-only entries use `litebans`, `banmanager`, `libertybans`. AdvancedBanZ shares `advancedban` routes, with its actual title/version displayed. The standalone React `IntegrationsPage` consumes the routes above and existing MCC context/components; root navigation should hide its entry when no enabled integrations are discovered. The in-page buttons contain only discovered integrations.

## LuckPerms

### Редактор и autocomplete 0.8

Группы/загруженные игроки находятся слева; справа выбранный holder, таблица прав, вкладки inheritance/metadata, add bar снизу. Схема следует [официальному Web Editor](https://github.com/LuckPerms/LuckPerms/wiki/Web-Editor/739791d24e41f1991b485c9cf6aa9ea42554f8db), но операции сразу сохраняются отдельными подтверждёнными API запросами; общего staged-save нет. Контексты вводятся key/value, expiry — datetime; право можно дать группе или проверить известного offline игрока через UUID.

`POST /api/integrations/luckperms/query` с телом `{id:"<unique action UUID>",action:"suggestions",search:"<text>",limit:20}` и штатной CSRF-проверкой читает объявленный Bukkit permission registry и children/descriptions, объединяемые UI с текущими node keys. Кеш — 30 секунд, registry cap — 8192, ответ — до 40 подсказок, `search` — до 200 символов. Prefix совпадения первыми, дубли удаляются. Необъявленные wildcard/динамические права не придумываются; точное право можно ввести вручную. Keyboard: up/down/Enter/Escape, late responses отвергаются. Inheritance подсказывает реальные имена существующих групп.

Integration OFF отменяет queued операции, очищает кеш и отклоняет старые результаты; lifecycle epoch и повторные authority checks предотвращают возобновление прежней работы при быстром OFF→ON. Уже начавшаяся запись во внешнем provider не отменяется гарантированно; при неопределённом результате holder нужно обновить перед повтором. Внешний владелец наказаний остаётся независимо обнаруживаемым, встроенные санкции не разблокируются отключением integration UI.


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

### Редактор наказаний 0.9

Supported boundary: AdvancedBan 2 (`me.leoko.advancedban`) and AdvancedBanZ 4.0.1 (`net.hnt8.advancedban`) public PunishmentManager, UUIDManager, TimeManager, PunishmentType and Punishment constructor/create/delete methods. Only the exact reviewed plugin names and namespaces are accepted. AdvancedBanZ 4.0.1's public JAR exposes both bounded SQLQuery enum constants and the public `getPunishments(SQLQuery,Object...)` method; this was inspected offline without initializing the plugin. LiteBans, BanManager and LibertyBans remain ownership-only/unavailable entries, not working editors.

The editor uses the existing `/api/integrations/advancedban/query` and `/mutate` routes. A cached player list, history/active tabs, type/status filters and a detailed record show actual player, reason, moderator, issue/expiry time and source `{id,title,version}`. Integration metadata refreshes automatically while the page is visible; the redundant “Check plugins” button is removed from the shared integration header. Admin/Owner can read. Only Owner can issue/revoke with the existing confirmation, CSRF, tracked action and audit guards.

Query examples (the normal tracked action ID is also required by the route):

```json
{"action":"players","search":"Known","offset":0,"limit":50}
{"action":"history","player":"","type":"all","status":"all","offset":0,"limit":25}
{"action":"active","player":"KnownPlayer","type":"mute","status":"active","offset":0,"limit":25}
```

`IntegrationService.cachedPlayers(Supplier<List<Map<String,Object>>>)` accepts already-restored memory-only `{name,uuid}` identities. Root wires `GameService.knownPlayerIdentities`; online public Bukkit identities take precedence for the same UUID. Traversal/output bounds are 2048 identities/50 suggestions. Invalid identities are ignored; ambiguous name mappings cannot authorize an edit. A mutation also checks the public optional cached-player lookup where available. MCC never calls a network name resolver, scans all offline profiles, synthesizes a UUID or infers an IP identity.

The history boundary uses the provider's fixed public `SELECT_ALL_PUNISHMENTS_HISTORY_LIMIT` enum, active records its fixed `SELECT_ALL_PUNISHMENTS_LIMIT` enum, and requests at most 1001 rows per collection. MCC retains the latest 1000, exposes pages of at most 50, and reports `truncated`, `activeWindowTruncated`, `matchedInWindow`, `windowLimit`, `scope` and `hasMore`. Filters and totals refer to this recent window; this is **not a complete server-history export**. No arbitrary SQL or direct provider database access is available. A provider that ignores the limit is rejected. Older API shapes without these bounded constants cannot expose the global history; their existing explicit-player active lookup remains limited at the response boundary and is disclosed as a legacy provider query without a database-query cap.

History/active query bounds are `offset:0..1000`, `limit:1..50` (default 25). Type filters are `all`, `ban`, `mute`, `warn`, `note`, `kick`; status filters are `all`, `active`, `inactive`, `expired`, `unknown`, `recorded`. The cached-player query uses `search` up to 32 characters, `offset:0..2048`, and `limit:1..50` (default 50). An empty player filter selects the bounded global history; it does not fetch an unrestricted export.

History and active records have different database IDs. The DTO exposes `historyId` and `activeId` separately and joins only a unique semantic record (provider target key, moderator, start/end/type). Updating an active reason changes its fingerprint without rewriting the historical reason. Duplicate/ambiguous non-expired matches and unmatched non-expired rows in a truncated active window produce `unknown`, with no revoke action. A unique visible active match can still be displayed as `active` when that window is truncated. Other states are `expired`, `inactive`, and `recorded` for instant kicks. `inactive` means no matching active record in the complete bounded active window; it does not certify a manual revocation. The API has no historical revocation actor/time/reason, so `revocationDetailsAvailable:false` and the UI explains the limitation. A record's public UUID is returned only when the provider key is a valid UUID; IP/name keys never become fabricated UUIDs. `identityVerified`/`identityUuid` separately identify a unique matching server-cache identity for safe mutation.

Mutation examples:

```json
{"action":"issue","player":"KnownPlayer","uuid":"verified-player-uuid","type":"note","seconds":0,"reason":"Moderation note","confirmed":true}
{"action":"revoke","player":"KnownPlayer","uuid":"verified-player-uuid","punishmentId":123,"expectedRevision":"current-active-record-sha256","confirmed":true}
```

Supported issuance is ban, mute, warning and note (including temporary variants). IP sanctions, kicks, bulk clear and history purge are unavailable. Duration zero is permanent, maximum ten years; notes have no duration. Reasons reject control characters, provider layout substitutions and the apostrophe that this provider would strip. The operator is recorded as `MCC:<actor>`. The provider performs real storage/enforcement/history/event work; silent mode suppresses its broadcast, not enforcement. Positive active ID plus follow-up active lookup confirm issue, and follow-up absence confirms revoke. Provider lookups can themselves perform expired-record housekeeping.

Revoke accepts **only the active ID**, checks the target identity and current fingerprint again immediately before the public delete, and requires a displayed/issued fingerprint lease from the same panel session, exact plugin identity and integration lifecycle epoch. Lease TTL is five minutes with at most 2048 retained entries; OFF/close clear the leases, and a late previous-epoch query cannot revive them after ON. An expired lease or changed fingerprint yields a conflict; an absent active record is also rejected. Refresh before retrying. Local writes to a target are serialized. The provider has no public transaction shared with external editors, so an external edit can still race the final optimistic check; MCC does not claim atomic cross-plugin revocation or forcibly terminate provider database work.

**Required root guard:** pass `service.externalPunishments()` to GameService and PunishmentEngine. This memory-only supplier is true for any enabled AdvancedBan, AdvancedBanZ, LiteBans, BanManager or LibertyBans, including unsupported API versions, and is updated by PluginEnable/PluginDisable events. Its enabled-name set prevents disabling one provider from unblocking MCC while another remains enabled. It must suppress built-in punishment initialization, periodic enforcement, login/chat enforcement and mutation routes while the external provider owns moderation. Preserve MCC's existing historical records without reapplying them. Existing builtin routes must reroute only explicitly supported actions to AdvancedBan/AdvancedBanZ or report external-provider ownership; never fall back silently. An unsupported enabled provider yields `punishmentProvider:"unavailable"`, empty capabilities and rejects integration reads/writes. Two or more enabled providers also make MCC moderation unavailable, rather than selecting a mutation target arbitrarily; both AdvancedBan names merge into a single unavailable UI entry with both actual titles/versions. Availability is checked again immediately before a supported mutation, including whether it is still the only enabled known punishment provider.

This allowlist cannot discover an arbitrary renamed/custom moderation plugin or a proxy-only provider outside the Bukkit process. Such providers need an explicit reviewed identity/API addition; MCC does not guess ownership from `/ban` command registration or plugin-name substrings. Disabling the final known provider permits native moderation again; old MCC records are preserved and are never migrated or copied into the external provider by this change.

## GrimAC

The reputable anti-cheat boundary is the current official GrimAPI provider, started API, GrimUser `runSafely`, `getChecks` and public AbstractCheck name/violation methods. Other versions are unavailable. No private Grim implementation internals or NoCheatPlus internals are inspected.

Query `{ "uuid":"verified-player-uuid" }` returns at most 128 real check names/violation readings, or `tracked:false` when Grim has no such user. The snapshot executes on the player's packet thread through the public `runSafely` contract. These readings are diagnostics rather than proof of cheating. This initial scope provides a per-player read-only snapshot; it does not claim a historical alerts feed, change checks/setbacks or automatically punish players. Root OreMonitor can link/open the same player diagnostic view when this integration is available.

## Verification and limits

Contract tests exercise public-interface dispatch, specialized static builders, discovery absence/disable, unavailable external-provider ownership, multi-provider lifecycle/ambiguity, AdvancedBanZ namespace selection and class-loader failures, role/confirmation checks, queued cancellation/capacity, revoked actors, actual paged CoreProtect parsing, LuckPerms revision preservation and inheritance rejection. The relocated AdvancedBanZ adapter is exercised with isolated in-memory public-contract fixtures for query/create/delete; these fixtures are test-only and are never bundled with MCC. Passing these tests is not evidence that optional plugin versions were installed or their mutations were exercised in production. Native optional-plugin checks and browser pixel checks must be recorded separately after root wiring.

New 0.9 source regressions additionally cover history/active ID collisions, duplicate matching, truncated-window uncertainty, provider reason edits, pagination, UUID non-synthesis, cached identity ambiguity, source metadata, history retention after revoke, stale fingerprint rejection, five-minute session/plugin/epoch leases and bounded retention. The complete 0.9 source build passed 303 Java, 111 Node and 12 Python tests, plus TypeScript/Vite/Gradle. Native issue/query/stale-revision rejection/revoke checks passed on fresh isolated Paper and Purpur for the exact final 0.9 JAR; real provider history persisted through three normal restarts on each core. The final browser inspection displayed that history and source at desktop and mobile widths; previous native read checks are not relabelled as new mutation coverage. No production punishment or permission changes are part of these implementation tests.

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
- [AdvancedBanZ bounded public SQLQuery enum](https://github.com/At87668/AdvancedBanZ/blob/master/core/src/main/java/net/hnt8/advancedban/utils/SQLQuery.java)
- [AdvancedBanZ public manager/history methods](https://github.com/At87668/AdvancedBanZ/blob/master/core/src/main/java/net/hnt8/advancedban/manager/PunishmentManager.java)
- [AdvancedBanZ public Punishment API](https://github.com/At87668/AdvancedBanZ/blob/master/core/src/main/java/net/hnt8/advancedban/utils/Punishment.java)
- [LiteBans official configuration and moderation ownership](https://gitlab.com/ruany/LiteBans/-/blob/master/config.yml)
- [BanManager official source](https://github.com/BanManagement/BanManager)
- [LibertyBans official command ownership](https://github.com/A248/LibertyBans/blob/master/docs/Commands.md)
- [GrimAPI](https://github.com/GrimAnticheat/GrimAPI)
- [Paper nonblocking cached player lookup](https://jd.papermc.io/paper/26.2/org/bukkit/Bukkit.html#getOfflinePlayerIfCached(java.lang.String))
