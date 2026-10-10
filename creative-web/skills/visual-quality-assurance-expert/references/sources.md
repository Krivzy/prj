# Official capture sources

Проверены **2026-10-10**. Artistic review rubric — авторская рабочая методика; источники ниже подтверждают способ capture/emulation, а не субъективные эстетические оценки.

| Источник | Область подтверждения |
| --- | --- |
| [Playwright Visual comparisons](https://playwright.dev/docs/test-snapshots) | Capture/baseline comparison and rendering environment |
| [Playwright PageAssertions](https://playwright.dev/docs/api/class-pageassertions) | Stable screenshot assertions and disabled animations |
| [Playwright Emulation](https://playwright.dev/docs/emulation) | Viewport/devices/theme/reduced motion |
| [Playwright Web server](https://playwright.dev/docs/test-webserver) | Launch/readiness of existing project |
| [Playwright Locators](https://playwright.dev/docs/locators) | UI interaction targets |
| [MDN prefers-reduced-motion](https://developer.mozilla.org/en-US/docs/Web/CSS/@media/prefers-reduced-motion) | Browser media preference behaviour |

Версию tooling и реальное image rendering проверяй для каждого проекта. Skill не устанавливает browser/tool автоматически и не дублирует performance/accessibility skills.
