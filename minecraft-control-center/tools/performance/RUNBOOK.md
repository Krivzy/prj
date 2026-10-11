# Порядок финального controlled сравнения

Статус: подготовлено; этот файл **не содержит результата нового измерения**.
Новый запуск разрешён только после двух нормальных native closures и завершения
release deploy. Не запускать одновременно Gradle, другую JVM/native проверку
или copy/backup heavy job. Main остаётся включённым фоном; его активность
и ограничения CPU/IO/temperature/cache записываются в финальный отчёт.

1. Проверить final frozen MCC SHA и неизменность build inputs. Тот же JAR
   используется всеми kernels. Проверить pins Paper/Purpur/Foundation/JDK,
   закрытые runtime cache snapshots и свободные собственные ports.
2. Запустить один fresh template preparation JVM. Штатно остановить, проверить
   `exit 0` / process identity / ports и сохранить world manifest.
3. Выполнить **3 paired repeats × 3 kernels = 9 measured JVM**, последовательно,
   с seeded order, новыми копиями общего snapshot и одинаковой конфигурацией.
   Флаги measurement: `--controlled-generation --runs 3 --phase-seconds 60
   --warmup-seconds 30`, **без `--pilot`**. Сохраняется существующий duration
   guard. 60 s относится к четырём time-based phases; chunk phases содержат
   128 admissions; settle между фазами 100 ticks.
4. После каждого JVM требуется normal closure. Workload/closure/исходный
   generated sample hash guard обязаны пройти. При отказе сессия остаётся
   failed, результат не объявляется comparative success, реальные raw files
   и отличия сохраняются. Guard не ослаблять ради желаемого результата.
5. После закрытия прочитать все 12 582 912 сохранённых blockstates каждой
   пары Paper↔Core, Paper↔Purpur, Core↔Purpur для каждого repeat: всего 9
   coordinate comparisons. Сохранить counts, coordinates, ore/stone/fluid/
   leaf categories, biome NBT и SHA отчётов. Fluids не удалять из диагностики.
6. Экспортировать whitelisted numeric CSV + recomputed summaries/paired CI,
   затем просмотреть состав и составить `docs/PERFORMANCE-COMPARISON.md`:
   artifacts, version/flags/world setup, p50/p95/p99, main-thread allocation,
   heap/GC, bounded chunk rate, equality diagnostics и ограничения. Оба
   comparisons с Paper сохраняются; дополнительно нужен **прямой paired
   MCC-Core-minus-Purpur** и его собственный whole-run CI. Пересекающиеся
   либо вычтенные Paper CI не заменяют прямой contrast.

Восстановленный public runner использует explicit inputs из README. После
его закрытого session result запуск только read-only диагностики/экспорта:

```powershell
python -X utf8 tools/performance/diagnose_session.py --session '<ABS_PRIVATE_SESSION_RESULT_JSON>' --source-private-root '<ABS_HARNESS_PRIVATE_DIRECTORY>'
python -X utf8 tools/performance/export_results.py --session '<ABS_PRIVATE_SESSION_RESULT_JSON>' --source-private-root '<ABS_HARNESS_PRIVATE_DIRECTORY>' --name controlled-final-26_3
```

Diagnostics пишет новые полные JSON только внутри private session. Export
пишет очищенные numeric files только в новой `results/<name>/`. Оригинальные
worlds/logs/receipts не перезаписываются и не публикуются.

Локальный исторический controlled Paper run с 10 s phases / 5 s initial
warmup занимал около 99.5 s до result. Увеличение четырёх time-based phases
до 60 s и initial warmup до 30 s добавляет ~225 s/run, то есть около 49 min
для 9 measurements. С template/copy/normal stop/full diagnostics/report
ориентир **55–65 min**, без гарантии: тяжёлые chunk/GC или IO delays могут
увеличить время. Force kill по дедлайну отсутствует.

Nonpilot означает этот matched measurement protocol, а не полную проверку
production. Bootstrap по трём whole-run paired repeats остаётся exploratory,
поправки на множество метрик нет. Общий fastest/vanilla-equivalent вывод
не делается. Pufferfish 1.21.10 исключён из same-version сравнения.
