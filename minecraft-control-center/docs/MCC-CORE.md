# MCC Core 0.2.0

MCC Core — исполняемый серверный дистрибутив с настоящими оптимизациями исходников
Paper 26.3, собственным запуском, проверкой связи с Minecraft Control Center и
установкой через панель. В отличие от предварительного 0.1, версия 0.2 включает
patch движка. Превосходство над Purpur/Pufferfish пока не доказано workload benchmark.

## Закреплённая основа

- Minecraft 26.3, JDK 25; upstream Paper имеет статус BETA.
- Source commit `46d4834e8e2ec4af9dcfa028076fb16dfb8a3911`, соответствующий Paper build 169.
- Исходная официальная сборка Paper: SHA-256
  `185d9f4267afca48959d6a56150cf8803b6645aee10de3ed147df7ab1ff781fb`.
- Собственная foundation 0.2: 55 980 330 байт, SHA-256
  `86c96263ba9a6257cc8b03e8e8fd8f90ee03fedb396918ed1fa1f74ebe196223`.
- Patchset SHA-256 `3d42ace4d8f3a14bb7765cb173da0bcd06aa7a6533bd33e4e8892a50d0ca3c6f`.

Официальные источники: [Paper build metadata](https://fill.papermc.io/v3/projects/paper/versions/26.3/builds/169),
[Paper source](https://github.com/PaperMC/Paper/tree/46d4834e8e2ec4af9dcfa028076fb16dfb8a3911),
[Paperclip](https://github.com/PaperMC/Paperclip). Автоматического обновления движка
или версии Minecraft нет.

## Оптимизации движка

- Ограниченный BitSet pool генерации руды: до 8 KiB на worker, без изменения RNG
  или порядка размещения; nested/oversized requests не удерживаются в pool.
- NBT serialization без второго lookup и лишних fastutil entry allocations.
- Bulk encoding больших contiguous long arrays с точным scalar fallback.
- Heightmap snapshots в EnumMap без stream pipeline; массивы продолжают копироваться.
- Palette storage без двух дублирующих `long` полей, с сохранением unsigned арифметики.

Изменения не переносят AI, redstone или Bukkit plugins на посторонние потоки,
не урезают фермы, view/simulation distance или spawning. Paper уже выполняет
IO и генерацию чанков на workers; это сохраняется. Подробности, 17 regression
тестов и source recovery находятся в [core-engine](../core-engine/README.md).
Bukkit.getName/getVersion и Minecraft brand сохраняют Paper для API compatibility;
панель показывает MCC Core только по живому подтверждённому CoreBridge.

Paper не полностью идентичен Mojang Vanilla: его API и upstream исправления
остаются. Совместимость всех Paper plugins и лучший результат любого workload
не следуют из этих оптимизаций автоматически.

## Связь с панелью

До запуска Paperclip Core проверяет `plugins/MinecraftControlCenter.jar`, его
точный SHA-256, реальные MCC entrypoints, protocol и engine provenance. Если
обязательный плагин отсутствует или изменён, миры не начинают загружаться.

После успешного bind веб-сервера MCC устанавливает связь через CoreBridge в
system classloader. Ожидание ограничено 180 секундами; затем один daemon worker
проверяет состояние раз в две секунды без обхода чанков, сущностей или игроков.
Отказ/отключение панели вызывает публичный `Bukkit.shutdown()` и штатное
сохранение. Принудительного kill/System.exit работающего сервера нет. Закрытие
браузера не отключает серверную панель и не останавливает Core.

Core привязан к точному MCC JAR. После обновления MCC необходимо снова подготовить
Core; иначе запуск отклоняется до миров. Это ограничение штатного дистрибутива,
а не защита от владельца компьютера, который способен менять JAR/исходники.

## Профили и нагрузка

Пресет `vanilla-throughput` сохраняет текущие distances, активацию/AI сущностей,
spawn, villagers, collisions, item merging и gamerules. Он выбирает VANILLA
redstone, сохраняет pathfinding updates и ограничивает incremental autosave
24 чанками за тик. 24 — также обычное значение upstream Paper; само по себе
оно не является новой оптимизацией MCC или доказанным выигрышем.
Интервал и полное сохранение при остановке не меняются. Custom не восстанавливает
управляемые значения и сохраняет пользовательские файлы. Пресет доступен для
Paper, Purpur и MCC Core; Spigot не имеет соответствующего Paper параметра.

На обычном Paper/Purpur engine resource остаётся на диске внутри MCC JAR: он
не распаковывается и не загружается в heap при открытии панели. Статус проверяет
только ZIP directory/size. Чтение 56 MB, SHA и распаковка выполняются только
после явного выбора Core; используется буфер 256 KiB. У Core нет дополнительной
полной копии world/chunk structures в памяти.

Параметры: [Paper world configuration](https://docs.papermc.io/paper/reference/world-configuration/),
[Paper global configuration](https://docs.papermc.io/paper/reference/global-configuration/).

## Подготовка и установка

Мастер первичной настройки предлагает Core. Сначала дистрибутив подготавливается;
фактическая замена происходит при штатном перезапуске, когда старая JVM завершена.

1. Проверяется MCC launcher v2: JVM, parent supervisor, run ID, ready и serverFile.
2. Из MCC JAR извлекается `mcc-core/engine-0.2.0.jar` в защищённый cache
   `plugins/MinecraftControlCenter/mcc-core/engine-0.2.0.jar`. Проверяются точный
   размер, SHA-256, source commit, patchset и main-thread execution contract.
   Сеть для подготовки Core не нужна; Paperclip при первом запуске может получить
   vanilla dependencies своим стандартным механизмом.
3. В foundation добавляются только bootstrap classes, metadata и license notices
   из MCC. Все foundation payload/provenance entries сохраняются побайтно.
   Исходный manifest хранится в `META-INF/mcc-upstream-manifest.mf`, основной
   `Main-Class` меняется на CoreBootstrap.
4. `staged/<UUID>/MCC-Core.jar` и `selection.json` связывают source server JAR,
   актуальный MCC JAR, foundation, готовый Core и run ID. Изменение любого
   связанного артефакта до установки отклоняет план.
5. После normal stop под offline launcher lease сохраняется проверенный старый
   server JAR и receipt; затем тот же serverFile заменяется атомарно. Миры и базы
   данных не заменяются. Backup: `mcc-core/backups/<UUID>/previous-server.jar`.
6. Managed Purpur profile metadata переводится в Paper configuration identity
   с точной отдельной копией `previous-kernel-profile.json` и receipt.
   Custom не переписывается. Отображаемое имя Core — MCC Core, config target — Paper.

Переход поддерживает Paper/Purpur 26.3 с подключённым MCC launcher. Для других
версий он недоступен; автоматической миграции миров или обновления Minecraft нет.

## Сборка и локальная упаковка

[core-engine/README.md](../core-engine/README.md) описывает восстановление patched
foundation из официального source pin. Полный build MCC проверяет foundation
и включает её ресурсом вместе с GPL и атрибуцией. Generated Minecraft sources,
JAR, worlds, auth databases и game assets не публикуются в source repository.

Для отдельной упаковки в новом test server directory положите проверенную
foundation как `engine-0.2.0.jar` и актуальный `plugins/MinecraftControlCenter.jar`:

```powershell
java -cp plugins/MinecraftControlCenter.jar dev.mcc.server.MccCoreInstaller `
  --server-root . --upstream-jar engine-0.2.0.jar --output MCC-Core.jar
```

Команда создаёт новый JAR; сервер не запускается, существующие файлы не заменяются.
Запуск через MCC supervisor использует `--server-jar MCC-Core.jar`. Ручной
`java -jar MCC-Core.jar --nogui --nojline` также запускает панель; для установки
переходов и кнопки штатного перезапуска необходим supervisor.

## Откат и проверки

После normal stop и подтверждённого завершения JVM проверить receipt и восстановить
только прежний server JAR с точным SHA. Если мигрировал managed Purpur profile,
восстановить `previous-kernel-profile.json`. Миры и базы данных не откатываются.
Для прежнего MCC нужен соответствующий prelaunch hook/supervisor contract.

17 engine tests проверяют NBT bytes/roundtrip, palette widths, Buffer encoding
и bounded/reentrant scratch pool. Runtime unit tests проверяют missing/mismatched
panel, provenance, payload/license/manifest preservation, plan/authority/launcher
ограничения. Native startup, real plugins, restart, graceful shutdown и workload
результаты должны подтверждаться отчётом текущей пары MCC/Core. Unit tests и
краткий smoke run не доказывают общую производительность.
