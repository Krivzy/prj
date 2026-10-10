import type {ActionResult, Auth} from './types';

let csrf = '';
export class ApiError extends Error {status: number; constructor(message: string, status: number) {super(message); this.status = status;}}
export function setAuth(auth: Auth) {csrf = auth.csrf ?? '';}
export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  const controller = new AbortController();
  const timeout = window.setTimeout(() => controller.abort(), 20000);
  const headers: Record<string, string> = {'Accept': 'application/json', ...options.headers as Record<string,string>};
  if (options.body && !headers['Content-Type']) headers['Content-Type'] = 'application/json';
  if (options.method && options.method !== 'GET' && csrf) headers['X-MCC-CSRF'] = csrf;
  try {
    const response = await fetch(path, {...options, credentials: 'same-origin', headers, signal: options.signal ?? controller.signal});
    const body = response.status === 204 ? null : await response.json().catch(() => null);
    if (!response.ok) {
      if (response.status === 401) window.dispatchEvent(new Event('mcc-session-expired'));
      throw new ApiError(body?.message ?? body?.error ?? `HTTP ${response.status}`, response.status);
    }
    return body as T;
  } catch(error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw new Error('Запрос прерван или время ожидания истекло / Request timed out');
    throw error;
  } finally {window.clearTimeout(timeout);}
}
export const post = <T>(path: string, body: unknown) => request<T>(path, {method: 'POST', body: JSON.stringify(body)});
export async function postTracked<T>(path: string, body: Record<string, unknown>): Promise<T> {
  const id = crypto.randomUUID();
  let result = await post<T & {pending?: boolean; id?: string}>(path, {...body, id});
  const deadline = Date.now() + 120000;
  while (result?.pending) {
    if (Date.now() > deadline) throw new Error('Действие ещё выполняется. Обновите состояние перед повторной отправкой. / Action still pending. Refresh before retrying.');
    await new Promise(resolve => window.setTimeout(resolve, 1000));
    result = await request<T & {pending?: boolean}>(`/api/actions/${id}`);
  }
  return result;
}
export async function execute(action: string, params: Record<string, unknown>, confirmed = false) {
  const id = crypto.randomUUID();
  let result = await post<ActionResult>('/api/action', {id, action, params, confirmed});
  const deadline = Date.now() + 120000;
  if (result.pending) window.dispatchEvent(new CustomEvent('mcc-action-pending', {detail: {id, action}}));
  while (result.pending) {
    if (Date.now() > deadline) throw new Error('Действие ещё выполняется. Обновите состояние; повторная отправка может создать новое действие. / Action still pending. Refresh state before retrying.');
    await new Promise(resolve => window.setTimeout(resolve, 1000));
    result = await request<ActionResult>(`/api/actions/${id}`);
  }
  if (!result.ok) throw new Error(typeof result.result === 'string' ? result.result : 'Операция отклонена / Action rejected');
  return result;
}
