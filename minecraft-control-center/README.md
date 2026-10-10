# Minecraft Control Center 0.4 — Overworld

Полная новая визуальная система панели управления Minecraft: charcoal/stone surfaces, emerald accents, Monocraft с кириллицей, собственная 3D voxel-сцена с водой и порталом, Motion/GSAP transitions, адаптивная навигация и поиск Ctrl/Cmd+K. Сохранены 13 функциональных разделов и существующий Java/Paper backend, API, роли и подтверждения действий.

## Состав и восстановление

Это очищенный публичный снимок исходников, сборки и тестов. База пользователей, миры, конфигурация установленного сервера, credentials, журналы и сторонние Minecraft-изображения сюда не входят. Игровые текстуры восстанавливаются из собственного установленного клиента Minecraft 26.3; original JAR, world data и account database хранятся отдельно у владельца. Monocraft распространяется с SIL OFL 1.1 в `frontend/public/fonts/`.

Требуются JDK 25, Node.js 24, npm, Python 3 для извлечения игровых ресурсов. Gradle wrapper закреплён на 9.2.1. Paper API закреплён в `build.gradle`; native smoke выполнен на Paper 26.3 build 169.

```powershell
python scripts/Extract-Assets.py --client "$env:APPDATA/.minecraft/versions/26.3/26.3.jar"
npm ci --prefix frontend --ignore-scripts --no-audit --no-fund
npm run test --prefix frontend
npm run build --prefix frontend
.\gradlew.bat --no-daemon -PskipFrontend=true build
```

Для Linux используются свой путь к установленному клиенту и `./gradlew` вместо `.\gradlew.bat`. Linux/Folia native server run в рамках этого redesign не проверялись. При полной стандартной Gradle-сборке frontendInstall/frontendTest/frontendBuild запускаются автоматически; `-PskipFrontend=true` допустим только после явно выполненных npm test/build.

Результат: `build/libs/MinecraftControlCenter.jar`, OneJAR с backend, библиотеками и встроенным web frontend. Отдельный Node.js production process не нужен.

## Установка и обновление

На новом Paper-сервере поместите JAR в `plugins/`, запустите сервер и откройте loopback URL панели. Владелец создаётся с одноразовым приватным `plugins/MinecraftControlCenter/.setup-token`; shared-password bootstrap удалён в 0.4. Пароль владельца 12–128 символов. `auth.bootstrap-admin` сохранён только как совместимый legacy key и не создаёт встроенного пользователя; при обновлении существующая SQLite account database сохраняется.

Обновление требует штатной остановки Minecraft: сохраните текущий plugin JAR и закрытую резервную копию plugin data/config/database и миров, замените только JAR, затем запустите прежний launcher. Проверьте версию 0.4.0, вход существующего владельца, `/api/auth/status`, `/api/status`, live connection и нужные игровые разделы. Для отката штатно остановите сервер и верните предыдущий JAR; данные не удаляйте и не публикуйте.

## Проверки и границы

TypeScript/Vite build, 36 Node tests, 65 JUnit tests и OneJAR distribution verification прошли. Проверены 13 страниц × 1440/768/390 px, owner/viewer, login/error states, focus traps, Escape, command navigation, отмена подтверждения, reduced motion, offscreen pause и реальная потеря WebGL context. Сцена ограничена 30 renders/sec и DPR 1.5; это ограничения renderer, а не измеренный FPS на любом устройстве.

Скриншоты браузерной матрицы сняты с изолированными фиктивными API данными. Отдельный native smoke проверяет настоящий Paper, auth и WebSocket; изменения миров и длительный soak в рамках visual redesign не выполняются. Three.js загружается лениво; Vite сообщает о крупном Three chunk и служебных `use client` directives upstream dependencies. 21st.dev/Figma/Context7 connector auth не использовалась как зависимость этой реализации.

Не обещаны новые AI-функции, полный world backup, Linux/Folia compatibility или реализация всех исторических roadmap пунктов. Работа посвящена полностью новому интерфейсу существующей панели и безопасной первой настройке.

## Исправление мерцания травы

Блоки травы в русле уменьшены по высоте: нижняя грань совпадает с верхом земли, поверхность русла и вода сохраняют прежний уровень. Пересечения геометрии, вызывавшие z-fighting при анимации, устранены. Два regression tests проверяют все слои острова и границу русла; анимация проверена в браузере.
