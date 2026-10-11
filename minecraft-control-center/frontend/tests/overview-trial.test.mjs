import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
const require=createRequire(import.meta.url),ts=require('typescript');
const React=require('react'),{renderToStaticMarkup}=require('react-dom/server');
const data=source=>'data:text/javascript;base64,'+Buffer.from(source).toString('base64');
const file=name=>pathToFileURL(require.resolve(name)).href;
const policy=name=>data(ts.transpileModule(fs.readFileSync(new URL('../src/'+name+'.ts',import.meta.url),'utf8'),{compilerOptions:{module:ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2022}}).outputText);
const context=data(`export const useApp=()=>globalThis.overviewFixture.app;export const useResource=url=>{globalThis.overviewFixture.requests.push(url);return {data:[],reload(){}}};export const duration=()=>'';export const time=()=>'';export const formatNumber=value=>typeof value==='number'?String(value):'—';`);
const live=data('export const useLive=()=>globalThis.overviewFixture.live;');
const ui=data(`import React from '${file('react')}';export const Panel=({children,...props})=>React.createElement('section',props,children);export const Button=({children,...props})=>React.createElement('button',props,children);export const ActionButton=({children,disabled})=>React.createElement('button',{disabled},children);export const Empty=({title,children})=>React.createElement('div',null,title,children);export const Avatar=()=>null;export const Icon=()=>null;export const Loading=()=>React.createElement('div',null,'Loading');`);
const scene=data('export default ()=>null;');
let source=ts.transpileModule(fs.readFileSync(new URL('../src/Overview.tsx',import.meta.url),'utf8'),{compilerOptions:{module:ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2022,jsx:ts.JsxEmit.ReactJSX}}).outputText;
for(const [from,to] of Object.entries({'react':file('react'),'react/jsx-runtime':file('react/jsx-runtime'),'./context':context,'./live':live,'./ui':ui,'./ServerRestart':data('export const RestartControl=()=>null;'),'./module-view':policy('module-view'),'./license-view':policy('license-view'),'./OverworldScene':scene}))source=source.replaceAll(`'${from}'`,`'${to}'`).replaceAll(`"${from}"`,`"${to}"`);
const {default:Overview}=await import(data(source));
const snapshot={timestamp:100,serverName:'Paper',minecraftVersion:'26.3',online:1,maxPlayers:20,tps:[20],mspt:2,heapUsed:256*1024**2,heapMax:1024**3,cpu:5,uptimeSeconds:100,worldCount:3};
function fixture(fullAccess,extra={}){
 globalThis.overviewFixture={requests:[],app:{t:ru=>ru,auth:{user:{name:'QA'},license:{mode:fullAccess?'temporary-full':'trial',fullAccess}},modules:fullAccess?{}:{playerSnapshots:false,timeline:false,map:false,events:false,automations:false},owner:false,administrator:true,navigate(){}},live:{snapshot:{...snapshot,...extra},players:[],timeline:[],updated:100,status:'live'}};
 return renderToStaticMarkup(React.createElement(Overview));
}
test('Overview renders the real pruned trial contract with no world/event arrays and no premium history request',()=>{
 const html=fixture(false);
 assert.match(html,/Твой сервер\. Твои правила\./);assert.match(html,/3 мира подключено/);
 assert.match(html,/Управление миром доступно в полной версии/);assert.match(html,/Графики истории доступны в полной версии/);
 assert.match(html,/<select[^>]*disabled=""/);assert.deepEqual(globalThis.overviewFixture.requests,[null]);
 assert.equal(Object.hasOwn(globalThis.overviewFixture.live.snapshot,'worlds'),false);
});
test('Full overview still renders actual world controls and requests its history',()=>{
 const html=fixture(true,{worlds:[{name:'world',uuid:'test-world',time:1000,weather:'clear',difficulty:'NORMAL'}],activeEvents:[]});
 assert.match(html,/<option value="world" selected="">world<\/option>/);
 assert.doesNotMatch(html,/Управление миром доступно в полной версии/);
 assert.deepEqual(globalThis.overviewFixture.requests,['/api/metrics?range=5m']);
});
