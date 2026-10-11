import test from 'node:test';
import assert from 'node:assert/strict';
import {mergeWorldDetails} from '../src/world-detail-view.ts';

const detail={uuid:'world-1',name:'world',environment:'NORMAL',time:100,players:0,gamerules:{'minecraft:keep_inventory':false,'minecraft:random_tick_speed':3}};
const summary={uuid:'world-1',name:'world',environment:'NORMAL',time:200,players:2};
test('Live world counters retain detailed typed gamerules',()=>{
  const [result]=mergeWorldDetails([summary],[detail]);
  assert.equal(result.time,200);assert.equal(result.players,2);assert.deepEqual(result.gamerules,detail.gamerules);
});
test('Recreated or renamed world cannot inherit stale gamerules',()=>{
  for(const current of [{...summary,uuid:'world-2'},{...summary,name:'renamed'},{...summary,environment:'NETHER'}])
    assert.equal(mergeWorldDetails([current],[detail])[0].gamerules,undefined);
});
test('Live removals are authoritative; detailed records support pre-snapshot loading',()=>{
  assert.deepEqual(mergeWorldDetails([],[detail]),[]);
  assert.deepEqual(mergeWorldDetails(undefined,[detail]),[detail]);
  assert.deepEqual(mergeWorldDetails(undefined,undefined),[]);
});
test('New detailed rule readback replaces the displayed value without mutating input',()=>{
  const next={...detail,gamerules:{'minecraft:keep_inventory':true}};
  const before=JSON.stringify({detail,summary});const result=mergeWorldDetails([summary],[next]);
  assert.equal(result[0].gamerules['minecraft:keep_inventory'],true);
  assert.equal(JSON.stringify({detail,summary}),before);assert.notEqual(result[0],summary);
});
