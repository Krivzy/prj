import test from 'node:test';
import assert from 'node:assert/strict';
import {eventDefaults,eventPayload,updateLootRow} from '../src/EventModel.ts';
const treasure={id:'treasure',fields:[{key:'loot',default:[{material:'DIAMOND',amount:1,weight:1}]}]};
test('Switching adventures creates independent nested reward settings',()=>{const a=eventDefaults(treasure,[{key:'count',default:12}]),b=eventDefaults(treasure,[]);a.loot[0].amount=6;assert.equal(b.loot[0].amount,1);assert.equal(treasure.fields[0].default[0].amount,1);});
test('Event payload excludes stale settings from a previously selected adventure',()=>{assert.deepEqual(eventPayload({id:'fireworks',fields:[{key:'color'}]},{x:1,y:80,z:2,color:'81e9a1',loot:[{material:'TNT'}],mob:'CREEPER'},'world'),{preset:'fireworks',world:'world',x:1,y:80,z:2,color:'81e9a1'});});
test('Resource editing changes one row without altering amounts or siblings',()=>{const rows=[{material:'DIAMOND',amount:3,weight:2},{material:'IRON_INGOT',amount:16,weight:5}];const next=updateLootRow(rows,0,{material:'EMERALD'});assert.equal(next[0].amount,3);assert.equal(next[1],rows[1]);assert.equal(rows[0].material,'DIAMOND');});
