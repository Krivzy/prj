import {useEffect,useRef,useState} from 'react';
import {useApp,useResource} from './context';
import {post,postTracked} from './api';
import {Button,Empty,ErrorBox,Icon,Loading,Modal,Panel} from './ui';
import {canApplyKernelProfile,canSelectKernelProfile,kernelProfileValue} from './kernel-profile-view';
import type {KernelProfileId,KernelProfilePlan,KernelProfileResult,KernelProfileStatus} from './kernel-profile-view';
import './KernelProfiles.css';

export default function KernelProfiles(){
  const {t,owner,administrator,notify}=useApp();
  const resource=useResource<KernelProfileStatus>(administrator?'/api/server/profiles':null);
  const [plan,setPlan]=useState<KernelProfilePlan|null>(null),[busy,setBusy]=useState(false),[error,setError]=useState('');
  const mounted=useRef(true),activeOwner=useRef(owner);activeOwner.current=owner;
  useEffect(()=>{mounted.current=true;return()=>{mounted.current=false;};},[]);
  useEffect(()=>{if(!owner)setPlan(null);},[owner]);
  const labels:Record<KernelProfileId,[string,string]>={custom:['Свои настройки','Custom files'],vanilla:['Полная ванила','Vanilla behavior'],'vanilla-performance':['Ванила + производительность','Vanilla + performance'],optimized:['Оптимизированный','Optimized'],'maximum-optimized':['Максимально оптимизированный','Maximum optimization']};
  const description:Record<KernelProfileId,[string,string]>={
    custom:['Управляй файлами сам. MCC сохранит текущие настройки и не будет переписывать конфигурацию ядра.','Manage the files yourself. MCC preserves current kernel settings and never rewrites them.'],
    vanilla:['Ванильные настройки поведения в рамках этого ядра. Плагины и отличия самого ядра сохраняются.','Vanilla-like behavior within this kernel. Plugins and inherent kernel differences remain.'],
    'vanilla-performance':['Настройки, близкие к ванильным: меньше дистанция симуляции, оптимизация взрывов и распределённое сохранение.','Vanilla-like settings with a shorter simulation distance, cached explosion calculations and paced saving.'],
    'maximum-optimized':['Сохраняет текущие дистанции обзора и симуляции. Агрессивно сокращает работу сущностей и меняет redstone. Фермы и механики требуют проверки.','Preserves current view and simulation distances. Aggressively reduces entity work and changes redstone. Test farms and mechanics before use.'],
    optimized:['Меньше активных сущностей и дальности симуляции, другой redstone. Поведение ферм может измениться.','Fewer active entities, a shorter simulation distance and different redstone. Farms can behave differently.'],
  };
  async function select(id:KernelProfileId){
    if(!activeOwner.current||busy)return;setBusy(true);setError('');
    try{const next=await post<KernelProfilePlan>('/api/server/profiles/plan',{profile:id});if(mounted.current&&activeOwner.current)setPlan(next);}
    catch(e){if(mounted.current)setError(e instanceof Error?e.message:String(e));}
    finally{if(mounted.current)setBusy(false);}
  }
  async function apply(){
    if(!canApplyKernelProfile(activeOwner.current,plan,Date.now())||busy){setError(t('План устарел или права изменились. Создай новый план.','The plan expired or your permissions changed. Create a fresh plan.'));return;}
    setBusy(true);setError('');
    try{
      const result=await postTracked<KernelProfileResult>('/api/server/profiles/apply',{planId:plan!.planId,confirmed:true});
      if(!mounted.current)return;setPlan(null);resource.reload();notify(result.liveError||t('Профиль сохранён.','Profile saved.'),!!result.liveError);
    }catch(e){if(mounted.current){setPlan(null);setError(e instanceof Error?e.message:String(e));resource.reload();}}
    finally{if(mounted.current)setBusy(false);}
  }
  if(!administrator)return <Panel className="kernel-profiles"><Empty title={t('Профили ядра доступны администрации','Kernel profiles are available to administrators')} icon="settings"/></Panel>;
  if(resource.loading&&!resource.data)return <Panel className="kernel-profiles"><Loading/></Panel>;
  if(resource.error&&!resource.data)return <Panel className="kernel-profiles"><ErrorBox error={resource.error} retry={resource.reload}/></Panel>;
  const data=resource.data;
  if(!data||!data.profiles?.length)return <Panel className="kernel-profiles"><Empty title={t('Профили пока недоступны','Profiles are unavailable')} icon="settings"/><Button type="button" onClick={resource.reload}>{t('Обновить','Refresh')}</Button></Panel>;
  return <Panel className="kernel-profiles">
    <header className="kp-heading"><div><span className="eyebrow">{t('Поведение сервера','Server behavior')}</span><h3>{t('Профиль ядра','Kernel profile')}</h3><p>{data.kernel} · Minecraft {data.minecraftVersion}</p></div><Button type="button" className="ghost" onClick={resource.reload} disabled={busy} aria-label={t('Обновить состояние профиля','Refresh profile state')}><Icon name="refresh"/>{t('Обновить','Refresh')}</Button></header>
    {(error||resource.error)&&<ErrorBox error={error||resource.error}/>}
    {data.pendingRestart&&<div className="kp-notice" role="status"><Icon name="warning"/><div><strong>{t('Есть изменения для следующего запуска','Changes await the next startup')}</strong><p>{t('Часть параметров ядро читает при запуске. Текущая работа сервера продолжается.','The kernel reads some settings at startup. The server continues running with its current settings.')}</p></div></div>}
    {!data.capabilities.managedFiles&&<div className="kp-notice"><Icon name="warning"/><div><strong>{t('Конфигурация не поддерживается','Unsupported configuration')}</strong><p>{data.capabilities.reason}</p><p>{t('Доступен режим своих файлов. MCC не будет менять неизвестную схему.','Custom files mode remains available. MCC will preserve the unknown schema.')}</p></div></div>}
    <div className="kp-cards">{data.profiles.map(option=><article key={option.id} className={`kp-card ${option.id===data.profile?'selected':''}`}>
      <div className="kp-card-top"><span className={`kp-swatch ${option.id}`} aria-hidden="true"/><span className="kp-current">{option.id===data.profile?t('Выбран','Selected'):option.managed?t('Под управлением MCC','Managed by MCC'):t('Файлы ядра','Kernel files')}</span></div>
      <h4>{t(...labels[option.id])}</h4><p>{t(...description[option.id])}</p>
      <dl><div><dt>{t('Обзор','View distance')}</dt><dd>{option.viewDistance??t('Из файлов','From files')}</dd></div><div><dt>{t('Симуляция','Simulation')}</dt><dd>{option.simulationDistance??t('Из файлов','From files')}</dd></div></dl>
      <Button type="button" className={option.id===data.profile?'ghost':'primary'} disabled={!canSelectKernelProfile(owner,busy,option)} aria-label={`${t('Посмотреть изменения','Review changes')}: ${t(...labels[option.id])}`} onClick={()=>void select(option.id)}>{busy?<span className="spinner"/>:<Icon name="chevron"/>}{t('Посмотреть изменения','Review changes')}</Button>
    </article>)}</div>
    {!owner&&<p className="kp-readonly">{t('Ты можешь просматривать состояние. Менять профиль может только Owner.','You can inspect the state. Only the Owner can change a profile.')}</p>}
    <div className="kp-footnote"><Icon name="settings"/><p>{t('Управляемый профиль применяется при каждом запуске. В режиме своих файлов MCC ничего не переписывает. Пресеты не меняют JAR ядра.','A managed profile is reapplied at every startup. Custom files mode never rewrites kernel files. Presets do not change the kernel JAR.')}</p></div>
    {!data.capabilities.startupHookConfigured&&data.managed&&<p className="kp-footnote">{t('Prelaunch hook не настроен. Если файлы менялись вручную, MCC восстановит профиль после запуска и отметит необходимость ещё одного перезапуска.','The prelaunch hook is not configured. Manually changed files are restored after startup and require one more restart.')}</p>}
    {data.worlds.length>0&&<details className="kp-worlds"><summary>{t('Фактические дистанции миров','Actual world distances')}</summary><dl>{data.worlds.map(world=><div key={world.name}><dt>{world.name}</dt><dd>{world.viewDistance||'—'} / {world.simulationDistance||'—'}</dd></div>)}</dl></details>}
    {plan&&owner&&<Modal title={t('Проверка профиля','Review profile')} close={()=>{if(!busy)setPlan(null);}}>
      <div className="modal-body kp-plan"><h3>{t(...labels[plan.profile])}</h3><p>{plan.customPreservesFiles?t('Настройки ядра останутся байт в байт прежними. MCC сохранит только выбор режима.','Kernel files remain byte-identical. MCC only saves the selected mode.'):t('MCC создаст закрытую резервную копию и изменит только перечисленные параметры. Комментарии YAML могут быть переформатированы.','MCC creates a private backup and changes only the listed settings. YAML comments and formatting can be normalized.')}</p>
        {plan.pendingRestart&&<div className="kp-notice"><Icon name="warning"/>{t('Для части изменений требуется перезапуск.','Some changes require a restart.')}</div>}
        {plan.preservesDistances&&!plan.customPreservesFiles&&<div className="kp-notice"><Icon name="check"/>{t("Текущие дистанции обзора и симуляции сохраняются. Они не уменьшаются ни сейчас, ни при запуске.","Current view and simulation distances are preserved, both now and at startup.")}</div>}
        {plan.profile==='maximum-optimized'&&<div className="kp-tradeoffs"><strong>{t("Изменения механик","Gameplay tradeoffs")}</strong><p>{t("Сущности вне меньшего радиуса обновляются реже. Неактивные жители перестают полноценно тикать, AI мобов из спавнеров отключается; предметы сильнее объединяются и радиус спавна уменьшается. Это меняет фермы и восстановление торговли.","Entities outside a smaller radius tick less often. Inactive villagers stop fully ticking and spawner mobs lose AI; items merge more aggressively and the spawn radius shrinks. This changes farms and trade restocking.")}</p><p>{data.kernel.toLowerCase()==='spigot'?t("У Spigot нет поддерживаемой настройки реализации redstone — она пропущена.","Spigot has no supported redstone implementation setting; it is skipped."):t("Alternate Current меняет порядок обновления redstone. Проверь схемы перед применением.","Alternate Current changes redstone update order. Test circuits before applying.")}</p></div>}
        {plan.liveDistances&&<p>{t('Дистанции миров применятся сейчас через публичный API ядра.','World distances are applied now through the kernel’s public API.')}</p>}
        <div className="kp-change-list">{plan.files.filter(file=>file.changes.length>0).map(file=><section key={file.path}><h4>{file.path}</h4>{file.changes.map(change=><div className="kp-change" key={change.key}><code>{change.key}</code><span>{kernelProfileValue(change.before)} <span aria-hidden="true">→</span> {kernelProfileValue(change.after)}</span></div>)}</section>)}</div>
        {!plan.customPreservesFiles&&plan.files.every(file=>file.changes.length===0)&&<p>{t('Файлы уже соответствуют профилю. Выбор останется обязательным при будущих запусках.','Files already match this profile. The selection remains authoritative on future startups.')}</p>}
        <p className="kp-expiry">{t('План действителен 5 минут. Внешняя правка файла отменяет применение.','This plan is valid for 5 minutes. External file changes reject the apply operation.')}</p>
        <div className="kp-actions"><Button type="button" className="ghost" disabled={busy} onClick={()=>setPlan(null)}>{t('Отмена','Cancel')}</Button><Button type="button" className="primary" disabled={busy||!canApplyKernelProfile(owner,plan,Date.now())} onClick={()=>void apply()}>{busy&&<span className="spinner"/>}{t('Применить профиль','Apply profile')}</Button></div>
      </div>
    </Modal>}
  </Panel>;
}
