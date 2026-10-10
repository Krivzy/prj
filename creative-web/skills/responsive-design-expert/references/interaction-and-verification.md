# Touch, адаптация анимаций/3D и browser verification

Читай при interactions, canvas или проверке результата. Тестовая матрица расширяется только по реальным требованиям и дефектам.

## Способы ввода и navigation

Используй capabilities: `(hover: hover) and (pointer: fine)` для декоративного hover, `(pointer: coarse)` для ergonomics, `prefers-reduced-motion` для движения. На гибридном ноутбуке primary pointer и текущий pointer event могут отличаться; проверяй `pointerType`, если это существенно. Ширина 1024 px не означает мышь, ширина 390 px не означает слабый GPU.

Сначала semantic HTML: `button` для действия, `a` для перехода, labels для полей. Small visible icon может иметь большую hit-area, если она не перекрывает соседние controls. Цель 44–48 CSS px удобна на touch, а WCAG 2.2 AA criterion 2.5.8 требует 24×24 CSS px либо удовлетворения конкретного exception/spacing condition. Не выдавай выбранную ergonomic цель за буквальный стандарт.

Не делай important action доступным только при hover. Hover tooltip не заменяет label/error; touch и keyboard должны получить смысл. Menu остается operable до/после animation; fullscreen overlay закрывается через явный control и Escape с корректным focus lifecycle. При открытом mobile keyboard поле и submit доступны через scroll; не фиксируй form в неподвижном `100vh` box.

Для touch gestures оставь browser свободу, которая не нужна эффекту:

```css
.responsive-horizontal-drag { touch-action: pan-y pinch-zoom; }
.responsive-tap-action { touch-action: manipulation; }
```

Первый вариант подходит custom horizontal drag, который сохраняет vertical pan/zoom; он не универсален для любого draggable. Native scroll carousel обычно лучше оставить с нативной прокруткой. `touch-action:none` и global `preventDefault` выключают важные функции; не применяй их ко всей странице. Drag имеет button/keyboard/single-pointer альтернативу. Не переопределяй browser back-swipe или pinch-zoom.

## Mobile images и композиция

Responsive image loading (`srcset` + точный `sizes`) выбирает подходящий ресурс; `picture` нужен для art direction/другого crop или формата. `sizes` должен соответствовать CSS slot, иначе mobile может скачать слишком большой файл. Declare dimensions/aspect-ratio; LCP-image не lazy-load по общему правилу «все картинки lazy». Содержание crop и фокальная точка проверяются отдельно от byte-size.

Текст рядом с изображением на wide layout может переместиться ниже на narrow. Не переставляй смысловые части только визуально, оставляя нелогичный DOM. Decorative background не получает ложный alt; значимое изображение сохраняет корректную текстовую альтернативу.

## Motion tiers

Опиши устойчивые states и эффекты для capabilities; не поддерживай отдельный дублированный React tree только ради анимации.

| Режим | Возможное поведение | Обязательное условие |
| --- | --- | --- |
| Wide + fine pointer | Tilt, pointer preview, умеренный parallax | Эффект не меняет hit-target и доступный смысл |
| Narrow/coarse pointer | Явные actions, короткие state transitions | Содержание и главный путь не потеряны |
| Reduced motion | Немедленное state update или простая спокойная смена | Ни один reveal/loader/pin не блокирует content |
| Слабое устройство / measured pressure | Меньше layers, без тяжёлого blur/postprocessing | Настоящий fallback и повторная проверка |

При изменении breakpoint/matchMedia удаляй прежний animation context, RAF/listeners/observers. Для GSAP используйте его documented matchMedia/context, для React — lifecycle specialist; не оставляй старые pin-spacers после отключения. Изменение высоты контента/fonts/media может требовать refresh scroll geometry после layout стабилизации; не запускай постоянный refresh в RAF.

## Адаптация 3D

Профильный Three.js/R3F skill отвечает за API и disposal; этот skill задаёт responsive contracts:

- Canvas size следует реальному контейнеру через поддерживаемый renderer/ResizeObserver, а camera aspect/projection и framing обновляются. Не задавай canvas CSS width отдельно от backing render size без понимания результата.
- Пересмотри camera distance/FOV/target по bounds и composition. Узкий экран часто требует другого framing, а не просто меньшего объекта; значимая деталь не обрезается.
- Cursor rotation меняется на спокойное представление или явный control на touch. Для важного поворота предмета есть button/single-pointer alternative, scroll страницы не захвачен глобально.
- Ограничивай DPR и quality по measurement; снижать resolution, shadows/postprocessing/particles обычно полезнее, чем скрывать смысл. Загрузка models/textures имеет состояния progress/error и устойчивый poster/fallback.
- Render on demand возможен для неподвижной сцены; continuous animation останавливается offscreen/hidden, когда это соответствует движку и UX. Не обещай 60 FPS без profile на подходящем устройстве.
- Reduced motion не получает обязательный camera fly-through. При WebGL unavailable/context loss/error содержательный DOM/poster продолжает работать.

## Практическая матрица browser проверки

Начальный sampling при отсутствии заданных размеров: 320, 390, 768, 1024 и 1440 CSS px, плюс короткий landscape. Это проверочная выборка, не рекомендованные CSS breakpoints и не доказательство всех устройств. Для каждого реального layout threshold проверь `threshold-1`, threshold и `threshold+1`.

Проверяй только representative flows плюс затронутые состояния, избегая бессмысленного Cartesian product. При доступном Playwright автоматизируй функциональные сценарии, overflow measurements и screenshots через установленный browser skill; при ручном UI используй доступный browser control. Emulation не подтверждает реальные GPU, browser bars, keyboard, notch или screen reader.

| Измерение | Что проверить |
| --- | --- |
| Viewport/height | Narrow/wide, breakpoint edges, landscape, resize без reload |
| Text/zoom | Long text/localization, 200% enlargement, reflow 320 CSS px |
| Input | Keyboard, hover pointer, touch, drag alternative, focus visibility |
| Content state | Loading/empty/error, реальные большие данные, delayed fonts/media |
| Motion | Normal/reduced, start/mid/end, быстрый повторный input |
| Navigation | Menu, route/back/hash, overlay open/close, scroll restoration |
| Scene/media | Framing, poster/WebGL fallback, resource failure, context cleanup |

Read-only overflow assertion в Playwright может сравнить `scrollWidth <= clientWidth + 1`. Он находит геометрию, а не качество дизайна: у страницы может быть hidden overflow с недоступным текстом или намеренный двумерный content. Осмотри screenshots и реальную operability. Для выбранной локальной table exception проверяй surrounding content и доступность локального scroll.

Реальный browser zoom 400% от 1280 px соответствует ширине 320 CSS px для reflow, но viewport-only тест не заменяет проверку поведения при zoom/text settings. Не применяй `deviceScaleFactor: 2` как замену text zoom: он описывает raster density.

Перед screenshot дождись целевого устойчивого состояния, fonts ready и нужных images; не прячь проблему через произвольный большой timeout. Для visual regression фиксируй fonts/data/viewport/animation time, чтобы diff был связан с изменением. Manual screenshots всё равно нужны для композиции и hierarchy.

## Типичные неудачи и проверка фикса

- Global `overflow-x:hidden` скрывает focus/content: исправить intrinsic/fixed constraint, повторить keyboard и overflow test.
- Tiny desktop UI после `transform:scale()` на mobile: перестроить layout/density и hit-targets.
- `100vh` overlay за keyboard/browser bars: использовать подходящие dynamic units/scroll container, подтвердить на устройстве.
- Breakpoint re-init дублирует ScrollTrigger/RAF: lifecycle cleanup и несколько resize/navigation циклов.
- Mobile scene обрезана: адаптировать camera/framing; screenshots и interaction на narrow/landscape.
- CSS reorder создаёт неправильный tab order: исправить DOM и связанную визуальную композицию.
- Нестабильный image crop: явно задать object-position/art-directed source и проверить предмет на всех выбранных размерах.

## Проверенные источники

Проверены 2026-10-10.

- [MDN: hover](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/At-rules/@media/hover) — primary pointing capability.
- [MDN: touch-action](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Properties/touch-action) — взаимодействие browser pan/zoom и custom gestures.
- [MDN: Responsive images](https://developer.mozilla.org/en-US/docs/Web/HTML/Guides/Responsive_images) — resolution switching, `srcset`/`sizes` и art direction.
- [MDN: prefers-reduced-motion](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/At-rules/@media/prefers-reduced-motion) — настройки движения.
- [W3C: Target Size Minimum](https://www.w3.org/WAI/WCAG22/Understanding/target-size-minimum.html) — WCAG 2.2 AA 24 CSS px, spacing и exceptions.
- [W3C: Resize Text](https://www.w3.org/WAI/WCAG22/Understanding/resize-text.html) и [Reflow](https://www.w3.org/WAI/WCAG22/Understanding/reflow.html) — enlargement и equivalent narrow viewport.
