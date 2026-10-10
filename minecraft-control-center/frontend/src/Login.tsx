import {lazy, Suspense, useEffect, useRef, useState} from 'react';
import type {FormEvent} from 'react';
import gsap from 'gsap';
import {post, request, setAuth} from './api';
import type {Auth} from './types';
import {Brand} from './Shell';
import {Button, Icon} from './ui';
const OverworldScene = lazy(() => import('./OverworldScene'));

export default function Login({status, onAuth, lang, changeLang}: {
  status: Auth; onAuth: (auth:Auth) => void; lang:'ru'|'en'; changeLang:() => void;
}) {
  const t = (ru:string,en:string) => lang === 'ru' ? ru : en;
  const [username,setUsername] = useState('');
  const [password,setPassword] = useState('');
  const [token,setToken] = useState('');
  const [error,setError] = useState('');
  const [busy,setBusy] = useState(false);
  const [show,setShow] = useState(false);
  const intro = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const media = gsap.matchMedia();
    media.add('(prefers-reduced-motion: no-preference)', () => {
      const context = gsap.context(() => {
        gsap.from('.login-word', {y:22, opacity:0, duration:.65, stagger:.08, ease:'power3.out'});
        gsap.from('.login-intro-detail', {opacity:0, y:8, duration:.45, delay:.25});
      }, intro);
      return () => context.revert();
    });
    return () => media.revert();
  }, [lang]);
  async function submit(event:FormEvent) {
    event.preventDefault(); setBusy(true); setError('');
    try {
      const auth = await post<Auth>(status.setupRequired ? '/api/auth/setup' : '/api/auth/login', {username,password,...status.setupRequired ? {token} : {}});
      const actual = auth.authenticated ? auth : await request<Auth>('/api/auth/status');
      setAuth(actual); setPassword(''); setToken(''); onAuth(actual);
    } catch (reason) {setError(reason instanceof Error ? reason.message : String(reason));}
    finally {setBusy(false);}
  }
  return <main className="auth-layout">
    <section className="auth-scene">
      <Brand/>
      <div className="login-intro" ref={intro}>
        <span className="login-world-tag"><i/>{t('Твоя следующая глава','Your next chapter')}</span>
        <h1>{(lang === 'ru' ? ['Создавай.','Управляй.','Играй.'] : ['Build.','Control.','Play.']).map(word => <span className="login-word" key={word}>{word}</span>)}</h1>
        <p className="login-intro-detail">{t('Весь Minecraft-сервер.\nВ одном месте. По твоим правилам.','Your entire Minecraft server.\nOne place. Your rules.')}</p>
      </div>
      <div className="login-landscape"><Suspense fallback={<div className="scene-placeholder"/>}><OverworldScene/></Suspense></div>
      <div className="auth-footer"><span className="pixel-cross" aria-hidden="true"/><span>Paper · One JAR · Live control</span><span>MCC</span></div>
    </section>
    <section className="auth-panel">
      <button className="language-button" onClick={changeLang}>{lang === 'ru' ? 'English' : 'Русский'}</button>
      <div className="auth-form">
        <span className="auth-step"><i/>{status.setupRequired ? t('Первый запуск','First launch') : t('Вход в панель','Control center access')}</span>
        <h2>{status.setupRequired ? t('Начало\nнового мира.','A new world\nbegins here.') : t('Твой мир\nждёт тебя.','Your world\nis waiting.')}</h2>
        <p>{status.setupRequired ? t('Создай владельца панели с помощью setup token сервера.','Create the owner account using the server setup token.') : t('Войди, чтобы продолжить приключение.','Sign in to continue the adventure.')}</p>
        <form onSubmit={submit} className="stack">
          {status.setupRequired && <label className="field"><span>Setup token</span><input type="password" autoComplete="off" required value={token} onChange={event => setToken(event.target.value)}/></label>}
          <label className="field"><span>{t('Логин','Username')}</span><input autoComplete="username" placeholder={t('Твоё имя','Your name')} required minLength={3} maxLength={32} value={username} onChange={event => setUsername(event.target.value)}/></label>
          <div className="field"><label htmlFor="login-password">{t('Пароль','Password')}</label><div className="password-input"><input id="login-password" placeholder="••••••••••••" type={show ? 'text' : 'password'} required minLength={status.setupRequired ? 12 : undefined} maxLength={128} autoComplete={status.setupRequired ? 'new-password' : 'current-password'} value={password} onChange={event => setPassword(event.target.value)}/><button type="button" onClick={() => setShow(value => !value)} aria-label={show ? t('Скрыть пароль','Hide password') : t('Показать пароль','Show password')}>{show ? '◉' : '◎'}</button></div></div>
          {error && <div className="error-box" role="alert">{error}</div>}
          <Button type="submit" className="primary login-submit" disabled={busy} aria-busy={busy}>{busy && <span className="spinner"/>}{status.setupRequired ? t('Создать владельца','Create owner') : t('Войти в свой мир','Enter your world')}<Icon name="arrow" size={19}/></Button>
        </form>
        <span className="auth-note"><Icon name="check" size={15}/>{t('Только ты и твой сервер.','Just you and your server.')}</span>
      </div>
      <footer className="auth-credits">Создано пользователем Krivzy</footer>
    </section>
  </main>;
}
