# Realtime, tables and charts

## Выбор transport

| Потребность | Разумный исходный вариант |
| --- | --- |
| Данные меняются редко, delay допустим | Fetch + invalidation или polling |
| Server pushes updates в одну сторону | SSE, если host поддерживает нужный stream lifecycle |
| Двустороннее взаимодействие, collaborative state | WebSocket service с явным session protocol |

Это архитектурные критерии, не утверждение о поддержке конкретным host. Проверь лимиты выбранного deployment: duration, concurrent connections, idle timeout, proxy buffering, reconnect и billing. Next Route Handler сам по себе не доказывает WebSocket upgrade capability. Для Vercel/API инфраструктуры подключай доступные профильные backend/deployment skills по необходимости.

Client connection создаётся в effect и закрывается cleanup; обработчики удаляются. Reconnect с capped exponential backoff и jitter, остановка при auth expiry и unmount. Сообщение validate и включает resource id/version/event id; дедуплицируй повтор. Initial snapshot + version/cursor позволяют после reconnect восстановить пропущенное. Не применяй late event после смены tenant/resource. Optimistic mutation reconciliation нужна, если одно обновление приходит и из response, и из realtime.

Безопасность server stream: authorization на subscribe и publish, resource/tenant scope, revocation или token expiry, размер/rate limits и schema. Никаких administrator channel names или full DB rows клиенту. Browser не хранит privileged service key. Проверяй lost connection, resume tab, network offline, duplicated/out-of-order message и logout.

## Tables

Определи local dataset или server paginated dataset до выбора sorting/filtering. URL может хранить стабильные filters/page/sort; schema нормализует значения. Для больших объёмов database/server query делает работу над всем dataset, client хранит UI state. Pagination сбрасывается при изменении filter; persisted selection использует стабильные IDs, а не row index. Virtualization применяй только при измеримой потребности и сохрани keyboard/screen-reader поведение.

shadcn Data Table — guide с TanStack Table, а не универсальная обязательная datagrid. Документация, проверенная 2026-10-10, использует v9 feature-based API; существующие v8 imports не смешивай с v9 example. Browser UI колонок остаётся client-side, fetching/scoping — server-side. [Data Table](https://ui.shadcn.com/docs/components/data-table)

Проверь: стабильный сортируемый key, unique row id, empty/loading/error, числовую и date sorting, select-all semantics across pages, permission-aware actions с server enforcement, длинные значения и narrow viewport. Не превращай мобильную таблицу в body overflow; используй локальный scroll container с обозначением или действительно пригодное альтернативное представление.

## Charts

Зафиксируй единицы, aggregation, timezone, gaps/null policy, zero baseline при необходимости и источник legend. Chart запрашивает только нужные aggregated data. Повторные live updates батчируй, ограничивай history и не пересоздавай всю series без необходимости. Tooltip не должен быть единственным способом получить точные значения; текстовый summary/таблица доступны отдельно.

shadcn Chart использует Recharts; container требует осмысленной минимальной высоты для responsive rendering. Следуй chart API установленной версии и theme tokens. Не добавляй tooltip import из другой primitive family. [Chart](https://ui.shadcn.com/docs/components/chart)

Проверь zero/negative/missing/large values, local date boundaries, narrow width, theme switch, resize, legend wrapping, reduced motion и обновление dataset. Дизайн графиков координируй с design-system skill; содержательную WCAG проверку направляй в существующий `accessibility`.
