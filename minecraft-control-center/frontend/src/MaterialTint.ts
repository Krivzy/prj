/** Inventory preview has no biome. Use fixed foliage/grass preview colours only on client-tinted faces. */
export function materialTint(texture:string,tintindex?:number):string|undefined {
  if(tintindex!==0)return undefined;
  const name=texture.match(/^\/game-assets\/textures\/block\/([a-z0-9_]+)\.png$/)?.[1];
  if(name==='spruce_leaves')return '#619961';
  if(name==='birch_leaves')return '#80a755';
  if(name&&['oak_leaves','jungle_leaves','acacia_leaves','dark_oak_leaves','mangrove_leaves','vine'].includes(name))return '#77ab48';
  if(name&&['grass_block_top','grass_block_side_overlay','short_grass','tall_grass_top','tall_grass_bottom','fern','large_fern_top','large_fern_bottom','sugar_cane'].includes(name))return '#91bd59';
  return undefined;
}
export function tintMatrix(color:string):string {
  const rgb=[1,3,5].map(offset=>parseInt(color.slice(offset,offset+2),16)/255);
  return `${rgb[0]} 0 0 0 0  0 ${rgb[1]} 0 0 0  0 0 ${rgb[2]} 0 0  0 0 0 1 0`;
}
