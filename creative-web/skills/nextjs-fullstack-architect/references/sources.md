# Official sources

Проверены **2026-10-10**. Инструкции написаны заново; документы используются для API boundaries и version-sensitive решений. Ссылки `latest` могут обновиться, поэтому при реализации сверяй установленную версию.

| Источник | Для чего читать |
| --- | --- |
| [Next.js installation](https://nextjs.org/docs/app/getting-started/installation) | Node/TypeScript requirements, latest version, supported setup |
| [Server and Client Components](https://nextjs.org/docs/app/getting-started/server-and-client-components) | RSC boundary, composition, client imports |
| [Route Handlers](https://nextjs.org/docs/app/getting-started/route-handlers) | HTTP boundary and routing conventions |
| [Authentication](https://nextjs.org/docs/app/guides/authentication) | Session/DAL/DTO and authorization placement |
| [Data Security](https://nextjs.org/docs/app/guides/data-security) | server-only, callable actions, input/output protection |
| [Mutating Data](https://nextjs.org/docs/app/getting-started/mutating-data) | Mutation lifecycle and framework forms |
| [Caching](https://nextjs.org/docs/app/getting-started/caching) | Cache Components model |
| [Revalidating](https://nextjs.org/docs/app/getting-started/revalidating) | Version-specific invalidation |
| [React useActionState](https://react.dev/reference/react/useActionState) | State/action signature and pending |
| [TypeScript strict](https://www.typescriptlang.org/tsconfig/strict.html) | Strict family of type checks |
| [Tailwind responsive design](https://tailwindcss.com/docs/responsive-design) | Current breakpoint/container-query syntax |
| [shadcn Next.js installation](https://ui.shadcn.com/docs/installation/next) | Project component setup |
| [shadcn Data Table](https://ui.shadcn.com/docs/components/data-table) | Current TanStack family/API |
| [shadcn Chart](https://ui.shadcn.com/docs/components/chart) | Recharts integration and responsive container |

TypeScript `strict` проверяет семейство type-safety rules; upgrade может добавить более строгие проверки. Не выключай его глобально ради одного ошибки integration: уточни тип/контракт и соблюдай существующий migration plan.
