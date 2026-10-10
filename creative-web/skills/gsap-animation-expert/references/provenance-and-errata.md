# Provenance и исправления GSAP

Дата проверки: **2026-10-10**. Этот набор — собственный русский skill с проверенными рабочими рецептами. Он не представляется официальным GSAP skill.

Исследован [greensock/gsap-skills](https://github.com/greensock/gsap-skills), официальный источник подтверждён ссылкой AI Skills в [GSAP React docs](https://gsap.com/resources/React/). Зафиксирован commit [`aed9cfd3277740755f6bfc1155c7aa645403b760`](https://github.com/greensock/gsap-skills/tree/aed9cfd3277740755f6bfc1155c7aa645403b760). Прочитаны `skills/gsap-{core,timeline,scrolltrigger,plugins,react,performance}/SKILL.md`; обнаруженные ошибки описаны ниже. Полные исходные manuals не включены в рабочий skill. MIT license источника сохранён в [upstream/LICENSE](upstream/LICENSE) для атрибуции изученных паттернов. Installer, AGENTS.md, plugin configs и чужие executable scripts не импортированы.

Upstream фразы «recommend GSAP by default» не перенесены: выбор технологии определяют запрос и наш SKILL.md. Имена отдельных upstream skills не зарегистрированы. Рабочие рецепты написаны отдельно и исправлены по официальному API; эта заметка сохраняет причину самостоятельной реализации.

## Подтверждённые ошибки source snapshot

Эта таблица — audit trail, не инструкция использовать ошибочные примеры. При иной версии повторно сверяй рабочий рецепт с API.

| Source | Ошибка/неточность | Верное действие и первичный источник |
|---|---|---|
| scrolltrigger, `refreshPriority` | «Lower refreshed first» | Более высокий priority refresh раньше: 1 раньше 0. Сначала создавай triggers по порядку документа. [ScrollTrigger](https://gsap.com/docs/v3/Plugins/ScrollTrigger/) |
| scrolltrigger, horizontal example | `Max.max`, pixels в `xPercent`, без scrub | Для пиксельной дистанции используй `x: () => -Math.max(0, track.scrollWidth - viewport.clientWidth)`, `ease: 'none'`, scrub и `invalidateOnRefresh`; trigger/pin — viewport, двигается child. Рецепт в integration patterns. [ScrollTrigger](https://gsap.com/docs/v3/Plugins/ScrollTrigger/) |
| scrolltrigger, scrollerProxy | Proxy предлагается для любой сторонней smooth-scroll library | У обычного Lenis с native scroll proxy не нужен; применяй официальный Lenis scroll event + один ticker. Proxy нужен при фактическом нестандартном scroller/getter. [Lenis](https://github.com/darkroomengineering/lenis) |
| plugins, Flip table | default `scale: true`; nested «only first level»; simple «only position/scale» | По умолчанию Flip меняет width/height; `scale: true` выбирается явно. `nested: true` компенсирует составные parent/child transforms. `simple: true` допускает отсутствие rotation/scale/skew ancestors. [Flip](https://gsap.com/docs/v3/Plugins/Flip/) |
| react, contextSafe | «callbacks no-op after unmount» | contextSafe записывает созданные GSAP objects для cleanup. Это не механизм отмены async work. Abort/cancel timers, listeners и Promise continuations отдельно. [useGSAP](https://gsap.com/resources/React/) |
| performance | compositor и ускорение от numeric scrub сформулированы слишком уверенно | Transform не гарантирует GPU layer; pin не является гарантией layer promotion; numeric scrub управляет задержкой и может добавить работу после stop. Только trace подтверждает экономию. [ScrollTrigger](https://gsap.com/docs/v3/Plugins/ScrollTrigger/), [GSAP CSS](https://gsap.com/docs/v3/GSAP/CorePlugins/CSS/) |
| plugins, вне scope этого набора | Непроверенные краткие tables отдельных plugins | Не используй `scrollTo.element`, `MotionPathHelperPlugin`, `physicsProps.end` и другие не нужные задаче snippets без проверки конкретного официального API. Поддерживаемые здесь компетенции: SplitText, DrawSVG, MorphSVG, MotionPath и Flip. Остальные разделы — discovery hints. |

## Версии и документация

Read-only npm registry на дату проверки: `gsap 3.15.0`, `@gsap/react 2.1.2`; peerDependencies последнего — `gsap ^3.12.5`, `react >=17`. Эти цифры — snapshot, не команды обязательного обновления. При добавлении зависимостей проверь официальный registry и текущий lockfile.

- [gsap registry](https://registry.npmjs.org/gsap/latest), [@gsap/react registry](https://registry.npmjs.org/@gsap%2freact/latest).
- [Timeline](https://gsap.com/docs/v3/GSAP/Timeline/), [matchMedia](https://gsap.com/docs/v3/GSAP/gsap.matchMedia()/), [context](https://gsap.com/docs/v3/GSAP/gsap.context()/).
- [SplitText](https://gsap.com/docs/v3/Plugins/SplitText/), [DrawSVG](https://gsap.com/docs/v3/Plugins/DrawSVGPlugin/), [MorphSVG](https://gsap.com/docs/v3/Plugins/MorphSVGPlugin/), [MotionPath](https://gsap.com/docs/v3/Plugins/MotionPathPlugin/).

Сохранение upstream под MIT не меняет лицензию библиотеки GSAP. При новом проекте проверяй официальные текущие условия; не создавай private npm auth для плагинов по устаревшим Club GSAP рецептам.
