import {post,request} from './api.ts';
import type {SetupSession} from './setup-view';

// StrictMode mounts twice. Share only the in-flight exchange; never keep a token in storage.
const exchanges=new Map<string,Promise<SetupSession>>();
export function getSetupSession(token:string|null){
  const key=token??'resume';const current=exchanges.get(key);if(current)return current;
  const pending=token?post<SetupSession>('/api/setup/claim',{token}):request<SetupSession>('/api/setup/status');
  exchanges.set(key,pending);void pending.finally(()=>{if(exchanges.get(key)===pending)exchanges.delete(key);}).catch(()=>{});return pending;
}
