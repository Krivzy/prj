# MCC 0.7 map-patch release verification

The assembled release JAR is `0517544f0c082e572bee0d02cdafe68da1c14013543a6bbcf8102298aa0cb744`, 35,173,038 bytes. Verification below concerns this exact build. Earlier release observations are retained separately and are not substituted for current evidence.

## Build and API boundaries

205 Java tests passed with zero failures, errors or skips. All 63 frontend Node tests, TypeScript typecheck, Vite production build and Gradle Shadow packaging passed. All 4,207 embedded frontend files match the production frontend bytes. The source compiles against the official pinned Spigot 26.3 API. A public-method audit checked 299 Bukkit method descriptors against the actual Paper runtime API. Separate GameRule public-method probes passed against both exact APIs; this covers their interface/class binary difference.

The world page combines live counters with detailed rules only for the same UUID, name and environment. Four regressions cover this join. Actual Paper world responses contain 58 typed rules per world; the browser retained these rules at all three tested widths.

Ten additional map regressions cover unchanged FULL surface/stamp handling, changed geometry, removal, scan-time PNG coalescing, recovery after interrupted base/parent/removal writes, and legacy manifests without the current image-coherence marker. Unchanged decoded surfaces no longer cause repeated PNG encoding. These are functional and persisted-cache checks, rather than a guaranteed large-world throughput measurement.

## Actual browser inspection

The running isolated release was inspected through the Codex in-app browser, using real navigation and settled loading states. All fourteen ordinary sections were opened at 1280, 768 and 390 CSS pixels: Overview, Players, Worlds, Map, Entities, Events, Fair Play, Console, Chat, Automation, Backups, Plugins, Audit and Settings. All 42 observations have a visible page heading, no page error box and no document overflow.

All 42 saved page screenshots and the mobile preset review dialog were independently inspected. There were no blocking visual defects. The preset dialog has the common 20px inner padding and fits the 390px viewport. Very long fixture world IDs can wrap between characters, including a one-character last line; full identifiers are retained. The initial desktop map screenshot captured a render in progress and is not evidence of final whole-world coverage. Subsequent map views show terrain; completed coverage is established by the separate native receipt.

LuckPerms appeared after the real plugin became enabled. A harmless permission was saved through the embedded editor in an isolated test group, alongside a separately saved native API permission. The editor was inspected at all three CSS widths. Seven additional interaction screenshots were independently accepted. Two initial desktop/tablet captures contained a narrow capture artifact and remain uncertified; their full-width replacements were viewed on a separate, normally closed browser-only fixture with the same exact JAR. These recaptures supplement pixel coverage without replacing or relabeling the completed original native run.

A real restart was requested through the Overview button. The portal loading screen and the new ready screen were viewed at desktop/mobile widths. The mobile heading and actions fit the stable 390×844 viewport. A new ready boot and fresh login were required; both permission entries remained visible after the restart. Exit-zero semantics for that UI restart are inferred from the observed completed requested-restart protocol and the reviewed supervisor branch that starts a replacement only after exit zero. Its transient exit-code field was not independently sampled by the browser. The native receipt separately records the final normal stop's actual exit code zero.

Screenshots and detailed machine/world receipts are private local review artifacts and are excluded from the public source snapshot. Static captures do not certify animation smoothness, flicker, focus order, full below-fold interaction or populated ore-monitor behavior.

## Native release run

The new isolated exact-JAR release run closed successfully on 2026-10-11 with fifteen recorded checkpoints and no errors. Actual Paper 26.3 build 169 exercised current-session role/CSRF checks, a stale-config conflict before any write, six exact private config backups, live distances in all three worlds, and managed 8/6 distances restored before JVM startup after manual 11/9 drift. The catalog downloaded and installed the declared-compatible LuckPerms 5.5.71; real API save/revision/readback and the browser-created permission both survived normal restarts.

Custom retained manually selected 5/4 values in server.properties through restart; existing Spigot per-world 8/6 overrides still controlled actual world distances. Full server.properties bytes differed across restart, so byte-identical whole-kernel startup is not claimed; the exact field-level cause was not preserved in this receipt. A separate closed offline MCC Custom hook under the exclusive lease left all six configuration files byte-identical. These are distinct observations.

The completed map contained all 138 saved FULL chunks among 2,021 slots, with zero pending tiles and failures. The single background worker recorded zero Bukkit chunk loads and plugin tickets. An independent authenticated PNG probe confirmed actual 256px PNG data, private ETag/304 caching, anonymous 401 and retired terrain/build endpoints 410. The final owned normal stop exited zero and both fixture ports closed. Actual JVM samples confirmed protocol 2, parent/PID identity, preboot profile hooks and completed requested restarts.

## Earlier evidence and limits

The previous `96ce4a2eb239bf0ee45e308169ae2ecb03cecf215da56137b149fa9a0c6dfe9a` release had its own 195-test build, isolated fifteen-checkpoint run and browser/pixel review. Those receipts remain historical evidence for that JAR. They are not relabeled as checks of this map-patch build. A later production map deadline exposed repeated encoding of unchanged cached surfaces; the new map regressions and fresh complete native run above validate the resulting correction. This public audit describes isolated verification and does not claim that a production update has already succeeded.

Native Paper and LuckPerms checks do not certify every Spigot/Purpur fork or optional provider version. CoreProtect, AdvancedBan and Grim adapters have public-contract tests; their particular server/plugin combinations require separate native validation. CoreProtect is read-only; Grim readings and ore-rate colors do not establish cheating. AdvancedBan suppresses MCC native moderation enforcement while enabled; existing histories and native bans are not automatically migrated.

This is a functional release verification, not a formal WCAG audit, many-player gameplay benchmark, large-world benchmark or long JVM stability soak. Whole JVM RAM includes the kernel and worlds. The documented Microsoft G1 mitigation addresses a specific vendor crash path without promising that every future crash is prevented.
