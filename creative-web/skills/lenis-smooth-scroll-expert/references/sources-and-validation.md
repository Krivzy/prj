# Sources и operational validation

Дата проверки: **2026-10-10**. Официальный repository [darkroomengineering/lenis](https://github.com/darkroomengineering/lenis) связан с [Lenis site](https://lenis.dev/). Исторический URL пользователя [lenis.darkroom.engineering](https://lenis.darkroom.engineering/) остаётся ориентиром, package/docs проверяй по официальному repository. Registry snapshot: [lenis 1.3.26](https://registry.npmjs.org/lenis/latest), package import `lenis`, React adapter `lenis/react`, CSS `lenis/dist/lenis.css`.

`respectReducedMotion` в проверенном текущем README default true и реагирует live: smoothing отключается, programmatic scroll мгновенный, Lenis events могут продолжать обеспечивать sync. Это version-dependent API; на старой версии реализуй matchMedia policy или согласованно обнови dependency. Не ставь respectReducedMotion:false как performance workaround.

## Конфигурация по задаче

Не копируй все options из example. `lerp` и `duration/easing` имеют разную семантику: при заданном lerp duration/easing не задают тот же timing. AutoToggle требует recommended CSS и определённые browser capabilities; проверь поддержку целевого набора. `allowNestedScroll` может делать DOM tree checks каждый input event, поэтому targeted prevent часто проще для известных panels.

Lenis README указывает ограничения CSS scroll-snap, iframe wheel forwarding, Safari frame limits и unstable syncTouch на старом iOS. Не обещай одинаковую прокрутку на всех платформах. Для scroll-snap сначала оцени native CSS, а если Lenis обязателен — совместимый официальный lenis/snap. Не создавай JS snap, который борется с native snap и keyboard.

## Диагностика по симптому

| Симптом | Проверка | Минимальное решение |
|---|---|---|
| Scroll перестал двигаться | Disable Lenis, проверь overflow/height, isStopped и modal lifecycle | Исправь native scroll/CSS или lock owner |
| Скорость вдвое/рывки | autoRaf + custom RAF/ticker? Два providers? Умножение time? | Один instance/clock; milliseconds в Lenis |
| ScrollTrigger отстаёт | lenis scroll event → update? scroller правильный? | Официальный sync без лишнего proxy |
| Pins «съехали» после font/image | Layout settlement и refresh order | Один refresh после изменения; reserved media size |
| Modal/textarea не scroll | Root lock, nested prevent и panel overflow | Targeted prevent, правильный dialog lock |
| Anchor/skip link неверный | Автоматический anchors + собственный click? Focus/history? Header offset? | Один navigation owner + accessible focus |
| Mobile feel плохой | Native touch comparison, syncTouch setting, реальный hardware | Оставь native touch/disable feature |
| Leak после route changes | Ticker callbacks/RAF/matchMedia listeners и instances | Локальный teardown, shared root lifecycle |

## Acceptance evidence

Проверка не требует создавать весь сайт при установке skills. В будущем конкретном проекте проведи один reproducer: native baseline, enabled mode, reduce mode и destroy→native rollback. Покажи логи только технического состояния без credentials. Сравни количество instance и external callbacks до/после 5 route cycles; destroy обязан убрать принадлежавшие ему классы/events, но внешний ticker снимается отдельно.

Измерь UI latency/long tasks/scroll trace в production build с реальным wheel/trackpad/touch. Средний FPS и screenshot не доказывают качество прокрутки; проверь фокус, skip link, history, nested controls и in-view animations. Reduced transport не заменяет отдельную проверку GSAP pin/parallax и 3D camera reduced variant.

Основные APIs: [README setup/settings/methods](https://github.com/darkroomengineering/lenis), [React README](https://github.com/darkroomengineering/lenis/blob/main/packages/react/README.md), [GSAP ticker](https://gsap.com/docs/v3/GSAP/gsap.ticker/), [ScrollTrigger](https://gsap.com/docs/v3/Plugins/ScrollTrigger/). Source verification не равняется фактическому runtime испытанию каждого будущего проекта.
