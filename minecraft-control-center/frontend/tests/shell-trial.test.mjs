import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createRequire} from 'node:module';
import {pathToFileURL} from 'node:url';

const require=createRequire(import.meta.url),ts=require('typescript');
const React=require('react'),{renderToStaticMarkup}=require('react-dom/server');
const data=source=>'data:text/javascript;base64,'+Buffer.from(source).toString('base64');
const file=name=>pathToFileURL(require.resolve(name)).href;
const transpile=(name,jsx=false)=>ts.transpileModule(fs.readFileSync(new URL('../src/'+name,import.meta.url),'utf8'),{compilerOptions:{module:ts.ModuleKind.ESNext,target:ts.ScriptTarget.ES2022,jsx:jsx?ts.JsxEmit.ReactJSX:undefined}}).outputText;
const policy=name=>data(transpile(name+'.ts'));
const license=policy('license-view'),modules=policy('module-view');
const navigation=data(transpile('navigation.ts').replaceAll("'./license-view.ts'",`'${license}'`).replaceAll("'./module-view.ts'",`'${modules}'`));
const context=data('export const useApp=()=>globalThis.shellFixture.app;');
const live=data('export const useLive=()=>globalThis.shellFixture.live;');
const ui=data(`import React from '${file('react')}';export const Icon=({name,size})=>React.createElement('svg',{'data-icon':name,width:size,height:size,'aria-hidden':true});export const Modal=()=>null;`);
const motion=data(`import React from '${file('react')}';export const motion={span:({layoutId,transition,...props})=>React.createElement('span',props)};`);
let source=transpile('Shell.tsx',true).replace("import './license-ui.css';",'');
for(const [from,to] of Object.entries({'react':file('react'),'react/jsx-runtime':file('react/jsx-runtime'),'motion/react':motion,'./context':context,'./live':live,'./ui':ui,'./roleLabel':policy('roleLabel'),'./navigation':navigation,'./license-view':license}))source=source.replaceAll(`'${from}'`,`'${to}'`).replaceAll(`"${from}"`,`"${to}"`);
const {default:Shell}=await import(data(source));
const trial={mode:'trial',fullAccess:false,allowedFeatures:['overview','console','chat','settings']};
function fixture(fullAccess){
  const licenseState={...trial,fullAccess};
  globalThis.shellFixture={app:{lang:'ru',t:ru=>ru,auth:{user:{name:'QA',role:'Owner'},license:licenseState},integrationIds:[],modules:fullAccess?{}:{playerSnapshots:false,entitySnapshots:false,oreMonitor:false,events:false,automations:false,map:false,timeline:false,catalog:false},navigate(){}},live:{status:'live',snapshot:{serverName:'Paper',minecraftVersion:'26.3'}}};
  return renderToStaticMarkup(React.createElement(Shell,{page:'dashboard',changeLang(){},profile(){},logout(){},children:null}));
}
function navButtons(html){return html.match(/<button[^>]*class="nav-item[^>]*>[\s\S]*?<\/button>/g)??[];}

test('Trial sidebar keeps complete Russian names and accessible restrictions separate from decorative fixed-size locks',()=>{
  const buttons=navButtons(fixture(false)),locked=buttons.filter(button=>button.includes('license-locked'));
  assert.equal(locked.length,10);
  for(const button of locked){
    assert.match(button,/disabled=""/);
    assert.match(button,/aria-label="[^"]+ — требуется полная версия"/);
    assert.match(button,/<span class="nav-label">[^<]+<\/span><svg class="nav-lock" width="14" height="14"/);
    assert.match(button,/<svg class="nav-lock"[^>]*aria-hidden="true"/);
    assert.doesNotMatch(button,/▣/);
  }
  assert(locked.some(button=>button.includes('<span class="nav-label">Резервные копии</span>')));
  assert(locked.some(button=>button.includes('<span class="nav-label">Журнал действий</span>')));
  for(const label of ['Обзор','Консоль','Чат','Настройки']){
    const button=buttons.find(button=>button.includes(`aria-label="${label}"`));
    assert(button,`Expected available section ${label}`);
    assert.doesNotMatch(button,/disabled=""|nav-lock/);
  }
});

test('Temporary full access removes trial locks and restores all visible navigation buttons',()=>{
  const buttons=navButtons(fixture(true));
  assert.equal(buttons.length,15);
  for(const button of buttons)assert.doesNotMatch(button,/disabled=""|license-locked|nav-lock|требуется полная версия/);
});
