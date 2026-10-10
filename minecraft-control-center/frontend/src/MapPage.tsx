import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {request} from './api';
import {formatNumber, useApp, useResource} from './context';
import {useLive} from './live';
import {GameImage, assetName, useAssets} from './assets';
import {Button, ErrorBox, Icon} from './ui';
import {IsometricMap} from './isometric-map';
import {INITIAL_ZOOM, mergeSurfaceTiles, project, unproject, viewportFor} from './map-surface';
import type {Point, SurfaceCell, SurfaceTile, Terrain, WorldIndex} from './map-surface';
import './IsometricMap.css';

const coordinates = (p: Point) => [p.x, p.y, p.z].map(Math.floor).join(', ');
const detail = (error: unknown) => error instanceof Error ? error.message : String(error);
type Viewport = ReturnType<typeof viewportFor>;

function WorldOverview({index, cx, cz, jump, t}: {index: WorldIndex | null; cx: number; cz: number; jump: (x: number, z: number) => void; t: (ru: string, en: string) => string}) {
  const canvas = useRef<HTMLCanvasElement>(null);
  const base = useMemo(() => {
    if (!index?.bounds) return null;
    const {minX, maxX, minZ, maxZ} = index.bounds;
    const origin = {x: (minX + maxX + 1) / 2, y: 0, z: (minZ + maxZ + 1) / 2};
    const scale = Math.min(264 / ((maxX - minX + maxZ - minZ + 2) * 8), 140 / ((maxX - minX + maxZ - minZ + 2) * 4));
    const image = document.createElement('canvas'); image.width = 288; image.height = 164;
    const ctx = image.getContext('2d')!; ctx.fillStyle = '#81e9a1';
    const pixels = new Set<number>();
    for (const region of index.regions) {
      const bits = atob(region.presence);
      for (let i = 0; i < 1024; i++) {
        if (!(bits.charCodeAt(i >> 3) & (1 << (i & 7)))) continue;
        const p = project({x: region.rx * 32 + i % 32 + .5, y: 0, z: region.rz * 32 + Math.floor(i / 32) + .5}, origin);
        const x = Math.floor(144 + p.x * scale), y = Math.floor(82 + p.y * scale), key = x + y * 288;
        if (x < 0 || x >= 288 || y < 0 || y >= 164 || pixels.has(key)) continue;
        pixels.add(key); ctx.fillRect(x, y, Math.max(1, 8 * scale), Math.max(1, 4 * scale));
      }
    }
    return {image, origin, scale};
  }, [index]);
  useEffect(() => {
    const ctx = canvas.current?.getContext('2d'); if (!ctx) return;
    ctx.fillStyle = '#111c19'; ctx.fillRect(0, 0, 288, 164);
    if (!base) return;
    ctx.globalAlpha = .55; ctx.drawImage(base.image, 0, 0); ctx.globalAlpha = 1;
    const p = project({x: cx + .5, y: 0, z: cz + .5}, base.origin);
    ctx.strokeStyle = '#f2d88b'; ctx.lineWidth = 2; ctx.strokeRect(144 + p.x * base.scale - 4, 82 + p.y * base.scale - 4, 8, 8);
  }, [base, cx, cz]);
  return <section className="iso-map-panel"><div className="iso-map-panel-heading"><h3>{t('Сохранённые области', 'Saved areas')}</h3><span>{formatNumber(index?.savedChunks)} {t('чанков', 'chunks')}</span></div>
    <canvas ref={canvas} width={288} height={164} aria-label={t('Сохранённые области мира. Для точного перехода используйте координаты.', 'Saved world areas. Use coordinates for precise navigation.')} onClick={e => {
      if (!base) return;
      const rect = e.currentTarget.getBoundingClientRect();
      const p = unproject({x: ((e.clientX - rect.left) * 288 / rect.width - 144) / base.scale, y: ((e.clientY - rect.top) * 164 / rect.height - 82) / base.scale}, base.origin);
      jump(Math.floor(p.x) * 16, Math.floor(p.z) * 16);
    }}/><p>{index?.truncated ? t('Обзор ограничен 2048 файлами регионов.', 'Overview is limited to 2048 region files.') : t('Выберите область или введите координаты.', 'Choose an area or enter coordinates.')}</p>
  </section>;
}

export default function MapPage() {
  const {t, lang} = useApp(), live = useLive(), assets = useAssets();
  const worlds = live.snapshot?.worlds ?? [], [worldName, setWorldName] = useState('');
  const world = worlds.find(w => w.name === worldName) ?? worlds[0];
  const canvas = useRef<HTMLCanvasElement>(null), view = useRef<IsometricMap | null>(null);
  const [center, setCenter] = useState<Point>({x: 0, y: 64, z: 0}), [zoom, setZoom] = useState(INITIAL_ZOOM);
  const [viewport, setViewport] = useState<Viewport>({cx: 0, cz: 0, radius: 2, step: 1});
  const [cache, setCache] = useState<{world: string; tiles: SurfaceTile[]}>({world: '', tiles: []});
  const [loading, setLoading] = useState(true), [pending, setPending] = useState(false), [error, setError] = useState('');
  const [revision, setRevision] = useState(0), [hover, setHover] = useState<SurfaceCell | null>(null), [selected, setSelected] = useState<SurfaceCell | null>(null);
  const [draft, setDraft] = useState({x: '0', z: '0'});
  const index = useResource<WorldIndex>(world ? '/api/map/index?world=' + encodeURIComponent(world.name) : null);
  const lastWorld = useRef('');

  useEffect(() => {
    if (!canvas.current) return;
    const instance = new IsometricMap(canvas.current, {
      onView: (point, scale, next) => {
        setCenter(point); setZoom(scale);
        setViewport(previous => previous.cx === next.cx && previous.cz === next.cz && previous.radius === next.radius ? previous : next);
      }, onHover: setHover, onSelect: setSelected
    });
    view.current = instance;
    return () => {instance.dispose(); view.current = null; lastWorld.current = '';};
  }, []);
  useEffect(() => {
    if (!world || lastWorld.current === world.name) return;
    lastWorld.current = world.name;
    const point = {x: world.spawn.x, y: world.spawn.y, z: world.spawn.z};
    view.current?.clear(world.name); view.current?.setCenter(point, true);
    setCenter(point); setZoom(INITIAL_ZOOM); setDraft({x: String(Math.floor(point.x)), z: String(Math.floor(point.z))});
    setSelected(null); setHover(null); setCache({world: world.name, tiles: []}); setError(''); setLoading(true);
    const rect = canvas.current?.getBoundingClientRect();
    setViewport(viewportFor(point, rect?.width ?? 800, rect?.height ?? 580, INITIAL_ZOOM));
  }, [world]);
  useEffect(() => {view.current?.setAssets(assets);}, [assets]);

  // Requests are scoped to the visible chunk window and stop when hidden.
  useEffect(() => {
    if (!world) return;
    const name = world.name, expected = viewportFor(center, canvas.current?.clientWidth ?? 800, canvas.current?.clientHeight ?? 580, zoom);
    if (expected.cx !== viewport.cx || expected.cz !== viewport.cz) return;
    setLoading(true); setPending(false); setError('');
    let active = true, timer = 0, controller: AbortController | null = null, attempts = 0;
    const schedule = (ms: number) => {clearTimeout(timer); if (active && !document.hidden) timer = window.setTimeout(load, ms);};
    async function load() {
      if (!active || document.hidden) return;
      controller?.abort(); controller = new AbortController();
      const current = controller, deadline = window.setTimeout(() => current.abort(), 20000);
      setLoading(true);
      try {
        const query = new URLSearchParams({world: name, cx: String(viewport.cx), cz: String(viewport.cz), radius: String(viewport.radius), step: '1'});
        const data = await request<Terrain>('/api/map/terrain?' + query, {signal: current.signal});
        if (!active || current.signal.aborted) return;
        setLoading(false); setError(''); setPending(data.pending);
        setCache(previous => ({world: name, tiles: mergeSurfaceTiles(previous.world, previous.tiles, name, data.tiles, viewport)}));
        if (data.pending && ++attempts < 16) schedule(1800); else schedule(120000);
      } catch (reason) {
        if (active && !document.hidden) {setLoading(false); setPending(false); setError(detail(reason));}
      } finally {clearTimeout(deadline);}
    }
    const visibility = () => {clearTimeout(timer); if (document.hidden) controller?.abort(); else {attempts = 0; schedule(250);}};
    document.addEventListener('visibilitychange', visibility); schedule(250);
    return () => {active = false; clearTimeout(timer); controller?.abort(); document.removeEventListener('visibilitychange', visibility);};
    // Moving inside one chunk only repaints the cached image.
  }, [world?.name, viewport, revision]);

  const tiles = useMemo(() => cache.world === world?.name ? cache.tiles.filter(tile => Math.abs(tile.cx - viewport.cx) <= viewport.radius && Math.abs(tile.cz - viewport.cz) <= viewport.radius) : [], [cache, world?.name, viewport]);
  useEffect(() => {view.current?.setTerrain(tiles, world?.name ?? '');}, [tiles, world?.name]);
  const players = live.players.filter(p => p.online && p.world === world?.name && typeof p.x === 'number' && typeof p.y === 'number' && typeof p.z === 'number');
  useEffect(() => {
    view.current?.setMarkers(world ? [{...world.spawn, id: 'spawn', name: 'Spawn', spawn: true}, ...players.map(p => ({id: p.uuid, name: p.name, x: p.x!, y: p.y!, z: p.z!}))] : []);
  }, [world, players]);
  const jump = useCallback((x: number, z: number, y?: number) => {
    const point = {x: Math.max(-29999840, Math.min(29999840, x)), y: y ?? world?.spawn.y ?? 64, z: Math.max(-29999840, Math.min(29999840, z))};
    view.current?.setCenter(point, false, y === undefined); setDraft({x: String(Math.floor(point.x)), z: String(Math.floor(point.z))});
  }, [world?.spawn.y]);
  const ready = tiles.filter(tile => tile.status === 'ready').length, point = selected ?? hover;
  const tileFailure = tiles.find(tile => tile.status === 'error' || tile.refreshStatus === 'error');
  const mapError = error || (tileFailure ? t('Не удалось загрузить участок карты. Повторите попытку.', 'A map area could not be loaded. Please try again.') : '');
  const deferred = tiles.some(tile => tile.status === 'deferred' || tile.refreshStatus === 'deferred');
  const worldItem = world?.environment === 'NETHER' ? 'netherrack' : world?.environment === 'THE_END' ? 'end_stone' : 'grass_block';

  return <div className="iso-map-page">
    <header className="page-heading"><div><span className="eyebrow">{t('Твой мир', 'Your world')}</span><h1>{t('Карта мира', 'World map')}</h1><p>{t('Знакомые места. Новые маршруты.', 'Familiar places. New paths.')}</p></div><span className="iso-map-view-label"><span/>{t('Изометрия', 'Isometric view')}</span></header>
    <div className="iso-map-toolbar">
      <label className="iso-map-world"><GameImage kind="blocks" id={worldItem} size={28}/><span className="sr-only">{t('Мир', 'World')}</span><select value={world?.name ?? ''} onChange={e => setWorldName(e.target.value)}>{worlds.map(w => <option key={w.uuid} value={w.name}>{w.name}</option>)}</select></label>
      <form className="iso-map-coordinates" onSubmit={e => {e.preventDefault(); const x = Number(draft.x), z = Number(draft.z); if (draft.x.trim() && draft.z.trim() && Number.isFinite(x) && Number.isFinite(z)) jump(x, z);}}>
        {(['x', 'z'] as const).map(axis => <label key={axis}><span>{axis.toUpperCase()}</span><input type="number" required step="1" min={-29999840} max={29999840} aria-label={t('Координата', 'Coordinate') + ' ' + axis.toUpperCase()} value={draft[axis]} onChange={e => setDraft({...draft, [axis]: e.target.value})}/></label>)}
        <Button type="submit" disabled={!world}>{t('Перейти', 'Go')}</Button>
      </form>
      <div className="iso-map-toolbar-actions"><Button disabled={!world} onClick={() => world && jump(world.spawn.x, world.spawn.z, world.spawn.y)}><GameImage kind="items" id="compass" size={20}/>{t('Спавн', 'Spawn')}</Button><Button aria-label={t('Обновить карту', 'Refresh map')} title={t('Обновить карту', 'Refresh map')} onClick={() => setRevision(n => n + 1)} disabled={!world || loading}><Icon name="refresh"/></Button></div>
    </div>
    {mapError && <ErrorBox error={mapError} retry={() => setRevision(n => n + 1)}/>}
    <div className="iso-map-layout">
      <section className="iso-map-stage" data-smooth-scroll="off" aria-label={t('Карта', 'Map')}>
        <canvas ref={canvas} tabIndex={0} role="img" aria-label={t('Изометрическая карта поверхности мира', 'Isometric world surface map')} aria-describedby="iso-map-help"/>
        <div className="iso-map-location"><span>{world?.name ?? t('Загрузка мира…', 'Loading world…')}</span><strong>X {formatNumber(Math.floor(center.x))}<i/>Z {formatNumber(Math.floor(center.z))}</strong></div>
        <div className="iso-map-compass" aria-hidden="true"><span>N</span><b>↖</b><span>E ↗</span></div>
        {!ready && <div className="iso-map-empty" role="status"><GameImage kind="items" id="filled_map" size={48}/><h2>{mapError ? t('Участок недоступен', 'Area unavailable') : deferred ? t('Сервер занят', 'Server is busy') : loading || pending ? t('Открываем местность…', 'Opening terrain…') : t('Здесь пока нет карты', 'No map here yet')}</h2><p>{mapError ? t('Повторите загрузку или вернитесь к спавну.', 'Retry loading or return to spawn.') : deferred ? t('Загрузка продолжится, когда сервер освободится.', 'Loading will resume when the server is available.') : loading || pending ? t('Загружаем сохранённые участки мира.', 'Loading saved parts of the world.') : t('Эта область ещё не сохранена. Вернитесь к спавну или выберите другой участок.', 'This area has not been saved yet. Return to spawn or choose another area.')}</p>{!loading && !pending && <Button disabled={!world} onClick={() => world && jump(world.spawn.x, world.spawn.z, world.spawn.y)}>{t('К спавну', 'Return to spawn')}</Button>}</div>}
        <div className="iso-map-controls"><div className="iso-map-zoom"><Button aria-label={t('Приблизить', 'Zoom in')} onClick={() => view.current?.zoomBy(1.25)}>+</Button><span>{Math.round(zoom * 100)}%</span><Button aria-label={t('Отдалить', 'Zoom out')} onClick={() => view.current?.zoomBy(.8)}>−</Button></div><div className="iso-map-pan">{([{label: t('Вверх', 'Up'), glyph: '↑', delta: [-8, -8]}, {label: t('Влево', 'Left'), glyph: '←', delta: [-8, 8]}, {label: t('Вправо', 'Right'), glyph: '→', delta: [8, -8]}, {label: t('Вниз', 'Down'), glyph: '↓', delta: [8, 8]}]).map(({label, glyph, delta}) => <Button key={glyph} aria-label={label} onClick={() => view.current?.pan(delta[0], delta[1])}>{glyph}</Button>)}</div></div>
        <div className="iso-map-status"><span className={loading || pending ? 'is-loading' : ''}/>{mapError ? t('Участок недоступен', 'Area unavailable') : deferred ? t('Сервер занят · загрузка отложена', 'Server busy · loading deferred') : loading || pending ? t('Подгружаем участки', 'Loading areas') : ready ? t('Местность загружена', 'Terrain loaded') : t('Нет карты участка', 'No map data here')}</div>
      </section>
      <aside className="iso-map-sidebar">
        <WorldOverview index={index.data} cx={viewport.cx} cz={viewport.cz} jump={jump} t={t}/>
        {index.error && <ErrorBox error={index.error} retry={index.reload}/>}
        <section className="iso-map-panel"><div className="iso-map-panel-heading"><h3>{t('На карте', 'On the map')}</h3><span>{players.length} {t('в игре', 'online')}</span></div><button className="iso-map-marker" disabled={!world} onClick={() => world && jump(world.spawn.x, world.spawn.z, world.spawn.y)}><GameImage kind="items" id="compass" size={28}/><span><strong>{t('Точка появления', 'World spawn')}</strong><small>{world ? coordinates(world.spawn) : '—'}</small></span><Icon name="arrow" size={16}/></button>{players.map(player => <button key={player.uuid} className="iso-map-marker" onClick={() => jump(player.x!, player.z!, player.y!)}><span className="iso-map-player-dot"/><span><strong>{player.name}</strong><small>{coordinates({x: player.x!, y: player.y!, z: player.z!})}</small></span><Icon name="arrow" size={16}/></button>)}{!players.length && <p>{t('Игроков в этом мире пока нет.', 'No players in this world yet.')}</p>}</section>
        <section className="iso-map-panel iso-map-point"><div className="iso-map-panel-heading"><h3>{t('Местность', 'Terrain')}</h3><span>{selected ? t('Выбрано', 'Selected') : t('Обзор', 'Explore')}</span></div>{point ? <div className="iso-map-point-detail"><GameImage kind="blocks" id={point.material} size={36}/><div><strong>{assetName(assets, 'blocks', point.material, lang)}</strong><p>{coordinates(point)}</p></div></div> : <p>{t('Наведите курсор или нажмите на участок, чтобы узнать его высоту.', 'Hover or select a surface to see its elevation.')}</p>}</section>
      </aside>
    </div>
    <footer className="iso-map-footer"><p id="iso-map-help">{t('Перетаскивание — перемещение · Колесо или + / − — масштаб · Стрелки — движение', 'Drag to move · Wheel or + / − to zoom · Arrow keys to pan')}</p><span><i className="spawn"/>{t('Спавн', 'Spawn')}<i/>{t('Игроки', 'Players')}</span></footer>
  </div>;
}
