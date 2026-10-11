# MCC 0.7 release verification

The current assembled JAR is `d2a70b19e13aa3c7be8b91758fd194591887a9458bf7d40d7474b40e4b24ca05`, 35,180,008 bytes. Its current isolated checks and earlier feature coverage are recorded separately below. This audit does not claim that the production update has already completed.

## Build and exclusive asset change

215 Java tests, 63 frontend Node tests and eight Python asset regressions passed without failures or skips. TypeScript typecheck, Vite production build and Gradle Shadow packaging passed. All 4,207 embedded frontend files match the production frontend bytes. Source compiles against the pinned official Spigot 26.3 API; public-method and GameRule probes also checked the actual Paper API. Four world-detail regressions preserve typed rules only when UUID, name and environment match.

An independent ZIP-entry comparison against `495c23eda90ab815ba9175492e9d9f71320cad9056861a68a944a5d76ea50643` found identical entry sets: 14,951 entries remain byte-identical, and only `web/game-assets/catalog.json` changes. Exactly four Ghast head coordinates change from 16 to 32. The PNG texture itself, backend classes, JavaScript and CSS bytes are unchanged. The old crop contained 256 transparent pixels; the corrected crop contains 1,024 opaque pixels. Tests cover the real alpha pixels and the constrained metadata change.

The retained ten map regressions cover unchanged FULL surfaces, changed geometry, removal, scan-time PNG coalescing, interrupted image writes and legacy manifests without the image-coherence marker. They verify functional persisted-cache behavior, rather than promising large-world throughput.

## Current browser and pixel review

The current JAR was opened through real navigation in the Codex in-app browser after a real requested restart and fresh login. All fourteen ordinary sections were inspected at 1280, 768 and 390 CSS pixels: Overview, Players, Worlds, Map, Entities, Events, Fair Play, Console, Chat, Automation, Backups, Plugins, Audit and Settings. The 42 settled observations have no page error box or document overflow, with main scroll position and header top at zero.

All 42 screenshots were independently viewed. Separate current screenshots show the Ghast face at all three widths, LuckPerms and AdvancedBanZ at all three widths, the restart transition and ready screens, and the initial Overview. These 54 images were actually inspected; no blocking visual defect remains in the captured states. Long fixture world identifiers can wrap between arbitrary characters, including a single-character last line, while retaining the complete identifier. Local horizontal scrolling in tables and automation controls is intentional.

The corrected white Ghast face and its facial details are visible on mobile, tablet and desktop. Current runtime requests revalidated the exact catalog bytes and the 32×32 head rectangle. LuckPerms displays both previously saved inert permission entries after restart; current inspection did not change them. AdvancedBanZ displays its actual title and an empty query for an unused synthetic identity; no sanction was created or revoked.

The portal restart transition and new ready screen are legible. A distinct ready boot and fresh login were observed. The prior child exit code was not sampled directly: exit-zero semantics are explicitly inferred from the completed requested-restart protocol and the unchanged reviewed supervisor branch that replaces the child only after normal exit zero. The current final native stop has its own directly recorded exit code zero.

Screenshots and detailed fixture receipts remain private review artifacts and are excluded from public source. Static captures do not certify smooth animation, absence of every transient flicker, keyboard focus order, every below-fold interaction or populated ore-monitor behavior.

## Current exact-JAR native scope

The distinct asset-patch run closed successfully on 2026-10-11 with two actual boots and seventeen recorded checkpoints. Both boots used Paper 26.3, supervisor protocol 2 and the existing preboot profile hook. OS samples bind the same daemon to distinct Java processes and creation times; the second boot is the actual browser-requested restart. Existing fixture credentials were preserved, and no new account or permission was created in this scope.

Before and after restart, the real AdvancedBanZ 4.0.1 API was available through canonical `advancedban` routing. Two targetless built-in moderation requests per boot returned HTTP 503 with the expected provider-disabled message; the built-in punishment list remained unchanged. MCC issued no provider sanction or revoke operation. Actual provider-file digests were unchanged around these queries; the public provider API can itself expire cached entries, which is why such housekeeping is recorded separately from explicit MCC mutations. LuckPerms 5.5.71 returned both existing native and browser-created inert permission entries.

Both boots retained Custom configuration content and returned 58 typed game rules in each of three worlds. Whole configuration bytes were not identical across startup, so byte-identical Paper startup is not claimed. The current proof distinguishes unchanged configuration content from exact byte equality.

Both current map checks finished a fresh scan: 138 rendered FULL chunks, 2,021 saved slots and 2,021 scanned records, image coherence version 1, a positive scan timestamp, zero pending tiles, failures and scan failures. The single background worker recorded zero Bukkit chunk loads and plugin tickets. Actual private 256×256 PNG data, ETag/304 caching, anonymous 401 and retired terrain/build endpoints 410 were checked. Final normal stop exited zero, owned processes disappeared and fixture ports closed.

## Historical coverage remains historical

The previous exact `495c23eda90ab815ba9175492e9d9f71320cad9056861a68a944a5d76ea50643` JAR completed its unchanged fifteen-checkpoint feature suite with four owned boots: role/CSRF checks, conflict-before-write, six private config backups, live distances, managed profile restoration, catalog installation of LuckPerms and real API/UI permission persistence. Its final map checkpoint recorded cached startup with 138 rendered chunks and **zero indexed slots**; that checkpoint alone does not establish a completed rescan. Its separately closed AdvancedBanZ addon did establish 138 FULL chunks among 2,021 fully scanned slots, provider routing and built-in suppression. Those raw receipts keep their original JAR hash.

That previous candidate's pixel review found a blank Ghast caused by the transparent crop. Its rejected visual-candidate status and preliminary focus-scroll capture artifacts remain preserved. They are not relabeled as passing current screenshots. The current two-boot asset scope supplements the immutable earlier feature suite through the independently proven exclusive catalog delta; it does not claim a rerun of all fifteen historical checkpoints on the current JAR.

Earlier `0517544f0c082e572bee0d02cdafe68da1c14013543a6bbcf8102298aa0cb744` map-patch and `96ce4a2eb239bf0ee45e308169ae2ecb03cecf215da56137b149fa9a0c6dfe9a` release receipts remain separate history. The map regressions address repeated PNG encoding and interrupted cache coherence revealed during that work.

## Limits

Actual native execution here covers Paper 26.3, LuckPerms 5.5.71 and AdvancedBanZ 4.0.1. Other Bukkit forks and optional provider versions are not runtime-certified. CoreProtect has bounded read-only operations and contract tests; Grim readings also have contract tests. Actual AdvancedBanZ query/routing/suppression checks do not certify native create/revoke of sanctions. Enabled unsupported or competing punishment providers fail closed for built-in enforcement; existing histories and native bans are not automatically migrated.

Ore-rate colors are conservative observations, not proof of cheating or automatic bans. This is functional release verification, not a formal WCAG audit, many-player benchmark, large-world throughput guarantee or long JVM soak. Whole JVM memory includes the kernel and worlds. The documented Microsoft G1 mitigation addresses a specific vendor path and does not guarantee that every future crash is prevented.
