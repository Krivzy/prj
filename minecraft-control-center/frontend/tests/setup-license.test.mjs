import test from 'node:test';
import assert from 'node:assert/strict';
import {canCompleteSetup,passwordProblem,removeSetupFragment,setupTokenFromFragment,watchSetupLink} from '../src/setup-view.ts';
import {effectiveModules,licenseAllowsAction,licenseAllowsPage,licenseAllowsTopic} from '../src/license-view.ts';
import {availablePages,navigationPages} from '../src/navigation.ts';
const trial={mode:'trial',fullAccess:false,keyActivationAvailable:false,allowedFeatures:['overview','console','chat','settings'],lockedFeatures:[]};
test('One-time setup capability is read from a valid fragment and erased without exposing it to a request URL',()=>{
  const token='a'.repeat(43);assert.equal(setupTokenFromFragment('#setup='+token),token);assert.equal(setupTokenFromFragment('#setup=../bad'),null);
  let replaced;removeSetupFragment({hash:'#setup='+token,pathname:'/',search:'?lang=ru'},{replaceState:(...args)=>{replaced=args;}});assert.deepEqual(replaced,[null,'','/?lang=ru']);
});
test('Setup completion needs bound identity, strong matching password, unexpired session and current profile plan',()=>{
  const session={required:true,player:{name:'TestOwner',uuid:'fixture'},csrf:'test-only',expiresAt:200};const plan={profile:'custom',expiresAt:150};
  assert.equal(canCompleteSetup(session,'long unique passphrase','long unique passphrase','custom',plan,100),true);
  for(const invalid of [{...session,player:undefined},{...session,csrf:''},{...session,expiresAt:99}])assert.equal(canCompleteSetup(invalid,'long unique passphrase','long unique passphrase','custom',plan,100),false);
  assert.equal(canCompleteSetup(session,'long unique passphrase','different confirmation','custom',plan,100),false);
  assert.equal(canCompleteSetup(session,'long unique passphrase','long unique passphrase','vanilla',plan,100),false);
  assert.equal(canCompleteSetup(session,'long unique passphrase','long unique passphrase','custom',plan,151),false);
  assert.equal(passwordProblem('aaaaaaaaaaaa','aaaaaaaaaaaa','TestOwner'),'weak');assert.equal(passwordProblem('123456789012','123456789012','TestOwner'),'weak');
});
test('Trial grants only selected pages and never starts locked modules or topics',()=>{
  assert.deepEqual(['dashboard','console','chat','settings','players','worlds','map','plugins','luckperms','backups'].filter(page=>licenseAllowsPage(trial,page)),['dashboard','console','chat','settings']);
  const flags=effectiveModules({playerSnapshots:true,map:true,chat:true,console:false},trial);assert.equal(flags.playerSnapshots,false);assert.equal(flags.map,false);assert.equal(flags.chat,true);assert.equal(flags.console,false);
  assert.deepEqual(['state','players','world','punishments','events','console','chat'].filter(topic=>licenseAllowsTopic(trial,topic)),['state','console','chat']);
  assert.equal(licenseAllowsAction(trial,'console.command'),true);assert.equal(licenseAllowsAction(trial,'player.ban'),false);
  assert.equal(licenseAllowsPage({...trial,fullAccess:true},'map'),true);assert.equal(licenseAllowsPage(undefined,'map'),true);
});
test('Trial navigation leaves full features visible and unavailable while command search offers only usable pages',()=>{
  const flags=effectiveModules({},trial),visible=navigationPages('Owner',[],flags,trial),usable=availablePages('Owner',[],flags,trial);
  assert.equal(visible.some(page=>page.id==='map'),true);assert.equal(visible.some(page=>page.id==='players'),true);
  assert.deepEqual(usable.map(page=>page.id),['dashboard','console','chat','settings']);
  assert.equal(navigationPages('Viewer',[],{},trial).some(page=>page.id==='console'),false);
});

test('A new Minecraft link in an already-open tab restarts setup, and the listener is removed on unmount',()=>{
  const target=new EventTarget();target.location={hash:''};let received=0;
  const dispose=watchSetupLink(target,()=>received++);
  target.location.hash='#other';target.dispatchEvent(new Event('hashchange'));assert.equal(received,0);
  target.location.hash='#setup='+'a'.repeat(43);target.dispatchEvent(new Event('hashchange'));assert.equal(received,1);
  target.location.hash='#setup='+'b'.repeat(43);target.dispatchEvent(new Event('hashchange'));assert.equal(received,2);
  dispose();target.dispatchEvent(new Event('hashchange'));assert.equal(received,2);
});
