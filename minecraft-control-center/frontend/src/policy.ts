const moderatorActions = new Set(['player.kick','player.ban','player.unban','player.mute','player.unmute','player.heal','player.feed','player.message','chat.broadcast','player.freeze','player.unfreeze']);
const ownerActions = new Set(['console.command','server.settings','player.op','player.deop','player.ipban','player.ban_ip','player.unban_ip']);
export function canExecute(role: string, action: string): boolean {
  const normalized = role.toLowerCase();
  if (normalized === 'viewer') return false;
  if (ownerActions.has(action)) return normalized === 'owner';
  if (normalized === 'moderator') return moderatorActions.has(action);
  return normalized === 'owner' || normalized === 'admin';
}
export function mapPoint(origin: {x:number;z:number;size:number;step:number}, relativeX:number, relativeY:number) {
  const col=Math.max(0,Math.min(origin.size-1,Math.floor(relativeX*origin.size)));
  const row=Math.max(0,Math.min(origin.size-1,Math.floor(relativeY*origin.size)));
  return {x:origin.x+col*origin.step,z:origin.z+row*origin.step};
}
export function mergeEntries<T>(history:T[],live:T[],limit:number):T[] {
  const seen=new Set<string>();
  return [...history,...live].filter(entry=>{const key=JSON.stringify(entry);if(seen.has(key))return false;seen.add(key);return true;}).slice(-limit);
}
