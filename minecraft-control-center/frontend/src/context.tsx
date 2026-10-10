import {createContext, useCallback, useContext, useEffect, useState} from 'react';
import type {SetStateAction} from 'react';
import {request} from './api';
import type {Auth} from './types';
export type Page = 'dashboard' | 'players' | 'worlds' | 'map' | 'mobs' | 'events' | 'console' | 'chat' | 'automations' | 'backups' | 'plugins' | 'audit' | 'settings';
export type AppContextType = {auth: Auth; lang: 'ru' | 'en'; t: (ru: string, en: string) => string; navigate: (page: Page) => void; action: (name: string, params: Record<string, unknown>, danger?: string) => Promise<unknown>; confirm:(text:string)=>Promise<boolean>; notify: (text: string, error?: boolean) => void; readonly: boolean; administrator: boolean; owner: boolean};
export const AppContext = createContext<AppContextType | null>(null);
export function useApp() {const context = useContext(AppContext); if (!context) throw new Error('Missing MCC context'); return context;}
export function useResource<T>(path: string | null) {
  type ResourceState = {path: string | null; data: T | null; error: string; loading: boolean};
  const empty = (key: string | null): ResourceState => ({path:key,data:null,error:'',loading:!!key});
  const [state, setState] = useState<ResourceState>(()=>empty(path));
  const [revision, setRevision] = useState(0);
  // Hide another URL's result in the render that changes the URL, before effects run.
  const current = path && state.path===path ? state : empty(path);
  const setData = useCallback((value: SetStateAction<T | null>)=>setState(previous=>{
    const base = previous.path===path ? previous : {path,data:null,error:'',loading:!!path};
    return {...base,data:typeof value==='function'?(value as (previous:T|null)=>T|null)(base.data):value};
  }),[path]);
  useEffect(() => {
    if (!path) {setState(empty(null)); return;}
    let active = true;
    setState(previous=>({...empty(path),data:previous.path===path?previous.data:null}));
    void request<T>(path).then(value => {if (active) setState({path,data:value,error:'',loading:false});}).catch(err => {
      if (active) setState(previous=>({...previous,error:err instanceof Error?err.message:String(err),loading:false}));
    });
    return () => {active = false;};
  }, [path, revision]);
  return {...current,reload: () => setRevision(x => x + 1),setData};
}
export function formatNumber(value: unknown, digits = 0) {return typeof value === 'number' && Number.isFinite(value) ? value.toLocaleString(undefined, {maximumFractionDigits: digits}) : '—';}
export function bytes(value: unknown) {return typeof value === 'number' && value > 0 ? `${(value / 1024 / 1024).toFixed(0)} MiB` : '—';}
export function time(value: unknown) {if (!value) return '—'; const date = new Date(value as string | number); return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleTimeString([], {hour:'2-digit', minute:'2-digit', second:'2-digit'});}
export function duration(seconds: number) {return seconds >= 86400 ? `${Math.floor(seconds/86400)}d ${Math.floor(seconds%86400/3600)}h` : `${Math.floor(seconds/3600)}h ${Math.floor(seconds%3600/60)}m`;}
