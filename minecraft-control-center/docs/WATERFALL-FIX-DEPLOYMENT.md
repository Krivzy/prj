# Waterfall outlet fix: verified installation

Verified at 2026-10-10T21:03:03.712Z (UTC; 2026-10-11 Europe/Moscow).

Two decorative solid voxels at the waterfall outlet were removed; water now has an unobstructed path. Other terrain cells and the internal riverbed remain unchanged. Source: [d329cc494df79af03c7a9d1ee2c1a8d3a1227529](https://github.com/Krivzy/prj/commit/d329cc494df79af03c7a9d1ee2c1a8d3a1227529).

## Installation

The owner explicitly authorized this previously deferred restart. Two API checks reported zero online players. Paper saved and stopped normally with exit code 0; original listeners and processes exited before replacement. An access-restricted offline backup of plugins, configuration and three worlds was verified file by file using SHA-256; SQLite integrity was `ok`. Only the MCC JAR was replaced, then the existing launcher started with its existing memory options.

- Installed MCC version: 0.4.0.
- Installed JAR SHA-256: `30fb8ceb3c02e5abfb01dd4f72e4f3ec1f8492a4d72d5e248c06aa02d3d60513`.
- Previous JAR SHA-256: `9ebf68d7847761dfb1097ed67f3459113bea0cd8038d0a33c9b3c16535755530`.
- Existing Owner account and all three worlds remained available.
- HTTP8080 and Minecraft25565 remain loopback-only.

## Verification

The installed JAR, served index and font bytes matched the prepared artifact. Native plugin state, authenticated REST, an actual Edge browser, loaded Monocraft, WebGL (34 draw calls, 5506 triangles), the application's live connection and cookie-authenticated WebSocket hello/pong passed. Browser, console and HTTP errors: zero. Verification performed no gameplay mutations. The open application browser was refreshed and existing sign-in restored.

The prepared source previously passed 37 Node tests, 65 JUnit tests, TypeScript/Vite/Gradle builds and native exact-artifact smoke. Those source tests were not repeated solely for installation.

## Recovery

Use the source commit above and the build/asset extraction instructions in [README](../README.md). Runtime data and credentials are restored from the owner's private backups. For a JAR rollback, recheck the current run and players, save and stop normally, preserve the current state, restore only the previous JAR, and start the launcher. Keep the current DB, configuration and worlds; do not rewind them as part of this geometry correction. Rollback was not executed.
