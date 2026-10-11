# События Minecraft Control Center 0.8

Полная страница предлагает 24 настраиваемых приключения: сохранены волны мобов, фейерверки, визуальная гроза и сокровища, добавлены 20 вариантов. Общие параметры — мир, центр XYZ/spawn, число импульсов/объектов, interval ticks, duration seconds и radius. Catalog описывает фактические поля и ограничения; сервер повторно проверяет всё до первого эффекта.

## Каталог

| ID | Приключение | Дополнительные параметры |
|---|---|---|
| `waves` | Волны мобов | curated mob, health, batch |
| `fireworks` | Фейерверки | HEX color, shape, flight power |
| `lightning` | Гроза | визуальные молнии, общий ритм и зона |
| `treasure` | Сокровища | material, amount, weight каждой строки loot |
| `sanctuary` | Заповедник | animal, adult/baby |
| `champion` | Чемпион арены | один curated mob, health, damage, boss bar |
| `xp-rain` | Дождь опыта | XP per orb |
| `supply-drop` | Снабжение | weighted resource/tool loot, amounts |
| `food-rain` | Пикник | edible loot, amounts, weights |
| `meteor-show` | Метеорный дождь | trail height, particle density |
| `aurora` | Северное сияние | HEX color, height, particle count |
| `music` | Концерт нотных блоков | sound, volume, base pitch |
| `healing-zone` | Лагерь восстановления | hearts per pulse |
| `feast-zone` | Общий стол | food points per pulse |
| `speed-trial` | Спринт | amplifier, effect duration |
| `moonwalk` | Лунная походка | jump amplifier, slow-falling duration |
| `glowing-hunt` | Охота на огоньки | mob, health, target score, weighted reward |
| `elite-patrol` | Элитный патруль | mob, health, leather/iron/diamond equipment |
| `slime-swarm` | Нашествие слизней | slime size; splitting blocked |
| `bee-garden` | Пчелиный сад | bounded peaceful bees |
| `snowball-arena` | Снежная арена | volley speed |
| `egg-hunt` | Поиск яиц | event-owned pickups, target score, reward |
| `checkpoint-race` | Гонка по маякам | 2–12 relative XYZ points, reach radius, reward |
| `fishing-festival` | Рыбацкий турнир | target catches, weighted reward |

Четыре общих поля: count 1–128, intervalTicks 20–1200, durationSeconds 10–3600, radius 0–32. Champion всегда один. Loot/reward 1–12 строк; material — существующий предмет, amount 1–64 и не больше реального stack size, weight 1–100. Одна weighted stack выбирается на импульс/победу; вес 2 даёт вдвое больший шанс, чем вес 1. Food rain отвергает несъедобные items. Маршрут содержит relative offsets от −32 до 32; все точки заранее проверяются на loaded chunk и world border.

Поддерживаемые противники ограничены zombie/husk/drowned/skeleton/stray/bogged/spider/cave_spider/zombified_piglin. Взрывающиеся и меняющие terrain мобы не разрешены. Old aliases `animals` и `boss` принимаются как sanctuary/champion. Старый простой treasure `item` адаптируется в loot; расширенные произвольные item metadata не являются контрактом нового weighted event loot.

## API и интерфейс

- GET `/api/events/catalog`: presets, commonFields и limits.
- GET `/api/events`: `{active, history, enabled, limits}` вместо старого массива.
- Tracked action `event.start`: typed plan с preset/world и полями выбранного определения.
- `event.pause`: `eventId`, `paused` boolean.
- `event.stop`: `eventId`.

Start/stop/pause требуют обычную авторизацию, CSRF, Admin/Owner и enabled module. Tracked requests могут вернуть pending/202; клиент обязан опросить `/api/actions/{requestId}` до actual result. Пауза сохраняет оставшуюся длительность; performance pause отражается отдельно. Event DTO включает собственные counters, configuration и score summary. Ограниченная история 20 событий существует в памяти текущего event engine, не возобновляется после restart.

В automation-графе stop/pause также принимают `fromLastEvent:true`: ID только последнего успешно начатого события этого запуска, без глобального поиска. UI предлагает «Начатое этой цепочкой» по умолчанию. Предшествующий `event.start` обязателен; история других запусков не даёт права выбрать их событие этим флагом. Остальные источники — explicit Event UUID или `fromTrigger:true`; они не смешиваются. Отмена цепочки прекращает оставшиеся действия, но не отменяет автоматически уже начатое приключение.

Карточки используют 24 разных игровых предмета как иконки, Minecraft typography и существующие stone/green tokens. Category/search переключают selected configuration; смена adventure создаёт независимые defaults и не переносит случайно loot/effects другого типа. Конфигурация занимает целую страницу, activity/history доступны ниже. На узких экранах панели и resource rows складываются. Выключенный игровой каталог не вызывает HTTP503: resource/effect ID можно ввести вручную, sound сохраняет текущее значение. Server registry остаётся источником истины.

## Работа и cleanup

События исполняются только на main thread Bukkit, без async world access, force chunk load или generation. Anchor заранее должен быть загружен. Если случайная точка pulse выходит в незагруженный chunk, pulse получает failure вместо загрузки. Визуальные firework/lightning/meteor не взрывают и не изменяют blocks; snowballs и hostile mobs остаются обычными игровыми сущностями и могут взаимодействовать с игроками.

Пределы: 3 активных события (config может уменьшить), 200 owned entities, до 3 операций tick, целевой мягкий budget 750 µs. Один Bukkit spawn/callback может превысить target; это не hard real-time гарантия. Round-robin не даёт одному событию занять весь budget. TPS<18 запрещает новые события и приостанавливает effects; срок события продолжает идти при performance pause. Manual pause продлевает срок на время паузы.

Participants/score tracking ограничены 32, просмотр world player candidates до 128 за pulse с меняющимся началом. Support pulse применяет эффект одному выбранному игроку, поэтому больше участников означает больше нужных pulses. Race также проверяет одного участника за pulse. Hunting учитывает убийства owned mobs; egg hunt — успешный pickup собственных tagged items; fishing — successful catch в радиусе. Игрок может получить reward один раз в данном event. Если inventory переполнен, leftover не выбрасывается за пределами события и сообщается в error.

PDC ownership позволяет stop/expiry/module OFF убрать созданные event entities и boss bar. Unloaded tagged entities удаляются при последующей загрузке; engine не грузит их ради cleanup. Vanilla transformations, slime split и breeding owned entities блокируются, чтобы не потерять ownership. Полученные предметы, использованный XP и уже применённые короткие эффекты не откатываются. Native hostile drops и сторонние plugins могут влиять на gameplay: stop — cleanup собственного события, а не rollback мира.

На restart текущие events не продолжаются. Enabled [автоматизации](GRAPH-API.md) могут начать новое событие по следующему подходящему trigger. `eventComplete` сообщает только штатный completed event; stopped не выдаётся за completed.

## Проверки и ограничения evidence

Pure Java tests проверяют 24 typed definitions/defaults, bounds, unsafe mobs, loot/weights/route validation и aliases. Frontend tests проверяют независимые defaults, stale-field pruning и immutable row editing; TypeScript проверен. Это source/unit evidence. Текущая общая сборка исходника прошла 269 Java, 78 Node, 11 Python, TypeScript и Vite/Gradle, candidate SHA-256 `b34e1b5bab2cf22bc8aa12163dab7481e05b6ac82c50fb61b48a65248e5cb68a`. Run-local event flow покрывается четырьмя regression tests.

На этом точном JAR 2026-10-11 выполнена изолированная native проверка Paper26.3: каталог содержит 24 unique presets; для каждого фактически прошли start, один pulse, pause, resume и stop. Параметры count1/radius0/interval20/duration10, людей в игре нет. После каждой остановки active пуст и owned entity counter равен нулю. Получены 25 checkpoints, ошибок нет. Один отдельный подтверждённый manual run выключенного графа `start → delay1.3 → stop` успешно сверил один и тот же Event UUID у обоих action nodes; событие stopped, spawned1/failed0/ownedEntities0.

Loaded anchor для этих сценариев предоставил отдельный fixture-only QA addon. Это внешняя подготовка тестового мира, не chunk loading карты или MCC events. Предыдущая попытка на полностью выгруженном fixture была отклонена до создания первого события; её отрицательный результат сохранён отдельно. Принадлежащий QA force flag снят после сценариев. После внешнего прерывания среды тот же стенд восстановлен с сохранёнными credentials и данными, но прерванная серия не объявлена штатно завершённой и побайтовая идентичность DB через это прерывание не утверждается.

Новая полная native серия того же JAR завершилась отдельно: 13 core checkpoints, семь запусков Paper, штатная остановка с exit code 0, отсутствие принадлежащих ей Java/supervisor PID и закрытые fixture ports. QA marker оставался неактивным при восстановленных запусках; после закрытия addon выведен из plugins под offline supervisor lease. Это не новое выполнение 24 событий: положительные event lifecycle и event-flow результаты сохраняют свой первоначальный scope.

Пустой fixture не доказывает настоящие race wins, mob kills, item pickups, fish scoring или выдачу наград участникам. Healing/food/movement pulses без людей подтверждают lifecycle, но не изменение состояния игрока. Проверка defaults одного pulse не заменяет все комбинации loot/routes/нагрузку нескольких событий. Native проверка двух постоянных interval profiles описана в [Graph API](GRAPH-API.md). Production event actions этим набором не выполнялись.
