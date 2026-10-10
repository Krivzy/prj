export type Point = {x: number; y: number; z: number};
export type SurfaceBlock = {material: string; blockData: string; color: string};
export type SurfaceTile = {cx: number; cz: number; status: string; refreshing?: boolean; refreshStatus?: string; message?: string; size?: number; heights?: (number | null)[]; blocks?: (number | null)[]; palette?: SurfaceBlock[]; sampledAt?: number};
export type Terrain = {world: string; worldId: string; cx: number; cz: number; radius: number; step: number; tiles: SurfaceTile[]; players: (Point & {uuid: string; name: string})[]; pending: boolean; minY: number; maxY: number; metrics: {cache: number; pending: number; loads: number; tickets: number; snapshotMs: number}};
export type WorldIndex = {world: string; regions: {rx: number; rz: number; chunks: number; presence: string}[]; savedChunks: number; bounds: {minX: number; maxX: number; minZ: number; maxZ: number} | null; truncated: boolean; scannedAt: number};
export type SurfaceCell = Point & SurfaceBlock;
export type SurfaceFocus = {world: string; x: number; z: number; pending: boolean};
export const ISO_X = 8, ISO_Z = 4, ISO_Y = 6;
export const MIN_ZOOM = .65, MAX_ZOOM = 4, INITIAL_ZOOM = 1.6;
export const MAX_SURFACE_TILES = 64;

/** One fixed view direction. Relative coordinates keep remote locations precise. */
export function project(point: Point, origin: Point) {
  const x = point.x - origin.x, z = point.z - origin.z;
  return {x: (x - z) * ISO_X, y: (x + z) * ISO_Z - (point.y - origin.y) * ISO_Y};
}
export function unproject(point: {x: number; y: number}, origin: Point, y = origin.y): Point {
  const u = point.x / ISO_X, v = (point.y + (y - origin.y) * ISO_Y) / ISO_Z;
  return {x: origin.x + (u + v) / 2, y, z: origin.z + (v - u) / 2};
}
export function viewportFor(center: Point, width: number, height: number, zoom: number) {
  const radius = Math.min(3, Math.max(1, Math.ceil(Math.max(width / (32 * ISO_X * zoom), height / (32 * ISO_Z * zoom)) / 2)));
  return {cx: Math.max(-1874990, Math.min(1874990, Math.floor(center.x / 16))), cz: Math.max(-1874990, Math.min(1874990, Math.floor(center.z / 16))), radius, step: 1};
}
export function surfaceCells(tiles: SurfaceTile[]): SurfaceCell[] {
  const cells: SurfaceCell[] = [];
  for (const tile of tiles) {
    if (tile.status !== 'ready' || !tile.heights || !tile.blocks || !tile.palette) continue;
    for (let i = 0; i < 256; i++) {
      const y = tile.heights[i], block = tile.blocks[i];
      if (typeof y !== 'number' || !Number.isFinite(y) || Math.abs(y) > 2048 || typeof block !== 'number') continue;
      const material = tile.palette[block];
      if (!material || /^(AIR|CAVE_AIR|VOID_AIR)$/.test(material.material)) continue;
      cells.push({x: tile.cx * 16 + i % 16, y, z: tile.cz * 16 + Math.floor(i / 16), ...material});
    }
  }
  return cells.sort((a, b) => a.x + a.z - b.x - b.z || a.x - b.x);
}
/** Resolve navigation once from its exact saved column; unknown areas keep their current height. */
export function queueSurfaceFocus(previous: SurfaceFocus | null, world: string, point: Point, explicitY = false): SurfaceFocus | null {
  if (explicitY || !world || !Number.isFinite(point.x) || !Number.isFinite(point.z)) return null;
  const x = Math.floor(point.x), z = Math.floor(point.z);
  return previous?.world === world && previous.x === x && previous.z === z ? previous : {world, x, z, pending: true};
}
export function resolveSurfaceFocus(focus: SurfaceFocus | null, world: string, tiles: SurfaceTile[]): {focus: SurfaceFocus | null; height: number | null} {
  if (!focus || focus.world !== world) return {focus: null, height: null};
  if (!focus.pending) return {focus, height: null};
  const cx = Math.floor(focus.x / 16), cz = Math.floor(focus.z / 16);
  const tile = tiles.find(candidate => candidate.cx === cx && candidate.cz === cz);
  if (tile?.status !== 'ready' || !tile.heights || !tile.blocks || !tile.palette) return {focus, height: null};
  const index = focus.x - cx * 16 + (focus.z - cz * 16) * 16;
  const y = tile.heights[index], block = tile.blocks[index], material = typeof block === 'number' ? tile.palette[block] : undefined;
  if (typeof y !== 'number' || !Number.isFinite(y) || Math.abs(y) > 2048 || !material || /^(AIR|CAVE_AIR|VOID_AIR)$/.test(material.material)) return {focus, height: null};
  return {focus: {...focus, pending: false}, height: y + 1};
}
/** Retain a visible tile while refreshing; missing and world switches invalidate it. */
export function mergeSurfaceTiles(previousWorld: string | undefined, previous: SurfaceTile[] | undefined, world: string, incoming: SurfaceTile[], center: {cx: number; cz: number}, limit = MAX_SURFACE_TILES) {
  const result = new Map<string, SurfaceTile>();
  if (world === previousWorld) for (const tile of previous ?? []) if (tile.status === 'ready') result.set(`${tile.cx},${tile.cz}`, tile);
  for (const tile of incoming) {
    const key = `${tile.cx},${tile.cz}`, retained = result.get(key);
    result.set(key, retained && ['loading', 'processing', 'deferred', 'error'].includes(tile.status)
      ? {...retained, refreshing: true, refreshStatus: tile.status, message: tile.message}
      : tile);
  }
  return [...result.values()].sort((a, b) => Math.abs(a.cx - center.cx) + Math.abs(a.cz - center.cz) - Math.abs(b.cx - center.cx) - Math.abs(b.cz - center.cz)).slice(0, limit);
}
export function isMapClick(start: {x: number; y: number}, end: {x: number; y: number}) {return Math.hypot(end.x - start.x, end.y - start.y) <= 5;}
export function shade(color: string, factor: number) {
  const hex = /^#[\da-f]{6}$/i.test(color) ? color.slice(1) : '77976a';
  return '#' + [0, 2, 4].map(i => Math.round(Math.min(255, parseInt(hex.slice(i, i + 2), 16) * factor)).toString(16).padStart(2, '0')).join('');
}
