import {useEffect,useState} from 'react';
import {post} from './api';
import {useApp,useResource} from './context';
import type {Settings} from './types';
import {AsyncButton,ErrorBox,Icon,Loading,Panel} from './ui';
import KernelProfiles from './KernelProfiles';
import PanelUsers from './PanelUsers';
import LicenseSettings from './LicenseSettings';
import MccCoreSettings from './MccCoreSettings';
import {licenseAllowsModule} from './license-view';
import './KernelProfiles.css';
const moduleLabels:Record<string,[string,string,string,string]>={
  chat:['Чат','Chat','История сообщений и live-поток.','Message history and live stream.'],
  events:['Игровые события','Game events','Сценарии событий. Отключение завершит текущие события и очистит созданные объекты.','Event scenarios. Disabling stops active events and cleans up their owned objects.'],
  automations:['Автоматизации','Automations','Триггеры, таймеры и графы. Отключение отменяет оставшиеся шаги.','Triggers, timers and graphs. Disabling cancels remaining steps.'],
  map:['Сохранённая карта','Saved map','Фоновое чтение мира и создание PNG. Файлы кэша сохраняются.','Background world reading and PNG rendering. Cached files remain on disk.'],
  oreMonitor:['Честная игра','Fair play','Сбор добычи и SQLite истории. При выключении принятые данные сохраняются, обработчики и кэш освобождаются.','Mining capture and SQLite history. Disabling flushes accepted data and releases listeners and cache.'],
  integrations:['Интеграции','Integrations','API сторонних плагинов и кэш прав. Определение владельца наказаний продолжает работать.','Third-party APIs and permission cache. Punishment ownership detection remains active.'],
  metrics:['Метрики','Metrics','История метрик, измерения CPU, RAM и диска. Служебный health остаётся доступным.','Metric history and CPU, RAM and disk sampling. Core health remains available.'],
  playerSnapshots:['Данные игроков','Player data','Периодические снимки игроков, инвентаря и live-инспектора.','Periodic player snapshots, inventory and live inspector.'],
  entitySnapshots:['Сущности и игровые каталоги','Entities and game catalogs','Обход сущностей и управление спавном; игровые каталоги предметов, эффектов, звуков и мобов.','Entity enumeration and spawning; game catalogs of items, effects, sounds and mobs.'],
  timeline:['Журнал в интерфейсе','Timeline','Чтение и live-поток журнала. Обязательный аудит подтверждённых изменений сохраняется.','Audit reading and live stream. Required auditing of confirmed changes is retained.'],
  catalog:['Каталог плагинов','Plugin catalog','Сетевой поиск, планы установки и их кэши. Установленные плагины не отключаются.','Network search, install plans and their caches. Installed plugins remain enabled.'],
  console:['Поток консоли','Console stream','Чтение журнала и команды из панели. Серверная консоль продолжает работать.','Log tailing and dashboard commands. The server console keeps working.'],
};
export default function SettingsPage(){
  const app=useApp(),resource=useResource<Settings>('/api/settings');
  const [profile,setProfile]=useState('balanced'),[modules,setModules]=useState<Record<string,boolean>>({});
  useEffect(()=>{if(resource.data){setProfile(resource.data.profile);setModules(resource.data.modules);}},[resource.data]);
  async function save(){await post('/api/settings',{profile,modules});window.dispatchEvent(new Event('mcc-settings-changed'));resource.reload();app.notify(app.t('Настройки и фоновые подсистемы обновлены','Settings and background services updated'));}
  return <><div className="page-heading"><div className="section-title"><div><h1>{app.t('Настройки MCC','MCC settings')}</h1><p>{app.t('Выбирай, какие подсистемы действительно работают.','Choose which services actually run.')}</p></div></div></div>
    {resource.error&&<ErrorBox error={resource.error} retry={resource.reload}/>} {resource.loading&&!resource.data&&<Loading/>}
    {resource.data&&<div className="two-panels settings-panels"><Panel><div className="panel-heading"><h2>{app.t('Нагрузка MCC','MCC overhead')}</h2><Icon name="dashboard"/></div>
      <div className="profile-options">{[{id:'eco',ru:'Реже обновлять снимки',en:'Less frequent snapshots'},{id:'balanced',ru:'Баланс по умолчанию',en:'Balanced default'},{id:'performance',ru:'Более частые обновления',en:'More frequent updates'}].map(option=><label key={option.id} className={profile===option.id?'selected':''}><input type="radio" name="profile" checked={profile===option.id} onChange={()=>setProfile(option.id)} disabled={!app.administrator}/><span><strong>{option.id}</strong><small>{app.t(option.ru,option.en)}</small></span></label>)}</div>
      <h3>{app.t('Фоновые модули','Background modules')}</h3><div className="module-list kp-module-list">{Object.entries(modules).map(([key,enabled])=>{const labels=moduleLabels[key]??[key,key,'',''],allowed=licenseAllowsModule(app.auth.license,key);return <label key={key}><span><strong>{app.t(labels[0],labels[1])}{!allowed&&<span className="nav-lock"> · {app.t('Полная версия','Full access')}</span>}</strong><small>{app.t(labels[2],labels[3])}</small></span><input type="checkbox" checked={enabled&&allowed} onChange={e=>setModules({...modules,[key]:e.target.checked})} disabled={!app.administrator||!allowed}/></label>;})}</div>
      <p className="hint settings-module-note">{app.t('Выключенный модуль останавливает свои фоновые задачи и сбор данных, снижая нагрузку на сервер. Сохранённые данные остаются. Вход в панель, настройки и резервные копии продолжают работать.','Disabled modules stop their background tasks and data collection, reducing server load. Saved data stays available. Sign-in, settings and backups keep working.')}</p>
      <AsyncButton className="primary" disabled={!app.administrator} run={save}>{app.t('Сохранить настройки','Save settings')}</AsyncButton></Panel>
      <Panel><div className="panel-heading"><h2>{app.t('Подключение и измерения','Connection and measurements')}</h2><Icon name="settings"/></div><dl className="data-list"><div><dt>Bind address</dt><dd><code>{resource.data.host}</code></dd></div><div><dt>Port</dt><dd>{resource.data.port}</dd></div></dl>
      <p className="hint">{app.t('Адрес и порт меняются в config.yml при остановленном сервере. Значения CPU и RAM относятся ко всей JVM; это не измерение памяти одного MCC.','Change address and port in config.yml while the server is stopped. CPU and RAM cover the entire JVM, not MCC alone.')}</p>
      {resource.data.performance&&<details className="settings-details"><summary>{app.t('Фактические счётчики MCC','Actual MCC counters')}</summary><dl className="data-list">{Object.entries(resource.data.performance).map(([key,value])=><div key={key}><dt>{key}</dt><dd>{typeof value==='object'?JSON.stringify(value):String(value)}</dd></div>)}</dl></details>}
      {resource.data.limits&&<details className="settings-details"><summary>{app.t('Ограничения очередей','Queue limits')}</summary><dl className="data-list">{Object.entries(resource.data.limits).map(([key,value])=><div key={key}><dt>{key}</dt><dd>{String(value)}</dd></div>)}</dl></details>}</Panel></div>}
    <LicenseSettings/><MccCoreSettings/>{app.administrator&&<KernelProfiles/>}{app.owner&&<PanelUsers/>}</>;
}
