export type VoxelBlock = {
  x: number;
  y: number;
  z: number;
  scale?: [number, number, number];
  tint?: number;
};

export const OVERWORLD_BLOCK_SIZE = 0.7;

/** Build disjoint voxel strata and leave the waterfall outlet open. */
export function createOverworldTerrain() {
  const unit = OVERWORLD_BLOCK_SIZE;
  const riverDepth = 0.17;
  const top: VoxelBlock[] = [];
  const soil: VoxelBlock[] = [];
  const rock: VoxelBlock[] = [];
  const deep: VoxelBlock[] = [];

  for (let x = -6; x <= 6; x++) {
    for (let z = -4; z <= 4; z++) {
      const contour = (x / 6.0) ** 2 + (z / 4.3) ** 2;
      if (contour > 1 || (x < -4 && z > 2) || (x > 4 && z < -2)) continue;
      // No grass or soil at the front outlet: the waterfall passes through this column.
      if (x === 0 && z === 4) continue;
      const shade = 0.9 + ((x * 7 + z * 11 + 99) % 5) * 0.024;
      const river = x === 0 && z >= -1;
      // The lower face remains at -unit/2, flush with the unchanged soil layer.
      top.push({x: x * unit, y: river ? -riverDepth / 2 : 0, z: z * unit, tint: shade,
        ...(river ? {scale: [unit, unit - riverDepth, unit] as [number, number, number]} : {})});
      soil.push({x: x * unit, y: -0.7, z: z * unit, tint: shade});
      if (contour < 0.86) rock.push({x: x * unit, y: -1.4, z: z * unit, tint: shade});
      if (contour < 0.66) rock.push({x: x * unit, y: -2.1, z: z * unit, tint: shade * 0.95});
      if (contour < 0.41) deep.push({x: x * unit, y: -2.8, z: z * unit, tint: shade});
      if (contour < 0.16 && x !== 1) deep.push({x: x * unit, y: -3.5, z: z * unit, tint: shade});
    }
  }
  return {top, soil, rock, deep};
}
