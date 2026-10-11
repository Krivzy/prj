# MCC 0.7 release verification

The assembled release JAR is `96ce4a2eb239bf0ee45e308169ae2ecb03cecf215da56137b149fa9a0c6dfe9a`, 35,171,647 bytes. Verification below concerns this exact build; earlier fixture runs are retained separately and are not substituted for release evidence.

## Build and API boundaries

195 Java tests passed with zero failures, errors or skips. All 63 frontend Node tests, TypeScript typecheck, Vite production build and Gradle Shadow packaging passed. All 4,207 embedded frontend files match the production frontend bytes. The source compiles against the official pinned Spigot 26.3 API. A public-method audit checked 299 Bukkit method descriptors against the actual Paper runtime API. Separate GameRule public-method probes passed against both exact APIs; this covers their interface/class binary difference.

The world page combines live counters with detailed rules only for the same UUID, name and environment. Four regressions cover this join. Actual Paper world responses contain 58 typed rules per world; the browser retained these rules at all three tested widths.

## Actual browser inspection

The running isolated release was inspected through the Codex in-app browser, using real navigation and settled loading states. All fourteen ordinary sections were opened at 1280, 768 and 390 CSS pixels: Overview, Players, Worlds, Map, Entities, Events, Fair Play, Console, Chat, Automation, Backups, Plugins, Audit and Settings. All 42 observations have a visible page heading, no page error box and no document overflow.

All 42 saved page screenshots and the mobile preset review dialog were independently inspected. There were no blocking visual defects. The preset dialog now has the common 20px inner padding and fits the 390px viewport. Very long fixture world IDs can wrap between characters, including a one-character last line; full identifiers are retained. Initial desktop views included a transient disconnected indicator; later views connected. The map screenshot captured a render in progress and is not evidence of final whole-world coverage.

LuckPerms appeared only after the real plugin became enabled. A harmless permission was saved through the embedded editor in an isolated test group, alongside a separately saved native API permission. The editor was inspected at all three widths. A real restart was then requested through the Overview button: the portal animation appeared during shutdown/startup, the ready screen required a new ready boot, and a fresh login was required. Both permission entries remained visible after the restart.

Screenshots and detailed machine/world receipts are private local review artifacts and are deliberately excluded from this public source snapshot.

## Native release run

The isolated release run closed successfully on 2026-10-11 with fifteen recorded checkpoints and no errors. Actual Paper 26.3 build 169 exercised current-session role/CSRF checks, a stale-config conflict before any write, six exact private config backups, live distances in all three worlds, and managed 8/6 distances restored before JVM startup after manual 11/9 drift. The catalog downloaded and installed the declared-compatible LuckPerms 5.5.71; real API save/revision/readback and the browser-created permission both survived normal restarts.

Custom retained manually selected 5/4 values in server.properties through restart; existing Spigot per-world 8/6 overrides still controlled actual world distances. Full server.properties bytes differed across restart, so byte-identical whole-kernel startup is not claimed; the exact field-level cause was not preserved in this receipt. A separate closed offline MCC Custom hook under the exclusive lease left all six configuration files byte-identical. These are distinct observations.

The completed map contained all 138 saved FULL chunks among 2,021 slots, with zero pending tiles and failures. The single background worker recorded zero Bukkit chunk loads and plugin tickets. An independent authenticated PNG probe confirmed actual 256px PNG data, private ETag/304 caching, anonymous 401 and retired terrain/build endpoints 410. The final owned normal stop exited zero and both fixture ports closed. Actual JVM samples confirmed protocol 2, parent/PID identity, preboot profile hooks and completed requested restarts.

## Scope of claims

Native Paper and LuckPerms checks do not certify every Spigot/Purpur fork or optional provider version. CoreProtect, AdvancedBan and Grim adapters have public-contract tests; their particular server/plugin combinations require separate native validation. CoreProtect is read-only; Grim readings and ore-rate colors do not establish cheating. AdvancedBan suppresses MCC native moderation enforcement while enabled; existing histories and native bans are not automatically migrated.

This is a functional release verification, not a formal WCAG audit, many-player gameplay benchmark, large-world benchmark or long JVM stability soak. Whole JVM RAM includes the kernel and worlds. The documented Microsoft G1 mitigation addresses a specific vendor crash path without promising that every future crash is prevented.
