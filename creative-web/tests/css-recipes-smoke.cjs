// Isolated CSS/DOM smoke test; never installs packages or modifies a project.
// Example: node tests/css-recipes-smoke.cjs --playwright-module <module-path> --channel msedge --report <output-json>
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const arg = name => {
  const index = process.argv.indexOf(`--${name}`);
  return index < 0 ? undefined : process.argv[index + 1];
};
const { chromium } = require(arg('playwright-module') || 'playwright');
const skillsRoot = path.resolve(__dirname, '../skills');
const sourceHashes = {};
const read = (skill, file) => {
  const relative = `${skill}/references/${file}`;
  const bytes = fs.readFileSync(path.join(skillsRoot, relative));
  sourceHashes[relative] = crypto.createHash('sha256').update(bytes).digest('hex');
  return bytes.toString('utf8');
};
const css = text => [...text.matchAll(/```css\r?\n([\s\S]*?)```/g)].map(match => match[1]).join('\n');
const luminance = hex => {
  const channels = hex.slice(1).match(/../g).map(value => parseInt(value, 16) / 255)
    .map(value => value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4);
  return channels[0] * .2126 + channels[1] * .7152 + channels[2] * .0722;
};
const contrast = (a, b) => (Math.max(luminance(a), luminance(b)) + .05) /
  (Math.min(luminance(a), luminance(b)) + .05);

async function run() {
  const browser = await chromium.launch({ headless: true, ...(arg('channel') ? { channel: arg('channel') } : {}) });
  try {
    const page = await browser.newPage({ viewport: { width: 1024, height: 800 }, reducedMotion: 'no-preference', colorScheme: 'light' });
    const pageErrors = [];
    page.on('pageerror', error => pageErrors.push(error.message));
    const report = {
      checkedDate: arg('checked-date') || new Date().toISOString().slice(0, 10),
      recordedAt: new Date().toISOString(),
      browser: browser.version(),
      channel: arg('channel') || 'bundled-chromium',
      scope: 'Isolated source Markdown CSS/DOM recipes; no production page, mobile device or cross-engine claim',
      checks: [],
    };

    const creative = read('creative-frontend-expert', 'effects-and-lifecycle.md');
    const html = [...creative.matchAll(/```html\r?\n([\s\S]*?)```/g)].map(match => match[1]).join('\n');
    await page.setContent(`<style>${css(creative)}</style>${html}<div class="creative-media"><img src="data:image/svg+xml,%3Csvg xmlns=%22http://www.w3.org/2000/svg%22 width=%22100%22 height=%22100%22/%3E"></div><div class="creative-decoration">Decoration</div>`);
    const state = await page.evaluate(() => ({
      display: getComputedStyle(document.querySelector('.creative-cta')).display,
      clip: getComputedStyle(document.querySelector('.creative-media img')).clipPath,
      mask: getComputedStyle(document.querySelector('.creative-decoration')).maskImage,
      stroke: getComputedStyle(document.querySelector('.creative-line path')).stroke,
    }));
    assert.equal(state.display, 'inline-flex');
    assert.match(state.clip, /polygon/);
    assert.match(state.mask, /linear-gradient/);
    assert.notEqual(state.stroke, 'none');
    await page.keyboard.press('Tab');
    assert.equal(await page.locator('.creative-cta').evaluate(element => getComputedStyle(element).outlineStyle), 'solid');
    await page.locator('.creative-cta').hover();
    await page.waitForFunction(() => getComputedStyle(document.querySelector('.creative-cta__arrow')).transform !== 'none');
    await page.emulateMedia({ reducedMotion: 'reduce' });
    assert.equal(await page.locator('.creative-cta__arrow').evaluate(element => getComputedStyle(element).transitionDuration), '0s');
    await page.locator('.creative-line').evaluate(element => element.dataset.animate = 'true');
    assert.equal(await page.locator('.creative-line path').evaluate(element => getComputedStyle(element).animationName), 'none');
    report.checks.push({ name: 'creative-CSS-focus-hover-clip-mask-SVG-reduced', result: 'pass' });

    const transitionFunction = [...creative.matchAll(/```js\r?\n([\s\S]*?)```/g)].find(match => match[1].includes('updateWithOptionalTransition'))[1];
    await page.addScriptTag({ content: transitionFunction });
    assert.equal(await page.evaluate(async () => { let count = 0; await updateWithOptionalTransition(() => count++); return count; }), 1);
    await page.emulateMedia({ reducedMotion: 'no-preference' });
    assert.equal(await page.evaluate(async () => { let count = 0; await updateWithOptionalTransition(() => { count++; document.body.dataset.updated = 'true'; }); return count; }), 1);
    assert.deepEqual(await page.evaluate(async () => {
      let count = 0, caught = false;
      try { await updateWithOptionalTransition(() => { count++; return Promise.reject(new Error('Fixture failure')); }); }
      catch (error) { caught = error.message === 'Fixture failure'; }
      return { count, caught };
    }), { count: 1, caught: true });
    report.checks.push({ name: 'ViewTransition-one-update-normal-reduced-rejected', result: 'pass' });

    await page.setContent(`<style>${css(read('ui-ux-design-system-expert', 'tokens-and-components.md'))}\n${css(read('ui-ux-design-system-expert', 'product-patterns.md'))}</style><button class="system-action">Action</button><div class="system-glass" data-glass="enabled">Panel</div><div class="system-bento"><section class="system-bento__primary">Primary</section><section>Secondary</section></div>`);
    const themes = [];
    const palettes = {};
    for (const [name, theme, scheme, color] of [
      ['light', null, 'light', 'rgb(32, 36, 43)'], ['system-dark', null, 'dark', 'rgb(245, 247, 250)'],
      ['explicit-light', 'light', 'dark', 'rgb(32, 36, 43)'], ['explicit-dark', 'dark', 'light', 'rgb(245, 247, 250)'],
    ]) {
      await page.emulateMedia({ colorScheme: scheme });
      await page.evaluate(value => value ? document.documentElement.dataset.theme = value : document.documentElement.removeAttribute('data-theme'), theme);
      assert.equal(await page.locator('body').evaluate(element => getComputedStyle(element).color), color);
      if (name === 'light' || name === 'explicit-dark') {
        palettes[name === 'light' ? 'light' : 'dark'] = await page.evaluate(() => {
          const computed = getComputedStyle(document.documentElement);
          const roles = { canvas: '--surface-canvas', panel: '--surface-panel', primary: '--text-primary', secondary: '--text-secondary', action: '--action-bg', actionText: '--action-text', border: '--control-border', focus: '--focus-ring' };
          return Object.fromEntries(Object.entries(roles).map(([role, property]) => [role, computed.getPropertyValue(property).trim()]));
        });
      }
      themes.push(name);
    }
    report.checks.push({ name: 'CSS-theme-overrides', result: 'pass', themes });
    const ratios = {};
    for (const [name, palette] of Object.entries(palettes)) {
      ratios[name] = {
        primaryCanvas: contrast(palette.primary, palette.canvas), secondaryPanel: contrast(palette.secondary, palette.panel),
        action: contrast(palette.action, palette.actionText), borderPanel: contrast(palette.border, palette.panel),
        focusCanvas: contrast(palette.focus, palette.canvas),
      };
      for (const key of ['primaryCanvas', 'secondaryPanel', 'action']) assert.ok(ratios[name][key] >= 4.5);
      for (const key of ['borderPanel', 'focusCanvas']) assert.ok(ratios[name][key] >= 3);
    }
    report.checks.push({ name: 'example-token-contrast', result: 'pass', ratios });

    await page.setContent(`<style>${css(read('responsive-design-expert', 'layout-recipes.md'))}\n${css(read('responsive-design-expert', 'interaction-and-verification.md'))}</style><main class="responsive-shell"><h1 class="responsive-title">LongUnbrokenHeadingWithoutAnyWhitespaceAtAll</h1><div class="responsive-grid"><article>First</article><article>Second</article><article>Third</article></div><div class="responsive-region"><article class="responsive-feature"><div>Media</div><div>Content</div></article></div><div class="responsive-horizontal-drag">Drag</div></main>`);
    const widths = [320, 390, 575, 576, 577, 767, 768, 769, 799, 800, 801, 1024, 1440];
    for (const width of widths) {
      await page.setViewportSize({ width, height: 800 });
      const geometry = await page.evaluate(() => ({ width: document.documentElement.clientWidth, scroll: document.documentElement.scrollWidth, touch: getComputedStyle(document.querySelector('.responsive-horizontal-drag')).touchAction }));
      assert.ok(geometry.scroll <= geometry.width + 1);
      assert.equal(geometry.touch, 'pan-y pinch-zoom');
    }
    await page.setViewportSize({ width: 1024, height: 800 });
    const containers = [];
    for (const width of [575, 576, 577]) {
      await page.locator('.responsive-region').evaluate((element, value) => element.style.width = `${value}px`, width);
      const columns = await page.locator('.responsive-feature').evaluate(element => getComputedStyle(element).gridTemplateColumns.split(' ').length);
      assert.equal(columns, width >= 576 ? 2 : 1);
      containers.push({ width, columns });
    }
    report.checks.push({ name: 'responsive-overflow-and-container-edges', result: 'pass', widths, containers });
    assert.deepEqual(pageErrors, []);
    report.pageErrors = pageErrors;
    report.sourceHashes = sourceHashes;
    const serialized = `${JSON.stringify(report, null, 2)}\n`;
    if (arg('report')) fs.writeFileSync(path.resolve(arg('report')), serialized, 'utf8');
    console.log(serialized);
  } finally { await browser.close(); }
}
run().catch(error => { console.error(error.stack); process.exitCode = 1; });
