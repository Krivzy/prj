import test from 'node:test';
import assert from 'node:assert/strict';
import {getSetupSession} from '../src/setup-api.ts';
import {setAuth} from '../src/api.ts';
test('StrictMode duplicate setup mount shares one single-use claim and reload resumes only through cookie status',async()=>{
  const previousWindow=globalThis.window,previousFetch=globalThis.fetch;let resolve;const calls=[];
  globalThis.window={setTimeout,clearTimeout,dispatchEvent:()=>true};globalThis.fetch=(path,options)=>{calls.push({path,options});return new Promise(done=>{resolve=done;});};
  try{const token='synthetic-test-capability-'.padEnd(43,'x'),first=getSetupSession(token),second=getSetupSession(token);assert.equal(first,second);assert.equal(calls.length,1);assert.equal(calls[0].path,'/api/setup/claim');assert.equal(calls[0].options.method,'POST');assert.equal(calls[0].options.credentials,'same-origin');assert.deepEqual(JSON.parse(calls[0].options.body),{token});
    resolve(new Response(JSON.stringify({required:true,player:{name:'SyntheticOwner',uuid:'fixture'},csrf:'fixture-csrf'}),{headers:{'Content-Type':'application/json'}}));await first;
    const resumed=getSetupSession(null);assert.equal(calls.length,2);assert.equal(calls[1].path,'/api/setup/status');assert.equal(calls[1].options.body,undefined);resolve(new Response(JSON.stringify({required:true,linkRequired:true}),{headers:{'Content-Type':'application/json'}}));await resumed;
  }finally{globalThis.window=previousWindow;globalThis.fetch=previousFetch;setAuth({authenticated:false,setupRequired:false});}
});
