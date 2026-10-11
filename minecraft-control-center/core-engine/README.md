# MCC Core engine 0.2

Это настоящий набор изменений исходников Paper 26.3, commit
`46d4834e8e2ec4af9dcfa028076fb16dfb8a3911`. Папка содержит только небольшой
patch, собственные Java helpers, тесты, сборочные скрипты и license notices.
Сгенерированные исходники Minecraft и JAR остаются в исключённых из Git
каталогах `.work`, `build` и `generated` основного проекта.

Сохраняются обычный Paper game tick, AI, redstone, фермы, расстояния обзора
и симуляции, Bukkit/Paper API. Генерация и IO чанков остаются на штатных worker
потоках Paper. MCC не переносит Bukkit callbacks, сущности или world tick
на посторонние потоки.

Изменения движка:

- Scratch BitSet генерации руды: один буфер на worker, до 65 536 бит / 8 KiB;
  вложенные и крупные запросы получают отдельный буфер; release выполняется в `finally`.
- NBT: запись entries без второго hash lookup; fastutil maps используют reusable iterator.
- Long arrays: bulk encoding для больших contiguous buffers; composite, read-only и
  недостаточная capacity сохраняют скалярное поведение, включая исключения.
- Chunk packets: EnumMap вместо stream/hash table для heightmaps; snapshot arrays
  копируются, путь oversized block entities сохранён.
- Palette storage: удалены два дублирующих `long` поля; unsigned арифметика сохранена.

17 поведенческих тестов проверяют эти контракты. Direct OreFeature oracle генерируется
локально из полного неизменённого upstream source с проверкой SHA; 45 комбинаций
seed/vein size/air exposure сравнивают точные blockstates, порядок writes и RNG,
дополнительно проверяются nested actual placement и освобождение после exception. Они не доказывают превосходство
над Purpur/Pufferfish, полную ванильность Paper или совместимость каждого плагина.
Native lifecycle и workload benchmark — отдельные проверки. В исследованных
официальных ветках Pufferfish отсутствует 26.3; сравнение его 1.21.10 с 26.3
не является честным сравнением одной версии.

## Сборка из корня проекта MCC

Нужны Git, Python 3.10+ и JDK 25. Установите `JAVA_HOME`, затем выполните:

```powershell
python core-engine/build-engine.py --checkout core-engine/.work/paper --output generated/core-engine/MCC-Core-engine-0.2.0-26.3.jar
```

Builder создаёт отдельный checkout точного официального commit, накладывает
patch и собственный overlay, запускает 17 тестов и создаёт Paperclip. Он
ограничивает Gradle двумя workers и 2 GiB, не сбрасывает чужой checkout, не
запускает и не заменяет Minecraft сервер. На Windows long paths и техническая
Git identity применяются только к этому build process; глобальные настройки Git
не меняются. Дополнительный `--gradle-home` позволяет указать Gradle cache.

`finalize-engine.py` сохраняет каждый исходный Paperclip entry побайтно и добавляет
`META-INF/mcc-engine.properties`. JSON receipt рядом с JAR фиксирует исходники,
patchset, SHA-256 и результаты тестов. `verify-foundation.py <raw-paperclip.jar>
<foundation.jar>` независимо проверяет сохранность всех payload и license entries.

После этого обычный build MCC включает foundation как ресурс
`mcc-core/engine-0.2.0.jar`; `verifyCoreFoundation` и `verifyDistribution` проверяют
его точные размер, SHA-256, source commit и patchset. Текущий release pin:

- 55 980 330 байт;
- SHA-256 `86c96263ba9a6257cc8b03e8e8fd8f90ee03fedb396918ed1fa1f74ebe196223`;
- patchset SHA-256 `3d42ace4d8f3a14bb7765cb173da0bcd06aa7a6533bd33e4e8892a50d0ca3c6f`.

В проверенном повторном build на тех же inputs получен тот же SHA. Полностью
независимая сборка на другом компьютере пока не проверена. Если dependency/build
inputs дают иной hash, не обходите проверку: повторите source/native проверку и
согласованно обновите release pins в `CoreBootstrap.java` и `build.gradle`.
Сходное имя JAR или только provenance metadata не заменяет проверки SHA-256.

Foundation содержит настоящий patched engine. Серверный MCC Core JAR дополнительно
содержит CoreBootstrap/CoreBridge, проверяет обязательную панель до открытия миров
и штатно сохраняет/останавливает сервер при отказе панели. Установка, pairing и
откат описаны в [MCC-CORE.md](../docs/MCC-CORE.md).

Лицензия движка: [GPL-3.0](LICENSE.md). Атрибуция: [THIRD-PARTY.md](THIRD-PARTY.md).

Локальные counterbalanced hotpath measurements и ограничения:
[PERFORMANCE.md](PERFORMANCE.md), [raw samples](PERFORMANCE-SAMPLE.json).
