import test from 'node:test';
import assert from 'node:assert/strict';
import {adjacent,normalizedBounds,sampleIndex,volume} from '../src/map3d-geometry.ts';

test('Face selection builds beside the face and never replaces the selected block',()=>{
  const point={x:-17,y:64,z:31};
  for(const normal of [{x:1,y:0,z:0},{x:-1,y:0,z:0},{x:0,y:1,z:0},{x:0,y:-1,z:0},{x:0,y:0,z:1},{x:0,y:0,z:-1}]){
    const target=adjacent({point,normal,blockData:'minecraft:stone',material:'STONE'});
    assert.equal(Math.abs(target.x-point.x)+Math.abs(target.y-point.y)+Math.abs(target.z-point.z),1);
    assert.deepEqual(point,{x:-17,y:64,z:31});
  }
});
test('Reversed corners and single-block selections count inclusive bounding volume',()=>{
  assert.equal(volume(normalizedBounds({x:15,y:15,z:15},{x:0,y:0,z:0})),4096);
  assert.equal(volume(normalizedBounds({x:4,y:64,z:-8},{x:4,y:64,z:-8})),1);
});
test('Minecraft X/Z/Y palette indexing respects negative origin and volume edges',()=>{
  const origin={x:-16,y:56,z:-32};
  assert.equal(sampleIndex(origin,16,{x:-16,y:56,z:-32}),0);
  assert.equal(sampleIndex(origin,16,{x:-15,y:56,z:-32}),1);
  assert.equal(sampleIndex(origin,16,{x:-16,y:56,z:-31}),16);
  assert.equal(sampleIndex(origin,16,{x:-16,y:57,z:-32}),256);
  assert.equal(sampleIndex(origin,16,{x:-1,y:71,z:-17}),4095);
  assert.equal(sampleIndex(origin,16,{x:0,y:71,z:-17}),undefined);
  assert.equal(sampleIndex(origin,16,{x:-17,y:56,z:-32}),undefined);
});
