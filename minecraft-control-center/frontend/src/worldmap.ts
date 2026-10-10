import * as THREE from 'three';
import {VoxelView,faces,textureInfo,regionCell} from './map3d';
import type {Animation,FaceInstance,Region,Selection,TextureManifest,Vec3} from './map3d';
import {terrainCell,textureTint} from './worldmap-geometry';
import type {TerrainBlock,TerrainTile} from './worldmap-geometry';
export type {TerrainBlock,TerrainTile} from './worldmap-geometry';

export type Terrain={world:string;worldId:string;cx:number;cz:number;radius:number;step:number;tiles:TerrainTile[];players:{uuid:string;name:string;x:number;y:number;z:number}[];pending:boolean;minY:number;maxY:number;metrics:{cache:number;pending:number;loads:number;tickets:number;snapshotMs:number}};
export type WorldIndex={world:string;regions:{rx:number;rz:number;chunks:number;presence:string}[];savedChunks:number;bounds:{minX:number;maxX:number;minZ:number;maxZ:number}|null;truncated:boolean;scannedAt:number};
export type BuildCatalog={blocks:{material:string;blockData:string;supported:boolean;reason?:string}[];maxBlocks:number};

/** Actual snapshot terrain and exact leased detail overlay; camera may visit any world coordinate. */
export class WorldView extends VoxelView {
  private terrain:Terrain|null=null;
  private center:Vec3={x:0,y:64,z:0};
  private signature='';
  private lastManifest:TextureManifest|null=null;
  private markerSignature='';
  private viewportTimer=0;
  private onViewport:(point:Vec3,distance:number)=>void;
  constructor(host:HTMLElement,onSelect:(value:Selection,extend:boolean,button:number,inspect:boolean)=>void,onViewport:(point:Vec3,distance:number)=>void){super(host,onSelect,true);this.onViewport=onViewport;this.camera.far=20000;this.camera.updateProjectionMatrix();this.controls.maxDistance=6000;this.controls.maxPolarAngle=Math.PI*.85;this.grid.visible=false;this.controls.addEventListener('end',this.viewportEnd);this.renderer.domElement.setAttribute('aria-label','Minecraft world. Drag to orbit; right drag to pan; wheel to zoom. Left click places; right click removes in editor mode. Ctrl click inspects.');}
  private viewportEnd=()=>{window.clearTimeout(this.viewportTimer);this.viewportTimer=window.setTimeout(()=>{const o=this.region?.origin??{x:0,y:0,z:0},target=this.controls.target;this.center={x:target.x+o.x,y:target.y+o.y,z:target.z+o.z};this.onViewport(this.center,this.camera.position.distanceTo(target));},250);};
  setWorld(world:string,point:Vec3){if(this.region?.world===world){this.focus(point);this.center={...point};return;}this.terrain=null;this.signature='';this.center={...point};this.region={world,worldId:'',origin:{x:Math.floor(point.x/16)*16,y:0,z:Math.floor(point.z/16)*16},size:80,blocks:[],palette:[],players:[],loadedBlocks:0,totalBlocks:0,sampledAt:0,loadedOnly:true};this.disposeMeshes();this.home();}
  setTerrain(terrain:Terrain,detail:Region|null,manifest:TextureManifest){
    const signature=`${terrain.world}:${terrain.cx}:${terrain.cz}:${terrain.step}:${terrain.tiles.map(t=>`${t.cx},${t.cz},${t.status},${t.sampledAt??0}`).join(';')}:${detail?.sampledAt??0}`;
    const markerSignature=terrain.players.map(p=>`${p.uuid}:${p.x}:${p.y}:${p.z}`).join(';');
    if(signature===this.signature&&this.terrain&&manifest===this.lastManifest){if(this.region&&markerSignature!==this.markerSignature){this.region.players=terrain.players.map(p=>({...p,yaw:0}));this.markerSignature=markerSignature;this.updateMarkers();this.dirty=true;}return;}this.markerSignature=markerSignature;this.signature=signature;this.lastManifest=manifest;this.terrain=terrain;
    const old=this.region?.origin??{x:0,y:0,z:0},origin={x:terrain.cx*16,y:0,z:terrain.cz*16};const shift=new THREE.Vector3(old.x-origin.x,old.y-origin.y,old.z-origin.z);this.camera.position.add(shift);this.controls.target.add(shift);this.rebaseGhost(shift);
    this.region={world:terrain.world,worldId:terrain.worldId,origin,size:(terrain.radius*2+1)*16,blocks:[],palette:[],players:terrain.players.map(p=>({...p,yaw:0})),loadedBlocks:0,totalBlocks:0,sampledAt:Date.now(),loadedOnly:true};this.disposeMeshes();
    const tiles=new Map(terrain.tiles.filter(t=>t.status==='ready').map(t=>[`${t.cx},${t.cz}`,t]));
    const inside=(p:Vec3)=>detail&&p.x>=detail.origin.x&&p.x<detail.origin.x+detail.size&&p.y>=detail.origin.y&&p.y<detail.origin.y+detail.size&&p.z>=detail.origin.z&&p.z<detail.origin.z+detail.size;
    const cell=(p:Vec3):TerrainBlock|null|undefined=>{if(inside(p)){const b=regionCell(detail!,p);return b?.air?null:b;}return terrainCell(tiles,p);};
    const groups=new Map<string,{url?:string;animation?:Animation;transparent:boolean;instances:FaceInstance[]}>();
    const add=(point:Vec3,block:TerrainBlock)=>{if(/^(AIR|CAVE_AIR|VOID_AIR)$/.test(block.material))return;for(const face of faces){const n=face.normal,neighbor=cell({x:point.x+n.x,y:point.y+n.y,z:point.z+n.z});if(neighbor&&(!/WATER|GLASS|LEAVES|ICE/.test(neighbor.material)||neighbor.blockData===block.blockData))continue;const info=textureInfo(manifest,block.material,face.name),transparent=/WATER|GLASS|ICE/.test(block.material),key=`${info.url??block.color}:${transparent}`;let group=groups.get(key);if(!group){group={...info,transparent,instances:[]};groups.set(key,group);}group.instances.push({selection:{point,normal:{...n},material:block.material,blockData:block.blockData},position:new THREE.Vector3(point.x-origin.x+.5+n.x*.5,point.y+.5+n.y*.5,point.z-origin.z+.5+n.z*.5),rotation:new THREE.Euler(...face.rotation),color:textureTint(block.material,face.name,info.url,block.color)});}};
    for(const tile of tiles.values()){if(!tile.heights||!tile.voxels||!tile.palette)continue;for(let col=0;col<256;col++){const h=tile.heights[col];if(h===null)continue;for(let d=0;d<(tile.depth??12);d++){const i=tile.voxels[col+256*d];if(i===null)continue;const point={x:tile.cx*16+col%16,y:h-d,z:tile.cz*16+Math.floor(col/16)};if(!inside(point))add(point,tile.palette[i]);}}}
    if(detail){const s=detail.size,o=detail.origin;for(let y=0;y<s;y++)for(let z=0;z<s;z++)for(let x=0;x<s;x++){const point={x:o.x+x,y:o.y+y,z:o.z+z},b=regionCell(detail,point);if(b&&!b.air)add(point,b);}}
    const dummy=new THREE.Object3D(),color=new THREE.Color();for(const group of groups.values()){const material=new THREE.MeshLambertMaterial({map:group.url?this.texture(group.url,group.animation):null,alphaTest:.15,transparent:group.transparent,opacity:group.transparent?.72:1,side:THREE.FrontSide}),mesh=new THREE.InstancedMesh(this.plane,material,group.instances.length),cells:Selection[]=[];group.instances.forEach((instance,i)=>{dummy.position.copy(instance.position);dummy.rotation.copy(instance.rotation);dummy.updateMatrix();mesh.setMatrixAt(i,dummy.matrix);mesh.setColorAt(i,color.set(instance.color));cells.push(instance.selection);});mesh.userData.cells=cells;mesh.instanceMatrix.needsUpdate=true;if(mesh.instanceColor)mesh.instanceColor.needsUpdate=true;mesh.computeBoundingSphere();this.group.add(mesh);this.meshes.push(mesh);}this.updateMarkers();this.controls.update();this.dirty=true;
  }
  private flushMotion(){const damping=this.controls.enableDamping;this.controls.enableDamping=false;this.controls.update();this.controls.enableDamping=damping;}
  home(){this.flushMotion();const target=this.toLocal(this.center);this.controls.target.copy(target);this.camera.position.copy(target).add(new THREE.Vector3(65,62,65));this.controls.update();this.dirty=true;}
  top(){this.flushMotion();const target=this.toLocal(this.center);this.controls.target.copy(target);this.camera.position.copy(target).add(new THREE.Vector3(0,110,.01));this.controls.update();this.dirty=true;}
  focus(point:Vec3){this.flushMotion();this.center={...point};super.focus(point);}
  dispose(){window.clearTimeout(this.viewportTimer);this.controls.removeEventListener('end',this.viewportEnd);super.dispose();}
}
