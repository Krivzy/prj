# Игровые операции и границы нагрузки

Цель: Paper 26.1.2, API `26.1.2.build.74-stable`, Java 25. REST передаёт игровые операции в основной поток Paper; фоновые HTTP/SQLite задачи не читают игровые объекты. Для `/api/action` требуется `{id: UUID, action, params, confirmed}`. Конкретные ограничения прав и подтверждений применяет HTTP-слой.

## Gamerules

`world.gamerule` принимает `{world, rule, value}`. `rule` — современный registry key, например `minecraft:keep_inventory`. Список доступных правил и текущие значения возвращает `/api/worlds`: экспериментальные правила без включённого feature flag пропускаются. Реестр и типы соответствуют [официальному API Paper](https://jd.papermc.io/paper/26.1.2/org/bukkit/GameRules.html).

Boolean требует JSON `true`/`false`, а Integer требует целое конечное число. Строки, дробные числа, `NaN`, бесконечности и выход за границы отклоняются до изменения мира.

| Registry key без `minecraft:` | Диапазон Integer в MCC |
| --- | --- |
| `random_tick_speed` | 0–128 |
| `players_sleeping_percentage` | 0–100 |
| `max_command_forks` | 0–1024 |
| `max_command_sequence_length` | 0–65536 |
| `max_block_modifications` | 0–32768 |
| `max_entity_cramming` | 0–128 |
| `fire_spread_radius_around_player` | −1–10000 |
| Остальные Integer правила | 0–10000 |

Сервер дополнительно проверяет собственные допустимые значения. Верхние пределы MCC предотвращают ввод произвольного Integer до 2 миллиардов. Изменение самой логики мира всё равно может влиять на нагрузку: высокие `random_tick_speed`, дальность симуляции и сторонние datapacks требуют наблюдения за MSPT.

## Инвентарь

`GET /api/players/{uuid}/inventory?kind=main|ender` возвращает `revision`, `size` и `slots`. `revision` — SHA-256 полного Paper-представления всех слотов, включая компоненты предметов.

- `inventory.set`: `{uuid,kind,slot,revision,item}`; `item:null` очищает выбранный слот.
- `inventory.move`: `{uuid,kind,from,to,revision}`; операция меняет два слота местами.
- При изменившемся `revision` сервер отвечает HTTP 409 и не меняет ни один слот. Клиент должен перечитать инвентарь.
- Новый предмет полностью валидируется до изменения инвентаря. При том же material редактируется копия исходного stack: скрытые компоненты, пользовательские атрибуты, цвета и сторонние PDC сохраняются. Неизменённые name/lore/enchantments сохраняют существующее оформление.
- Число предметов ограничено фактическим `maxStackSize` stack. `give` сообщает фактически выданное количество и `leftover`; переполнение не выбрасывается в мир автоматически.

## Спавн

`mob.spawn` принимает `{world,x,y,z,type,count,radius,name?,health?,ai?,persistent?,glowing?}`; вместо координат можно передать online player `uuid`. Ответ `status:queued` означает постановку в очередь, а не завершение. В topic `spawn` приходит итог `completed|partial` с фактическими `spawned`, `failed`, `entityUuids` и `error`.

Границы: до 128 requested entity за операцию, до 4 очередей, суммарно до 5 спавнов за тик; начало следующей операции прекращается после бюджета 0,75 ms. Один native вызов Paper может превысить этот бюджет. TPS ниже 18 приостанавливает очередь. Применяется `game.max-world-entities` (по умолчанию 10000). MCC не загружает destination chunks: незагруженная точка даёт отдельную ошибку. Player, Wither и Ender Dragon исключены из каталога спавна.

`mob.modify` работает только с загруженным living entity, кроме Player. Параметры `{entityUuid,name?,health?,ai?,glowing?,invisible?,silent?,invulnerable?,remove?}` валидируются перед применением.

## Игровые события

Рабочие presets: `waves`, `fireworks`, `lightning`, `treasure`, `animals`, `boss`. Запуск: `{preset,world,x,y,z,count,intervalTicks,durationSeconds,radius}` или `{preset,uuid,...}`. Для `treasure` можно передать `item`. Остановка и пауза: `{eventId}` и `{eventId,paused:true|false}`.

До 3 активных событий, до 200 принадлежащих MCC entity, не более 3 операций за тик с бюджетом 0,75 ms и TPS Guard 18. Выгруженные entity продолжают занимать лимит и не вынуждают подгрузку chunk. Погибшие, подобранные, объединённые и исчезнувшие entity освобождают лимит через `EntityRemoveEvent`. Пауза продлевает срок события на время паузы.

При stop, истечении срока или отключении модуля удаляются только entity с PDC ownership текущего события. Выгруженные остатки очищаются при следующей загрузке chunk. Уже подобранные игроком награды остаются в его инвентаре.

Защита вещей: предметы событий не объединяются с чужими dropped stacks; event mobs не подбирают предметы. Event Zombie/Skeleton не превращаются в новые UUID, а random spawn data и Zombie reinforcements отключены для сохранения лимита и владения. Boss — Zombie с 100 HP и attack damage 6, без взрывов. Молния использует `strikeLightningEffect`, а fireworks имеют marker, отменяющий их damage. Живые mobs сохраняют обычное поведение vanilla, включая бой; следует выбирать подходящее место для preset.

## Карта

Модуль `map` выключен по умолчанию. `/api/map?world=...&x=...&z=...` возвращает tile 64×64 с шагом 2 blocks, исходной приблизительной палитрой и маркерами игроков. Незагруженные chunks дают `null` pixel. Суммарно до 64 колонок за тик и бюджет 0,45 ms; native вызов может занять больше. Кэш: 8 tiles на 5 минут, до 4 одновременно готовящихся tiles. При отключении модуля и shutdown задания отменяются; постоянный scheduler карты отсутствует, пока нет запросов.
