# Эффекты, CSS/SVG и lifecycle

Читай нужный раздел после выбора выразительного приёма. Рецепты показывают механизм; colors/timings адаптируй к tokens проекта, не копируй весь набор эффектов.

## CSS: кнопка и hover без зависимости

Нативная кнопка/ссылка сохраняет действие и focus. Анимация подчёркивает действие; текст остаётся читаемым. Стрелка — декоративная часть с `aria-hidden="true"`.

```html
<a class="creative-cta" href="#details">
  <span>Посмотреть детали</span><span class="creative-cta__arrow" aria-hidden="true">↗</span>
</a>
```

```css
.creative-cta {
  display: inline-flex;
  align-items: center;
  gap: .75rem;
  min-block-size: 2.75rem;
  padding: .7rem 1rem;
  border: 1px solid currentColor;
  border-radius: var(--radius-action, .25rem);
  color: var(--text-action, #173b74);
  background: var(--surface-action, #fff);
  text-decoration: none;
}
.creative-cta:focus-visible { outline: 3px solid currentColor; outline-offset: 4px; }
.creative-cta__arrow { transition: transform 180ms ease-out; }
@media (hover: hover) and (pointer: fine) and (prefers-reduced-motion: no-preference) {
  .creative-cta:hover .creative-cta__arrow { transform: translate(.12rem, -.12rem); }
}
@media (prefers-reduced-motion: reduce) {
  .creative-cta__arrow { transition: none; }
}
```

Не используй `transition: all`; перечисляй свойства. Не удаляй outline ради круглой маски. Сложный magnetic-button сохраняет hit-area неподвижным: перемещай внутренний декоративный wrapper, не кнопку под указателем.

## Clip и mask

`clip-path` задаёт резкую границу видимости, `mask-image` — прозрачность. Применяй к отдельному изображению/декору; не к контейнеру с focusable CTA.

```css
.creative-media { overflow: hidden; }
.creative-media img {
  display: block;
  inline-size: 100%;
  block-size: auto;
  clip-path: polygon(0 0, 100% 0, 100% 90%, 0 100%);
}
.creative-decoration { pointer-events: none; }
@supports (mask-image: linear-gradient(#000, transparent)) {
  .creative-decoration {
    mask-image: linear-gradient(to bottom, #000 70%, transparent 100%);
  }
}
```

Задай статично видимое состояние по умолчанию. Если анимация reveal не инициализировалась, ничего не должно остаться с `opacity: 0` или полностью закрытым clip. Mask с external URL может стать полностью прозрачным при ошибке загрузки/CORS; не полагайся на него для единственного значимого изображения. Тестируй путь доставки ассета и fallback. Для morph одинаковое число/порядок polygon-вершин обеспечивает интерполяцию; произвольные SVG `d` не обязательно совместимы. При несовместимых paths используй нормализацию в GSAP MorphSVG specialist, если он доступен, или более простой crossfade.

## SVG-линия и векторная анимация

Для значимого SVG дай доступное имя (`title` и связанный `aria-labelledby`); для декора — `aria-hidden="true"`. Уникализируй IDs mask/clip/filter в повторяемых компонентах. `viewBox` управляет масштабом, а размеры контейнера резервируют место.

```html
<svg class="creative-line" viewBox="0 0 120 24" aria-hidden="true">
  <path pathLength="1" d="M4 18 C32 2 76 2 116 18" />
</svg>
```

```css
.creative-line { inline-size: min(100%, 24rem); block-size: auto; }
.creative-line path { fill: none; stroke: currentColor; stroke-width: 2; }
@media (prefers-reduced-motion: no-preference) {
  .creative-line[data-animate="true"] path {
    stroke-dasharray: 1;
    animation: creative-draw 650ms ease-out both;
  }
}
@keyframes creative-draw { from { stroke-dashoffset: 1; } to { stroke-dashoffset: 0; } }
```

Значения dash здесь числовые, не проценты: `pathLength="1"` калибрует длину. Установи `data-animate` только когда эффект нужен; при reduced motion path видим. Для синхронизации многих линий, transform-origin и scroll бери GSAP specialist. Не строй анимацию на динамическом перерисовывании большого SVG-filter без profiling.

## Cursor-reactive эффекты

Custom cursor — декоративный enhancement, не заменитель системного указателя и не единственный способ показать возможность действия.

- Включай по `matchMedia('(hover: hover) and (pointer: fine)')`, дополнительно учитывай reduced motion. На гибридном устройстве проверь реальный `event.pointerType`.
- Один RAF считывает последнюю позицию и обновляет transform; не создавай React state update на каждое `pointermove`. Не делай layout read в каждом кадре.
- Декоративный layer имеет `pointer-events: none`, `aria-hidden`, и не является live region. Не ставь глобальный `cursor: none`.
- Регистрируй listeners и RAF в lifecycle эффекта; на cleanup удаляй listeners/`matchMedia` listeners и `cancelAnimationFrame`. Учитывай уход pointer, blur окна и hidden tab.
- Bounds для локального tilt/magnet обновляй при resize/scroll/смене layout, затем используй cached geometry. Сдерживай угол/смещение, чтобы hit-target и читаемость не страдали.

## Page transitions

Сначала выбери механизм, совместимый с router/framework и его стабильной версией. Нельзя захватывать все ссылки, терять открытия в новой вкладке, back/forward, hash-navigation или менять history ради wipe-анимации.

Для небольшого same-document DOM update применим View Transitions с feature detection:

```js
async function updateWithOptionalTransition(update) {
  if (!document.startViewTransition ||
      matchMedia('(prefers-reduced-motion: reduce)').matches) {
    await update();
    return;
  }
  const transition = document.startViewTransition(update);
  // Handle animation promises independently from the committed UI update.
  void transition.ready.catch(() => {});
  const finished = transition.finished.catch(() => {});
  // Rejected update remains a real error; do not run a side effect twice.
  await transition.updateCallbackDone;
  // A skipped animation must not cancel an already committed UI update.
  await finished;
}
```

`update` меняет DOM/состояние ровно один раз. React state scheduling и router navigation не эквивалентны синхронному DOM update; используй документированный framework integration вместо слепого копирования callback. Обрабатывай errors в вызывающем UI. Названия `view-transition-name` должны быть уникальны в одном rendered state; для старых browsers переход выполняется сразу. Для cross-document переходов отдельно проверь поддержку и same-origin ограничения.

В любом механизме сохраняй title, доступное уведомление смены страницы, осмысленный focus и согласованную scroll restoration. Visual transition не делает overlay модальным и не даёт права блокировать input до конца длинной timeline.

## Lifecycle и performance

| Симптом | Вероятная причина | Исправление и проверка |
| --- | --- | --- |
| После navigation анимация ускоряется | Повторные RAF/listeners/timelines | Cleanup; повторные mounts/navigation и memory/profile |
| Hero сдвигается после fonts/images | Нет резервной геометрии | dimensions/aspect-ratio, metrics-aware fallback; проверить CLS |
| Hover пропадает на touch | Основное действие спрятано в hover | Явный control, одинаковый функциональный путь |
| Scroll дёргается на mobile | Pin, blur/repaint, тяжёлый JS | Упростить/отключить эффект по capabilities и профилю |
| Reduced motion скрывает текст | Reveal исходно invisible | По умолчанию visible, animation opt-in |
| GPU память растёт | Постоянный `will-change`, большие promoted layers | Временный hint при необходимости; убрать после анимации |
| Morph неожиданно прыгает | Несовместимые path commands | Нормализация/compatible shapes или crossfade |

Предпочитай transform/opacity для частых кадров, но подтверждай profiling: они не гарантируют отсутствие дорогого compositing. Layout/paint-анимация может быть оправданной для короткого действия, если стоимость измерена. Не назначай `will-change` всем узлам. Общий контроль WebGL, bundle и утечек делегируй профильному skill.

## Проверенные источники

Проверены 2026-10-10; поддержку сверяй с browsers конкретного проекта.

При подготовке рецепты реально проверены через Playwright 1.62.1 / Edge 155.0.4283.45: CTA keyboard focus/hover, clip/mask, SVG visibility при reduced motion и ViewTransition update в normal/reduced/error режимах. Update вызывается один раз, rejected update передаётся вызывающему коду, unhandled page errors отсутствуют. Это browser smoke механизмов, а не тест готового сайта или доказательство всех browsers.

- [MDN: clip-path](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Properties/clip-path) — basic shapes и SVG clipping.
- [MDN: mask-image](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Properties/mask-image) — mask sources, transparency и условия загрузки.
- [MDN: SVG pathLength](https://developer.mozilla.org/en-US/docs/Web/SVG/Reference/Attribute/pathLength) — калибровка длины path и dash.
- [MDN: hover](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/At-rules/@media/hover) — возможности основного pointing device.
- [MDN: prefers-reduced-motion](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/At-rules/@media/prefers-reduced-motion) — пользовательская настройка уменьшения движения.
- [MDN: View Transition API](https://developer.mozilla.org/en-US/docs/Web/API/View_Transition_API) и [startViewTransition](https://developer.mozilla.org/en-US/docs/Web/API/Document/startViewTransition) — same-document snapshots и callback lifecycle.
- [MDN: updateCallbackDone](https://developer.mozilla.org/en-US/docs/Web/API/ViewTransition/updateCallbackDone) и [finished](https://developer.mozilla.org/en-US/docs/Web/API/ViewTransition/finished) — отдельные promises update и завершения.
