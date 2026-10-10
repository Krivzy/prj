import test from 'node:test';
import assert from 'node:assert/strict';
import {project, unproject, viewportFor, surfaceCells, mergeSurfaceTiles, queueSurfaceFocus, resolveSurfaceFocus, isMapClick, MAX_SURFACE_TILES} from '../src/map-surface.ts';

const palette = [{material:'STONE', blockData:'minecraft:stone', color:'#999999'}, {material:'AIR', blockData:'minecraft:air', color:'#000000'}];
const tile = (cx, cz, sampledAt=1) => ({cx, cz, status:'ready', sampledAt, heights:Array(256).fill(65), blocks:Array(256).fill(0), palette});

test('Fixed isometric projection is reversible, including negative and remote coordinates', () => {
  for (const origin of [{x:0,y:64,z:0}, {x:-23,y:71,z:-127}, {x:29999800,y:-64,z:-29999800}]) {
    for (const point of [{x:origin.x+15,y:origin.y-4,z:origin.z+32}, {...origin}, {x:origin.x-8,y:origin.y+20,z:origin.z-11}]) {
      const p=unproject(project(point,origin),origin,point.y);
      for (const axis of ['x','y','z']) assert.ok(Math.abs(p[axis]-point[axis])<1e-8);
    }
  }
});
test('Surface indexing reads the real top block of negative chunks, excluding absent and air columns', () => {
  const t=tile(-2,-1); t.heights[255]=71; t.heights[0]=null; t.blocks[1]=1; t.blocks[2]=null;
  const cells=surfaceCells([t, {...tile(1,1),status:'missing'}]);
  assert.equal(cells.length,253);
  assert.deepEqual(cells.find(c=>c.x===-17&&c.z===-1),{x:-17,y:71,z:-1,...palette[0]});
  assert.equal(cells.some(c=>c.x===-32&&c.z===-16),false);
  assert.ok(cells.every(c=>c.material==='STONE'));
});
test('Temporary refresh preserves a complete surface, but missing and world switches invalidate it', () => {
  const original=tile(0,-1), center={cx:0,cz:-1};
  for(const status of ['loading','processing','deferred','error']) {
    const result=mergeSurfaceTiles('world',[original],'world',[{cx:0,cz:-1,status}],center);
    assert.equal(result[0].heights,original.heights); assert.equal(result[0].refreshStatus,status);
  }
  const missing={cx:0,cz:-1,status:'missing'};
  assert.deepEqual(mergeSurfaceTiles('world',[original],'world',[missing],center),[missing]);
  assert.deepEqual(mergeSurfaceTiles('old',[original],'new',[missing],center),[missing]);
  assert.equal(mergeSurfaceTiles('world',[original],'world',[tile(0,-1,2)],center)[0].sampledAt,2);
});
test('Long map journeys retain at most 64 tiles nearest the current location', () => {
  let retained=[];
  for(let i=0;i<200;i++) retained=mergeSurfaceTiles('world',retained,'world',[tile(i,0)],{cx:i,cz:0});
  assert.equal(retained.length,MAX_SURFACE_TILES); assert.equal(retained[0].cx,199);
  assert.equal(Math.min(...retained.map(t=>t.cx)),136);
});
test('Chunk window is bounded at every viewport and zoom; negative positions floor correctly', () => {
  for(const [width,height,zoom] of [[390,470,.65],[1440,590,1.6],[7680,4320,.65],[1,1,4]]) {
    const result=viewportFor({x:-.1,y:64,z:-16.1},width,height,zoom);
    assert.equal(result.cx,-1); assert.equal(result.cz,-2); assert.equal(result.step,1);
    assert.ok(result.radius>=1&&result.radius<=3);
    assert.ok((result.radius*2+1)**2<=49);
  }
});
test('A drag or pinch cannot be mistaken for a short point-selection click', () => {
  assert.equal(isMapClick({x:100,y:100},{x:103,y:104}),true);
  assert.equal(isMapClick({x:100,y:100},{x:106,y:100}),false);
  assert.equal(isMapClick({x:100,y:100},{x:160,y:145}),false);
});
test('Navigation to a distant hill centers its real surface instead of the spawn plane', () => {
  const point={x:32,y:64,z:48}, hill=tile(2,3); hill.heights[0]=191;
  const intent=queueSurfaceFocus(null,'world',point);
  const result=resolveSurfaceFocus(intent,'world',[hill]);
  assert.equal(result.height,192);
  const surface={...point,y:192};
  assert.equal(project(surface,point).y,-768);
  assert.equal(project(surface,{...point,y:result.height}).y,0);
});
test('Focus floors negative fractional coordinates and uses their exact column', () => {
  const point={x:-.1,y:64,z:-16.1}, t=tile(-1,-2); t.heights[255]=89; t.heights[0]=132;
  const intent=queueSurfaceFocus(null,'world',point);
  assert.equal(intent.x,-1); assert.equal(intent.z,-17);
  assert.equal(resolveSurfaceFocus(intent,'world',[t]).height,90);
});
test('Pending and missing chunks never borrow an adjacent saved chunk elevation', () => {
  const intent=queueSurfaceFocus(null,'world',{x:16,y:64,z:0});
  for(const status of ['loading','processing','deferred','error','missing']) {
    const result=resolveSurfaceFocus(intent,'world',[tile(0,0),{...tile(1,0),status}]);
    assert.equal(result.height,null); assert.equal(result.focus,intent);
  }
  assert.equal(resolveSurfaceFocus(intent,'world',[tile(0,0)]).height,null);
});
test('Absent, air and invalid-height columns keep focus pending until valid terrain arrives', () => {
  const intent=queueSurfaceFocus(null,'world',{x:0,y:64,z:0});
  for(const height of [null,NaN,Infinity,4096]) {
    const t=tile(0,0); t.heights[0]=height;
    assert.equal(resolveSurfaceFocus(intent,'world',[t]).height,null);
  }
  for(const block of [null,1,1000]) {
    const t=tile(0,0); t.blocks[0]=block;
    assert.equal(resolveSurfaceFocus(intent,'world',[t]).height,null);
  }
  assert.equal(resolveSurfaceFocus(intent,'world',[tile(0,0)]).height,66);
});
test('Explicit spawn and player elevation cancels pending automatic focus', () => {
  const point={x:0,y:200,z:0}, pending=queueSurfaceFocus(null,'world',point);
  const explicit=queueSurfaceFocus(pending,'world',point,true);
  assert.equal(explicit,null);
  assert.equal(resolveSurfaceFocus(explicit,'world',[tile(0,0)]).height,null);
});
test('Terrain from a different world cannot resolve an earlier focus intent', () => {
  const point={x:0,y:64,z:0}, pending=queueSurfaceFocus(null,'old',point);
  assert.deepEqual(resolveSurfaceFocus(pending,'new',[tile(0,0)]),{focus:null,height:null});
  const next=queueSurfaceFocus(pending,'new',point);
  assert.equal(next.world,'new'); assert.equal(next.pending,true);
});
test('A completed focus stays stable across tile refreshes and same-column navigation', () => {
  const point={x:0,y:64,z:0}, first=resolveSurfaceFocus(queueSurfaceFocus(null,'world',point),'world',[tile(0,0)]);
  assert.equal(first.height,66); assert.equal(first.focus.pending,false);
  const refreshed=tile(0,0,2); refreshed.heights[0]=140;
  const same=queueSurfaceFocus(first.focus,'world',{...point,x:.9,z:.9});
  assert.equal(same,first.focus); assert.equal(resolveSurfaceFocus(same,'world',[refreshed]).height,null);
  const next=queueSurfaceFocus(same,'world',{...point,x:1});
  assert.equal(next.pending,true); assert.equal(resolveSurfaceFocus(next,'world',[refreshed]).height,66);
});
