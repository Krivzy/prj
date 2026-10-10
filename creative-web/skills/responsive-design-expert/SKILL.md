---
name: responsive-design-expert
description: "Исправляй и проектируй responsive web-layout: mobile-first, fluid type, Grid/Flex/container queries, touch и адаптация motion/3D. Применяй для поведения при разных viewport/container sizes и диагностики overflow; общая арт-дирекция и backend сюда не относятся."
---

# Responsive Design Expert

Отвечай за сохранение содержания, действий и характера интерфейса при изменении ширины, высоты, масштаба текста и способа ввода. Responsive дизайн — изменение отношений и поведения, а не уменьшенный desktop screenshot.

## Начальная диагностика

Изучи стек, поддерживаемые browsers, breakpoints/tokens, viewport meta, layout containers, длинные реальные данные и существующие mobile-компоненты. Воспроизведи дефект на заданном размере и состоянии; ширина сама по себе не определяет наличие touch.

Если пользователь сообщает только «на телефоне сломано», начни с реальных страниц и типичного узкого viewport, потом уточни критичные неизвестные через наблюдения. Не требуй каждый раз модель устройства для обычного layout-fix.

Для новой визуальной концепции используй существующий `frontend-design`, для foundations — `ui-ux-design-system-expert`; не дублируй их каталог палитр/стилей. Для специализированной оптимизации сцены/анимации подключай соответствующий Three.js/GSAP/Motion/GLSL skill по фактическому эффекту.

Прочитай [layout-recipes.md](references/layout-recipes.md) для CSS, container queries и первопричины overflow. Для touch, 3D/motion и проверочной матрицы — [interaction-and-verification.md](references/interaction-and-verification.md).

## Выбор layout

- Начни с устойчивого узкого состояния, затем добавляй layout при появлении достаточного места для конкретного содержания. Используй project breakpoints, но не удерживай сломанную структуру только потому, что ширина «почти стандартная».
- Grid выбирай для связанных строк/колонок, Flex для линейного потока/wrap, container queries для компонентов в разных slots. Media queries — для page-level composition и capabilities.
- Typography и spacing могут быть fluid с ограниченными `clamp()` и `rem`. Масштабирование текста проверяй отдельно; чистый `vw` и fixed heights часто делают enlargement бесполезным.
- Определи, какие компоненты складываются, меняют density, получают local scroll или переходят в detail-view. Не удаляй значимые данные/action на mobile без доступного альтернативного пути.
- DOM/keyboard order соответствует смыслу. `order` и grid placement не исправляют неправильный reading order.
- Media резервирует место (`width`/`height`, `aspect-ratio`), uses appropriate crop/source/size. Декоративный canvas не определяет размеры всей страницы.

## Минимальный безопасный fix

Сначала найди ограничение: intrinsic minimum, фиксированная ширина, длинный unbroken текст, `100vw`, offscreen transform, absolute decor, таблица, nested overflow или неверный ancestor containment. Исправляй источник, а не добавляй global `overflow-x: hidden`.

Содержательный двумерный table/map/canvas может иметь локальную прокрутку; surrounding content продолжает reflow. Декоративный bleed можно локально обрезать после проверки focus/shadow/sticky, но это не универсальное лечение layout.

## Touch и анимации

- Hover дополняет действие; важный control есть и для touch/keyboard. Ergonomic target около 44–48 CSS px — полезная цель продукта, а WCAG 2.2 AA minimum 24×24 CSS px имеет отдельные exceptions/spacing conditions. Не смешивай CSS px, pt и dp.
- Не выключай zoom и native scroll. Для gestures используй наименьшее нужное `touch-action`; drag имеет single-pointer/keyboard альтернативу.
- На touch и reduced motion исключай необязательный cursor-follow, magnetic hit-target, длинный pin/scrub и camera motion. Сохраняй содержание/действие в статичном представлении.
- Меньший экран не обязательно слабый GPU, desktop не обязательно быстрый. Для 3D выбирай measured quality tiers, ограничивай DPR, render work, postprocessing и ресурсы через scene specialist; resize обновляет camera/framing.
- Один владелец scroll/animation. При breakpoint/capability changes корректно re-init/cleanup, а не наслаивай listeners и timelines.

## Acceptance criteria

Проверяй существующий проект в браузере. CSS parse или успешная сборка не доказывают responsive результат.

- Узкие/широкие размеры, реальные project breakpoints ±1 px, короткий landscape viewport и изменение размера без reload.
- Эквивалент 320 CSS px для reflow, 200% text enlargement; реальный browser zoom отдельно от viewport emulation.
- Navigation/menu/form/table/gallery работают без потерянных actions; overlays и sticky не закрывают focus/CTA.
- Нет необъяснимого page-level horizontal overflow; локально прокручиваемый content осознан, имеет доступный путь управления.
- Keyboard, touch, hover-capable pointer и reduced motion; доступные target sizes и focus.
- Images/fonts загрузились, длинные labels/URLs/data и loading/error/empty не ломают структуру.
- 3D/animations не обрезают значимый объект, имеют fallback и не оставляют утечки после resize/navigation; performance оценивается на указанных устройствах.

После исправления повтори проблемный размер, соседние пороги и representative wide/narrow состояния. Отчёт указывает первопричину, изменённый constraint, проверенные размеры/режимы и ограничения эмуляции; не обещай поддержку всех устройств по desktop screenshot.
