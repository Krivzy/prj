# Minecraft Control Center 0.5 — визуальная проверка

Дата: 2026-10-11, Europe/Moscow. Версия 0.5 подготовлена как кандидат. Работающий сервер 0.4 не останавливали, его JAR не заменяли; установка требует отдельного согласованного окна. Этот документ не является отчётом установки.

## Состояние проверки

**Все 13 разделов просмотрены в браузере на desktop 1440×1000 и mobile 390×844; карта дополнительно при 688×892. Текущий build `eba023c3…` имеет exact native/map UI PASS, свежий чистый idle27.202s и закрытый before/after FULL audit PASS.** Полный обход обычных страниц относится к предыдущему source preview; после него изменены только четыре RU/EN подписи карты и её backend admission, с отдельным новым просмотром карты. Прежние измерения `05946c63…` сохранены отдельно.

Повторный обход выполнялся на подготовленном frontend через read-only preview. Для обычных страниц использовались фактические ответы работающего сервера; для новой карты нужен отдельный backend 0.5, поскольку live 0.4 содержит прежний terrain/editor contract. Итоговое отображение рассматривалось по pixels. Открытие меню/фильтров и закрытие несохранённых форм не запускает игровые действия.

## Все 13 вкладок

| Раздел | Desktop 1440×1000 | Mobile 390×844 | Повторно просмотрено |
| --- | --- | --- | --- |
| Обзор | PASS | PASS | Minecraft hero, metrics и нижние панели |
| Игроки | PASS | PASS | Список, пустое состояние и читаемые фильтры |
| Миры | PASS | PASS | Полные имена и независимое размещение значков |
| Карта мира | PASS | PASS | Exact `eba023c3…`: native relief, fixed isometric surface, controls/sidebar; также688×892 |
| Мобы | PASS | PASS | Подписи, каталог и единая каменная/зелёная палитра |
| События | PASS | PASS | Пресеты, форма и modal без запуска события |
| Консоль | PASS | PASS | Журнал, фильтр и composer |
| Чат | PASS | PASS | Поток/пустое состояние и composer |
| Автоматизации | PASS | PASS | Готовый граф, Inspector и список запусков без сохранения/запуска |
| Резервные копии | PASS | PASS | Читаемые строки таблицы и локальная прокрутка |
| Плагины | PASS | PASS | Список, staged/empty состояния и фильтр |
| Журнал действий | PASS | PASS | Таблица и доступные controls без raw-data публикации |
| Настройки | PASS | PASS | Профили, модули и компоновка панелей |

На этих 12 страницах после исправлений не найден горизонтальный overflow всего документа или неожиданный error box. Таблицы с большим числом колонок сохраняют прокрутку внутри своего контейнера. Точные runtime журналы, пользовательские данные и снимки мира в публичный отчёт не включаются.

## Исправленные причины визуальных дефектов

- Mobile drawer получил горизонтальные строки с читаемыми подписями и controls высотой 44 px; нижние разделы и выход доступны. Для active marker зарезервировано место рядом с длинными названиями.
- Имя мира занимает отдельную строку и не дробится из-за соседнего значка. Состояние дополнительно просмотрено при ширине 688 px; обычные имена измерений отображаются полностью.
- Backup/audit/staged tables сохраняют нормальную ширину даты, статуса, SHA и действий; на узком экране используется локальный scroll.
- Подписи мобов и responsive catalog grid приведены к читаемому размеру. Каталоги, selected state и controls используют общую stone/grass палитру.
- Player filter не переносит короткую подпись на две строки. Settings panels выровнены по началу, без искусственного высокого пустого пространства; профили и module controls используют действующую тему.
- Plugin search при отсутствии совпадений показывает empty state с действием Clear. Shared modal focus filtering исключает hidden/disabled/inert элементы; Escape и возврат в рабочий контекст сохранены.
- Поверхности формы фейерверка приведены к каменной палитре; поздняя проверка desktop подтвердила фон `rgb(37,45,48)`. Focus-visible skip link использует общий зелёный action token.
- Независимый review новой карты исправил ложное missing-состояние при загрузке нового окна, старые hover/selection после перехода или refresh, различение tile error/deferred и конфликт bubbling mouse wheel с инерцией прокрутки страницы. Эти изменения проверены в source; финальная browser проверка карты указана ниже отдельно.

Дополнительно просмотрены English overview, пустой поиск в палитре перехода к разделам «Куда отправимся?» с 13 разделами, Plugin NoMatches/Clear и закрытие открытого overlay через Escape. Проверка пустого поиска не относится к каталогу узлов автоматизации. После обхода выполнено чтение доступных browser dev logs: список ошибок пустой. Формальный WCAG audit, все языковые варианты каждой страницы и все возможные данные серверных плагинов не заявляются проверенными.

## Текущий проверенный build

- MCC 0.5.0 JAR: `34586307` bytes.
- SHA-256: `eba023c37d590f53605b00af6d36d1e588c4553d1b0f4e5d3eb854f2d3501191`.
- TypeScript/Vite, Gradle tests и Shadow distribution: PASS.
- Node tests: 41 PASS. JUnit: 64 PASS, 0 failures/errors/skips.
- 4199 встроенных frontend файлов совпали с локальным dist по точным байтам.
- 60 собственных backend class entries проверены; 53 не изменились относительно прежнего кандидата. Девять ожидаемых add/remove/change операций ограничены WorldTerrainEngine/SavedChunkStatus. Полная идентичность backend не заявляется.
- 4194 из 4199 frontend assets остались byte-identical; изменения пяти generated entries ограничены четырьмя RU/EN literal labels и Vite import references. Styling и ordinary-page logic не изменились после полного обхода.

В эту сборку входит исправление смещения рельефа при Go/pan: навигация без явного Y выбирает верхнюю поверхность нужной колонки, удерживая ориентацию карты. Высокие участки ранее могли оказаться вне viewport из-за опоры на spawn Y. Семь regression tests и прежние native pixels подтверждают эту логику; exact новый map smoke записывается отдельно. Новый backend проверяет persisted FULL в bounded worker до Paper load, имеет один preflight/load reservation, снимает snapshot немедленно в main-thread completion и не добавляет ENTITY_TICKING plugin tickets. При low TPS/повторном snapshot attempt в tick возвращается deferred; mutable Chunk не переносится в следующий tick.

При смене мира захваченные указатели освобождаются, прежние drag/pinch отменяются. Независимая проверка жизненного цикла renderer с mocked DOM/RAF дала 6 PASS; это source-level regression evidence, не browser performance benchmark.

Один очищенный lifecycle regression в source test suite подтверждает: held pointer → world switch → pointer move сохраняет новый центр. Он первоначально добавлен после упаковки `05946c63…` как tests-only change; текущая сборка уже включает новый полный41-test запуск. Восстановимый сценарий описан в [ISOMETRIC-MAP.md](ISOMETRIC-MAP.md#navigation-lifecycle-regression).

Объёмный редактор и BuildEngine отсутствуют в дистрибутиве. Общий Three.js chunk декоративной login/overview сцены остаётся крупным; новая карта использует Canvas2D. Наличие этого chunk не означает непрерывный WebGL renderer карты.

## Текущая карта: exact `eba023c3…`

| Проверка | Статус | Подтверждение |
| --- | --- | --- |
| Native actual terrain / proto admission | PASS — API | Actual256-cell surface, height58..87/variation29/palette6; известный сохранённый proto возвращает missing и не ready, после stop его статус не стал FULL |
| Exact current desktop map UI | PASS — `eba023c3…` | Go−64/−48:5120 cells/5136000 bitmap pixels; Go−32/−32:6400/4396416. Read-only selection, новые Saved areas2021, wheel128→164% с неизменным scrollY30; drag и ArrowRight корректно меняют X/Z |
| Exact current mobile/688 map UI | PASS — `eba023c3…` | 390×844 и688×892; новые labels помещаются, controls44×44px, toolbar44px; zoom160→128%, scroll вне canvas825px сохраняет zoom128%. Page overflow и browser errors отсутствуют |
| Worldswitch /missing/spawn | PASS — `eba023c3…` | Normal→Nether→End→Normal очищает cells/selection до loading; ready других измерений не exercised. FarX/Z3M:«Нет карты участка»,cells0,sidebar очищен;«К спавну»→0/0 |
| Fresh current idle interval | PASS — `eba023c3…` |27.202s,drawDelta0,bakeDelta0,cells6400,bitmap4396416; stable1440×1000/scroll0,textures settled,no full-page capture. Прежние25.456s относятся только к059 |
| Closed same-scope FULL comparison | PASS — `eba023c3…` | FULL138→138, координаты совпали; Anvil locations2021→2021, status changes0/new proto0. Исходные11 file SHA иseed metadata не изменились; isolated process завершён штатно с exit0, live server не затронут |

«Сохранённые области» / Saved areas означает Anvil location coverage, включая proto, а не только готовый FULL terrain. Новый missing label «Нет карты участка» / No map data here не обещает, что область никогда не существовала.

Закрытое сравнение относится только к этому isolated run и scope. Оно не является запретом любых будущих действий Paper или доказательством отсутствия файловых записей при обычном сохранении сервера.

## Историческая map UI проверка: exact `05946c63…`

Этот кандидат имел SHA-256 `05946c63ebf4c47f2d9311587c331309f345627eacd58fca60ef3a377cb4a502`,34580738 bytes,41 Node/60 JUnit и58 backend classes. Измерения ниже не переименованы в результаты нового backend.

| Проверка | Статус | Критерий |
| --- | --- | --- |
| Actual native terrain | PASS — `05946c63…` | Реальный лес и крутые склоны; выбор Birch Leaves с координатами поверхности. Terrain читает 256 верхних колонок на чанк |
| Desktop/mobile pixels | PASS — `05946c63…` | 1440×1000, 390×844 и 688×892; controls 44 px, sidebar/footer доступны, editor отсутствует, page overflow/error boxes нет |
| Wheel/pan/keyboard | PASS — `05946c63…` | Zoom buttons 160→102%, wheel 102→131% при неизменном page scroll; drag и ArrowRight меняют X/Z с фиксированной ориентацией |
| Go/Spawn/world switch | PASS — `05946c63…` | Go −64/−48 и −32/−32 после settle сохраняет точные координаты; Spawn 0/0. Normal→Nether→End→Normal очищает selection и old cells до загрузки; ready terrain других измерений не заявлен |
| Loading/missing | EXERCISED — `05946c63…` | Loading при смене мира; удалённые X/Z показывают «Здесь пока нет карты» |
| Desktop error | EXERCISED — PREVIOUS PROXY OUTAGE | Offline upstream в read-only preview: cached surface, HTTP500/Retry/status, без crash/overflow. Это ошибка proxy после stop fixture, не product-native500 |
| Mobile map error / deferred | NOT EXERCISED | Mobile при offline upstream показал auth fallback с unmounted map; deferred не наблюдался |
| Idle rendering | PASS — `05946c63…` | 25.456 s, draw delta 0, bake delta 0; 6400 cells / 4396416 bitmap pixels, stable 1440×1000, textures settled, без full-page captures |
| Backend retirement | SOURCE / BUILD VERIFIED | Editor и BuildEngine удалены; authenticated retired routes отвечают 410 до body/game dispatch. Отдельный browser mutation сценарий не выполнялся |
| FULL count after this former run | OBSERVED FINDING | После native прогона FULL count вырос121→138 /2021 Anvil locations. Это finding прежней ticket-based реализации; causal attribution не подменяет controlled текущий retest |

Чистый idle interval подтверждает отсутствие постоянной raster work только в указанном браузерном состоянии. Ранний 24-second интервал с full-page capture/изменением viewport исключён из benchmark PASS. Публичный репозиторий не содержит browser captures с фактическим миром.

## Ограничения и следующий шаг

Canvas limits, 256 surface reads вместо 3072 прежних voxel positions и отсутствие idle self-scheduling — ограничения реализации. Они сами по себе не доказывают универсальные 60 FPS, field CWV, нулевой CPU overhead или устойчивость при 20 активных игроках. Idle interval относится только к конкретному browser/состоянию после окончания загрузки текстур.

В одном из прежних изолированных прогонов наблюдался fatal HotSpot G1/Watcher crash. Эта причина не объявляется исправленной изменениями CSS или карты; короткая функциональная проверка не заменяет длительный JVM soak. Данные работающего сервера не удалялись и не откатывались.

После финальной проверки карты кандидат можно устанавливать только через штатный останов/закрытую offline backup/замену JAR и новую live verification. В этой задаче такой перезапуск ещё не выполнялся. Действующие карта/API и архитектура описаны в [ISOMETRIC-MAP.md](ISOMETRIC-MAP.md), [API.md](../API.md), [LIMITATIONS.md](../LIMITATIONS.md) и [PERFORMANCE.md](../PERFORMANCE.md).
