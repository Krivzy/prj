---
name: threejs-r3f-expert
description: Build, integrate, debug, or optimize Three.js and React Three Fiber scenes, Drei helpers, models, lighting, cameras, particles, scroll-driven 3D, and adaptive GPU quality. Use for actual 3D/WebGL work; select CSS, SVG, or an image when they deliver the same experience more simply.
---

# Three.js / React Three Fiber Expert

Добавляй 3D, когда взаимодействие, пространственное объяснение или характер продукта оправдывает его стоимость. Сначала опиши, что посетитель поймёт или сделает благодаря сцене, какой будет poster/static replacement и сколько загрузки/CPU/GPU допустимо. Сохранение основного текста и CTA не должно зависеть от GL context или загрузки модели.

Для lifecycle, assets и примеров читай [scene architecture](references/scene-architecture.md). Для budgets и диагностической лестницы — [GPU quality и acceptance](references/performance-and-sources.md). Детали custom vertex/fragment программы передавай `glsl-shaders-expert` только при необходимости; не загружай его для стандартных материалов.

## Совместимость и архитектура

Изучи framework, React major, lockfile и установленные `three`, `@react-three/fiber`, `@react-three/drei`. Проверяй peerDependencies выбранных releases: R3F/Drei major связан с React major. Не устанавливай библиотеки глобально и не обходи несовместимость `--force`/`--legacy-peer-deps`. В Next.js canvas island с hooks — client component; route shell, текст и данные могут остаться server rendered.

Разделяй SceneRoot (Canvas/renderer/errors), SceneContent (lights/cameras/assets), объектные компоненты и controller прогресса. Один владелец каждого GPU resource и один render loop на root. R3F `useFrame`/`useThree` работают только под Canvas. React state хранит редкие смысловые изменения, refs/uniforms — частые значения; не вызывай setState на каждый frame/pointer move.

GSAP может владеть storyboard progress, Motion — HTML overlays, Lenis — необязательным scroll transport. R3F читает общий progress ref и пишет финальную camera/object pose. Не добавляй отдельный `requestAnimationFrame` или `gl.render` параллельно Fiber loop; при explicit renderPriority takeover явно реализуй все passes. Не включай Drei ScrollControls вместе с page-level scroll controller без продуманной модели scroller.

## Создание сцены

1. Начни с камеры, silhouette и света на простых primitives. Проверь композицию на portrait/landscape и desktop до импорта тяжёлого GLB. Фиксируй модель координат: units, origin, camera near/far, object bounds.
2. Подбирай material по задаче: unlit/basic для graphic look, standard/physical для освещения. Не лечи неверный texture color space/lighting случайной exposure или гигантским bloom. Видимые color textures и data maps требуют разного color-space обращения.
3. Для камеры выбирай controls ровно по UX: OrbitControls — осмотр продукта, pointer damping — небольшая реакция, scroll storyboard — контролируемая траектория. Scroll camera и OrbitControls не должны одновременно писать position/target. Поддержи явные controls/reset и keyboard alternative при meaningful interaction.
4. Загружай GLTF/GLB по необходимости через loader/Suspense и ErrorBoundary. Проверяй реальный размер assets, texture dimensions, decoders/CORS, материалы, animation clips. Preload используй осознанно, а не для всего каталога. Cached asset нельзя произвольно dispose/mutate, если он используется другими сценами.
5. Частицы делай Points/instancing с typed buffer attributes и предвычисленными seeds. Большое число одинаковых meshes требует instancing/shared geometry/material, а не тысячи React components с независимыми materials. GPU updates — uniforms/attributes; новые vectors/materials каждый frame создают churn.
6. Постобработку добавляй после сравнения с исходным render. Каждый pass имеет стоимость full-screen fill/render targets; эффект не должен менять читаемость DOM или скрывать форму модели. Выбери composer, совместимый с текущей three/fiber версией, и проверь output color transform ровно один раз.

## Адаптация и доступность

Не подбирай quality только по ширине экрана или userAgent: мобильный high-DPR может оказаться тяжелее desktop. Начни с ограниченного DPR и лёгких shadows/effects; расширяй качество только при подтверждённом запасе. Portrait framing делай через bounds/camera, а не растяжение canvas. Container получает явную height/aspect-ratio, чтобы loading не создавал CLS.

Reduced-motion отключает camera travel, parallax, autoplay rotation и реакцию на курсор; смысловые действия остаются доступны. Decorative canvas помечай как декоративный и не перехватывай pointer/scroll поверх links. Meaningful сцена получает DOM описание, управления с labels и keyboard equivalent. Touch drag не должен отнимать вертикальную прокрутку страницы без причины.

Если WebGL2 недоступен, creation failed, asset failed или произошёл context loss, покажи статическое изображение/DOM representation с тем же размером. Это разные сбои: Canvas fallback на отсутствие GL не заменяет ErrorBoundary и обработку загрузки. Повторный запуск ограничивай, не создавай бесконечную цепочку context recovery.

## Проверка завершения

Проверяй реальные shader/asset/console errors, loading/failed/no-WebGL modes, late resize, mobile orientation, DPR, touch, focus, reduced motion и route teardown. В production build измерь baseline → сцена → эффекты, CPU frame time, draw calls, triangles/points и texture counts. После нескольких входов/выходов GPU resources должны стабилизироваться с учётом кеша, а loop/listeners перестать расти. Допускай 60 FPS только как измеренную цель на подходящем устройстве; сообщай hardware и quality preset. Длинный page load нельзя объяснять одной красивой screenshot.
