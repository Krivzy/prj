# MCC Core: стенд ограниченных нагрузок

Это отдельный Windows runner для Minecraft 26.3 / JDK 25. Пути к ядрам,
Java, frozen MCC JAR и кешам передаются явно. Он не импортирует launcher,
не читает production credentials и создаёт новые fixtures только в своей
исключённой из Git папке `.private/`. Linux execution не проверен.

Проверены Python guards и тот же Java workload в локальном диагностическом
стенде. Запуск этой адаптированной CLI на независимом компьютере пока не
проверен; это инструкция восстановления, а не обещание одинаковых результатов
на любом оборудовании. Состав и пределы измерений: [METHOD.md](METHOD.md).
Исходные неуспешные проверки сохранены в [DIAGNOSTICS.md](DIAGNOSTICS.md).
Финальный nonpilot протокол 9 runs / 60 s / 30 s и полная coordinate
диагностика описаны в [RUNBOOK.md](RUNBOOK.md).

## Входные файлы

Нужны Python 3.10+, Windows PowerShell с `Get-NetTCPConnection` / CIM и JDK 25.
Все три ядра запускаются одним Java executable и с одинаковыми G1 flags.
Фиксированные SHA указаны в [pins.json](pins.json); runner проверяет их сам.

1. Официальные Paper **26.3 build 169** и Purpur **26.3 build 2647**.
   Используйте официальный каталог [Paper](https://papermc.io/downloads/paper)
   и [Purpur](https://purpurmc.org/downloads). Файл другой версии/build не
   пройдёт pin, даже если имеет подходящее имя.
2. **Выделенные закрытые snapshots runtime caches** каждого ядра. В snapshot
   нужны `libraries/`, `versions/`, `cache/` из его подготовленной установки.
   Допускаются только обычные файлы/папки: junctions и symlinks отклоняются.
   Миры, `plugins/`, конфигурации и пользовательские базы исходной установки
   runner не копирует. Получите кеши в отдельной новой установке, штатно
   остановите её и отдельно скопируйте только эти три папки. Использовать
   live production folder в качестве snapshot запрещено.
3. `org.jetbrains:annotations:26.1.0`, SHA из pins. Paper cache должен также
   содержать закреплённые `paper-api`, `adventure-api`, `adventure-key`, `guava`
   по относительным путям из pins; annotations передаётся отдельным путём.
4. Один проверенный frozen **MCC 0.10** JAR и его SHA-256 для всех кандидатов.
   Runner не выбирает «последнюю» локальную сборку автоматически.
5. Опционально настоящая MCC Core **engine foundation 0.2.0** из
   [инструкции сборки](../../core-engine/README.md), SHA
   `86c96263ba9a6257cc8b03e8e8fd8f90ee03fedb396918ed1fa1f74ebe196223`.
   Это foundation с engine patches; lifecycle CoreBridge оболочки проверяется
   отдельно. Pufferfish 1.21.10 не участвует: версия Minecraft отличается.

`--offline-caches-confirmed` — явное подтверждение quiescent snapshots.
Собственный lock runner сериализует только его читателей, **не блокирует
внешний сервер**. Каждый скопированный файл проверяется SHA до/после чтения;
при изменении копирование прекращается. Автоматической подготовки исходных
серверов, принятия EULA за пользователя или остановки чужих процессов нет.

## Проверка и запуск

Из корня восстановленного MCC проекта в Windows PowerShell:

```powershell
python -X utf8 -m unittest discover -s tools/performance -p 'test_*.py' -v

$benchInputs = @(
  '--java-home', 'D:\MCC-Bench\jdk-25',
  '--paper-jar', 'D:\MCC-Bench\inputs\paper-26.3-169.jar',
  '--paper-cache', 'D:\MCC-Bench\inputs\paper-cache',
  '--annotations-jar', 'D:\MCC-Bench\inputs\annotations-26.1.0.jar',
  '--offline-caches-confirmed'
)
python -X utf8 tools/performance/benchmark.py --compile-only @benchInputs
```

Тесты не запускают JVM. `--compile-only` запускает только javac и создаёт
отдельный детерминированно упакованный benchmark plugin; сервер не запускается.
Пути в примере являются placeholders, их надо заменить своими. Не указывайте
production папки и не вставляйте пароли, ключи или токены в аргументы.

После отдельного подтверждения готовности компьютера к нагрузке:

```powershell
$benchExecution = @(
  '--purpur-jar', 'D:\MCC-Bench\inputs\purpur-26.3-2647.jar',
  '--purpur-cache', 'D:\MCC-Bench\inputs\purpur-cache',
  '--mcc-jar', 'D:\MCC-Bench\inputs\MinecraftControlCenter-0.10.0.jar',
  '--mcc-sha256', '<EXACT_REVIEWED_MCC_SHA256>',
  '--candidate-jar', 'D:\MCC-Bench\inputs\MCC-Core-engine-0.2.0-26.3.jar',
  '--candidate-sha256', '86c96263ba9a6257cc8b03e8e8fd8f90ee03fedb396918ed1fa1f74ebe196223',
  '--candidate-source-commit', '46d4834e8e2ec4af9dcfa028076fb16dfb8a3911',
  '--accept-minecraft-eula'
)
python -X utf8 tools/performance/benchmark.py --execute-disposable-benchmarks @benchInputs @benchExecution --controlled-generation --pilot --runs 3 --phase-seconds 30 --warmup-seconds 15
```

`--accept-minecraft-eula` явно принимает [Minecraft EULA](https://www.minecraft.net/eula)
для новых тестовых серверов. Без него execution отклоняется. Короткая команда
помечается `pilot-only`; интервалы по трём whole-server runs являются
exploratory. Полный default имеет 5 repeats / 120 s фаз / 60 s начальный
warmup и может занять более часа. Runner не объявляет общего победителя.

Порты по умолчанию: Paper `18105/25605`, Purpur `18106/25606`, Core
`18107/25607` (web/game). Их можно заменить отдельными непересекающимися
`--web-port-base` / `--game-port-base`. Серверы работают последовательными
запусками. Все слушатели обязаны принадлежать собственному JVM и localhost.

## Остановка, результаты, восстановление

Runner удерживает собственный дочерний process handle и проверяет executable,
parent PID, creation time и nonce до управления им. После измерений единственная
команда управления — `stop` через stdin этого JVM. Force kill, restart и hot
reload отсутствуют. До копирования собственного world snapshot обязательны
`exit 0`, нормальный stop, отсутствие прежнего process identity и закрытые порты.

В `.private/` сохраняются `result.json`, `ticks.csv`, `gc.log`, manifest
копирования, session/closure receipts. При отказе файлы остаются для разбора.
`closure-pending.json` требует ручной проверки: runner не убивает процесс и
не копирует открытый мир. Не удаляйте такую папку автоматически.

В Git не входят JAR, кеши, миры, логи и `.private/`. Опубликовать можно только
проверенные очищенные числовые результаты; raw session receipt содержит
абсолютные локальные пути. `export_results.py` использует явный allowlist:
проверяет оригинальные SHA / closure / unmodified terrain digest, переписывает
только числовой CSV и пересчитывает summaries/paired CI. Logs/worlds/process
command lines и абсолютные source paths в export не попадают. Пример:

```powershell
python -X utf8 tools/performance/export_results.py --session 'D:\MCC\tools\performance\.private\session-<UUID>\result.json' --source-private-root 'D:\MCC\tools\performance\.private' --name controlled-26_3
```

Export создаётся только в новой `results/<name>/`; существующие отчёты не
перезаписываются. Nonpilot export требует минимум три полные paired repeats,
plan ≥60 s / initial idle warmup ≥30 s и минимум 1200 ticks в четырёх
time-based phases. Короткие chunk phases остаются 128 admissions. Смешанные
MCC/benchmark/JVM/generation/template/workload inputs отклоняются. Публикуются
оба прежних comparisons с Paper и прямой `MCC-Core-minus-Purpur`, вычисленный
из разностей **тех же paired runs**, со своим whole-run bootstrap CI.
Даже экспортированный состав надо просмотреть перед commit.
Production откат не нужен, поскольку он не меняется.
Для отката harness верните предыдущие versioned Python/Java файлы и создайте
новые fixtures. Исходные snapshots сохраняются нетронутыми.
