# Текущая карта: surface-only Canvas2D

## Архитектура карты0.6

Просмотр карты запрашивает manifest из памяти, PNG из ограниченного tile cache/локального файла и позиции игроков. Он не читает Anvil, не вызывает ChunkSnapshot/getChunkAtAsync и не генерирует рельеф. Отдельный единственный worker минимального приоритета сканирует сохранённые данные независимо от зрителей; цикл≤8ms целевых квантов и duty≤10% с паузой при TPS<18/MSPT>45. Заголовки проверяются примерно раз60s, принудительная перепроверка сохранённых records раз300s. Первый render неизбежно расходует CPU/диск, последующие обновления ограничены изменёнными records. PNG256 и zoom pyramid хранятся на диске между перезапусками. Это архитектурные лимиты; жёсткое время выполнения отдельного декодирования/PNG writer зависит от оборудования и не является SLA.

Клиент использует ограниченное количество обычных `<img>`-тайлов, fixed projection, pan/zoom и отдельные player markers. Геометрия чанков, текстуры и 3D render loop отсутствуют. Видимый/скрытый lifecycle и интервалы определены в [ISOMETRIC-MAP.md](docs/ISOMETRIC-MAP.md). Каталог не запрашивается на других страницах, пагинирован, metadata cache ограничен; сетевые операции работают вне Minecraft main thread.

Native результаты0.6 приведены в [VISUAL-AUDIT-0.6.md](docs/VISUAL-AUDIT-0.6.md). Ниже сохранены измерения прошлых версий; они не доказывают FPS/CPU0.6 и не обещают нулевую нагрузку первого рендера.

Фиксированная изометрическая карта использует верхнюю поверхность каждого чанка: максимум256 BlockData reads вместо3072 позиций объёмного surface-depth sampler. `voxels/depth`, detail region и строительный engine удалены. Это доказуемое ограничение числа читаемых позиций, а не измерение CPU overhead: отдельный многопользовательский benchmark не выполнен. Browser рисует по запросу, без постоянного render loop и вращения.

Сервер сохраняет bounds, cache/pending64, asyncLoads1 (`generate=false`, включая preflight), один snapshot за tick при TPS≥18; обработка снимка выполняется serial worker1/queue8. Disk-only preflight принимает только persisted Status=FULL, с лимитами1MiB compressed/2MiB decoded/depth32; Bukkit API из worker не вызывается. Snapshot снимается немедленно в main-thread completion до освобождения Paper CHUNK_LOAD ticket; MCC не добавляет ENTITY_TICKING plugin tickets и не переносит Chunk на следующий tick. LowTPS/same-tick completion получает deferred без удержания. Разовый захват не прерывается по таймеру, поэтому main-thread budget1 ms остаётся мягким. Готовые tiles кешируются120 s. Просмотр далёких существующих чанков имеет ограниченную ненулевую нагрузку.

Историческая fixture QA до удаления plugin tickets: run47c (JAR0fa469…) сохранил121 FULL координату source, при этом добавил29 proto slots/15 status transitions; следующий run99ad (JAR05946…) после UI имел138 FULL/2021 slots. Exact26.3 bytecode подтверждает, что `generate=false` возвращает null для proto, но ENTITY_TICKING plugin ticket имеет level31 и допускает соседние FULL promotions; это объяснение не подменяет controlled before/after retest. Отсутствующий удалённый region file остался отсутствующим. Ниже исторические измерения старой карты и редактора сохранены с принадлежностью к прежним версиям.

Финальный повтор0.5.0 на JAR`eba023c37d590f53605b00af6d36d1e588c4553d1b0f4e5d3eb854f2d3501191` (run`c76b05eb-cf85-4481-ad08-029168c5f149`) после read-only desktop/mobile UI и normal stop сохранил весь closed baseline FULL set138→138 и slots2021→2021, без proto status transitions. Known proto`(-7,-3)` остался initialize_light и отвечал missing; source/seed bytes неизменны. Проверка ограничена этой fixture и коротким прогоном, а не гарантией для любого мира или Paper background activity.

# MCC 0.3.1 — сохранённые лимиты

Текущая очередь учитывает reservation/inflight до async load; нехватка tickets возвращает deferred вместо ложного missing. Лимиты прежние: cache/pending64, loads2, tickets8, snapshot1/tick soft1ms. Actual fixture после lease: cache50/pending0/loads0/tickets0. Это функциональный smoke, не benchmark/soak. Ниже сохранены исторические измерения0.3.

# MCC 0.3: ограничения нагрузки и фактическая QA

Проверено на локальном Paper26.3/Java25. Это короткие снимки во время функциональной проверки, **не отдельный benchmark overhead** и не 20 активных игроков/длительный soak.

Whole-world index читает Anvil headers в фоне, до2048 файлов, cache120 s. Terrain загружает только существующие FULL чанки с `generate=false`: cache/pending по64, 2 одновременные загрузки, до8 plugin tickets, один snapshot за tick с бюджетом1 ms; serial worker и очередь8. Exact detail удерживает до4 перекрывающихся чанков на actor lease45 s. Удержание начинается в main-thread async callback до последующего handoff; повторный запрос сохраняет пересекающиеся leases. После закрытия браузера и ожидания lease в реальном тесте tickets=0. Paper может временно держать собственных соседей; весь мир не загружается в память.

Build ограничен4096 блоками и выполняется порциями; чтение exact region тоже распределено по ticks. Low TPS приостанавливает terrain loads. Native place/break и MCC build инвалидируют ограниченный cache; physics/console изменения используют TTL120 s. Graph timers/SQL выполняются вне игрового потока; 4 runs global/1 rule, total delay60 s/deadline90 s, история200.

В конце main graph QA: TPS `[20.0, 19.999987880007343, 18.598076762879057]`, MSPT **0.13168910891089108 ms**, snapshot p95 **0.7232 ms**, gameQueue **0**, SQL queue **0**, dropped **0**. Счётчики накоплены за весь процесс: actionMean **3.6245945544554456 ms**, actionMax **597.7077 ms**. Предыдущие map/warmup действия входят в них; причина максимума отдельно не профилировалась. Поэтому потолок2 ms для всех действий не подтверждён. Точные samples и provenance: [graph-v3-main.json](verification/graph-v3-main.json), [MAP-V3-E2E.json](reports/MAP-V3-E2E.json), live samples — [v3-live-map-native.json](verification/v3-live-map-native.json).

Визуальная Three.js сцена ограничена30 fps, останавливается вне экрана/при hidden/reduced-motion и освобождает GPU resources при unmount. Инерция колеса прекращает rAF после достижения цели; native touch/trackpad/nested inputs/graph/map исключены. Browser measurement и размеры lazy bundles: [browser-v3.json](verification/browser-v3.json), [FRONTEND-DESIGN.md](frontend/FRONTEND-DESIGN.md). Эти browser измерения не относятся к CPU Java server.

---

# Фактические измерения MCC 0.1.0

Проверка выполнена 2026-10-10 на Windows 10 Pro 19045, Intel Core i5-10400F (6 cores / 12 logical processors), около 16 GiB RAM. Paper 26.1.2 build 74, Microsoft OpenJDK 25.0.1+8-LTS, `-Xms512M -Xmx1536M`, flat world, loopback network. Это локальный smoke test; компьютер с 2 GiB RAM, i3, 20 активных игроков и длительная работа под нагрузкой не проверялись.

CPU ниже измерен через Windows process times, в процентах **одного ядра для всей JVM Paper**. Это не CPU плагина отдельно и не общий процент загрузки компьютера. Working Set и private bytes относятся ко всему Java process. Процессы тестовых клиентов не включены в CPU Paper. Замеры каждые 2 секунды; TPS/MSPT также проверены штатными console commands Paper.

| Сценарий | Наблюдение | CPU mean, % одного ядра | Working Set mean, MiB | Private mean, MiB | TPS |
| --- | --- | ---: | ---: | ---: | --- |
| Paper без MCC, 0 игроков | 53 samples / 104,67 s после 40 s прогрева | 3,822 | 800,24 | 828,20 | 20 / 20 / 20 |
| Paper с MCC, панель закрыта | 53 samples / 104,7 s после 40 s прогрева | 3,618 | 822,48 | 851,13 | 20 / 20 / 20 |
| MCC, 3 authenticated WebSocket, 0 игроков | 48 samples / 94,65 s после 40 s прогрева JVM | 3,362 | 813,07 | 854,93 | 20 / 20 / 20 |
| MCC, 5 idle protocol clients + 1 WebSocket | 30 samples / 58,35 s, окно 60 s | 4,869 | 912,81 | 942,91 | 20 / 19,977 / 19,985 |

Сравнение idle Paper/MCC на ранней сборке 0.1.0 показало около **+22,23 MiB Working Set** и **+22,93 MiB private bytes**. Оно предшествовало исправлениям ownership/gamerules/redaction и является приблизительной исходной оценкой, а не строгим измерением overhead конечного JAR. Разница CPU меньше обычных колебаний короткого замера; отрицательную разницу нельзя интерпретировать как ускорение Paper плагином. Значения памяти между разными JVM и моментами GC также колеблются.

Полное 120-секундное окно 3 WebSocket было 14:32:46–14:34:46 UTC. Все 60 samples дали CPU mean 6,218%; начало включало завершение прогрева JVM. В таблице отдельно указан период после 40 секунд от запуска. При 3 WebSocket snapshot mean/p95 были **0,272 / 0,359 ms**, SQL errors, dropped events и очереди — 0. Native Paper last-minute MSPT в конце окна — около 0,2 ms.

Окно 5 idle clients + 1 WebSocket: 14:39:33–14:40:33 UTC. В начале и конце действительно находились 5 online players; WebSocket connection count — 1. View distance тестового мира — 3, simulation distance — 2, difficulty — peaceful, loaded chunks — 143. В конце MSPT — **0,589 ms**, snapshot mean/p95 — **0,213 / 0,343 ms**; SQL errors, dropped events, automation errors и game/event queues — 0. Это проверка протокольных клиентов без gameplay, движения и генерации нового terrain.

Показатели `actionMeanMs=0,990` и `actionMaxMs=25,751` являются накопленными за integration run. Они включают реальные операции Paper. Максимум отдельной операции больше 1 ms; из этих данных нельзя утверждать, что любая операция всегда укладывается в 1 ms или что дорогие внешние плагины не влияют на tick.

Окна 3 WebSocket и 5 idle clients относятся к JAR SHA-256 `8e4b80fef838fb929daeca38c067f070a91ff7b4d55148448d5a28e3224b4067`. Idle A/B выполнен на ранней сборке 0.1.0; её точный SHA-256 не сохранён. После измерений добавлен lifecycle guard для mute persistence после закрытия DB и исправлен HTTP transport cap для JAR upload с сохранением JSON limit 64 KiB. Snapshot, game operations, SQL queues, profiles и event algorithms этими изменениями не менялись. Полные performance окна на конечном JAR не повторялись.

Короткий normal-startup smoke после lifecycle guard (SHA `2ffa69ebc75d550372f264538b326b9a97dba0012f4a4699a7f8a91f3a021c6a`) дал snapshot mean/p95 0,263/0,316 ms, MSPT 0,163 ms, TPS около 20 и SQL errors 0. Это 20 секунд functional validation с возможными supplemental API actions, не строгий overhead benchmark. Конечный SHA и целевые lifecycle/HTTP проверки находятся в `verification/build.json`, `final-lifecycle.json`, `final-transport.json` и `supplemental.json`.

Исходные сводки: `verification/baseline-summary.json`, `mcc-idle-summary.json`, `three-dashboard-summary.json`, `five-client-summary.json`, `environment.json`. История интерфейса показывает CPU всей JVM; показатели snapshot/action — wall-clock runtime instrumentation, SQL latency — время выполнения queued SQL work. Профили и ограничения уменьшают частоту snapshots при низком TPS; измерение не заменяет длительный profiler/soak test на конкретном сервере.

## Ограничения памяти0.7

История раньше читала все полные JSON-снимки (до8640 за24часа) и только затем выбирала360точек. Теперь SQL выбирает последние реальные точки временных корзин, не более361, до JSON parsing. Новые записи сохраняют только числовые показатели, а старые выбираются ограниченно без изменения исходных данных. Это representative samples, не среднее и не обещание сохранить каждый кратковременный пик.

SQLite page cache2MiB, mmap отключён. Сжатые HTTP-ресурсы ограничены4MiB/64entries. HTTP queue64/4threads с idle timeout, Undertow2workers/1IO, pool limit32buffers×16KiB (512KiB pooled; активные/direct/thread-local allocations отдельно). Idle snapshots не чаще10s, ore immutable snapshot5s с bounded ledger/queue. Карта читает сохранённые region files в одном worker, не загружает chunk и не держит plugin tickets.

Heap limit, committed/used Java heap и WorkingSet всей JVM — разные показатели. Minecraft worlds, ядро, плагины, GC/native buffers совместно занимают процесс; они не доказывают расход именно MCC. До/после нельзя выдавать за точный A/B при разной загрузке игроков. Production heap limit не снижается без измерения запаса.
