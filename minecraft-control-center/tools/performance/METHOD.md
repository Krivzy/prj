# Методика и пределы сравнения

## Одинаковые inputs

Minecraft 26.3, Paper upstream commit
`46d4834e8e2ec4af9dcfa028076fb16dfb8a3911`, JDK 25, heap 1024 MiB,
G1 flags из runner, один frozen MCC и один benchmark plugin. Для каждого
запуска создаётся новая копия **одного** штатно закрытого нового world snapshot
с manifest SHA. Seed `6381247891023`, view/simulation `8/8`, flat mechanics
world и отдельный normal terrain world. Paper 26.3 хранит dimensions внутри
главного world folder, поэтому копируется весь собственный `bench_mechanics`,
исключая player data и session.lock. Чужие миры не копируются.

Whitelisted localhost fixtures не допускают реальных игроков. MCC работает
в одинаковом eco/trial режиме: internal metrics включены, остальные optional
modules выключены. Natural spawn, random ticks, daylight/weather cycle и
autosave выключены одинаково. AI activation ranges `0` нужны только чтобы
no-player workload действительно обрабатывал AI; это не production preset.

## Фазы

| Фаза | Нагрузка и проверка |
| --- | --- |
| idle-control | Контроль с панелью и подготовленными пустыми механизмами |
| hopper-redstone | 64 banks; inventory conservation; минимальная cadence 8 ticks; настоящие lever/wire/lamp transitions |
| mob-ai | 128 villager + 128 zombie в отдельных клетках; population/validity; затем удаляются |
| saved-chunk-read | 128 подготовленных сохранённых чанков, максимум одно admission за tick |
| fresh-chunk-generation | 128 отсутствовавших normal чанков в фиксированном порядке |
| world-save | 64 dirty blocks, оба мира явно сохраняются раз в 100 ticks |

`--phase-seconds` задаёт длительность idle/mechanics/mobs/save. Chunk phases
завершаются после 128 чанков, **не** поддерживают нагрузку до заданной
длительности. `--warmup-seconds` — начальный idle warmup; между следующими
фазами выделено 100 ticks settle. Это короткий bounded profile, не устойчивый
часовой тест насыщения CPU/IO или рекорд chunks/s.

Обычный parallel режим: не более 4 pending futures, admission раз в tick.
Controlled режим: `chunk-system.worker-threads=1`, `io-threads=1` и
синхронный `World.loadChunk`, тот же порядок. Контролируемый профиль нужен
для повторяемости и не характеризует production parallel generation/chunk
sending. Удалённый из Paper 26.3 `gen-parallelism` не используется.

## Измерения

Каждый tick сохраняет `ServerTickEndEvent.getTickDuration()` (реальные MSPT,
без ожидания следующего tick), main-thread JVM allocated-byte counter delta,
heap sample, operation call time, completed/pending chunks. Heap обновляется
раз в 20 ticks; промежуточные строки повторяют последнее значение. Allocation
worker threads не измеряется. Heap — **весь JVM**, не память одного плагина
и не RSS. GC collection count/time снимаются на границах фаз; gc.log хранится
приватно. Call time не является latency async completion или physical flush.

Сводка: p50/p95/p99/mean/max MSPT, allocation total/per tick, heap median/max,
wall time, GC count/time, completed-chunk rate. Chunk rate ограничен admission
policy и условным 20 TPS. Скопированные cache/world файлы могут быть в OS
cache; холодный disk benchmark не заявляется.

Каждый статистический repeat — новая JVM и новая копия snapshot. Порядок
кандидатов перемешивается seeded RNG `20261011`. Tick rows автокоррелируют,
поэтому CI вычисляется из whole-run paired differences, не из тысяч ticks.
Для >=3 repeats используется exploratory percentile bootstrap (10 000
resamples, seed фиксирован), 95% interval без поправки на множество метрик.
Три repeats и короткий warmup не гарантируют narrow CI или независимость от
thermal/cache/background нагрузки. Значение ниже Paper в одной фазе не
является общим рейтингом ядер.

Public export пересчитывает `MCC-Core-minus-Paper`, `Purpur-minus-Paper` и
прямой `MCC-Core-minus-Purpur` из одинаковых paired whole-run summaries.
CI последнего строится из трёх непосредственных Core−Purpur разностей;
границы двух intervals с Paper не вычитаются. Недоступная allocation метрика
не превращается в ноль и для такого contrast CI не публикуется.

Nonpilot export требует >=3 полных независимых paired boots, planned time
phase >=1200 ticks / initial idle warmup >=600 ticks и фактически записанных
>=1200 ticks в каждой из четырёх time-based phases. Это nominal 60 s / 30 s
protocol, а не 30 s warmup каждой отдельной нагрузки. Проверяется совпадение
MCC/benchmark pins, числовой JDK25 version, generation mode, общего template
manifest и workload plan между всеми runs. Marker/closure/session provenance
должны совпадать. Перед анализом проверяются SHA исходных JSON/CSV; strings
server/JVM version допускают только grammar закреплённых builds и numeric JDK.
Каждый repeat требует отдельный run UUID и отдельный resolved fixture root:
один закрытый JVM нельзя несколько раз посчитать независимым запуском,
переименовав repetition. Абсолютные fixture paths используются только в
private guards и не попадают в public summary.

## Equality guard и диагностика

После всех измеряемых фаз SHA-256 строится из исходных
`BlockData.getAsString()` по каждым четвёртым X/Z и всем высотам всех 128
generated chunks в стабильном порядке координат. Ни fluids, ни leaves, ни
properties не исключаются и не нормализуются. Разные SHA блокируют
comparative verdict. Совпадение этой выборки не доказывает равенство всех
блоков, всех vanilla механик или compatibility каждого плагина.
В nonpilot export исходная sample SHA должна также совпадать между всеми
независимыми paired repeats. Исторические session files не меняются.

`terrain_diff.py` после нормальной остановки читает все сохранённые blockstates
128×16×16×384, показывает координаты/categories/biome NBT. Это отдельная
диагностика состояния **после stop**, а live digest снят раньше. Canonical
properties в выводе нужны для чтения NBT, исходный runtime hash не меняется.
Даже controlled Paper→Paper имел 6 несэмплированных fluid differences после
stop при одинаковых live sample SHA; этот факт сохранён в DIAGNOSTICS.md.

Часовые фермы, crops/random ticks, network player chunk sending, высокая
онлайн-нагрузка, полная vanilla equivalence и превосходство над Pufferfish
не проверяются этим стендом. Main, сторонние jobs, температура, ОС и кеш
нужно учитывать в каждом отчёте. Runner не выключает фоновые процессы.
