# Screenshots, visual regression and responsive evidence

## Screenshot discipline

Укажи browser/version, ОС, viewport/DPR, locale/timezone, theme, data fixture и build. Fonts и images должны быть готовы в проверяемой области, loading завершён по application signal. Fixed timeout и `networkidle` не являются надёжным состоянием для приложения с realtime/analytics. Full-page screenshot помогает изучить вертикальный ритм; viewport/locator capture точнее для отдельных states.

Snapshot comparison зависит от rendering environment. Baselines создаются и сравниваются в одинаковой ОС/browser/font setup, с закреплёнными data. Не обновляй весь baseline, чтобы «починить» diff: изучи actual/expected/diff, исправь причину и отдельно прими intended change. Mask только действительно variable region; маскирование целого feature или высокий tolerance прячет defects. [Visual comparisons](https://playwright.dev/docs/test-snapshots)

```ts
await page.goto('/dashboard');
await expect(page.getByTestId('dashboard-ready')).toBeVisible();
await page.evaluate(() => document.fonts.ready.then(() => undefined));
await expect(page).toHaveScreenshot('dashboard-desktop.png', {
  animations: 'disabled',
  fullPage: true,
});
```

`toHaveScreenshot` работает с Playwright Test runner и ждёт последовательные стабильные captures. `animations: 'disabled'` управляет CSS/Web Animations; continuous RAF, canvas, GSAP и shader time требуют контролируемого application test mode. Их visual quality проверяй также в нормальном режиме. [PageAssertions](https://playwright.dev/docs/api/class-pageassertions)

## Матрица viewport и state

Начальный sample для новой страницы: 390×844 (mobile), 768×1024 (tablet), 1440×900 (desktop), а также ширины непосредственно вокруг изменённого breakpoint. Добавь 320px/landscape/wide только когда layout/content требуют этого. Проверяй каждый meaningful state: closed/open menu, modal, form error, long content, empty/loading, theme. Mobile `hasTouch/isMobile` и DPR учитывай device profile; изменение viewport само по себе не включает touch. [Emulation](https://playwright.dev/docs/emulation)

```ts
for (const width of [390, 768, 1440]) {
  test(`layout fits viewport ${width}`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 });
    await page.goto('/');
    await expect(page.getByRole('main')).toBeVisible();
    const overflow = await page.evaluate(() =>
      document.documentElement.scrollWidth - document.documentElement.clientWidth);
    expect(overflow).toBeLessThanOrEqual(1);
  });
}
```

Viewport overflow signal не доказывает качество: скрытый `overflow-x:hidden` способен спрятать clipped control. Проверяй реальные edge elements и contents scroll region на screenshot. Hover проверяй с mouse profile, mobile flow без hover. Для reduced motion используй `page.emulateMedia({ reducedMotion: 'reduce' })`, затем verify visible end states и доступность controls.

## Reusable local inspection script

`scripts/inspect-local.cjs` использует `playwright` либо `@playwright/test` **из переданного project root**, ничего не устанавливает. Принимает только loopback HTTP(S) URL без credentials; создаёт fresh browser contexts и private-free diagnostic counts. Запускай в PowerShell, подставив реальные absolute пути:

```powershell
$taskProfileRoot = if ($env:CODEX_HOME) { $env:CODEX_HOME } else { Join-Path $env:USERPROFILE '.codex' }
node (Join-Path $taskProfileRoot 'skills\playwright-browser-automation-expert\scripts\inspect-local.cjs') "C:\path\to\project" "http://127.0.0.1:3100/" "C:\path\to\project\test-results\inspection" chromium
```

Ожидаются screenshots для 390/768/1440 и `report.json`. Strict default: exit 1 при failed navigation, pageerror, console.error, failed request, HTTP 4xx/5xx, document overflow или script/runtime failure. Если flow намеренно запрашивает 401/422, отметь это после просмотра report и проверь ожидаемый результат отдельным project test; helper не исключает такие ошибки молча. Exit 0 подтверждает только перечисленные технические условия в период capture, не весь application flow.

Чтобы использовать уже установленный Microsoft Edge, передай `msedge` последним аргументом; Chromium engine запускается с `channel: 'msedge'`. Helper не отправляет формы, не логинится, не сохраняет storageState и не выполняет fullscreen audit. Он включает reduced motion для устойчивых initial captures; animation inspection выполняй отдельно. Screenshot может содержать приватные данные даже без auth, поэтому используй очищенные local fixtures и не публикуй artifacts без просмотра. Открой изображения реально через image viewer, отметь defects и передай в `visual-quality-assurance-expert`.
