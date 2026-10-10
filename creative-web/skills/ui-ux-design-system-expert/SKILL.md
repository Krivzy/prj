---
name: ui-ux-design-system-expert
description: "Создавай или развивай согласованную web design system: визуальная концепция, semantic tokens, темы, component states, typography и правила сложных SaaS/dashboard. Применяй при системных UI-решениях; локальный CSS-fix без изменения системы не требует этого skill."
---

# UI/UX Design System Expert

Отвечай за правила, из которых получают устойчивые экраны и компоненты. Дизайн-система должна помогать пользователю выполнять задачи и позволять проекту развиваться; количество tokens и вариантов само по себе не является качеством.

## Границы ответственности

- Сначала изучи уже принятую систему, theme provider, style pipeline, библиотеку компонентов, реальные состояния и product requirements. Развивай её минимальными согласованными изменениями; не запускай редизайн ради применения skill.
- Для нового визуального направления подключи существующий `frontend-design`; при необходимости вариантов используй `ui-ux-pro-max` как локальный поиск. Этот skill специализируется на переходе от концепции к контрактам tokens/components и их проверке.
- Creative interactions передай `creative-frontend-expert`, точную переработку responsive поведения — `responsive-design-expert`. Для полноценного audit WCAG/performance/browser используй установленные специализированные skills. Не перечитывай их без необходимости.
- Принятые пользователем brand guidelines, framework и библиотеки имеют приоритет. shadcn/ui или 21st.dev дают строительный материал: адаптируй состояния, tokens, семантику и ownership, а не добавляй вторую независимую дизайн-систему.

## Краткая концепция до реализации

Для сложного нового интерфейса определи аудиторию, задачу, ожидаемую плотность, контент и тон. Зафиксируй:

1. Визуальную идею, связанную с предметом продукта, и несколько правил композиции.
2. Typography roles, measure/line-height, color roles, spacing/grid, surface/elevation/radii, icon policy.
3. Режимы light/dark, density и motion, если они действительно нужны.
4. Главные компоненты и их состояния; правила responsive преобразования.
5. Acceptance criteria: сценарии, доступность, устройства и визуальные проверки.

Сохраняй решения в существующем месте design-system документации; не создавай конкурирующий source of truth. Не требуй отдельного подтверждения разумных обратимых решений, если brief достаточен.

Прочитай [tokens-and-components.md](references/tokens-and-components.md) для contracts, CSS recipes и темы. Для SaaS/dashboard, bento, glass и согласованности экранов — [product-patterns.md](references/product-patterns.md).

## Рабочий процесс

- Составь inventory: surfaces, typography, controls, states, custom CSS, неиспользуемые варианты. Отличай дефект от намеренного page-specific решения.
- Раздели primitive values, semantic roles и при необходимости component aliases. Не создай слой aliases без реальной повторяемости. Не называй tokens через случайную страницу или цвет, если роль стабильна.
- Опиши component contract: смысл, props/variants, keyboard behavior, доступное имя, loading/error/empty/disabled, размеры и responsive границы. Публичный API не привязан к визуальному трюку.
- Реализуй самый важный representative flow и проверь на настоящих данных. Затем переноси устойчивые правила в остальные экраны, сохраняя контекстные различия.
- Для charts и tables определяй единицы, формат числа/даты, доступные descriptions и различимость серий; выбор chart опирается на вопрос к данным.
- В theme не инвертируй все hex механически. Проверяй текст, border/focus, overlays, graphics, errors, disabled и native controls по их роли.
- При автоматической проверке contrast учитывай реальный background, transparency, hover/focus и изображение; значение одного token недостаточно.

## Как избегать однотипного AI-дизайна

Свяжи решения с содержанием: scale hero отражает сообщение, density dashboard отражает частоту работы, форма sections отражает их смысл. Не назначай bento, glass, gradient, rounded cards и огромные цифры как постоянную комбинацию для любого проекта. Они допустимы при понятной причине.

Единство — это стабильные отношения, а не одинаковая карточка для всех ролей. Применяй общие typography/spacing/color rules к редакционному тексту, таблице, предметной витрине и control panel; разрешай разную композицию. Декор не должен подменять hierarchy.

## Проверка результата

- Tokens централизованы в существующем style pipeline; компоненты не создают собственные theme палитры. Нет массовых hardcoded исключений и конфликтующих generic selectors.
- Основной task flow проходит с keyboard и touch. Focus видим и не перекрыт sticky/overlay. Labels и ошибки понятны без цвета.
- Каждое важное состояние проверено: default, hover/focus, pressed/selected, loading, empty, error, disabled и, где применимо, read-only.
- Light/dark и system/user override согласованы, SSR/hydration не дают theme flash/ошибку; native controls имеют подходящий `color-scheme`.
- Разные text lengths, languages, 200% text enlargement и 320 CSS px не уничтожают содержание. Данные table/map получают локальную оправданную прокрутку.
- Contrast: обычный текст ≥4.5:1; большой по WCAG текст ≥3:1; существенные non-text controls/indicators ≥3:1 там, где критерий применим. Не объявляй полное соответствие WCAG по одному automated score.
- Screenshots реальных экранов подтверждают spacing, alignment, hierarchy, readable density и сохранение индивидуальной концепции. Линт/сборка и browser сценарий дополняют визуальную проверку.

Отчёт фиксирует изменённые contracts/tokens, причины исключений, проверенные flows/themes, evidence и оставшиеся дефекты. Если система стала сложнее без улучшения сценария, упрости variants/aliases и повтори representative проверку.
