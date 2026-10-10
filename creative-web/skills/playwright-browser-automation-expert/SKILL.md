---
name: playwright-browser-automation-expert
description: "Автоматизирует browser inspection и проверяет реальные пользовательские сценарии через Playwright: формы, навигацию, ошибки JavaScript/API, screenshots, visual regression и разные browser/viewport. Применяй для browser automation и E2E; оценку художественного качества проводи вместе с visual-quality-assurance-expert."
metadata:
  short-description: "Browser automation, E2E и visual regression"
---

# Playwright Browser Automation Expert

Проверяй наблюдаемое поведение приложения. Используй уже подключённый browser tool или проектный Playwright; наличие skill не означает, что browser runtime, MCP или browser binaries установлены. Объясняй findings на русском языке.

## Выбор режима

| Режим | Когда нужен | Что подтверждает |
| --- | --- | --- |
| Browser/MCP inspection | Исследование UI и воспроизведение неизвестной проблемы | Конкретную наблюдавшуюся сессию |
| Playwright Test | Повторяемая regression/E2E/visual проверка | Assertions при зафиксированных data и runtime |
| Scripted screenshots | Быстрая матрица viewport и диагностика | Captures и технические сигналы, требующие просмотра |

MCP accessibility snapshot помогает находить элементы; композицию, clipping, spacing и canvas проверяй screenshots. Не превращай успешный MCP click в доказательство всего business flow. Надёжный regression сохраняй как тест в проекте, если дефект и стоимость оправдывают это.

## Рабочий процесс

1. Определи проект, exact URL/build, сценарии, test data, браузеры и ограничения. Изучи `package.json`, existing config/tests, installed Playwright и available tools. Работай с принадлежащим пользователю local/staging target и разрешёнными действиями. Submission с email/payment/production effect требует соответствующей авторизации, если её нет в задаче.
2. Запусти существующий dev/start script с loopback binding и свободным port; дождись HTTP readiness и нужного UI state. Не переиспользуй неизвестный слушающий процесс. Запоминай созданный process/session и заверши только его, если он больше не нужен.
3. До navigation подключи pageerror/console/response listeners. Уместные ожидаемые HTTP failures отличай от дефекта; redaction учитывает URL query, request bodies, headers, traces, screenshots и HTML reports.
4. Находи controls через role/accessible name/label/test id. `locator` и web-first assertions ждут состояние; не добавляй fixed sleep, force clicks и увеличенные retries как лечение гонки. Response ожидание регистрируй до действия.
5. Проверяй результат: URL/heading, видимые state, server response, сохранённые данные и отсутствие ошибок. Screenshot capture — не visual assertion, а visual assertion без approved baseline — не доказательство качества.
6. После исправления повтори воспроизведение и ближайший зависимый flow. Сохрани результат с browser/version, viewport, commit/build, test data и ссылками на очищенные artifacts. Не утверждай проверку неиспользованного browser.

## References по необходимости

- [Deterministic E2E recipes](references/e2e-recipes.md): config, формы, navigation, API/error assertions, browser projects.
- [Screenshots and responsive tests](references/screenshots-responsive.md): baseline discipline и матрица состояний.
- [MCP and authentication](references/mcp-auth.md): официальный Microsoft server, isolated sessions, auth state и границы доступа.
- [Official sources](references/sources.md): актуальные документы, проверены **2026-10-10**.

Для local smoke доступен [inspect-local.cjs](scripts/inspect-local.cjs). Он принимает project root, loopback URL, output directory и optional browser name (`chromium`, `firefox`, `webkit`, установленный `msedge`); использует **уже установленный** project Playwright и fresh contexts, создаёт три screenshots и JSON counts/overflow report. Его вызов описан в screenshot reference. Он не заменяет navigation/forms tests и визуальный просмотр.

## Проверяемые состояния

Покрывай критичный happy path, invalid input, server failure и empty/loading; auth — allowed/denied/logout, когда он менялся. Responsive: mobile touch, tablet, desktop, viewport возле изменённого breakpoint. Темы и reduced motion включай, если UI их поддерживает. Chromium/Firefox/WebKit нужны по browser support проекта; WebKit emulation не равна испытанию на физическом iPhone. Для WCAG, performance и широкого website audit подключай существующие `accessibility`, `performance`, `core-web-vitals`, `web-quality-audit` по цели, без дублирования.

Если tool недоступен, сначала используй имеющийся поддерживаемый browser runtime. Не устанавливай глобальные зависимости и не меняй MCP config как побочный эффект обычного UI теста. Явная задача настройки tools позволяет установку после source/compatibility проверки. При отсутствии runtime сообщи точный непроверенный участок, сохрани reproducible test и завершённые статические проверки.
