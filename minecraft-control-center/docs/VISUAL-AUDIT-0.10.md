# Визуальная проверка MCC 0.10

Документ подготовлен для точной source-сборки MCC 0.10 с SHA-256 `8ff5fa8374ffa85d2ad9cbab658a055587c90f5b1e4e08549681a5d47b5a6425` (91 402 117 байт). Root завершил browser report этой сборки с PASS в описанной ниже области; отдельно завершена свежая полная native-пара Paper/Purpur с PASS. Полный новый browser wizard и workload benchmark этим QA browser отчётом не подтверждаются; Main имеет отдельный ограниченный first-run waiting report ниже; failed receipts предыдущих сборок сохранены отдельно.

## Сценарии текущей версии

| Сценарий | Что требуется подтвердить в настоящем браузере | Статус |
| --- | --- | --- |
| Чистая установка | Нет default account; понятное объяснение получения ссылки в Minecraft | Ожидается |
| Одноразовая setup-ссылка | Подтверждённый Minecraft ник, четыре шага, fragment очищается | Ожидается |
| Новая ссылка в той же вкладке | Мастер открывает новую setup-сессию, прежние пароль и план не сохраняются в форме | Ожидается |
| Пароль и пресет | Подсказки, ошибки, срок плана, подтверждение реальных изменений | Ожидается |
| Режим панели | Пробная/временная полная версия объяснены; произвольный ключ не активирует лицензию | UI Trial → temporary full PASS; новый browser key rejection не проверялся |
| MCC Core | Предложение подготовки, настоящий статус pairing и честное требование restart | Установленный Core 0.2.0/connected/sidebar PASS при 1285 и 375 px; полный wizard не повторялся |
| Завершение | Только настоящий Minecraft владелец, вход в выбранный режим, без показа credentials в снимках | Ожидается |
| Пробная боковая навигация | Подпись и отдельный SVG-замок 14 px не перекрываются в широком и узком окне | PASS: 1285/903/375/320 px, десять видимых 14×14 locks, gap 4 px |
| Адаптивность и доступность | Узкое и широкое окно, keyboard focus, reduced motion, отсутствие перекрытий и переполнения страницы | Trial widths/Core desktop-mobile без overflow PASS; новый focus/reduced-motion сценарий не проверялся |
| Обновлённый Main | Настоящая версия ядра/MCC, существующие миры/плагины, ограничения setup без подмены владельца | Deploy/liveness PASS отдельно; browser first-run waiting PASS при 1285 px, Owner wizard не завершён |

Мастер использует Minecraft-маяк и переходы Motion. В последней source-правке nav-label выделена flex-область, для отдельного SVG-замка 14 px справа оставлено место; браузер подтвердил отсутствие наложения в четырёх trial widths, описанных ниже. Курсоры Cursors Extended прежней версии сохранены. Наличие компонентов и unit-тестов не доказывает их визуальное состояние в конкретном браузере; прежние снимки MCC 0.9 и предварительной MCC 0.10 остаются отдельной историей.

## Подтверждённые source-проверки

Точная сборка прошла 354 Java, 123 Node и 32 Python теста, TypeScript/Vite/Gradle. Все 4509 входных файлов сборки остались неизменными, 4228 frontend-файлов совпали со встроенными. Build receipt SHA-256 `3ad224b7a73155e5e37d8ad2830c1990eab53f6247f86b9397feb91892388346`. В unit-проверках есть обновление setup fragment в существующей вкладке, очистка прежней формы, срок setup session/profile plan и fallback движения, а также SSR-регрессии обзора с сокращённым trial snapshot без коллекций. Исторический acdf native receipt failed и не является browser PASS текущего JAR; фактический QA browser report 8ff5 и отдельный Main first-run waiting report описаны ниже.

В текущую сборку включены два проверенных source fix. LogTail публикует максимум четыре immutable batches за pulse, до 16 строк и 24 576 B JSON UTF-8 массива; сообщение — до 4096 UTF-16 code units и 16 384 B JSON без разрыва surrogate pair. Pending raw ограничен 65 536 B, partial line — 32 768 B, плюс одна held row; Realtime queue 32 не изменена. Одиннадцать LogTail и две Realtime focused проверки прошли без skips. Equal/larger rotation не является новой проверенной возможностью. SavedImageMap публикует `ready` после записи финального manifest и удаления recovery marker, а close дожидается реального завершения worker в пределах 10 s или возвращает ошибку. Две новые deterministic проверки этого порядка включены в 20 PASS focused SavedImageMap tests. Ни одна из этих unit-проверок не является browser/native PASS.

Промежуточная full build с 352 Java тестами была failed: `recoveryPropagatesIdenticalBasePngToMissingParent` получил NPE при отсутствующей parent tile. Исходные failed log/XML/source сохранены с archive receipt `76ab3f656ec201f1f0476df3dee3a1bed72586f24a60d19a5f63065500f925db`; её результат не заменён успешным. Текущая 354-test full build выполнена после SavedImageMap fix заново.

Предварительная проверка старого JAR подтвердила получение персональной ссылки настоящим Minecraft игроком и четыре шага, но срок setup grant истёк до полного сценария. Стенд завершён штатно с exit code 0. В следующем предварительном JAR `9e252680…a3553d` найден React crash trial-обзора: сервер правомерно не отдавал закрытые коллекции, а интерфейс ожидал `worlds`. Ошибка исправлена и покрыта SSR-проверками; failed native/browser receipt сохранён, Paper закрыт штатно, Purpur в этом failed run не запускался. Эти сведения не подтверждают завершение сценариев нового JAR и не переносятся в итоговый PASS. На `b210c632…00761` отдельно засвидетельствованы полный browser wizard настоящего Minecraft владельца и UI-переход Trial → временная полная версия; native run остановился на ошибочном QA ожидании 410 вместо корректного 409 stale AdvancedBan revision. Следующий `44624331…85605b` остановился на rate-limit 429 девятого setup POST: QA добавил исходную паузу 61 s, не ослабляя требование replay 403. Оба Paper стенда завершились штатно, Purpur в этих runs не запускался. На предварительном e008 полноценный новый browser wizard не повторялся: свежий Owner создавался через настоящий Minecraft protocol/chat link и HTTP API, после чего Root отдельно проверял Trial sidebar и переключение временного режима; Core UI оставался незавершённым. Прежний browser wizard остаётся явно привязан к b210.

## Отдельные UI-наблюдения e008

Root действительно проверил Trial sidebar и UI-переход на временный полный режим в окнах 1285, 903 и 375 px. Десять SVG-замков имели размер 14×14 px, отступ от подписи не меньше 4 px; текст не перекрывался, горизонтального overflow не было. Снимки относятся к e008 и хранятся отдельно от последующих проверок.

Общий e008 native/browser receipt failed: `/api/plugins/manage` получил настоящий HTTP 500, поскольку прежний общий предел 64 MiB отвергал собственный MCC JAR 91.4 MB. Paper штатно закрыт 19:25 UTC с exit code 0, Purpur в этом run не запускался. Исправление inventory предусматривает локальный предел 128 MiB только для точного собственного имени/main MCC при прежних ограничениях каталога и ZIP. Последующие JAR требуют свежих service/Core/native и browser receipts; предыдущие удачные UI-наблюдения не заменяют эти проверки.

## Отдельный browser report 884 и failed native

Root завершил browser-884-complete.json: Trial sidebar, четыре разрешённых trial страницы, переключение временного полного режима и настоящий MCC Core 0.2 UI после миграции на Paper. Receipt `1074fc61ec3e545e0d8d59ecc3e01c698610d01a24c1823020e675c5ce29815d` имеет `passed:true`, семь screenshot references и `coreBrowserPending:false`. Создание Owner в нём выполнялось настоящим protocol/API; `freshWizardBrowserExercised:false` и `ownerCreatedInBrowser:false` сохранены. Полный browser wizard остаётся прежним доказательством b210.

Общий native 884 завершился failed, несмотря на Paper33 PASS/штатное закрытие: Purpur после восьми checks не получил automatic downgrade WebSocket ACK в пределах 15 s после успешного trial POST; full ACK 12 topics ранее получен. Service или QA timing/transport причина пока unknown. Owned PID/ports закрыты, failed receipt `4b0d50b0…786ac` сохранён. Для исторического acdf 4228 frontend assets были подтверждены побайтно равными 884; это byte verification, а не выполненный browser run acdf или текущего 8ff5 JAR. Текущий browser report 8ff5 ниже имеет собственные наблюдения; прежние снимки 884 не подменяют его и не расширяют его область.

## Failed native acdf: browser proof не создан

Immutable report `2816504787676814acf03f32353e25a38a51845d6bcf5f6230a884c8461d5710` завершён failed. Purpur31 checks / пять runs прошёл и штатно закрыт; Paper после семи checks завершился `TRIAL_GATES_FAILED` и закрыт штатно. Все десять owned PID и четыре fixture ports отсутствуют. WS hello и ACK трёх trial topics были получены; после 48 events за 66 ms наблюдался remote-end без opcode 8/close code. Получение 1013 не утверждается.

В source того acdf JAR bounded queue 32 и startup LogTail около 151 individual publishes рассматривались как кандидат перегрузки. В текущей source-сборке LogTail batching исправлен и покрыт focused tests. Свежий native proof 8ff5 прошёл Trial/full/automatic downgrade на обоих ядрах без повторения прежнего отказа; это не доказывает конкретную причину старого TCP завершения. Negative archive receipt `9e81dd75394be03e0d9fd8ccc07f7c53ce85b247c47bd1bfc1b00aaacbb99807` сохранён. Ни отдельный успешный Purpur, ни прежние снимки 884 не заменяют полную новую native-пару или browser report. Свежий browser report 8ff5 завершён отдельно; Main deployment/first-run waiting проверены отдельно ниже; performance-сессия завершена INCOMPLETE после трёх из девяти measured boots, без confidence intervals или вывода о превосходстве.

## Browser report текущего 8ff5 JAR

Фактический `browser-final.json` завершён 2026-10-11 21:42:56 UTC, SHA-256 `d6506aba7969bd7c19038f0c9e50c078e6fc829c240d235e8da53ec714a46571`: `passed:true`, `coreBrowserPending:false`, точный MCC JAR `8ff5fa8374ffa85d2ad9cbab658a055587c90f5b1e4e08549681a5d47b5a6425`. Это Trial/sidebar/temporary-full/Core UI проверка, а не повтор полного wizard. Настоящий OP Minecraft получил личную chat link; Owner создан через protocol и защищённый HTTP API. Браузер вошёл в существующий QA аккаунт, поэтому `ownerCreatedInBrowser:false` и `freshWizardBrowserExercised:false` остаются частью receipt.

| Фактический сценарий | Наблюдение |
| --- | --- |
| Trial sidebar 1285/903/375/320 px | Десять видимых locks 14×14 px, gap 4 px, текст не перекрывается, scrollWidth не превышает viewport |
| Разрешённые trial страницы | Открыты обзор, консоль, чат и настройки |
| UI temporary full | Подтверждение выполнено; locks 0, disabled navigation 0 |
| MCC Core desktop 1285 px | Установлен 0.2.0, panel connected, sidebar MCC Core, locks 0, без горизонтального overflow |
| MCC Core mobile 375 px | Установленный 0.2.0 виден, locks 0, без горизонтального overflow |

Сохранены десять screenshot references, включая отклонённые первые capture. При первом 375 px resize замки оказались скрытыми 0×0; это измерение rejected и не выдано за проверку layout. Accepted mobile screenshot — `trial-sidebar-mobile-observed-open.png` с видимыми 14×14 locks. Source review не установил причину промежуточного закрытия меню/remount, исправление продукта по нему не заявлено.

Первый `core-desktop-overview.png` снят узким при DOM 1285 и rejected как desktop proof. Accepted `core-desktop-overview-observed.jpg` показывает полный desktop: DOM 1285×892, JPEG pixels 1275×885. Начальная проверка PNG header была неверным ожиданием формата, затем проверены настоящие JPEG markers и целостность изображения. Это исправление QA-проверки, а не подтверждённый product bug. Browser PASS не заменяет отдельное доказательство native-пары, Main installation, benchmark или нового полного wizard.

## Отдельное native-доказательство текущего JAR

Свежий report SHA-256 `c156a5fbde7bb5493be7a6784e89355da743480b1615d1b039b78ce0c7a10b5e` завершён 2026-10-11 21:43:14 UTC с `passed:true`: Purpur31/Paper33 checks, по пять actual runs, оба closed/normal exit 0, четыре missing/mismatched-MCC negative boots exit 1 до миров. На живом WebSocket прошли Trial 3/full 12/automatic downgrade 3 topics. Setup был настоящим protocol/API; полный свежий browser wizard не выполнялся. Core migration, provider/LuckPerms/data preservation, четыре механики и нормальная остановка при отключении панели проверены native-путём, а не по screenshot.

Independent closure receipt `33e739266ef5f2d4f0a45de678d481c0a493aeb775ef809bea9a4b484f3d48cb` подтверждает отсутствие всех 16 owned PID и LISTEN четырёх fixture ports. TIME_WAIT/PID 0 не объявляется активным listener или незакрытым сервером. Via-translated client 1.21.1 decode before/after Core не заменяет native 26.3 client guarantee; idle samples не являются workload benchmark. Main deployment/first-run waiting проверены отдельно ниже; performance-сессия завершена INCOMPLETE после трёх из девяти measured boots, без confidence intervals или вывода о превосходстве.

## Отдельный Main first-run waiting screen

Main deployment `6a1d972cebcb809138156432885d0e949e44f49e92f0d5d21068a435d17ac6b3` и independent liveness `b9ebda6c3df4039c1f70878a6c8bbef2e3e9520e094c646242075666b71e1c2a` имеют PASS для exact 8ff5/0.10, run `5765513a-fbb3-4f9d-a0f0-52437ebdcecd`, сервер leftRunning на прежнем Paper 26.3/явном 8080. Core на Main автоматически не устанавливался. Сохранность конфигурации/UUID/закрытых БД проверяется deployment receipt, а не browser screenshot: семь SHA pins exact; `server.properties` сравнивается побайтно с исключением только валидированной второй generated timestamp строки.

Фактический browser receipt `baf578a6e14d97be25ad8d5578f24fce44a273b37416a7e6e08f476cc2c2189d` 2026-10-11 21:56:52 UTC проверяет first-run waiting screen при viewport 1285×892, JPEG screenshot 1275×885. Видны инструкции начать с Minecraft; `setupRequired:true`, default admin unavailable, личная ссылка настоящего OP ещё ожидается. `ownerCreatedByAgent:false`, `authFieldsSetByAgent:false`, `firstWizardCompletedByAgent:false`. Это PASS ожидания авторизованной ссылки, а не завершения wizard или аутентифицированного обхода Main.

Первая Main попытка была failed и штатно rolled back в 0.9 из-за обновившейся Java-generated timestamp строки; negative archive `c269855f07c59dc48eba3d4af6d5a59f355cd6181422f057fad743a679cc3786` сохранён. Только ops verifier скорректирован с сохранением всех остальных byte guards и Windows extended-length hashing; 18 tests PASS, receipt `1f43fce9658c2235395967c02d17e275419b61712087d665e589a43b999b1753`. Это не source/product crash fix. Workload benchmark завершён INCOMPLETE после трёх из девяти measured boots; отдельные workloads прошли и закрыты штатно, следующая копия/запуск остановлены по ресурсам стенда. Failed-session `41faf6a06701bafc358a3939bf4feeb1a774f4dd4440f180678037684b14e0f1` не является comparative PASS. [Частичные workload-результаты и ограничения](PERFORMANCE-COMPARISON.md) сохранены отдельно. GitHub publication подтверждается отдельно по remote ref/blob hashes; этот browser report её не доказывает.

## Ограничения

Изменения permissions/наказаний/миров и проверки отказа Core выполняются только на изолированных QA-стендах. Снимки не содержат setup token, пароля, cookie или закрытой заметки. Ширина окна сама по себе не доказывает физический touch; source fallback не выдаётся за проверку реального reduced-motion устройства.

Native lifecycle, byte verification ресурсов, Core behavior tests и workload benchmark — отдельные доказательства. Browser screenshots не показывают нагрузку Java, аллокации, сохранность БД или превосходство Core над другим ядром. [Релиз и состояние проверки](MCC-0.10-RELEASE.md), [первый запуск](ONBOARDING.md), [MCC Core](MCC-CORE.md).
