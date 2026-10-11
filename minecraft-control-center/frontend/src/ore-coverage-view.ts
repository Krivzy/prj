const KEY='mcc.ore-coverage-ack';
const valid=(value:unknown):value is string=>typeof value==='string'&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
export function readCoverageAck(storage:Pick<Storage,'getItem'>):string{
  try{const value=storage.getItem(KEY);return valid(value)?value:'';}catch{return '';}
}
export function acknowledgeCoverage(storage:Pick<Storage,'setItem'>,bootId:string):string{
  if(!valid(bootId))return '';
  try{storage.setItem(KEY,bootId);}catch{/* In-memory dismissal still works when storage is unavailable. */}
  return bootId;
}
export function coverageNoticeVisible(partial:boolean,bootId:string|undefined,ack:string):boolean{
  return partial&&(!valid(bootId)||bootId!==ack);
}
