import test from 'node:test';
import assert from 'node:assert/strict';
import {ApiError,execute,postTracked,request,setAuth} from '../src/api.ts';

function sandbox(fetchImplementation){
  const previousFetch=globalThis.fetch,previousWindow=globalThis.window;
  const events=[];
  globalThis.window={setTimeout:(callback,ms)=>setTimeout(callback,ms===1000?0:ms),clearTimeout,dispatchEvent:event=>{events.push(event.type);return true;}};
  globalThis.fetch=fetchImplementation;
  return {events,restore(){globalThis.fetch=previousFetch;globalThis.window=previousWindow;setAuth({authenticated:false,setupRequired:false});}};
}
const response=(body,status=200)=>new Response(JSON.stringify(body),{status,headers:{'Content-Type':'application/json'}});

test('Raw JAR upload keeps its MIME type and includes session CSRF',async()=>{
  let captured;
  const env=sandbox(async(path,options)=>{captured={path,options};return response({ok:true});});
  try{setAuth({authenticated:true,setupRequired:false,csrf:'test-only-csrf'});const blob=new Blob(['fixture']);await request('/api/plugins/stage',{method:'POST',body:blob,headers:{'Content-Type':'application/java-archive'}});assert.equal(captured.options.headers['Content-Type'],'application/java-archive');assert.equal(captured.options.headers['X-MCC-CSRF'],'test-only-csrf');assert.equal(captured.options.credentials,'same-origin');assert.equal(captured.options.body,blob);}finally{env.restore();}
});
test('Expired session dispatches expiration and does not report success',async()=>{
  const env=sandbox(async()=>response({message:'Sign in first'},401));
  try{await assert.rejects(request('/api/state'),error=>error instanceof ApiError&&error.status===401);assert.deepEqual(env.events,['mcc-session-expired']);}finally{env.restore();}
});
test('Pending action polls original ID without repeating the POST',async()=>{
  const calls=[];let id;
  const env=sandbox(async(path,options)=>{calls.push({path,method:options.method??'GET'});if(options.method==='POST'){id=JSON.parse(options.body).id;return response({ok:false,pending:true,id},202);}return response({ok:true,result:{applied:true},sequence:12});});
  try{const result=await execute('world.time',{world:'world',time:1000});assert.equal(result.ok,true);assert.equal(calls.filter(x=>x.method==='POST').length,1);assert.equal(calls[1].path,`/api/actions/${id}`);assert.deepEqual(env.events,['mcc-action-pending']);}finally{env.restore();}
});
test('HTTP 200 action with ok false rejects instead of showing success',async()=>{
  const env=sandbox(async()=>response({ok:false,result:'Action rejected',sequence:0}));
  try{await assert.rejects(execute('player.heal',{uuid:'fixture'}),/Action rejected/);}finally{env.restore();}
});
test('Tracked moderation polls a raw result and preserves confirmation without a second POST',async()=>{
  const calls=[];let id;
  const env=sandbox(async(path,options)=>{calls.push({path,options});if(options.method==='POST'){const body=JSON.parse(options.body);id=body.id;assert.equal(body.confirmed,true);assert.equal(body.durationSeconds,0);return response({id,pending:true},202);}return response({id:7,type:'kick',status:'completed'});});
  try{const result=await postTracked('/api/punishments',{player:'fixture',type:'kick',reason:'fixture',durationSeconds:0,confirmed:true});assert.equal(result.id,7);assert.equal(result.status,'completed');assert.equal(calls.length,2);assert.equal(calls[1].path,`/api/actions/${id}`);}finally{env.restore();}
});
test('Tracked operations propagate authorization failure and do not poll',async()=>{
  let calls=0;const env=sandbox(async()=>{calls++;return response({message:'Owner required'},403);});
  try{await assert.rejects(postTracked('/api/build/actions',{confirmed:true}),error=>error instanceof ApiError&&error.status===403);assert.equal(calls,1);}finally{env.restore();}
});
test('Map caller signal keeps the request timeout and does not abort the caller',async()=>{
  let timeout,usedSignal,cleared=false;
  const env=sandbox((_path,{signal})=>{usedSignal=signal;return new Promise((_resolve,reject)=>signal.addEventListener('abort',()=>reject(signal.reason),{once:true}));});
  globalThis.window.setTimeout=callback=>{timeout=callback;return 1;};
  globalThis.window.clearTimeout=()=>{cleared=true;};
  const caller=new AbortController();
  try{const pending=request('/api/map/images',{signal:caller.signal});timeout();await assert.rejects(pending,/Request timed out/);assert.equal(usedSignal.aborted,true);assert.equal(caller.signal.aborted,false);assert.equal(cleared,true);}finally{env.restore();}
});
test('Caller cancellation reaches fetch even with a custom abort reason',async()=>{
  let usedSignal;
  const env=sandbox((_path,{signal})=>{usedSignal=signal;return new Promise((_resolve,reject)=>signal.addEventListener('abort',()=>reject(signal.reason),{once:true}));});
  const caller=new AbortController();
  try{const pending=request('/api/map/players',{signal:caller.signal});caller.abort(new Error('Caller cancelled'));await assert.rejects(pending,/Request timed out/);assert.equal(usedSignal.aborted,true);}finally{env.restore();}
});
test('An already cancelled caller reaches fetch as cancelled and successful requests remove its listener',async()=>{
  const cancelled=new AbortController();cancelled.abort();let usedSignal;
  const env=sandbox(async(_path,{signal})=>{usedSignal=signal;if(signal.aborted)throw signal.reason;return response({ok:true});});
  try{await assert.rejects(request('/api/map/images',{signal:cancelled.signal}),/Request timed out/);assert.equal(usedSignal.aborted,true);
    const caller=new AbortController(),original=caller.signal.removeEventListener.bind(caller.signal);let removed=0;
    caller.signal.removeEventListener=(...args)=>{if(args[0]==='abort')removed++;original(...args);};
    assert.deepEqual(await request('/api/state',{signal:caller.signal}),{ok:true});assert.equal(removed,1);caller.abort();assert.equal(usedSignal.aborted,false);
  }finally{env.restore();}
});
