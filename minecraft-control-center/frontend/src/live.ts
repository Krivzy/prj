import {useSyncExternalStore} from 'react';
import {request} from './api';
import type {Player, Snapshot, StreamEntry} from './types';
import {mergeEntries} from './policy';

type LiveState = {snapshot: Snapshot | null; players: Player[]; status: 'connecting' | 'live' | 'offline'; sequence: number; error: string; chat: StreamEntry[]; console: StreamEntry[]; timeline: StreamEntry[]; updated: number};
let state: LiveState = {snapshot: null, players: [], status: 'connecting', sequence: 0, error: '', chat: [], console: [], timeline: [], updated: 0};
const listeners = new Set<() => void>();
const emit = (next: Partial<LiveState>) => {state = {...state, ...next}; listeners.forEach(fn => fn());};
export const useLive = () => useSyncExternalStore(fn => {listeners.add(fn); return () => listeners.delete(fn);}, () => state);
export function resetLive() {emit({snapshot: null, players: [], status: 'offline', sequence: 0, error: '', chat: [], console: [], timeline: []});}
export async function refreshLive() {
  const responses = await Promise.allSettled([request<Snapshot>('/api/state'), request<Player[]>('/api/players')]);
  const patch: Partial<LiveState> = {updated: Date.now()};
  if (responses[0].status === 'fulfilled') {
    const currentStamp = state.snapshot ? new Date(state.snapshot.timestamp).getTime() : 0;
    const nextStamp = new Date(responses[0].value.timestamp).getTime();
    if (!state.snapshot || !Number.isFinite(currentStamp) || !Number.isFinite(nextStamp) || nextStamp >= currentStamp) patch.snapshot = responses[0].value;
    patch.error = '';
  }
  else patch.error = responses[0].reason instanceof Error ? responses[0].reason.message : 'Не удалось получить данные';
  if (responses[1].status === 'fulfilled') patch.players = responses[1].value;
  emit(patch);
}
export function connectLive(role = 'viewer') {
  let stopped = false;
  let attempts = 0;
  let ws: WebSocket | undefined;
  let retry: ReturnType<typeof setTimeout> | undefined;
  let heartbeat: ReturnType<typeof setInterval> | undefined;
  let lastPong = Date.now();
  let refreshTimer: ReturnType<typeof setTimeout> | undefined;
  function connect() {
    if (stopped) return;
    emit({status: 'connecting'});
    ws = new WebSocket(`${location.protocol === 'https:' ? 'wss:' : 'ws:'}//${location.host}/ws`);
    ws.onopen = () => {
      attempts = 0; lastPong = Date.now(); emit({status: 'live', sequence: 0});
      void refreshLive();
      const loadHistory = (topic:'chat'|'console') => void request<StreamEntry[]>(`/api/${topic}`).then(history=>emit({[topic]:mergeEntries(history,state[topic],250)})).catch(()=>{});
      if (role !== 'viewer') loadHistory('chat');
      if (['owner','admin'].includes(role)) loadHistory('console');
      const topics = ['state', 'players', 'timeline', 'events','spawn','world'];
      if (role !== 'viewer') topics.push('chat','punishments');
      if (['owner','admin'].includes(role)) topics.push('console','automation');
      ws?.send(JSON.stringify({type: 'subscribe', topics}));
      heartbeat = setInterval(() => {
        if (Date.now() - lastPong > 65000) ws?.close();
        else if (ws?.readyState === WebSocket.OPEN) ws.send(JSON.stringify({type: 'ping'}));
      }, 25000);
    };
    ws.onmessage = event => {
      let msg: {type: string; topic?: string; sequence?: number; data?: unknown};
      try {msg = JSON.parse(event.data);} catch {return;}
      lastPong = Date.now();
      if (msg.type === 'hello') {emit({sequence: msg.sequence ?? 0}); return;}
      if (msg.type !== 'event' || !msg.topic) return;
      if (typeof msg.sequence === 'number' && msg.sequence <= state.sequence) return;
      const patch: Partial<LiveState> = {sequence: msg.sequence ?? state.sequence, updated: Date.now()};
      if (msg.topic === 'state' && msg.data && typeof msg.data === 'object') patch.snapshot = msg.data as Snapshot;
      if (msg.topic === 'players' && Array.isArray(msg.data)) patch.players = msg.data as Player[];
      if (['chat', 'console', 'timeline'].includes(msg.topic)) {
        const topic = msg.topic as 'chat' | 'console' | 'timeline';
        const entries = Array.isArray(msg.data) ? msg.data : [msg.data];
        patch[topic] = [...state[topic], ...entries.filter(Boolean) as StreamEntry[]].slice(-250);
      }
      emit(patch);
      window.dispatchEvent(new CustomEvent('mcc-event', {detail: msg}));
      if(msg.topic==='punishments')window.dispatchEvent(new Event('mcc-punishments'));
      if(msg.topic==='automation')window.dispatchEvent(new CustomEvent('mcc-automation-event',{detail:msg.data}));
      if ((msg.topic === 'players' && !Array.isArray(msg.data)) || msg.topic === 'events') {
        if (!refreshTimer) refreshTimer = setTimeout(() => {refreshTimer = undefined; void refreshLive();}, 350);
      }
    };
    ws.onclose = () => {
      clearInterval(heartbeat); emit({status: 'offline'});
      if (!stopped) retry = setTimeout(connect, Math.min(30000, 1000 * 2 ** attempts++) + Math.random() * 400);
    };
    ws.onerror = () => ws?.close();
  }
  connect();
  return () => {stopped = true; clearTimeout(retry); clearTimeout(refreshTimer); clearInterval(heartbeat);if(ws){ws.onopen=null;ws.onclose=null;ws.onmessage=null;ws.onerror=null;ws.close();}};
}
