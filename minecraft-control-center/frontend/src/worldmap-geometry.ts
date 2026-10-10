import type {Vec3} from './map3d-geometry';
export type TerrainBlock={material:string;blockData:string;color:string};
export type TerrainTile={cx:number;cz:number;status:string;refreshing?:boolean;refreshStatus?:string;message?:string;size?:number;depth?:number;heights?:(number|null)[];blocks?:(number|null)[];voxels?:(number|null)[];palette?:TerrainBlock[];sampledAt?:number};
export function terrainCell(tiles:Map<string,TerrainTile>,point:Vec3){const cx=Math.floor(point.x/16),cz=Math.floor(point.z/16),tile=tiles.get(`${cx},${cz}`);if(!tile?.heights||!tile.voxels||!tile.palette)return undefined;const col=(point.x-cx*16)+16*(point.z-cz*16),height=tile.heights[col];if(height===null)return undefined;const d=height-point.y;if(d<0)return null;if(d>=(tile.depth??12))return undefined;const i=tile.voxels[col+256*d];return i===null?null:tile.palette[i];}
export function isClick(start:{x:number;y:number},end:{x:number;y:number}){return Math.hypot(end.x-start.x,end.y-start.y)<=5;}

/** Native classic foliage/grass PNGs are grayscale. Stable approximate biome colors. */
export function textureTint(material:string,face:string,url:string|undefined,fallback:string){
 if(!url)return fallback;
 if(material==='WATER'&&/\/water_(?:still|flow)\.png(?:$|\?)/.test(url))return '#3F76E4';
 if(material==='BIRCH_LEAVES')return '#80A755';
 if(material==='SPRUCE_LEAVES')return '#619961';
 if(['OAK_LEAVES','DARK_OAK_LEAVES','ACACIA_LEAVES','JUNGLE_LEAVES','MANGROVE_LEAVES'].includes(material))return '#77A35E';
 if(material==='GRASS_BLOCK'&&face==='up'&&/\/grass_block_top\.png(?:$|\?)/.test(url))return '#79A84A';
 return '#ffffff';
}

/** Keep a verified old tile during refresh; genuine missing/PROTO and world changes win. */
export function mergeTerrainTiles(previousWorld:string|undefined,previous:TerrainTile[]|undefined,world:string,next:TerrainTile[]){
 if(previousWorld!==world||!previous)return next;
 const old=new Map(previous.filter(t=>t.status==='ready'&&t.voxels&&t.palette).map(t=>[`${t.cx},${t.cz}`,t]));
 return next.map(tile=>{const retained=old.get(`${tile.cx},${tile.cz}`);return retained&&['loading','processing','deferred','error'].includes(tile.status)?{...retained,refreshing:true,refreshStatus:tile.status,message:tile.message}:tile;});
}

/** A bounded rectangular preview must lease around its centre, not its first corner. */
export function previewReadCenter(body:{min?:Vec3;max?:Vec3;origin?:Vec3;rotation?:number},clipboardSize?:Vec3){
 let min=body.min,max=body.max;
 if(body.origin){min=body.origin;if(clipboardSize){const rotated=body.rotation===90||body.rotation===270,size=rotated?{x:clipboardSize.z,y:clipboardSize.y,z:clipboardSize.x}:clipboardSize;max={x:min.x+size.x-1,y:min.y+size.y-1,z:min.z+size.z-1};}}
 if(!min)return undefined;
 if(!max)return min;
 const start={x:Math.min(min.x,max.x),y:Math.min(min.y,max.y),z:Math.min(min.z,max.z)},end={x:Math.max(min.x,max.x),y:Math.max(min.y,max.y),z:Math.max(min.z,max.z)};
 if(['x','y','z'].some(axis=>end[axis as keyof Vec3]-start[axis as keyof Vec3]+1>16))return min;
 return {x:Math.floor((start.x+end.x+1)/2),y:Math.floor((start.y+end.y+1)/2),z:Math.floor((start.z+end.z+1)/2)};
}
