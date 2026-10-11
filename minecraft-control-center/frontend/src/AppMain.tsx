import './PanelUsers.css';
import {lazy, Suspense, useCallback, useEffect, useRef, useState} from 'react';
import {motion, useReducedMotion} from 'motion/react';
import {ApiError, execute, post, request, setAuth} from './api';
import {AppContext,useResource} from './context';
import type {Page} from './context';
import {connectLive, refreshLive, resetLive, useLive} from './live';
import {pageEnabled} from './module-view';
import type {Auth,Settings} from './types';
import {Button, Icon, Loading, Modal} from './ui';
import {GameImage} from './assets';
import {canExecute} from './policy';
import {availablePages, pages} from './navigation';
import Shell, {Brand} from './Shell';
import Login from './Login';
import Account from './Account';
import Dashboard from './Dashboard';
import {RestartScreen,useServerRestart} from './ServerRestart';
import {effectiveModules,licenseAllowsAction,licenseAllowsPage} from './license-view';
import {watchSetupLink} from './setup-view';
const Management = lazy(() => import('./Management'));
const SetupWizard=lazy(()=>import('./SetupWizard'));
type Toast = {id:number; text:string; error:boolean};
type Confirmation = {text:string; resolve:(yes:boolean) => void};

export default function App() {
  const [auth, setAuthState] = useState<Auth|null>(null);
  const [authError, setAuthError] = useState('');
  const [authRevision, setAuthRevision] = useState(0);
  const [setupLink,setSetupLink]=useState(()=>new URLSearchParams(location.hash.replace(/^#/, '')).has('setup'));
  const [setupRevision,setSetupRevision]=useState(0);
  useEffect(()=>watchSetupLink(window,()=>{setSetupLink(true);setSetupRevision(value=>value+1);}),[]);
  const [lang, setLang] = useState<'ru'|'en'>(() => localStorage.getItem('mcc-language') === 'en' ? 'en' : 'ru');
  const [page, setPage] = useState<Page>('dashboard');
  const [profile, setProfile] = useState(false);
  const [toasts, setToasts] = useState<Toast[]>([]);
  const [confirmation, setConfirmation] = useState<Confirmation|null>(null);
  const activeConfirmation = useRef<Confirmation|null>(null);
  const timers = useRef<Set<ReturnType<typeof setTimeout>>>(new Set());
  const live = useLive();
  const reduce = useReducedMotion();
  const restarting=useServerRestart();
  const settings=useResource<Settings>(auth?.authenticated?'/api/settings':null);
  const modules=effectiveModules((live.snapshot?.modules as Record<string,boolean>|undefined)??settings.data?.modules??{},auth?.license);
  const integrations=useResource<{items:{id:string}[]}>(auth?.authenticated&&modules.integrations!==false&&['Owner','Admin'].includes(auth.user?.role??'')?'/api/integrations':null);
  const integrationIds=integrations.data?.items.map(item=>item.id)??[];
  useEffect(()=>{if(!auth?.authenticated||modules.integrations===false||!['Owner','Admin'].includes(auth.user?.role??''))return;const timer=setInterval(()=>{if(!document.hidden)integrations.reload();},30000);return()=>clearInterval(timer);},[auth?.authenticated,auth?.user?.role,modules.integrations]);
  useEffect(()=>{const changed=()=>settings.reload();window.addEventListener('mcc-settings-changed',changed);return()=>window.removeEventListener('mcc-settings-changed',changed);},[auth?.authenticated]);
  useEffect(()=>{if(!pageEnabled(modules,page))setPage('dashboard');},[modules,page]);
  const t = (ru:string,en:string) => lang === 'ru' ? ru : en;
  const notify = useCallback((text:string, error=false) => {
    const id = Date.now() + Math.random();
    setToasts(previous => [...previous.slice(-3), {id,text,error}]);
    const timer = setTimeout(() => {setToasts(previous => previous.filter(item => item.id !== id)); timers.current.delete(timer);}, 5500);
    timers.current.add(timer);
  }, []);
  useEffect(() => () => {timers.current.forEach(clearTimeout); timers.current.clear(); activeConfirmation.current?.resolve(false);}, []);
  function confirm(text:string) {
    return new Promise<boolean>(resolve => {
      activeConfirmation.current?.resolve(false);
      activeConfirmation.current = {text,resolve};
      setConfirmation(activeConfirmation.current);
    });
  }
  function closeConfirm(yes:boolean) {activeConfirmation.current?.resolve(yes); activeConfirmation.current = null; setConfirmation(null);}
  function navigate(next:Page) {
    if (!availablePages(auth?.user?.role ?? 'Viewer',integrationIds,modules,auth?.license).some(item => item.id === next)) return;
    setPage(next);
    window.scrollTo({top:0, behavior:'instant'});
    requestAnimationFrame(() => document.getElementById('main')?.focus({preventScroll:true}));
  }
  useEffect(() => {localStorage.setItem('mcc-language',lang); document.documentElement.lang = lang;}, [lang]);
  useEffect(() => {
    const visibility = () => {document.documentElement.dataset.visible = String(!document.hidden);};
    visibility(); document.addEventListener('visibilitychange',visibility);
    return () => document.removeEventListener('visibilitychange',visibility);
  }, []);
  useEffect(() => {
    let active = true; setAuthError('');
    void request<Auth>('/api/auth/status').then(value => {if (active) {setAuth(value); setAuthState(value);}})
      .catch(reason => {if (active) setAuthError(reason instanceof Error ? reason.message : String(reason));});
    return () => {active = false;};
  }, [authRevision]);
  useEffect(() => {if (auth?.authenticated) return connectLive(auth.user?.role?.toLowerCase(),auth.license);}, [auth?.authenticated,auth?.user?.name,auth?.user?.role,auth?.csrf,auth?.license?.mode]);
  useEffect(()=>{const changed=(event:Event)=>{const value=(event as CustomEvent<Auth>).detail;if(value?.authenticated){setAuth(value);setAuthState(value);resetLive();}};window.addEventListener('mcc-license-changed',changed);return()=>window.removeEventListener('mcc-license-changed',changed);},[]);
  useEffect(() => {
    const expire = () => {
      setAuth({authenticated:false,setupRequired:false}); setAuthState({authenticated:false,setupRequired:false});
      resetLive(); notify(lang === 'ru' ? 'Сессия истекла' : 'Session expired',true);
    };
    window.addEventListener('mcc-session-expired',expire);
    return () => window.removeEventListener('mcc-session-expired',expire);
  }, [lang,notify]);
  useEffect(() => {document.title = `${pages.find(item => item.id === page)?.[lang] ?? 'MCC'} · Minecraft Control Center`;}, [page,lang,auth?.authenticated]);
  useEffect(() => {
    if (auth?.authenticated && !availablePages(auth.user?.role ?? 'Viewer',integrationIds,modules,auth.license).some(item => item.id === page)) setPage('dashboard');
  }, [auth?.authenticated,auth?.user?.role,auth?.license?.mode,page,integrationIds.join(','),modules]);
  async function action(name:string,params:Record<string,unknown>,danger?:string) {
    if (!canExecute(auth?.user?.role ?? 'Viewer',name)) throw new Error(t('Недостаточно прав','Permission denied'));
    if(!licenseAllowsAction(auth?.license,name))throw new Error(t('Эта функция доступна в полной версии. Открой настройки, чтобы изменить режим доступа.','This feature requires full access. Open settings to change access mode.'));
    let confirmed = false;
    if (danger) {confirmed = await confirm(danger); if (!confirmed) return null;}
    try {
      const result = await execute(name,params,confirmed);
      const queued = result.result && typeof result.result === 'object' && ['queued','running'].includes(String((result.result as {status?:string}).status));
      notify(queued ? t('Действие запланировано','Action scheduled') : t('Готово — применено в игре','Done — applied in game'));
      void refreshLive(); window.dispatchEvent(new Event('mcc-action-complete')); return result.result;
    } catch (reason) {
      if (reason instanceof ApiError && reason.status === 409) notify(t('Инвентарь изменился. Обновите данные.','Inventory changed. Refresh data.'),true);
      throw reason;
    }
  }
  async function logout() {
    try {await post('/api/auth/logout',{}); setAuthState({setupRequired:false,authenticated:false}); setAuth({setupRequired:false,authenticated:false}); resetLive();}
    catch (reason) {notify(reason instanceof Error ? reason.message : String(reason),true);}
  }
  if(restarting.restart)return <RestartScreen restart={restarting.restart} lang={lang} done={()=>{restarting.clear();resetLive();setAuth({authenticated:false,setupRequired:false});setAuthState(null);setPage('dashboard');setAuthRevision(v=>v+1);}}/>;
  if (!auth) return <main className="boot"><Brand/><span className="boot-cube"/><h1>{t('Открываем твой мир…','Opening your world…')}</h1>{authError ? <><p role="alert">{authError}</p><Button onClick={() => setAuthRevision(value => value+1)}>{t('Повторить','Retry')}</Button></> : <span className="pixel-loader"/>}</main>;
  if(setupLink||auth.setupRequired)return <Suspense fallback={<main className="boot"><Brand/><span className="pixel-loader"/></main>}><SetupWizard key={setupRevision} lang={lang} changeLang={()=>setLang(value=>value==='ru'?'en':'ru')} onAuth={value=>{setSetupLink(false);setAuthState(value);resetLive();setPage('dashboard');}} onExit={()=>{setSetupLink(false);setAuthRevision(value=>value+1);}}/></Suspense>;
  if (!auth.authenticated) return <Login onAuth={setAuthState} lang={lang} changeLang={() => setLang(value => value === 'ru' ? 'en' : 'ru')}/>;
  const role = auth.user?.role?.toLowerCase() ?? 'viewer';
  return <AppContext.Provider value={{auth,integrationIds,modules,lang,t,navigate,action,confirm,notify,readonly:role === 'viewer',administrator:['owner','admin'].includes(role),owner:role === 'owner'}}>
    <Shell page={page} changeLang={() => setLang(value => value === 'ru' ? 'en' : 'ru')} profile={() => setProfile(true)} logout={() => void logout()}>
      <main id="main" className="main-content" tabIndex={-1}>
        {auth.mustChangePassword&&<div className="default-password-note"><span>{t('Выбери новый личный пароль, чтобы продолжить.','Choose a new personal password to continue.')}</span><Button onClick={()=>setProfile(true)}>{t('Изменить пароль','Change password')}</Button></div>}
        {!licenseAllowsPage(auth.license,'players')&&<div className="trial-access-note"><span>{t('Пробная версия: обзор, консоль, чат и настройки. Все возможности можно временно открыть без ключа.','Trial access: overview, console, chat and settings. Temporarily unlock every feature without a key.')}</span><Button onClick={()=>navigate('settings')}>{t('Версия панели','Panel access')}</Button></div>}
        {auth.setup?.configurationPending&&<div className="error-box" role="status">{auth.setup.message||t('Профиль создан. Проверь настройки ядра: часть конфигурации требует повторного применения.','Your account is created. Review kernel settings: some configuration needs to be applied again.')}</div>}
        {auth.setup?.coreInstallation==='staged-awaiting-restart'&&<div className="trial-access-note" role="status">{t('MCC Core подготовлен. Ядро установится при следующем штатном перезапуске сервера.','MCC Core is staged. The kernel will be installed at the next normal server restart.')}</div>}
        {role === 'viewer' && <div className="viewer-note"><Icon name="check" size={16}/>{t('Режим просмотра','Read-only mode')}</div>}
        {live.error && <div className="error-box" role="alert">{live.error}<button onClick={() => void refreshLive()}>{t('Обновить','Refresh')}</button></div>}
        <motion.div key={page} className="page-transition" initial={reduce ? false : {opacity:0,y:8}} animate={{opacity:1,y:0}} transition={{duration:.22,ease:[.2,.8,.2,1]}}>
          <Suspense fallback={<Loading/>}>{page === 'dashboard' ? <Dashboard/> : <Management page={page}/>}</Suspense>
        </motion.div>
      </main>
    </Shell>
    {(profile||auth.mustChangePassword) && <Account close={() => {if(!auth.mustChangePassword)setProfile(false);}} onAuth={setAuthState}/>}
    {confirmation && <Modal title={t('Подтвердить действие','Confirm action')} close={() => closeConfirm(false)} danger><div className="confirm-body"><GameImage id="barrier" size={48}/><p>{confirmation.text}</p><div className="button-row"><Button onClick={() => closeConfirm(false)}>{t('Отмена','Cancel')}</Button><Button className="danger" onClick={() => closeConfirm(true)}>{t('Подтвердить','Confirm')}</Button></div></div></Modal>}
    <div className="toasts" aria-live="polite">{toasts.map(toast => <div key={toast.id} className={`achievement-toast ${toast.error ? 'toast-error' : ''}`}><span className="toast-pixel" aria-hidden="true">{toast.error ? '!' : '✓'}</span><div><small>{toast.error ? t('Не выполнено','Action failed') : 'Minecraft Control Center'}</small><p>{toast.text}</p></div><button className="icon-button" onClick={() => setToasts(previous => previous.filter(item => item.id !== toast.id))} aria-label={t('Закрыть','Dismiss')}><Icon name="close" size={15}/></button></div>)}</div>
  </AppContext.Provider>;
}
