import type {Auth,LicenseState} from './types';
import type {KernelProfileId,KernelProfileStatus,KernelProfileResult} from './kernel-profile-view';

export type SetupSession={required:boolean;linkRequired?:boolean;authenticated?:boolean;player?:{name:string;uuid:string};csrf?:string;expiresAt?:number;license?:LicenseState;profiles?:KernelProfileStatus;core?:{available?:boolean;name?:string;version?:string;active?:boolean;connected?:boolean;pendingRestart?:boolean;selection?:string;message?:string}};
export type SetupComplete=Auth&{setup?:{completed:boolean;profileResult?:KernelProfileResult;coreChoice?:string;configurationPending?:boolean;message?:string;coreInstallation?:string}};
export type SetupLicenseMode='trial'|'temporary-full';
export function setupTokenFromFragment(hash:string){const params=new URLSearchParams(hash.replace(/^#/,''));const token=params.get('setup');return token&&/^[A-Za-z0-9_-]{32,256}$/.test(token)?token:null;}
export function watchSetupLink(target:Pick<Window,'addEventListener'|'removeEventListener'|'location'>,receive:()=>void){
  const changed=()=>{if(new URLSearchParams(target.location.hash.replace(/^#/, '')).has('setup'))receive();};
  target.addEventListener('hashchange',changed);
  return()=>target.removeEventListener('hashchange',changed);
}
export function removeSetupFragment(location:{hash:string;pathname:string;search:string},history:{replaceState:(data:unknown,title:string,url:string)=>void}){if(new URLSearchParams(location.hash.replace(/^#/, '')).has('setup'))history.replaceState(null,'',location.pathname+location.search);}
export function passwordProblem(password:string,confirmation:string,name:string):'short'|'long'|'weak'|'mismatch'|null{
  if(password.length<12)return 'short';if(password.length>128)return 'long';
  const normalized=password.toLowerCase();if(['adminadmin','passwordpassword','123456789012','qwertyqwerty'].includes(normalized)||normalized===name.toLowerCase()||/^(.)\1+$/.test(password))return 'weak';
  if(password!==confirmation)return 'mismatch';return null;
}
export function canCompleteSetup(session:SetupSession|null,password:string,confirmation:string,profile:KernelProfileId,plan:{profile:string;expiresAt:number}|null,now:number){return !!session?.required&&!!session.player&&!!session.csrf&&Number.isFinite(session.expiresAt)&&session.expiresAt!>now&&!passwordProblem(password,confirmation,session.player.name)&&!!plan&&plan.profile===profile&&plan.expiresAt>now;}
