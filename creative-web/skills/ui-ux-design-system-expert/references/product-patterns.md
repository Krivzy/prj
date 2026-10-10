# Продуктовые композиции и визуальная согласованность

Читай при сложном SaaS, dashboard или выборе визуального языка. Здесь важна структура информации; general accessibility audit и browser automation подключаются отдельно при необходимости.

## Плотность и hierarchy

Начни с частоты и цены действий: что человек смотрит ежедневно, что сравнивает, что редактирует, что опасно. Primary action один для текущего контекста, а не обязательно один на всём экране. Display-типографика и огромные KPI не должны вытеснять рабочие данные.

Для dashboard определи вопрос к каждой метрике. Значение сопровождают название, единицы, период и, если нужно, сравнение. Используй одинаковое форматирование и явные missing states. Не придумывай цифры или «рост», чтобы заполнить карточки. Dynamic charts должны иметь stable dimensions, loading/error/empty и текстовое резюме/табличную альтернативу.

Table отвечает на сравнение строк/столбцов. Сначала сохраняй semantic table и реальные функции sort/filter/pagination; virtualization выбирай по объёму и measured cost, не по универсальному числу строк. Не превращай таблицу в карточки автоматически: пользователь может потерять возможность сравнения. На узком экране помогай приоритетными колонками, detail-view или локальным scroll с доступным управлением.

## Bento и редакционная вариативность

Bento работает, когда разный размер блока обозначает его роль. Не делай каждый dashboard похожим на рекламную landing page. DOM order сохраняет смысл; CSS layout не меняет порядок tab/read ради красивой мозаики.

Пример: три связанных факта, главный шире на большом контейнере. Это структура, которую следует наполнять настоящим содержанием; узкий режим — одна колонка.

```css
.system-bento {
  display: grid;
  gap: var(--space-4, 1rem);
  grid-template-columns: minmax(0, 1fr);
}
.system-bento > * { min-inline-size: 0; }
@media (min-width: 50rem) {
  .system-bento { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .system-bento__primary { grid-column: span 2; }
}
```

Сохраняй shared tokens, но разнообразь смысловую подачу: список для процесса, полноразмерное изображение для детали, table для сравнения, текст с margin notes для исследования. Не вводи decorative eyebrow, pill label и numbered icon в каждый раздел независимо от содержания.

## Light/dark, glass и премиальный корпоративный стиль

Dark theme снижает яркость surfaces и перестраивает roles/elevation; не является палитрой «все значения инвертировать». Проверяй charts, screenshots продукта, logos, error badges и layered overlays. Равномерно яркие borders могут сделать dark theme шумной.

Glass — один ограниченный слой, если фон помогает ориентироваться. Сначала работоспособный opaque fallback; затем enhancement. Контраст проверяется на худшем реальном backdrop, а не только на выбранном screenshot.

```css
.system-glass {
  background: var(--surface-panel, #fff);
  color: var(--text-primary, #20242b);
  border: 1px solid var(--control-border, #6d7886);
}
@supports (backdrop-filter: blur(8px)) {
  .system-glass[data-glass="enabled"] {
    background: rgb(255 255 255 / .94);
    backdrop-filter: blur(8px);
  }
  :root[data-theme="dark"] .system-glass[data-glass="enabled"] {
    background: rgb(32 39 49 / .94);
  }
  @media (prefers-color-scheme: dark) {
    :root:not([data-theme]) .system-glass[data-glass="enabled"] {
      background: rgb(32 39 49 / .94);
    }
  }
}
```

Рецепт — opt-in через `data-glass`, с высоким opacity; он не доказывает contrast на любом внешнем фоне. Не используй большой fullscreen animated blur без измерений, не размещай все формы поверх видео. Если blur дорогой или readability недостаточна, оставь opaque поверхность; это полноценное состояние.

Премиальный корпоративный характер выражают ясность, реальное доказательство, качественная типографика, контролируемая плотность и точная композиция. Золото, glass и gradients не являются обязательными. Минимализм не означает отсутствие labels/controls или бесконечные пустые margins.

## Подключение внешних компонентов

Если нужен компонент 21st.dev/shadcn, используй доступную официальную интеграцию и установленный skill этого источника. Не предполагай, что любой найденный snippet audited. Проверь:

- provenance/лицензию, framework/package versions и минимальный набор dependencies;
- component API, semantic role, keyboard/focus и disabled/loading/errors;
- CSS isolation, raw tokens, theme behavior, motion ownership и responsive behavior;
- код event handlers, resource fetching, third-party scripts и безопасность данных;
- стоимость bundle/DOM и то, можно ли адаптировать уже имеющийся компонент.

Разрешение на UI-задачу не является разрешением добавлять сторонний сервис с ключами или менять global MCP/config. При отсутствии авторизации используй доступный компонентный код/локальные источники и сообщи точное ограничение.

## Проверка representative flows

Выбери несколько сценариев, покрывающих реальные контракты: вход/основное действие, форма с error, table с длинными данными, empty/loading, modal/popover. Не создавай гигантский snapshot suite ради одноразового palette edit.

Проверяй screenshots themes и narrow/wide layouts, keyboard без мыши, 200% text, touch, reduced motion, slow assets и offline/error там, где сценарий с ними связан. Animated state проверяй в начале, в процессе и в устойчивом конце. Для безопасного фикса устрани первопричину (token/layout/state), затем повтори затронутые consumers.

| Антипаттерн | Более устойчивое решение |
| --- | --- |
| Все роли превращены в одинаковые карточки | Выбрать structure по задаче при общих tokens |
| Theme override через случайный hex в компоненте | Изменить semantic role или обоснованный component alias |
| «Лёгкий» font/серый текст поверх glass | Проверить реальные contrast/background и opaque fallback |
| Table mobile скрывает все важные данные | Приоритетные columns + доступная полная detail/scroll версия |
| Dashboard заполнен fictitious KPI | Реальные данные либо явно обозначенные fixtures для demo |
| Удалён focus ради визуального вида | Согласованный focus token и свободная область вокруг controls |

## Источники

Проверены 2026-10-10.

- [MDN: backdrop-filter](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Properties/backdrop-filter) — backdrop, transparency и stacking contexts.
- [MDN: minmax()](https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Values/minmax) — track sizing для grid.
- [W3C: Non-text Contrast](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html) — controls/graphics относительно окружающих цветов.
- [W3C: Reflow](https://www.w3.org/WAI/WCAG22/Understanding/reflow.html) — сохранение информации и исключения для table/map.
