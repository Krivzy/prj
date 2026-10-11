/** Original resourcepacks/default textures from fishstiz/cursors_extended (MIT).
 * Exact bytes, upstream metadata and license: public/assets/cursors/ce-provenance.json.
 * Commit a293ed53412d49c22107fb3cc409dbf68920998c. Natural 32px scale, no recoloring.
 */
const DEFAULT = {name: 'default', src: '/assets/cursors/ce-default.png', width: 32, height: 32, textureHeight: 32, xhot: 0, yhot: 0, frames: 1, frameMs: 0} as const;
const POINTING_HAND = {name: 'pointing_hand', src: '/assets/cursors/ce-pointing_hand.png', width: 32, height: 32, textureHeight: 32, xhot: 7, yhot: 0, frames: 1, frameMs: 0} as const;
const IBEAM = {name: 'ibeam', src: '/assets/cursors/ce-ibeam.png', width: 32, height: 32, textureHeight: 32, xhot: 10, yhot: 12, frames: 1, frameMs: 0} as const;
const NOT_ALLOWED = {name: 'not_allowed', src: '/assets/cursors/ce-not_allowed.png', width: 32, height: 32, textureHeight: 32, xhot: 11, yhot: 11, frames: 1, frameMs: 0} as const;
const GRABBING = {name: 'grabbing', src: '/assets/cursors/ce-grabbing.png', width: 32, height: 32, textureHeight: 32, xhot: 10, yhot: 2, frames: 1, frameMs: 0} as const;
const BUSY = {name: 'busy', src: '/assets/cursors/ce-busy.png', width: 32, height: 32, textureHeight: 256, xhot: 0, yhot: 6, frames: 8, frameMs: 50} as const;

export const CURSOR_ASSETS = {arrow: DEFAULT, action: POINTING_HAND, text: IBEAM, disabled: NOT_ALLOWED, grab: POINTING_HAND, grabbing: GRABBING, busy: BUSY} as const;
export type PixelCursorState = keyof typeof CURSOR_ASSETS;

export function pixelCursorImageReady(image: Pick<HTMLImageElement, 'complete' | 'naturalWidth' | 'naturalHeight'> | undefined, state: PixelCursorState): boolean {
  const asset = CURSOR_ASSETS[state];
  return Boolean(image?.complete && image.naturalWidth === asset.width && image.naturalHeight === asset.textureHeight);
}
