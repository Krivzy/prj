import {CURSOR_ASSETS} from './cursor-assets.ts';
import type {PixelCursorState} from './cursor-assets.ts';
export type {PixelCursorState} from './cursor-assets.ts';

export type CursorSemantics = {
  busy?: boolean;
  disabled?: boolean;
  text?: boolean;
  action?: boolean;
  grab?: boolean;
  pressed?: boolean;
};

export function pixelCursorState(semantics: CursorSemantics): PixelCursorState {
  if (semantics.busy) return 'busy';
  if (semantics.disabled) return 'disabled';
  if (semantics.text) return 'text';
  if (semantics.action) return 'action';
  if (semantics.grab) return semantics.pressed ? 'grabbing' : 'grab';
  return 'arrow';
}

export function pixelCursorPosition(x: number, y: number, state: PixelCursorState): string {
  const {xhot, yhot} = CURSOR_ASSETS[state];
  return `translate3d(${x - xhot}px, ${y - yhot}px, 0)`;
}

const TEXT_TARGETS = 'textarea,[role="textbox"],input:not([type="checkbox"]):not([type="radio"]):not([type="range"]):not([type="color"]):not([type="button"]):not([type="submit"]):not([type="reset"]):not([type="file"]):not([type="hidden"])';
const ACTION_TARGETS = 'button,a[href],summary,select,[role="button"],[role="link"],[role="option"],[role="menuitem"],[role="tab"],[role="checkbox"],[role="radio"],input[type="checkbox"],input[type="radio"],input[type="button"],input[type="submit"],input[type="reset"],input[type="file"],input[type="color"]';
const GRAB_TARGETS = '[data-cursor="grab"],[data-cursor="grabbing"],[draggable="true"],.image-map-stage,.iso-map-stage,.react-flow__pane.draggable,.react-flow__node.draggable,.react-flow__resize-control';
const NATIVE_TARGETS = 'iframe,object,embed,select,input[type="range"],input[type="color"],input[type="file"],.react-flow__resize-control,[data-cursor="native"]';

function elementTarget(target: EventTarget | null): Element | null {
  const element = target as Element | null;
  return element && typeof element.closest === 'function' ? element : null;
}

export function pixelCursorTarget(target: EventTarget | null, pressed: boolean): PixelCursorState {
  const element = elementTarget(target);
  if (!element) return 'arrow';
  const label = element.closest('label');
  return pixelCursorState({
    busy: Boolean(element.closest('[aria-busy="true"]')),
    disabled: Boolean(element.closest(':disabled,[aria-disabled="true"],[inert],[data-cursor="disabled"]')),
    text: Boolean(element.closest(TEXT_TARGETS)) || (element as HTMLElement).isContentEditable === true,
    action: Boolean(element.closest(ACTION_TARGETS)) || Boolean(label?.querySelector('input[type="checkbox"],input[type="radio"]')),
    grab: Boolean(element.closest(GRAB_TARGETS)),
    pressed: pressed || Boolean(element.closest('[data-cursor="grabbing"]')),
  });
}

export type PixelCursorEnvironment = { window: Window; document: Document; assetReady?: (state: PixelCursorState) => boolean };
export type PixelCursorController = (() => void) & {refresh: () => void};
type CursorPoint = {x: number; y: number; target: Element | null; pressed: boolean};

/** One global mouse overlay. No event interception, React updates or animation loop. */
export function mountPixelCursor(view: HTMLElement, environment: PixelCursorEnvironment): PixelCursorController {
  const {window: win, document: doc} = environment;
  const fine = win.matchMedia('(any-hover: hover) and (any-pointer: fine)');
  const reduced = win.matchMedia('(prefers-reduced-motion: reduce)');
  const forced = win.matchMedia('(forced-colors: active)');
  const cleanup: Array<() => void> = [];
  let active = false;
  let disposed = false;
  let lastPoint: CursorPoint | null = null;

  function hide(clearPoint = true) {
    active = false;
    view.hidden = true;
    doc.documentElement.classList.remove('mcc-pixel-cursor-active');
    if (clearPoint) lastPoint = null;
  }

  function eligible() {
    return !disposed && fine.matches && !reduced.matches && !forced.matches && doc.visibilityState === 'visible' && doc.hasFocus();
  }

  function refresh() {
    if (!lastPoint) return;
    if (!eligible()) return hide();
    const {x, y, target, pressed} = lastPoint;
    if (target?.isConnected === false) return hide();
    const state = pixelCursorTarget(target, pressed);
    // Never hide the native cursor while the original PNG is unavailable/failed.
    if (environment.assetReady && !environment.assetReady(state)) return hide(false);
    view.dataset.state = state;
    // Set the exact hotspot before showing; no interpolation or one-frame old position.
    view.style.transform = pixelCursorPosition(x, y, state);
    view.hidden = false;
    if (!active) {
      active = true;
      doc.documentElement.classList.add('mcc-pixel-cursor-active');
    }
  }

  function point(event: PointerEvent) {
    if (event.pointerType !== 'mouse' || !eligible()) return hide();
    const {clientX: x, clientY: y} = event;
    if (!Number.isFinite(x) || !Number.isFinite(y) || x < 0 || y < 0 || x >= doc.documentElement.clientWidth || y >= doc.documentElement.clientHeight) return hide();
    const target = elementTarget(event.composedPath()[0] ?? event.target);
    // Browser-owned frames, form pickers and resize controls retain native feedback.
    if (target?.closest(NATIVE_TARGETS)) return hide();
    const textarea = target?.closest('textarea');
    if (textarea) {
      const style = win.getComputedStyle(textarea);
      if (style.resize !== 'none') {
        const rect = textarea.getBoundingClientRect();
        const corner = style.direction === 'rtl' ? x < rect.left + 18 : x > rect.right - 18;
        if (corner && y > rect.bottom - 18) return hide();
      }
    }
    const pressed = event.type === 'pointerdown' || (event.type !== 'pointerup' && event.buttons !== 0);
    lastPoint = {x, y, target, pressed};
    refresh();
  }

  function listen(target: EventTarget, type: string, listener: EventListener, options?: AddEventListenerOptions) {
    target.addEventListener(type, listener, options);
    cleanup.push(() => target.removeEventListener(type, listener, options));
  }

  const pointer = point as EventListener;
  const passiveCapture = {capture: true, passive: true};
  for (const type of ['pointermove', 'pointerdown', 'pointerup', 'pointerover']) listen(doc, type, pointer, passiveCapture);
  listen(doc, 'pointerout', ((event: PointerEvent) => { if (event.relatedTarget === null) hide(); }) as EventListener, passiveCapture);
  listen(doc, 'pointercancel', () => hide(), passiveCapture);
  listen(doc, 'keydown', ((event: KeyboardEvent) => { if (event.key === 'Tab') hide(); }) as EventListener, {capture: true});
  listen(doc, 'visibilitychange', () => hide());
  listen(win, 'blur', () => hide());
  listen(win, 'pointerleave', () => hide(), {passive: true});
  listen(win, 'pagehide', () => hide());
  listen(win, 'resize', () => hide(), {passive: true});
  for (const query of [fine, reduced, forced]) listen(query, 'change', () => hide());
  hide();

  const dispose = () => {
    disposed = true;
    for (const remove of cleanup) remove();
    hide();
  };
  return Object.assign(dispose, {refresh});
}
