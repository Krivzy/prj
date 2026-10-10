# Зависимости и ресурсы

MCC поставляет runtime-зависимости внутри одного JAR. Paper/Minecraft не включены в MCC JAR; их устанавливают отдельно на сервере.

| Зависимость | Назначение | Лицензия / официальный источник |
| --- | --- | --- |
| [Undertow](https://github.com/undertow-io/undertow) | HTTP и WebSocket | Apache-2.0 |
| [XNIO](https://github.com/xnio/xnio) | Неблокирующий network transport | Apache-2.0 |
| [JBoss Logging](https://github.com/jboss-logging/jboss-logging) | Логирование Undertow | Apache-2.0 |
| [JBoss Threads](https://github.com/jbossas/jboss-threads) | Worker transport | Apache-2.0 |
| [WildFly Common](https://github.com/wildfly/wildfly-common) | Утилиты network transport | Apache-2.0 |
| [SmallRye Common](https://github.com/smallrye/smallrye-common) | Утилиты runtime | Apache-2.0 |
| [Jackson](https://github.com/FasterXML/jackson-databind) | JSON | Apache-2.0 |
| [SQLite JDBC](https://github.com/xerial/sqlite-jdbc) | SQLite database | Apache-2.0; SQLite core — public domain |
| [Bouncy Castle](https://www.bouncycastle.org/licence.html) | Argon2id | Bouncy Castle License, текст лицензии в upstream |
| [Paper API](https://github.com/PaperMC/Paper) | Игровой API, compile-only | GPL-3.0 / upstream notices; не включён в MCC JAR |
| [Gradle](https://github.com/gradle/gradle) | Build wrapper | Apache-2.0 |
| [Shadow](https://github.com/GradleUp/shadow) | One-JAR packaging | Apache-2.0 |
| [JUnit](https://github.com/junit-team/junit5) | Только тестирование | EPL-2.0; не включён в MCC JAR |

Лицензионные metadata runtime-библиотек сохраняются в JAR по возможности; подписи исходных зависимостей исключаются, потому что сборка перенесла часть packages и исходные подписи больше не описывают результирующий архив. Это не изменение проверки TLS или загрузчика Minecraft.

Полный список frontend-библиотек и их версий находится в `frontend/package-lock.json`; их лицензии содержатся в установленных npm packages. Перед коммерческой перепубликацией дистрибутива проверьте применимые уведомления в upstream sources.

MCC не содержит Minecraft client/server JAR или копий пользовательских миров. Установка Paper регулируется соответствующими лицензиями и [Minecraft EULA](https://www.minecraft.net/eula).
