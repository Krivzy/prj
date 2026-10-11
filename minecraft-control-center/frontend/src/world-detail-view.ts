import type {World} from './types';

/** Live summaries update counters; detailed records retain rules for the same world identity. */
export function mergeWorldDetails(live:World[]|null|undefined,details:World[]|null|undefined):World[]{
  if(live==null)return details?.slice()??[];
  const byId=new Map((details??[]).map(world=>[world.uuid,world]));
  return live.map(summary=>{
    const detail=byId.get(summary.uuid);
    if(!detail||detail.name!==summary.name||detail.environment!==summary.environment)return summary;
    return {...detail,...summary,gamerules:detail.gamerules};
  });
}
