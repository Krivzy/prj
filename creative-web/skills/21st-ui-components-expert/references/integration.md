# Официальная интеграция 21st

Проверено 2026-10-10 по первичным источникам:

- https://github.com/21st-dev/codex-plugin/blob/main/README.md — Codex plugin, OAuth, manual API-key connection.
- https://github.com/21st-dev/magic-mcp/blob/main/README.md — migration старого Magic MCP и reset прежних keys.
- https://21st.dev/mcp — настройка аккаунта/ключей.
- https://learn.chatgpt.com/docs/extend/mcp — Codex HTTP MCP/auth/env_http_headers.

Новый endpoint: https://21st.dev/api/mcp. Официальный Codex plugin поддерживает OAuth; API key остаётся альтернативой. Source plugin README и старый Magic README различаются способом header: plugin документирует bearer_token_env_var, Magic manual — x-api-key. Пользуйся документацией текущего выбранного способа, не применяй оба вслепую.

Для manual OAuth registration используют `codex mcp add 21st --url https://21st.dev/api/mcp`, затем `codex mcp login 21st`. Это интерактивный вход владельца аккаунта. Записывать credential в config не требуется. После входа проверить подключение и tools/list, а не только exit code registration.

Для API key допустим безопасный environment mapping (секрета в TOML нет):

```toml
[mcp_servers.21st]
url = "https://21st.dev/api/mcp"
bearer_token_env_var = "API_KEY_21ST"
```

Переменная должна существовать в environment процесса Codex, а не только случайного дочернего shell. Не передавай ключ в args, URL, пример с literal credentials или git. Закрытое хранение выполняется по действующим правилам пользователя; не печатай значение.

В installation этой suite запись 21st подготовлена disabled до реальной авторизации. После входа включи именно эту запись, проверь fresh runtime и реальный read-only search/get_component. Не добавляй вторую запись или полный plugin поверх неё без выбора одного владельца MCP.

Без ключа endpoint может вернуть HTTP 401, рекламировать OAuth или отказать при tools/list. Это не проверенная доступность каталога. Tools/limits и scopes меняются; перечень всегда получать динамически. Hosted AI generation может требовать отдельного плана и не обязательна для search/retrieval.
