# CSS раскладка и диагностика overflow

Читай для конкретного responsive layout/fix. CSS recipes адаптируются к существующим tokens и поддерживаемым browsers; не добавляй второй reset поверх project reset.

## Устойчивая основа и fluid typography

```css
*, *::before, *::after { box-sizing: border-box; }
.responsive-shell {
  inline-size: min(100%, 76rem);
  margin-inline: auto;
  padding-inline: clamp(1rem, 3vw, 2rem);
}
.responsive-title {
  font-size: clamp(2rem, 1.25rem + 3vw, 5rem);
  line-height: 1.05;
  overflow-wrap: anywhere;
}
.responsive-copy { max-inline-size: 68ch; line-height: 1.6; }
.responsive-media { display: block; max-inline-size: 100%; block-size: auto; }
```

`overflow-wrap: anywhere` уместен для длинных unbroken строк/URLs, но не должен заменять грамотную типографику обычного текста. `word-break: break-all` делает текст труднее читать. `clamp()` limits не доказывают поддержку 200% text enlargement: проверь реальные browser/text settings, особенно большой max с `vw`-частью.

Не запрещай user scaling в viewport meta. Для обычной страницы: `width=device-width, initial-scale=1`; `viewport-fit=cover` нужен только при осознанной работе с safe-area.

## Intrinsic Grid/Flex

```css
.responsive-grid {
  display: grid;
  gap: 1rem;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 18rem), 1fr));
}
.responsive-grid > * { min-inline-size: 0; }
.responsive-toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: .75rem;
}
.responsive-toolbar__search { flex: 1 1 16rem; min-inline-size: 0; }
```

`minmax(0, 1fr)` и `min-inline-size: 0` позволяют track/item с длинным content реально сжиматься. По умолчанию Grid/Flex items часто сохраняют intrinsic minimum; overflow текстов исправляй также внутри компонента. `auto-fit` схлопывает пустые tracks, `auto-fill` сохраняет их: выбирай по ожидаемой геометрии, а не взаимозаменяемо.

## Container queries с fallback

Измеряемый ancestor должен быть отдельным от query target. Size query не меняет собственный container по его размеру; query применяет правила к потомкам.

```html
<div class="responsive-region">
  <article class="responsive-feature">
    <div class="responsive-feature__media">Медиа</div>
    <div class="responsive-feature__content">Содержание</div>
  </article>
</div>
```

```css
.responsive-feature { display: grid; gap: 1rem; grid-template-columns: minmax(0, 1fr); }
.responsive-feature > * { min-inline-size: 0; }
@supports (container-type: inline-size) {
  .responsive-region { container: feature / inline-size; }
  @container feature (min-width: 36rem) {
    .responsive-feature { grid-template-columns: minmax(0, 1fr) minmax(0, 1.4fr); }
  }
}
```

Устойчивое одноколоночное состояние — полноценный fallback. Если требуется старый browser и двухколоночный fallback, добавь media query внутри `@supports not (container-type: inline-size)`; он менее точен для sidebar. Не задавай `container-type: size` без обдуманного block sizing: это меняет containment и может неожиданно схлопнуть содержимое.

`cqi` следует размеру подходящего query container; при его отсутствии units имеют viewport fallback. Если поведение критично, явно создай container и проверь размещение компонента в узком sidebar, а не только в большом viewport. CSS custom properties не подставляются как `var(--breakpoint)` в обычные media/size query conditions; используйте существующий build mechanism или реальный literal threshold.

## Высота экрана и full-bleed

Hero обычно имеет `min-block-size`, а не фиксированную высоту; content может вырасти. Stable `svh` полезен для композиции при mobile browser bars; `dvh` — для overlay, который должен следовать текущей доступной высоте. Keyboard и реальные safe-area проверяй на устройстве.

```css
.responsive-hero { min-block-size: 70vh; }
@supports (height: 100svh) {
  .responsive-hero { min-block-size: min(44rem, 100svh); }
}
.responsive-overlay {
  max-block-size: 100vh;
  overflow: auto;
  padding-block-end: max(1rem, env(safe-area-inset-bottom));
}
@supports (height: 100dvh) { .responsive-overlay { max-block-size: 100dvh; } }
```

Full-bleed лучше сделать parent grid с внешними rails и `grid-column: 1 / -1`, а содержимое поместить в центральный track. `width:100vw` внутри centered container может учитывать scrollbar и вызвать overflow. Не маскируй это на `html/body`.

## Диагностика horizontal overflow

Сначала сравни `document.documentElement.scrollWidth` и `clientWidth`. В DevTools Console целевой страницы следующий read-only snippet показывает кандидатов:

```js
(() => {
  const width = document.documentElement.clientWidth;
  return [...document.querySelectorAll('body *')].map(element => {
    const rect = element.getBoundingClientRect();
    return { element, left: rect.left, right: rect.right, width: rect.width };
  }).filter(item => item.width > 0 && (item.left < -1 || item.right > width + 1));
})();
```

Результат — кандидаты, не список гарантированных дефектов: offscreen drawers, carousel items, transformed decor и clipped children могут быть намеренными. `getBoundingClientRect()` включает transforms, но не даёт полной картины pseudo-elements/shadows. Если кандидатов нет, проверь `::before/::after`, scrollable ancestors, document width и layout overlays. Большой DOM обход выполняй по запросу, не в каждом RAF.

Порядок проверки:

1. Воспроизведи размер/scroll/state, исключи открытый намеренный drawer.
2. Найди первый ancestor, где ширина начинает превосходить доступную; сравни computed width/min-width, padding/border и track sizing.
3. Проверь fixed px width, `100vw`, nowrap, unbroken text, image intrinsic size, negative margin, transforms и absolute positioning.
4. Для Grid/Flex исправь intrinsic minimum; для content — wrap/resize; для двумерных данных — локальный overflow с сохранённой семантикой.
5. При намеренном decorative bleed обрежь его собственный wrapper после проверки focus/sticky/shadow. Не убирай scrollbar вместо исправления.
6. Перепроверь `scrollWidth/clientWidth` и сценарии на соседних widths и после загрузки fonts/images.

Не применяй `max-width:100%` ко всем узлам: это ломает intentional tracks/table и не всегда исправляет их intrinsic minimum. Не компенсируй overflow сотней точечных breakpoint patches.

## Проверенные источники

Проверены 2026-10-10.

CSS recipes проверены на isolated content через Playwright 1.62.1 / Edge 155.0.4283.45: overflow отсутствовал при viewport widths 320, 390, 575–577, 767–769, 799–801, 1024, 1440 CSS px с длинным unbroken heading. Named container переключил feature с 1 на 2 columns при 575/576/577 px; computed `touch-action` равен `pan-y pinch-zoom`. Проверка не заменяет реальные данные, mobile device, text zoom или другие browser engines.

- [MDN: Container queries](https://developer.mozilla.org/en-US/docs/Web/CSS/CSS_containment/Container_queries) — ancestor containment, named containers, units и fallback.
- [MDN: clamp()](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Values/clamp) — bounded fluid values.
- [MDN: minmax()](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Values/minmax) — intrinsic track sizing.
- [MDN: overflow-wrap](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Properties/overflow-wrap) — перенос unbroken strings.
- [MDN: length](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Values/length) — viewport units `sv*`, `lv*`, `dv*` и relative lengths.
- [MDN: getBoundingClientRect](https://developer.mozilla.org/en-US/docs/Web/API/Element/getBoundingClientRect) — rendered geometry относительно viewport.
- [MDN: CSS custom properties](https://developer.mozilla.org/en-US/docs/Web/CSS/Using_CSS_custom_properties) — область применения `var()` и query ограничения.
