# Проверяемые integration patterns

API сверены по [GSAP React](https://gsap.com/resources/React/), [matchMedia](https://gsap.com/docs/v3/GSAP/gsap.matchMedia()/), [ScrollTrigger](https://gsap.com/docs/v3/Plugins/ScrollTrigger/) и [SplitText](https://gsap.com/docs/v3/Plugins/SplitText/) **2026-10-10**. Код — небольшие адаптируемые рецепты; он не был запущен в каждом будущем проекте. Перед применением нужны его typecheck/build/browser checks.

## Scoped React setup, desktop pin, reduced motion

Базовая разметка видима и без анимации. Desktop условие здесь пример, breakpoint выбери по композиции. `mm.revert()` также удаляет принадлежащий matchMedia pin; чужие triggers сохраняются.

```tsx
"use client";
import { useRef } from "react";
import gsap from "gsap";
import { ScrollTrigger } from "gsap/ScrollTrigger";
import { useGSAP } from "@gsap/react";

gsap.registerPlugin(ScrollTrigger, useGSAP);

export function FeatureScene() {
  const root = useRef<HTMLElement>(null);
  useGSAP(() => {
    const mm = gsap.matchMedia();
    mm.add(
      "(min-width: 64rem) and (prefers-reduced-motion: no-preference)",
      () => {
        const tl = gsap.timeline({
          defaults: { ease: "none" },
          scrollTrigger: {
            trigger: root.current,
            start: "top top",
            end: "+=700",
            pin: true,
            scrub: 0.4,
            invalidateOnRefresh: true,
          },
        });
        tl.addLabel("detail")
          .to("[data-art]", { yPercent: -12 }, "detail")
          .to("[data-caption]", { opacity: 0.6 }, "detail");
      },
      root,
    );
    return () => mm.revert();
  }, { scope: root });
  return (
    <section ref={root}>
      <div data-art aria-hidden="true">Decorative artwork</div>
      <h2 data-caption>Feature explanation</h2>
    </section>
  );
}
```

По сравнению с `duration: 0` на каждом tween отключение setup сохраняет нормальную прокрутку в reduce. Изменение media query live приводит к revert. Если effect получает external listener, убирай его в custom cleanup. Client directive не отменяет SSR первоначальной разметки: не обращайся к window во время render.

## Responsive text reveal

Для простого заголовка, без вложенных links. Вызывай внутри useGSAP/matchMedia no-preference callback; зарегистрируй SplitText один раз. Не скрывай h1 в глобальной CSS до успеха JS.

```ts
import { SplitText } from "gsap/SplitText";
gsap.registerPlugin(SplitText);

const split = SplitText.create(headingElement, {
  type: "lines",
  mask: "lines",
  autoSplit: true,
  onSplit(self) {
    return gsap.from(self.lines, {
      yPercent: 110,
      opacity: 0,
      duration: 0.55,
      stagger: 0.08,
      ease: "power2.out",
    });
  },
});
// When not owned by a GSAP context, call split.revert() on teardown.
```

Проверяй reflow после font load, изменение ширины и возврат оригинального текста при cleanup. `aria: 'auto'` не сохраняет функциональность вложенных links; предпочтительно оставь linked paragraph обычным и split только декоративные слова вне controls. Для вручную дублированного accessible текста не создавай вторые focusable controls.

## Horizontal track: корректные единицы

Внутри scoped setup используй стабильные `viewportElement` и `trackElement`. Native mobile replacement — CSS overflow track с доступными controls или обычный stack. Состояние при нулевой дистанции должно оставаться статическим.

```ts
const travel = () => Math.max(0,
  trackElement.scrollWidth - viewportElement.clientWidth);
if (travel() > 0) {
  gsap.to(trackElement, {
    x: () => -travel(),
    ease: "none",
    scrollTrigger: {
      trigger: viewportElement,
      pin: viewportElement,
      start: "top top",
      end: () => `+=${travel()}`,
      scrub: true,
      invalidateOnRefresh: true,
    },
  });
}
```

Если responsive layout меняет zero travel на positive, пересоздай setup через matchMedia или принадлежащий компоненту lifecycle. `refresh()` пересчитает только существующий trigger, не создаст отсутствующий. Вложенные triggers с `containerAnimation` не могут сами pin/snap; moving track должен иметь linear ease. После fonts/images/data layout settlement сделай один согласованный refresh, не refresh каждый frame.

## Проверка ошибок владения

Для shared layout в React чаще проще Motion `layoutId`; если выбран GSAP Flip, сохрани state до `setState`, запускай `Flip.from` после соответствующего DOM commit и wrap callback contextSafe. Таймер «через 300 ms» не доказывает commit. Если пользователь быстро меняет состояние, новая transition должна стартовать из текущей визуальной геометрии; тестируй отмену и возврат focus. Не применяй `ScrollTrigger.getAll().forEach(kill)` в component teardown и не объединяй Lenis со ScrollSmoother в одном root.
