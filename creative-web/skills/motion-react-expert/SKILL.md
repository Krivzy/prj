---
name: motion-react-expert
description: Implement or review Motion for React state animations, AnimatePresence exits, layout and shared-element transitions, springs, gestures, drag, forms, lists, tables, and page transition boundaries. Use for declarative React interaction motion; complex imperative scroll choreography belongs to GSAP.
---

# Motion for React Expert

Выбирай Motion для анимации состояний React: вход/выход, изменение layout, interactive feedback и gestures. CSS достаточно для простого hover/focus; GSAP уместен для сложного storyboard/pin. Не устанавливай оба инструмента ради одной микроанимации. Сначала зафиксируй состояние, event, owner DOM/property, interrupted behavior и reduce variant.

Для реальных patterns и ловушек прочитай [React patterns](references/react-patterns.md); для актуальных версий, источников и acceptance — [compatibility и validation](references/compatibility-and-validation.md). Работай в архитектуре проекта, не создавай сайт при настройке skill.

## Владение и API

Изучи existing imports (`motion`/`framer-motion`), lockfile и React major. Новый код текущего пакета обычно импортирует из `motion/react`; migration существующего пакета — отдельное изменение, а не обязательный побочный эффект. Установка зависимостей локальна проекту и соответствует его package manager. Hooks/state/events требуют client boundary в Next.js, но это не повод превращать всю страницу в client component.

Motion владеет transform/layout/opacity своего DOM node; GSAP или CSS transitions не должны одновременно анимировать те же properties. Для комбинированного эффекта раздели wrappers и зафиксируй ownership. Даже отдельные nodes могут конфликтовать: GSAP transform ancestor влияет на Motion layout measurements. Canvas/GPU uniforms остаются во владении 3D controller, scroll transport — в Lenis/native scroll.

`AnimatePresence` должен оставаться mounted, когда conditional child удаляется; direct animated children имеют стабильные keys. Для `mode='wait'` учитывай ограничение одного child и задержку доступа к следующему состоянию. Не задерживай функциональную навигацию ради длинной transition; framework должен действительно сохранять outgoing tree, иначе exit не запустится.

`layout` анимирует измеренную геометрию после React layout change; конечный layout меняй className/style, а не отдельным imperative DOM mutation. Shared `layoutId` стабилен и уникален в нужной группе; используй `LayoutGroup` namespace для нескольких независимых экземпляров. В scrolling/fixed контейнерах учитывай `layoutScroll`/`layoutRoot`; текст/радиусы могут требовать child layout/position animation вместо сильного scale.

## Практическая композиция

- **Springs:** используй один согласованный motion token набор. Physics spring задаётся stiffness/damping/mass, time-based preset — duration/bounce; не смешивай параметры без понимания precedence. Большой overshoot/elastic motion не должен ухудшать tracking фокуса и чтение.
- **Gestures:** native button/link сохраняет keyboard behavior. `whileHover` — дополнение, а не единственный канал информации; focus-visible и pressed/selected состояние читаются без движения. Touch drag ограничивай axis и локальным touch-action, сохраняя page scroll. Для drag-only действий добавь кнопки/keyboard alternative.
- **Cards/menus:** motion следует состоянию открытия/выбора. Focus management, Escape, outside click, inert/background и ARIA обеспечивает корректный menu/dialog primitive; AnimatePresence их не реализует. На время exit удалённая панель не должна сохранять опасные focusable controls.
- **Forms:** анимируй появление error/success без скачка label/input. Error остаётся текстом с aria-describedby, общий summary/focus уместен после submit. Motion не выполняет валидацию, не дублируй live announcements на каждом frame.
- **Lists/tables:** stable entity IDs вместо index keys. Ограничь animated rows видимой частью; virtualization и sorting не должны разрушать смысловую структуру. Native table сохраняет table/tr/td; test transforms/sticky/header alignment, при проблемах анимируй cell content, а не заменяй table дивами.
- **Page transitions:** сначала проверь router boundary, suspense/loading и history/focus restoration. AnimatePresence поверх router без сохраняющегося outgoing tree не является готовой интеграцией. Не клонируй приватный router context или не держи скрытую старую страницу бесконечно.

## Производительность и reduced-motion

Высокочастотные pointer/scroll значения держи в MotionValue; не переносите каждый update в React state. `useScroll`/`useTransform` подходят для простой progress-индикации; не добавляй ScrollTrigger ради progress bar. Измеряй стоимость layout animation при большом DOM. Рассмотри LazyMotion/m при измеренной bundle потребности и подходящем feature set; полные motion components внутри strict LazyMotion могут перечеркнуть экономию.

На согласованном application boundary установи `MotionConfig reducedMotion='user'`; `useReducedMotion` нужен для custom parallax, autoplay, canvas/video и drag/inertia decisions. Автоматическая политика отключает transforms/layout, но не все opacity/color effects: длинные fades/flash тоже оценивай. Reduce variant сохраняет понятное состояние, без hero travel и обязательной задержки.

Задай проектный бюджет активных элементов и latency взаимодействия; при 60 Hz весь frame около 16.7 ms. Не обещай 60 FPS по наличию spring/transform API. Останавливай внеэкранные loops и проверяй low-end/touch scenario. Сначала лёгкое уменьшение displacement/overlap/числа элементов, затем более сложная оптимизация.

## Критерий завершения

Проверь быстрый open/close/submit/remove/reorder, route interruptions, stable keys, focus после удаления, keyboard-only и touch, live reduce preference, loading/error/empty и container resize. После unmount остановлены custom timers/listeners/subscriptions; declarative presence/layout state не растёт между transitions. Сними screenshot в settled и промежуточном состояниях; screenshot с animations disabled не проверяет choreography. Запусти подходящие typecheck/build/E2E по реальному UI flow, укажи browser и unresolved router limitations.
