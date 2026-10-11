import {lazy, Suspense, useEffect, useId, useState} from 'react';
import {duration, formatNumber, time, useApp, useResource} from './context';
import {useLive} from './live';
import {ActionButton, Avatar, Button, Empty, Icon, ItemForm, Loading, Modal, Panel} from './ui';
import {GameImage} from './assets';
import {RestartControl} from './ServerRestart';
import {moduleEnabled} from './module-view';
import type {Catalogs, Metric} from './types';
const OverworldScene = lazy(() => import('./OverworldScene'));
const metrics = [
  {key:'tps',label:'TPS',color:'#82e6aa'}, {key:'mspt',label:'MSPT',color:'#f6b66a'},
  {key:'cpu',label:'CPU',color:'#89c9e5'}, {key:'heapUsed',label:'RAM',color:'#b8a2ee'},
  {key:'online',label:'Players',color:'#d7e78e'}, {key:'entityCount',label:'Entities',color:'#ee9b9b'},
];
const gb = (value:unknown) => typeof value === 'number' ? `${(value/1024**3).toFixed(1)} GiB` : '—';
const weatherLabels: Record<string,[string,string]> = {clear:['Ясно','Clear'],rain:['Дождь','Rain'],thunder:['Гроза','Thunder']};

export function Chart({data, metric, color}: {data:Metric[]; metric:string; color:string}) {
  const id = useId().replace(/:/g,'');
  const values = data.map(sample => {const value = sample[metric]; return Array.isArray(value) && typeof value[0] === 'number' ? value[0] : typeof value === 'number' ? value : Number.NaN;});
  const valid = values.filter(Number.isFinite);
  if (!valid.length) return <div className="chart-no-data">—</div>;
  const width = 820, height = 210, pad = 12;
  const max = Math.max(...valid,metric === 'tps' ? 20 : 1)*1.12;
  const coords = values.map((value,index) => Number.isFinite(value) ? `${pad+index/Math.max(1,values.length-1)*(width-pad*2)},${height-pad-value/max*(height-pad*2)}` : '').filter(Boolean);
  return <svg className="chart" viewBox={`0 0 ${width} ${height}`} role="img" aria-label={`${metric}: ${valid.length} samples, latest ${valid.at(-1)?.toFixed(2)}`}>
    <defs><linearGradient id={`chart-${id}`} x2="0" y2="1"><stop stopColor={color} stopOpacity=".17"/><stop offset="1" stopColor={color} stopOpacity="0"/></linearGradient></defs>
    {[0,.25,.5,.75,1].map(value => <line key={value} x1={pad} x2={width-pad} y1={pad+value*(height-pad*2)} y2={pad+value*(height-pad*2)} stroke="var(--line)" strokeDasharray="3 7"/>)}
    <polygon points={`${pad},${height-pad} ${coords.join(' ')} ${width-pad},${height-pad}`} fill={`url(#chart-${id})`}/>
    <polyline points={coords.join(' ')} fill="none" stroke={color} strokeWidth="2.5" strokeLinejoin="round"/>
    <circle cx={coords.at(-1)?.split(',')[0]} cy={coords.at(-1)?.split(',')[1]} r="4" fill={color}/>
  </svg>;
}

export default function Overview() {
  const app = useApp();
  const {snapshot:s, players, timeline, updated, status} = useLive();
  const [selected,setSelected] = useState('');
  const [range,setRange] = useState('5m');
  const [metric,setMetric] = useState('tps');
  const [give,setGive] = useState(false);
  const [recipient,setRecipient] = useState('');
  const metricsEnabled = moduleEnabled(app.modules,'metrics');
  const playersEnabled = moduleEnabled(app.modules,'playerSnapshots');
  const catalogEnabled = moduleEnabled(app.modules,'entitySnapshots');
  const timelineEnabled = moduleEnabled(app.modules,'timeline');
  const history = useResource<Metric[]>(metricsEnabled ? `/api/metrics?range=${range}` : null);
  const catalogs = useResource<Catalogs>(give && catalogEnabled && playersEnabled ? '/api/catalogs' : null);
  useEffect(() => {if (!metricsEnabled) return; const timer = setInterval(() => {if (!document.hidden) history.reload();},15000); return () => clearInterval(timer);}, [range,metricsEnabled]);
  useEffect(() => {if (!catalogEnabled || !playersEnabled) setGive(false);}, [catalogEnabled,playersEnabled]);
  if (!s) return <Loading/>;
  const world = s.worlds?.find(item => item.name === selected) ?? s.worlds?.[0];
  const online = players.filter(player => player.online);
  const chartMetric = metrics.find(item => item.key === metric)!;
  const selectedPlayer = online.find(player => player.uuid === recipient) ?? online[0];
  const quick = [
    {id:'day',ru:'День',en:'Day',icon:'sun',action:'world.time',params:{world:world?.name,time:1000}},
    {id:'night',ru:'Ночь',en:'Night',icon:'moon',action:'world.time',params:{world:world?.name,time:13000}},
    {id:'clear',ru:'Ясно',en:'Clear',icon:'sun',action:'world.weather',params:{world:world?.name,weather:'clear'}},
    {id:'rain',ru:'Дождь',en:'Rain',icon:'rain',action:'world.weather',params:{world:world?.name,weather:'rain'}},
  ];
  return <div className="dashboard overworld-dashboard">
    <div className="overview-welcome"><div><span>{app.t('С возвращением,','Welcome back,')} {app.auth.user?.name}</span><h1>{app.t('Твой сервер. Твои правила.','Your server. Your rules.')}</h1></div><span className={`server-state ${!metricsEnabled || s.performanceGuard || status !== 'live' ? 'warning' : ''}`}><i/>{!metricsEnabled ? app.t('Метрики отключены','Metrics disabled') : s.performanceGuard ? app.t('Защита нагрузки','Load protection') : status === 'live' ? app.t('Всё под контролем','All systems ready') : app.t('Обновляем связь','Reconnecting')}</span></div>
    <section className="overworld-hero">
      <div className="hero-copy">
        <span className="hero-version"><i/>Minecraft {s.minecraftVersion}</span>
        <h2>{app.t('Бесконечный мир.\nПолный контроль.','Infinite world.\nComplete control.')}</h2>
        <p>{app.t('От первого блока до большого сервера.\nВсё, что нужно для следующей главы.','From the first block to a thriving server.\nEverything you need for your next chapter.')}</p>
        <div className="hero-buttons">{app.owner&&<RestartControl/>}<Button className="primary" disabled={!moduleEnabled(app.modules,'map')} onClick={() => app.navigate('map')}>{app.t('Открыть карту','Open world map')}<Icon name="arrow" size={18}/></Button><Button className="hero-secondary" onClick={() => app.navigate('worlds')}>{app.t('Управлять мирами','Manage worlds')}</Button></div>
        <span className="hero-live-note"><span className="pixel-cross" aria-hidden="true"/>{s.worldCount ?? s.worlds.length} {app.t('мира подключено','worlds connected')} <b/> {app.t('Синхронизация в реальном времени','Realtime sync')}</span>
      </div>
      <div className="hero-landscape" aria-hidden="true"><Suspense fallback={<div className="scene-placeholder"/>}><OverworldScene compact/></Suspense></div>
      <span className="hero-scene-label">Overworld <i/> {app.t('Мир возможностей','A world of possibility')}</span>
      <span className="hero-edge-pixels" aria-hidden="true"/>
    </section>
    <section className="server-instruments" aria-label={app.t('Показатели сервера','Server metrics')}>
      <div><span>{app.t('Игроки онлайн','Players online')}</span><strong>{s.online}<small>/ {s.maxPlayers}</small></strong><span className="instrument-detail"><i className="mini-health"><b style={{width:`${Math.min(100,s.online/Math.max(1,s.maxPlayers)*100)}%`}}/></i>{app.t('Сейчас в игре','In game now')}</span></div>
      <div><span>{app.t('Тики в секунду','Ticks per second')}</span><strong>{formatNumber(s.tps?.[0],2)}<small>TPS</small></strong><span className={`instrument-detail ${s.tps?.[0] >= 18 ? 'good' : 'warn'}`}><i className="signal-square"/>{typeof s.tps?.[0]!=='number'?app.t('Метрика недоступна','Metric unavailable'):s.tps[0] >= 18 ? app.t('Стабильный ритм','Steady rhythm') : app.t('Повышенная нагрузка','Under load')}</span></div>
      <div><span>{app.t('Время тика','Tick duration')}</span><strong>{formatNumber(s.mspt,2)}<small>ms</small></strong><span className="instrument-detail">{app.t('Бюджет тика','Tick budget')} 50 ms</span></div>
      <div><span>{app.t('Память сервера','Server memory')}</span><strong>{formatNumber(s.heapUsed/1024**3,1)}<small>GiB</small></strong><span className="instrument-detail">{app.t('Доступно JVM','JVM limit')} {gb(s.heapMax)}</span></div>
    </section>
    <div className="overview-grid">
      <Panel className="metrics-panel">
        <div className="panel-heading"><div><span className="section-kicker">{app.t('Наблюдение','Monitoring')}</span><h2>{app.t('Пульс сервера','Server pulse')}</h2></div><div className="segmented" aria-label={app.t('Период','Range')}>{['5m','1h','24h'].map(value => <button key={value} className={range === value ? 'active' : ''} aria-pressed={range === value} onClick={() => setRange(value)}>{value}</button>)}</div></div>
        <div className="chart-tabs">{metrics.map(item => <button key={item.key} className={metric === item.key ? 'active' : ''} aria-pressed={metric === item.key} onClick={() => setMetric(item.key)}><i style={{background:item.color}}/>{item.label}</button>)}</div>
        {!metricsEnabled ? <Empty title={app.t('Метрики отключены','Metrics disabled')} icon="dashboard">{app.t('Включи модуль метрик в настройках, чтобы возобновить измерения.','Enable metrics in settings to resume sampling.')}</Empty> : history.data?.length ? <><Chart data={history.data} metric={metric} color={chartMetric.color}/><div className="chart-axis"><span>{time(history.data[0]?.time ?? history.data[0]?.timestamp)}</span><span>{time(history.data.at(-1)?.time ?? history.data.at(-1)?.timestamp)}</span></div></> : <Empty title={history.error ? app.t('История недоступна','History unavailable') : app.t('Собираем первые измерения','Collecting the first samples')} icon="dashboard"/>}
        <div className="metrics-bottom"><span><i/>{app.t('Данные с твоего сервера','Data from your server')}</span><span>{app.t('Обновлено','Updated')} {time(updated)}</span></div>
      </Panel>
      <Panel className="world-controls"><div className="panel-heading"><div><span className="section-kicker">{app.t('Простые действия','Quick controls')}</span><h2>{app.t('Пульт мира','World controls')}</h2></div><Icon name="worlds" size={21}/></div>
        <label className="world-select-label"><span>{app.t('Выбранный мир','Selected world')}</span><select aria-label={app.t('Мир','World')} value={world?.name ?? ''} onChange={event => setSelected(event.target.value)}>{s.worlds.map(item => <option key={item.uuid} value={item.name}>{item.name}</option>)}</select></label>
        <div className="world-facts"><span><Icon name={world && world.time >= 13000 && world.time < 23000 ? 'moon' : 'sun'} size={16}/>{world ? `${Math.floor(((world.time+6000)%24000)/1000).toString().padStart(2,'0')}:${Math.floor(world.time%1000/1000*60).toString().padStart(2,'0')}` : '—'}</span><span>{world ? app.t(...weatherLabels[world.weather] ?? [world.weather,world.weather]) : '—'}</span><span>{world?.difficulty ?? '—'}</span></div>
        <div className="world-control-grid">{quick.map(item => <ActionButton key={item.id} action={item.action} params={item.params} disabled={!world}><Icon name={item.icon} size={22}/><span>{app.t(item.ru,item.en)}</span></ActionButton>)}</div>
        <div className="world-control-footer"><ActionButton action="world.save" params={{world:world?.name}} disabled={!world}><Icon name="check" size={16}/>{app.t('Сохранить мир','Save world')}</ActionButton><Button className="text-button" onClick={() => app.navigate('worlds')}>{app.t('Все настройки','All settings')}<Icon name="arrow" size={15}/></Button></div>
      </Panel>
      <Panel className="online-panel"><div className="panel-heading"><div><span className="section-kicker">{app.t('Твоя команда','Your community')}</span><h2>{app.t('Сейчас в игре','Playing now')}</h2></div><span className="count-pill">{playersEnabled ? online.length : '—'}</span></div>
        {!playersEnabled ? <Empty title={app.t('Снимки игроков отключены','Player snapshots disabled')} icon="players"/> : online.length ? <div className="player-mini-list">{online.slice(0,5).map(player => <button key={player.uuid} onClick={() => app.navigate('players')}><Avatar name={player.name} small/><span><strong>{player.name}</strong><small>{player.world}</small></span><em>{player.ping} ms</em><Icon name="chevron" size={15}/></button>)}</div> : <div className="empty-player-scene"><span className="empty-pixel-person" aria-hidden="true">▣</span><strong>{app.t('Приключение начинается с тебя','The adventure starts with you')}</strong><small>{app.t('Игроки появятся здесь, когда войдут на сервер.','Players will appear here when they join.')}</small></div>}
        <div className="players-footer"><Button className="text-button" disabled={!playersEnabled} onClick={() => app.navigate('players')}>{app.t('Все игроки','All players')}<Icon name="arrow" size={16}/></Button><Button disabled={!app.administrator || !playersEnabled || !catalogEnabled || !online.length} onClick={() => setGive(true)}><GameImage id="diamond" size={18}/>{app.t('Выдать предмет','Give item')}</Button></div>
      </Panel>
      <Panel className="server-health"><div className="panel-heading"><div><span className="section-kicker">{app.t('Под капотом','Under the hood')}</span><h2>{app.t('Ресурсы','Resources')}</h2></div><span className="uptime-chip">{duration(s.uptimeSeconds)}</span></div>
        <div className="resource-meter"><span>CPU <b>{formatNumber(s.cpu,1)}%</b></span><i><b style={{width:`${typeof s.cpu === 'number' ? Math.max(0,Math.min(100,s.cpu)) : 0}%`}}/></i></div>
        <dl className="data-list"><div><dt>{app.t('Чанки','Chunks')}</dt><dd>{formatNumber(s.loadedChunks)}</dd></div><div><dt>{app.t('Сущности','Entities')}</dt><dd>{formatNumber(s.entityCount)}</dd></div><div><dt>{app.t('Память системы','System RAM')}</dt><dd>{gb(s.systemRamUsed)}<small> / {gb(s.systemRamTotal)}</small></dd></div><div><dt>{app.t('Диск','Disk')}</dt><dd>{gb(s.diskUsed)}<small> / {gb(s.diskTotal)}</small></dd></div></dl>
      </Panel>
      <Panel className="activity-panel"><div className="panel-heading"><h2>{app.t('Последние события','Recent activity')}</h2><Icon name="events" size={18}/></div>{!timelineEnabled ? <Empty title={app.t('Лента отключена','Activity feed disabled')} icon="events"/> : timeline.length ? <ol className="timeline">{timeline.slice(-5).reverse().map((entry,index) => <li key={`${entry.time}-${index}`}><i/><div><p>{String(entry.message ?? entry.text ?? entry.action ?? '')}</p><small>{time(entry.time ?? entry.timestamp)}</small></div></li>)}</ol> : <Empty title={app.t('События появятся здесь','Events will appear here')} icon="events"/>}</Panel>
      <div className="next-adventure"><span className="pixel-cross" aria-hidden="true"/><h2>{app.t('Что создадим дальше?','What will you build next?')}</h2><p>{app.t('Новый мир, событие или сценарий — выбирай свою следующую идею.','A new world, event or workflow. Make your next idea happen.')}</p><div><Button disabled={!moduleEnabled(app.modules,'events')} onClick={() => app.navigate('events')}>{app.t('Создать событие','Create an event')}<Icon name="arrow" size={16}/></Button>{app.administrator && <Button disabled={!moduleEnabled(app.modules,'automations')} onClick={() => app.navigate('automations')}>{app.t('Автоматизации','Automations')}<Icon name="arrow" size={16}/></Button>}</div></div>
    </div>
    {give && <Modal title={app.t('Подарить предмет','Give an item')} close={() => setGive(false)}><div className="modal-body"><div className="recipient-select"><GameImage id="player_head" size={32}/><label><span>{app.t('Получатель','Recipient')}</span><select aria-label={app.t('Получатель','Recipient')} value={selectedPlayer?.uuid ?? ''} onChange={event => setRecipient(event.target.value)}>{online.map(player => <option key={player.uuid} value={player.uuid}>{player.name}</option>)}</select></label></div><ItemForm catalogs={catalogs.data} save={async item => {if (!selectedPlayer) return; await app.action('player.give',{uuid:selectedPlayer.uuid,item}); setGive(false);}} button={app.t('Выдать предмет','Give item')}/></div></Modal>}
  </div>;
}
