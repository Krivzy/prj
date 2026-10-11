# Обход известной ошибки G1 в Microsoft OpenJDK

Microsoft описал сбой `g1HeapRegionManager.cpp:55` с сообщением `master free list MT safety protocol at a safepoint` в своей реализации time-based heap sizing. Периодическое уменьшение heap может нарушить синхронизацию списка свободных регионов. Точная сигнатура и рекомендуемый обход опубликованы в [Microsoft OpenJDK issue #677](https://github.com/microsoft/openjdk/issues/677).

Для существующего Microsoft OpenJDK 25 Microsoft рекомендует два аргумента перед `-jar`:

```text
-XX:+UnlockDiagnosticVMOptions -XX:-G1UseTimeBasedHeapSizing
```

Они отключают эту дополнительную функцию, сохраняя G1. См. также [Microsoft release notes](https://learn.microsoft.com/en-us/java/openjdk/release-notes#openjdk-25). В сообщении об ошибке не указана конкретная выпущенная версия с исправлением; закрытый issue сам по себе не подтверждает, что используемый runtime исправлен.

## Что проверено

На Microsoft OpenJDK 25.0.1+8 выполнен отдельный probe без запуска Minecraft-сервера:

```text
java -XX:+UnlockDiagnosticVMOptions -XX:-G1UseTimeBasedHeapSizing -XX:+PrintFlagsFinal -version
```

Процесс завершился с exit code 0. `PrintFlagsFinal` показал `G1UseTimeBasedHeapSizing=false` и `UseG1GC=true`. До отключения функция имела значение `true`. Это подтверждает принятие параметров и состояние флагов.

Короткий native smoke сервера с теми же аргументами проверяет запуск и работу панели. Его результат сохраняется отдельно от standalone probe; даже успешный smoke не является длительным JVM soak test. Эти проверки не дают гарантии отсутствия всех будущих сбоев или снижения CPU. Данная сигнатура также не доказывает нехватку памяти.

## Применение и откат

Сохраните прежнюю команду запуска и добавьте оба аргумента в существующий launcher. Менять настройки работающего сервера следует в согласованное окно после штатной остановки; при аварийном завершении сначала подтвердите отсутствие прежних процессов и сохраните offline-копию данных, включая SQLite sidecars.

Обход сохраняет прежний runtime и сборщик мусора. Для отката удалите только эти два аргумента после остановки сервера. Указанные параметры специфичны для Microsoft build; перед использованием другого поставщика JVM проверьте его документацию и принятие флагов.
