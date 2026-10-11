import type {LicenseState} from './types';

const featureByPage:Record<string,string>={dashboard:'overview',mobs:'entities',ores:'fairPlay',luckperms:'integrations',coreprotect:'integrations',advancedban:'integrations',audit:'timeline',backups:'backups'};
const trialModules=new Set(['metrics','console','chat']);
const allModules=['metrics','console','chat','playerSnapshots','entitySnapshots','oreMonitor','integrations','events','automations','map','timeline','catalog'];
export function licenseAllowsPage(license:LicenseState|undefined,page:string){return !license||license.fullAccess===true||license.allowedFeatures?.includes(featureByPage[page]??page)===true;}
export function licenseAllowsModule(license:LicenseState|undefined,module:string){return !license||license.fullAccess===true||trialModules.has(module);}
export function effectiveModules(modules:Record<string,boolean>,license:LicenseState|undefined){if(!license||license.fullAccess)return modules;return Object.fromEntries([...new Set([...allModules,...Object.keys(modules)])].map(key=>[key,licenseAllowsModule(license,key)&&modules[key]!==false]));}
export function licenseAllowsTopic(license:LicenseState|undefined,topic:string){return !license||license.fullAccess===true||['state','console','chat'].includes(topic);}
export function licenseAllowsAction(license:LicenseState|undefined,action:string){return !license||license.fullAccess===true||['console.command','chat.broadcast','server.settings','server.restart'].includes(action);}
