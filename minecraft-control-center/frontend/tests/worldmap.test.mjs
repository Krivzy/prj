import test from 'node:test';
import assert from 'node:assert/strict';
import {terrainCell,isClick,textureTint,mergeTerrainTiles,previewReadCenter} from '../src/worldmap-geometry.ts';

test('Actual sampled layer indexing handles negative chunks and never invents lower terrain',()=>{
 const heights=Array(256).fill(null),voxels=Array(3072).fill(null),stone={material:'STONE',blockData:'minecraft:stone',color:'#fff'},grass={material:'GRASS_BLOCK',blockData:'minecraft:grass_block[snowy=false]',color:'#fff'};
 heights[255]=71;voxels[255]=1;voxels[255+256]=0;
 const tiles=new Map([['-2,-1',{cx:-2,cz:-1,status:'ready',depth:12,heights,voxels,palette:[stone,grass]}]]);
 assert.equal(terrainCell(tiles,{x:-17,y:71,z:-1}),grass);
 assert.equal(terrainCell(tiles,{x:-17,y:70,z:-1}),stone);
 assert.equal(terrainCell(tiles,{x:-17,y:72,z:-1}),null);
 assert.equal(terrainCell(tiles,{x:-17,y:60,z:-1}),null);
 assert.equal(terrainCell(tiles,{x:-17,y:59,z:-1}),undefined);
 assert.equal(terrainCell(tiles,{x:-16,y:71,z:-1}),undefined);
});
test('Camera orbit/pan movement above five pixels never becomes a placement click',()=>{
 assert.equal(isClick({x:100,y:100},{x:103,y:104}),true);
 assert.equal(isClick({x:100,y:100},{x:106,y:100}),false);
 assert.equal(isClick({x:100,y:100},{x:160,y:145}),false);
});

test('Only grayscale classic foliage and grass tops receive approximate native tint',()=>{
 assert.equal(textureTint('BIRCH_LEAVES','up','/game-assets/textures/block/birch_leaves.png','#888'),'#80A755');
 assert.equal(textureTint('SPRUCE_LEAVES','up','/spruce_leaves.png','#888'),'#619961');
 assert.equal(textureTint('GRASS_BLOCK','up','/game-assets/textures/block/grass_block_top.png','#888'),'#79A84A');
 assert.equal(textureTint('GRASS_BLOCK','north','/game-assets/textures/block/grass_block_side.png','#888'),'#ffffff');
 assert.equal(textureTint('STONE','up','/stone.png','#888'),'#ffffff');
 assert.equal(textureTint('CHERRY_LEAVES','up','/cherry_leaves.png','#888'),'#ffffff');
 assert.equal(textureTint('AZALEA_LEAVES','up','/azalea_leaves.png','#888'),'#ffffff');
 assert.equal(textureTint('OAK_LEAVES','up',undefined,'#888'),'#888');
});

test('A refresh keeps old complete terrain until a new real snapshot arrives, but never hides missing/PROTO',()=>{
 const original={cx:0,cz:-1,status:'ready',sampledAt:10,heights:[64],voxels:[0],palette:[{material:'STONE',blockData:'minecraft:stone',color:'#888'}]};
 for(const status of ['loading','processing','deferred','error']){
  const tiles=mergeTerrainTiles('world',[original],'world',[{cx:0,cz:-1,status}]);
  assert.equal(tiles[0].voxels,original.voxels);assert.equal(tiles[0].refreshing,true);assert.equal(tiles[0].refreshStatus,status);
 }
 const fresh={...original,sampledAt:11,voxels:[1]};assert.equal(mergeTerrainTiles('world',[original],'world',[fresh])[0],fresh);
 const missing={cx:0,cz:-1,status:'missing'};assert.equal(mergeTerrainTiles('world',[original],'world',[missing])[0],missing);
 const loading={cx:0,cz:-1,status:'loading'};assert.equal(mergeTerrainTiles('old',[original],'new',[loading])[0],loading);
 assert.equal(mergeTerrainTiles('world',[original],'world',[{cx:1,cz:-1,status:'loading'}])[0].voxels,undefined);
});
test('Native grayscale animated water receives blue tint, unrelated textures preserve original color',()=>{
 assert.equal(textureTint('WATER','up','/game-assets/textures/block/water_still.png','#888'),'#3F76E4');
 assert.equal(textureTint('WATER','north','/game-assets/textures/block/water_flow.png','#888'),'#3F76E4');
 assert.equal(textureTint('BLUE_ICE','up','/blue_ice.png','#888'),'#ffffff');
});

test('A16-cube spanning a chunk boundary leases its whole volume instead of only its first half',()=>{
 const min={x:8,y:64,z:8},max={x:23,y:79,z:23},center=previewReadCenter({min,max});
 assert.deepEqual(center,{x:16,y:72,z:16});
 assert.equal(Math.floor((min.x+7)/16),0,'Old corner-centred16-cube would only lease chunk0');
 assert.equal(Math.floor(max.x/16),1,'Requested full cube also needs chunk1');
 for(const axis of ['x','y','z']){assert.ok(center[axis]-8<=min[axis]);assert.ok(center[axis]+7>=max[axis]);}
 assert.deepEqual(previewReadCenter({min:max,max:min}),center);
 assert.deepEqual(previewReadCenter({min,max:{...max,x:24}}),min,'Larger regions remain loaded-only, no unbounded lease request');
});
test('Rotated clipboard paste leases the centre of its transformed bounds',()=>{
 assert.deepEqual(previewReadCenter({origin:{x:15,y:64,z:15},rotation:90},{x:2,y:3,z:10}),{x:20,y:65,z:16});
 assert.deepEqual(previewReadCenter({origin:{x:15,y:64,z:15},rotation:0},{x:2,y:3,z:10}),{x:16,y:65,z:20});
 assert.equal(previewReadCenter({}),undefined);
});
