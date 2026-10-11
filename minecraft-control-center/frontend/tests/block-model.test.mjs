import test from 'node:test';
import assert from 'node:assert/strict';
import {projectModel,textureTransform} from '../src/BlockModel.ts';
const texture='/game-assets/textures/block/oak_planks.png';
const box=(from,to)=>({from,to,faces:{up:{texture},north:{texture},east:{texture}}});
test('A slab remains half-height and stairs retain their two actual client elements',()=>{
 const cube=projectModel({elements:[box([0,0,0],[16,16,16])]});
 const slab=projectModel({elements:[box([0,0,0],[16,8,16])]});
 const stairs=projectModel({elements:[box([0,0,0],[16,8,16]),box([0,8,8],[16,16,16])]});
 assert.equal(cube.length,3);assert.equal(slab.length,3);assert.equal(stairs.length,6);assert.notDeepEqual(cube,slab);assert.notDeepEqual(stairs,slab);
 const height=faces=>Math.max(...faces.flatMap(f=>f.points.map(p=>p[1])))-Math.min(...faces.flatMap(f=>f.points.map(p=>p[1])));
 assert.ok(height(slab)<height(cube));assert.ok(stairs.every(f=>textureTransform(f)?.startsWith('matrix(')));
});
test('UV rotation changes only the texture mapping, not the geometry',()=>{
 const a=projectModel({elements:[box([0,0,0],[16,8,16])]});
 const element=box([0,0,0],[16,8,16]);element.faces.up.rotation=90;
 const b=projectModel({elements:[element]});assert.deepEqual(a.map(f=>f.points),b.map(f=>f.points));assert.notEqual(textureTransform(a.find(f=>f.shade===0)),textureTransform(b.find(f=>f.shade===0)));
});
test('Malformed or unbounded geometry and external textures cannot reach SVG output',()=>{
 assert.deepEqual(projectModel({elements:Array.from({length:33},()=>box([0,0,0],[1,1,1]))}),[]);
 assert.deepEqual(projectModel({elements:[box([NaN,0,0],[16,16,16])]}),[]);
 const element=box([0,0,0],[16,16,16]);for(const f of Object.values(element.faces))f.texture='https://other.invalid/private.png';assert.deepEqual(projectModel({elements:[element]}),[]);
});
test('The fixed north/east/up camera projects cube faces into disjoint areas',()=>{
 const faces=projectModel({elements:[box([0,0,0],[16,16,16])]});
 const separated=(a,b)=>[a,b].some(poly=>poly.some((p,i)=>{
  const q=poly[(i+1)%poly.length],axis=[q[1]-p[1],p[0]-q[0]];
  const range=points=>points.map(([x,y])=>x*axis[0]+y*axis[1]);const aa=range(a),bb=range(b);
  return Math.max(...aa)<=Math.min(...bb)+1e-7||Math.max(...bb)<=Math.min(...aa)+1e-7;
 }));
 for(let i=0;i<faces.length;i++)for(let j=i+1;j<faces.length;j++)assert.ok(separated(faces[i].points,faces[j].points),'visible faces may share an edge but cannot overlap');
});
