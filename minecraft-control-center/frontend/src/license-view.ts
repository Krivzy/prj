import type {Auth,LicenseState} from './types';

/** Accept only a complete license policy from an accepted server snapshot. */
export function snapshotLicense(value:unknown):LicenseState|undefined{
  if(!value||typeof value!=='object')return;
  const next=value as Partial<LicenseState>;
  if(!['trial','temporary-full'].includes(String(next.mode))||typeof next.fullAccess!=='boolean'||next.fullAccess!==(next.mode==='temporary-full')||typeof next.keyActivationAvailable!=='boolean')return;
  if(!Array.isArray(next.allowedFeatures)||!Array.isArray(next.lockedFeatures)||next.allowedFeatures.length>32||next.lockedFeatures.length>32||![...next.allowedFeatures,...next.lockedFeatures].every(item=>typeof item==='string'))return;
  return next as LicenseState;
}
/** A license update changes access only; the current login, identity and CSRF remain intact. */
export function authWithSnapshotLicense(auth:Auth|null,value:unknown):Auth|null{
  const next=snapshotLicense(value);
  if(!auth?.authenticated||!next||JSON.stringify(auth.license)===JSON.stringify(next))return auth;
  return {...auth,license:next};
}

const featureByPage:Record<string,string>={dashboard:'overview',mobs:'entities',ores:'fairPlay',luckperms:'integrations',coreprotect:'integrations',advancedban:'integrations',audit:'timeline',backups:'backups'};
const trialModules=new Set(['metrics','console','chat']);
const allModules=['metrics','console','chat','playerSnapshots','entitySnapshots','oreMonitor','integrations','events','automations','map','timeline','catalog'];
export function licenseAllowsPage(license:LicenseState|undefined,page:string){return !license||license.fullAccess===true||license.allowedFeatures?.includes(featureByPage[page]??page)===true;}
export function licenseAllowsModule(license:LicenseState|undefined,module:string){return !license||license.fullAccess===true||trialModules.has(module);}
export function effectiveModules(modules:Record<string,boolean>,license:LicenseState|undefined){if(!license||license.fullAccess)return modules;return Object.fromEntries([...new Set([...allModules,...Object.keys(modules)])].map(key=>[key,licenseAllowsModule(license,key)&&modules[key]!==false]));}
export function licenseAllowsTopic(license:LicenseState|undefined,topic:string){return !license||license.fullAccess===true||['state','console','chat'].includes(topic);}
export function licenseAllowsAction(license:LicenseState|undefined,action:string){return !license||license.fullAccess===true||['console.command','chat.broadcast','server.settings','server.restart'].includes(action);}
