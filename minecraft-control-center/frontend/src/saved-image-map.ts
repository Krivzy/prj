/** Cached image coordinates shared by PNG tiles and live player markers. No terrain geometry. */
export type Position = {x: number; y: number; z: number};
export type ImagePoint = {u: number; v: number};
export type Camera = ImagePoint & {scale: number};
export type Size = {width: number; height: number};
export type ImageBounds = {minU: number; minV: number; maxU: number; maxV: number};
export type ImageManifest = {
  world: string; worldId: string; tileSize: number; maxLevel: number;
  bounds: ImageBounds | null; spawn: Position;
  status: 'rendering' | 'ready' | 'paused' | 'empty' | 'error';
  renderedChunks: number; savedSlots: number; revision: number; pendingTiles: number;
  updatedAt: number; scannedAt: number; failures: number; message: string;
  generatedOnly: boolean; backgroundOnly: boolean;
};
export type Marker = Position & {uuid: string; name: string};
export type PlayerMarkers = {world: string; worldId: string; players: Marker[]; timestamp: number};
export const MIN_SCALE = 2 ** -20, MAX_SCALE = 8, TILE_SIZE = 256, MAX_VISIBLE_IMAGES = 96;
export const projectImage = (p: Position): ImagePoint => ({u: (p.x - p.z) * 2, v: p.x + p.z - p.y});
export const unprojectImage = (p: ImagePoint, y: number): Position => ({x: (p.u / 2 + p.v + y) / 2, y, z: (-p.u / 2 + p.v + y) / 2});
export const screenPoint = (p: ImagePoint, camera: Camera, size: Size) => ({x: (p.u - camera.u) * camera.scale + size.width / 2, y: (p.v - camera.v) * camera.scale + size.height / 2});
export function fitImage(bounds: ImageBounds, size: Size): Camera {
  const scale = Math.min(Math.max(1, size.width - 64) / Math.max(1, bounds.maxU - bounds.minU), Math.max(1, size.height - 64) / Math.max(1, bounds.maxV - bounds.minV));
  return {u: (bounds.minU + bounds.maxU) / 2, v: (bounds.minV + bounds.maxV) / 2, scale: Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale))};
}
export function zoomImage(camera: Camera, factor: number, size: Size, pointer = {x: size.width / 2, y: size.height / 2}): Camera {
  const scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, camera.scale * factor));
  const x = pointer.x - size.width / 2, y = pointer.y - size.height / 2;
  return {u: camera.u + x / camera.scale - x / scale, v: camera.v + y / camera.scale - y / scale, scale};
}
export const panImage = (camera: Camera, dx: number, dy: number): Camera => ({...camera, u: camera.u + dx / camera.scale, v: camera.v + dy / camera.scale});
/** Only DOM/image count is bounded. There is no saved-world radius or tile inventory limit. */
export function visibleImages(camera: Camera, size: Size, maxLevel = 20, bounds: ImageBounds | null = null) {
  let level = Math.min(maxLevel, Math.max(0, Math.floor(-Math.log2(camera.scale))));
  function windowAt(l: number) {
    const span = TILE_SIZE * 2 ** l;
    let minX = Math.floor((camera.u - size.width / (2 * camera.scale)) / span) - 1;
    let maxX = Math.floor((camera.u + size.width / (2 * camera.scale)) / span) + 1;
    let minY = Math.floor((camera.v - size.height / (2 * camera.scale)) / span) - 1;
    let maxY = Math.floor((camera.v + size.height / (2 * camera.scale)) / span) + 1;
    // Clamp before iteration: a tiny saved island at minimum zoom still costs only its PNGs.
    if (bounds) {minX = Math.max(minX, Math.floor(bounds.minU / span)); maxX = Math.min(maxX, Math.floor(bounds.maxU / span)); minY = Math.max(minY, Math.floor(bounds.minV / span)); maxY = Math.min(maxY, Math.floor(bounds.maxV / span));}
    return {span, minX, maxX, minY, maxY};
  }
  let window = windowAt(level);
  while ((window.maxX - window.minX + 1) * (window.maxY - window.minY + 1) > MAX_VISIBLE_IMAGES && level < maxLevel) window = windowAt(++level);
  const tiles: {level: number; x: number; y: number; u: number; v: number; span: number}[] = [];
  outer: for (let y = window.minY; y <= window.maxY; y++) for (let x = window.minX; x <= window.maxX; x++) {
    if (tiles.length >= MAX_VISIBLE_IMAGES) break outer;
    const u = x * window.span, v = y * window.span;
    if (bounds && (u > bounds.maxU || v > bounds.maxV || u + window.span < bounds.minU || v + window.span < bounds.minV)) continue;
    tiles.push({level, x, y, u, v, span: window.span});
  }
  return tiles;
}
export function sameMarkers(a: Marker[], b: Marker[]) {
  return a.length === b.length && a.every((p, i) => p.uuid === b[i].uuid && p.name === b[i].name && p.x === b[i].x && p.y === b[i].y && p.z === b[i].z);
}
