# MCC 0.11: встроенный Java launcher и исправления панели

Один `MinecraftControlCenter.jar` теперь содержит Bukkit-плагин, web-панель и исполняемый Java launcher. Этот же файл устанавливается в `plugins/` и служит entrypoint родительского процесса, который сохраняется при штатном restart Minecraft. Для нового управляемого запуска Python или отдельный launcher JAR не нужны. Reader и writer согласовывают блокировки очереди; оба дефекта воспроизведены и исправлены в source/regression проверках. Новая полная сборка после writer fix и свежие 13 process-level scenarios прошли. Свежая пара Paper/Purpur с Paper Core migration/refresh прошла полностью. Offline deployment Main прошёл после проверенной закрытой копии; заменены ровно три файла. Main остаётся выключенным и не запускался.

## Точная текущая сборка после reader и writer fixes

| Проверка | Фактический результат |
| --- | --- |
| MCC version | `0.11.0` |
| JAR | 91 454 706 байт, SHA-256 `66d4d8e27be1e514eac9ffbf66bc8edbe0facfa555d1541be792abff75094e2a` |
| Build receipt | SHA-256 `1afe599c94e68bb2d00b57c3bfd87ea9d52432ed5e6c71db94b62462863d8a2e`, 2026-10-12 01:09:16 UTC |
| Java | 413 tests, failures/errors/skips 0 |
| Node / Python | 135 / 32 tests |
| TypeScript / Vite / Gradle | PASS |
| Compiler inputs | 4536, before/after неизменны; manifest SHA-256 `21e7f14a4d816b87b6e3f8051a4f29c05cc72fd8cc8d8ce160483756802a2b4b` |
| Embedded frontend | 4228 файлов совпали с dist побайтно |
| Manifest | `Main-Class: dev.mcc.launcher.LauncherMain` |
| Core foundation | Прежняя 0.2.0, SHA-256 `86c96263ba9a6257cc8b03e8e8fd8f90ee03fedb396918ed1fa1f74ebe196223` |

Это source/build proof нового `66d4…`; результаты прежних `006689…` и `24d504…` ниже сохранены как история, а не заменяют свежие 13 cases или Minecraft native verification.

## История: сборка reader fix до writer fix

| Проверка | Фактический результат |
| --- | --- |
| MCC version | `0.11.0` |
| JAR | 91 452 786 байт, SHA-256 `0066898cdbd9415ff6364d3b8d85bd3609fb17796385c7be58f5aeba7fa38b41` |
| Build receipt | SHA-256 `43b13652c4aea51119f750c79c423f75f2c5eafa3dfba10949b53b425aed856a`, 2026-10-12 00:21:42 UTC |
| Java | 404 tests, failures/errors/skips 0 |
| Node / Python | 135 / 32 tests |
| TypeScript / Vite / Gradle | PASS |
| Compiler inputs | 4533, before/after неизменны; manifest SHA-256 `bf10d7976aa8b62463bb70a610863503f66ee3d85edd78cf2630e5a078961cac` |
| Embedded frontend | 4228 файлов совпали с dist побайтно |
| Manifest | `Main-Class: dev.mcc.launcher.LauncherMain` |
| Core foundation | Прежняя 0.2.0, SHA-256 `86c96263ba9a6257cc8b03e8e8fd8f90ee03fedb396918ed1fa1f74ebe196223` |

Этот build proof относится только к `006689…`, до исправления writer. Expanded process receipt SHA-256 `967da4f04a872eee0f6f8d2af8c94227febd354d2dd699b7dc167917ab414ba8` имеет `passed:false`, 12/13 cases PASS. В case `writer-lock-restart` сам restart под реальной внешней блокировкой достиг второго boot; затем terminal CLI stop завершился exit 1 с generic IOException и его UUID отсутствует в durable queue. Все 13 cases CLOSED без force kill; Main остался OFF. Отдельная closure-проверка SHA-256 `39646af8c7d448626dc35e1500a8cce8d82756988263ab414d4b9eb903af9ea1` сохраняет этот отрицательный результат. Точная исходная exception trace недоступна. Это другой результат, чем первый native attempt, где принятый stop был в очереди. Кандидат `006689…` не является финальным JAR, а девять прежних process-level cases `24d504…` не заменяют повторную проверку.

## Предыдущая сборка: исторический build proof

| Проверка | Фактический результат |
| --- | --- |
| MCC version | `0.11.0` |
| JAR | 91 452 521 байт, SHA-256 `24d5044a195b4fea52beccd48aac28d950bd0050e202c88ed1f3623594c8256f` |
| Build receipt | SHA-256 `d2caaf6d0af60d6f9bfa6de3933ebc57ae08292c888d52dfaaa995bc739cef3f`, 2026-10-12 00:00:13 UTC |
| Java | 401 tests, failures/errors/skips 0 |
| Node | 135 tests |
| Python | 32 tests |
| TypeScript / Vite / Gradle | PASS |
| Compiler inputs | 4532, before/after неизменны; manifest SHA-256 `18a30f42fd0326c2723167d66a3b008035d89b4b77a018b0e34b950b638ad1f0` |
| Embedded frontend | 4228 файлов совпали с dist побайтно |
| Manifest | `Main-Class: dev.mcc.launcher.LauncherMain`; Bukkit entrypoint остаётся `dev.mcc.MccPlugin` |
| Core foundation | Прежняя 0.2.0, SHA-256 `86c96263ba9a6257cc8b03e8e8fd8f90ee03fedb396918ed1fa1f74ebe196223` |

Этот executable JAR содержит launcher class и точную прежнюю foundation. Таблица фиксирует предыдущий артефакт `24d504…`, не новую сборку исправления. Source/build proof не заменяет работу настоящего сервера. [Восстановление и сборка](../README.md), [source engine, pin и лицензии](../core-engine/README.md).

## Один JAR: что запускается

Из каталога сервера, где находятся kernel JAR, принятый владельцем `eula.txt` и `plugins/MinecraftControlCenter.jar`, с Java 25:

```text
java -Xms32M -Xmx96M -jar plugins/MinecraftControlCenter.jar --server-root . --server-jar paper.jar --memory-mib 1536 --kernel-name Paper --minecraft-version 26.3
```

Для Purpur или установленного Core укажите настоящий server JAR/имя ядра. Владелец выбирает память Minecraft отдельно: `--memory-mib` задаёт максимальный heap, `--min-memory-mib` — начальный (512 MiB по умолчанию). `-Xms32M -Xmx96M` относится только к сохраняющемуся parent JVM, не к Minecraft и не к полной RSS parent. Перед каждым boot временная hook JVM использует `-Xms32M -Xmx192M`; общий расход процессов больше одной цифры heap. Эти пределы не являются измерением снижения RAM всего сервера.

`--status`, `--restart` и `--stop` предоставляет тот же JAR. Parent владеет stdin своего child, проверяет executable/PID/start time/run UUID, удерживает daemon lease и ждёт штатного exit. Restart после запроса разрешён только после exit 0; принятый запрос не равен завершённому restart. Обычный незапрошенный exit 0 и запрошенный stop терминальны. Неожиданный ненулевой exit допускает до трёх recovery попыток за десять минут с задержками 2/10/30 s; `--max-crash-restarts 0` отключает их. Force kill и подключение к чужому работающему JVM не реализованы.

Protocol 3 явно обозначает `embedded-java` и `java-file-lock-byte-0`. Parent переживает завершение child, сохраняя byte-zero FileLock до собственного окончания. Ограниченная очередь принимает только `stop/restart`, не произвольные console commands. Новый Windows provider удерживает queue/daemon handles с `NOSHARE_DELETE`; control/log файлы остаются закрытыми. Ограниченный журнал — два файла по 8 MiB. [Полный запуск, остановка и миграция](EMBEDDED-LAUNCHER.md).

## Hosting и legacy launcher

Обычная установка JAR в `plugins/` по-прежнему поднимает панель, когда хостинг запускает kernel напрямую. Управляемый restart из MCC требует нашего parent или подходящего управления со стороны хостинга. Если хостинг запрещает свою startup-команду либо убивает всё дерево процессов, parent не может пережить такой stop; используйте restart хостинга. Порт новых установок остаётся 8123, явно настроенный Main 8080 и hosting allocation не меняются. [Порт и внешний origin](HOSTING-PORT.md).

Legacy Python protocol 2 оставлен совместимым и документирован отдельно. Перед миграцией нужно штатно завершить оба старых процесса и сохранить закрытую копию. На Unix `flock` v2 и Java FileLock v3 различны, поэтому одновременный запуск запрещён; prior-PID guard не заменяет диагностику внешнего force-stop. Сборка/Windows process tests не доказывают Unix native behavior, произвольный filesystem provider или любой сторонний kernel.

## Исправления панели

- `request()` связывает внешний AbortSignal с собственным timeout controller и снимает listener в `finally`. Раньше переданный signal отменял запрос, но отключал от fetch внутренний timeout: его 20 s deadline не останавливал такой запрос. Теперь внешний cancel и timeout действуют совместно. Tests проверяют внешний cancel, уже отменённый signal, timeout и cleanup.
- Принятый server snapshot обновляет режим лицензии в текущей auth state без подмены пользователя/CSRF. Trial/temporary-full изменения из другого клиента отражаются по живому state; malformed snapshot не выдаёт доступ и не восстанавливает истёкший login. Это локальный временный режим, не подключённое лицензирование ключами.
- Отключённый `playerSnapshots` блокирует `player.*`, `players.*` и `inventory.*`: общая кнопка действия, повторная client-side проверка и серверный ModulePolicy согласованы. Проверки охватывают player heal, массовое лечение и inventory update; React render не выполняет действий.

Эти утверждения подтверждены source/regression checks. Отдельный визуальный аудит нового JAR не повторялся; прежний аудит 0.10 остаётся историей. Реальная multi-browser синхронизация здесь не заявляется: Node tests не заменяют такой фактический UI proof.

## Обновление уже установленного Core

Foundation/engine patches 0.2 не изменены. После offline замены MCC 0.11 hook перед следующим Minecraft boot обновляет **подтверждённую installed** Core capsule: проверяет старую selection, SHA оболочки/старого MCC binding и exact foundation/patchset, собирает новую привязку, сохраняет прежние Core JAR и selection, затем атомарно заменяет пару. Ошибка после замены восстанавливает проверенную пару; неизвестное параллельное изменение не перезаписывается. Миры и пользовательские базы этим refresh не заменяются.

Это не автоматический пересчёт stale pending fresh-install plan: план, связанный с прежним MCC JAR, остаётся недействующим и должен быть подготовлен владельцем заново. Если вместо Core вручную установлен обычный Paper, старая selection не преобразует его обратно в Core. При прямом запуске kernel без MCC offline hook согласованное обновление пары остаётся ручной операцией. [Core pairing, backup и откат](MCC-CORE.md).

## Текущий process-level proof: 13/13 на новом JAR

Receipt SHA-256 `6d5a56fb45b908507845ef10592bf249c7def8a52e62f70d088daebe3297a7b5` подтверждает свежий `66d4…`: все 13 real-Java synthetic child сценариев прошли и CLOSED, owned processes отсутствуют, force kill не использован. Прежняя coverage не переиспользовалась; Main не запускался и остался OFF.

Проверены normal stop; requested restart; cleanup после accepted restart; terminal startup stop; обычный незапрошенный exit; отмена recovery; exhaustion трёх recovery; refusal restart после ненулевого child exit; duplicate launcher lease; same-JAR CLI stop/restart; writer-lock stop/restart. В двух lock cases настоящий внешний Java writer удерживал эксклюзивную FileLock не менее 1,5 s, затем явно освобождал её. Restart достиг нового boot, последующий terminal CLI stop прошёл.

Ожидаемые refusal/recovery cases корректно завершаются launcher exit 7; это не утверждение exit 0 для всех сценариев. Receipt явно содержит `worldSaveClaimed:false`: synthetic child не является Minecraft, и 13 PASS не подтверждают world save/Core migration/plugin integrations. Настоящие native proofs приведены отдельно ниже; synthetic PASS их не заменяет.

## История: девять process-level сценариев предыдущего JAR

Сохранён receipt SHA-256 `9f90ce54fa5779167afb52180e1e5d1d7693e124d0ef55802b3acfeb093d3feb`: девять отдельных real-Java сценариев launcher с synthetic child прошли, все owned processes закрыты, force kill не использован. Проверены normal stop; requested restart; cleanup после accepted restart; terminal startup stop; обычный незапрошенный exit; отмена recovery; исчерпание трёх recovery; отказ restart после ненулевого child exit; duplicate launcher lease.

Ожидаемые отрицательные lifecycle cases завершили launcher с exit 7, что является правильным отказом, а не общим exit-0 результатом. Synthetic child не является Minecraft: receipt явно содержит `worldSaveClaimed:false`. Main не запускался/не изменялся; прежняя native coverage не переносилась. Это проверка настоящих процессов/leases протокола, а не доказательство сохранности Minecraft worlds, plugin integrations или Core refresh.

## Отрицательный первый native attempt и повторная проверка

Первый Paper attempt сохранён с `passed:false`, receipt SHA-256 `aa915f2da560323a153bdab25019d345f78caa419ff1a258b9e6ca77be134d19`. Harness сначала прочитал прежний stopped status до нового PID; этот результат не считается доказательством полного lifecycle/Core refresh. При последующем завершении parent сообщил `FAILED / IOException / launcher-shutdown`. Полной трассировки исходного IOException нет, поэтому конкретную строку его возникновения по одному class name установить нельзя.

CLI stop был принят и находится в durable queue: отдельное подтверждение SHA-256 `73e30e078d62414e7846dc5db649eb6a10f21b655360f299c8cea871f0190b51`. Принятый запрос не доказывает успешное завершение parent. Независимая closure-проверка SHA-256 `f04adf644357564ad61658189b437f2162be449d18a5a43463a81e86387f5369` подтвердила отсутствие всех шести наблюдавшихся JVM и Main/QA listeners, три current-run world-save summaries и отсутствие force kill. Main остался OFF. Нормальный exit 0 parent не подтверждён; наблюдавшаяся новая привязка Core не переносится как PASS на следующий attempt.

Отдельный regression воспроизвёл дефект с настоящим вторым Java-процессом: writer удерживал полную эксклюзивную FileLock, а старый `CommandQueue.read()` выполнял чтение без согласованной блокировки и получал generic `IOException` в `FileDispatcherImpl.pread0`. RED XML SHA-256 `23d8fe3f149b39e4a33145355da643b3d47e252b80b546453359c5412751d090` содержит один тест/один failure. Это самостоятельное воспроизведение дефекта, не восстановленная трассировка IOException из первого native attempt.

Теперь reader получает shared `tryLock` и сохраняет его до финальной проверки и commit cursor. Занятая writer lease откладывает чтение, не меняя cursor; genuine I/O errors не подавляются. Проверены отдельный writer process, overlapping lock того же JVM и truncation guards. GREEN CommandQueue XML SHA-256 `9189f4b5c83a4ac4d42f31ab06d1bb241cd439500dc486d4d02d771e5e6a551d` содержит девять tests, failures/errors/skips 0; соответствующий focused набор из 32 tests и новая полная сборка из 404 Java tests прошли.

Расширенный набор из 13 synthetic cases выполнен заново на `66d4…`: результат приведён в текущем process-level proof выше. Fresh Paper/Purpur и оба Paper Core paths подтверждены отдельным native proof ниже. Старые process/native receipts на новый JAR не переносились.

## Writer fix: ограниченное ожидание и повторная проверка

Shared reader создал короткое законное окно, когда writer не может сразу получить эксклюзивную lease. Отдельный RED RestartService XML SHA-256 `7e9a7f020a7b84381fd31383dc453dbf4ea656c159ee91ef8de171670ddd4ca5` подтвердил отказ старого writer: один test/один failure. Новый `LauncherQueueWriter` ждёт не более 2 s с шагом до 10 ms только при `tryLock == null` либо `OverlappingFileLockException`. Настоящие I/O errors остаются fatal; interruption сохраняется и прекращает ожидание.

Получив блокировку, CLI повторно проверяет actual daemon/child, startInstant, удерживаемую daemon lease, run UUID и identity/budget очереди. API повторно проверяет прежний контракт: неизменные PID/run, running/ready state, живой parent, authority и identity/budget очереди. API не получил OS lease/startInstant probe. Ожидание API выполняется на HTTP worker, не Minecraft main thread; ограничение не превращается в бесконечное ожидание или автоматическое повторение команды после её принятия.

GREEN RestartService XML SHA-256 `14da6e561f7ef549e6bd1463b032fedff89bbcdbb4bbab822df3678411c8a971`: 14/14; LauncherQueueWriter XML SHA-256 `6f5b97d6383697b2c6fe9aa4bbd274cd48a217e82dd366942dde5ff3b1b29412`: 4/4, failures/errors/skips 0. Focused source набор 50 PASS. Новая полная сборка из 413 Java tests прошла. Это source verification writer coordination/guards; свежие 13 synthetic cases прошли отдельно. Настоящий Minecraft lifecycle подтверждён отдельным свежим native proof ниже.

## История: native остановлен resource gate, не завершённый PASS

Прогон нового `66d4…`, receipt SHA-256 `daafd1e4ecd4eb620593a4535ed05f036e5958d600825687a4ea431edf62453f`, имеет `passed:false`. В Paper прошли genuine OP onboarding, temporary-full, сохранение LuckPerms, API restart/boot 2 и Paper→Core/boot 3; normal CLI stop дал exit 0 и три world-save summaries, owned процессы закрыты. Затем свободное место стало меньше 3 GiB: resource gate отказал до следующего fresh daemon для old binding refresh. Purpur не запускался. Это незавершённая проверка из-за ресурса, а не подтверждённый product crash; частичная coverage не переносится на повторный прогон.

Перед повтором удалены только 48 byte-exact дубликатов MCC JAR из закрытых synthetic fixtures, 4 389 758 688 байт. Три retained JAR versions, все receipts/logs/control, worlds/auth, Main backup и source сохранены. До и после подтверждены отсутствие выбранных recorded PID и private Java/Python/Node scopes, отсутствие шести listeners и Main OFF; свободное место после очистки — 7 371 030 528 байт на момент проверки. Plan SHA-256 `b566a8b0f39f7cb29d08b96c16a931662eec21d7dab4b826bd9a08989ecc9bb4`, execution SHA-256 `1e26055f8d1d3b0c72b046a94fca5255a739ccbe24944fbc8adbabe573edeb50`. Первое read-only preparation отклонялось из-за наблюдения historical PID presence, причина этого наблюдения не доказана; повторная строгая absence-проверка прошла без ослабления identity guards. После очистки свежая полная native-пара выполнена в новых fixtures с прежними gates; отдельный положительный proof приведён ниже.

## История: native остановлен неверным timestamp ожиданием harness

Receipt SHA-256 `5a485a6dec842bad77c3690149db9e37769a4dcb91e0a1543eab72f010178442` имеет `passed:false`; Paper `closed:false` в исходном отчёте вызван cleanup static-pin assertion. Независимая closure/diff проверка SHA-256 `03bbfb26e47c601844dbf5387cef03324970bdaa65daa6db7899b441705c6358` подтвердила отсутствие всех наблюдавшихся parent/child, private process scopes и шести listeners. Child штатно остановлен с exit 0 и тремя world-save summaries; exit 0 второго parent в исходном receipt не сериализован и здесь не заявляется. Main остался OFF.

Ожидалось полное равенство вновь собранной Core capsule прежнему SHA, но `JarOutputStream` выставляет время implicit `META-INF/MANIFEST.MF`. Raw SHA различаются: A `241427f8c22d75e5b83c616d2f26bb6bfaf777d4a258187f42cd257a062bdd1b`, B `34dfbbeb7df4c047d5e9bd3a50a826c7773cfbbfcc1d53e92311621f4d1ea0ec`. Все 574 entry payloads (55 990 213 B), headers/attributes/comments совпали, кроме двух значений внутри восьми DOS timestamp bytes manifest. Независимый exact comparison после нормализации только этих полей дал SHA-256 `69f2874e5c4bf3fccd0353dfb4fffbd78537d9d5610387d38d562722303af393`; время укладывалось от parent start до selection refreshedAt и до child start. Это отдельное доказательство ограниченной metadata-разницы, а не raw A=B. Harness исправляется узко: проверка разрешённых полей/времени, selection и backup, затем exact pin фактически установленного B. Исходники продукта и JAR `66d4…` не менялись; fresh полная native-пара подтверждена отдельным proof ниже, partial coverage не переносилась.

## Свежий native proof: Paper / Purpur / Core

Новый прогон на точном `66d4…` прошёл полностью, receipt SHA-256 `c11de1e4ba0db75a1dd0192b801b20122a21e2c8f2e2721d20ab2152d17f8e1a`. Paper — 14 checks, пять Minecraft child boots и два Java daemon; Purpur — семь checks, два child boots и один daemon. Прежняя частичная coverage не переносилась. Genuine whitelisted OP chat-link onboarding, собственный пароль, temporary-full и сохранение реальной unused LuckPerms permission прошли в изолированных fixtures; Main account не создавался.

Paper прошёл ordinary API restart с сохранением UUID трёх миров, gamerules, distances и записи LuckPerms, затем Paper→Core migration через offline hook. Во втором daemon старый установленный Core с MCC 0.10 binding обновлён до текущего plugin SHA перед child/world load; последующий web restart прошёл с сохранением проверенных данных. Purpur проверен обычным boot/restart/persistence/stop, без Purpur→Core migration claim.

Refreshed Core proof SHA-256 `ada7d670a2398433021004ee4678395f64faf554dab8c4eda51d465bfa051568` подтвердил exact установленный B `c85ccb49fbfed3125dd3e9b2db34e6570d04e3c552a37bb0c933f1e9dd70efa5`, прежнюю Core/selection backup pair и source identity. Payload/structure fingerprint 574 entries, 55 990 213 B, совпал с normalized SHA `69f2874e5c4bf3fccd0353dfb4fffbd78537d9d5610387d38d562722303af393`: исключены только восемь manifest DOS timestamp bytes, local/central time равны, timestamp находится между hook start и refreshedAt, до child start. После проверки установленный B закреплён exact SHA; raw SHA разных capsules равными не объявляются.

Независимая closure receipt SHA-256 `fa00669d6d0783422f9680169bde3901216eb9edae8e1fa7d142d52a436fab98`: семь children, три daemon, четыре authenticated web restart, три terminal same-JAR CLI stop. Каждый terminal stop — exit 0 и три world-save summaries, всего девять. Все 12 recorded PID, private Java/Python/Node scopes и шесть listeners отсутствуют; force kill не использован. На момент native QA, до последующего offline обновления, Main status/JAR были неизменны и Main оставался OFF. Это functional Windows lifecycle/persistence proof, не performance benchmark, Unix hosting или совместимость всех плагинов.

Отдельный ops harness proof SHA-256 `3b368d70fcdd8a11c234abb1f55941acbc8b2b2eb88d96f16e1bbb37503644d7`: 26 Python / 9 Node guards для узкой timestamp/selection/backup проверки. Они не меняют frozen fullbuild totals 413 Java / 135 Node / 32 Python и не являются дополнительными Minecraft boots. Новый визуальный аудит не повторялся; история 0.10 не переносится на новый JAR.

## Подтверждённые проверки и offline обновление

| Область | Состояние 0.11 |
| --- | --- |
| Сборка после reader + writer fixes | PASS: `66d4…`, 413 Java / 135 Node / 32 Python; source/build, не native proof |
| 13 process-level synthetic сценариев | PASS: свежий `66d4…`, receipt `6d5a56fb45b908507845ef10592bf249c7def8a52e62f70d088daebe3297a7b5`, все passed/closed без force kill; не Minecraft |
| Изолированный Paper 26.3 под embedded parent | PASS: 14 checks / пять children / два daemon, ordinary restart, Core migration/refresh, normal stop и closure |
| Изолированный Purpur 26.3 под embedded parent | PASS: семь checks / два children / один daemon, ordinary restart/persistence/normal stop и closure |
| Новая миграция Paper → Core | PASS: offline hook, actual Core brand/connected, world/LP preservation; Purpur→Core не проверялся |
| Refresh старого установленного Core с MCC 0.10 binding | PASS: verified old pair backup, новая привязка до child start, exact B pin и следующий normal web restart |
| Browser нового JAR | Отдельный визуальный аудит не повторялся; аудит 0.10 — история, Node tests не считаются screenshot proof |
| Main offline deployment | PASS: проверенная закрытая копия; заменены MCC JAR + локальные Start/Stop scripts, остальные 11 222 файла byte-exact; Main OFF без boot |

Предыдущая offline preparation была отклонена до файловых изменений из-за Windows long-path проверки helper; отрицательное подтверждение SHA-256 `3e43e785c6235911e4f094b4eb93d75ae31cb4d97febe906092118c74c33d5a0` сохранено как история. Исправлены только deployment helper и его tests: безопасное чтение длинных Windows paths и явный импорт Security module через системный Windows PowerShell 5.1 после сбоя autoload в PowerShell 7. Отдельные 20 ops tests прошли; они не входят в 32 Python fullbuild tests. Product source/JAR и native coverage не менялись; успешная последующая установка подтверждена отдельно ниже.

## Подтверждённый offline deployment Main

Закрытая резервная копия Main проверена до изменения файлов: 11 225 файлов, 726 404 948 B, SHA-256 receipt `ebdcb8aef75fabbf14c7c4691fb78e4ca50b9c5436cebd5504c3fb9468355d1e`. Все скопированные байты сверены по SHA-256; source inventory и исходные данные не менялись. SQLite integrity checks выполнялись только на дубликатах. Копия защищена private ACL и сохраняет предыдущий MCC JAR, Core/selection, configuration, auth и worlds для отдельного offline отката.

Финальная offline deployment receipt SHA-256 `a0c76cbb6c2bf75287a5f008b7d86603f0bf8717c4f9f1bb6698185da70ea306` имеет `passed:true`, `mainStillStopped:true`, `serverStarted:false`, `serverCommandsSent:false`, `forceKilled:false`. Заменены ровно `MinecraftControlCenter.jar`, `Start-Local.ps1` и `Stop-Local.ps1`; установлен текущий JAR `66d4d8e27be1e514eac9ffbf66bc8edbe0facfa555d1541be792abff75094e2a`. Остальные 11 222 файла совпали побайтно, включая installed Core/selection, auth, worlds, configuration и status. ACL, байты/identity/mtime lock-файла сохранены; исходный daemon byte-0 lease удерживался во время операции и штатно освобождён. Все проверенные Main/QA listeners отсутствуют. Откат этой успешной операции не выполнялся.

Main остаётся выключенным по распоряжению пользователя и в этой фазе ни разу не запускался. Installed Core и selection сохраняют прежний MCC 0.10 binding; его refresh отложен до следующего разрешённого пользователем управляемого boot. Fresh native proof выше подтверждает этот refresh на изолированном Paper, а не запуск Main.

## Производительность, лицензии и публикация

Нового workload benchmark 0.11 нет. Прежнее [сравнение 0.10](PERFORMANCE-COMPARISON.md) осталось INCOMPLETE (3/9 measured boots), без CI и превосходства Core; launcher process tests этого результата не меняют. Tiny parent heap не означает фиксированную RSS, снижение RAM всей JVM или гарантию отсутствия crash. Воспроизведение любой команды на Linux/hosting и совместимость всех форков не следуют из Windows проверок.

Публичное сохранение должно включать source launcher, тесты, build/recovery docs и прежние MIT/OFL/GPL attribution. Private process/queue/status receipts, world/auth/config данные, локальные Start/Stop paths и credentials в public snapshot не входят. Binary JAR/сгенерированные Minecraft sources и foundation не публикуются автоматически вместе с исходниками. Source commit и его exact remote/blob verification подтверждаются отдельно Root; старый verified commit 0.10 `1bc6120a48becce81d6bde71e6036ee14db99b2b` не доказывает сохранение новых исходников 0.11.
