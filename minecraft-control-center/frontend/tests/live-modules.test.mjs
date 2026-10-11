import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
const require=createRequire(import.meta.url),ts=require('typescript');
function source(name){return ts.transpileModule(fs.readFileSync(new URL('../src/'+name+'.ts',import.meta.url),'utf8'),{compilerOptions:{module:ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2022}}).outputText;}
function url(text){return 'data:text/javascript;base64,'+Buffer.from(text).toString('base64');}
const liveSource=source('live').replace("from 'react'",`from '${pathToFileURL(require.resolve('react')).href}'`).replace("from './api'",`from '${url(source('api'))}'`).replace("from './module-view'",`from '${url(source('module-view'))}'`).replace("from './license-view'",`from '${url(source('license-view'))}'`).replace("from './policy'",`from '${url(source('policy'))}'`);
const {resetLive,refreshLive,getLiveState,connectLive}=await import(url(liveSource));
const turn=()=>new Promise(r=>setImmediate(r)),deferred=()=>{let resolve;const promise=new Promise(r=>resolve=r);return {promise,resolve};},response=value=>new Response(JSON.stringify(value),{headers:{'Content-Type':'application/json'}});
const snapshot=(at,flags)=>({timestamp:new Date(at).toISOString(),modules:flags,worlds:[]});
function browser(){const events=new EventTarget();globalThis.window={setTimeout,clearTimeout,dispatchEvent:e=>events.dispatchEvent(e)};globalThis.location={protocol:'http:',host:'127.0.0.1:18085'};if(!globalThis.CustomEvent)globalThis.CustomEvent=class extends Event{constructor(name,{detail}={}){super(name);this.detail=detail;}};}
browser();
test('Delayed players cannot restore an older ON snapshot after a newer OFF state',async()=>{
  resetLive();const oldPlayers=deferred(),requested=deferred();let n=0;
  globalThis.fetch=async path=>path==='/api/state'?response(snapshot(++n===1?100:200,{playerSnapshots:n===1})):((requested.resolve()),oldPlayers.promise);
  const old=refreshLive();await requested.promise;await refreshLive();assert.equal(getLiveState().snapshot.modules.playerSnapshots,false);
  oldPlayers.resolve(response([{uuid:'old-player'}]));await old;
  assert.equal(getLiveState().snapshot.modules.playerSnapshots,false);assert.deepEqual(getLiveState().players,[]);
});
test('An unaccepted stale HTTP snapshot cannot scrub newer module flags or start player work',async()=>{
  resetLive();const stale=deferred();let n=0,players=0;
  globalThis.fetch=path=>{if(path==='/api/state')return ++n===1?stale.promise:Promise.resolve(response(snapshot(300,{playerSnapshots:false,chat:false})));players++;return Promise.resolve(response([]));};
  const old=refreshLive();await refreshLive();stale.resolve(response(snapshot(100,{playerSnapshots:true,chat:true})));await old;
  assert.equal(players,0);assert.equal(getLiveState().snapshot.modules.chat,false);assert.equal(getLiveState().snapshot.modules.playerSnapshots,false);
});
test('Chat history requested before OFF to ON cannot repopulate the cleared cache',async()=>{
  resetLive();const history=deferred(),started=deferred();let socket;
  globalThis.WebSocket=class {static OPEN=1;readyState=1;constructor(){socket=this;}send(){}close(){this.readyState=3;}};
  globalThis.fetch=async path=>path==='/api/state'?response(snapshot(100,{chat:true,playerSnapshots:true})):path==='/api/players'?response([]):((started.resolve()),history.promise);
  const close=connectLive('moderator');try{socket.onopen();await started.promise;
    socket.onmessage({data:JSON.stringify({type:'event',topic:'state',sequence:1,data:snapshot(200,{chat:false,playerSnapshots:true})})});
    socket.onmessage({data:JSON.stringify({type:'event',topic:'state',sequence:2,data:snapshot(300,{chat:true,playerSnapshots:true})})});
    history.resolve(response([{message:'old chat',timestamp:100}]));await turn();await turn();
    assert.equal(getLiveState().snapshot.modules.chat,true);assert.deepEqual(getLiveState().chat,[]);
  }finally{close();resetLive();}
});
test('Trial connection subscribes only overview/chat/console and never fetches or accepts player data',async()=>{
  resetLive();let socket;const calls=[],messages=[];
  globalThis.WebSocket=class {static OPEN=1;readyState=1;constructor(){socket=this;}send(value){messages.push(JSON.parse(value));}close(){this.readyState=3;}};
  globalThis.fetch=async path=>{calls.push(path);return response(path==='/api/state'?snapshot(100,{playerSnapshots:true,chat:true,console:true}):[]);};
  const close=connectLive('owner',{mode:'trial',fullAccess:false,allowedFeatures:['overview','console','chat','settings']});
  try{socket.onopen();await turn();await turn();assert.equal(calls.includes('/api/players'),false);assert.equal(calls.includes('/api/chat'),true);assert.equal(calls.includes('/api/console'),true);for(const message of messages.filter(x=>x.type==='subscribe'))assert.deepEqual(message.topics,['state','chat','console']);
    socket.onmessage({data:JSON.stringify({type:'event',topic:'players',sequence:1,data:[{name:'must-not-appear'}]})});assert.deepEqual(getLiveState().players,[]);
  }finally{close();resetLive();}
});
