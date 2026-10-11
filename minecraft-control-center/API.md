# REST / WebSocket API

## Действующий контракт0.6

Карта0.6 использует постоянные PNG-тайлы. GET `/api/map/images?world=<name>` возвращает manifest, границы всей сохранённой FULL-поверхности, уровни пирамиды, состояние фонового обхода. GET `/api/map/images/tile?world=<name>&level=<n>&x=<n>&y=<n>` отдаёт PNG256×256, приватное кеширование60s и ETag/304. GET `/api/map/players?world=<name>` возвращает только текущие позиции игроков. Все требуют активную сессию и включённый модуль карты. Старые `/api/map`, `/api/map/terrain`, `/api/map/index`, `/api/map/detail`, `/api/map/region` и `/api/build/*` отвечают410 после аутентификации. Запрос изображения не читает игровые чанки и не запускает рендер.

GET `/api/plugins/catalog?query=&sort=downloads&offset=0&limit=12&compatible=true&featured=false` доступен Admin/Owner; точная версия Minecraft берётся из Paper runtime. GET `/api/plugins/catalog/plan?projectId=<id>` доступен Owner и выдаёт actor-bound план на5min со стабильным совместимым релизом и обязательными зависимостями. POST `/api/plugins/catalog/install` требует Owner, CSRF, `confirmed:true`, `planId`, UUID `id`. Долгая операция отвечает202 с `pending:true`; GET `/api/actions/<id>` возвращает итог. GET `/api/plugins/catalog/installed` различает pending JAR и реально включённый PluginManager plugin. Установка требует обычный рестарт; `/reload` не используется.

DELETE `/api/users/<username>` требует активный Owner и CSRF. Нельзя удалить себя либо последнего Owner; все сессии удалённого пользователя отзываются, audit остаётся. Новая пустая loopback-установка создаёт единственный admin/admin с Owner-правами; UI называет роль «Администратор панели». `defaultPassword` в auth/status обозначает начальный пароль. Такая учётная запись не допускается при allow-lan/public-origin либо нелокальном TCP source. Обычные обновления не меняют существующих пользователей.

Подробности: [карта](docs/ISOMETRIC-MAP.md), [плагины](docs/PLUGIN-CATALOG.md), [пользователи](docs/LOCAL-ADMIN.md). Более ранние контракты ниже сохраняются как история и применяются только там, где0.6 их не заменяет.

Base: `http://localhost:8080`. API ответы JSON; authenticated изменения требуют cookie `MCC_SESSION` и header `X-MCC-CSRF`. Получите CSRF из `/api/auth/status` или результата login/setup. Browser credentials: same-origin. Примеры не содержат credentials.

| Endpoint | Метод | Назначение |
|---|---|---|
| /api/auth/status | GET | setupRequired/authenticated/user/csrf |
| /api/auth/setup | POST | {token,username,password}, только loopback, один Owner |
| /api/auth/login | POST | {username,password} |
| /api/auth/logout | POST | Завершить сессию |
| /api/state | GET | Последний безопасно снятый реальный snapshot |
| /api/players | GET | Онлайн- и известные игроки |
| /api/players/{uuid} | GET | Inspector, статистика/полученные достижения |
| /api/players/{uuid}/inventory?kind=main\|ender | GET | Слоты и revision |
| /api/worlds | GET | Загруженные миры, seed по роли |
| /api/catalogs | GET | Реальные материалы/mobs/effects/enchantments/sounds |
| /api/entities?world=world&x=0&y=64&z=0&radius=16 | GET | Загруженные ближайшие living mobs, максимум100 результатов |
| /api/events | GET | Активные игровые события |
| /api/plugins | GET | Установленные плагины |
| /api/plugins/staged | GET | Owner: загруженные inert JAR |
| /api/plugins/stage | POST | Owner: raw JAR, X-MCC-Filename, CSRF, ≤16 MiB |
| /api/map?world=world&x=0&z=0 | GET | Включённый map module, terrain64×64, step2, loadedOnly |
| /api/metrics?range=5m\|1h\|24h | GET | Фактическая история, максимум360 точек |
| /api/audit | GET | Последние500 записей Audit |
| /api/chat, /api/console | GET | Ограниченная история текущего запуска |
| /api/automations | GET/POST | Список/сохранение правила |
| /api/automations/{id} | DELETE | Удаление правила |
| /api/backups | GET/POST | Owner: список/создание MCC backup |
| /api/settings | GET/POST | Профиль и переключатели модулей |
| /api/users | GET/POST | Owner: пользователи {username,password,role} |
| /api/action | POST | Единый dispatcher |
| /api/actions/{id} | GET | Фактический результат pending action этого пользователя |

## Action Dispatcher

```json
{"id":"<fresh UUID>","action":"world.time","params":{"world":"world","time":1000},"confirmed":false}
```

200: `{ok:true,result:<actual>,sequence:<number>}`. Для пакетного спавна result.status=`queued`; настоящий итог придёт topic=`spawn`. Наличие `ok:true` здесь подтверждает принятую сервером очередь, а не создание всех requested mobs. Если уже начатая синхронная операция выполняется более10 секунд, API возвращает202 `{ok:false,pending:true,id,...}`: клиент проверяет `/api/actions/{id}`. Истёкшая ещё не начатая операция отменяется. Повтор того же id и тех же параметров в пределах10 минут не выполняет действие заново; изменённые параметры с тем же id отклоняются. Перезапуск сбрасывает dedup cache.

Ошибки: 400 validation/unsupported operation/явный JSON size check; 401 session missing; 403 permissions/CSRF/Origin/Host; 409 inventory conflict; 413 HTTP body limit; 429 rate limit; 503 queue/module availability. При потере связи обновите реальное состояние; не создавайте новый id для слепого повтора опасного действия.

Основные action roots: `world.*`, `server.settings`, `player.*`, `players.heal_all`, `inventory.set/move`, `mob.spawn/modify`, `chat.broadcast`, `console.command`, `event.start/pause/stop`. Поддержанные параметры валидируются в `GameService.java`; ограничения игровых операций описаны в [docs/GAME-API.md](docs/GAME-API.md). Интерфейс предоставляет подмножество этих действий, перечисленное в [docs/VISUAL-AUDIT-0.6.md](docs/VISUAL-AUDIT-0.6.md).

Inventory mutation требует {uuid,kind,revision,...}. move меняет местами {from,to}; set задаёт {slot,item}, null удаляет предмет после явного подтверждения. item содержит material/amount и поддержанные optional поля.

## WebSocket

Authenticated same-origin `/ws`. Начало: `{type:'hello',sequence}`. Подписка: `{type:'subscribe',topics:[...]}`. Данные: `{type:'event',topic,sequence,data}`. Heartbeat: `{type:'ping'}` → `{type:'pong',sequence}`.

Topics: state, players, world, inspector, inventory, spawn, events, chat, console, timeline. Права на темы фильтруются сервером. Sequence используется для отсечения повторов, но пропуски sequence нормальны при подписке на часть тем. После reconnect клиент получает полный `/api/state` и `/api/players`. State публикуется только при новом игровом snapshot; все игроки/миры не отправляются каждый тик. Текущая версия передаёт полные snapshots для выбранных тем; универсальный field-level delta протокол запланирован.


## API0.3: графы и потоковая карта

- GET `/api/automations/catalog`: допустимые узлы/события/условия/действия и лимиты (Admin/Owner).
- GET/POST `/api/automations`: сохранённые graphVersion1 правила с nodes/edges/pos; старые flat правила преобразуются при чтении.
- POST `/api/automations/{ruleId}/run`: `{id:<freshUUID>,confirmed:true,context:{world,uuid?}}`, возвращает RunDTO; повтор того же id с тем же телом не создаёт второй запуск.
- GET `/api/automations/runs/{runId}` и `/api/automations/{ruleId}/runs?limit=10`: действительные состояния шагов и история (до50 за запрос).
- GET `/api/automations/{ruleId}/export`: flat экспорт только для представимого простого графа.
- WebSocket topic `automation`: снимок RunDTO каждого шага, только Admin/Owner. Контракты: docs/GRAPH-API.md.
- GET `/api/map/index?world=...`: сохранённое Anvil покрытие регионов, bounds в координатах чанков, bitmap presence, truncated. `savedChunks` считает занятые header slots, включая proto chunks; это не число полностью сформированных FULL чанков.
- GET `/api/map/terrain?world=...&cx=0&cz=0&radius=2&step=1`: состояния плиток ready/loading/processing/missing/deferred/error и реальные верхние блоки. `cx/cz` — координаты чанков; `radius` 0–3. Совместимый параметр `step` принимает 1/2/4, но плитки всегда содержат полный surface16×16 с `step:1`; прореживание выполняет браузер. `generate=false`, общий ограниченный cache/queue.

Map endpoints требуют включённый модуль и действительную сессию. Карта доступна только для просмотра: fixed isometric Canvas2D, pan/zoom, без вращения и редактирования. GET/POST/DELETE к `/api/build` и всем `/api/build/*`, `/api/map/detail`, `/api/map/region` отвечают authenticated **410 feature_removed**; они не вызывают game worker и не читают тело. Другие HTTP методы получают405. BuildEngine и build WebSocket topic удалены. Существующие SQL документы `build-history` сохраняются как архив; схема и игровые миры не изменяются.

Ready tile: `{cx,cz,size:16,step:1,status:'ready',heights:(number|null)[256],blocks:(number|null)[256],palette:[{material,blockData,color}],sampledAt,generated:true}`. Индекс ячейки `x+16*z`, локальные x/z 0–15. `blocks` ссылается на palette. Null означает отсутствие доступной поверхности; `missing` только отсутствие сохранённого/полностью сформированного чанка, `deferred/error` допускают повтор запроса. Данные объёма `voxels/depth` больше не вычисляются и не передаются.

Top-level terrain response сохраняет `world/worldId,cx/cz,radius/step,tiles,players,pending,minY/maxY,limits,metrics,generatedOnly:true`. Читается не более256 поверхностных BlockData на snapshot вне игрового потока; снимок чанка захватывается сразу в main-thread async completion максимум один за tick. Кеш готового рельефа120 s, cache/pending64, asyncLoads1 (включая disk preflight), soft main-thread budget1 ms и TPS guard18. Plugin chunk tickets больше не добавляются; `metrics.tickets` и `limits.tickets` всегда0. Browser polling нужен только для pending viewport с bounded interval; после ready/hidden/unmount его следует остановить. Просмотр сохранённых чанков всё же вызывает ограниченные async loads, поэтому нулевая серверная нагрузка не заявляется.

`generatedOnly:true` описывает выдачу сохранённой готовой поверхности; загрузка использует `getChunkAtAsync(...,false,false)` после disk-only preflight Status=FULL. Preflight работает на общем worker1/queue8, ограничен1MiB record/2MiB decoded NBT/depth32/tags131072/array262144; gzip/zlib/raw поддержаны, external `.mcc` и другие codecs отвечают error без load. Неполный persisted chunk отвечает missing. Paper transient CHUNK_LOAD ticket защищает немедленный snapshot; MCC не удерживает Chunk до следующего tick и не добавляет ENTITY_TICKING plugin ticket. Историческая QA до этого изменения: run47c сохранил121 FULL с29 новыми proto slots, а run99ad после UI получил138 FULL/2021 slots. Поэтому старое async `generate=false` само по себе не являлось доказательством отсутствия соседних promotions; нулевая Anvil header growth не гарантируется.
