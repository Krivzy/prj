import test from 'node:test';
import assert from 'node:assert/strict';
import {canSelectKernelProfile,canApplyKernelProfile,kernelProfileValue} from '../src/kernel-profile-view.ts';
const plan={planId:'20f82894-1e2a-4cd8-8555-93eb1a58d163',profile:'custom',expiresAt:1001,files:[],liveDistances:false,pendingRestart:false,customPreservesFiles:true};
test('Only active Owner can select supported profiles or confirm a fresh plan',()=>{
  const option={id:'optimized',available:true};assert.equal(canSelectKernelProfile(false,false,option),false);assert.equal(canSelectKernelProfile(true,true,option),false);assert.equal(canSelectKernelProfile(true,false,{...option,available:false}),false);assert.equal(canSelectKernelProfile(true,false,option),true);
  assert.equal(canApplyKernelProfile(false,plan,1000),false);assert.equal(canApplyKernelProfile(true,plan,1001),false);assert.equal(canApplyKernelProfile(true,plan,1000),true);assert.equal(canApplyKernelProfile(true,{...plan,expiresAt:Infinity},1000),false);
});
test('Custom plan must preserve all kernel files and cannot silently apply live distances',()=>{
  assert.equal(canApplyKernelProfile(true,{...plan,files:[{path:'server.properties'}]},1000),false);assert.equal(canApplyKernelProfile(true,{...plan,liveDistances:true},1000),false);assert.equal(canApplyKernelProfile(true,{...plan,customPreservesFiles:false},1000),false);
  assert.equal(canApplyKernelProfile(true,{...plan,profile:'optimized',files:[{path:'server.properties'}],liveDistances:true,customPreservesFiles:false},1000),true);
});
test('Typed review values never stringify arbitrary objects or missing settings as config content',()=>{
  assert.equal(kernelProfileValue(false),'false');assert.equal(kernelProfileValue(8),'8');assert.equal(kernelProfileValue('VANILLA'),'VANILLA');assert.equal(kernelProfileValue(null),'—');assert.equal(kernelProfileValue({unrelated:'value'}),'—');
});
test('Maximum optimization cannot confirm a plan that lowers distance files or invokes distance setters',()=>{
  const maximum={...plan,profile:'maximum-optimized',customPreservesFiles:false,preservesDistances:true,files:[{path:'spigot.yml',changes:[{key:'world-settings.default.nerf-spawner-mobs',before:false,after:true}]}]};
  assert.equal(canApplyKernelProfile(true,maximum,1000),true);assert.equal(canApplyKernelProfile(true,{...maximum,liveDistances:true},1000),false);assert.equal(canApplyKernelProfile(true,{...maximum,preservesDistances:false},1000),false);assert.equal(canApplyKernelProfile(true,{...maximum,files:[{path:'server.properties',changes:[]}]},1000),false);assert.equal(canApplyKernelProfile(true,{...maximum,files:[{path:'spigot.yml',changes:[{key:'world-settings.default.view-distance',after:4}]}]},1000),false);
});
test('Vanilla throughput preserves distances and never invokes live distance setters',()=>{
  const throughput={...plan,profile:'vanilla-throughput',customPreservesFiles:false,preservesDistances:true,files:[{path:'config/paper-world-defaults.yml',changes:[{key:'chunks.max-auto-save-chunks-per-tick',before:24,after:24}]}]};
  assert.equal(canApplyKernelProfile(true,throughput,1000),true);assert.equal(canApplyKernelProfile(true,{...throughput,liveDistances:true},1000),false);assert.equal(canApplyKernelProfile(true,{...throughput,preservesDistances:false},1000),false);
});
