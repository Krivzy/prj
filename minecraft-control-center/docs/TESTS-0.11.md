# Проверки MCC 0.11

Reader и writer fixes прошли отдельные source regressions и новую полную сборку `66d4…`. Свежие 13 process-level cases и native Paper/Purpur с Paper Core migration/refresh прошли и закрыты; offline установка на Main прошла: заменены ровно три файла, остальные 11 222 файла сохранены побайтно. Main остаётся выключенным и не запускался. Прежний кандидат `006689…` прошёл 12/13 cases и завершился отрицательно на `writer-lock-restart`; его owned процессы закрыты без force kill. Этот отчёт содержит только результаты 0.11; прежние proofs не переносятся на новый JAR.

## Текущая подтверждённая source-сборка

| Проверка | Фактический результат |
| --- | --- |
| JAR | 91 454 706 байт, SHA-256 `66d4d8e27be1e514eac9ffbf66bc8edbe0facfa555d1541be792abff75094e2a` |
| Build receipt | SHA-256 `1afe599c94e68bb2d00b57c3bfd87ea9d52432ed5e6c71db94b62462863d8a2e`, 2026-10-12 01:09:16 UTC |
| Java | 413 tests, failures/errors/skips 0 |
| Node / Python | 135 / 32 tests |
| TypeScript / Vite / Gradle | PASS |
| Compiler inputs | 4536, before/after неизменны; manifest SHA-256 `21e7f14a4d816b87b6e3f8051a4f29c05cc72fd8cc8d8ce160483756802a2b4b` |
| Embedded frontend | 4228 файлов совпали с dist побайтно |
| Core foundation | Прежняя 0.2.0, SHA-256 `86c96263ba9a6257cc8b03e8e8fd8f90ee03fedb396918ed1fa1f74ebe196223` |

Сборка `66d4…` не наследует process/native PASS от предыдущих кандидатов.

Expanded receipt SHA-256 `967da4f04a872eee0f6f8d2af8c94227febd354d2dd699b7dc167917ab414ba8` имеет `passed:false`: в последнем case restart с внешней FileLock достиг второго boot, затем terminal CLI stop завершился exit 1/generic IOException, его UUID не был записан в durable queue. Independent closure SHA-256 `39646af8c7d448626dc35e1500a8cce8d82756988263ab414d4b9eb903af9ea1` подтвердил все 13 CLOSED, без force kill/Main unchanged OFF. Этот результат не смешивается с прежним native case, где stop был принят и записан; точная исходная IOException trace не восстановлена.

## История сборки reader fix до writer fix

| Проверка | Фактический результат |
| --- | --- |
| MCC | `0.11.0` |
| JAR | 91 452 786 байт, SHA-256 `0066898cdbd9415ff6364d3b8d85bd3609fb17796385c7be58f5aeba7fa38b41` |
| Build receipt | SHA-256 `43b13652c4aea51119f750c79c423f75f2c5eafa3dfba10949b53b425aed856a`, 2026-10-12 00:21:42 UTC |
| Java | 404 tests, failures/errors/skips 0 |
| Node / Python | 135 / 32 tests |
| TypeScript / Vite / Gradle | PASS |
| Compiler inputs | 4533, before/after неизменны; manifest SHA-256 `bf10d7976aa8b62463bb70a610863503f66ee3d85edd78cf2630e5a078961cac` |
| Embedded frontend | 4228 файлов совпали с dist побайтно |
| JAR manifest | `Main-Class: dev.mcc.launcher.LauncherMain` |
| Core foundation | Прежняя engine 0.2.0, SHA-256 `86c96263ba9a6257cc8b03e8e8fd8f90ee03fedb396918ed1fa1f74ebe196223` |

Source/regression проверки охватывают совместную работу caller AbortSignal и 20 s timeout с cleanup, принятие live license snapshot без потери account/CSRF и запрет player/inventory actions при выключенном модуле. Node tests не являются browser screenshots или реальным multi-client UI proof.

## Windows FileLock: RED → GREEN

Отдельный настоящий Java writer удерживал полную эксклюзивную FileLock очереди. Старый reader выполнял несогласованное чтение и получил generic `IOException` в `FileDispatcherImpl.pread0`. RED CommandQueue XML SHA-256 `23d8fe3f149b39e4a33145355da643b3d47e252b80b546453359c5412751d090`: один test/один failure.

Новый reader удерживает shared `tryLock` до финальной проверки и commit cursor. Занятая writer lease откладывает чтение без изменения cursor; реальные I/O errors не подавляются. Проверены отдельный writer process, overlapping lock в том же JVM и truncation guards. GREEN CommandQueue XML SHA-256 `9189f4b5c83a4ac4d42f31ab06d1bb241cd439500dc486d4d02d771e5e6a551d`: девять tests, failures/errors/skips 0. Focused набор из 32 tests и полная сборка из 404 Java tests прошли. Это воспроизведение дефекта очереди, а не восстановленная трассировка исходного native IOException.

## История первого кандидата 0.11

Прежний JAR `24d5044a195b4fea52beccd48aac28d950bd0050e202c88ed1f3623594c8256f` прошёл 401 Java / 135 Node / 32 Python и девять real-Java synthetic child сценариев. Process receipt SHA-256 `9f90ce54fa5779167afb52180e1e5d1d7693e124d0ef55802b3acfeb093d3feb` подтверждает закрытие owned processes без force kill, но имеет `worldSaveClaimed:false`. Synthetic child не является Minecraft; ожидаемые отрицательные lifecycle cases корректно завершались exit 7.

Первый native attempt сохранён с `passed:false`, receipt SHA-256 `aa915f2da560323a153bdab25019d345f78caa419ff1a258b9e6ca77be134d19`. Harness прочитал прежний stopped status до нового PID; последующий parent сообщил `FAILED / IOException / launcher-shutdown`, полной трассировки нет. CLI stop действительно сохранён в durable queue, подтверждение SHA-256 `73e30e078d62414e7846dc5db649eb6a10f21b655360f299c8cea871f0190b51`.

Отдельная closure-проверка SHA-256 `f04adf644357564ad61658189b437f2162be449d18a5a43463a81e86387f5369` подтвердила отсутствие шести наблюдавшихся JVM и Main/QA listeners, три current-run world-save summaries, отсутствие force kill и Main OFF. Нормальный exit 0 parent не подтверждён. Наблюдавшийся новый Core binding не считается законченным Core refresh proof и не переносится на следующий attempt.

## Writer fix: RED → GREEN

Занятая shared reader lease раньше сразу отклоняла writer. RED RestartService XML `7e9a7f020a7b84381fd31383dc453dbf4ea656c159ee91ef8de171670ddd4ca5`: один test/один failure. Новый writer ожидает до 2 s с шагом до 10 ms только `null`/`OverlappingFileLockException`; interruption сохранено, genuine I/O остаётся fatal. Под полученной блокировкой CLI заново проверяет реальные daemon/child/startInstant/lease/run и queue identity/budget; API заново проверяет прежние immutable PID/run/running/ready/parent-alive, authority и queue guards, без нового OS lease/startInstant probe. API ожидание выполняется на HTTP worker.

GREEN RestartService XML `14da6e561f7ef549e6bd1463b032fedff89bbcdbb4bbab822df3678411c8a971`: 14/14; LauncherQueueWriter XML `6f5b97d6383697b2c6fe9aa4bbd274cd48a217e82dd366942dde5ff3b1b29412`: 4/4, failures/errors/skips 0. Focused набор 50 PASS и новый compiler/JAR proof подтверждены. Исходная IOException trace отрицательного expanded case не восстановлена; новый reproducible writer regression — отдельное доказательство дефекта. Fresh 13 process proof подтверждён отдельно ниже; Minecraft native proof подтверждён отдельно ниже.

## Свежие 13 process-level scenarios

Receipt SHA-256 `6d5a56fb45b908507845ef10592bf249c7def8a52e62f70d088daebe3297a7b5` относится к `66d4…`: 13/13 passed, все CLOSED без force kill, owned processes отсутствуют; предыдущая coverage не переиспользована. Main не запускался и остался OFF. Набор включает девять основных lifecycle cases, same-JAR CLI stop/restart и writer-lock stop/restart. Внешний настоящий Java writer удерживал эксклюзивную FileLock не менее 1,5 s и освобождал её явно; новый boot после restart и terminal CLI stop прошли.

Отрицательные refusal/recovery cases ожидаемо возвращают exit 7, остальные — exit 0. `worldSaveClaimed:false`: это synthetic child, а не Minecraft. Данный PASS не подтверждает сохранность Minecraft worlds, Core migration или работу сторонних plugins.

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

## Подтверждённые проверки и offline установка

| Область | Состояние |
| --- | --- |
| Build после writer fix | PASS: `66d4…`, 413 Java / 135 Node / 32 Python; таблица `006689…` историческая |
| 13 real-Java synthetic cases | PASS: новый `66d4…`, все passed/closed/no force; `worldSaveClaimed:false` |
| Fresh Paper / Purpur 26.3 | PASS: 14 / семь checks, authenticated restart/persistence/normal stop; независимая closure |
| Paper→Core и refresh старого установленного binding | PASS: actual Core/connected, verified old pair backup, strict normalized payload/time, exact B и следующий restart; Purpur→Core не проверялся |
| Browser нового JAR | Отдельный визуальный аудит не повторялся; прежний аудит 0.10 остаётся историей |
| Main offline deployment | PASS: проверенная закрытая копия, только MCC JAR и локальные Start/Stop scripts; остальные 11 222 файла byte-exact, Main OFF без boot |

Предыдущая offline preparation была отклонена до файловых изменений из-за Windows long-path проверки helper; отрицательное подтверждение SHA-256 `3e43e785c6235911e4f094b4eb93d75ae31cb4d97febe906092118c74c33d5a0` сохранено как история. Исправлены только deployment helper и его tests: безопасное чтение длинных Windows paths и явный импорт Security module через системный Windows PowerShell 5.1 после сбоя autoload в PowerShell 7. Отдельные 20 ops tests прошли; они не входят в 32 Python fullbuild tests. Product source/JAR и native coverage не менялись; успешная последующая установка подтверждена отдельно ниже.

## Подтверждённый offline deployment Main

Закрытая резервная копия Main проверена до изменения файлов: 11 225 файлов, 726 404 948 B, SHA-256 receipt `ebdcb8aef75fabbf14c7c4691fb78e4ca50b9c5436cebd5504c3fb9468355d1e`. Все скопированные байты сверены по SHA-256; source inventory и исходные данные не менялись. SQLite integrity checks выполнялись только на дубликатах. Копия защищена private ACL и сохраняет предыдущий MCC JAR, Core/selection, configuration, auth и worlds для отдельного offline отката.

Финальная offline deployment receipt SHA-256 `a0c76cbb6c2bf75287a5f008b7d86603f0bf8717c4f9f1bb6698185da70ea306` имеет `passed:true`, `mainStillStopped:true`, `serverStarted:false`, `serverCommandsSent:false`, `forceKilled:false`. Заменены ровно `MinecraftControlCenter.jar`, `Start-Local.ps1` и `Stop-Local.ps1`; установлен текущий JAR `66d4d8e27be1e514eac9ffbf66bc8edbe0facfa555d1541be792abff75094e2a`. Остальные 11 222 файла совпали побайтно, включая installed Core/selection, auth, worlds, configuration и status. ACL, байты/identity/mtime lock-файла сохранены; исходный daemon byte-0 lease удерживался во время операции и штатно освобождён. Все проверенные Main/QA listeners отсутствуют. Откат этой успешной операции не выполнялся.

Main остаётся выключенным по распоряжению пользователя и в этой фазе ни разу не запускался. Installed Core и selection сохраняют прежний MCC 0.10 binding; его refresh отложен до следующего разрешённого пользователем управляемого boot. Fresh native proof выше подтверждает этот refresh на изолированном Paper, а не запуск Main.

Никаких новых performance measurements 0.11 нет; прежнее [сравнение 0.10](PERFORMANCE-COMPARISON.md) осталось INCOMPLETE, 3/9 measured boots, без CI и доказательства превосходства Core. Parent heap 32–96 MiB не равен RSS или общему RAM сервера. Windows проверки не доказывают Unix native lifecycle либо поддержку всех kernels/hosting.

## Повторение source tests

Из корня checkout после восстановления foundation и зависимостей по [README](../README.md) и [core-engine README](../core-engine/README.md):

```powershell
npm run test --prefix frontend
.\gradlew.bat --no-daemon -PskipFrontend=true test
python -B scripts/tests/test_server_supervisor.py
```

Эти команды не запускают Minecraft. Последняя проверяет retained legacy Python protocol 2, а не новый Java protocol 3. Исполняемый launcher и управляемый запуск описаны в [EMBEDDED-LAUNCHER](EMBEDDED-LAUNCHER.md); полная область релиза и сохранённые отрицательные результаты — в [MCC-0.11-RELEASE](MCC-0.11-RELEASE.md). Частные fixtures, process/queue receipts, worlds, auth и credentials не входят в публичный отчёт.
