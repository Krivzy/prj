---
name: visual-quality-assurance-expert
description: "Проводит визуальный контроль работающего интерфейса: запускает локальный проект, рассматривает browser screenshots, проверяет композицию, отступы, типографику, responsive states и анимации, исправляет defects и повторно проверяет результат. Применяй для visual QA и доработки отображения; новый дизайн и формальный performance/WCAG audit передавай профильным навыкам."
metadata:
  short-description: "Визуальный QA с screenshots и повторной проверкой"
---

# Visual Quality Assurance Expert

Проверяй интерфейс глазами в реальном browser и исправляй доказанные визуальные дефекты. Уважай выбранную визуальную концепцию: QA не разрешает самовольный redesign. Объясняй findings на русском языке.

## Перед проверкой

Изучи цель страницы, design reference/tokens, existing UI conventions, локальный run script, URL и critical user flows. Уточнение нужно только для отсутствующей информации, меняющей результат; иначе используй подтверждённый design system и явно обозначь допущение. Если reference отсутствует, оценивай consistency, читаемость и usability по текущей концепции, отдельно помечая субъективное улучшение.

Подключай `playwright-browser-automation-expert` для launch/interaction/capture. Можно использовать имеющийся поддерживаемый browser tool без новой зависимости. Успешные build, DOM snapshot или наличие screenshot файла не заменяют просмотр pixels. Если browser не запускается, подготовь reproducer/static fix и укажи отсутствие browser validation.

## Цикл observe → repair → verify

1. Запусти existing project script на loopback и проверь, что открыта нужная версия приложения. Не создавай новый website для QA. Сохраняй identity созданного process/session.
2. Составь небольшую матрицу route × viewport × state по изменениям. Захвати desktop/mobile, нужный tablet/breakpoint и состояния menu/modal/form/error/empty/theme. Сначала обычный stable state, затем motion states.
3. Открой screenshots через доступный image viewer. Проверяй viewport capture, отдельные проблемные regions и full-page flow; отмечай конкретное место, фактический симптом и ожидаемое поведение. Сопоставляй pixels с DOM/computed layout при поиске причины.
4. Раздели defects на блокирующие использование, нарушающие layout/иерархию и локальную polish. Исправляй root cause минимальной правкой token/component/layout/lifecycle. Не скрывай overflow или отсутствующие данные маской, opaque overlay и global clipping.
5. Повтори тот же сценарий в тех же условиях, затем соседний viewport/state, который мог пострадать. Рассмотри новые screenshots. Regression baseline обновляй только после review intended visual change.
6. Заверши, когда существенные defects устранены, повторное отображение просмотрено и critical flow работает. Если остаётся blocker среды или неразрешённое product decision, сообщи точный незавершённый участок; не трать циклы на несвязанный polish.

## Что загружать по задаче

- [Composition and defect diagnosis](references/composition.md): hierarchy, type, spacing, layout и типичные причины.
- [Responsive and motion inspection](references/responsive-motion.md): матрица touch/breakpoint/animation states.
- [Evidence and repair protocol](references/evidence-repair.md): воспроизводимые screenshots, severity, iteration и итог.
- [Official capture sources](references/sources.md): API для подтверждения, проверены **2026-10-10**.

## Совместная работа навыков

`creative-frontend-expert` и `ui-ux-design-system-expert` (или существующие `frontend-design`/`ui-ux-pro-max`) отвечают за новую концепцию и design rules. `responsive-design-expert` исправляет сложный адаптивный layout. `gsap-animation-expert`, `motion-react-expert`, `lenis-smooth-scroll-expert`, `threejs-r3f-expert`, `glsl-shaders-expert` подключай лишь при соответствующем root cause. Существующие `performance`, `core-web-vitals`, `accessibility`, `web-quality-audit` ведут измерения и формальные аудиты; этот skill не объявляет WCAG compliance или 60 FPS по screenshot.

Отчёт содержит страницы/состояния/browser/viewport, найденные и устранённые defects, подтверждённые captures, relevant tests, limitations. При meaningful regression сохраняй deterministic test; не создавай snapshot suite для каждого безопасного изменения текста. Не публикуй screenshot/traces с приватными данными без необходимого разрешения и очистки.
