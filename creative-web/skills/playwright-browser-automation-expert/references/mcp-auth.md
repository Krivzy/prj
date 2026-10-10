# Microsoft Playwright MCP and authentication

## Подключённый инструмент и Test runner

Сначала проверь имеющиеся MCP/browser capabilities. MCP делает interactive inspection; project `@playwright/test` даёт versioned repeatable assertions. При настройке используй официальный [microsoft/playwright-mcp](https://github.com/microsoft/playwright-mcp), сверяй runtime requirements, package provenance и CLI `--help`. Проверено 2026-10-10.

Документация server показывает пакет `@playwright/mcp`, stdio через `npx` и `--isolated`; isolated browser не сохраняет profile на disk. Persistent profile хранит sessions и используется одной browser instance; concurrent clients требуют отдельных profiles/isolated mode. MCP не является security boundary. [Official README](https://github.com/microsoft/playwright-mcp/blob/main/README.md)

Для Codex настройка делается поддерживаемым `codex mcp` CLI или существующим TOML форматом; не копируй JSON `mcpServers` в TOML. Сначала read existing entry и CLI help, сохрани backup при изменении, добавь только нужный server. На Windows `npx.cmd` или проверенный `node` + package CLI путь может понадобиться из-за shell. Для воспроизводимости закрепляй проверенную package version; `@latest` в документации — bootstrap shortcut, не гарантия неизменности.

Минимальный package launch пример, который нужно адаптировать к текущей Codex registration:

```text
npx.cmd @playwright/mcp@<verified-version> --isolated --headless
```

`<verified-version>` — намеренно заменяемый параметр, это не готовая команда. Не придумывай tool names: получи фактические tools после connection. Проверка: server registration → protocol initialize/tools/list → browser launch → local page snapshot → safe control interaction → screenshot. Успешный `--help` проверяет только запуск CLI; `tools/list` не подтверждает browser interaction.

## Auth state

Storage state может дать возможность действовать от имени пользователя; cookies/localStorage/IndexedDB snapshots, persistent profile, traces и reports не попадают в Git. Test accounts и собственный ephemeral state предпочтительны. Сохраняй только необходимый state, в private path, с expiry/cleanup и минимальным ACL. `.gitignore` не удаляет ранее tracked state. [Playwright authentication](https://playwright.dev/docs/auth)

Использование уже открытого личного browser profile/extension — отдельный выбор пользователя, а не default QA flow. Не просматривай посторонние tabs/accounts. Не сохраняй credentials в MCP args/config, URL query, code или screenshots. При необходимости login используй существующий безопасный способ передачи secrets и изолированную сессию. Навык не даёт разрешения отправлять письма, платить, менять production records или обходить protections.

При зависимости E2E от login setup используй framework fixtures/setup projects, дождись завершения redirects и отдели разрешённые аккаунты по роли. Parallel tests, изменяющие server state, не должны случайно делить один account/resource. После logout проверь API deny и удаление client-visible private state; browser UI redirect сам по себе недостаточен.
