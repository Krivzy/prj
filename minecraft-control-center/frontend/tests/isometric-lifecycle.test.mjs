import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import ts from 'typescript';

// The renderer imports extensionless TypeScript modules for Vite. Transpile the
// two independent modules in memory using the project's existing devDependency.
// This regression needs no browser, native canvas, network or generated files.
const source = new URL('../src/', import.meta.url);
const moduleUrl = text => 'data:text/javascript;base64,' + Buffer.from(text).toString('base64');
const compile = name => ts.transpileModule(readFileSync(new URL(name, source), 'utf8'), {
  compilerOptions: {target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext}
}).outputText;
const surfaceUrl = moduleUrl(compile('map-surface.ts'));
const renderer = compile('isometric-map.ts').replace(/from ['"]\.\/map-surface['"]/, `from '${surfaceUrl}'`);
const {IsometricMap} = await import(moduleUrl(renderer));

test('Changing worlds cancels a held pointer before it can restore the previous centre', () => {
  const names = ['window', 'document', 'ResizeObserver', 'requestAnimationFrame', 'cancelAnimationFrame', 'clearTimeout'];
  const original = new Map(names.map(name => [name, Object.getOwnPropertyDescriptor(globalThis, name)]));
  const frames = new Map(), timers = new Map(), captured = new Set(), classes = new Set(), listeners = new Map();
  let id = 0, view;
  const context = new Proxy({}, {get: (target, key) => key in target ? target[key] : (() => {})});
  const canvas = {
    dataset: {}, width: 0, height: 0,
    getContext: () => context,
    getBoundingClientRect: () => ({left: 0, top: 0, width: 800, height: 600}),
    addEventListener: (name, callback) => listeners.set(name, callback),
    removeEventListener: name => listeners.delete(name),
    focus() {},
    setPointerCapture: pointerId => captured.add(pointerId),
    hasPointerCapture: pointerId => captured.has(pointerId),
    releasePointerCapture: pointerId => captured.delete(pointerId),
    classList: {add: name => classes.add(name), remove: name => classes.delete(name)}
  };
  const flush = queue => {const callbacks = [...queue.values()]; queue.clear(); for (const callback of callbacks) callback();};
  const pointer = (name, x, y) => listeners.get(name)?.({button: 0, pointerId: 1, clientX: x, clientY: y});
  const views = [];
  try {
    globalThis.window = {devicePixelRatio: 1, setTimeout: callback => {const key = ++id; timers.set(key, callback); return key;}};
    globalThis.clearTimeout = key => timers.delete(key);
    globalThis.requestAnimationFrame = callback => {const key = ++id; frames.set(key, callback); return key;};
    globalThis.cancelAnimationFrame = key => frames.delete(key);
    globalThis.ResizeObserver = class {observe() {} disconnect() {}};
    globalThis.document = {hidden: false, addEventListener() {}, removeEventListener() {}};
    view = new IsometricMap(canvas, {onView: centre => views.push(centre), onHover() {}, onSelect() {}});
    view.clear('first');
    view.setCenter({x: 5000, y: 66, z: 6000});
    flush(timers);
    assert.deepEqual(views.at(-1), {x: 5000, y: 66, z: 6000});
    pointer('pointerdown', 400, 300);
    assert.equal(captured.size, 1);
    assert.equal(classes.has('is-dragging'), true);

    // A second interaction may select another world while the first pointer is
    // still held. Continuing that pointer must not use its former drag origin.
    view.clear('second');
    view.setCenter({x: 0, y: 70, z: 0});
    assert.equal(captured.size, 0);
    assert.equal(classes.has('is-dragging'), false);
    pointer('pointermove', 560, 300);
    flush(timers);
    assert.deepEqual(views.at(-1), {x: 0, y: 70, z: 0});
    flush(frames);
    assert.equal(frames.size, 0, 'A demand-driven paint must not schedule an idle RAF');
    view.dispose();
    view = null;
    assert.equal(timers.size, 0);
    assert.equal(listeners.size, 0);
  } finally {
    view?.dispose();
    for (const name of names) {
      const descriptor = original.get(name);
      if (descriptor) Object.defineProperty(globalThis, name, descriptor);
      else delete globalThis[name];
    }
  }
});
