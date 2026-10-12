# Встроенный launcher: один MCC JAR

Начиная с 0.11 `MinecraftControlCenter.jar` содержит панель, Bukkit-плагин и Java launcher. Python и отдельный launcher JAR для новой установки не нужны. Положите файл в `plugins/MinecraftControlCenter.jar`, оставив серверное ядро рядом с `eula.txt` в корне сервера. Launcher не принимает EULA за владельца.

Из каталога сервера, с Java 25:

```text
java -Xms32M -Xmx96M -jar plugins/MinecraftControlCenter.jar --server-root . --server-jar paper.jar --memory-mib 1536 --kernel-name Paper --minecraft-version 26.3
```

Для Purpur укажите его JAR и `--kernel-name Purpur`. Максимальная память Minecraft задаётся `--memory-mib`; минимальная — `--min-memory-mib` (512 по умолчанию). Heap launcher задаётся до `-jar` и не заменяет память Minecraft. `scripts/Start-Server.ps1` выполняет этот запуск; `-Direct` оставляет запуск под управлением хостинга.

Тот же файл предоставляет команды управления:

```text
java -jar plugins/MinecraftControlCenter.jar --status --server-root .
java -jar plugins/MinecraftControlCenter.jar --restart --server-root .
java -jar plugins/MinecraftControlCenter.jar --stop --server-root .
java -jar plugins/MinecraftControlCenter.jar --help
```

`--stop` завершает сервер с сохранением миров и затем launcher. `--restart` запрашивает штатную остановку и новый запуск. Принятие запроса ещё не означает завершение: состояние отражается в status и панели. Не заменяйте MCC JAR, пока оба процесса не завершились: Windows может удерживать его открытым.

Один распространяемый JAR запускает отдельный небольшой родительский Java-процесс и дочерний процесс Minecraft. Родитель нужен для запуска после завершения JVM сервера. Если хостинг запускает непосредственно `paper.jar`, панель работает, но её кнопка управляемого перезапуска доступна только с подключённым launcher. Если хостинг запрещает свою startup-команду или уничтожает всё дерево процессов, используйте его управление перезапуском. Автоматического подключения к чужому уже запущенному процессу нет.

## Остановка, восстановление и настройки

Launcher владеет stdin дочернего процесса, отправляет `stop` и ждёт его выхода. Запрошенный restart допускается только после exit 0. Запрошенная остановка терминальна, в том числе во время восстановления. После неожиданного ненулевого выхода предусмотрено до трёх попыток за десять минут с задержками 2/10/30 секунд. `--max-crash-restarts 0` отключает их. Принудительное завершение процесса и таймаут записи конфигурации не используются.

Перед каждым запуском отдельная короткая JVM из этого же JAR выполняет `ProfileBootstrap`: применение выбранного пресета, отложенное обслуживание плагинов и подготовленный переход на MCC Core. Ошибка hook блокирует запуск, сохраняя возможность диагностики. Миры не заменяются этим hook. Кнопка перезапуска требует готовности ядра и MCC API и проверяет настоящий parent PID, PID сервера и UUID текущего запуска.

## Протокол и миграция

Protocol 3 имеет `launcherKind:embedded-java`, `leaseKind:java-file-lock-byte-0`, совпадающие `runId/currentRunId` и время запуска процессов. Java FileLock байта 0 в `.mcc-launcher/daemon.lock` удерживается на всём протяжении работы, включая hook и промежуток между запусками. Очередь принимает только ограниченные записи `stop/restart` с UUID. Старые записи при новом старте пропускаются; повторные и чужие запросы отклоняются.

Reader удерживает shared FileLock; CLI/API writer получает эксклюзивную блокировку с ожиданием до 2 секунд и шагом до 10 ms только при занятой lease (`null` или `OverlappingFileLockException`). Interruption прекращает ожидание с сохранением флага, настоящие I/O errors не подавляются. После ожидания CLI заново проверяет actual daemon/child/startInstant/daemon lease/run, API — прежние immutable PID/run/running/ready/parent-alive и authority. Обе стороны повторно проверяют identity/budget очереди под блокировкой перед durable append. API не добавляет OS lease/startInstant probe и ждёт на HTTP worker, не Minecraft main thread. Принятый запрос не нужно автоматически отправлять повторно; завершение проверяют по status.

Legacy Python v2 остаётся в исходниках для совместимости и старых тестов; [его документация](SERVER-SUPERVISOR.md) описывает protocol 2. Для миграции полностью остановите v2 и Minecraft, проверьте отсутствие процессов, сохраните резервную копию, затем используйте запуск выше. На Unix Python `flock` и Java FileLock используют разные механизмы: одновременный запуск v2/v3 запрещён. Новый launcher дополнительно отказывает при живом или недоступном предыдущем daemon PID или PID сервера. Блокировка не заменяет диагностику после принудительного внешнего завершения между созданием child и публикацией PID.

Контрольные файлы и ограниченные журналы приватные. Не публикуйте их вместе с исходниками. Свежие Windows native-проверки на отдельных серверах прошли: Paper 14 checks с обычным restart, Paper→Core и обновлением прежнего installed binding; Purpur семь ordinary lifecycle checks без Core migration. Семь children/три daemon закрыты, четыре web restart и три terminal CLI stop прошли без force kill, с девятью world-save summaries. Offline update Main прошёл после проверенной закрытой копии: заменены только MCC JAR и два локальных Start/Stop scripts, остальные 11 222 файла сохранены побайтно. Main не запускался и остаётся OFF; installed Core/selection сохраняют прежний binding до следующего разрешённого пользователем boot. [Точные proofs и границы 0.11](TESTS-0.11.md).

На файловой системе без native `fileKey` очередь сверяет canonical path и время создания файла, удерживая открытый FileChannel и проверяя размер. Это выявляет обычную замену файла; не является доказательством inode против владельца ОС, который сам подменил файл с сохранением timestamps. Контрольный каталог доступен только владельцу и SYSTEM. Журнал ограничен двумя файлами по 8 MiB; oversized legacy-журнал нужно сохранить и архивировать при выключенном сервере, launcher сам его не обрезает.

На Windows retained queue/daemon handles дополнительно запрещают delete/rename с помощью `NOSHARE_DELETE`, сохраняя чтение и запись очереди; неподдержанный provider отклоняется. [Контракт опции в исходниках OpenJDK 25](https://github.com/openjdk/jdk/blob/jdk-25%2B36/src/jdk.unsupported/share/classes/com/sun/nio/file/ExtendedOpenOption.java). Unix native-проверки этой версии отдельно не выполнялись: Windows-тесты не доказывают поведение другого provider.

При обновлении MCC на уже установленном MCC Core hook обновляет проверенную привязку к новому плагину до старта ядра, с резервной копией пары и откатом. [Контракт обновления Core](MCC-CORE.md).
