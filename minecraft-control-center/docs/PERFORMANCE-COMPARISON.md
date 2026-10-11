# Paper / Purpur / MCC Core 0.2: неполное сравнение

Статус — **INCOMPLETE**. Non-pilot сессия MCC 0.10 завершилась с `passed:false` и `comparativeVerdict:not-established`: из девяти запланированных измеренных запусков завершены три, по одному Paper/Purpur/MCC Core в первой парной серии. Каждый отдельный workload прошёл свои assertions и штатный stop/exit 0. Принятый минимум — три независимые парные серии, то есть девять различных boots — не выполнен. Доверительные интервалы не рассчитаны; превосходство Core не установлено.

Ниже опубликованы фактические числовые наблюдения первой серии. Их нельзя считать девятью runs, тремя независимыми повторами или завершённым сравнительным экспериментом. Близкие значения, отрицательная разность либо один большой выброс не доказывают устойчивый выигрыш или ухудшение.

## Проверенные исходные данные

[Numeric observations](../tools/performance/results/resource-halt-26_3/observations.json), [manifest](../tools/performance/results/resource-halt-26_3/manifest.json) и три allowlisted CSV сохранены с проверкой оригинальных byte hashes. CSV не фильтровались и не заменялись синтетическими повторениями:

- [Paper rep0](../tools/performance/results/resource-halt-26_3/Paper-rep0-ticks.csv).
- [Purpur rep0](../tools/performance/results/resource-halt-26_3/Purpur-rep0-ticks.csv).
- [MCC Core rep0](../tools/performance/results/resource-halt-26_3/MCC-Core-rep0-ticks.csv).

| Доказательство | SHA-256 |
| --- | --- |
| Numeric observations | `3c988b767cd4275b097f4a58c9e71b0cca7ecdc3754384a8171b5288da21a6fc` |
| Numeric manifest | `504f3d3aeb2fccc4d1709ba8e36b06e799295aa8677e88c07889e15b91077897` |
| Original failed session, закрытые данные | `41faf6a06701bafc358a3939bf4feeb1a774f4dd4440f180678037684b14e0f1` |
| MCC 0.10 JAR | `8ff5fa8374ffa85d2ad9cbab658a055587c90f5b1e4e08549681a5d47b5a6425` |
| MCC Core 0.2 foundation | `86c96263ba9a6257cc8b03e8e8fd8f90ee03fedb396918ed1fa1f74ebe196223` |
| MCCBenchmark workload JAR | `1a7d6c2615da58c19a12343ba0f6ac76eaa71871c922d03179faf072f8e6749d` |

Original session, process/GC logs, caches, worlds и конфигурации остаются закрытыми. Публичный экспорт содержит числовые CSV, закреплённые версии/хеши и ограниченный terrain summary, без credentials и абсолютных локальных путей.

## Одинаковые условия и область нагрузки

| Ядро | Фактический serverVersion | JDK |
| --- | --- | --- |
| Paper | `26.3-169-46d4834 (MC: 26.3)` | 25.0.1 |
| Purpur | `26.3-2647-4cd2cf6 (MC: 26.3)` | 25.0.1 |
| MCC-Core | `26.3-DEV-46d4834 (MC: 26.3)` | 25.0.1 |

Все ядра использовали один закрытый template snapshot, одинаковые MCC/workload JAR и G1 JVM flags (`-Xms1024M -Xmx1024M`; полный набор в [runner](../tools/performance/benchmark.py)). Четыре time-based phases имеют 1200 measured ticks, приблизительно 60 s при 20 TPS; initial idle warmup — 600 ticks, номинально 30 s. Chunk phases ограничены 128 admissions и занимают 129 measured ticks. Числа в таблицах округлены; JSON/CSV сохраняют исходную точность.

Generation mode — `controlled-worker1-io1-synchronous-row-order`: фиксированные worker/IO limits и последовательный порядок admission. Этот эксперимент не измеряет default parallel saturation. Benchmark запускает patched engine foundation; production CoreBridge pairing/отключение панели проверены отдельными [native-проверками](MCC-0.10-RELEASE.md).

Нагрузка искусственная, без игроков; одинаковый `entity-activation-range=0` выбран для AI-профиля и не предлагается как production preset. Во время измерений Main оставался включённым фоновым workload на том же компьютере. Внешние CPU/IO, давление на RAM, pagefile, GC и cache state могут влиять на результаты. Pufferfish 1.21.10 исключён: сравнение с Minecraft 26.3 было бы сравнением разных версий. Методика и guards: [METHOD.md](../tools/performance/METHOD.md), [RUNBOOK.md](../tools/performance/RUNBOOK.md).

## Время игрового тика: наблюдения одной серии

Все значения — ms; p50/p95 вычислены по measured tick rows этой фазы. Tick rows зависимы во времени и не заменяют независимые boots.

| Фаза | Paper p50 | Paper p95 | Purpur p50 | Purpur p95 | Core p50 | Core p95 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Простой | 0.189 | 0.346 | 0.260 | 0.463 | 0.221 | 0.483 |
| Hopper / redstone | 0.162 | 0.605 | 0.218 | 0.743 | 0.194 | 0.927 |
| AI мобов | 8.574 | 18.113 | 7.916 | 11.102 | 9.811 | 22.131 |
| Чтение сохранённых чанков | 2.019 | 28.326 | 2.058 | 32.894 | 1.868 | 16.020 |
| Генерация новых чанков | 5.081 | 107.645 | 1.752 | 110.129 | 2.493 | 106.932 |
| Сохранение мира | 0.199 | 0.657 | 0.293 | 0.772 | 0.198 | 0.645 |

## Аллокации и sampled heap

В каждой ячейке: **KiB аллокаций main thread на measured tick / MiB median sampled heap**. Аллокации не включают все async workers; heap sampled каждые 20 ticks относится к всей JVM. Это не RSS, не Working Set и не расход одного MCC. Память между JVM зависит от предыдущих фаз и GC; один median не доказывает уменьшение памяти production сервера.

| Фаза | Paper KiB/tick / heap MiB | Purpur KiB/tick / heap MiB | Core KiB/tick / heap MiB |
| --- | ---: | ---: | ---: |
| Простой | 11.05 / 507.50 | 11.37 / 804.00 | 11.07 / 537.00 |
| Hopper / redstone | 272.36 / 681.00 | 272.59 / 455.50 | 272.47 / 674.00 |
| AI мобов | 1939.21 / 596.60 | 1915.57 / 603.23 | 1920.82 / 590.96 |
| Чтение сохранённых чанков | 481.05 / 553.20 | 479.78 / 574.48 | 480.47 / 571.42 |
| Генерация новых чанков | 332.67 / 687.53 | 329.35 / 731.91 | 329.10 / 705.25 |
| Сохранение мира | 83.44 / 859.00 | 82.82 / 862.50 | 81.93 / 731.00 |

## Ограниченная chunk throughput

Значения — 128 completed chunks / фактическое wall-clock время фазы, chunks/s. Нагрузка содержит фиксированный admission limit, а не настоящий поток игроков или насыщение всего сервера.

| Фаза | Paper | Purpur | Core |
| --- | ---: | ---: | ---: |
| Чтение сохранённых чанков | 19.843 | 19.693 | 19.848 |
| Генерация новых чанков | 14.885 | 15.239 | 15.420 |

## Terrain после штатной остановки

В каждой из трёх попарных проверок первой серии сравнивались полные сохранённые block states всех 128 generated chunks: 12 582 912 блоковых позиций на пару. Вода, lava и block properties не исключались; исходный live digest не переписывался.

| Пара в rep0 | Сравнено позиций | Различных block states | Различных исходных sample positions | Чанков с отличием biome NBT |
| --- | ---: | ---: | ---: | ---: |
| Paper ↔ Core | 12 582 912 | 0 | 0 | 0 |
| Paper ↔ Purpur | 12 582 912 | 0 | 0 | 0 |
| Core ↔ Purpur | 12 582 912 | 0 | 0 | 0 |

Все три original live sample SHA совпали: `8cd08f979f7a529a878cd3810ad1485abc8348dcab055f8035434692a095cb23`. [Очищенный terrain summary](../tools/performance/results/resource-halt-26_3/terrain-summary.json) имеет SHA-256 `51816925bc88efd4fbe107a27f65e2a8b2f73c6ac82fa43114c9c577a6063c31`; он ссылается по хешам на все три закрытых full reports. Full poststop diagnostic index SHA-256 `273350863e37a72075a511d10bbcc8772f7eccb74a00d06679a63d90fa25a064`. Это ограниченная проверка одного seed/template/набора чанков. Три пары внутри rep0 не являются тремя независимыми repeats. Совпадение сохранённых block states и biome NBT не доказывает идентичность всего мира, всех игровых механик, block entities, native 26.3 client rendering или долгого tick simulation. Прежний недетерминированный Paper pilot остаётся отдельным отрицательным результатом, а не причиной менять старый digest.

## Почему сессия не завершена

Root разрешил остановить новые fixtures после неожиданного внешнего расхода свободного диска и давления на память стенда. Halt receipt фиксирует 2 667 548 672 свободных байта диска в 2026-10-11 22:13:04 UTC. Причина внешнего расхода диска не доказана. Текущий Purpur завершил workload и normal stop; удержание существующих nonblocking input-cache leases отклонило следующую fixture до копирования и запуска JVM (`PermissionError`). Активные чужие процессы не останавливались, source/assertions/live sample guard не изменялись.

После завершения все начатые benchmark JVM, включая template boot, штатно закрыты с exit 0, dedicated ports закрыты, оба leases освобождены и проверены повторным acquisition/release. Post-halt verification SHA-256 `c08548b8a37b81f9df0b910c94e79a2136daf629c149e920e528cdc9ea3931b8`; остановка `c7db1e452aa65b0b6ee591920bed0654ff8c57979a9dbfaa26c81bc60b4d037b`, lease release `b92170c35257fb4840896b0d1860f375ece3202fa2c3e7d58b8b8f0efee3344c`. Это ресурсная остановка, а не наблюдавшаяся авария MCC Core.

Неизменённый strict comparative exporter отказал с `Failed/unfinished sessions cannot become comparative reports`; receipt SHA-256 `cf5e0ac48d1134b9dde08b4eb8b238966f8f97c919fa5507f0d67345cdbafff5`. Отдельный numeric INCOMPLETE export не производит CI и не меняет `sessionPassed:false`. Strict final persistence validator также не получает comparative PASS.

## Повторная проверка и восстановление

Для завершённого сравнения нужен новый session на подготовленном стенде с достаточным запасом диска/RAM и контролем фоновой нагрузки: минимум три полные независимые paired repeats/девять distinct measured boots с теми же 60 s фазами/30 s initial warmup, exact artifact/config/template pins и исходными sample/assertion guards. Старые результаты не переписываются и не дополняются синтетическими runs. Автоматический повтор после halt не выполняется.

Восстановление исходников и foundation описано в [README](../README.md), [core-engine/README.md](../core-engine/README.md), восстановление harness и его входов — в [tools/performance/README.md](../tools/performance/README.md). [MCC 0.10 release](MCC-0.10-RELEASE.md) отдельно подтверждает сборку, native lifecycle, browser и Main deployment; эти PASS не завершают workload сравнение.
