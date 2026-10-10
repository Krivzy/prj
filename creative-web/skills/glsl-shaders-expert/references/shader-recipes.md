# Проверяемая shader программа

Проверено **2026-10-10**: [ShaderMaterial](https://threejs.org/docs/pages/ShaderMaterial.html), [RawShaderMaterial](https://threejs.org/docs/pages/RawShaderMaterial.html), [Texture](https://threejs.org/docs/pages/Texture.html), [GLSL ES 3.00 specification](https://registry.khronos.org/OpenGL/specs/es/3.0/GLSL_ES_Specification_3.00.pdf) и [WebGL2 specification](https://registry.khronos.org/webgl/specs/latest/2.0/). Пример ниже — собственный простой материал, не скопированный noise asset. Он использует обычный Three ShaderMaterial с традиционным поддерживаемым preprocessing dialect; не устанавливай одновременно raw GLSL3 declarations.

## Uniform contract

| Name | Type | Units/space | Owner |
|---|---|---|---|
| uTime | float | seconds, paused/reduced variant has fixed value | render controller |
| uDisplacement | float | object-space distance; 0 for flat/reduced view | quality/motion policy |
| uAspect | float | canvas CSS width / height, height > 0 | resize controller |
| uPointer | vec2 | UV 0..1, same y orientation as vUv | normalized pointer controller |
| uColorA/B | vec3 | linear color in this material pipeline | palette owner |

## Vertex shader

```glsl
uniform float uTime;
uniform float uDisplacement;
varying vec2 vUv;
void main() {
  vUv = uv;
  vec3 p = position;
  p.z += sin((p.x + p.y) * 3.0 + uTime * 0.3) * uDisplacement;
  gl_Position = projectionMatrix * modelViewMatrix * vec4(p, 1.0);
}
```

Plane needs sufficient segments only if actual displacement is enabled; screen-aligned flat gradient can use a two-triangle quad. Этот unlit материал не пересчитывает surface lighting; для shaded deformation нужны корректные normals и shadow materials. Не увеличивай segments для эффекта, который происходит только во fragment stage.

## Fragment shader: small value-noise field

```glsl
uniform float uTime;
uniform float uAspect;
uniform vec2 uPointer;
uniform vec3 uColorA;
uniform vec3 uColorB;
varying vec2 vUv;

float hashCell(vec2 p) {
  return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}
float valueNoise(vec2 p) {
  vec2 cell = floor(p);
  vec2 f = fract(p);
  vec2 blend = f * f * (3.0 - 2.0 * f);
  float a = hashCell(cell);
  float b = hashCell(cell + vec2(1.0, 0.0));
  float c = hashCell(cell + vec2(0.0, 1.0));
  float d = hashCell(cell + vec2(1.0, 1.0));
  return mix(mix(a, b, blend.x), mix(c, d, blend.x), blend.y);
}
void main() {
  vec2 scale = vec2(uAspect, 1.0);
  vec2 p = (vUv - 0.5) * scale;
  vec2 pointer = (uPointer - 0.5) * scale;
  float nearPointer = exp(-5.0 * dot(p - pointer, p - pointer));
  float field = valueNoise(p * 2.4 + vec2(uTime * 0.05, -uTime * 0.04));
  float wave = sin(p.x * 4.0 + p.y * 3.0 + field * 1.7
    - uTime * 0.25 + nearPointer * 0.4);
  float blend = smoothstep(-0.8, 0.8, wave);
  gl_FragColor = vec4(mix(uColorA, uColorB, blend), 1.0);
  #include <tonemapping_fragment>
  #include <colorspace_fragment>
}
```

Sine hash — лёгкий decorative seed, не качественный генератор для scientific/simulation задачи. При large coordinates/precision differences возможен иной pattern на GPU; не полагайся на идентичные pixels across vendors. Для repeatable visual baseline фиксируй backend и time. Уменьшение frequency/amplitude часто предотвращает shimmer лучше, чем добавление supersampling.

## Three material setup

```ts
import { Color, ShaderMaterial, Vector2 } from "three";
const material = new ShaderMaterial({
  uniforms: {
    uTime: { value: 0 },
    uDisplacement: { value: 0 },
    uAspect: { value: 1 },
    uPointer: { value: new Vector2(0.5, 0.5) },
    uColorA: { value: new Color("#152d32") },
    uColorB: { value: new Color("#b9c2a6") },
  },
  vertexShader,
  fragmentShader,
});
// In the owning loop, update values; do not re-create material per frame.
material.uniforms.uTime.value = elapsedSeconds;
material.uniforms.uPointer.value.set(pointerU, pointerV);
// Owner teardown: material.dispose(); dispose owned textures separately.
```

`vertexShader`/`fragmentShader` variables содержат строки выше. Three Color/string palette и color chunks образуют конкретный output pipeline. Chunks являются version-specific Three shader API: при изменении версии проверяй [colorspace_fragment source](https://github.com/mrdoob/three.js/blob/dev/src/renderers/shaders/ShaderChunk/colorspace_fragment.glsl.js) и [tonemapping_fragment source](https://github.com/mrdoob/three.js/blob/dev/src/renderers/shaders/ShaderChunk/tonemapping_fragment.glsl.js). При composer с final output pass определить, где выполняется tone mapping/color conversion: не делай их дважды. RawShaderMaterial не вставляет эти built-ins автоматически и требует собственного контракта.

## GLSL3 distinction

Для RawShaderMaterial с `glslVersion: GLSL3` объяви `precision highp float;`, vertex `in vec3 position; in vec2 uv; out vec2 vUv;`, fragment `in vec2 vUv; out vec4 outColor;`, use `texture(...)` и пиши в outColor. Не дублируй вручную #version, если версия вставляется renderer. Это отдельная версия программы, не набор замен в произвольном ShaderMaterial snippet. Raw output color space, matrices и attributes тоже должны быть определены явно.
