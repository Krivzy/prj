---
name: gsap-animation-expert
description: Implement or debug GSAP timelines, ScrollTrigger pinning and scrubbing, staggered text/SVG reveals, FLIP transitions, and React animation cleanup. Use for imperative choreography or GSAP-specific work; ordinary hover states and React state transitions can use CSS or Motion.
---

# GSAP Animation Expert

Работай по-русски; код и API оставляй на английском. Этот skill выбирает и интегрирует GSAP для конкретной композиции, а не добавляет библиотеку в каждый проект. Сначала уточни роль эффекта, состояния начала/конца, возможность прерывания, размеры экрана и поведение без движения. Для простого hover/focus достаточно CSS; для React layout/exit чаще достаточно Motion.

## Подключение подробностей

Для lifecycle, pinning, text reveal и horizontal track прочитай [integration patterns](references/integration-patterns.md). Для tween/timeline control, FLIP и SVG — [choreography и SVG](references/choreography-and-svg.md). [Provenance и errata](references/provenance-and-errata.md) нужны при проверке версии или оценке официального upstream skill. Полные upstream manuals не включены: рабочие рецепты сверены с API docs, а не требуют мысленно исправлять чужие примеры.

## Архитектура и владение

1. Изучи `package.json`, lockfile и существующую систему анимаций. Зависимости `gsap` и при React `@gsap/react` добавляй только в выбранный проект его package manager. Проверяй версии и peerDependencies; snapshot источников не обязывает обновлять работающий проект.
2. Опиши motion specification: trigger → targets → properties → duration/ease → interruption → mobile → reduced-motion. Один timeline может управлять несколькими фазами; используй labels и position parameter, а не цепочку таймеров.
3. Каждый animated property имеет одного владельца. GSAP владеет сценической последовательностью; Motion — реактивным UI на отдельном узле/обёртке; Lenis — scroll transport; R3F `useFrame` — render и конечной записью 3D transforms. GSAP может менять числовой progress-ref для 3D, который R3F читает; не записывай одну rotation одновременно из двух loop.
4. Для scroll-linked timeline один ScrollTrigger ставь на timeline, а не его дочерние tweens. Стабильный wrapper определяет trigger/pin; движение происходит на внутренних элементах. DOM layout должен оставаться пригодным для чтения и без JS.
5. В React создавай анимации в client component через `useGSAP` со scoped ref. Для пересоздания при зависимости используй `revertOnUpdate: true`; поздние обработчики оборачивай `contextSafe`, отменяй fetch/timers/listeners самостоятельно. Cleanup локален: нельзя уничтожать ScrollTriggers чужих компонентов через `getAll().forEach(kill)`.

## Работа с эффектами

- **Pinning:** сначала создай обычный поток документа. Проверь длину сценария, переход из pin, sticky/fixed ancestors и focus на внутренних controls. На узком/touch экране чаще заменяй длинный pin на последовательный контент; reduced-motion обычно получает контент без pin/parallax.
- **Parallax:** используй небольшое смещение внутреннего изображения с запасом краёв; ограничь диапазон и раздели слои. Не скрывай CTA и смысловой текст до нужного scroll progress. Numeric scrub добавляет задержку playhead и не гарантирует ускорения.
- **Stagger/reveal:** ограничь общую задержку первой информации, а не только each duration. Не создавай сотни wrappers для обычного параграфа. Для lines после fonts/reflow применяй SplitText autoSplit/onSplit; верни animation из callback. Семантика ссылок внутри split текста требует отдельного решения и проверки screen reader.
- **SVG:** сохрани `viewBox`, координатную систему, доступное имя и статическое состояние. Drawing нужен видимый stroke; morphing нужно проверить в середине траектории и при отмене. Не меняй React-owned SVG DOM через convertToPath без явной модели владения.
- **FLIP:** capture старой геометрии → изменение layout владельцем React → после commit `Flip.from`. Не перемещай React DOM вручную. `absolute` и `scale` применяй по измеренной необходимости; scaled text может потребовать отдельного child.
- **Синхронизация:** общие labels, один progress или clock. При быстром повторном hover/click корректно reverse/restart/overwrite только принадлежащие эффекту properties. Не накапливай новый tween на каждый pointer event: рассмотри `quickTo`.

## Производительность и приёмка

Предпочитай transform/opacity, но подтверждай фактические layout/paint/composite costs в браузере. Большая маска, filter/blur и SVG morph могут быть дорогими. `will-change` добавляй на время конкретного эффекта; не промотируй весь документ.

В начале согласуй бюджет сцены: число одновременно движущихся узлов, длительность pin, дополнительный JS, размер изображений, целевой класс устройств. Для 60 Hz весь frame имеет около 16.7 ms, не только GSAP; 60 FPS является целью измерения, не обещанием. Уменьшай количество слоёв/сегментов/масок на mobile и останавливай бесконечные декорации вне viewport.

Проверь в реальном браузере: первый paint без скрытого навсегда текста; прокрутку вперёд/назад; быстрые повторные события; hash navigation; keyboard focus; resize с переходом breakpoint; позднюю загрузку шрифта/изображения; reduced-motion при загрузке и изменении live; несколько mount/unmount/route transitions в StrictMode. После unmount должны исчезнуть только принадлежащие компоненту triggers, pin spacers, listeners и tweens. Укажи измеренные устройства, trace и ограничения; сборка одна не доказывает плавность.
