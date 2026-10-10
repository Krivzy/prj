# Deterministic E2E recipes

## Подключение к существующему проекту

Сохрани existing runner/config и package manager. При новой настройке проверь текущие requirements и package engine: docs 2026-10-10 перечисляют Node 22.x/24.x/26.x и Windows 11+/Server 2019+. `@playwright/test` и browser binaries совместимы с конкретным package release; после upgrade могут понадобиться новые binaries. [Installation](https://playwright.dev/docs/intro), [Browsers](https://playwright.dev/docs/browsers)

Ниже команды для **PowerShell в корне проекта с npm**. При pnpm/yarn используй существующий эквивалент, не создавай ещё один lockfile:

```powershell
npm.cmd install -D @playwright/test
npx.cmd playwright install chromium firefox webkit
npx.cmd playwright test --project=chromium
```

Первая меняет dev dependency и lockfile, вторая скачивает browser binaries, третья запускает assertions. Команды разрешены при setup task; не запускай их повторно при каждом audit. Успех установки binaries не означает, что launch прошёл.

## Config recipe

Пример предполагает, что `npm run start` запускает уже собранный проект, поддерживает `--hostname/--port` (как Next) и порт свободен. Для другого server адаптируй **существующий** script и readiness URL. CI сначала выполняет production build. [Configuration](https://playwright.dev/docs/test-configuration), [Web server](https://playwright.dev/docs/test-webserver)

```ts
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests/e2e',
  forbidOnly: Boolean(process.env.CI),
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: 'http://127.0.0.1:3100',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'firefox', use: { ...devices['Desktop Firefox'] } },
    { name: 'webkit', use: { ...devices['Desktop Safari'] } },
    { name: 'mobile', use: { ...devices['Pixel 5'] } },
  ],
  webServer: {
    command: 'npm run start -- --hostname 127.0.0.1 --port 3100',
    url: 'http://127.0.0.1:3100',
    reuseExistingServer: false,
    timeout: 120_000,
  },
});
```

`reuseExistingServer: false` уместен для контролируемого запуска; если пользователь явно выбрал уже работающий server, проверь его identity и используй этот URL. Secret-bearing traces/screenshots остаются в закрытом месте; настрой gitignore и artifact retention по project policy.

## Form/API flow recipe

Названия/endpoint — контракт примера, замени реальными controls и test data. Ценность проверки — результат серверного действия, а не количество clicks. Listeners регистрируются до загрузки; асинхронная response wait начинается до submit. [Locators](https://playwright.dev/docs/locators), [Assertions](https://playwright.dev/docs/test-assertions), [Network](https://playwright.dev/docs/network)

```ts
import { test, expect } from '@playwright/test';

test('project creation and navigation', async ({ page }) => {
  const pageErrors: string[] = [];
  page.on('pageerror', error => pageErrors.push(error.name));

  await page.goto('/projects/new');
  await expect(page.getByRole('heading', { name: 'Новый проект' })).toBeVisible();
  await page.getByLabel('Название').fill('Browser fixture project');

  const saveResponse = page.waitForResponse(response => {
    const url = new URL(response.url());
    return url.pathname === '/api/projects'
      && response.request().method() === 'POST';
  });
  await page.getByRole('button', { name: 'Создать', exact: true }).click();
  expect((await saveResponse).status()).toBe(201);
  await expect(page.getByRole('status')).toContainText('Создан');
  await page.getByRole('link', { name: 'Все проекты' }).click();
  await expect(page).toHaveURL(/\/projects$/);
  await expect(page.getByText('Browser fixture project', { exact: true })).toBeVisible();
  expect(pageErrors).toEqual([]);
});
```

Создание данных должно быть isolated и очищаться разрешённым test fixture/API только в test environment. Если приложение использует Server Action вместо `/api/projects`, проверяй observable success и DAL result; не закрепляй нестабильный внутренний action URL/id в тесте.

Для controlled failure route mock ставь **до** triggering action: `await page.route('**/api/projects', route => route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ code: 'UNAVAILABLE' }) }));`. Затем verify error UI и восстановление после retry. Такой mock подтверждает UI failure handling, не работоспособность настоящего backend. У response 4xx/5xx не обязательно срабатывает `requestfailed`; слушай оба события.

## Ошибки, flake и scope

Console error, uncaught exception, failed request и invalid HTTP status — разные сигналы. Собирай minimal sanitized evidence без bodies/tokens. Assert неожиданные ошибки, а ожидаемую validation 422 обозначь в тесте. При flake воспроизведи race через trace, исправь ожидание/fixture/production code; retries не превращают flaky test в доказательство.

Полный multi-browser прогон нужен после изменения browser-sensitive CSS/API/flow или по existing CI policy. Для узкой правки сначала воспроизведение + relevant browser; расширяй матрицу по конкретному риску. Сборка и unit tests дополняют E2E, а не заменяют наблюдение browser UI.
