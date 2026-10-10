# Timeline, FLIP и SVG

Проверено **2026-10-10**: [Timeline](https://gsap.com/docs/v3/GSAP/Timeline/), [CSSPlugin](https://gsap.com/docs/v3/GSAP/CorePlugins/CSS/), [Flip](https://gsap.com/docs/v3/Plugins/Flip/), [DrawSVG](https://gsap.com/docs/v3/Plugins/DrawSVGPlugin/), [MorphSVG](https://gsap.com/docs/v3/Plugins/MorphSVGPlugin/), [MotionPath](https://gsap.com/docs/v3/Plugins/MotionPathPlugin/). Это scoped snippets; `rootElement`, targets и state принадлежат вызывающему lifecycle.

## Композиция с контролируемыми фазами

```ts
const ctx = gsap.context(() => {
  const tl = gsap.timeline({
    paused: true,
    defaults: { duration: 0.45, ease: "power2.out" },
  });
  tl.addLabel("enter", 0)
    .from("[data-title]", { y: 24, opacity: 0 }, "enter")
    .from("[data-card]", {
      y: 16, opacity: 0, stagger: { amount: 0.24 },
    }, "enter+=0.1")
    .addLabel("ready");
  tl.play();
}, rootElement);
// Owner teardown: ctx.revert(); restores inline styles and kills owned work.
```

Начальные состояния `from` применяются при создании; несколько from/fromTo на одной property могут перезаписывать друг друга до воспроизведения. Раздели targets/фазы, либо задай later tween `immediateRender: false` при нужной семантике. `duration` всей timeline определяется children; не присваивай constructor duration как default их длительности. For feedback-driven UI храни timeline и reverse/play существующий экземпляр вместо накопления timers.

На безусловном semantic контенте opacity animation обычно сохраняет поток; `autoAlpha: 0` дополнительно делает visibility hidden, влияя на focus/assistive visibility. Выбирай это намеренно для скрываемого UI, а не автоматически для текста. При reduced motion skip setup оставляет весь контент видимым.

## FLIP, когда layout меняется

```ts
import { Flip } from "gsap/Flip";
gsap.registerPlugin(Flip);
const state = Flip.getState(targetElements);
// Apply final layout through the DOM owner, then wait for its committed DOM.
const transition = Flip.from(state, {
  duration: 0.4,
  ease: "power2.inOut",
  scale: true,
  nested: true,
});
```

В vanilla final class change может происходить непосредственно между capture и from. В React выполняй capture перед изменением состояния и from после соответствующего commit, например принадлежащим update `useLayoutEffect`; не копируй comment как синхронный React setState. `nested` нужен при одновременно включённых parent/child targets. `scale` не default; проверь distortion текста/границ. `absolute` может убрать элементы из flow, поэтому измерь reserved height. Быстрое повторное действие должно прерывать принадлежащую transition и измерять текущую visual state.

## SVG recipes

```ts
import { DrawSVGPlugin } from "gsap/DrawSVGPlugin";
import { MorphSVGPlugin } from "gsap/MorphSVGPlugin";
import { MotionPathPlugin } from "gsap/MotionPathPlugin";
gsap.registerPlugin(DrawSVGPlugin, MorphSVGPlugin, MotionPathPlugin);

gsap.fromTo(strokePath, { drawSVG: "0% 0%" }, {
  drawSVG: "0% 100%", duration: 0.8, ease: "power1.inOut",
});
gsap.to(shapePath, {
  morphSVG: { shape: targetPath }, duration: 0.6, ease: "power2.inOut",
});
gsap.to(movingElement, {
  motionPath: { path: trajectoryPath, align: trajectoryPath,
    alignOrigin: [0.5, 0.5], autoRotate: true },
  duration: 1, ease: "none",
});
```

Это независимые возможности, не обязательная одна сцена. Строки импорта добавляй только для выбранного эффекта. Для draw исходный path должен иметь stroke и stroke-width; fill не «рисуется». Для morph предпочитай подготовленные path elements; проверь self-intersections и winding mid-frame. Если исходник circle/rect, преобразуй asset заранее, не подменяй React node на клиенте. Для path-following проверь систему координат viewBox, responsive масштаб и origin.

Семантически значимое SVG получает title/description или подходящее accessible name; декоративное SVG не должно создавать лишний tab stop. Static/reduced version сохраняет правильную конечную форму без forced scroll. Уточняй стоимость path complexity: smoothing/precompile исправляют разные проблемы, а не гарантируют низкую стоимость каждого frame.
