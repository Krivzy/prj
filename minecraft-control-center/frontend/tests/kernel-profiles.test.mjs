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
