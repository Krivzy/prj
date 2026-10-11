import {useEffect, useState} from 'react';
import type {ReactNode} from 'react';
import {motion} from 'motion/react';
import {useApp} from './context';
import {roleLabel} from './roleLabel';
import type {Page} from './context';
import {useLive} from './live';
import {availablePages, pages} from './navigation';
import {Icon, Modal} from './ui';

export function Brand() {
  return <span className="brand"><span className="brand-mark" aria-hidden="true"><i/><b/><em/></span><span className="brand-name">MCC<small>Minecraft Control Center</small></span></span>;
}

export default function Shell({page, changeLang, profile, logout, children}: {
  page: Page; changeLang: () => void; profile: () => void;
  logout: () => void; children: ReactNode;
}) {
  const app = useApp();
  const live = useLive();
  const [menu, setMenu] = useState(false);
  const [commands, setCommands] = useState(false);
  const [query, setQuery] = useState('');
  const entries = availablePages(app.auth.user?.role ?? 'Viewer',app.integrationIds);
  const title = pages.find(item => item.id === page);
  const results = entries.filter(item => item[app.lang].toLowerCase().includes(query.toLowerCase()));
  useEffect(() => {
    const shortcut = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
        event.preventDefault(); setCommands(value => !value); setQuery('');
      }
      if (event.key === 'Escape') setMenu(false);
    };
    document.addEventListener('keydown', shortcut);
    return () => document.removeEventListener('keydown', shortcut);
  }, []);
  function navigate(next: Page) {setMenu(false); setCommands(false); app.navigate(next);}
  const connected = live.status === 'live';
  return <>
    <a className="skip-link" href="#main">{app.t('К содержимому', 'Skip to content')}</a>
    <div className="app-shell">
      <aside id="workspace-navigation" className={`sidebar ${menu ? 'is-open' : ''}`}>
        <button className="brand-button" onClick={() => navigate('dashboard')} aria-label="Minecraft Control Center"><Brand/></button>
        <div className="realm-card"><span className="realm-block" aria-hidden="true">▦</span><div><strong>{live.snapshot?.serverName ?? 'Minecraft'}</strong><small>{live.snapshot?.serverName ?? 'Minecraft'} {live.snapshot?.minecraftVersion ?? ''}</small></div><span className={connected ? 'status-dot' : 'offline-dot'}/></div>
        <nav aria-label={app.t('Разделы', 'Sections')}>
          {[0,1,2].map(group => <div className="nav-group" key={group}>
            <span className="nav-group-label">{group === 0 ? app.t('Твой сервер','Your server') : group === 1 ? app.t('Управление','Control') : app.t('Система','System')}</span>
            {entries.filter(item => item.group === group).map(item => <button key={item.id} className={`nav-item ${page === item.id ? 'selected' : ''}`} onClick={() => navigate(item.id)} aria-label={item[app.lang]} aria-current={page === item.id ? 'page' : undefined}>
              {page === item.id && <motion.span className="nav-selection" layoutId="navigation-active" transition={{type:'spring', stiffness:420, damping:38}}/>}
              <Icon name={item.id} size={18}/><span>{item[app.lang]}</span>{page === item.id && <i className="nav-pixel"/>}
            </button>)}
          </div>)}
        </nav>
        <div className="sidebar-end"><span className="sidebar-world-line"/><p>{app.t('Большие идеи.\nБесконечный мир.','Big ideas.\nAn infinite world.')}</p><span className="sidebar-caption">One world. Your rules.</span></div>
        <div className="sidebar-mobile-actions"><button className="nav-item" onClick={logout}><Icon name="logout" size={18}/><span>{app.t('Выйти из панели','Sign out')}</span></button></div>
      </aside>
      <div className="workspace">
        <header className="topbar">
          <button className="mobile-menu icon-button" aria-label={menu?app.t('Закрыть навигацию','Close navigation'):app.t('Открыть навигацию','Open navigation')} aria-expanded={menu} aria-controls="workspace-navigation" onClick={() => setMenu(value => !value)}><span/><span/><span/></button>
          <div className="breadcrumb"><span>{app.t('Пульт управления','Control center')}</span><strong>{title?.[app.lang]}</strong></div>
          <div className="topbar-actions">
            <button className="command-launcher" onClick={() => {setQuery(''); setCommands(true);}} aria-label={app.t('Найти раздел','Find a section')}><Icon name="search" size={17}/><span>{app.t('Перейти к…','Jump to…')}</span><kbd>Ctrl K</kbd></button>
            <span className={`connection ${live.status}`}><i/>{connected ? app.t('Подключён','Connected') : live.status === 'connecting' ? app.t('Подключение','Connecting') : app.t('Нет связи','Offline')}</span>
            <button className="language-button" onClick={changeLang} aria-label={app.t('Switch to English','Переключить на русский')}>{app.lang === 'ru' ? 'EN' : 'RU'}</button>
            <button className="user-chip" onClick={profile} aria-label={app.t('Открыть мой профиль','Open my account')}><span>{app.auth.user?.name?.slice(0,1).toUpperCase()}</span><div>{app.auth.user?.name}<small>{roleLabel(app.auth.user?.role,app.lang)}</small></div></button>
            <button className="icon-button logout-button" onClick={logout} aria-label={app.t('Выйти','Sign out')}><Icon name="logout" size={18}/></button>
          </div>
        </header>
        {children}
        <footer className="workspace-footer"><span>Создано пользователем Krivzy</span><span>Minecraft Control Center <i/> One JAR</span></footer>
      </div>
    </div>
    {commands && <Modal title={app.t('Куда отправимся?','Where to next?')} close={() => setCommands(false)}>
      <div className="command-menu"><label className="command-search"><Icon name="search"/><input data-autofocus autoFocus aria-label={app.t('Поиск раздела','Search sections')} placeholder={app.t('Начни вводить название…','Type a section name…')} value={query} onChange={event => setQuery(event.target.value)} onKeyDown={event => {if (event.key === 'Enter' && results[0]) navigate(results[0].id);}}/></label>
        <div className="command-results">{results.map(item => <button key={item.id} onClick={() => navigate(item.id)}><Icon name={item.id}/><span>{item[app.lang]}</span><Icon name="arrow" size={16}/></button>)}{results.length === 0 && <p>{app.t('Такого раздела нет','No matching section')}</p>}</div>
        <small>{app.t('Enter — открыть первый результат · Esc — закрыть','Enter to open the first result · Esc to close')}</small>
      </div>
    </Modal>}
  </>;
}
