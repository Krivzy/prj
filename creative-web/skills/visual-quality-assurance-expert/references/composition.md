# Composition and defect diagnosis

## Проход по hierarchy

Начни с full composition: что пользователь понимает первым, где основное действие, как контент разбит на смысловые группы. Затем проверь секции, components и microspacing. Несколько минут на общей иерархии полезнее десятков исправлений border radius при потерянном CTA.

| Область | Наблюдение на screenshots | Root causes для проверки |
| --- | --- | --- |
| Hierarchy | Нет доминирующего заголовка, competing CTAs, равный вес всему | Token hierarchy, actual content priorities, слишком много accents |
| Grid/alignment | Ломаные оси, элементы секций расходятся | Разные containers, padding, min-width, nested grid gaps |
| Spacing | Нет группировки, прыгающие отступы, modal cramped | One-off margins, line-height, missing layout gap/token |
| Typography | Нечитаемый мелкий текст, broken wraps, неожиданная ширина | Font failed/fallback, missing weight, font metrics, max-width |
| Images/media | Неправильный crop, stretch, late jump, невидимый focal point | Aspect ratio, object-fit/position, reserved dimensions, asset failure |
| Controls | Label clipped, CTA offscreen, inconsistent states | Fixed height, untranslated long strings, unavailable state styles |
| Layering | Menu behind canvas, sticky overlaps heading | Stacking contexts, transformed parent, portal, z-index contract |
| Data UI | Numeric alignment unclear, legend broken, table leaks body | Tabular numerals, container height, overflow region, responsive presentation |

Это диагностика, а не новый style guide. Используй фактические tokens/intent. Bento, glass, dark mode и expressive type не являются дефектом сами по себе; проверяй их пригодность содержанию и читаемость. Не превращай все проекты в один «premium» шаблон.

## Typography inspection

Проверь реальный font load, кириллицу/нужный язык, 400/500/700 веса и fallback. Сравни heading line breaks с reference на том же viewport. Не фиксируй перенос `<br>` ради одной ширины, если это ломает остальные. Long title, длинное имя/URL, числовые значения и error message помогают выявить weak container rules.

Флюидный размер требует нормальной строки: достаточный line-height, допустимая line length и max measure. Изменение font-size отдельно от spacing может нарушить композицию. Для dialog/forms/table проверяй вторичный текст и placeholder, а не только Hero.

## Из pixel symptom к минимальной правке

1. Найди DOM элемент и bounding box/computed style. Убедись, что screenshot соответствует нужному UI state, а не loading/fallback.
2. Определи место неправильного constraint: child intrinsic sizing, parent grid/flex, text wrapping, transform или stacking.
3. Исправь этот constraint. Например, overflowing flex child часто требует `min-width: 0` и пригодного wrap, grid может требовать `minmax(0, 1fr)`; `overflow-x:hidden` на body скрывает симптом.
4. Если ошибка повторяется во многих components, правь общий token/component; если это единственный intentional variant — локальную rule.
5. Сравни before/after для одинакового content. Изменение unrelated spacing/font/theme усложняет оценку и может ввести regressions.

Objective defects фиксируй как факты: «кнопка выходит за viewport на 390px», «заголовок перекрыт fixed header». Aesthetic proposals формулируй отдельно: «уменьшение emphasis вторичных cards может усилить основной показатель». Не навязывай subjective preference как ошибку.
