import assert from 'node:assert/strict';
import {test} from 'node:test';
import {createOverworldTerrain, OVERWORLD_BLOCK_SIZE} from '../src/overworld-terrain.ts';

const epsilon = 1e-9;
const near = (actual, expected) => assert(Math.abs(actual - expected) < epsilon, `${actual} should equal ${expected}`);

test('Overworld terrain has no intersecting solid voxels or overlapping exterior side faces', () => {
  const terrain = createOverworldTerrain();
  const blocks = Object.values(terrain).flat().map(block => {
    const scale = block.scale ?? Array(3).fill(OVERWORLD_BLOCK_SIZE);
    return ['x', 'y', 'z'].map((axis, index) => [block[axis] - scale[index] / 2, block[axis] + scale[index] / 2]);
  });
  assert(blocks.length > 200, 'Check the complete generated island, including lower strata');
  for (let i = 0; i < blocks.length; i++) {
    for (let j = i + 1; j < blocks.length; j++) {
      const penetrates = blocks[i].every((range, axis) =>
        Math.min(range[1], blocks[j][axis][1]) - Math.max(range[0], blocks[j][axis][0]) > epsilon);
      assert(!penetrates, `Terrain voxels ${i} and ${j} intersect`);
    }
  }
});

test('Recessed river keeps its surface height and meets the unchanged soil without penetration', () => {
  const {top, soil} = createOverworldTerrain();
  const river = top.filter(block => block.x === 0 && block.z >= -OVERWORLD_BLOCK_SIZE);
  assert(river.length >= 5, 'Include the complete river through the front bank');
  for (const grass of river) {
    const dirt = soil.find(block => block.x === grass.x && block.z === grass.z);
    assert(dirt);
    near(grass.y + grass.scale[1] / 2, 0.18);
    near(grass.y - grass.scale[1] / 2, dirt.y + OVERWORLD_BLOCK_SIZE / 2);
    near(dirt.y, -0.7);
  }
  for (const grass of top.filter(block => !river.includes(block))) {
    near(grass.y, 0);
    assert.equal(grass.scale, undefined, 'The other turf blocks retain their full size');
  }
});

test('The waterfall passes through an open outlet without grass or soil in its path', () => {
  const terrain = createOverworldTerrain();
  const waterfall = {xMin: -0.32, xMax: 0.32, yMin: -3.13, yMax: 0.25, z: 2.82};
  for (const block of Object.values(terrain).flat()) {
    const [width, height, depth] = block.scale ?? Array(3).fill(OVERWORLD_BLOCK_SIZE);
    const coversFall = block.x - width / 2 < waterfall.xMax && block.x + width / 2 > waterfall.xMin
      && block.y - height / 2 < waterfall.yMax && block.y + height / 2 > waterfall.yMin
      && block.z - depth / 2 < waterfall.z && block.z + depth / 2 > waterfall.z;
    assert(!coversFall, `Solid voxel at ${block.x},${block.y},${block.z} blocks the waterfall`);
  }
  assert(!terrain.top.some(block => block.x === 0 && block.z === 4 * OVERWORLD_BLOCK_SIZE));
  assert(!terrain.soil.some(block => block.x === 0 && block.z === 4 * OVERWORLD_BLOCK_SIZE));
  assert(terrain.top.some(block => block.x === 0 && block.z === 3 * OVERWORLD_BLOCK_SIZE), 'Keep the upstream riverbed');
});
