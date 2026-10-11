# MCC0.7: установка и откат

Один `MinecraftControlCenter.jar` содержит web frontend и backend. Обновление сохраняет существующие аккаунты, миры и историю. `/reload`, принудительное завершение Java и динамическая загрузка сторонних JAR не используются.

Перед заменой выполните нормальный `stop`, дождитесь завершения Java и освобождения портов. Сохраните предыдущий JAR, launcher, startup scripts и закрытую согласованную копию миров/конфигураций/MCC SQLite; проверьте хешы source/copy и SQLite integrity. Эта копия может содержать секреты и не предназначена для GitHub. После этого замените JAR и запустите сервер.

## Штатный restart из панели

Подключите [launcher v2](SERVER-SUPERVISOR.md) из проверенного снимка исходников. Python нужен только для launcher, а Node.js — только для сборки frontend. Пример параметров, где пути задаёт оператор:

```text
python -B scripts/Server-Supervisor.py --java <absolute-java-executable> --server-root <absolute-server-root> --server-jar paper.jar --memory-mib 1536 --min-memory-mib 512 --run-id <new-canonical-UUID> --kernel-name Paper --minecraft-version 26.3
```

Используйте фактическое имя ядра и точную Minecraft version. Для конкретного Microsoft OpenJDK с подтверждённой G1-сигнатурой существуют два [документированных флага](JVM-G1-MITIGATION.md), передаваемые как `--jvm-arg=-XX:+UnlockDiagnosticVMOptions` и `--jvm-arg=-XX:-G1UseTimeBasedHeapSizing`. Они не добавляются скрытно во все Java distributions. На Windows фоновый запуск через `Start-Process` должен использовать `-WindowStyle Hidden`.

Launcher удерживает exclusive lease, выполняет offline profile hook до каждого Java boot и передаёт MCC protocol identity. Кнопка в обзоре доступна Owner при подтверждённой принадлежности процессу. Запрос запускает только normal stop/save/start; новое ядро получает новый runId/bootId. Анимация завершается после фактического ready нового процесса. Авторизация предыдущего процесса заканчивается и требуется новый вход.

После unexpected exit recovery ограничено тремя попытками за10минут с задержками2/10/30секунд. Обычный terminal stop не запускает recovery. При crash loop, ошибке hook/ownership или сохранения launcher останавливает автоматизацию и сохраняет диагностику; он не убивает зависший Minecraft. Гарантия отсутствия всех будущих крашей не заявляется.

## Проверка и откат

Проверьте version0.7 в панели, фактическое имя ядра, API readiness, прежние UUID миров, initial-password restrictions, карту/обычные вкладки, профили и owner permissions. Optional integrations отображаются только при реально включённом совместимом плагине. Не устанавливайте неподдерживаемый optional JAR только ради появления вкладки.

Для отката после normal stop верните прежний JAR и его launcher/startup scripts; сохраняйте текущие миры и историю. Обновление0.7 не выполняет необратимую миграцию SQLite. Если управляемый preset менял ядро, переключитесь в свои файлы либо отдельно восстановите проверенные configuration backups при выключенном сервере. Не перематывайте миры на старую резервную копию вместе с откатом интерфейса.
