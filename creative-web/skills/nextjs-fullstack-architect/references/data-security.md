# Data, forms and security

## DAL и authorization

Предпочитай существующую проверенную auth library. DAL проверяет действующую session и централизует access policy. Role достаточна только для роли: resource ownership и tenant scope проверяются отдельно. Query фильтрует данные до чтения и mutation; запрещённые ресурсы не попадают в DTO. Proxy/Middleware дают предварительный redirect, а итоговая проверка остаётся у data source. [Authentication guide](https://nextjs.org/docs/app/guides/authentication)

`import 'server-only'` защищает границу импортов build ошибкой. Экспорт `'use server'` функции создаёт callable mutation surface: вход validate, caller reauthorize, return filter. Секретный модуль на server не делает все его возвращаемые значения безопасными. [Data Security](https://nextjs.org/docs/app/guides/data-security)

Ниже contract recipe, а не готовая auth/db реализация. Все функции project-specific и должны быть связаны с существующими DAL/session/DB:

```ts
// server/dal.ts
import 'server-only';
import { requireActor } from '@/server/auth';
import { projectRepository } from '@/server/projects';

export async function renameProject(projectId: string, name: string) {
  const actor = await requireActor();
  if (!actor.capabilities.includes('project:write')) {
    return { ok: false as const, code: 'FORBIDDEN' as const };
  }
  // Repository updates only a row matching BOTH tenant + accessible ownership.
  // It must enforce the predicate in the write, not only in a prior read.
  const changed = await projectRepository.renameAccessible({
    actorId: actor.id,
    tenantId: actor.tenantId,
    projectId,
    name,
  });
  return changed
    ? { ok: true as const }
    : { ok: false as const, code: 'NOT_FOUND_OR_FORBIDDEN' as const };
}
```

Не доверяй `tenantId`, `actorId`, role, ownership из hidden inputs. Избегай раскрытия существования чужого объекта, когда контракт этого не допускает. У read и write один policy; unit tests проверяют policy, integration tests — SQL/DB scope и прямой unauthorized mutation.

## Forms и Server Actions

Schema принимает длины/диапазоны/формат и неизвестные поля по установленной convention. Существующий validator сохраняй; TypeScript type без runtime parse не валидирует HTTP/FormData. Клиент отправляет только editable fields, сервер берёт session context самостоятельно. Используй transactions для связанного изменения, unique constraints для инвариантов и conflict state для concurrent edits.

`useActionState(action, initialState)` получает `[state, dispatchAction, isPending]`. Подключение `dispatchAction` к `<form action>` предоставляет Action context; ручной вызов требует Transition. Первый аргумент reducerAction — previous state, второй — submitted payload. [React useActionState](https://react.dev/reference/react/useActionState)

Состояния формы:

| Состояние | Обязательное поведение |
| --- | --- |
| invalid | Error рядом с полем, связь `aria-describedby`, сохранение значений |
| pending | Защита от случайного повтора, loading label, стабильная геометрия |
| server conflict | Понятное предложение reload/review, без потери черновика |
| success | Подтверждение результата и актуальные данные |
| unexpected failure | Public message; exception details остаются server-side |

Redirect и framework control-flow exceptions не поглощай общим catch. При upload лимитируй size/type на server; MIME/extension клиента недостаточны. Внешние mutations получают разумный timeout и понятный retry/idempotency policy. [Mutating Data](https://nextjs.org/docs/app/getting-started/mutating-data)

## Caching по фактической модели

Проверь `cacheComponents` и версию. Текущая страница Caching описывает Cache Components; документация предыдущей модели вынесена отдельно. Не меняй feature flag только для повторения примера. Назначь каждому query scope (`public`, `tenant`, `user`), TTL/freshness и события invalidation. Client refresh и invalidation server data cache не считать одним действием. [Caching](https://nextjs.org/docs/app/getting-started/caching)

После успешной mutation invalidation относится только к зависимым resources/routes. Отдельно проверь несколько пользователей, logout/login и cache hit: ни HTML, ни RSC payload, ни CDN response не должны вернуть данные другого пользователя. Для revalidation APIs прочитай документацию своей версии, особенно сигнатуры tag expiration. [Revalidating](https://nextjs.org/docs/app/getting-started/revalidating)

## Integration boundaries

Credentials server-side, публичные `NEXT_PUBLIC_*` переменные считаются опубликованными. URL пользователя не используется для privileged fetch без разрешённых destinations и policy, предотвращающей SSRF. Query parameters/paths validate; DB запросы parameterized. Не вставляй недоверенный HTML без необходимой sanitization. Cookies/session настройки берутся из auth provider, включая Secure/HttpOnly/SameSite; собственный cross-origin API получает CSRF/origin strategy по своему контракту. CSP и security headers вводи совместимо с deployment и UI, не отключай protection ради успешного build.
