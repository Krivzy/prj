import test from 'node:test';
import assert from 'node:assert/strict';
import {filterOrePlayers,oreCount,oreTotal} from '../src/ore-monitor-view.ts';
const player=(id,name,severity,diamond,short=0)=>({id,name,severity,world:'World',online:true,total:diamond,lastMinedAt:1000,flaggedOres:[],counts:{60:{diamond:short},300:{diamond},600:{diamond}}});
test('Changing the count window cannot turn a ten-minute pace flag into an X-Ray verdict',()=>{
  const flagged=player('a','Miner','RED',160,0);const ordinary=player('b','Vein','GREEN',12,12);
  assert.equal(oreCount(flagged,60,'diamond'),0);assert.equal(filterOrePlayers([ordinary,flagged],'','RED','ALL',60)[0].id,'a');
  assert.equal(filterOrePlayers([ordinary,flagged],'','ALL','diamond',60).length,1);assert.equal(filterOrePlayers([ordinary,flagged],'','ALL','diamond',60)[0].id,'b');
  assert.equal(flagged.severity,'RED');
});
test('Player filters preserve source snapshot, match world/name and sort by status then selected-window blocks',()=>{
  const players=[player('a','First','GREEN',12,12),player('b','Second','YELLOW',70,0),player('c','Third','YELLOW',80,10)];
  assert.deepEqual(filterOrePlayers(players,' WORLD ','ALL','ALL',60).map(row=>row.id),['c','b','a']);
  assert.deepEqual(players.map(row=>row.id),['a','b','c']);assert.deepEqual(filterOrePlayers(players,'first','GREEN','diamond',600).map(row=>row.id),['a']);
});
test('Incomplete or malformed counts remain bounded nonnegative display values',()=>{
  const value=player('a','Miner','GREEN',7);value.counts[600]={diamond:7,ancient_debris:-1,iron:Infinity};
  assert.equal(oreCount(value,300,'copper'),0);assert.equal(oreTotal(value,600),7);
});
