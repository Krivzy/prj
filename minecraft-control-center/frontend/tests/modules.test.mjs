import test from 'node:test';
import assert from 'node:assert/strict';
import {moduleEnabled,pageEnabled,requestedTopics,topicEnabled,imageMapWork} from '../src/module-view.ts';
import {availablePages} from '../src/navigation.ts';
test('Disabled subsystems remove their pages and live subscriptions while keeping settings and core safety',()=>{
  const off={playerSnapshots:false,entitySnapshots:false,events:false,oreMonitor:false,integrations:false,chat:false,console:false,automations:false,timeline:false,map:false};
  const pages=availablePages('Owner',['luckperms','advancedban'],off).map(page=>page.id);assert.deepEqual(pages,['dashboard','worlds','backups','plugins','settings']);
  assert.deepEqual(requestedTopics('owner',off),['state','world','punishments']);assert.equal(topicEnabled(off,'inventory'),false);assert.equal(pageEnabled(off,'players'),false);assert.equal(pageEnabled(off,'settings'),true);
});
test('Legacy settings default missing flags on and roles still limit discovery subscriptions',()=>{
  assert.equal(moduleEnabled({},'oreMonitor'),true);assert.equal(pageEnabled({},'map'),true);assert.equal(requestedTopics('viewer',{}).includes('console'),false);assert.equal(requestedTopics('viewer',{}).includes('punishments'),false);assert.equal(availablePages('Viewer',[],{}).some(page=>page.id==='ores'),false);
});
test('Player snapshots control map markers independently from the saved basemap',()=>{
  assert.deepEqual(imageMapWork({map:true,playerSnapshots:false}),{base:true,players:false});
  assert.deepEqual(imageMapWork({map:false,playerSnapshots:true}),{base:false,players:false});
  assert.deepEqual(imageMapWork({}),{base:true,players:true});
});
