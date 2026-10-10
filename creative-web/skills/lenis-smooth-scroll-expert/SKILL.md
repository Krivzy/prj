---
name: lenis-smooth-scroll-expert
description: Evaluate, integrate, debug, or remove Lenis smooth scrolling, including GSAP ScrollTrigger synchronization, one-clock lifecycle, navigation and nested scroll behavior, reduced motion, and mobile fallback. Use only when smooth scrolling serves a concrete UX goal; native scrolling remains the baseline.
---

# Lenis Smooth Scroll Expert

Smooth scrolling — опциональная часть UX. До включения проверь native baseline: зачем нужна inertia/synchronization, где основной scroller, как работают anchors, keyboard и browser history, есть ли modals/nested panels, какие mobile устройства поддерживаются. Если ощущения или измерения хуже, оставь native scroll. Этот skill не включает Lenis во всех будущих проектах автоматически.

Для интеграции и cleanup читай [single-clock lifecycle](references/lifecycle-and-navigation.md). Для документации/версий и типичных сбоев — [sources и acceptance](references/sources-and-validation.md). `gsap-animation-expert` нужен только если проект уже использует ScrollTrigger; Lenis не требует GSAP.

## Инварианты интеграции

1. Изучи текущую библиотеку и lockfile. Используй актуальный пакет `lenis`, не добавляй deprecated `@studio-freight/lenis` рядом. Dependency и CSS подключаются в конкретный проект; глобальная установка npm не даёт корректного project runtime.
2. Один root scroller, один владелец instance, один вызывающий `lenis.raf` clock. Выбирай `autoRaf: true`, собственный RAF или GSAP ticker — только один режим. GSAP ticker передаёт seconds, Lenis raf ожидает milliseconds.
3. Lenis управляет scroll transport; ScrollTrigger — trigger/progress/pin, Motion — UI, R3F — GPU render. Lenis/native scroll не должен одновременно конкурировать со ScrollSmoother, locomotive scroll, CSS smooth behavior или другой programmatic scroll animation.
4. Обычная интеграция Lenis/native page scroll использует scroll event → ScrollTrigger.update и общий clock. Не добавляй `scrollerProxy` без фактического нестандартного getter/container. `update` и `refresh` имеют разную роль: refresh после layout settlement, не на каждом scroll event.
5. Удаляй принадлежащие instance callbacks/ticker/RAF/listeners при unmount или пересоздании. `destroy()` убирает внутренние события, но не удаляет внешнюю GSAP ticker функцию или собственный RAF. Сохраняй references этих callbacks для удаления.

## UX, навигация и overlays

Подключи recommended stylesheet и проверь вычисленные overflow/height/styles, а не копируй большой CSS workaround. Native page должна прокручиваться без Lenis; `overflow:hidden` на root и transform всей страницы не являются универсальной интеграцией.

Anchor links требуют явной настройки текущего Lenis (`anchors`) или согласованного route owner. Focus и history остаются обязанностью приложения: scrollTo сам не реализует accessible navigation. После link/skip link пользователь должен получить нужный location и focus, back/forward должен восстановить позицию согласно router policy. Учти sticky header через scroll-padding/scroll-margin или measured scrollTo offset; не скрывай heading за header.

Для dialog согласуй scroll lock с его primitive. Stop Lenis не равнозначен полному native body lock на всех input modes. Content panel должен прокручиваться, background — соответствовать dialog contract. На close возобновляй scroll ровно однажды; несколько overlays требуют owner/ref-count model, чтобы один close не разблокировал другой. Проверяй cleanup и route change при открытом dialog.

Nested scrollables, textarea, code panes, maps и iframe должны сохранять свой input. Используй targeted `data-lenis-prevent`/prevent callback или документированное allowNestedScroll по измеренной необходимости; обход DOM на каждом событии может иметь стоимость. Не перехватывай wheel/touch всего документа вручную. Для horizontal widgets сохраняй вертикальную прокрутку и keyboard alternative.

## Mobile и reduced motion

Сохраняй native touch baseline; не включай syncTouch ради одинакового desktop feel без проверки реального iOS/Android. Уменьшай inertia на mobile или обходи Lenis в coarse-pointer режиме при доказанной необходимости. Не выбирай качество по userAgent без измерений. Browser low-power/refresh-rate ограничения нельзя исправить увеличением ticker rate.

Reduced motion должен менять поведение live. В новых версиях Lenis имеет respectReducedMotion; для старого lockfile не приписывай отсутствующую option. Можно полностью destroy instance и перейти на native при reduce, сохранив ScrollTrigger native updates. Собственные parallax/pin/camera effects регулируются независимо: выключение smooth transport не отключает GSAP/3D animation. Не блокируй пользовательский доступ до окончания easing; обычный scroll не должен быть locked.

## Проверка и решение о сохранении

Сравни native и Lenis на одном сценарии: wheel/trackpad, touch fling, Home/End/PageUp/PageDown/Space, tab-to-offscreen field, selection, anchor/hash/direct URL, skip link, back/forward, modal, nested panel, image/font layout change, resize/orientation и live reduce. Измерь long tasks, scroll jank, idle CPU и количество clocks после нескольких route transitions; aim 60 FPS измеряется, не обещается.

Если дефект исчезает без Lenis, сначала уменьшай configuration и проверь один clock, CSS и ownership. Не маскируй проблему постоянным preventDefault или отключением доступности. Завершённая интеграция включает native/reduced fallback и доказательство cleanup. Укажи ограничения browser/input device и способ выключения feature flag/provider; native rollback должен быть простым.
