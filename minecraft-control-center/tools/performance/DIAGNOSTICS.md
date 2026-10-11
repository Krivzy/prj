# Предварительная проверка стенда, 2026-10-11

Все перечисленные disposable JVM завершены нормальным `stop`, exit 0;
свои ports закрыты, прежние process identities отсутствуют. Main не менялся.
Private worlds/logs/session receipts не публикуются.

Исходный parallel pilot: Paper, Purpur и Core прошли шесть bounded workload
assertions, но имели разные original generated block-state sample SHA.
Сессия сохранена как failed и **не используется для performance verdict**.
Повтор точно того же Paper также дал разные hash и blockstates. Это
показывает неповторяемость самого parallel baseline в этом fixture; точная
причина всех различий не установлена. Различия не ограничивались fluids.

| Сравнение сохранённых snapshots | Отличающихся блоков | Позиции исходной hash выборки | Biome NBT chunks |
| --- | ---: | ---: | ---: |
| Paper → Core, parallel pilot | 507 | 34 | 0 |
| Paper → Purpur, parallel pilot | 8064 | 505 | 0 |
| Paper → тот же Paper, parallel repeat | 7839 | 496 | 0 |
| Paper → тот же Paper, worker1/io1/synchronous repeat | 6 | 0 | 0 |

Paper/Core: 501 stone-variant и 6 ore-variant differences; fluids/leaves
не отличались. Paper/Paper parallel различался также trees/leaves/fluids.
Проверены все 12 582 912 сохранённых блоков в 128 generated chunks.

Два independent controlled Paper boots дали одинаковый **исходный** sample
SHA `9e1ca006065d53ff320910dc5e043d5af828980a665ef173e0b2904574fc5e60`.
Но snapshots после stop различались в 6 позициях other→fluid вне sample.
Поэтому полное побайтовое равенство мира не заявляется; исходные guards
не ослаблялись исключением воды или преобразованием hash.

Эти предварительные runs использовали прежний frozen MCC artifact
`4b5827d8f826e27d2a7beba58802ea81d011d5d98775cfe9da3068abdb059ac4`.
Они проверяли стенд, не новый финальный bundle. Финальные measured repeats
и точные artifact SHA должны быть в отдельном performance comparison отчёте.
