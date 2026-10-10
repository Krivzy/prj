---
name: nextjs-fullstack-architect
description: "Проектирует и реализует Next.js App Router приложения на React и TypeScript: границы server/client, защищённые данные и mutations, формы, RBAC, realtime, таблицы и графики. Применяй при архитектурной или fullstack работе с Next.js; визуальную концепцию и browser QA поручай профильным навыкам."
metadata:
  short-description: "Next.js архитектура, данные и защищённые mutations"
---

# Next.js Fullstack Architect

Создавай поддерживаемое приложение, в котором маршрутизация, бизнес-правила, доступ к данным и UI имеют понятные границы. Объясняй решения на русском языке. Сохраняй выбранный пользователем стек и работающие соглашения проекта.

## Начало работы

- Изучи `package.json`, lockfile, Node/package-manager versions, `next.config.*`, `tsconfig.json`, `components.json`, маршруты, DAL, auth provider, deployment target и тесты. Используй существующий package manager; не создавай второй lockfile.
- Прочитай установленную документацию Next.js, если она есть в `node_modules/next/dist/docs`; сверяй внешние официальные документы с фактической major/minor версией. Проверка источников этого skill: **2026-10-10**, а не гарантия будущей совместимости.
- Для нового проекта выбирай текущие stable версии, проверив package engines, peer dependencies и runtime deployment. Обновление рабочего проекта делай только в рамках задачи. Не переноси автоматически настройки Next 16, Tailwind 4, TanStack Table 9 в проект предыдущей версии.
- Зафиксируй маршруты и пользовательские роли, источник каждого набора данных, требования свежести, critical flows и способ проверки. Для сложного UI согласуй реализацию с `ui-ux-design-system-expert` или существующим `ui-ux-pro-max`; для shadcn прочитай существующий `shadcn`.

## Распределение ответственности

| Задача | Где выполнять |
| --- | --- |
| Initial data, secrets, DB, privileged integration | Server Component или `server-only` DAL |
| State, events, browser APIs, chart interaction, socket connection | Узкий Client Component |
| Mutation из собственного UI | Server Action с проверкой входа и прав в DAL |
| Public API, webhook, external client, streaming endpoint | Route Handler с явным контрактом и auth |
| Long-lived WebSocket broker | Поддерживаемый host/service; не предполагай upgrade support у serverless |

`'use client'` помечает границу импортов: перенос верхнего layout в client может втянуть в browser весь subtree. Держи providers и interactive islands глубоко. Передавай минимальные serializable DTO. Client Components могут prerender на server: доступ к `window` во время render всё ещё опасен. Компоненты server UI можно передавать через `children` клиентской оболочке, не импортируя DAL в неё.

## Рабочие правила

1. Держи `app/` тонким: routing, layout, metadata, loading/error/not-found. Feature-модули содержат domain types, schemas, use cases, DAL и UI; общие primitives — в `components/ui`. Не строй repository/service слои без реальной потребности.
2. При чтении и mutation проверяй session, role/capability, tenant и ownership рядом с data source. Hidden button, layout redirect, Proxy/Middleware и action ID не заменяют server authorization. Все client arguments и route params недоверенные.
3. Определи модель cache проекта до её изменения: Cache Components и предыдущая модель отличаются. Изоляцию пользовательских/tenant данных проверяй отдельно; invalidation выполняй после успешной транзакции. Не добавляй cache в auth только ради ускорения.
4. Формы получают server validation и устойчивые idle/pending/success/error состояния. Client validation улучшает feedback, но не является границей безопасности. Для `useActionState` учитывай предыдущий state первым аргументом; возвращай публичные структурированные ошибки.
5. У таблиц определяй server/client sorting, filtering и pagination исходя из объёма данных. Вычисления клиента над одной server page не дают сортировку всего dataset. У графиков определяй units, timezone, aggregation, loading/empty/error и фиксируемую геометрию.
6. Realtime используй при требовании свежести: polling/SSE/WebSocket выбирай по transport/host и потокам данных. Cleanup, reconnect, resync после пропуска, deduplication и auth expiry — часть реализации.

## Загрузка подробностей по задаче

- [Architecture and compatibility](references/architecture.md): структура, server/client, API, версии, Tailwind и shadcn.
- [Data and security](references/data-security.md): DAL, RBAC, forms/actions, кеш и безопасные интеграции.
- [Interactive data UI](references/interactive-data-ui.md): realtime, таблицы и графики.
- [Official sources](references/sources.md): ссылки и область подтверждения. Открывай только нужные документы.

## Проверка результата

Запусти имеющиеся lint, typecheck, domain/integration tests и production build. Изменённый auth проверяй сценариями unauthenticated, allowed, denied и cross-tenant/ownership, включая прямой вызов API/mutation. После mutation проверь свежие данные в текущем и другом относящемся маршруте. В browser проверь hydration, navigation, pending/error/empty состояния, back/forward, mobile и ошибки console/network через `playwright-browser-automation-expert`. Изменённые несколько TSX компонентов дополнительно направляй в существующий `vercel:react-best-practices`, когда он доступен. Для performance, WCAG или комплексного аудита используй существующие `performance`, `core-web-vitals`, `accessibility`, `web-quality-audit`; не дублируй их аудит здесь.

Результат: краткое архитектурное решение, изменённые файлы, реально выполненные проверки, оставшиеся ограничения и восстановление. Успешная сборка сама по себе не подтверждает работоспособность приложения.
