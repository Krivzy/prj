import test from 'node:test';
import assert from 'node:assert/strict';
import {fitImage, visibleImages, projectImage, unprojectImage, zoomImage, screenPoint, panImage, sameMarkers, MAX_VISIBLE_IMAGES} from '../src/saved-image-map.ts';

test('image and player projection share signed world coordinates and actual height', () => {
  for (const point of [{x:-64,y:77,z:-48},{x:29999840,y:-64,z:-29999840},{x:0,y:320,z:0}]) assert.deepEqual(unprojectImage(projectImage(point),point.y),point);
  assert.deepEqual(projectImage({x:0,y:100,z:0}),{u:0,v:-100});
});
test('whole saved extent fits without a spawn radius limit', () => {
  const bounds={minU:-120000000,minV:-60000000,maxU:120000000,maxV:60000000}, size={width:390,height:450};
  const camera=fitImage(bounds,size);
  for (const point of [{u:bounds.minU,v:bounds.minV},{u:bounds.maxU,v:bounds.maxV}]) {const p=screenPoint(point,camera,size);assert.ok(p.x>=31&&p.x<=size.width-31);assert.ok(p.y>=31&&p.y<=size.height-31);}
  assert.ok(visibleImages(camera,size).length<=MAX_VISIBLE_IMAGES);
});
test('wheel zoom preserves the image location under the pointer', () => {
  const camera={u:-128,v:177,scale:.125}, size={width:1440,height:560}, pointer={x:231,y:448};
  const location={u:camera.u+(pointer.x-size.width/2)/camera.scale,v:camera.v+(pointer.y-size.height/2)/camera.scale};
  const next=zoomImage(camera,1.4,size,pointer), actual=screenPoint(location,next,size);
  assert.ok(Math.abs(actual.x-pointer.x)<1e-9);assert.ok(Math.abs(actual.y-pointer.y)<1e-9);
});
test('signed tile windows follow far saved locations, with only a DOM count budget', () => {
  const size={width:1440,height:560}, camera={u:-60000000,v:30000000,scale:1};
  const tiles=visibleImages(camera,size);assert.ok(tiles.length>0&&tiles.length<=MAX_VISIBLE_IMAGES);assert.ok(tiles.every(p=>p.x<-200000&&p.y>100000));
  assert.ok(tiles.some(p=>p.u<=camera.u&&p.u+p.span>=camera.u&&p.v<=camera.v&&p.v+p.span>=camera.v));
  assert.equal(visibleImages(camera,size,20,{minU:0,minV:0,maxU:100,maxV:100}).length,0);
});
test('large displays choose coarser cached pyramid images instead of cropping the world', () => {
  const tiles=visibleImages({u:0,v:0,scale:1},{width:10000,height:4000});assert.ok(tiles.length<=MAX_VISIBLE_IMAGES);assert.ok(tiles[0].level>0);
  const min=Math.min(...tiles.map(p=>p.u)),max=Math.max(...tiles.map(p=>p.u+p.span));assert.ok(min<=-5000&&max>=5000);
});
test('minimum zoom on a tiny world clamps before iteration and uses only existing extent', () => {
  const tiles=visibleImages({u:0,v:0,scale:2**-20},{width:1440,height:560},0,{minU:-2,minV:-80,maxU:64,maxV:18});assert.equal(tiles.length,4);assert.ok(tiles.every(p=>p.level===0));
});
test('camera pans are independent of block sampling, identical players do not change overlay state', () => {
  assert.deepEqual(panImage({u:0,v:0,scale:.5},64,-32),{u:128,v:-64,scale:.5});
  const a=[{uuid:'p',name:'Player',x:1,y:64,z:2}];assert.equal(sameMarkers(a,[{...a[0]}]),true);assert.equal(sameMarkers(a,[{...a[0],z:3}]),false);assert.equal(sameMarkers(a,[]),false);
});
