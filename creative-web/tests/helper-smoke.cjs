'use strict';
const fs=require('node:fs');const path=require('node:path');const http=require('node:http');const {spawn}=require('node:child_process');
const root=path.resolve(__dirname,'..');const helper=path.join(root,'skills/playwright-browser-automation-expert/scripts/inspect-local.cjs');
const project=process.argv[2];if(!project)throw new Error('Pass a project folder with Playwright dependency');
const server=http.createServer((req,res)=>{
 if(req.url==='/favicon.ico'){res.writeHead(204);res.end();return;}
 if(req.url==='/missing'){res.writeHead(404);res.end();return;}
 const broken=req.url==='/broken';
 const injection=broken?'<script>console.error("fixture-error");fetch("/missing");throw new Error("fixture-pageerror");</script>':'';
 res.writeHead(200,{'Content-Type':'text/html;charset=utf-8'});res.end('<!doctype html><html><meta name="viewport" content="width=device-width,initial-scale=1"><style>body{margin:0;padding:12px;font-family:system-ui}</style><h1>QA helper fixture</h1>'+injection+'</html>');
});
function run(url,folder){return new Promise((resolve,reject)=>{const c=spawn(process.execPath,[helper,project,url,folder,'msedge'],{windowsHide:true,stdio:['ignore','pipe','pipe']});let stdout='';c.stdout.on('data',b=>stdout+=b);c.stderr.on('data',()=>{});c.on('error',reject);c.on('close',code=>resolve({code,stdout}));});}
(async()=>{const results={};try{
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const base='http://127.0.0.1:'+server.address().port;
 for(const route of ['good','broken']){const folder=path.join(root,'receipts','helper-'+route);const r=await run(base+'/'+route,folder);const report=JSON.parse(fs.readFileSync(path.join(folder,'report.json'),'utf8'));results[route]={exitCode:r.code,passed:report.passed,viewports:report.viewports.map(v=>({width:v.viewport.width,passed:v.passed,mainStatus:v.mainStatus,pageErrors:v.pageErrorCount,consoleErrors:v.consoleErrorCount,httpErrors:v.httpErrorCount,overflow:v.overflowPixels}))};}
 const invalid=await run('https://example.com/',path.join(root,'receipts','helper-invalid'));results.externalOriginRejected=invalid.code!==0;
 results.ok=results.good.exitCode===0&&results.good.passed&&results.broken.exitCode===1&&!results.broken.passed&&results.broken.viewports.every(v=>v.pageErrors>0&&v.consoleErrors>0&&v.httpErrors>0)&&results.externalOriginRejected;
 if(!results.ok)process.exitCode=1;
}catch(e){results.ok=false;results.error=e.name;process.exitCode=1;}finally{server.close();fs.writeFileSync(path.join(root,'receipts','helper-smoke.json'),JSON.stringify(results,null,2));console.log(JSON.stringify(results,null,2));}})();
