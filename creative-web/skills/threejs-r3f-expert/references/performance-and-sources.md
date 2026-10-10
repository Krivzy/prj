# GPU budgets, деградация и проверка

Дата проверки первичных источников: **2026-10-10**.

Registry snapshot: `three 0.186.1`, `@react-three/fiber 9.8.1`, `@react-three/drei 10.7.9`. Fiber peers включают React/React DOM `>=19 <19.4`; Drei — React `^19`, Fiber `^9`. Это основание проверить совместимость, а не предписание менять React 18 проект. [Fiber introduction](https://r3f.docs.pmnd.rs/getting-started/introduction), [three registry](https://registry.npmjs.org/three/latest), [Fiber registry](https://registry.npmjs.org/@react-three%2ffiber/latest), [Drei registry](https://registry.npmjs.org/@react-three%2fdrei/latest).

## Диагностическая лестница

1. Измерь DOM-only baseline в production build. Затем включи геометрию, потом модели/свет, затем postprocessing по одному. Не оптимизируй «всё сразу» — найди bottleneck.
2. Уменьши DPR. Большое улучшение указывает на fill-rate/fragment/render-target pressure. Число pixels растёт примерно как DPR². Проверяй на реальном mobile high-DPR, а не только desktop viewport emulation.
3. Отключи shadows, transparent layers/transmission и composer passes. Если это помогает, уменьши quality/размер target или количество passes; low-poly сам по себе не решает overdraw.
4. Посмотри draw calls и program/material count. Для repeated objects используй instancing и shared resources. Для высоких triangles — LOD/simplification; для huge textures — меньшие размеры/compression. Контроль размера GLB zip не заменяет оценку decoded GPU memory.
5. Если GPU лёгкий, профилируй CPU: raycasting, allocations, events, React commits, scroll callbacks. Не обновляй весь React tree через setState/useFrame. Ограничь interactive hit surfaces и обработку pointer events.

Рабочие бюджеты должны быть согласованы на сцену: download bytes, texture dimensions/количество, DPR cap, число passes и draw calls, допустимое frame time. Начальные ориентиры для лёгкого promotional hero могут быть DPR 1..1.5, один canvas и 0..1 optional postprocess pass; это engineering hypothesis, не универсальная спецификация. Снижай visual cost пока реальный target device не уложится в проектный бюджет.

## Adaptive quality

[Drei PerformanceMonitor](https://drei.docs.pmnd.rs/performances/performance-monitor) позволяет реагировать на устойчивые средние FPS, а не шум одного кадра. Используй hysteresis, ограниченные quality presets и cooldown. Меняй DPR/effects/particle count постепенно; если качество колеблется, остановись на фиксированном low preset. Demand rendering в idle может исказить интерпретацию «FPS»: монитор должен измерять активную работу, а не штрафовать отсутствие кадров.

Не измеряй FPS в headless CI как доказательство GPU real-world performance. CPU throttling в DevTools не эмулирует конкретный mobile GPU. Время frame около 16.7 ms при 60 Hz относится ко всему rendering pipeline; 120 Hz требует другого бюджета. Запиши browser/device/DPR/preset, duration и p95 frame time/drop pattern, сравни исходное и итоговое. Thermal throttling и low-power mode могут менять результат.

## Fallback matrix

| Состояние | Ожидаемый UX |
|---|---|
| JS/3D chunk ещё не загружен | Poster, reserved dimensions, доступные текст и CTA |
| Нет WebGL2/renderer creation failed | Static replacement; navigation и controls страницы работают |
| GLB/decoder/CORS failed | Error state с ограниченным retry или poster, без бесконечного loader |
| Context lost | Pause/controller cleanup; ограниченное восстановление либо poster |
| Reduced motion | Static pose, без scroll camera travel/autoplay; meaningful control доступен |
| Offscreen/hidden document | Нет бесполезной непрерывной animation, возможна demand/stop модель |

Current Three WebGLRenderer использует WebGL2; WebGL1 больше не поддерживается начиная r163. Поэтому тест `canvas.getContext('webgl')` не доказывает возможность renderer creation. [WebGLRenderer](https://threejs.org/docs/pages/WebGLRenderer.html). Этот факт не требует переключения на WebGPU. ShaderMaterial GLSL и WebGPU Node/TSL не взаимозаменяемы.

## Acceptance evidence

Зафиксируй static + loading + failure screenshots на portrait/landscape; rotation/camera endpoints; отсутствие console asset/shader errors; одинаковый доступ к смыслу/CTA в fallback. Сделай несколько route mount/unmount cycles и сравни `renderer.info.memory`/draw calls, количество roots/listeners и idle CPU с исходным. Cached GPU resources могут не стать нулём, но не должны расти бесконечно. При подозрении на leak различай cache, renderer internals и потерянные owned resources.

Основные источники: [R3F scaling performance](https://r3f.docs.pmnd.rs/advanced/scaling-performance), [pitfalls](https://r3f.docs.pmnd.rs/advanced/pitfalls), [Canvas ownership/fallback](https://r3f.docs.pmnd.rs/api/canvas), [objects/disposal](https://r3f.docs.pmnd.rs/api/objects), [GLTFLoader](https://threejs.org/docs/pages/GLTFLoader.html), [EffectComposer](https://threejs.org/docs/pages/EffectComposer.html), [Material dispose](https://threejs.org/docs/pages/Material.html). Примеры API сверены документально; будущая сцена требует самостоятельной browser/GPU проверки.
