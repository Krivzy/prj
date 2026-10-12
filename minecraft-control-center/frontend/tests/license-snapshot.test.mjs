import test from 'node:test';
import assert from 'node:assert/strict';
import {authWithSnapshotLicense,snapshotLicense} from '../src/license-view.ts';
const trial={mode:'trial',fullAccess:false,keyActivationAvailable:false,allowedFeatures:['overview','console','chat','settings'],lockedFeatures:['players']};
const full={...trial,mode:'temporary-full',fullAccess:true,allowedFeatures:[...trial.allowedFeatures,'players'],lockedFeatures:[]};
test('A snapshot license replaces access and preserves the active account and CSRF',()=>{
  const auth={authenticated:true,setupRequired:false,csrf:'test-only-csrf',user:{name:'QA',role:'Owner'},license:trial};
  const upgraded=authWithSnapshotLicense(auth,full);
  assert.equal(upgraded.license,full);assert.equal(upgraded.csrf,auth.csrf);assert.equal(upgraded.user,auth.user);assert.equal(upgraded.authenticated,true);
  const downgraded=authWithSnapshotLicense(upgraded,trial);assert.equal(downgraded.license,trial);assert.equal(downgraded.csrf,auth.csrf);assert.equal(downgraded.user,auth.user);assert.equal(auth.license,trial);
  assert.equal(authWithSnapshotLicense(downgraded,{...trial}),downgraded);
});
test('Malformed license snapshots cannot grant access or resurrect an expired login',()=>{
  const auth={authenticated:true,setupRequired:false,license:trial};
  for(const value of [null,{},'temporary-full',{...trial,fullAccess:true},{...full,allowedFeatures:'players'},{...full,lockedFeatures:[12]},{...full,keyActivationAvailable:undefined}]){assert.equal(snapshotLicense(value),undefined);assert.equal(authWithSnapshotLicense(auth,value),auth);}
  const loggedOut={authenticated:false,setupRequired:false};assert.equal(authWithSnapshotLicense(loggedOut,full),loggedOut);assert.equal(authWithSnapshotLicense(null,full),null);
});
