# Автоматизации 0.8 · Graph v1

Графы и профили хранятся в SQLite. Каждое включённое правило во включённом профиле реагирует на свои серверные события, даже когда браузер закрыт. Ручная кнопка — дополнительная проверка, а не обязательный способ запуска. Новые правила и профили по умолчанию выключены; переход в enabled требует `confirmed:true`. Доступ к просмотру и изменению автоматизаций имеют Admin и Owner.

## HTTP

| Метод | Путь | Результат |
|---|---|---|
| GET | `/api/automations` | Сохранённые Graph DTO, включая адаптированные legacy rules |
| GET | `/api/automations/catalog` | 14 triggers, 20 conditions, 24 actions и реальные ограничения |
| POST | `/api/automations` | Проверка и сохранение полного графа; подтверждение включения |
| DELETE | `/api/automations/{id}` | Удаление с отменой оставшихся узлов |
| GET | `/api/automations/profiles` | Независимые профили с `id`, `name`, `enabled` |
| POST | `/api/automations/profiles` | Создание или сохранение профиля; подтверждение включения |
| DELETE | `/api/automations/profiles/{id}` | Только пустой профиль; основной профиль удалять нельзя |
| POST | `/api/automations/{id}/run` | `{id:requestUUID, confirmed:true, context:{world?,uuid?}}` |
| GET | `/api/automations/runs/{runId}` | Текущее состояние запуска |
| GET | `/api/automations/{id}/runs?limit=10` | История правила, limit 1–50 |
| GET | `/api/automations/{id}/export` | Legacy export только для представимого trigger→action |

Обычная авторизация, CSRF и module gate действуют для всех изменяющих запросов. Отключённый модуль возвращает штатную недоступность; редактор не запрашивает выключенный каталог игровых ресурсов. Item/effect IDs можно ввести вручную, реестр проверяет сервер. Выключение профиля сохраняет его графы и историю.

## Документ и профили

```json
{
  "id":"c94a49d4-9ba5-4949-a4f9-2ba09bbcb67d",
  "profileId":"00000000-0000-4000-8000-000000000001",
  "name":"Утро без игроков","enabled":false,"world":"world",
  "cooldownSeconds":30,"graphVersion":1,
  "nodes":[
    {"id":"start","type":"trigger","config":{"trigger":"interval","intervalSeconds":30},"pos":{"x":0,"y":0}},
    {"id":"if","type":"condition","config":{"condition":"onlineCount","operator":"eq","value":0},"pos":{"x":240,"y":0}},
    {"id":"day","type":"action","config":{"action":"world.time","params":{"time":6000}},"pos":{"x":480,"y":0}}
  ],
  "edges":[
    {"id":"one","source":"start","target":"if","sourceHandle":"next"},
    {"id":"two","source":"if","target":"day","sourceHandle":"true"}
  ]
}
```

`profileId` необязателен у старых документов: они относятся к основному профилю с указанным выше UUID. Основной профиль первоначально включён, чтобы сохранить прежние enabled rules; последующее сохранённое выключение сохраняется после restart. Новый пользовательский профиль первоначально выключен. Предел — 20 профилей и 100 правил в сумме.

Сохранение включённого нового правила или переход false→true требует `confirmed:true`. Редактор запрашивает подтверждение. Изменение уже включённого правила отменяет текущий запуск; следующий сигнал использует новую сохранённую версию. Ручная проверка может запустить выключенное правило с явным подтверждением; она не включает его постоянные triggers.

## Triggers

`interval`, `join`, `quit`, `death`, `respawn`, `worldChange`, `advancement`, `mobKill`, `blockBreak`, `blockPlace`, `bedEnter`, `levelChange`, `weatherChange`, `eventComplete`.

Единственный trigger на граф. Интервал и cooldown 30–86400 секунд. При restart автоматический интервал сначала выдерживает cooldown; пропущенные запуски не воспроизводятся пачкой. Signal до истечения cooldown не становится подходящим из-за задержки worker. Контекст содержит только разрешённые простые поля: UUID, name, world, material, mob, advancement, level, preset, eventId. Interval не содержит игрока: задайте UUID явно для player conditions/actions.

Высокочастотный listener сначала проверяет наличие подписчиков. Break/place проверяются следующим tick: окончательная cancellation и фактическое изменение уже загруженного блока. Bed trigger требует успешный вход в кровать. Сигнал завершения события относится к `completed`; его сущности уже очищены, поэтому остановка по этому ID не оживляет событие. После quit online-действия могут быть недоступны: используйте `playerOnline`.

## Conditions

| Группа | Типы | Значения |
|---|---|---|
| Сервер | `onlineCount`, `tps`, `chance` | Число игроков, TPS, процент 0–100 |
| Мир | `worldTime`, `weather`, `worldPlayers`, `difficulty`, `environment`, `pvp` | Typed number/string/boolean |
| Игрок | `playerOnline`, `playerHealth`, `playerFood`, `playerLevel`, `playerGamemode` | Реальный online state и текущие параметры |
| Контекст игрока | `playerPermission`, `playerHasItem`, `playerEffect`, `playerBiome`, `playerY`, `playerDistance` | Bukkit permission, material/amount, effect, biome, height, loaded-location distance |

Числовые сравнения `eq/ne/lt/lte/gt/gte`; для строк/boolean только `eq/ne`. Alias `neq` нормализуется в `ne`. UUID/world берутся из config, затем trigger context; world также наследуется из поля графа. Пустые UUID следует удалить. Реестры, инвентарь и состояние Bukkit читаются на main thread; permission — обычное право Bukkit, а не интеграция с внешним плагином. MCC не загружает чанки ради проверки biome/teleport/event locations.

TPS threshold ограничен 0–20; фактический optional platform TPS может быть чуть выше 20. Предпочтительнее `gte 19`, чем `eq 20`. Неизвестная метрика обрабатывается по общему контракту ServerPlatform; она не сертификат производительности.

## Actions

| Группа | Разрешённые действия |
|---|---|
| Мир | `world.time`, `world.weather`, `world.difficulty`, `world.pvp`, `world.gamerule` |
| Сообщения | `chat.broadcast`, `player.message`, `player.title`, `player.actionbar`, `player.sound` |
| Предметы и эффекты | `player.give`, `player.effect`, `player.firework`, `player.particles` |
| Параметры игрока | `player.heal`, `player.feed`, `player.xp`, `player.level`, `player.extinguish`, `player.glowing`, `player.teleport` |
| События | `event.start`, `event.pause`, `event.stop` |

GameService проверяет typed arguments и lifecycle событий при каждом исполнении. Player/entity snapshot modules управляют наблюдением и HTTP/UI-каталогами; их OFF не запрещает самостоятельные типизированные game actions включённого automation engine и не включает обратно periodic captures. Duration эффекта и погоды измеряется в ticks; title duration — seconds; teleport требует уже загруженный chunk. Game rule выбирается из реальных typed правил текущего мира. `event.start` принимает конфигурацию [событий](EVENTS.md), включая loot, checkpoint route и центр spawn/XYZ. `player.give` сохраняет существующие metadata item DTO.

Для `event.stop` и `event.pause` выбирается ровно один источник ID: действующий `eventId` (совместимый alias `id`), `fromTrigger:true` из контекста, либо `fromLastEvent:true`. Последний вариант — UI default для цепочки `event.start → delay → event.stop`: ID берётся только из последнего успешного `event.start` этого запуска. Он не заимствуется из другого профиля, предыдущего запуска, истории или контекста триггера; при отсутствии успешного старта узел завершается ошибкой. ID успешного старта сохраняется в ограниченном node result для проверки. После остановки и завершения события этот ID перестаёт быть активным; новая команда остановки/паузы не создаёт событие заново. Отмена запуска не останавливает уже созданное событие автоматически: оставшиеся узлы отменены, а событие сохраняет собственную длительность и может быть остановлено отдельно.

JavaScript, произвольные команды, OP, shutdown/restart и наказания отсутствуют в allowlist. Внешние plugin integrations в граф не включены.

## Порядок, пределы и lifecycle

Graph v1 — DAG: 32 nodes, 64 edges, 64 KiB JSON, все nodes достижимы из единственного trigger. Cycles, неизвестные nodes/handles, duplicate edges и отсутствующие endpoints отклоняются. Pos finite от −10000 до 10000. У condition handles `true/false`, у других `next`. Delay 0.05–60 секунд, сумма delay ≤60 секунд, общий deadline 90 секунд.

Топологический порядок и fan-out сохранены. Merge исполняется один раз, если активирован хотя бы один вход. Неактивная ветка получает skipped. Следующее действие начинается после результата предыдущего; ошибка прекращает хвост. Уже выполненные изменения не откатываются.

Одновременно 4 runs, не более 1 на правило. Queue signals 128, drain до 8 сигналов за проход с паузой 100 ms и обходом правил round-robin. Устаревшие сигналы старше 10 секунд отклоняются. Общий bucket ограничивает conditions/actions до 8 операций за 250 ms. Это ограничение числа вызовов, не обещание фиксированного времени каждого Bukkit вызова. Trigger capture/main ticks не ждут SQLite. JDBC/history/timers выполняет отдельный worker.

OFF очищает signals и отменяет active runs. Lifecycle epoch не позволяет уже извлечённому старому signal запуститься после OFF→ON. Run authority повторно проверяется непосредственно внутри queued main callback, поэтому отменённый профиль/правило не применяет ещё не начавшееся действие. Уже начавшийся неотменяемый callback может закончиться: history сообщает `effectMayComplete:true`, но хвост не запускается.

История SQLite namespace `automation-runs` ≤200, состояния queued/running/completed/failed/cancelled; node states pending/running/completed/skipped/failed/cancelled. WebSocket topic `automation` сообщает изменения. Штатный shutdown отменяет хвост до закрытия базы. После аварии незавершённые записи помечаются cancelled при reopen и не продолжаются. `automationErrors` включает cancelled; оценивать причину следует по конкретной записи. Монотонный bucket основан на system nano time; cooldown — captured wall-clock trigger time.

Legacy rules адаптируются при чтении без автоматического переписывания базы. При сохранении редактор использует v1. Legacy export сложного графа отклоняется, чтобы не терять branches/delays; старый сервер не обязан понимать добавленные в 0.8 trigger/action types.

## Проверки текущего исходника

Добавлены GameEventSpec/AutomationCatalog/AutomationProfiles regressions для typed catalogs, confirmation, SQLite profiles/reopen, двух автоматических профилей, queued callback cancellation, полled signal OFF→ON и capture-time cooldown. Старый interval test ожидает bounded asynchronous publication вместо синхронного pulse. Frontend tests проверяют независимость nested defaults, удаление stale event settings и точечное изменение loot row. TypeScript проверен после реализации редактора.

Текущая общая сборка исходника 0.8 прошла 269 Java, 78 Node и 11 Python проверок, TypeScript и Vite/Gradle; candidate SHA-256 `b34e1b5bab2cf22bc8aa12163dab7481e05b6ac82c50fb61b48a65248e5cb68a`. Четыре regression tests run-local event flow проверяют exact ID start/pause/delay/resume/stop, изоляцию разных запусков, отмену задержанного stop при OFF→ON и mutually exclusive typed ID sources.

На этом JAR 2026-10-11 изолированный Paper26.3 фактически выполнил два enabled interval graphs в двух включённых профилях. Браузер fixture в этом окне не открывался; ручные `/run` запросы отсутствовали. Оба broadcast action завершились автоматически. Эти первоначальные наблюдения относятся к своему процессу; последующая смена supervisor при подготовке загруженной области не переименовывает их в новые результаты.

Позже в интерфейсе фактически выполнен штатный requested restart и показана готовность нового процесса. До запуска AFTER-проверки среда исполнения была внешне прервана: прежние процессы исчезли, их незавершённая native серия сохранена отдельно как interrupted, без заявления о normal exit. Тот же fixture затем восстановлен на новом supervisor с прежними credentials, данными и ID профилей/правил. Побайтовая идентичность DB через прерывание не измерялась и не утверждается.

В восстановленном процессе AFTER-проверка фактически подтвердила сохранённые два enabled профиля и оба правила, новые автоматические завершения, созданные после начала проверки, и отсутствие ручных `/run`. Затем оба профиля выключены; за 33 секунды наблюдения новых Run ID не появилось. Это отдельное измерение persistence и отмены новых запусков в новом процессе, а не повторная маркировка первоначального browser-closed результата. Полная восстановленная core серия на этом JAR завершилась отдельно: 13 checkpoints, семь запусков Paper, normal stop exit code 0, отсутствие owned PID и закрытые fixture ports.

Отдельный выключенный QA graph выполнен одним подтверждённым manual `/run`: `event.start → delay1.3 → event.stop {fromLastEvent:true}`. Оба action nodes вернули один Event UUID, итоговое событие stopped/spawned1/failed0/ownedEntities0. Это actual run-local flow evidence, но не доказательство автоматического запуска этого графа. Полный каталог [событий](EVENTS.md) на том же JAR отдельно прошёл 24 one-pulse start/pause/resume/stop на внешнем QA loaded anchor.

Эти native сценарии не охватывают все 14 игровых triggers, 20 conditions и 24 actions. Реальные player events, победы и награды требуют воспроизведения с участниками; пустой fixture их не сертифицирует. Производственные game actions этим набором не выполнялись.

## Исторические проверки 0.3

Следующие сведения относятся к прежним JAR и scope, не подтверждают повторное выполнение нового каталога 0.8.

Проверки Java: 16 тестов `AutomationGraphTest` и `AutomationTest`, реальные SQLite файлы, обе ветки, merge, несколько действий, отмена, роли, ограничения и reopen; они входят в полный Java-набор 62 PASS. Отчёт: `verification/graph-v3-unit.json`.

На отдельном Paper26.3 build169 выполнены 16 HTTP/WS сценариев: обе ветки реально меняют время и погоду, после задержки выполняется общий узел; настоящий scheduler запускает interval-граф через 30 секунд; проверены история, legacy export, повтор запроса без повторного действия, запреты Moderator/Viewer для HTTP и WS, ошибки графа, подтверждение и отмена задержки. Отчёт `verification/graph-v3-main.json` содержит точный tested JAR SHA256 и хеши backend-классов. Исходные время и погода восстановлены, тестовые interval-графы выключены. Производственный сервер этим тестом не изменялся.

Снимки нагрузки в этом отчёте относятся к короткой проверке fixture и совмещены с другими QA. Среднее/максимальное время action накоплено с запуска сервера, включает холодные вызовы и другие запросы; это не доказательство отдельного overhead MCC или времени каждого действия меньше 2ms. Native player/chat события 26.3 в этом наборе не воспроизводились.

Полная сборка `4405736350d4e8803211169ecb96b31c851f5b9f4a896502fe1c8e9f6689ce7e` проверена 62 Java и 26 frontend тестами. В `verification/build-v3.json` подтверждено, что Automation, AutomationGraph и GameService в этой сборке побайтово совпадают с классами JAR, на котором выполнены 16 HTTP/WS сценариев. Отчёт основного запуска сохраняет исходный tested SHA256; его результаты не переименовываются в повторный запуск на новой сборке.

На этой финальной сборке отдельно выполнен настоящий shutdown внутри активной задержки 60 секунд и restart Paper: подготовка 1 PASS, persistence 4 PASS, cleanup 1 PASS. Позиции графа и завершённая история сохранились; незавершённая задержка и следующий world.time получили cancelled. Проверка дождалась исходного deadline плюс 2 секунды: следующий узел не выполнился, повторного запуска нет. Созданные этим набором графы удалены, исходные время и погода восстановлены. Отчёты: `verification/graph-v3-prepare-interruption.json`, `verification/graph-v3-persistence.json`, `verification/graph-v3-cleanup.json`.

Короткие снимки после restart: 0 игроков, 0 loaded chunks, TPS 20, пустые очереди и automationActive 0; последний MSPT 0.221ms и snapshotP95 0.344ms. Это проверка свободного тестового мира, а не нагрузочное испытание с игроками. Счётчики automationRuns/errors сбрасываются после restart; сохранённая история доступна отдельно.
