import test from 'node:test';
import assert from 'node:assert/strict';
import {parseAcceptedRestart,restartHasCompleted} from '../src/restart-view.ts';
const prior='37c5486b-8153-4703-a033-e7a6673ec63d';
const next='3ac16751-f55d-405e-aa2f-05ae9ce93c96';
const receipt={accepted:true,requestId:next,bootId:prior,startedAt:1_000_000};

test('A restart receipt expires at ten minutes and rejects future or invalid browser storage data',()=>{
  assert.deepEqual(parseAcceptedRestart(receipt,1_000_000),receipt);
  assert.deepEqual(parseAcceptedRestart(receipt,1_599_999),receipt);
  for(const now of [999_999,1_600_000,Infinity,NaN])assert.equal(parseAcceptedRestart(receipt,now),null);
  for(const value of [null,[],{}, {...receipt,accepted:false},{...receipt,startedAt:NaN},{...receipt,startedAt:1_000_000.5},{...receipt,requestId:'-'.repeat(36)},{...receipt,bootId:'-'.repeat(36)}])assert.equal(parseAcceptedRestart(value,1_000_000),null);
});
test('Receipt parsing returns only the documented screen fields and does not mutate stored input',()=>{
  const input={...receipt,extra:'unused'};const accepted=parseAcceptedRestart(input,1_000_001);
  assert.deepEqual(accepted,receipt);assert.equal(input.extra,'unused');accepted.startedAt=2;assert.equal(input.startedAt,1_000_000);
});
test('Health from the old ready process cannot complete a restart; a genuinely different ready boot can',()=>{
  assert.equal(restartHasCompleted({ready:true,bootId:prior},prior),false);
  assert.equal(restartHasCompleted({ready:true,bootId:prior.toUpperCase()},prior),false);
  assert.equal(restartHasCompleted({ready:false,bootId:next},prior),false);
  for(const value of [null,{}, {ready:'true',bootId:next},{ready:true,bootId:''},{ready:true,bootId:'-'.repeat(36)}])assert.equal(restartHasCompleted(value,prior),false);
  assert.equal(restartHasCompleted({ready:true,bootId:next},prior),true);
  assert.equal(restartHasCompleted({ready:true,bootId:next},'invalid'),false);
});
