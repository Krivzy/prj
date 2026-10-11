# MCC Core: локальная проверка hotpaths

Это отдельный standalone microbenchmark на Java 25, без запуска Minecraft сервера.
Он измеряет три изменённых участка движка; это не сравнение серверов с Purpur или
Pufferfish и не основание обещать выигрыш любого мира или конфигурации.

В каждом случае: 4 warmups на вариант, 8 парных раундов с чередованием AB/BA,
полная проверка одинаковых long-array/NBT bytes до измерения и одинаковые checksums
каждого batch. Время — `System.nanoTime`, allocations текущего потока —
`com.sun.management.ThreadMXBean`. Никаких assertions по порогу времени нет.
JVM: 128–384 MiB, G1. Все исходные samples и счётчики находятся в
[PERFORMANCE-SAMPLE.json](PERFORMANCE-SAMPLE.json).

| Случай | Original, ns/op | MCC, ns/op | Original → MCC, bytes/op |
|---|---:|---:|---:|
| Ore scratch 4096 bits | 122.38 | 127.31 | 528 → 0 |
| Ore scratch 16384 bits | 355.94 | 189.82 | 2064 → 0 |
| Long array 256, heap | 234.61 | 71.00 | 0 → 0 |
| Long array 2048, heap | 1825.95 | 650.76 | 0 → 0 |
| Long array 2048, direct | 3286.97 | 500.94 | 0 → 120 |
| NBT 32 int/string pairs | 5896.45 | 5752.52 | 40 → 0 |
| NBT 256 int/string pairs | 47693.40 | 46617.65 | 40 → 0 |

Числа — медианы одного JVM run. Pool убирает новые scratch allocations, но для
маленького буфера в этом sample время выше примерно на 4%. Большие long arrays
кодируются быстрее; direct вариант создаёт 120 байт временных NIO views на
операцию. Это реальный компромисс CPU/allocations, а не бесплатное улучшение.
NBT здесь даёт небольшой выигрыш времени и устраняет 40 байт iterator allocation.
Palette layout и chunk snapshot loops покрыты source/unit проверкой и этим
microbenchmark не измерялись.

Данные не включают server MSPT, GC паузы всей JVM, network pipeline, plugins,
генерацию мира или load с игроками. Для них нужен отдельный одинаковый fixture,
повторные запуски и behavioural guards. Parallel worldgen baseline Paper имеет
неповторяемый digest в текущем тестовом стенде; его failed raw report не должен
превращаться в положительное сравнение. Direct OreFeature oracle проверен отдельно:
45 комбинаций seed/vein/exposure, все writes и следующий RNG state совпали.

## Повторный запуск

После engine build из [README.md](README.md), из корня MCC:

```powershell
python core-engine/run-hotpaths.py --checkout core-engine/.work/paper --output core-engine/build/hotpaths-new.json
```

Нужен `JAVA_HOME` на JDK 25. Скрипт принимает только закреплённый upstream commit,
не перезаписывает результаты и не запускает сервер. Делайте measurements отдельно
от работающих тестов/сборок/нагруженных Minecraft JVM. Для серьёзного performance
вывода повторяйте JVM forks и используйте JMH/профилирование целевого workload.
