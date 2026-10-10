# Scene lifecycle и проверенные APIs

Проверено **2026-10-10**: [Canvas](https://r3f.docs.pmnd.rs/api/canvas), [hooks](https://r3f.docs.pmnd.rs/api/hooks), [objects/disposal](https://r3f.docs.pmnd.rs/api/objects), [Drei useGLTF](https://drei.docs.pmnd.rs/loaders/gltf-use-gltf), [controls](https://drei.docs.pmnd.rs/controls/introduction), [MathUtils](https://threejs.org/docs/pages/MathUtils.html). Snippet показывает ownership и frame-independent damping; состояние `active` владелец страницы вычисляет отдельно, например по IntersectionObserver.

## Небольшой Canvas island

```tsx
"use client";
import { useRef } from "react";
import { Canvas, useFrame } from "@react-three/fiber";
import { MathUtils, type Mesh } from "three";

function ProductShape({ animate }: { animate: boolean }) {
  const mesh = useRef<Mesh>(null);
  useFrame(({ pointer }, delta) => {
    if (!mesh.current || !animate) return;
    const dt = Math.min(delta, 0.05);
    mesh.current.rotation.y = MathUtils.damp(
      mesh.current.rotation.y, pointer.x * 0.2, 8, dt);
    mesh.current.rotation.x = MathUtils.damp(
      mesh.current.rotation.x, -pointer.y * 0.1, 8, dt);
  });
  return (
    <mesh ref={mesh}>
      <boxGeometry args={[1.8, 1, 0.5]} />
      <meshStandardMaterial color="#bfb7a9" roughness={0.45} />
    </mesh>
  );
}

export function ProductScene({ active, reduceMotion }: {
  active: boolean; reduceMotion: boolean;
}) {
  const animate = active && !reduceMotion;
  return (
    <div style={{ height: 360 }} aria-hidden="true">
      <Canvas dpr={[1, 1.5]} frameloop={animate ? "always" : "demand"}
        camera={{ position: [0, 0, 4], fov: 40 }}
        fallback={<div>Static product representation</div>}>
        <ambientLight intensity={0.6} />
        <directionalLight position={[3, 4, 2]} intensity={2} />
        <ProductShape animate={animate} />
      </Canvas>
    </div>
  );
}
```

Это decorative sample: accessible product information находится в DOM рядом. В production замени текст fallback подходящим poster и добавь ErrorBoundary. При переходе reduceMotion в live=true этот пример прекращает движение в текущей pose; если нужен конкретный neutral pose, обнови object через отдельный effect и invalidate, а не оставляй бесконечный loop.

`frameloop='demand'` экономит работу при статической сцене, но mutation из GSAP/ref сама не вызывает frame. Согласованный controller должен вызвать `invalidate()` при изменении progress. Smooth damping в demand режиме требует продолжать invalidation до convergence, иначе получится одно обновление. Если сцена непрерывно меняется, используйте always только пока она активна. Для обычного vanilla Three.js владелец использует renderer.setAnimationLoop; на teardown setAnimationLoop(null).

## Assets и resources

`useGLTF(url)` возвращает кешированные scene/nodes/materials; `useGLTF.preload(url)` начинает загрузку раньше. Cache key — URL. Один Object3D не может находиться в двух parents одновременно: для нескольких экземпляров используй подходящий clone/instancing; skinned models требуют skeleton-aware clone. Меняй экземпляр material отдельно, если variant не должен влиять на соседние consumers.

Заранее проверь compression (Draco/Meshopt), texture formats и decoder hosting. Decoder URL/версии должны совпадать с проектом; не подставляй непроверенный CDN по памяти. Suspense fallback **внутри** Canvas должен быть scene node (или Drei Html), не обычным `<div>`. Loading poster и retry/error controls можно держать в внешнем DOM.

R3F автоматически dispose принадлежащие декларативные объекты по своей модели. `primitive` и кешированный external resource требуют отдельного ownership решения. `dispose={null}` — осознанное отключение автоповедения для shared assets, не универсальный фикс утечки. Уничтожение material не уничтожает его textures. Vanilla cleanup включает geometries, owned materials/textures, render targets, controls/listeners и renderer; не dispose shared cache на component unmount. Отмена loading callback и очистка timers — отдельная ответственность.

Renderer, созданный Canvas или его factory, принадлежит R3F; переданный renderer instance требует собственного dispose lifecycle. Не переносите renderer между roots без проверки. Для postprocessing выясни, кто освобождает composer buffers/passes; ещё один gl.render в useFrame с positive priority означает takeover automatic rendering.

## Свет, камера и scroll

Выровняй near/far с размером сцены, проверяй depth fighting при очень широком диапазоне. Camera aspect и projection matrix должны обновляться при container resize; Canvas делает свою стандартную часть, custom camera/controller — свою. Environment имеет texture/memory budget. Качественные contact shadows, high-resolution shadow maps и transmission могут стоить больше числа polygons.

Scroll scene может читать нормализованный 0..1 progress от native ScrollTrigger/useScroll/controller, интерполировать позиции в useFrame и render только там. UI state меняется на смысловых границах, не каждый пиксель. Touch и reduce получают static pose/простую последовательность. Не создавай два разных scroll контейнера для DOM story и 3D story, если их синхронизация не определена явно.
