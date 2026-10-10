import test from 'node:test';
import assert from 'node:assert/strict';
import {canExecute,mapPoint,mergeEntries} from '../src/policy.ts';

test('Viewer cannot dispatch any operation',()=>{for(const action of ['world.time','player.heal','chat.broadcast','console.command'])assert.equal(canExecute('Viewer',action),false);});
test('Moderator is limited to moderation whitelist',()=>{assert.equal(canExecute('Moderator','player.kick'),true);assert.equal(canExecute('Moderator','world.time'),false);assert.equal(canExecute('Moderator','inventory.set'),false);});
test('Admin cannot elevate privileges or execute console',()=>{assert.equal(canExecute('Admin','player.op'),false);assert.equal(canExecute('Admin','console.command'),false);assert.equal(canExecute('Admin','player.give'),true);assert.equal(canExecute('Admin','server.settings'),false);});
test('Owner can execute console while unknown roles deny',()=>{assert.equal(canExecute('Owner','console.command'),true);assert.equal(canExecute('unexpected','world.time'),false);});
test('Map coordinate selection respects tile origin and sample step',()=>{assert.deepEqual(mapPoint({x:-128,z:256,size:64,step:2},.5,.25),{x:-64,z:288});});
test('Map clicks at edges clamp to the actual tile',()=>{assert.deepEqual(mapPoint({x:0,z:0,size:64,step:2},1.1,-.1),{x:126,z:0});});
test('Reconnection combines real history with live events without duplicate messages',()=>{assert.deepEqual(mergeEntries([{time:1,message:'a'},{time:2,message:'b'}],[{time:2,message:'b'},{time:3,message:'c'}],2),[{time:2,message:'b'},{time:3,message:'c'}]);});
