# Один clock, cleanup и navigation

Проверено **2026-10-10** по официальному [Lenis README](https://github.com/darkroomengineering/lenis), [React adapter](https://github.com/darkroomengineering/lenis/blob/main/packages/react/README.md) и [GSAP ticker](https://gsap.com/docs/v3/GSAP/gsap.ticker/). Snippets относятся к `lenis 1.3.26` API snapshot и требуют project verification при другой версии.

## GSAP ticker integration с native reduce

Вызови этот factory **в browser effect** выбранного root provider. Передавай enabled явно; без продуктовой причины он false. CSS подключи в допустимой для framework application boundary.

```ts
import Lenis from "lenis";
import "lenis/dist/lenis.css";
import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";

gsap.registerPlugin(ScrollTrigger);

export function mountSmoothScroll(enabled: boolean): () => void {
  if (!enabled) return () => {};
  const reduce = window.matchMedia("(prefers-reduced-motion: reduce)");
  let releaseInstance: () => void = () => {};

  const configure = () => {
    releaseInstance();
    releaseInstance = () => {};
    if (reduce.matches) return;
    const lenis = new Lenis({
      autoRaf: false,
      anchors: true,
      syncTouch: false,
      respectReducedMotion: true,
    });
    const update = () => ScrollTrigger.update();
    const tick = (seconds: number) => lenis.raf(seconds * 1000);
    lenis.on("scroll", update);
    gsap.ticker.add(tick);
    releaseInstance = () => {
      gsap.ticker.remove(tick);
      lenis.destroy();
    };
  };

  configure();
  reduce.addEventListener("change", configure);
  return () => {
    reduce.removeEventListener("change", configure);
    releaseInstance();
  };
}
```

Effect в React может вернуть результат `mountSmoothScroll(enabled)`; StrictMode setup → teardown → setup должен оставить один instance/ticker. Factory не меняет глобальный `gsap.ticker.lagSmoothing`: официальный Lenis пример предлагает lagSmoothing(0), но это влияет на все GSAP timelines. Если проекту это действительно нужно, владелец animation clock делает настройку централизованно и документирует прежнюю policy. Не угадывай restore value и не меняй его из каждого component effect.

При no-GSAP интеграции используй `new Lenis({autoRaf:true, ...})` и destroy instance на teardown. При manual RAF сохраняй id, `cancelAnimationFrame(id)` перед destroy; внутренний RAF не должен продолжать планировать следующий frame после teardown. React adapter используй вместо собственного provider, если он уже есть; проверь options autoRaf/root/ref по lockfile version, не создавай второй instance под adapter.

В этом рецепте reduce destroy возвращает native scroll и убирает smoothing classes. Для приложения с WebGL sync, которое должно продолжать получать Lenis events, можно оставить instance с respectReducedMotion=true вместо destroy. Определи единственную policy и separately disable собственное decorative motion.

## Layout change

Lenis autoResize рассчитывает dimensions; ScrollTrigger всё равно может требовать refresh, когда trigger positions изменились. После images/fonts/data settlement вызывай согласованный `ScrollTrigger.refresh()` и при необходимости `lenis.resize()`. Не подписывай refresh на каждый lenis.on('scroll') и не refresh из onUpdate: это вызывает лишние расчёты/feedback.

## Navigation contract

Для обычных anchors возможен `anchors:true`; для measured header use `anchors:{offset:...}` или явно `scrollTo`. Если собственный link handler выполняет scrollTo, выключи автоматический anchor handler для этой же операции. Иначе два владельца могут прокрутить/обновить history дважды.

```ts
// targetHeading already has tabindex="-1"; history belongs to the router.
lenis.scrollTo(targetHeading, {
  offset: -headerHeight,
  immediate: reduceMotion,
  onComplete: () => targetHeading.focus({ preventScroll: true }),
});
```

Не ставь lock/force по умолчанию. Прерывание пользовательским input и навигация до completion должны быть обработаны владельцем. В случае delayed page/data rendering сначала дождись существования target, потом scroll; таймер с произвольной задержкой не доказывает layout readiness. Initial hash URL и browser back могут проходить иным путём, чем click handler — тестируй отдельно.

`stop()`/`start()` — управление instance, не полноценный modal primitive. Если native body lock управляется Radix/другим компонентом, сохрани его модель и проверяй точный scroller. Targeted `<div data-lenis-prevent>` полезен для nested scroll panel; это не замена его CSS overflow/max-height, ARIA и keyboard checks.
