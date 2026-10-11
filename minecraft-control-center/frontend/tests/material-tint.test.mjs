import test from 'node:test';
import assert from 'node:assert/strict';
import {projectModel} from '../src/BlockModel.ts';
import {materialTint,tintMatrix} from '../src/MaterialTint.ts';

test('Grass overlay tint reaches projected faces while the dirt face retains its original colour',()=>{
  const top='/game-assets/textures/block/grass_block_top.png',side='/game-assets/textures/block/grass_block_side.png',overlay='/game-assets/textures/block/grass_block_side_overlay.png';
  const model={elements:[{from:[0,0,0],to:[16,16,16],faces:{up:{texture:top,tintindex:0},east:{texture:side},north:{texture:overlay,tintindex:0}}}]};
  const faces=projectModel(model);assert.equal(faces.length,3);
  assert.ok(materialTint(top,faces.find(face=>face.texture===top).tintindex));
  assert.ok(materialTint(overlay,faces.find(face=>face.texture===overlay).tintindex));
  assert.equal(materialTint(side,faces.find(face=>face.texture===side).tintindex),undefined);
  const untinted=structuredClone(model);for(const face of Object.values(untinted.elements[0].faces))delete face.tintindex;
  assert.deepEqual(faces.map(face=>face.points),projectModel(untinted).map(face=>face.points));
});
test('Only client-tinted foliage gets a preview colour; distinct species and alpha are preserved',()=>{
  const prefix='/game-assets/textures/block/';
  assert.notEqual(materialTint(prefix+'oak_leaves.png',0),materialTint(prefix+'birch_leaves.png',0));
  assert.notEqual(materialTint(prefix+'spruce_leaves.png',0),materialTint(prefix+'birch_leaves.png',0));
  assert.equal(materialTint(prefix+'oak_leaves.png'),undefined);
  assert.equal(materialTint(prefix+'oak_leaves.png',16),undefined);
  assert.equal(materialTint(prefix+'netherrack.png',0),undefined);
  const matrix=tintMatrix(materialTint(prefix+'grass_block_top.png',0)).trim().split(/\s+/).map(Number);
  assert.equal(matrix.length,20);assert.deepEqual(matrix.slice(15),[0,0,0,1,0]);
  assert.ok(matrix[6]>matrix[0]&&matrix[0]>matrix[12],'green multiplier preserves texture detail, rather than painting opaque flat green');
});
