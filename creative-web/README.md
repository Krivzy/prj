# Creative Web Skills for Codex

Модульная система для разработки выразительных, доступных и производительных веб-интерфейсов. Подготовлена и проверена 2026-10-10. Сайт этим пакетом не создаётся. Skills — рабочие инструкции и ресурсы; реальное качество будущего продукта требует проверки самого проекта.

## Состав

13 новых глобальных skills:

| Skill | Назначение |
|---|---|
| awwwards-creative-web-developer | Мастер: concept → architecture → implementation → browser verification |
| creative-frontend-expert | Художественные композиции, hero, CSS/SVG и нестандартные взаимодействия |
| gsap-animation-expert | Timeline, ScrollTrigger, pin, text/SVG, FLIP и React cleanup |
| threejs-r3f-expert | Three/R3F/Drei, сцены, ресурсы, GPU и адаптивное качество |
| motion-react-expert | React presence/layout, gestures, springs и microinteractions |
| ui-ux-design-system-expert | Tokens, typography/color/grid, темы и SaaS/dashboard |
| nextjs-fullstack-architect | App Router, server/client, actions/API, RBAC, realtime и data UI |
| 21st-ui-components-expert | Официальный поиск, оценка и адаптация UI компонентов |
| lenis-smooth-scroll-expert | Оправданный smooth scroll, GSAP sync и lifecycle |
| glsl-shaders-expert | GLSL, procedural/cursor/GPU effects, fallback и profiling |
| responsive-design-expert | Fluid layout/type, container queries, touch и overflow |
| playwright-browser-automation-expert | E2E, forms/navigation/network/errors, screenshots и browser matrix |
| visual-quality-assurance-expert | Screenshots, композиция/spacing/type, defects → repair → recheck |

В `reused-skills/` сохранены существующие `accessibility`, `performance` и `core-web-vitals`, чтобы не создавать дублирующие aliases. Accessibility содержит точечное исправление двух contrast table rows: large text — 18pt / 14pt bold, не 18px / 14px bold. Первичный источник: [W3C SC 1.4.3](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum.html).

Мастер подключает специалистов по необходимости, а не загружает все инструкции. CSS выбирается для лёгких эффектов; Motion — для React state/layout; GSAP — для choreography; WebGL и Lenis применяются при содержательной ценности. Один владелец properties, явный lifecycle, accessible/reduced-motion fallback.

## Установка и восстановление

Требуются Codex с локальными skills, Git, Node и pnpm. Проверенная среда: Windows 10 x64, Codex CLI 0.162.0-alpha.2, Node 24.19.0, pnpm 11.25.0. Это измеренный baseline, а не требование зафиксировать все будущие проекты на этих версиях. Новые сайты выбирают актуальные совместимые stable dependencies по official docs и peerDependencies.

В Windows PowerShell из папки `creative-web`:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\Install-Skills.ps1
```

Installer проверяет SHA-256 и не перезаписывает отличающиеся существующие skills. Установка только skills: глобальные инструкции, MCP, аккаунты и сайты он не меняет. Эта версия Codex фактически обнаруживает `$CODEX_HOME/skills` (обычно `%USERPROFILE%/.codex/skills`). В более новых hosts сверяйте [официальные discovery paths](https://learn.chatgpt.com/docs/build-skills). Не создавайте mirror в `.agents/skills` без проверки: одинаковые names могут дублироваться.

Для Playwright MCP установите pinned tools в отдельную локальную папку профиля; не внутри проекта сайта и не с global install:

```powershell
$taskTools = Join-Path $env:USERPROFILE '.codex\tools\creative-web'
New-Item -ItemType Directory -Path $taskTools -Force | Out-Null
Copy-Item -LiteralPath .\tools\package.json -Destination $taskTools
Copy-Item -LiteralPath .\tools\pnpm-lock.yaml -Destination $taskTools
pnpm --dir $taskTools install --frozen-lockfile --ignore-scripts
$taskNode = (Get-Command node).Source
$taskCli = Join-Path $taskTools 'node_modules\@playwright\mcp\cli.js'
codex mcp add playwright -- $taskNode $taskCli --browser msedge --headless --isolated
```

Перед выполнением убедитесь, что папка tools и MCP name ещё не используются; существующие записи сначала изучайте. `msedge` использует установленный Edge. Для других браузеров выберите поддерживаемый channel/engine и установите browser binaries версии вашего Playwright. Изолированный профиль не сохраняет cookies аккаунтов.

21st: используйте одну HTTP запись из `config/mcp.example.toml`, disabled до настройки. OAuth поддерживается официальной [Codex integration](https://github.com/21st-dev/codex-plugin/blob/main/README.md); API key — альтернативный способ. Вход владельца выполняется `codex -c 'mcp_servers.21st.enabled=true' mcp login 21st`, затем включается сохранённая запись и проверяется реальный tools/list/search. Не храните ключ в TOML/git/CLI args. Endpoint без auth ответил 401; доступ к каталогу пока не подтверждён. Старый Magic MCP не устанавливайте для новой среды.

При использовании существующих shadcn/ui-ux-pro-max/frontend-design сначала проверьте каталог. Эти независимые skills не копируются в пакет: установка поверх уже имеющихся не требуется. При отсутствии используйте официальные/закреплённые sources из `THIRD_PARTY.md`, изучив содержимое перед установкой.

## Проверки

- Все 13 новых entries прошли bundled `skill-creator/scripts/quick_validate.py`; metadata, names, progressive refs и installed bytes проверены.
- Штатный `skills/list` обнаружил все 13 включёнными без load errors. Нового имени `impeccable` нет.
- `@playwright/mcp` 0.0.83 зарегистрирован; actual tools/list — 25 tools. В данном package его официальный internal Playwright dependency имеет version `1.64.0-alpha-1790635538000`. Это pinned dependency выпущенного MCP, а не версия будущего стека сайта.
- MCP Edge smoke: форма, API, anchor navigation, reduced motion и screenshots при 390/768/1440 px.
- QA helper: исправный fixture прошёл; deliberate pageerror/console.error/API404 fixture возвращает exit 1; внешняя начальная URL отклоняется.
- CSS recipes реально проверены в Edge: hover/focus, mask/clip/SVG, View Transition success/reduce/reject, themes и contrast; 13 viewport widths 320–1440 px, container threshold соседние размеры. В процессе устранена rejected transition promise ошибка.
- Independent reasoning forward-tests: SaaS 100k rows/RBAC, portfolio scroll/3D, SPA lifecycle, design/keyboard/mobile. Это проверка решений skill, не runtime тест сайта.

Фактические результаты в `verification/Summary.md`. Полноценный сайт, Firefox/WebKit runtime, физические устройства, GPU shader compilation и обещанный FPS не проверялись. Среда подготовлена к их выполнению в будущих проектах.

## Использование и откат

В Codex: `$awwwards-creative-web-developer`, затем опишите продукт и задачу. Implicit invocation включён. Skills доступны со следующего сообщения; если каталог не обновился, перезапустите Codex. MCP config changes требуют fresh runtime; текущий чат может продолжать использовать старый tool catalogue.

Откат skills: переносите только установленные этим пакетом папки из discovery root в отдельную backup папку. Отдельно удалите только новые MCP записи и tool folder, если они не используются другими задачами. Не заменяйте полный config старой копией поверх последующих изменений. Удаление Impeccable выполнялось отдельно с проверенной локальной резервной копией; этот installer его не удаляет. Credentials и приватные rollback файлы не входят в публикацию.
