# Tokens, темы и component contracts

Читай при создании foundations или изменении общей темы. Примеры демонстрируют структуру, а не предписывают палитру для всех продуктов.

## Выбор слоя tokens

| Слой | Пример | Зачем |
| --- | --- | --- |
| Primitive | `--color-blue-700`, `--space-4` | Повторно используемая шкала значений |
| Semantic | `--text-primary`, `--surface-panel`, `--action-bg` | Стабильная роль при смене темы |
| Component alias | `--table-row-selected-bg` | Только если компонент имеет отдельный повторяемый контракт |

Не создавай три слоя ради формальности. В маленьком проекте достаточно semantic CSS variables и короткой spacing scale. В большом проекте важны обнаруживаемость, владельцы и migrations, а не JSON большого размера.

В существующей Tailwind/shadcn конфигурации сначала установи текущую версию и mapping conventions. Tailwind v3/v4 и поколения shadcn theme не взаимозаменяемы; читай соответствующий skill/официальные docs перед изменением config. CSS variables не требуют установки token-пакета.

## Реальная CSS тема

Пример для project stylesheet. Значения — демонстрационные; цветовую концепцию выбери по brief. Варианты `data-theme` и system preference взаимно согласованы: явный user choice выигрывает у OS.

```css
:root {
  --surface-canvas: #fafaf8;
  --surface-panel: #ffffff;
  --text-primary: #20242b;
  --text-secondary: #4c5562;
  --action-bg: #264fc9;
  --action-text: #ffffff;
  --control-border: #6d7886;
  --focus-ring: #264fc9;
  --space-1: .25rem;
  --space-2: .5rem;
  --space-3: .75rem;
  --space-4: 1rem;
  --space-6: 1.5rem;
  --space-8: 2rem;
  --radius-control: .35rem;
  --radius-panel: .7rem;
  --type-body: 1rem;
  --type-title: clamp(1.5rem, 1.1rem + 1.5vw, 2.5rem);
  --content-measure: 68ch;
  color-scheme: light;
}
:root[data-theme="dark"] {
  --surface-canvas: #161a20;
  --surface-panel: #202731;
  --text-primary: #f5f7fa;
  --text-secondary: #bdc6d2;
  --action-bg: #b9caff;
  --action-text: #16223e;
  --control-border: #8a98aa;
  --focus-ring: #b9caff;
  color-scheme: dark;
}
@media (prefers-color-scheme: dark) {
  :root:not([data-theme]) {
    --surface-canvas: #161a20;
    --surface-panel: #202731;
    --text-primary: #f5f7fa;
    --text-secondary: #bdc6d2;
    --action-bg: #b9caff;
    --action-text: #16223e;
    --control-border: #8a98aa;
    --focus-ring: #b9caff;
    color-scheme: dark;
  }
}
body { color: var(--text-primary); background: var(--surface-canvas); }
.system-panel { background: var(--surface-panel); border-radius: var(--radius-panel); }
.system-action {
  min-block-size: 2.75rem;
  padding: var(--space-2) var(--space-4);
  border: 1px solid transparent;
  border-radius: var(--radius-control);
  color: var(--action-text);
  background: var(--action-bg);
  font: inherit;
}
.system-action:focus-visible { outline: 3px solid var(--focus-ring); outline-offset: 3px; }
```

Для production избегай дублирования темы в двух отдельных источниках: этот короткий CSS показывает fallback без build tooling. В большой системе собирай оба selectors из одного набора dark tokens средствами существующего pipeline. Theme choice сохраняй в разрешённом механизме проекта; не подавляй hydration warning вместо устранения причины mismatch. `color-scheme` помогает browser controls, но не заменяет авторскую тему.

## Typography, grid, spacing

- Typography role содержит font family/weight/size/line-height/measure, а не только размер. Табличные числа (`font-variant-numeric: tabular-nums`) полезны для сравнения, если font их поддерживает.
- Используй `rem` для масштабируемых UI sizes. Не фиксируй высоту поля, label или карточки так, чтобы увеличенный текст обрезался.
- Layout gutters и section spacing могут быть fluid, control spacing более стабилен. Внутренний padding компонента не заменяет внешний gap layout.
- Grid rules описывают смысл columns и transforms на узких экранах; не выводи весь продукт из одной 12-column сетки. Для вложенных компонентов используй container queries, когда нужен ответ на размер контейнера.
- Оптические исключения (иконка, крупная буква, логотип) возможны, но документируй назначение; случайные отрицательные margins на каждом экране — признак сломанного foundation.
- `z-index` имеет ограниченную шкалу roles: base, sticky, popover, modal, toast. Новый stacking context от transform/opacity/contain может изменить результат даже при большом числе.

## Component state matrix

Для реально нужных компонентов веди короткую матрицу, не создавая варианты «на всякий случай».

| Контракт | Пример проверки |
| --- | --- |
| Семантика и name | Link navigates, button acts; icon action имеет accessible name |
| State ownership | Controlled/uncontrolled правила и async completion однозначны |
| Loading | Предотвращена двойная отправка, progress назван, layout устойчив |
| Error | Связано с причиной/field; есть действие для исправления |
| Empty | Понятна причина отсутствия данных и следующий шаг |
| Disabled/read-only | Разные смыслы представлены корректно; пояснение не спрятано в hover |
| Selected/pressed | Текст/shape/state дополняют цвет; ARIA соответствует смыслу |
| Focus/keyboard | Порядок соответствует reading order; overlay восстанавливает focus |
| Data variability | Длинный label, zero, missing value, большие числа, localization |
| Theme/density | Основная функция и hit-target сохраняются во всех modes |

Default/hover/pressed должны оставаться различимыми и читаемыми. Не используй прозрачность всего control как единственный способ disabled, если она делает пояснение нечитаемым. Disabled имеет отдельные исключения в contrast criteria, но смысл и читаемость всё равно важны.

## Миграция и предотвращение дрейфа

Меняй semantic token один раз, затем проверь representative consumers. Сначала ищи usages и overrides; replacement raw hex по всей кодовой базе может затронуть иллюстрации, charts и внешние бренды. Старые aliases помечай deprecated в принятом формате и удаляй только после проверки callers.

В review ищи: новый hardcoded цвет без причины, новый spacing вне шкалы, локальный variant существующего компонента, conflicting selector и пропавшее состояние. Контекстные custom layouts допустимы. Не превращай consistency в запрет художественного решения.

## Источники

Проверены 2026-10-10.

CSS theme recipe проверен через Playwright 1.62.1 / Edge 155.0.4283.45: system light/dark и explicit user overrides работают. Для демонстрационных opaque tokens вычислены contrast ratios: light primary/canvas 14.90, secondary/panel 7.55, action 6.90; dark 16.27, 8.72, 9.72. Border/panel и focus/canvas выше 3:1 в обеих темах. Изменённые brand tokens, прозрачный glass и реальные состояния требуют новой проверки.

- [MDN: CSS custom properties](https://developer.mozilla.org/en-US/docs/Web/CSS/Using_CSS_custom_properties) — cascade, inheritance и fallback variables.
- [MDN: color-scheme](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Properties/color-scheme) — browser/native UI и поддерживаемые schemes.
- [MDN: clamp()](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Values/clamp) — bounded fluid values.
- [W3C: Contrast Minimum](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html) — 4.5:1 для обычного текста, 3:1 для большого по критерию текста и исключения.
- [W3C: Non-text Contrast](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html) — значимые controls/graphics и 3:1 относительно adjacent colors.
