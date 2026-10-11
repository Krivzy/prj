export type ModuleFlags=Record<string,boolean>;
const pageModule:Record<string,string>={players:'playerSnapshots',map:'map',mobs:'entitySnapshots',events:'events',ores:'oreMonitor',luckperms:'integrations',coreprotect:'integrations',advancedban:'integrations',chat:'chat',console:'console',automations:'automations',audit:'timeline'};
export function moduleEnabled(modules:ModuleFlags,key:string){return modules[key]!==false;}
export function imageMapWork(modules:ModuleFlags){const base=moduleEnabled(modules,'map');return {base,players:base&&moduleEnabled(modules,'playerSnapshots')};}
export function pageEnabled(modules:ModuleFlags,page:string){const key=pageModule[page];return !key||moduleEnabled(modules,key);}
export function topicEnabled(modules:ModuleFlags,topic:string){const key=({players:'playerSnapshots',inspector:'playerSnapshots',inventory:'playerSnapshots',spawn:'entitySnapshots',automation:'automations',events:'events',chat:'chat',console:'console',timeline:'timeline'} as Record<string,string>)[topic];return !key||moduleEnabled(modules,key);}
export function requestedTopics(role:string,modules:ModuleFlags){const topics=['state','players','timeline','events','spawn','world'];if(role!=='viewer')topics.push('chat','punishments');if(['owner','admin'].includes(role))topics.push('console','automation');return topics.filter(topic=>topicEnabled(modules,topic));}
