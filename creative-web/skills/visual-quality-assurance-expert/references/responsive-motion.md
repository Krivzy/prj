# Responsive and motion inspection

## Проверка от content constraints

Исходная матрица: 390×844, 768×1024, 1440×900. Дополняй реальными breakpoints проекта и ширинами непосредственно до/после изменённой точки. Проверяй mobile не только уменьшением browser окна: touch, отсутствие hover, reachable navigation, virtual keyboard, long labels, orientation и safe-area могут требовать device/manual inspection.

Для каждой relevant страницы:

- Header/menu: logo и CTA помещаются, menu можно открыть/закрыть, focus и overlay не потеряны.
- Hero: текст виден до/после hydration, CTA доступен, изображение не закрывает заголовок, tall viewport не создаёт бессмысленный пустой экран.
- Cards/grid: смысловой порядок сохраняется, text wrapping не ломает высоты, нет обрезанных actions.
- Forms/dialogs: errors и клавиатура не скрывают submit; контент допускает scroll внутри пригодного layout.
- Table/chart: локальный overflow отличается от document overflow, labels/legend не теряются, важные данные остаются доступными.
- Footer: длинные links/contacts помещаются, fixed elements не перекрывают последние controls.

Document width check — вспомогательный сигнал. Посмотри screenshots и проверь clipping, даже если scrollWidth равен viewport. zoom/200% text и keyboard checks подключай по изменённой layout и существующим `accessibility` требованиям.

## Motion как последовательность состояний

Один кадр не показывает плавность, доступность и lifecycle. Проверь initial, active, completed и interrupted state. Для interaction сделай repeated trigger, быстро open→close→open, route back/forward; для scroll — forward/back, refresh в middle, resize и scroll restoration. Контент должен оставаться видимым при reduced motion, отключённом animation module и asset failure.

| Тип | Что реально проверить |
| --- | --- |
| Reveal/text split | Initial text не остаётся invisible; wraps после font load/resize корректны |
| Pin/parallax | Нет jumps/blank gaps, controls не перекрыты, pin spacing пересчитан |
| Menu/dialog transition | Доступность controls, exit cleanup, focus возвращается, interruption безопасна |
| Page transition | Route действительно меняется; overlay завершается при error/back |
| Cursor/hover | Native pointer usable, touch не требует hover, scroll не блокируется |
| 3D/shader | Fallback контент, orientation/resize, canvas layering, взаимодействие не забирает navigation |

Эмуляция `prefers-reduced-motion: reduce` нужна для функционального end state; обычный режим — для actual animation. Playwright visual assertion с disabled animations упрощает baseline, но не проверяет GSAP/RAF/WebGL motion автоматически. [Playwright emulation](https://playwright.dev/docs/emulation), [Screenshot assertion](https://playwright.dev/docs/api/class-pageassertions)

Media query передаёт preference пользователя; reduced motion policy выбирается в конкретном UI и сохраняет meaningful feedback без лишнего перемещения. [MDN prefers-reduced-motion](https://developer.mozilla.org/en-US/docs/Web/CSS/@media/prefers-reduced-motion)

Если animation вызывает stutter, передай измерение `performance`/профильному animation skill: screenshot не измеряет frame time. Исправления cleanup/competing owners делай у конкретного effect, не отключай все анимации приложения. GSAP и Motion не должны одновременно владеть transform одного DOM node; допустимы отдельные wrapper levels и явно распределённые properties.
