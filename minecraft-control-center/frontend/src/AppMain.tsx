import './PanelUsers.css';
import {lazy, Suspense, useCallback, useEffect, useRef, useState} from 'react';
import {motion, useReducedMotion} from 'motion/react';
import {ApiError, execute, post, request, setAuth} from './api';
import {AppContext,useResource} from './context';
import type {Page} from './context';
import {connectLive, refreshLive, resetLive, useLive} from './live';
import type {Auth} from './types';
import {Button, Icon, Loading, Modal} from './ui';
import {GameImage} from './assets';
import {canExecute} from './policy';
import {availablePages, pages} from './navigation';
import Shell, {Brand} from './Shell';
import Login from './Login';
import Account from './Account';
import Dashboard from './Dashboard';
import {RestartScreen,useServerRestart} from './ServerRestart';
const Management = lazy(() => import('./Management'));
type Toast = {id:number; text:string; error:boolean};
type Confirmation = {text:string; resolve:(yes:boolean) => void};

export default function App() {
  const [auth, setAuthState] = useState<Auth|null>(null);
  const [authError, setAuthError] = useState('');
  const [authRevision, setAuthRevision] = useState(0);
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
  const integrations=useResource<{items:{id:string}[]}>(auth?.authenticated&&['Owner','Admin'].includes(auth.user?.role??'')?'/api/integrations':null);
  const integrationIds=integrations.data?.items.map(item=>item.id)??[];
  useEffect(()=>{if(!auth?.authenticated||!['Owner','Admin'].includes(auth.user?.role??''))return;const timer=setInterval(()=>{if(!document.hidden)integrations.reload();},30000);return()=>clearInterval(timer);},[auth?.authenticated,auth?.user?.role]);
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
    if (!availablePages(auth?.user?.role ?? 'Viewer',integrationIds).some(item => item.id === next)) return;
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
  useEffect(() => {if (auth?.authenticated) return connectLive(auth.user?.role?.toLowerCase());}, [auth?.authenticated,auth?.user?.name,auth?.user?.role,auth?.csrf]);
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
    if (auth?.authenticated && !availablePages(auth.user?.role ?? 'Viewer',integrationIds).some(item => item.id === page)) setPage('dashboard');
  }, [auth?.authenticated,auth?.user?.role,page,integrationIds.join(',')]);
  async function action(name:string,params:Record<string,unknown>,danger?:string) {
    if (!canExecute(auth?.user?.role ?? 'Viewer',name)) throw new Error(t('Недостаточно прав','Permission denied'));
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
  if (!auth.authenticated) return <Login status={auth} onAuth={setAuthState} lang={lang} changeLang={() => setLang(value => value === 'ru' ? 'en' : 'ru')}/>;
  const role = auth.user?.role?.toLowerCase() ?? 'viewer';
  return <AppContext.Provider value={{auth,integrationIds,lang,t,navigate,action,confirm,notify,readonly:role === 'viewer',administrator:['owner','admin'].includes(role),owner:role === 'owner'}}>
    <Shell page={page} changeLang={() => setLang(value => value === 'ru' ? 'en' : 'ru')} profile={() => setProfile(true)} logout={() => void logout()}>
      <main id="main" className="main-content" tabIndex={-1}>
        {auth.defaultPassword&&<div className="default-password-note"><span>{t('Используется стартовый пароль. Смените его в профиле перед удалённым доступом.','You are using the initial password. Change it in your profile before remote access.')}</span><Button onClick={()=>setProfile(true)}>{t('Мой профиль','My account')}</Button></div>}
        {role === 'viewer' && <div className="viewer-note"><Icon name="check" size={16}/>{t('Режим просмотра','Read-only mode')}</div>}
        {live.error && <div className="error-box" role="alert">{live.error}<button onClick={() => void refreshLive()}>{t('Обновить','Refresh')}</button></div>}
        <motion.div key={page} className="page-transition" initial={reduce ? false : {opacity:0,y:8}} animate={{opacity:1,y:0}} transition={{duration:.22,ease:[.2,.8,.2,1]}}>
          <Suspense fallback={<Loading/>}>{page === 'dashboard' ? <Dashboard/> : <Management page={page}/>}</Suspense>
        </motion.div>
      </main>
    </Shell>
    {profile && <Account close={() => setProfile(false)} onAuth={setAuthState}/>}
    {confirmation && <Modal title={t('Подтвердить действие','Confirm action')} close={() => closeConfirm(false)} danger><div className="confirm-body"><GameImage id="barrier" size={48}/><p>{confirmation.text}</p><div className="button-row"><Button onClick={() => closeConfirm(false)}>{t('Отмена','Cancel')}</Button><Button className="danger" onClick={() => closeConfirm(true)}>{t('Подтвердить','Confirm')}</Button></div></div></Modal>}
    <div className="toasts" aria-live="polite">{toasts.map(toast => <div key={toast.id} className={`achievement-toast ${toast.error ? 'toast-error' : ''}`}><span className="toast-pixel" aria-hidden="true">{toast.error ? '!' : '✓'}</span><div><small>{toast.error ? t('Не выполнено','Action failed') : 'Minecraft Control Center'}</small><p>{toast.text}</p></div><button className="icon-button" onClick={() => setToasts(previous => previous.filter(item => item.id !== toast.id))} aria-label={t('Закрыть','Dismiss')}><Icon name="close" size={15}/></button></div>)}</div>
  </AppContext.Provider>;
}
