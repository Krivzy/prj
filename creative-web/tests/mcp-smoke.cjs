'use strict';
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const {spawn} = require('node:child_process');
const readline = require('node:readline');
const root=path.resolve(__dirname,'..');
const output=path.join(root,'receipts');
const cli=process.argv[2];if(!cli)throw new Error('Pass the installed Playwright MCP cli.js path');
const report={checkedAt:new Date().toISOString(),package:'@playwright/mcp@0.0.83',browser:'msedge',isolated:true,headless:true,steps:[],screenshots:[]};
const fixture='<!doctype html><html lang="ru"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Skill suite fixture</title><style>body{font-family:system-ui;margin:0;padding:16px}main{max-width:48rem;margin:auto}input,button{font:inherit;max-width:100%;box-sizing:border-box}label{display:block}button{margin-top:12px;padding:10px}output{display:block;margin-top:12px}</style><main><h1>Skill suite fixture</h1><form><label for="name">Имя</label><input id="name" name="name" required><button>Проверить</button></form><output aria-live="polite"></output><a href="#done">К результату</a><p id="done">Результат</p></main><script>document.querySelector("form").addEventListener("submit",async e=>{e.preventDefault();const r=await fetch("/api/check");document.querySelector("output").textContent=(await r.json()).status+": "+document.querySelector("input").value;});</script></html>';
const server=http.createServer((req,res)=>{if(req.url==='/api/check'){res.writeHead(200,{'Content-Type':'application/json'});res.end('{"status":"ok"}');}else if(req.url==='/'){res.writeHead(200,{'Content-Type':'text/html;charset=utf-8'});res.end(fixture);}else{res.writeHead(404);res.end();}});
let child, lines, seq=0;const pending=new Map();
function request(method,params){return new Promise((resolve,reject)=>{const id=++seq;const timer=setTimeout(()=>{pending.delete(id);reject(new Error('RPC timeout: '+method));},30000);pending.set(id,{resolve,reject,timer});child.stdin.write(JSON.stringify({jsonrpc:'2.0',id,method,params})+'\n');});}
async function call(name,args){const r=await request('tools/call',{name,arguments:args});if(r.isError)throw new Error('MCP tool failed: '+name+' '+JSON.stringify(r.content).slice(0,250));report.steps.push(name);return r;}
(async()=>{
 try {
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
  const url='http://127.0.0.1:'+server.address().port+'/';
  child=spawn(process.execPath,[cli,'--browser','msedge','--headless','--isolated'],{cwd:path.resolve(root,'../..'),stdio:['pipe','pipe','pipe'],windowsHide:true});
  let stderrLines=0;child.stderr.on('data',b=>{stderrLines+=String(b).split('\n').filter(Boolean).length;});
  lines=readline.createInterface({input:child.stdout});
  lines.on('line',l=>{let m;try{m=JSON.parse(l);}catch{return;}const p=pending.get(m.id);if(p){pending.delete(m.id);clearTimeout(p.timer);m.error?p.reject(new Error('RPC error '+m.error.code)):p.resolve(m.result);}});
  const init=await request('initialize',{protocolVersion:'2025-03-26',capabilities:{},clientInfo:{name:'creative-web-local-smoke',version:'1.0.0'}});
  report.serverInfo=init.serverInfo;
  child.stdin.write(JSON.stringify({jsonrpc:'2.0',method:'notifications/initialized'})+'\n');
  const tools=await request('tools/list',{});report.tools=tools.tools.map(t=>t.name);
  const runCode=report.tools.includes('browser_run_code')?'browser_run_code':'browser_run_code_unsafe';
  for(const name of ['browser_navigate',runCode,'browser_close'])if(!report.tools.includes(name))throw new Error('Required tool absent: '+name);
  // This code is generated locally and runs only against our closed localhost fixture.
  await call('browser_navigate',{url});
  const code=`async (page) => {
   const errors=[];page.on('pageerror',e=>errors.push(e.name));
   await page.getByLabel('Имя').fill('Codex');
   await page.getByRole('button',{name:'Проверить'}).click();
   await page.waitForFunction(()=>document.querySelector('output').textContent==='ok: Codex');
   await page.getByRole('link',{name:'К результату'}).click();
   if(new URL(page.url()).hash!=='#done')throw new Error('Navigation failed');
   await page.emulateMedia({reducedMotion:'reduce'});
   const rows=[];
   for(const width of [390,768,1440]){
    await page.setViewportSize({width,height:900});
    const data=await page.evaluate(()=>({overflow:document.documentElement.scrollWidth>document.documentElement.clientWidth+1,reducedMotion:matchMedia('(prefers-reduced-motion: reduce)').matches}));
    if(data.overflow||!data.reducedMotion)throw new Error('Responsive/media failure');
    const filename=${JSON.stringify(output.replaceAll('\\','/'))}+'/mcp-fixture-'+width+'.png';
    await page.screenshot({path:filename,fullPage:true});rows.push({width,...data,screenshot:filename});
   }
   if(errors.length)throw new Error('JavaScript errors');
   return {form:'ok',api:'ok',navigation:'ok',pageErrors:errors.length,viewports:rows};
  }`;
  const result=await call(runCode,{code});
  const t=result.content.filter(c=>c.type==='text').map(c=>c.text).join('\n');
  report.fixtureResult=t;report.stderrLines=stderrLines;
  for(const width of [390,768,1440]){const f=path.join(output,'mcp-fixture-'+width+'.png');if(!fs.existsSync(f)||fs.statSync(f).size<1000)throw new Error('Screenshot missing');report.screenshots.push({width,bytes:fs.statSync(f).size});}
  await call('browser_close',{});report.ok=true;
 }catch(e){report.ok=false;report.failure=e.message;process.exitCode=1;}
 finally{for(const p of pending.values()){clearTimeout(p.timer);p.reject(new Error('Closing'));}pending.clear();if(child){child.stdin.end();child.kill();}if(lines)lines.close();server.close();fs.writeFileSync(path.join(output,'mcp-smoke.json'),JSON.stringify(report,null,2));console.log(JSON.stringify({...report,tools:report.tools?.length,fixtureResult:report.fixtureResult?.slice(0,1100)},null,2));}
})();
