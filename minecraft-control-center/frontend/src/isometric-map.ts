import {INITIAL_ZOOM, ISO_X, ISO_Y, ISO_Z, MAX_ZOOM, MIN_ZOOM, isMapClick, project, queueSurfaceFocus, resolveSurfaceFocus, shade, surfaceCells, unproject, viewportFor} from './map-surface';
import type {Point, SurfaceCell, SurfaceFocus, SurfaceTile} from './map-surface';
import type {Manifest} from './assets';

type Marker = Point & {id: string; name: string; spawn?: boolean};
type Options = {onView: (center: Point, zoom: number, viewport: ReturnType<typeof viewportFor>) => void; onHover: (cell: SurfaceCell | null) => void; onSelect: (cell: SurfaceCell | null) => void};
type Texture = {image: HTMLImageElement; canvas?: HTMLCanvasElement};

/** Static terrain is baked once; dragging paints one bitmap. No idle render loop. */
export class IsometricMap {
  private ctx: CanvasRenderingContext2D;
  private observer: ResizeObserver;
  private center: Point = {x: 0, y: 64, z: 0};
  private zoom = INITIAL_ZOOM;
  private width = 1;
  private height = 1;
  private frame = 0;
  private viewTimer = 0;
  private disposed = false;
  private dirtyTerrain = true;
  private signature = '';
  private tiles: SurfaceTile[] = [];
  private cells: SurfaceCell[] = [];
  private bitmap: HTMLCanvasElement | null = null;
  private bitmapBounds = {x: 0, y: 0, width: 1, height: 1};
  private bakeOrigin: Point = {x: 0, y: 64, z: 0};
  private assets: Manifest = {};
  private textures = new Map<string, Texture>();
  private markers: Marker[] = [];
  private markerSignature = '';
  private selected: SurfaceCell | null = null;
  private world = '';
  private surfaceFocus: SurfaceFocus | null = null;
  private hoverKey = '';
  private pointers = new Map<number, {x: number; y: number}>();
  private drag: {screen: {x: number; y: number}; center: Point; multi: boolean} | null = null;
  private pinch: {distance: number; zoom: number} | null = null;
  private panned = false;

  private canvas: HTMLCanvasElement;
  private options: Options;
  constructor(canvas: HTMLCanvasElement, options: Options) {
    this.canvas = canvas; this.options = options;
    const ctx = canvas.getContext('2d', {alpha: false});
    if (!ctx) throw new Error('Canvas 2D недоступен / Canvas 2D unavailable');
    this.ctx = ctx;
    canvas.dataset.mapRenderer = 'canvas2d-isometric';
    canvas.dataset.mapDraws = '0'; canvas.dataset.mapBakes = '0';
    this.observer = new ResizeObserver(() => this.resize());
    this.observer.observe(canvas);
    canvas.addEventListener('pointerdown', this.pointerDown);
    canvas.addEventListener('pointermove', this.pointerMove);
    canvas.addEventListener('pointerup', this.pointerUp);
    canvas.addEventListener('pointercancel', this.pointerCancel);
    canvas.addEventListener('pointerleave', this.pointerLeave);
    canvas.addEventListener('wheel', this.wheel, {passive: false});
    canvas.addEventListener('keydown', this.keyDown);
    document.addEventListener('visibilitychange', this.visibility);
    this.resize();
  }
  private resize() {
    const rect = this.canvas.getBoundingClientRect();
    if (!rect.width || !rect.height) return;
    this.width = rect.width; this.height = rect.height;
    const dpr = Math.min(1.5, window.devicePixelRatio || 1);
    this.canvas.width = Math.round(this.width * dpr); this.canvas.height = Math.round(this.height * dpr);
    this.schedule(); this.emitView();
  }
  private visibility = () => {if (document.hidden) {cancelAnimationFrame(this.frame); this.frame = 0;} else this.schedule();};
  private schedule() {if (!this.disposed && !document.hidden && !this.frame) this.frame = requestAnimationFrame(() => {this.frame = 0; this.draw();});}
  private emitView() {
    clearTimeout(this.viewTimer);
    this.viewTimer = window.setTimeout(() => {if (!this.disposed) this.options.onView({...this.center}, this.zoom, viewportFor(this.center, this.width, this.height, this.zoom));}, 180);
  }
  setCenter(point: Point, resetZoom = false, focusSurface = false) {
    const sameColumn = Math.floor(point.x) === Math.floor(this.center.x) && Math.floor(point.z) === Math.floor(this.center.z);
    this.center = {...point, y: focusSurface && sameColumn ? this.center.y : point.y}; if (resetZoom) this.zoom = INITIAL_ZOOM;
    this.surfaceFocus = queueSurfaceFocus(this.surfaceFocus, this.world, point, !focusSurface);
    this.resolveFocus();
    this.hoverKey = ''; this.options.onHover(null);
    this.selected = null; this.options.onSelect(null);
    this.schedule(); this.emitView();
  }
  clear(world = '') {
    this.world = world; this.surfaceFocus = null; this.panned = false;
    for (const id of this.pointers.keys()) if (this.canvas.hasPointerCapture(id)) this.canvas.releasePointerCapture(id);
    this.pointers.clear(); this.drag = null; this.pinch = null; this.canvas.classList.remove('is-dragging');
    this.tiles = []; this.cells = []; this.bitmap = null; this.signature = ''; this.selected = null;
    this.markers = []; this.markerSignature = ''; this.hoverKey = ''; this.dirtyTerrain = true;
    for (const texture of this.textures.values()) texture.image.onload = null;
    this.textures.clear(); this.options.onHover(null); this.schedule();
  }
  setTerrain(tiles: SurfaceTile[], world: string) {
    if (world !== this.world) return;
    const signature = tiles.map(t => `${t.cx},${t.cz}:${t.status}:${t.sampledAt ?? 0}:${t.refreshStatus ?? ''}`).join(';');
    if (signature === this.signature) return;
    this.hoverKey = ''; this.options.onHover(null);
    this.signature = signature; this.tiles = tiles; this.dirtyTerrain = true;
    if (this.resolveFocus()) this.emitView();
    this.schedule();
  }
  private resolveFocus() {
    const focus = this.surfaceFocus;
    if (this.pointers.size || !focus || focus.x !== Math.floor(this.center.x) || focus.z !== Math.floor(this.center.z)) return false;
    const result = resolveSurfaceFocus(focus, this.world, this.tiles);
    this.surfaceFocus = result.focus;
    if (result.height === null || result.height === this.center.y) return false;
    this.center = {...this.center, y: result.height};
    return true;
  }
  setAssets(assets: Manifest) {if (assets === this.assets) return; this.assets = assets; this.dirtyTerrain = true; this.schedule();}
  setMarkers(markers: Marker[]) {
    const signature = markers.map(m => `${m.id}:${m.name}:${m.x},${m.y},${m.z}`).join(';');
    if (signature === this.markerSignature) return;
    this.markerSignature = signature; this.markers = markers; this.schedule();
  }
  zoomBy(factor: number) {this.setZoom(this.zoom * factor);}
  private setZoom(value: number) {this.zoom = Math.min(MAX_ZOOM, Math.max(MIN_ZOOM, value)); this.schedule(); this.emitView();}
  pan(dx: number, dz: number) {this.center = {...this.center, x: Math.max(-29999840, Math.min(29999840, this.center.x + dx)), z: Math.max(-29999840, Math.min(29999840, this.center.z + dz))}; this.surfaceFocus = queueSurfaceFocus(this.surfaceFocus, this.world, this.center); this.resolveFocus(); this.schedule(); this.emitView();}
  private screen(point: Point) {const p = project(point, this.center); return {x: this.width / 2 + p.x * this.zoom, y: this.height / 2 + p.y * this.zoom};}
  private position(e: PointerEvent) {const r = this.canvas.getBoundingClientRect(); return {x: e.clientX - r.left, y: e.clientY - r.top};}
  private pointerDown = (e: PointerEvent) => {
    if (e.button !== 0) return;
    const p = this.position(e); this.pointers.set(e.pointerId, p);
    this.canvas.setPointerCapture(e.pointerId); this.canvas.focus({preventScroll: true});
    if (this.pointers.size === 1) {this.drag = {screen: p, center: {...this.center}, multi: false}; this.panned = false;}
    if (this.pointers.size === 2) {const [a, b] = [...this.pointers.values()]; this.pinch = {distance: Math.max(1, Math.hypot(a.x - b.x, a.y - b.y)), zoom: this.zoom}; if (this.drag) this.drag.multi = true;}
    this.canvas.classList.add('is-dragging');
  };
  private pointerMove = (e: PointerEvent) => {
    const p = this.position(e);
    if (this.pointers.has(e.pointerId)) {
      this.pointers.set(e.pointerId, p);
      if (this.pinch && this.pointers.size >= 2) {const [a, b] = [...this.pointers.values()]; this.setZoom(this.pinch.zoom * Math.hypot(a.x - b.x, a.y - b.y) / this.pinch.distance);}
      else if (this.drag) {const delta = unproject({x: (p.x - this.drag.screen.x) / this.zoom, y: (p.y - this.drag.screen.y) / this.zoom}, {x: 0, y: 0, z: 0}); this.panned ||= Math.abs(delta.x) + Math.abs(delta.z) > 0; this.center = {...this.drag.center, x: Math.max(-29999840, Math.min(29999840, this.drag.center.x - delta.x)), z: Math.max(-29999840, Math.min(29999840, this.drag.center.z - delta.z))}; this.schedule(); this.emitView();}
      return;
    }
    const cell = this.pick(p), key = cell ? `${cell.x},${cell.y},${cell.z}` : '';
    if (key !== this.hoverKey) {this.hoverKey = key; this.options.onHover(cell);}
  };
  private pointerUp = (e: PointerEvent) => {
    const p = this.position(e), wasClick = this.drag && !this.drag.multi && isMapClick(this.drag.screen, p);
    this.pointers.delete(e.pointerId); this.pinch = null;
    if (this.canvas.hasPointerCapture(e.pointerId)) this.canvas.releasePointerCapture(e.pointerId);
    if (wasClick) {this.selected = this.pick(p); this.options.onSelect(this.selected); this.schedule();}
    if (this.pointers.size) {this.drag = {screen: [...this.pointers.values()][0], center: {...this.center}, multi: true};} else {this.drag = null; this.canvas.classList.remove('is-dragging'); if (this.panned) this.surfaceFocus = queueSurfaceFocus(this.surfaceFocus, this.world, this.center); this.panned = false; if (this.resolveFocus()) this.schedule();}
    this.emitView();
  };
  private pointerCancel = (e: PointerEvent) => {this.pointers.delete(e.pointerId); this.drag = null; this.pinch = null; this.canvas.classList.remove('is-dragging'); if (!this.pointers.size) {if (this.panned) this.surfaceFocus = queueSurfaceFocus(this.surfaceFocus, this.world, this.center); this.panned = false; if (this.resolveFocus()) {this.schedule(); this.emitView();}}};
  private pointerLeave = () => {if (!this.drag) {this.hoverKey = ''; this.options.onHover(null);}};
  private wheel = (e: WheelEvent) => {e.preventDefault(); this.setZoom(this.zoom * Math.exp(-Math.max(-100, Math.min(100, e.deltaY)) * .0025));};
  private keyDown = (e: KeyboardEvent) => {
    const moves: Record<string, [number, number]> = {ArrowUp: [-8, -8], ArrowDown: [8, 8], ArrowLeft: [-8, 8], ArrowRight: [8, -8]};
    if (moves[e.key]) {e.preventDefault(); this.pan(...moves[e.key]);}
    if (e.key === '+' || e.key === '=') {e.preventDefault(); this.zoomBy(1.25);}
    if (e.key === '-') {e.preventDefault(); this.zoomBy(.8);}
  };
  private pick(p: {x: number; y: number}) {
    for (let i = this.cells.length - 1; i >= 0; i--) {
      const cell = this.cells[i], top = this.screen({...cell, x: cell.x + .5, z: cell.z + .5, y: cell.y + 1});
      if (Math.abs((p.x - top.x) / (ISO_X * this.zoom)) + Math.abs((p.y - top.y) / (ISO_Z * this.zoom)) <= 1) return cell;
    }
    return null;
  }
  private texture(cell: SurfaceCell) {
    const key = cell.material.toLowerCase(), entry = this.assets.blocks?.[`minecraft:${key}`] ?? this.assets.blocks?.[key];
    const url = typeof entry === 'string' ? entry : entry?.faces?.up ?? entry?.image ?? entry?.url;
    if (!url?.startsWith('/game-assets/')) return null;
    let texture = this.textures.get(cell.material);
    if (!texture) {
      // A palette fallback avoids an eviction/reload loop on unusually varied terrain.
      if (this.textures.size >= 96) return null;
      const image = new Image(); texture = {image}; this.textures.set(cell.material, texture);
      const result = texture;
      image.onload = () => {
        if (this.disposed) return;
        const tile = document.createElement('canvas'); tile.width = tile.height = 16;
        const context = tile.getContext('2d')!; context.imageSmoothingEnabled = false;
        context.drawImage(image, 0, 0, image.naturalWidth, Math.min(image.naturalHeight, image.naturalWidth), 0, 0, 16, 16);
        if (/LEAVES|GRASS_BLOCK|WATER|VINE/.test(cell.material)) {context.globalCompositeOperation = 'multiply'; context.fillStyle = cell.color; context.fillRect(0, 0, 16, 16);}
        result.canvas = tile; this.dirtyTerrain = true; this.schedule();
      };
      image.src = url;
    }
    return texture.canvas ?? null;
  }
  private polygon(ctx: CanvasRenderingContext2D, points: {x: number; y: number}[], fill: string) {
    ctx.fillStyle = fill; ctx.beginPath(); ctx.moveTo(points[0].x, points[0].y); for (const p of points.slice(1)) ctx.lineTo(p.x, p.y); ctx.closePath(); ctx.fill();
  }
  private bake() {
    this.dirtyTerrain = false; this.cells = surfaceCells(this.tiles);
    if (!this.cells.length) {
      this.bitmap = null; this.canvas.dataset.mapCells = '0'; this.canvas.dataset.mapBitmapPixels = '0';
      if (this.selected) {this.selected = null; this.options.onSelect(null);}
      return;
    }
    this.bakeOrigin = {x: this.tiles[0].cx * 16, y: this.center.y, z: this.tiles[0].cz * 16};
    const lookup = new Map(this.cells.map(c => [`${c.x},${c.z}`, c]));
    if (this.selected) {
      const old = this.selected, current = lookup.get(`${old.x},${old.z}`) ?? null;
      this.selected = current;
      if (!current || current.y !== old.y || current.blockData !== old.blockData) this.options.onSelect(current);
    }
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    for (const c of this.cells) {const p = project({...c, y: c.y + 1}, this.bakeOrigin); minX = Math.min(minX, p.x - ISO_X - 2); maxX = Math.max(maxX, p.x + ISO_X + 2); minY = Math.min(minY, p.y - 2); maxY = Math.max(maxY, p.y + ISO_Z * 2 + 2);}
    const width = Math.ceil(maxX - minX), height = Math.ceil(maxY - minY);
    const scale = Math.min(2, Math.sqrt(8_000_000 / (width * height)));
    const bitmap = document.createElement('canvas'); bitmap.width = Math.max(1, Math.floor(width * scale)); bitmap.height = Math.max(1, Math.floor(height * scale));
    const ctx = bitmap.getContext('2d')!; ctx.scale(scale, scale); ctx.translate(-minX, -minY); ctx.imageSmoothingEnabled = false;
    for (const c of this.cells) {
      const y = c.y + 1, a = project({...c, y}, this.bakeOrigin), b = project({...c, x: c.x + 1, y}, this.bakeOrigin), d = project({...c, z: c.z + 1, y}, this.bakeOrigin), f = project({...c, x: c.x + 1, z: c.z + 1, y}, this.bakeOrigin);
      const right = lookup.get(`${c.x + 1},${c.z}`), front = lookup.get(`${c.x},${c.z + 1}`);
      const side = c.material === 'GRASS_BLOCK' ? '#826344' : c.color;
      if (right && right.y < c.y) {const drop = (c.y - right.y) * ISO_Y; this.polygon(ctx, [b, f, {...f, y: f.y + drop}, {...b, y: b.y + drop}], shade(side, .68));}
      if (front && front.y < c.y) {const drop = (c.y - front.y) * ISO_Y; this.polygon(ctx, [d, f, {...f, y: f.y + drop}, {...d, y: d.y + drop}], shade(side, .84));}
      this.polygon(ctx, [a, b, f, d], c.color);
      const texture = this.texture(c);
      if (texture) {ctx.save(); ctx.transform(ISO_X / 16, ISO_Z / 16, -ISO_X / 16, ISO_Z / 16, a.x, a.y); ctx.globalAlpha = .83; ctx.drawImage(texture, 0, 0); ctx.restore();}
    }
    this.bitmap = bitmap; this.bitmapBounds = {x: minX, y: minY, width, height};
    this.canvas.dataset.mapCells = String(this.cells.length); this.canvas.dataset.mapBakes = String(Number(this.canvas.dataset.mapBakes) + 1);
    this.canvas.dataset.mapBitmapPixels = String(bitmap.width * bitmap.height);
  }
  private draw() {
    if (this.dirtyTerrain) this.bake();
    const ctx = this.ctx, dpr = this.canvas.width / this.width;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0); ctx.fillStyle = '#111c19'; ctx.fillRect(0, 0, this.width, this.height);
    ctx.strokeStyle = '#263b302f'; ctx.lineWidth = 1;
    for (let i = -12; i <= 12; i++) {ctx.beginPath(); ctx.moveTo(this.width / 2 + i * 96 * this.zoom - this.height, 0); ctx.lineTo(this.width / 2 + i * 96 * this.zoom + this.height, this.height); ctx.stroke(); ctx.beginPath(); ctx.moveTo(this.width / 2 + i * 96 * this.zoom + this.height, 0); ctx.lineTo(this.width / 2 + i * 96 * this.zoom - this.height, this.height); ctx.stroke();}
    if (this.bitmap) {const shift = project(this.bakeOrigin, this.center), b = this.bitmapBounds; ctx.imageSmoothingEnabled = false; ctx.drawImage(this.bitmap, this.width / 2 + (b.x + shift.x) * this.zoom, this.height / 2 + (b.y + shift.y) * this.zoom, b.width * this.zoom, b.height * this.zoom);}
    for (const marker of this.markers) {const p = this.screen({...marker, x: marker.x + .5, y: marker.y + 2, z: marker.z + .5}); if (p.x < -30 || p.x > this.width + 30 || p.y < -30 || p.y > this.height + 30) continue; ctx.fillStyle = '#111a16'; ctx.fillRect(p.x - 8, p.y - 18, 16, 16); ctx.fillStyle = marker.spawn ? '#f2d88b' : '#81e9a1'; ctx.fillRect(p.x - 5, p.y - 15, 10, 10); ctx.beginPath(); ctx.moveTo(p.x - 3, p.y - 2); ctx.lineTo(p.x + 3, p.y - 2); ctx.lineTo(p.x, p.y + 2); ctx.fill();}
    if (this.selected) {const c = this.selected, points = [{...c, y: c.y + 1}, {...c, x: c.x + 1, y: c.y + 1}, {...c, x: c.x + 1, z: c.z + 1, y: c.y + 1}, {...c, z: c.z + 1, y: c.y + 1}].map(p => this.screen(p)); ctx.beginPath(); ctx.moveTo(points[0].x, points[0].y); for (const p of points.slice(1)) ctx.lineTo(p.x, p.y); ctx.closePath(); ctx.strokeStyle = '#b9ffd0'; ctx.lineWidth = 2; ctx.stroke();}
    this.canvas.dataset.mapDraws = String(Number(this.canvas.dataset.mapDraws) + 1);
  }
  dispose() {
    this.disposed = true; cancelAnimationFrame(this.frame); clearTimeout(this.viewTimer); this.observer.disconnect();
    this.canvas.removeEventListener('pointerdown', this.pointerDown); this.canvas.removeEventListener('pointermove', this.pointerMove); this.canvas.removeEventListener('pointerup', this.pointerUp); this.canvas.removeEventListener('pointercancel', this.pointerCancel); this.canvas.removeEventListener('pointerleave', this.pointerLeave); this.canvas.removeEventListener('wheel', this.wheel); this.canvas.removeEventListener('keydown', this.keyDown); document.removeEventListener('visibilitychange', this.visibility);
    for (const t of this.textures.values()) t.image.onload = null; this.textures.clear(); this.bitmap = null; this.tiles = []; this.cells = [];
  }
}
