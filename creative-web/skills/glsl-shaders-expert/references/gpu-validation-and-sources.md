# GPU validation, graceful degradation и sources

Дата проверки: **2026-10-10**. Реальный WebGL context обязателен для проверки shader: TS typecheck/build не видит многие GLSL compile/link errors. Current Three registry snapshot `0.186.1`; WebGLRenderer начиная r163 поддерживает WebGL2, не WebGL1. [Renderer](https://threejs.org/docs/pages/WebGLRenderer.html), [registry](https://registry.npmjs.org/three/latest).

## Debug ladder

1. Сначала constant-color fragment shader. Black screen: проверь context, camera/geometry/viewport, visibility, depth/culling, shader logs.
2. Покажи UV как color; затем отдельно pointer/resolution/time. Это выявляет wrong coordinate convention, y inversion и zero-size container до сложной math.
3. Добавь один procedural field, затем distortion и color conversion. Для NaN проверь divisions, normalize zero-vector, pow negative base и out-of-range texture samples. Не подавляй compile errors.
4. Frame capture/trace сравни на постоянном DPR; затем снизь DPR/target size. Значительное ускорение указывает на fragment/fill pressure. Для CPU узкого места отдельно проверь uniforms/event allocations и React commits.
5. Caps и framebuffer completeness проверяй на initialization, не многократно hot loop. Float/half-float targets, filtering, precision, point size и texture limits различаются. Наличие WebGL2 не доказывает поддержку каждого optional GPU effect.

Бюджет задаётся сценой: DPR, full-screen passes, particle count, octave/sample count, geometry vertices, textures и render target memory. Начальная лёгкая версия использует один pass, небольшую frequency и ограниченный DPR; новые expensive эффекты добавляй после trace. Примерные 16.7 ms/frame на 60 Hz включают DOM/composite и GPU, а не отдаются shader целиком. Не заявляй steady FPS без hardware/time/quality evidence.

## Типичные стоимость/ошибка

| Решение | Риск | Проверка/уменьшение |
|---|---|---|
| Большой DPR + full-screen noise | Pixels растут DPR² | DPR/target cap, меньше samples |
| Много transparent particles | Overdraw несмотря на low polygons | Smaller sprites, fewer layers, mask/blend review |
| Material created per pointer/frame | Program/resource churn | Stable material/uniforms, mutable values |
| needsUpdate на каждый time change | Unnecessary compilation signaling | Меняй uniform.value; source/defines редко |
| Слишком dense plane | Geometry cost без нужной формы | Тесселяция только для vertex distortion |
| fBm с множеством octaves | ALU/texture bandwidth | Fixed small quality presets, noise texture comparison |
| Synchronous GPU readback | Pipeline stalls | Откажись, либо documented async path если требуется |
| Ping-pong targets без dispose | Memory leak на route changes | Явный resource owner/reset/dispose |

Перед копированием внешнего GLSL проверь источник и license, не исполняй чужой build script и не добавляй transitive loader ради одного string. Независимо от GLSL compile, некорректные sampled user data/URL нужно валидировать на JS/API boundary.

## Degradation contract

Static CSS/image poster и размеры контейнера присутствуют до initialization. Capability failure, compile failure, asset failure и context loss переводят visual layer в replacement, а основной текст, links/forms/navigation продолжают работать. Reduced motion может остановить time и оставить approved still frame, но optional pointer field тоже выключается при необходимости. Low-quality preset уменьшает cost без попытки скрыть недоступный основной control.

Context restoration — bounded workflow: cleanup old owned programs/buffers/targets/listeners → recreate нужный preset → проверить → при повторном сбое poster. Не создавай renderer в бесконечном retry и не оставляй hidden canvas, продолжающий animation. Fallback для mobile/unsupported не должен требовать GPU, чтобы показать GPU error.

## Acceptance

Проверь actual shader logs на Chromium/Firefox/WebKit в поддерживаемом наборе; разные vendors могут давать разные precision/noise pixels. Capture endpoints и intermediate states при фиксированном time; тест resize/orientation и current DPR. Сравни palette с обычным DOM token цветом, исключи double color transform. В reduce/offscreen/hidden mode profiling должен подтвердить отсутствие необязательной работы. После нескольких mount/unmount количество owned GPU resources стабилизируется; cached assets учитывай отдельно.

Первичные источники: [GLSL ES 3.00](https://registry.khronos.org/OpenGL/specs/es/3.0/GLSL_ES_Specification_3.00.pdf), [GLSL ES 1.00](https://www.khronos.org/files/opengles_shading_language.pdf), [WebGL2 spec](https://registry.khronos.org/webgl/specs/latest/2.0/), [ShaderMaterial](https://threejs.org/docs/pages/ShaderMaterial.html), [RawShaderMaterial](https://threejs.org/docs/pages/RawShaderMaterial.html), [Texture](https://threejs.org/docs/pages/Texture.html), [Material lifecycle](https://threejs.org/docs/pages/Material.html), [Renderer diagnostics](https://threejs.org/docs/pages/WebGLRenderer.html). Код этого набора проверен по API документально; runtime compilation в конкретном будущем проекте остаётся обязательной.
