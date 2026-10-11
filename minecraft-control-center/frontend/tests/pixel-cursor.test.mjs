import test from 'node:test';
import assert from 'node:assert/strict';
import {mountPixelCursor, pixelCursorPosition, pixelCursorState} from '../src/pixel-cursor-controller.ts';
import {CURSOR_ASSETS, pixelCursorImageReady} from '../src/cursor-assets.ts';
import {readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';

class Surface extends EventTarget {
  listeners = new Map();
  addEventListener(type, listener, options) {
    super.addEventListener(type, listener, options);
    if (!this.listeners.has(type)) this.listeners.set(type, new Set());
    this.listeners.get(type).add(listener);
  }
  removeEventListener(type, listener, options) {
    super.removeEventListener(type, listener, options);
    this.listeners.get(type)?.delete(listener);
  }
  get listenerCount() { return [...this.listeners.values()].reduce((n, values) => n + values.size, 0); }
}

function fixture(options = {}) {
  const classes = new Set();
  const queries = {
    fine: Object.assign(new Surface(), {matches: options.fine ?? true}),
    reduced: Object.assign(new Surface(), {matches: options.reduced ?? false}),
    forced: Object.assign(new Surface(), {matches: options.forced ?? false}),
  };
  const win = Object.assign(new Surface(), {
    matchMedia(query) { return queries[query.includes('reduced') ? 'reduced' : query.includes('forced') ? 'forced' : 'fine']; },
    getComputedStyle() { return {resize: 'vertical', direction: 'ltr'}; },
    requestAnimationFrame() { throw new Error('Cursor must not start an animation loop.'); },
  });
  const doc = Object.assign(new Surface(), {
    visibilityState: 'visible', focused: true,
    hasFocus() { return this.focused; },
    documentElement: {clientWidth: 1280, clientHeight: 900, classList: {add: value => classes.add(value), remove: value => classes.delete(value)}},
  });
  const paints = [];
  const assets = {ready: options.ready ?? true, failed: new Set()};
  let hidden = false;
  const view = {
    dataset: {}, style: {},
    get hidden() { return hidden; },
    set hidden(value) { hidden = value; paints.push({hidden: value, transform: this.style.transform, state: this.dataset.state}); },
  };
  const dispose = mountPixelCursor(view, {window: win, document: doc, assetReady: state => assets.ready && !assets.failed.has(state)});
  const pointer = (type = 'pointermove', options = {}) => {
    const event = new Event(type, {cancelable: true});
    const {target, ...properties} = options;
    Object.assign(event, {pointerType: 'mouse', clientX: 200, clientY: 150, buttons: 0, relatedTarget: {}, ...properties});
    event.composedPath = () => target ? [target, doc] : [doc];
    doc.dispatchEvent(event);
    return event;
  };
  const shown = () => !view.hidden && classes.has('mcc-pixel-cursor-active');
  return {doc, win, view, paints, pointer, dispose, queries, shown, classes, assets};
}

function target(semantics = {}) {
  return {
    isContentEditable: Boolean(semantics.editable),
    closest(selector) {
      if (selector.startsWith('iframe,object,embed')) return semantics.frame || semantics.native ? this : null;
      if (selector === 'textarea') return semantics.textarea ? this : null;
      if (selector.startsWith(':disabled')) return semantics.disabled ? this : null;
      if (selector === '[aria-busy="true"]') return semantics.busy ? this : null;
      if (selector.startsWith('textarea,')) return semantics.text ? this : null;
      if (selector.startsWith('button,')) return semantics.action ? this : null;
      if (selector.includes('.image-map-stage')) return semantics.grab ? this : null;
      return null;
    },
    getBoundingClientRect() { return {left: 20, right: 240, top: 20, bottom: 200}; },
  };
}

test('disabled wins over editable/action; controls inside a draggable map remain controls', () => {
  assert.equal(pixelCursorState({disabled: true, text: true, action: true, grab: true, pressed: true}), 'disabled');
  assert.equal(pixelCursorState({text: true, action: true}), 'text');
  assert.equal(pixelCursorState({action: true, grab: true, pressed: true}), 'action');
  assert.equal(pixelCursorState({grab: true, pressed: true}), 'grabbing');
  assert.equal(pixelCursorState({grab: true}), 'grab');
  assert.equal(pixelCursorState({}), 'arrow');
});

test('original per-shape hotspots preserve exact fractional client coordinates at natural scale', () => {
  assert.equal(pixelCursorPosition(50.25, 73.5, 'arrow'), 'translate3d(50.25px, 73.5px, 0)');
  assert.equal(pixelCursorPosition(50.25, 73.5, 'text'), 'translate3d(40.25px, 61.5px, 0)');
  assert.equal(pixelCursorPosition(50.25, 73.5, 'action'), 'translate3d(43.25px, 73.5px, 0)');
  assert.equal(pixelCursorPosition(50.25, 73.5, 'grabbing'), 'translate3d(40.25px, 71.5px, 0)');
  assert.equal(pixelCursorPosition(50.25, 73.5, 'disabled'), 'translate3d(39.25px, 62.5px, 0)');
  assert.equal(pixelCursorPosition(50.25, 73.5, 'busy'), 'translate3d(50.25px, 67.5px, 0)');
});

test('first mouse click without any movement paints its hotspot before showing', () => {
  const f = fixture();
  assert.equal(f.shown(), false);
  const event = f.pointer('pointerdown', {clientX: 418, clientY: 257});
  assert.equal(f.shown(), true);
  assert.deepEqual(f.paints.at(-1), {hidden: false, transform: 'translate3d(418px, 257px, 0)', state: 'arrow'});
  assert.equal(event.defaultPrevented, false);
  f.dispose();
});

test('high frequency movement writes the latest point directly without scheduling work', () => {
  const f = fixture();
  for (let x = 0; x < 1000; x++) f.pointer('pointermove', {clientX: x, clientY: 440});
  assert.equal(f.view.style.transform, 'translate3d(999px, 440px, 0)');
  assert.equal(f.shown(), true);
  f.dispose();
});

test('hover/down/up update action, text, disabled and drag states at the same position', () => {
  const f = fixture();
  f.pointer('pointerover', {target: target({action: true, grab: true})});
  assert.equal(f.view.dataset.state, 'action');
  f.pointer('pointermove', {target: target({text: true})});
  assert.equal(f.view.dataset.state, 'text');
  assert.equal(f.view.style.transform, 'translate3d(190px, 138px, 0)');
  f.pointer('pointerover', {target: target({disabled: true, text: true, action: true})});
  assert.equal(f.view.dataset.state, 'disabled');
  f.pointer('pointerdown', {target: target({grab: true}), buttons: 1});
  assert.equal(f.view.dataset.state, 'grabbing');
  f.pointer('pointerup', {target: target({grab: true}), buttons: 0});
  assert.equal(f.view.dataset.state, 'grab');
  f.dispose();
});

test('native pickers and textarea resize corner preserve native cursor; text body remains custom', () => {
  const f = fixture();
  f.pointer('pointermove', {target: target({native: true})});
  assert.equal(f.shown(), false);
  const textarea = target({textarea: true, text: true});
  f.pointer('pointermove', {target: textarea, clientX: 100, clientY: 100});
  assert.equal(f.view.dataset.state, 'text');
  assert.equal(f.shown(), true);
  f.pointer('pointermove', {target: textarea, clientX: 233, clientY: 193});
  assert.equal(f.shown(), false);
  f.dispose();
});

test('pointerup releases grab even before the application removes its stale drag class', () => {
  const f = fixture();
  const stage = target({grab: true});
  const original = stage.closest;
  stage.closest = selector => selector.includes('.is-dragging') ? stage : original.call(stage, selector);
  f.pointer('pointerdown', {target: stage, buttons: 1});
  assert.equal(f.view.dataset.state, 'grabbing');
  f.pointer('pointerup', {target: stage, buttons: 0});
  assert.equal(f.view.dataset.state, 'grab');
  f.dispose();
});

test('touch and pen restore the native cursor without preventing or capturing the event', () => {
  const f = fixture();
  for (const pointerType of ['touch', 'pen']) {
    f.pointer();
    const event = f.pointer('pointerdown', {pointerType, buttons: 1});
    assert.equal(f.shown(), false);
    assert.equal(f.classes.size, 0);
    assert.equal(event.defaultPrevented, false);
  }
  f.dispose();
});

test('Tab, blur, page hide and leaving the window restore native until fresh mouse intent', () => {
  const f = fixture();
  const hideEvents = [
    () => { const event = new Event('keydown'); Object.assign(event, {key: 'Tab'}); f.doc.dispatchEvent(event); },
    () => f.win.dispatchEvent(new Event('blur')),
    () => f.win.dispatchEvent(new Event('pagehide')),
    () => f.win.dispatchEvent(new Event('pointerleave')),
    () => f.pointer('pointerout', {relatedTarget: null}),
    () => f.doc.dispatchEvent(new Event('pointercancel')),
  ];
  for (const hide of hideEvents) {
    f.pointer();
    assert.equal(f.shown(), true);
    hide();
    assert.equal(f.shown(), false);
    assert.equal(f.classes.size, 0);
  }
  f.dispose();
});

test('moving between descendants does not hide the cursor', () => {
  const f = fixture();
  f.pointer();
  f.pointer('pointerout', {relatedTarget: target({action: true})});
  assert.equal(f.shown(), true);
  f.dispose();
});

test('coarse-only, reduced motion and forced colors never hide the native cursor', () => {
  for (const options of [{fine: false}, {reduced: true}, {forced: true}]) {
    const f = fixture(options);
    f.pointer('pointerdown');
    assert.equal(f.shown(), false);
    assert.equal(f.classes.size, 0);
    f.dispose();
  }
});

test('changing accessibility/device media immediately restores native and blocks further points', () => {
  const f = fixture();
  for (const [name, value] of [['reduced', true], ['forced', true], ['fine', false]]) {
    f.pointer();
    assert.equal(f.shown(), true);
    f.queries[name].matches = value;
    f.queries[name].dispatchEvent(new Event('change'));
    f.pointer('pointerdown');
    assert.equal(f.shown(), false);
    f.queries[name].matches = !value;
    f.queries[name].dispatchEvent(new Event('change'));
    assert.equal(f.shown(), false);
  }
  f.dispose();
});

test('hidden/unfocused documents and native scrollbar/frame areas keep system cursor', () => {
  const f = fixture();
  f.doc.visibilityState = 'hidden';
  f.doc.dispatchEvent(new Event('visibilitychange'));
  f.pointer();
  assert.equal(f.shown(), false);
  f.doc.visibilityState = 'visible';
  f.doc.focused = false;
  f.pointer();
  assert.equal(f.shown(), false);
  f.doc.focused = true;
  for (const point of [{clientX: -1}, {clientY: -1}, {clientX: 1280}, {clientY: 900}, {clientX: NaN}, {target: target({frame: true})}]) {
    f.pointer();
    f.pointer('pointermove', point);
    assert.equal(f.shown(), false);
  }
  f.dispose();
});

test('unmount/StrictMode cleanup removes every listener and class without stale reactivation', () => {
  const f = fixture();
  f.pointer();
  assert.equal(f.shown(), true);
  f.dispose();
  assert.equal(f.shown(), false);
  assert.equal(f.classes.size, 0);
  for (const surface of [f.doc, f.win, ...Object.values(f.queries)]) assert.equal(surface.listenerCount, 0);
  f.pointer('pointerdown');
  assert.equal(f.shown(), false);
  const disposeAgain = mountPixelCursor(f.view, {window: f.win, document: f.doc});
  const before = f.paints.length;
  f.pointer();
  assert.equal(f.paints.length, before + 1);
  assert.equal(f.shown(), true);
  disposeAgain();
  assert.equal(f.classes.size, 0);
});

test('unloaded original PNG keeps native cursor, then first load refresh paints the stored hotspot', () => {
  const f = fixture({ready: false});
  f.pointer('pointerdown', {clientX: 310, clientY: 257, target: target({action: true})});
  assert.equal(f.shown(), false);
  assert.equal(f.classes.size, 0);
  f.assets.ready = true;
  f.dispose.refresh();
  assert.equal(f.shown(), true);
  assert.equal(f.view.style.transform, 'translate3d(303px, 257px, 0)');
  f.dispose();
});

test('a failed selected image restores native and loading another shape cannot mask that failure', () => {
  const f = fixture();
  f.pointer('pointerover', {target: target({text: true})});
  assert.equal(f.shown(), true);
  f.assets.failed.add('text');
  f.dispose.refresh();
  assert.equal(f.shown(), false);
  assert.equal(f.classes.size, 0);
  f.dispose.refresh();
  assert.equal(f.shown(), false);
  f.pointer('pointermove');
  assert.equal(f.shown(), true);
  f.dispose();
});

test('late image load/observer refresh after Tab, touch or disposal never revives custom cursor', () => {
  const f = fixture({ready: false});
  f.pointer();
  const tab = new Event('keydown');
  Object.assign(tab, {key: 'Tab'});
  f.doc.dispatchEvent(tab);
  f.assets.ready = true;
  f.dispose.refresh();
  assert.equal(f.shown(), false);
  f.pointer();
  f.pointer('pointerdown', {pointerType: 'touch'});
  f.dispose.refresh();
  assert.equal(f.shown(), false);
  f.pointer();
  f.dispose();
  f.dispose.refresh();
  assert.equal(f.shown(), false);
  assert.equal(f.classes.size, 0);
});

test('real busy attribute selects only upstream busy frames and returns to static shape on completion', () => {
  const f = fixture();
  const semantics = {action: true, busy: false, disabled: false};
  const button = target(semantics);
  f.pointer('pointerover', {target: button});
  assert.equal(f.view.dataset.state, 'action');
  semantics.busy = true;
  semantics.disabled = true;
  f.dispose.refresh();
  assert.equal(f.view.dataset.state, 'busy');
  assert.equal(f.view.style.transform, 'translate3d(200px, 144px, 0)');
  semantics.busy = false;
  semantics.disabled = false;
  f.dispose.refresh();
  assert.equal(f.view.dataset.state, 'action');
  assert.equal(f.view.style.transform, 'translate3d(193px, 150px, 0)');
  f.dispose();
});

test('asset readiness validates decoded original dimensions, including the full busy strip', () => {
  assert.equal(pixelCursorImageReady(undefined, 'arrow'), false);
  assert.equal(pixelCursorImageReady({complete: false, naturalWidth: 32, naturalHeight: 32}, 'arrow'), false);
  assert.equal(pixelCursorImageReady({complete: true, naturalWidth: 0, naturalHeight: 0}, 'arrow'), false);
  assert.equal(pixelCursorImageReady({complete: true, naturalWidth: 22, naturalHeight: 22}, 'arrow'), false);
  assert.equal(pixelCursorImageReady({complete: true, naturalWidth: 32, naturalHeight: 32}, 'arrow'), true);
  assert.equal(pixelCursorImageReady({complete: true, naturalWidth: 32, naturalHeight: 32}, 'busy'), false);
  assert.equal(pixelCursorImageReady({complete: true, naturalWidth: 32, naturalHeight: 256}, 'busy'), true);
});

test('refresh after a busy target is removed restores native without reusing its stale state', () => {
  const f = fixture();
  const button = target({action: true, busy: true});
  button.isConnected = true;
  f.pointer('pointerover', {target: button});
  assert.equal(f.view.dataset.state, 'busy');
  assert.equal(f.shown(), true);
  button.isConnected = false;
  f.dispose.refresh();
  assert.equal(f.shown(), false);
  assert.equal(f.classes.size, 0);
  button.isConnected = true;
  f.dispose.refresh();
  assert.equal(f.shown(), false);
  f.pointer();
  assert.equal(f.view.dataset.state, 'arrow');
  assert.equal(f.shown(), true);
  f.dispose();
});

test('vendored original assets, metadata, license and state hotspots match pinned source manifest', () => {
  const base = new URL('../public/assets/cursors/', import.meta.url);
  const manifest = JSON.parse(readFileSync(new URL('ce-provenance.json', base), 'utf8'));
  assert.equal(manifest.commit, 'a293ed53412d49c22107fb3cc409dbf68920998c');
  assert.equal(manifest.resourcePack, 'default');
  assert.equal(manifest.originalBytes, true);
  const sha = raw => createHash('sha256').update(raw).digest('hex');
  const license = readFileSync(new URL(manifest.license.localFile, base));
  assert.equal(sha(license), manifest.license.sha256);
  assert.match(license.toString('utf8'), /MIT License\n\nCopyright \(c\) 2025 fishstiz/);
  assert.equal(manifest.assets.length, 6);
  for (const row of manifest.assets) {
    const png = readFileSync(new URL(row.localFile, base));
    assert.equal(sha(png), row.sha256);
    assert.equal(png.readUInt32BE(16), row.width);
    assert.equal(png.readUInt32BE(20), row.height);
    let metadata = {};
    if (row.metadata) {
      const raw = readFileSync(new URL(row.metadata.localFile, base));
      assert.equal(sha(raw), row.metadata.sha256);
      metadata = JSON.parse(raw);
    }
    for (const asset of Object.values(CURSOR_ASSETS).filter(value => value.name === row.name)) {
      assert.equal(asset.width, row.width);
      assert.equal(asset.textureHeight, row.height);
      assert.equal(asset.xhot, metadata.cursor?.xhot ?? 0);
      assert.equal(asset.yhot, metadata.cursor?.yhot ?? 0);
      assert.equal(asset.frames, metadata.animation ? row.height / row.width : 1);
      assert.equal(asset.frameMs, metadata.animation ? metadata.animation.frametime * 50 : 0);
    }
  }
  assert.equal(CURSOR_ASSETS.busy.frames * CURSOR_ASSETS.busy.frameMs, 400);
});
