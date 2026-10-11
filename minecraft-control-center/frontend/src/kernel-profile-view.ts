export type KernelProfileId = 'custom' | 'vanilla' | 'vanilla-throughput' | 'vanilla-performance' | 'optimized' | 'maximum-optimized';
export type KernelProfileOption = {id: KernelProfileId; available: boolean; managed: boolean; viewDistance: number | null; simulationDistance: number | null; preservesDistances?:boolean};
export type KernelProfileStatus = {
  kernel: string; minecraftVersion: string; profile: KernelProfileId; managed: boolean; pendingRestart: boolean; revision: string;
  capabilities: {managedFiles: boolean; liveDistances: boolean; startupHookConfigured: boolean; startupReapply: string; reason: string};
  profiles: KernelProfileOption[]; files: {path: string; sha256: string}[]; warnings: string[]; startupMessage: string;
  worlds: {name: string; viewDistance: number; simulationDistance: number}[];
};
export type KernelProfilePlan = {
  planId: string; profile: KernelProfileId; expiresAt: number; files: {path: string; beforeSha256: string; afterSha256: string; changes: {key: string; before: unknown; after: unknown}[]}[];
  liveDistances: boolean; pendingRestart: boolean; customPreservesFiles: boolean; preservesDistances?:boolean; tradeoffs?:string[];
};
export type KernelProfileResult = KernelProfileStatus & {saved: boolean; liveError: string; liveApplied: {world: string}[]; backupId: string};
export function canSelectKernelProfile(owner: boolean, busy: boolean, option: KernelProfileOption): boolean {return owner && !busy && option.available;}
export function canApplyKernelProfile(owner: boolean, plan: KernelProfilePlan | null, now: number): boolean {
  return !!(owner && plan && /^[a-f0-9-]{36}$/i.test(plan.planId) && Number.isFinite(plan.expiresAt) && plan.expiresAt > now && (plan.profile !== 'custom' || (plan.customPreservesFiles && plan.files.length === 0 && !plan.liveDistances)) && (!['maximum-optimized','vanilla-throughput'].includes(plan.profile)||(plan.preservesDistances===true&&!plan.liveDistances&&plan.files.every(file=>file.path!=='server.properties'&&file.changes.every(change=>!/(?:view|simulation)-distance$/.test(change.key))))));
}
export function kernelProfileValue(value: unknown): string {
  if (value === null || value === undefined) return '—';
  if (typeof value === 'boolean' || typeof value === 'number' || typeof value === 'string') return String(value);
  return '—';
}
