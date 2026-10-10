#!/usr/bin/env node
'use strict';

// Inspect an already running local project. No auth persistence or package install.
const fs = require('node:fs/promises');
const path = require('node:path');
const { createRequire } = require('node:module');

async function main() {
  const [projectRoot, target, outputRoot, browserName = 'chromium'] = process.argv.slice(2);
  if (!projectRoot || !target || !outputRoot) {
    throw new Error('Usage: node inspect-local.cjs PROJECT_ROOT LOCAL_URL OUTPUT_DIR [chromium|firefox|webkit|msedge]');
  }
  const url = new URL(target);
  if (!['http:', 'https:'].includes(url.protocol)
    || !['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)
    || url.username || url.password) {
    throw new Error('Expected a loopback HTTP(S) URL without credentials.');
  }
  if (!['chromium', 'firefox', 'webkit', 'msedge'].includes(browserName)) {
    throw new Error('Browser must be chromium, firefox, webkit or msedge.');
  }
  const projectRequire = createRequire(path.join(path.resolve(projectRoot), 'package.json'));
  let playwright;
  try {
    playwright = projectRequire('playwright');
  } catch (error) {
    if (error.code !== 'MODULE_NOT_FOUND') throw error;
    playwright = projectRequire('@playwright/test');
  }
  const output = path.resolve(outputRoot);
  await fs.mkdir(output, { recursive: true });
  const browserType = browserName === 'msedge' ? 'chromium' : browserName;
  const browser = await playwright[browserType].launch({
    headless: true,
    ...(browserName === 'msedge' ? { channel: 'msedge' } : {}),
  });
  const report = {
    browser: browserName,
    version: browser.version(),
    target: `${url.origin}${url.pathname}`, // no query/hash or credentials in report
    reducedMotion: 'reduce',
    capturedAt: new Date().toISOString(),
    viewports: [],
  };
  let failed = false;
  try {
    for (const viewport of [
      { width: 390, height: 844 },
      { width: 768, height: 1024 },
      { width: 1440, height: 900 },
    ]) {
      const context = await browser.newContext({ viewport, reducedMotion: 'reduce' });
      const page = await context.newPage();
      page.setDefaultTimeout(15_000);
      page.setDefaultNavigationTimeout(30_000);
      const result = {
        viewport,
        pageErrorCount: 0,
        consoleErrorCount: 0,
        failedRequestCount: 0,
        httpErrorCount: 0,
        mainStatus: null,
        overflowPixels: null,
        screenshot: `${browserName}-${viewport.width}.png`,
      };
      page.on('pageerror', () => { result.pageErrorCount += 1; });
      page.on('console', message => {
        if (message.type() === 'error') result.consoleErrorCount += 1;
      });
      page.on('requestfailed', () => { result.failedRequestCount += 1; });
      page.on('response', response => {
        if (response.status() >= 400) result.httpErrorCount += 1;
      });
      try {
        const response = await page.goto(url.href, { waitUntil: 'load' });
        result.mainStatus = response ? response.status() : null;
        await page.waitForFunction(() => document.fonts.status === 'loaded');
        result.overflowPixels = await page.evaluate(() =>
          document.documentElement.scrollWidth - document.documentElement.clientWidth);
        await page.screenshot({ path: path.join(output, result.screenshot), fullPage: true });
        result.passed = result.mainStatus !== null && result.mainStatus < 400
          && result.pageErrorCount === 0 && result.consoleErrorCount === 0
          && result.failedRequestCount === 0 && result.httpErrorCount === 0
          && result.overflowPixels <= 1;
      } catch (error) {
        result.passed = false;
        result.failureType = error.name; // message/stack can contain private target data
      } finally {
        await context.close();
      }
      if (!result.passed) failed = true;
      report.viewports.push(result);
    }
  } finally {
    await browser.close();
  }
  report.passed = !failed;
  await fs.writeFile(path.join(output, 'report.json'), `${JSON.stringify(report, null, 2)}\n`, 'utf8');
  console.log(JSON.stringify({ passed: report.passed, browser: browserName, captures: report.viewports.length }));
  if (failed) process.exitCode = 1;
}

main().catch(error => {
  // Avoid printing page content, URL query, cookies or third-party launch diagnostics.
  console.error(JSON.stringify({ passed: false, failureType: error.name,
    instruction: 'Check arguments, project Playwright dependency, browser binary and local server readiness.' }));
  process.exitCode = 1;
});
