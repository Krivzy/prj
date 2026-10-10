# Compatibility, sources и проверка

Дата проверки: **2026-10-10**. npm registry snapshot: `motion 14.1.0`, peers React/React DOM `^18.0.0 || ^19.0.0`; официальная installation guide требует React **18.2+**. Применяй более точное ограничение документации и текущие project peers. [Registry](https://registry.npmjs.org/motion/latest), [installation](https://motion.dev/docs/react-installation).

Новый пакет импортируется из `motion/react`. В официальных отдельных guides встречаются старые snippets `framer-motion`/`useViewportScroll`; не смешивай их с новым dependency. Для current scroll используй [useScroll](https://motion.dev/docs/react-use-scroll). Не устанавливай обе библиотеки как workaround imports. Existing framer-motion app можно оставить на совместимом import до отдельной миграции.

## Router и SSR checks

`motion/react-client` может быть полезен для документированного server-component import без hooks, но runtime hooks и state interactions должны жить в client component. Проверь Next/React boundary по фактическому проекту. Reduced motion preference может быть неизвестна на server render; не делай разные семантические trees на основании window во время render. Static content видим на первом paint, особенно при failure JS/hydration.

Проверка route transition начинается с реального router: outgoing node остаётся до exit? Что происходит с pending navigation, suspense fallback, back/forward, query change? AnimatePresence не перехватывает browser или framework lifecycle магически. Если надёжная exit boundary недоступна, предпочти короткий entrance нового layout и правильное focus/history restoration вместо unsupported hacks. Shared-element переход требует identity источника/назначения и состояния отмены.

## Bundle и измерения

[LazyMotion](https://motion.dev/docs/react-lazy-motion) и [bundle guide](https://motion.dev/docs/react-reduce-bundle-size) загружай только для optimization mode. Выбирай domAnimation/domMax по необходимым features, а не только по размеру. При использовании m lightweight components не импортируй full motion в тот же critical path без причины. Проверяй фактический bundle analyzer и production output: документационные размерные ориентиры зависят от bundler и версии.

Interaction budget определяет продукт. Для 60 Hz весь frame ~16.7 ms; spring API не доказывает выполнения бюджета. Профилируй large list/layout измерения, transformed ancestors, opacity/filter paint и React commit churn. При dragging проверяй реальные touch device и scroll coexistence; viewport emulation недостаточен для feel.

## Acceptance scenarios

| Сценарий | Что подтвердить |
|---|---|
| Быстрый open-close-open | Актуальное состояние, нет ghost backdrop/двойного exit |
| Удаление focused row | Focus переходит к осмысленному control; retained exit node недоступен |
| Sorting/filtering | Стабильные IDs, содержимое не «переезжает» к чужой entity |
| Form error/success | Message читается, label/value сохраняются, focus не прыгает из-за motion |
| Live reduced-motion | Большой transform/layout travel прекращается, собственные loops выключаются |
| Touch drag | Page scroll остаётся usable, есть equivalent non-drag действие |
| Несколько menus/tab bars | Shared layout IDs не соединяют несвязанные экземпляры |
| Route interruption/history | Navigation/focus корректны; transition не удерживает старый page tree |

Screenshot с animations disabled проверяет settled appearance. Для choreography отдельно просматривай реальные вход/выход и прерывания; deterministic visual tests используют controlled state/time и одинаковые fonts/data, без фиксации случайного промежуточного frame. Keyboard tests и screen reader review остаются частью приёмки значимых menus/forms.

Источники дополнительных решений: [AnimatePresence](https://motion.dev/docs/react-animate-presence), [layout](https://motion.dev/docs/react-layout-animations), [drag](https://motion.dev/docs/react-drag), [accessibility](https://motion.dev/docs/react-accessibility), [useAnimate](https://motion.dev/docs/react-use-animate). Snippets сверены по документации; browser execution проверяется в конкретном проекте и не утверждается этим skill installation.
