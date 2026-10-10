# Architecture and compatibility

## Практический план модуля

Пример структуры — исходная точка для новой feature, не требование перестроить проект:

```text
src/app/(workspace)/projects/[id]/page.tsx  # route: parse input, fetch DTO, compose UI
src/app/api/projects/[id]/route.ts         # only when external/API contract needed
src/features/projects/schema.ts           # input/output schemas safe for shared import
src/features/projects/domain.ts           # business types and pure calculations
src/features/projects/server/dal.ts        # server-only auth + scoped DB queries
src/features/projects/server/actions.ts    # thin use-server UI mutation adapter
src/features/projects/ui/project-form.tsx  # client island: interactive state
src/components/ui/                        # project-owned shadcn/primitives
```

Зависимости направляются UI → adapter → use case/DAL → database/integration. Server-модули не re-export через общий barrel, импортируемый клиентом. Pure domain code не зависит от routing и React. Ошибки domain имеют stable code; translation в UI/HTTP выполняется на границе.

## Server/client и навигация

Server page получает исходные данные и отдаёт interactive island только необходимый DTO. Props клиента не содержат DB handles, sessions, secret-bearing objects и приватные fields. Provider оборачивает только потребителей. Browser-only библиотека получает client wrapper; при действительно отсутствующей SSR совместимости отключение SSR рассматривай внутри Client Component и оценивай initial placeholder.

Для navigation используй framework Link/router API установленной версии. `loading.tsx` и подходящие Suspense boundaries должны сохранять размер контента. `error.tsx` получает путь retry и безопасное сообщение. Обрабатывай missing record отдельно от infrastructure failure. Не прячь ошибку пустым списком. Avoid waterfall: independent data requests start together, а нужные для access-control зависимости остаются последовательными.

## Version gate перед новым кодом

1. Сравни runtime Node и `engines`, installed `next/react/react-dom/typescript`, lockfile и CI runtime.
2. Проверь major Tailwind и фактический CSS pipeline; v4 CSS-first конфигурация и v3 `tailwind.config.*` не взаимозаменяемы без миграции.
3. Проверь shadcn `components.json`, aliases, styling, icon library и underlying primitive family. У текущей документации есть Base UI/React Aria/Radix варианты; API одного варианта не переносится без проверки в другой.
4. Для auth/database/realtime проверь совместимость выбранного Node/Edge/host runtime. Windows dev success не доказывает Linux production compatibility.
5. При обновлении cache или dynamic routing используй version-specific документацию и migration guide; не включай experimental options по памяти.

В docs, проверенных 2026-10-10, Next.js показывает 16.4.0, minimum Node 20.9 и TypeScript 5.1; App Router использует встроенную React canary реализацию, но `react`/`react-dom` остаются зависимостями проекта. Это справочная отметка для повторной проверки, не pinned install recommendation. [Installation](https://nextjs.org/docs/app/getting-started/installation)

Tailwind styling адаптируется к существующему project theme. Shared tokens используются одинаково в semantic CSS, компонентах и charts; responsive variants относятся к viewport, container variants — к реальному родителю. Не добавляй динамически составленные utility names, которые build scanner не может увидеть. [Responsive design](https://tailwindcss.com/docs/responsive-design)

shadcn CLI генерирует редактируемый project code. Перед `init/add` оцени существующие файлы и diff; выбирай совместимую component family. Не запускай `init` поверх уже настроенного проекта без причины. [Next.js installation](https://ui.shadcn.com/docs/installation/next)

## API contract

Route Handler нужен при HTTP boundary; Server Component обычно обращается к DAL напрямую, без fetch собственного API для того же server процесса. Endpoint описывает method, input schema, auth, scoped query, response DTO/status, timeout, pagination и public error code. Webhook проверяет provider signature по исходному body и повторную доставку; не превращай произвольный URL в server fetch proxy. Обновления используют транзакцию, idempotency или concurrency control, когда повтор может изменить данные дважды.

Не размещай `page.tsx` и `route.ts` для одного URL segment. Route Handlers используют Web Request/Response API, но runtime, cache behaviour и специальные APIs сверяй с версией. [Route Handlers](https://nextjs.org/docs/app/getting-started/route-handlers)
