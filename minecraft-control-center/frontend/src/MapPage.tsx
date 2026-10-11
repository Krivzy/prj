import {useCallback, useEffect, useMemo, useRef, useState} from 'react';
import {request} from './api';
import {formatNumber, useApp} from './context';
import {useLive} from './live';
import {imageMapWork} from './module-view';
import {GameImage} from './assets';
import {Button, ErrorBox, Icon} from './ui';
import {fitImage, panImage, projectImage, sameMarkers, screenPoint, unprojectImage, visibleImages, zoomImage} from './saved-image-map';
import type {Camera, ImageManifest, Marker, PlayerMarkers, Position, Size} from './saved-image-map';
import './SavedImageMap.css';

const detail = (error: unknown) => error instanceof Error ? error.message : String(error);
const coordinates = (p: Position) => [p.x, p.y, p.z].map(Math.floor).join(', ');

/** Native image decoding and HTTP cache only. This API never starts a terrain render. */
function CachedTile({world, level, x, y, revision, style}: {world: string; level: number; x: number; y: number; revision: number; style: React.CSSProperties}) {
  const [source, setSource] = useState(''), currentUrl = useRef('');
  useEffect(() => {
    let active = true;
    const controller = new AbortController(), timer = window.setTimeout(() => controller.abort(), 20000);
    const query = new URLSearchParams({world, level: String(level), x: String(x), y: String(y)});
    // Stable URLs let the browser reuse ETag/304 across manifest revisions.
    fetch('/api/map/images/tile?' + query, {credentials: 'same-origin', cache: 'no-cache', signal: controller.signal}).then(async response => {
      if (response.status === 401) window.dispatchEvent(new Event('mcc-session-expired'));
      if (!response.ok || !response.headers.get('Content-Type')?.startsWith('image/png')) return;
      const blob = await response.blob();
      if (!active || blob.size > 1048576) return;
      const next = URL.createObjectURL(blob), previous = currentUrl.current;
      currentUrl.current = next; setSource(next); if (previous) URL.revokeObjectURL(previous);
    }).catch(() => {/* Missing images remain transparent until a later background revision. */}).finally(() => clearTimeout(timer));
    return () => {active = false; clearTimeout(timer); controller.abort();};
  }, [world, level, x, y, revision]);
  useEffect(() => () => {if (currentUrl.current) URL.revokeObjectURL(currentUrl.current);}, []);
  return source ? <img className="image-map-tile" src={source} alt="" draggable={false} decoding="async" style={style}/> : null;
}

export default function MapPage() {
  const {t,modules} = useApp(), live = useLive();
  const mapWork=imageMapWork(modules);
  const worlds = live.snapshot?.worlds ?? [], [worldName, setWorldName] = useState('');
  const world = worlds.find(w => w.name === worldName) ?? worlds[0];
  const name = world?.name ?? '', worldId = world?.uuid ?? '';
  const stage = useRef<HTMLDivElement>(null), [size, setSize] = useState<Size>({width: 800, height: 560});
  const [camera, setCamera] = useState<Camera>({u: 0, v: -64, scale: 2}), cameraRef = useRef(camera); cameraRef.current = camera;
  const drag = useRef<{id: number; x: number; y: number; camera: Camera} | null>(null);
  const fitting = useRef({world: '', touched: false, fitted: false, width: 0, height: 0});
  const [manifest, setManifest] = useState<ImageManifest | null>(null), [players, setPlayers] = useState<Marker[]>([]);
  const [loading, setLoading] = useState(true), [error, setError] = useState(''), [playerError, setPlayerError] = useState('');
  const [reload, setReload] = useState(0), [draft, setDraft] = useState({x: '0', z: '0'});
  const data = manifest?.worldId === worldId ? manifest : null;
  const release = useCallback(() => {
    const previous = drag.current; drag.current = null;
    if (previous && stage.current?.hasPointerCapture(previous.id)) stage.current.releasePointerCapture(previous.id);
    stage.current?.classList.remove('is-dragging');
  }, []);

  useEffect(() => {
    const element = stage.current; if (!element) return;
    const observer = new ResizeObserver(entries => {const {width, height} = entries[0].contentRect; setSize(previous => previous.width === width && previous.height === height ? previous : {width, height});});
    observer.observe(element); return () => observer.disconnect();
  }, []);
  useEffect(() => {
    release(); fitting.current = {world: worldId, touched: false, fitted: false, width: 0, height: 0};
    setManifest(null); setPlayers([]); setError(''); setPlayerError(''); setLoading(true);
    const spawn = world?.spawn ?? {x: 0, y: 64, z: 0};
    setCamera({...projectImage(spawn), scale: 2}); setDraft({x: String(Math.floor(spawn.x)), z: String(Math.floor(spawn.z))});
    return release;
    // Live snapshot updates must not reset the camera or a held pointer.
  }, [worldId, release]);

  useEffect(() => {
    if (!name||!mapWork.base){setManifest(null);setLoading(false);setError('');return;}
    let active = true, timer = 0, controller: AbortController | null = null;
    const schedule = (ms: number) => {clearTimeout(timer); if (active && !document.hidden) timer = window.setTimeout(load, ms);};
    async function load() {
      if (!active || document.hidden) return;
      const current = new AbortController(); controller = current;
      try {
        const next = await request<ImageManifest>('/api/map/images?world=' + encodeURIComponent(name), {signal: current.signal});
        if (!active || current.signal.aborted || next.worldId !== worldId) return;
        setManifest(next); setLoading(false); setError(''); schedule(next.status === 'rendering' ? 5000 : 60000);
      } catch (reason) {if (active && !current.signal.aborted) {setLoading(false); setError(detail(reason)); schedule(15000);}}
    }
    const visibility = () => {clearTimeout(timer); if (document.hidden) {controller?.abort(); release();} else schedule(100);};
    document.addEventListener('visibilitychange', visibility); schedule(0);
    return () => {active = false; clearTimeout(timer); controller?.abort(); document.removeEventListener('visibilitychange', visibility);};
  }, [name, worldId, reload, release,mapWork.base]);

  useEffect(() => {
    if (!name||!mapWork.players){setPlayers([]);setPlayerError('');return;}
    let active = true, timer = 0, controller: AbortController | null = null;
    const schedule = (ms: number) => {clearTimeout(timer); if (active && !document.hidden) timer = window.setTimeout(load, ms);};
    async function load() {
      if (!active || document.hidden) return;
      const current = new AbortController(); controller = current;
      try {
        const next = await request<PlayerMarkers>('/api/map/players?world=' + encodeURIComponent(name), {signal: current.signal});
        if (!active || current.signal.aborted || next.worldId !== worldId) return;
        setPlayers(previous => sameMarkers(previous, next.players) ? previous : next.players); setPlayerError(''); schedule(2000);
      } catch (reason) {if (active && !current.signal.aborted) {setPlayerError(detail(reason)); schedule(10000);}}
    }
    const visibility = () => {clearTimeout(timer); if (document.hidden) controller?.abort(); else schedule(100);};
    document.addEventListener('visibilitychange', visibility); schedule(0);
    return () => {active = false; clearTimeout(timer); controller?.abort(); document.removeEventListener('visibilitychange', visibility);};
  }, [name, worldId, reload,mapWork.players]);

  useEffect(() => {
    const state = fitting.current;
    if (state.world !== worldId || state.touched || !data?.bounds || !data.scannedAt) return;
    if (!state.fitted || state.width !== size.width || state.height !== size.height) {
      setCamera(fitImage(data.bounds, size)); state.fitted = true; state.width = size.width; state.height = size.height;
    }
  }, [worldId, data?.bounds, data?.scannedAt, size]);

  const move = useCallback((dx: number, dy: number) => {fitting.current.touched = true; setCamera(previous => panImage(previous, dx, dy));}, []);
  const zoom = useCallback((factor: number, pointer?: {x: number; y: number}) => {fitting.current.touched = true; setCamera(previous => zoomImage(previous, factor, size, pointer));}, [size]);
  useEffect(() => {
    const element = stage.current; if (!element) return;
    const wheel = (event: WheelEvent) => {event.preventDefault(); release(); const rect = element.getBoundingClientRect(); zoom(Math.exp(-Math.max(-120, Math.min(120, event.deltaY)) * .0025), {x: event.clientX - rect.left, y: event.clientY - rect.top});};
    element.addEventListener('wheel', wheel, {passive: false}); return () => element.removeEventListener('wheel', wheel);
  }, [zoom, release]);
  const jump = (point: Position) => {release(); fitting.current.touched = true; setCamera(previous => ({...projectImage(point), scale: Math.max(1, previous.scale)})); setDraft({x: String(Math.floor(point.x)), z: String(Math.floor(point.z))});};
  const fit = () => {release(); if (data?.bounds) {fitting.current.touched = true; setCamera(fitImage(data.bounds, size));}};
  const images = useMemo(() => visibleImages(camera, size, data?.maxLevel ?? 20, data?.bounds ?? null), [camera, size, data?.maxLevel, data?.bounds]);
  const center = unprojectImage(camera, world?.spawn.y ?? 64), updated = data?.updatedAt ? new Date(data.updatedAt).toLocaleTimeString() : '—';
  const stateLabel = loading ? t('Открываем карту…', 'Opening map…') : data?.status === 'paused' ? t('Обновление на паузе', 'Updates paused') : data?.status === 'rendering' ? t('Рисуем сохранённый мир', 'Rendering saved world') : data?.status === 'empty' ? t('Мир пока не сохранён', 'No saved terrain yet') : data?.status === 'error' ? t('Обновление временно недоступно', 'Updates temporarily unavailable') : t('Карта сохранена', 'Map saved');
  const worldItem = world?.environment === 'NETHER' ? 'netherrack' : world?.environment === 'THE_END' ? 'end_stone' : 'grass_block';

  return <div className="image-map-page">
    <header className="page-heading"><div><span className="eyebrow">{t('Твой мир', 'Your world')}</span><h1>{t('Карта мира', 'World map')}</h1><p>{t('Весь знакомый мир. Игроки здесь и сейчас.', 'Your whole world. Players here and now.')}</p></div><span className="image-map-view-label"><i/>{t('Изометрия', 'Isometric view')}</span></header>
    <div className="image-map-toolbar">
      <label className="image-map-world"><GameImage kind="blocks" id={worldItem} size={28}/><span className="sr-only">{t('Мир', 'World')}</span><select value={name} onChange={e => {release(); setWorldName(e.target.value);}}>{worlds.map(w => <option key={w.uuid} value={w.name}>{w.name}</option>)}</select></label>
      <form className="image-map-coordinates" onSubmit={e => {e.preventDefault(); const x = Number(draft.x), z = Number(draft.z); if (draft.x.trim() && draft.z.trim() && Number.isFinite(x) && Number.isFinite(z)) jump({x: Math.max(-29999840, Math.min(29999840, x)), y: world?.spawn.y ?? 64, z: Math.max(-29999840, Math.min(29999840, z))});}}>
        {(['x', 'z'] as const).map(axis => <label key={axis}><span>{axis.toUpperCase()}</span><input type="number" required min={-29999840} max={29999840} step="1" aria-label={t('Координата', 'Coordinate') + ' ' + axis.toUpperCase()} value={draft[axis]} onChange={e => setDraft({...draft, [axis]: e.target.value})}/></label>)}<Button type="submit" disabled={!world}>{t('Перейти', 'Go')}</Button>
      </form>
      <div className="image-map-toolbar-actions"><Button onClick={fit} disabled={!data?.bounds}>{t('Весь мир', 'Whole world')}</Button><Button disabled={!world} onClick={() => world && jump(world.spawn)}><GameImage kind="items" id="compass" size={20}/>{t('Спавн', 'Spawn')}</Button><Button aria-label={t('Обновить состояние карты', 'Refresh map status')} title={t('Обновить состояние карты', 'Refresh map status')} onClick={() => setReload(n => n + 1)} disabled={!world || loading}><Icon name="refresh"/></Button></div>
    </div>
    {(error || data?.status === 'error' && data.message) && <ErrorBox error={error || data?.message || ''} retry={() => setReload(n => n + 1)}/>}
    <div className="image-map-layout">
      <section className="image-map-card">
        <div ref={stage} className="image-map-stage" tabIndex={0} role="region" aria-label={t('Изометрическая карта мира', 'Isometric world map')} aria-describedby="image-map-help" data-smooth-scroll="off" data-image-tiles={images.length} data-image-level={images[0]?.level ?? 0} onPointerDown={e => {
          if (e.button !== 0 || (e.target as HTMLElement).closest('button')) return;
          release(); fitting.current.touched = true; stage.current?.focus({preventScroll: true});
          drag.current = {id: e.pointerId, x: e.clientX, y: e.clientY, camera: cameraRef.current}; e.currentTarget.setPointerCapture(e.pointerId); e.currentTarget.classList.add('is-dragging');
        }} onPointerMove={e => {const held = drag.current; if (!held || held.id !== e.pointerId) return; setCamera(panImage(held.camera, held.x - e.clientX, held.y - e.clientY));}} onPointerUp={release} onPointerCancel={release} onLostPointerCapture={release} onKeyDown={e => {
          const delta: Record<string, [number, number]> = {ArrowLeft: [-64, 0], ArrowRight: [64, 0], ArrowUp: [0, -64], ArrowDown: [0, 64]};
          if (delta[e.key]) {e.preventDefault(); release(); move(...delta[e.key]);} else if (e.key === '+' || e.key === '=') {e.preventDefault(); zoom(1.4);} else if (e.key === '-') {e.preventDefault(); zoom(1 / 1.4);} else if (e.key === 'Escape') release();
        }}>
          <div className="image-map-imagery" aria-hidden="true">{name && images.map(tile => {const position = screenPoint(tile, camera, size), span = tile.span * camera.scale; return <CachedTile key={`${worldId}:${tile.level}:${tile.x}:${tile.y}`} world={name} {...tile} revision={data?.revision ?? 0} style={{left: position.x, top: position.y, width: span + .25, height: span + .25}}/>;})}</div>
          {world && (() => {const point = screenPoint(projectImage(world.spawn), camera, size); return <button className="image-map-pin is-spawn" style={{left: point.x, top: point.y}} onClick={() => jump(world.spawn)} aria-label={t('Точка появления', 'World spawn')} title={t('Точка появления', 'World spawn')}><span>⌂</span></button>;})()}
          {players.map(player => {const point = screenPoint(projectImage(player), camera, size); return <button key={player.uuid} className="image-map-pin is-player" style={{left: point.x, top: point.y}} onClick={() => jump(player)} aria-label={player.name}><span/><strong>{player.name}</strong></button>;})}
          <div className="image-map-location"><span>{name || t('Загрузка мира…', 'Loading world…')}</span><strong>X {formatNumber(Math.floor(center.x))}<i/>Z {formatNumber(Math.floor(center.z))}</strong></div>
          <div className="image-map-compass" aria-hidden="true"><b>N ↖</b><span>E ↗</span></div>
          {!data?.bounds && <div className="image-map-empty" role="status"><GameImage kind="items" id="filled_map" size={48}/><h2>{data?.status === 'error' ? t('Карта недоступна', 'Map unavailable') : loading || data?.status === 'rendering' ? t('Рисуем твой мир…', 'Mapping your world…') : data?.status === 'paused' ? t('Сервер занят', 'Server is busy') : t('Карта появится здесь', 'Your map will appear here')}</h2><p>{data?.status === 'error' ? t('Не удалось прочитать сохранённую местность. Проверьте сообщение об ошибке.', 'Saved terrain could not be read. Check the error message.') : loading || data?.status === 'rendering' ? t('Готовые участки появятся автоматически.', 'Rendered areas will appear automatically.') : data?.status === 'paused' ? t('Сохранённая карта остаётся доступной. Обновление продолжится позже.', 'Saved images remain available. Updates will resume later.') : t('Исследуйте мир в игре. Карта обновляется после сохранения мира.', 'Explore in game. The map updates after the world is saved.')}</p></div>}
        </div>
        <div className="image-map-controls"><div className="image-map-zoom"><Button aria-label={t('Приблизить', 'Zoom in')} onClick={() => {release(); zoom(1.4);}}>+</Button><span>{camera.scale >= .1 ? Math.round(camera.scale * 100) : (camera.scale * 100).toFixed(3)}%</span><Button aria-label={t('Отдалить', 'Zoom out')} onClick={() => {release(); zoom(1 / 1.4);}}>−</Button></div><div className="image-map-pan">{([{label: t('Вверх', 'Up'), glyph: '↑', delta: [0, -64]}, {label: t('Влево', 'Left'), glyph: '←', delta: [-64, 0]}, {label: t('Вправо', 'Right'), glyph: '→', delta: [64, 0]}, {label: t('Вниз', 'Down'), glyph: '↓', delta: [0, 64]}] as {label: string; glyph: string; delta: [number, number]}[]).map(({label, glyph, delta}) => <Button key={glyph} aria-label={label} onClick={() => {release(); move(...delta);}}>{glyph}</Button>)}</div></div>
      </section>
      <aside className="image-map-sidebar">
        <section className="image-map-panel"><div className="image-map-panel-heading"><h3>{t('Твой атлас', 'Your atlas')}</h3><span className={'image-map-status ' + (data?.status ?? '')}><i/>{stateLabel}</span></div><div className="image-map-summary"><strong>{formatNumber(data?.renderedChunks ?? 0)}</strong><span>{t('сохранённых чанков на карте', 'saved chunks mapped')}</span></div><dl><div><dt>{t('Обновлено', 'Updated')}</dt><dd>{updated}</dd></div><div><dt>{t('В игре', 'Online')}</dt><dd>{players.length}</dd></div></dl><p>{t('Карта обновляется в фоне. При просмотре меняются только позиции игроков.', 'The map updates in the background. Only player positions are live.')}</p>{data?.status === 'rendering' && <small>{t('Первое построение может занять время. Уже готовая местность доступна.', 'The first render takes time. Finished terrain is available as it is saved.')}</small>}</section>
        <section className="image-map-panel"><div className="image-map-panel-heading"><h3>{t('На карте', 'On the map')}</h3><span>{players.length} {t('в игре', 'online')}</span></div><button className="image-map-marker" disabled={!world} onClick={() => world && jump(world.spawn)}><GameImage kind="items" id="compass" size={28}/><span><strong>{t('Точка появления', 'World spawn')}</strong><small>{world ? coordinates(world.spawn) : '—'}</small></span><Icon name="arrow" size={16}/></button>{players.map(player => <button key={player.uuid} className="image-map-marker" onClick={() => jump(player)}><i className="image-map-player-dot"/><span><strong>{player.name}</strong><small>{coordinates(player)}</small></span><Icon name="arrow" size={16}/></button>)}{!players.length && <p>{t('Игроков в этом мире пока нет.', 'No players in this world yet.')}</p>}{playerError && <p className="image-map-overlay-error" role="status">{t('Позиции игроков временно недоступны.', 'Player positions are temporarily unavailable.')}</p>}</section>
      </aside>
    </div>
    <footer className="image-map-footer"><p id="image-map-help">{t('Перетаскивание — перемещение · Колесо или + / − — масштаб · Стрелки — движение', 'Drag to move · Wheel or + / − to zoom · Arrow keys to pan')}</p><span><i className="spawn"/>{t('Спавн', 'Spawn')}<i/>{t('Игроки', 'Players')}</span></footer>
  </div>;
}
