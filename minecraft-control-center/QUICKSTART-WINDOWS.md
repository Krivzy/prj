# Быстрый запуск MCC 0.11 в Windows

Основной сервер временно выключен по просьбе пользователя. Свежие изолированные Windows проверки прошли на Paper (14 checks, включая Paper→Core и refresh прежнего binding) и Purpur (семь ordinary lifecycle checks); все test processes закрыты. Этот документ описывает переносимый запуск новой версии; offline обновление Main подтверждено: заменены MCC JAR и два локальных Start/Stop scripts, остальные 11 222 файла сохранены побайтно. Main не запускался; следующий boot и обновление его Core binding выполняются отдельно по распоряжению пользователя. [Фактический статус 0.11](docs/MCC-0.11-RELEASE.md).

## Подготовка

Нужны Java 25, уже подготовленное совместимое ядро, принятая владельцем Minecraft EULA и один проверенный `MinecraftControlCenter.jar`. Поместите MCC в `plugins/MinecraftControlCenter.jar`; в корне сервера должны находиться настоящий kernel JAR и `eula.txt` с `eula=true`. Launcher не принимает EULA за пользователя. Перед миграцией со старого Python launcher штатно остановите и parent, и Minecraft, подтвердите отсутствие процессов и сохраните закрытую копию JAR/config/пользовательских данных.

## Управляемый запуск

В Windows PowerShell **из каталога сервера**:

```powershell
java -Xms32M -Xmx96M -jar plugins/MinecraftControlCenter.jar --server-root . --server-jar paper.jar --memory-mib 1536 --kernel-name Paper --minecraft-version 26.3
```

Команда оставляет launcher в текущем терминале. Для Purpur укажите его filename/`--kernel-name Purpur`; для существующего Core — настоящий server filename и `--kernel-name MCCCore`. Parent использует heap 32–96 MiB, child Minecraft получает свой heap; это не RSS граница всех процессов. Если установка требует отдельно проверенных JVM flags, сохраните их и передайте child через повторяемый `--jvm-arg <argument>`. [Embedded launcher и ограничения](docs/EMBEDDED-LAUNCHER.md), [известная G1 сигнатура и применимость flags](docs/JVM-G1-MITIGATION.md).

Если используется checkout scripts, `scripts/Start-Server.ps1` запускает этот же MCC JAR; `-Direct` оставляет kernel под управлением хостинга. Обычная установка MCC JAR в `plugins/` и прямой запуск kernel поднимают панель, но managed restart требует parent либо restart сервиса хостинга. Отдельный Python/launcher JAR для нового пути не нужен. Хостинг, уничтожающий всё process tree, не позволяет parent пережить stop.

## Статус, restart и normal stop

В другом PowerShell окне **из того же каталога сервера** выберите только нужную команду; не выполняйте весь блок подряд:

```powershell
java -jar plugins/MinecraftControlCenter.jar --status --server-root .
java -jar plugins/MinecraftControlCenter.jar --restart --server-root .
java -jar plugins/MinecraftControlCenter.jar --stop --server-root .
```

Выполняйте только нужную команду, не весь блок сразу. `--restart` принимает запрос stop/save/new boot, а не мгновенно подтверждает завершение. Дождитесь `state: running`, kernel/API readiness и нового run ID. `--stop` терминален: нужно дождаться normal exit и завершения parent/child перед заменой MCC JAR. Не используйте force kill или `/reload` как обновление.

## Первый вход и порт

Новая установка использует `http://localhost:8123`; явно настроенный Main `8080` и hosting allocation сохраняются. MCC/Dynmap не могут одновременно слушать один адрес/порт. Для удалённого сервера сначала настройте `web.public-origin` и HTTPS; MCC не угадывает внешний hostname. [Hosting port](docs/HOSTING-PORT.md), [security](SECURITY.md).

Default admin/admin в MCC 0.10/0.11 не создаётся. Войдите в Minecraft настоящим OP или игроком с `mcc.setup`, получите личную ссылку `/mcc setup` и оставайтесь онлайн до завершения мастера. В браузере подтвердите свой сильный пароль, preset и режим панели. [Onboarding](docs/ONBOARDING.md).

## Обновление и откат

После полного normal stop обоих процессов сохраните проверенную закрытую копию предыдущего MCC JAR, configuration и текущих data/worlds. Замените MCC JAR и используйте соответствующий новый startup; для уже установленного подтверждённого Core offline hook перед следующим управляемым boot обновит binding с backup прежней пары. Stale pending plan новой установки нужно подготовить заново. [Core update/rollback](docs/MCC-CORE.md).

Подтверждённое offline обновление Main заменило только MCC JAR и его локальные Start/Stop scripts после проверенной закрытой резервной копии. Оно не выполнило boot/refresh Core. Пользователь запускает Main отдельно; прежние installed Core и selection остаются до следующего управляемого boot. Точные подтверждения backup/deployment приведены в [релизе 0.11](docs/MCC-0.11-RELEASE.md). Локальные пути, credentials, worlds и receipts остаются закрытыми; для восстановления source нужны [README](README.md) и проверенный GitHub commit, runtime данные — из своих закрытых backups.

При rollback после normal stop верните согласованные прежние MCC/Core JAR и selection из проверенной копии; worlds/auth/config не откатывайте автоматически поверх новых записей. Команда управления может ждать занятую очередь до двух секунд; её принятие проверяйте по status, без автоматической повторной отправки. Исторический редактор карты и публичный default login прежних 0.3 версий не являются инструкцией для текущей установки. [Тесты и границы 0.11](docs/TESTS-0.11.md), [current limitations](LIMITATIONS.md).
