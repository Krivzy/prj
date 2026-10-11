# MCC 0.6 — проверки интерфейса и native-контракта

Дата: 2026-10-11. Статус: **проверенный кандидат, подготовленный к установке; production не обновлён этим аудитом.**

| Финальный результат | Подтверждённое значение |
| --- | --- |
| SHA-256 JAR | `6fcf84b7d2c793cfb85201d23b38026d11d5ecaf483108631edff1ba47ec9a85` |
| Размер JAR | 34 666 853 bytes |
| Сборка и regression tests | 114 Java/JUnit и 50 Node frontend tests PASS |
| Native | Отдельный Paper 26.3 fixture: два запуска точного JAR, restart, два normal shutdown с exit code 0 |
| Browser | 39 проверок: 13 вкладок × ширины 390, 688 и 1280 px |
| Production | Не затрагивался; recovery/install/launch требуют отдельного согласования |

Финальные native, JVM, complete-map и browser receipts имеют PASS и относятся к этому JAR. Native receipts связаны с одним fixture; фактические startup arguments подтверждены для обоих запусков. Последняя правка повторного префикса версии включена в окончательный пакет и повторно проверена.

## Сборка и независимые проверки

| Проверка | Результат | Граница доказательства |
| --- | --- | --- |
| Java/JUnit | 114 tests PASS | Regression coverage исходников конечного кандидата |
| Node frontend | 50 tests PASS | Включая проверки новой карты и каталога |
| Catalog security/lifecycle | 25 tests PASS, включая Windows junction guard | Входит в Java coverage; не прибавляется к 114 |
| Offline account migration | 7 synthetic scenarios PASS | Только новые disposable SQLite DB, без production |
| Microsoft JVM flag probe | PASS на Java 25.0.1+8 | Standalone `-version`/`PrintFlagsFinal`, отдельно от native startup |

Миграционные fixtures проверили успешную смену единственного исходного Owner, сохранение audit/documents, отказ при устаревшем fingerprint, другом аккаунте, нескольких аккаунтах, hardlink backup и наличии WAL, а также транзакционный откат при нарушенной postcondition. Значения password hashes и содержимое пользовательской базы в отчёт не входят.

Каталожные tests покрывают exact same-release compatibility, actor/TTL/single-use plans, active Owner authority, SSRF/redirect/identity guards, безопасный YAML, зависимости в том числе у повторно используемых JAR, no-overwrite, отмену задач и rollback только своих новых файлов. [Контракт каталога](PLUGIN-CATALOG.md).

## Интерфейс

Проверены все 13 разделов: Обзор, Игроки, Миры, Карта мира, Мобы, События, Консоль, Чат, Автоматизации, Резервные копии, Плагины, Журнал действий и Настройки. Каждая вкладка открыта при ширинах 390, 688 и 1280 px. Во всех 39 проверках отсутствовали horizontal page overflow и сообщения об ошибках, зафиксированные матрицей.

| Дополнительная проверка | Фактический результат |
| --- | --- |
| Карта | Наблюдались 8 PNG tiles и 0 Canvas; pan изменил положение изображений на 64 px, zoom изменился с 38% до 53%; режим всего мира и пустой Nether просмотрены |
| Плагины | Поиск и план LuckPerms открыты; план отменён; у версии один префикс `v` |
| Пользователи | Delete confirmation просмотрен и отменён; удаление своего аккаунта отключено |
| Изображения миров | Просмотрены неподвижные SVG-блоки с совпадающими гранями и local texture/fallback |

Pixel review охватил desktop карту, карточки миров, план плагина и диалог удаления, а также mobile миры и каталог. Матрица остальных страниц подтверждена DOM-проверками компоновки; это не заявление о pixel review всех возможных состояний. Browser-диалоги plan/delete были отменены. Фактические операции установки и удаления тестовых пользователей проверялись отдельно через native API.

Pinch, reduced motion, formal WCAG audit, многопользовательский stress и длительный browser/JVM soak не выполнялись. Не заявляются проверки всех возможных loading/error состояний и игрового поведения сторонних плагинов.

## Постоянная PNG-карта

| Проверка | Фактический результат |
| --- | --- |
| Modern saved-record decoder probe | 138 сохранённых FULL records успешно декодированы, 1883 non-FULL/proto исключены, 0 ошибок декодирования; всего 2021 occupied Anvil slots |
| Завершённый обход NORMAL | 138 rendered FULL chunks / 2021 saved slots; pending 0, failures 0, scan timestamp установлен |
| Nether и End | Оба измерения просканированы; legitimate empty: 0 rendered chunks / 0 saved slots, pending 0, failures 0 |
| Первоначальная подготовка карты | Наблюдённое `initialRenderMs=195299` в этом fixture |
| Native API | Manifest, PNG pyramid, ETag и auth PASS |
| Resource caps | Chunk loads 0, plugin tickets 0, один background worker |
| После normal restart | Готовый map cache восстановлен; точный JAR снова запущен и нормально остановлен |

Saved slots включают незавершённые записи; это не число готовых FULL chunks. Ноль ошибок относится к указанному decoder probe и не является общим integrity check всего мира. Просканированное пустое измерение отличается от неоконченного, paused или ошибочного обхода. Complete-map receipt подтверждает законченный обход всех трёх измерений и нулевые pending/failure counters.

Просмотр использует DOM `<img>` PNG tiles и отдельные live player markers. Он не запускает foreground terrain sampler/WebGL renderer или construction tools. Ограниченный background worker готовит сохранённые изображения независимо от зрителей; первый обход расходует CPU и диск. Нулевые chunk loads/plugin tickets не означают нулевой overhead. Время первого обхода — измерение одной fixture, не SLA или benchmark для любого размера мира и числа игроков. [Архитектура и ограничения](PERSISTENT-IMAGE-MAP.md).

## Реальная установка плагина

Через native catalog API установлен настоящий Chunky 1.5.3 с проверкой опубликованного SHA-512 и локального SHA-256 `530d2c7430a96a39957391b7088be144daa3108f7665896d1c23aa8dd4af32f3`. Stable release заявил совместимость с точной версией Minecraft 26.3 и Paper/Bukkit/Spigot. После штатного restart fixture Paper фактически распознал установленный плагин как enabled; map cache также сохранился.

Это подтверждает download/validation/install и загрузку указанного артефакта в контрольном окружении. Полное игровое поведение Chunky и совместимость произвольных сторонних JAR не проверялись. В production этот аудит сторонние плагины не устанавливал. Для нескольких файлов нет filesystem-wide atomicity при crash/power loss; обычный rollback отменяет только свои новые файлы. [Контракт MCC](PLUGIN-CATALOG.md), [Modrinth version API](https://docs.modrinth.com/api/operations/getprojectversions/), [Paper plugin.yml](https://docs.papermc.io/paper/dev/plugin-yml/).

## JVM и deployment

Сигнатура прежнего сбоя Microsoft JVM совпадает с [vendor issue #677](https://github.com/microsoft/openjdk/issues/677). Документированный обход: `-XX:+UnlockDiagnosticVMOptions -XX:-G1UseTimeBasedHeapSizing`. Standalone probe показал `G1UseTimeBasedHeapSizing=false`, `UseG1GC=true`. Оба фактически наблюдённых native запуска окончательного JAR использовали эти аргументы; runtime и G1 сохранены. Конкретная выпущенная версия с исправлением в issue не названа. [Объяснение и границы проверки](JVM-G1-MITIGATION.md), [Microsoft release notes](https://learn.microsoft.com/en-us/java/openjdk/release-notes#openjdk-25).

Два успешных запуска и normal shutdown являются короткой функциональной проверкой, а не длительным JVM soak test. Они не гарантируют снижение CPU или отсутствие всех будущих сбоев.

Production recovery/install/launch, offline WAL checkpoint, конкретная миграция локального аккаунта и включение map module остаются подготовленными. После аварийного завершения сначала подтверждают отсутствие прежних процессов и сохраняют raw DB/WAL/SHM backup; crash нельзя объявлять normal stop. Production installation и post-deployment browser/API checks требуют отдельного согласования и собственного verified receipt.
