export type AcceptedRestart = {accepted:true;requestId:string;bootId:string;startedAt:number};
const UUID=/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const MAX_AGE=600_000;
function uuid(value:unknown):value is string {return typeof value==='string'&&UUID.test(value);}

/** A stored receipt is a screen hint, not permission to send another restart. */
export function parseAcceptedRestart(value:unknown,now:number):AcceptedRestart|null {
  if(!value||typeof value!=='object'||!Number.isFinite(now))return null;
  const v=value as Partial<AcceptedRestart>;
  if(v.accepted!==true||!uuid(v.requestId)||!uuid(v.bootId)||typeof v.startedAt!=='number'||!Number.isSafeInteger(v.startedAt)||v.startedAt<0)return null;
  const age=now-v.startedAt;if(age<0||age>=MAX_AGE)return null;
  return {accepted:true,requestId:v.requestId,bootId:v.bootId,startedAt:v.startedAt};
}

/** A still-ready old process or malformed health body cannot finish a restart screen. */
export function restartHasCompleted(health:unknown,previousBootId:string):boolean {
  if(!uuid(previousBootId)||!health||typeof health!=='object')return false;
  const value=health as {ready?:unknown;bootId?:unknown};
  return value.ready===true&&uuid(value.bootId)&&value.bootId.toLowerCase()!==previousBootId.toLowerCase();
}
