# Verified React patterns

API сверены **2026-10-10** по [animation](https://motion.dev/docs/react-animation), [AnimatePresence](https://motion.dev/docs/react-animate-presence), [layout](https://motion.dev/docs/react-layout-animations), [gestures](https://motion.dev/docs/react-gestures), [drag](https://motion.dev/docs/react-drag), [accessibility](https://motion.dev/docs/react-accessibility) и [useAnimate](https://motion.dev/docs/react-use-animate). Snippets не являются production-ready menu/dialog implementations; semantic/focus owner определён отдельно.

## State-driven card list

```tsx
"use client";
import { AnimatePresence, MotionConfig, motion, useReducedMotion }
  from "motion/react";

type Item = { id: string; label: string };
export function ResultList({ items }: { items: Item[] }) {
  const reduce = useReducedMotion();
  return (
    <MotionConfig reducedMotion="user">
      <motion.ul layout>
        <AnimatePresence initial={false}>
          {items.map((item) => (
            <motion.li key={item.id} layout="position"
              initial={{ opacity: 0, y: reduce ? 0 : 8 }}
              animate={{ opacity: 1, y: 0 }}
              exit={{ opacity: 0 }}
              transition={{ duration: 0.18 }}>
              {item.label}
            </motion.li>
          ))}
        </AnimatePresence>
      </motion.ul>
    </MotionConfig>
  );
}
```

`initial={false}` у Presence сохраняет первый server-rendered список видимым без entrance. Последующие inserts допускают короткий fade, небольшое y только в no-reduce. Для interactive row owner восстанавливает focus после удаления до exit; retained exit node не должен оставаться дополнительной keyboard destination. Layout на parent помогает переизмерить список, а position на child избегает растяжения текста. В больших результатах сохрани virtualization, не анимируй весь dataset.

## Shared layout и namespaces

Свяжи active indicator через постоянный `layoutId` в собственной LayoutGroup; группу не меняй key на каждом render. Две независимые tab bars получают разные id. ARIA tabs behavior, roving tabIndex и keyboard navigation задаются существующим доступным компонентом; motion decoration `aria-hidden` не становится tab.

```tsx
import { LayoutGroup, motion } from "motion/react";
// Inside an already accessible tab implementation:
<LayoutGroup id="product-tabs">
  {tabs.map((tab) => (
    <div key={tab.id}>
      {tab.id === selectedId &&
        <motion.span layoutId="indicator" aria-hidden="true" />}
    </div>
  ))}
</LayoutGroup>
```

Scrollable ancestor, участвующий в measurements, может быть `motion.div layoutScroll`; fixed overlay — `layoutRoot`. Для `mode='popLayout'` custom direct child должен передавать DOM ref так, как требует актуальная React/Motion версия. Проверь parents positioning и быструю отмену, а не копируй mode ради устранения jump.

## Drag как дополнение к доступному действию

```tsx
const bounds = useRef<HTMLDivElement>(null);
return (
  <div ref={bounds} className="drag-zone">
    <motion.div drag="x" dragConstraints={bounds}
      dragElastic={0.08} dragMomentum={false}
      style={{ touchAction: "pan-y" }}>
      <button type="button" onClick={openDetails}>Open details</button>
    </motion.div>
  </div>
);
```

Imports: `useRef` из React и `motion` из motion/react. Zone имеет реальную measured width/height. Momentum=false полезен для deterministic repositioning, но drag itself не создаёт drop zone/keyboard sorting. В reorder workflow добавь кнопки move up/down или полноценную keyboard DnD модель с объявлением нового места. Картинки внутри draggable container получают `draggable={false}`, если browser ghost image мешает. Не ставь touch-action:none на весь документ.

## Cleanup и custom sequences

`useAnimate` возвращает scope ref и scoped animate с автоматическим cleanup его анимаций. Пример: `const [scope, animate] = useAnimate();` → ref на DOM → `animate('li', {opacity: 1})`. Это не отменяет external Promise, observer, timeout или вручную подключённую subscription. Для delayed logic учитывай cancellation и mounted state; controls от custom standalone animate останавливай согласно используемому API.

MotionValue не вызывает React render на каждый frame. Используй `useMotionValueEvent` для нужной подписки вместо setState в собственном scroll listener; event может менять React state только при редком смысловом threshold. `useScroll` возвращает progress MotionValue, а `useTransform` mapping может прямо идти в style. GSAP не должен писать тот же style и не должен создавать competing scroll source.

Springs строятся из motion tokens для feel, а длительность реакции оценивается на реальном UI. Не заставляй пользователю ждать completion Promise перед доступом к следующему действию, если это не требуется смыслом операции. Для reduced motion можно сохранять короткий fade, но UI state должен быть доступен сразу.
