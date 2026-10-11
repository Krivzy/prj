import test from 'node:test';
import assert from 'node:assert/strict';
import {filterOrePlayers,oreCount,oreTotal} from '../src/ore-monitor-view.ts';
const player=(id,name,severity,recent,hour=recent,all=hour)=>({id,name,severity,world:'World',online:true,total:hour,allTimeTotal:all,lastMinedAt:1000,flaggedOres:[],counts:{300:{diamond:recent},1800:{diamond:hour},3600:{diamond:hour},ALL_TIME:{diamond:all}}});
test('All-time and hourly totals never recolour the five-minute status',()=>{
  const historical=player('a','Miner','GREEN',0,200,5000);const flagged=player('b','Vein','RED',64,64,64);
  assert.equal(oreTotal(historical,'ALL_TIME'),5000);assert.equal(oreCount(historical,300,'diamond'),0);
  assert.equal(filterOrePlayers([historical,flagged],'','RED','ALL','ALL_TIME')[0].id,'b');assert.equal(historical.severity,'GREEN');
});
test('Recent-window filters preserve source snapshot, search world/name and rank status then displayed blocks',()=>{
  const players=[player('a','First','GREEN',12,100),player('b','Second','YELLOW',30,80),player('c','Third','YELLOW',40,70)];
  assert.deepEqual(filterOrePlayers(players,' WORLD ','ALL','ALL',300).map(row=>row.id),['c','b','a']);
  assert.deepEqual(players.map(row=>row.id),['a','b','c']);assert.deepEqual(filterOrePlayers(players,'first','GREEN','diamond',3600).map(row=>row.id),['a']);
});
test('Incomplete or malformed counts remain finite nonnegative display values',()=>{
  const value=player('a','Miner','GREEN',7);value.counts.ALL_TIME={diamond:7,ancient_debris:-1,iron:Infinity};
  assert.equal(oreCount(value,300,'copper'),0);assert.equal(oreTotal(value,'ALL_TIME'),7);
});
test('Displayed total combines all eleven ore block families without a Fortune multiplier',()=>{
  const value=player('a','Miner','GREEN',0);value.counts.ALL_TIME={diamond:1,gold:2,redstone:3,coal:4,lapis:5,emerald:6,iron:7,copper:8,quartz:9,nether_gold:10,ancient_debris:11};
  assert.equal(oreTotal(value,'ALL_TIME'),66);assert.equal(oreCount(value,'ALL_TIME','lapis'),5);
});