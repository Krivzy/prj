---
name: glsl-shaders-expert
description: Design, implement, debug, and optimize custom GLSL vertex and fragment shaders for WebGL/Three.js effects such as procedural noise, distortion, liquid gradients, particles, and pointer-reactive GPU animation. Use when a custom GPU program is necessary; prefer CSS/SVG/static assets for simpler effects and preserve a no-WebGL fallback.
---

# GLSL / Shaders Expert

Начни с визуального результата и бюджета: что shader даёт сверх CSS/SVG/обычного материала, где эффект отображается, как он реагирует на pointer/scroll, каким будет static/reduced version. Обязательный контент и управление остаются в DOM. Красивый background не оправдывает shader, если gradient/image даёт тот же эффект с меньшими расходами.

Для программы, uniforms и noise pipeline читай [verified shader recipes](references/shader-recipes.md). Для profiling, capabilities, fallback и источников — [GPU validation](references/gpu-validation-and-sources.md). Создание Canvas/models/cameras делегируй `threejs-r3f-expert` по необходимости; этот skill владеет GPU program/math, а не всем приложением.

## Контракт программы

Сначала зафиксируй backend и shader dialect. WebGL1/GLSL ES 1.00 и WebGL2/GLSL ES 3.00 отличаются declarations, texture lookup и fragment output. Current Three WebGLRenderer требует WebGL2; это не означает, что каждый ShaderMaterial snippet должен вручную содержать `#version 300 es`: Three имеет собственный preprocessing/glslVersion режим. WebGPU/TSL не исполняет тот же GLSL автоматически.

Для ShaderMaterial используй documented injected built-ins, для RawShaderMaterial явно объявляй нужные attributes/uniforms/precision. Не смешивай GLSL1 `varying`/`gl_FragColor` с raw GLSL3 `in/out` в одном режиме. Не добавляй glslify/vite loader/plugin, пока проектная сборка реально этого не требует; string modules часто достаточно.

Составь таблицу uniforms: name → type → units → coordinate space → owner → update frequency → default. Resolution бывает CSS pixels или drawing-buffer pixels; pointer бывает viewport pixels, NDC или UV — определяй это явно. Time используй в seconds и ограничивай resumed delta, чтобы hidden tab не вызывал скачок. Aspect correction рассчитывай для контейнера, не всегда для window.

## Компетенции и выбор реализации

- **Vertex deformation:** позицию меняет vertex stage; эффект зависит от tessellation. Добавляй только нужные segments, корректируй normals/lighting для значимой деформации. Смещение за исходные bounds влияет на culling/raycasting и shadows; CPU hit geometry может не совпадать с картинкой.
- **Fragment/liquid gradient:** используй плавные field values, noise/domain distortion и палитру, связанную с design tokens. Full-screen blur, много octaves и большой DPR влияют на fill rate. Antialias edges по производной/ограничению частоты, избегай мерцания мелких деталей при движении.
- **Noise/procedural:** сначала простая проверяемая value noise/analytic field; simplex/fBm/Worley выбирай по виду и budget. Лицензию заимствованной implementation сохраняй. Fixed upper-bound loops, небольшой octave count и precomputed/noise texture могут быть дешевле длинной функции; сравни trace.
- **Distortion:** UV displacement ограничивай, определяй wrapping/clamp и aspect. Не запускай произвольный high-amplitude distortion смыслового текста/форм. Для image effect учти texture colorSpace, premultiplied alpha и края.
- **Particles:** сохраняй seeds/attributes в buffers, frame animation — на GPU через time/progress, без React state на каждый particle. Points/instancing сокращает draw calls; прозрачные крупные sprites всё равно создают overdraw. Учитывай максимальный point size, depth write и blending по выбранному виду.
- **Cursor-reactive:** нормализуй pointer по canvas bounding rect, invert y только один раз; use stable uniforms and damping. Touch получает иной meaningful control или static pose. Decorative canvas не должен перехватывать links; pointermove не создаёт новые materials или DOM layout read каждый frame.
- **GPU feedback/simulation:** ping-pong targets нужны только для действительно stateful эффекта. Capabilities/float targets/framebuffer completeness, memory и reset policy проверяй до включения. Не читай render target синхронно каждый frame без причины.

## Ownership и lifecycle

Один render-loop owner: R3F useFrame или vanilla renderer loop. GSAP может менять progress uniform/proxy, Motion — DOM overlay; один uniform/property не получает две competing writers. Uniform object создавай один раз, обновляй `.value`; shader source/defines и material recompilation меняются редко. `material.needsUpdate` не требуется для каждой time update.

В teardown останавливай loop/subscriptions/pointer/resize listeners; dispose только принадлежащие scene materials/geometries/textures/render targets. Shader uniform с texture не освобождает texture автоматически при material.dispose. Не уничтожай shared loader cache. При rebuild сохрани ограниченный resource owner вместо накопления programs.

## Доступность, mobile и завершение

Reduced-motion отключает autoplay time/deformation/camera parallax/cursor following; оставь статичный выразительный кадр или poster. Live preference change должен остановить actual updates, а не только скрыть canvas. Offscreen/hidden scene прекращает бесполезное вычисление. Mobile quality: DPR cap, fewer samples/octaves/particles, меньший target, optional effects off; различай screen width и GPU capability.

Проверяй shader compile/link log, NaN/infinity/zero-division, uniforms, aspect resize, color pipeline, transparency, intermediate frames, keyboard/touch, no-WebGL/context loss и resource cleanup. Сравни CPU/GPU baseline и результат на реальном hardware; 60 FPS — измеряемая цель, не свойство GLSL. Если compilation/capability failed, покажи static replacement той же композиции, сохрани layout/контент и ограничь retry. Сборка TypeScript не подтверждает компиляцию shader на GPU.
