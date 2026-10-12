import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';
const require=createRequire(import.meta.url),ts=require('typescript');
const React=require('react'),{renderToStaticMarkup}=require('react-dom/server');
const data=text=>'data:text/javascript;base64,'+Buffer.from(text).toString('base64');
const file=name=>pathToFileURL(require.resolve(name)).href;
const compile=name=>ts.transpileModule(fs.readFileSync(new URL('../src/'+name,import.meta.url),'utf8'),{compilerOptions:{module:ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2022,jsx:ts.JsxEmit.ReactJSX}}).outputText;
const imports={'react':file('react'),'react/jsx-runtime':file('react/jsx-runtime'),'./context':data('export const useApp=()=>globalThis.actionButtonFixture;'),'./policy':data(compile('policy.ts')),'./license-view':data(compile('license-view.ts')),'./module-view':data(compile('module-view.ts')),'./assets':data('export const GameImage=()=>null,useAssetName=()=>"",useAssets=()=>null,assetName=()=>"";'),'./WorkbenchScene':data('export default ()=>null;'),'./Catalog':data('export const CatalogPicker=()=>null,CatalogInput=()=>null;')};
let source=compile('ui.tsx');for(const [from,to] of Object.entries(imports))source=source.replaceAll(`'${from}'`,`'${to}'`).replaceAll(`"${from}"`,`"${to}"`);
const {ActionButton}=await import(data(source));
function render(action,modules){globalThis.actionButtonFixture={auth:{user:{role:'Owner'},license:{fullAccess:true}},modules,action(){throw new Error('Rendering cannot execute an action');},notify(){}};return renderToStaticMarkup(React.createElement(ActionButton,{action,params:{}},'Test action'));}
test('Real action buttons disable player and inventory actions when player snapshots are off',()=>{
  for(const action of ['player.heal','players.heal_all','inventory.set']){assert.match(render(action,{playerSnapshots:false}),/disabled=""/);assert.doesNotMatch(render(action,{playerSnapshots:true}),/disabled=""/);}
});
test('Action button module guards preserve independent server controls and event cleanup',()=>{
  const off={playerSnapshots:false,events:false,entitySnapshots:false,chat:false,console:false};
  for(const action of ['mob.spawn','event.start','chat.broadcast','console.command'])assert.match(render(action,off),/disabled=""/);
  for(const action of ['world.save','server.settings','event.stop'])assert.doesNotMatch(render(action,off),/disabled=""/);
});
