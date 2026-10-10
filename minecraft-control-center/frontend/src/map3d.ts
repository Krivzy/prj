import * as THREE from 'three';
import {OrbitControls} from 'three/addons/controls/OrbitControls.js';
import {sampleIndex} from './map3d-geometry';
import {isClick} from './worldmap-geometry';
import type {Vec3,Selection,Bounds} from './map3d-geometry';
export type {Vec3,Selection,Bounds} from './map3d-geometry';

export type Region={world:string;worldId:string;origin:Vec3;size:number;blocks:(number|null)[];palette:{blockData:string;material:string;color:string;occluding:boolean;air:boolean}[];players:{uuid:string;name:string;x:number;y:number;z:number;yaw:number}[];loadedBlocks:number;totalBlocks:number;sampledAt:number;loadedOnly:true};
export type Animation={height:number;frameHeight:number;frames:number[];frameTime:number};
export type TextureManifest={blocks?:Record<string,string|{image?:string;url?:string;faces?:Record<string,string>;animation?:Animation}>};
export type BuildParams={operation:string;world:string;min?:Vec3;max?:Vec3;origin?:Vec3;rotation?:number;blockData?:string};
export type Preview={token?:string;operation:string;changes?:number;affected?:number;copied?:number;bounds:Bounds;expiresAt?:number;requiresConfirmation?:boolean;clipboard?:{blocks:number;size:Vec3}};
export type BuildJob={jobId:string;status:'queued'|'validating'|'saving'|'running'|'paused'|'completed'|'partial'|'failed';operation:string;changed:number;skipped:number;total:number;progress:number;message?:string;startedAt:number;finishedAt:number};
export type BuildHistory={undo:{id:string;world:string;operation:string;at:number;blocks:number}[];redo:{id:string;world:string;operation:string;at:number;blocks:number}[];clipboard:{blocks?:number;size?:Vec3};maxBlocks:number;loadedOnly:boolean};
export function regionCell(region:Region,point:Vec3){const sample=sampleIndex(region.origin,region.size,point);if(sample===undefined)return undefined;const index=region.blocks[sample];return index===null?null:region.palette[index];}
export const faces=[
  {name:'east',normal:{x:1,y:0,z:0},rotation:[0,Math.PI/2,0]},
  {name:'west',normal:{x:-1,y:0,z:0},rotation:[0,-Math.PI/2,0]},
  {name:'up',normal:{x:0,y:1,z:0},rotation:[-Math.PI/2,0,0]},
  {name:'down',normal:{x:0,y:-1,z:0},rotation:[Math.PI/2,0,0]},
  {name:'south',normal:{x:0,y:0,z:1},rotation:[0,0,0]},
  {name:'north',normal:{x:0,y:0,z:-1},rotation:[0,Math.PI,0]},
] as const;
function localUrl(value:string|undefined){return value?.startsWith('/')&&!value.startsWith('//')?value:undefined;}
export function textureInfo(manifest:TextureManifest,material:string,face:string){const key=material.toLowerCase();const record=manifest.blocks?.[`minecraft:${key}`]??manifest.blocks?.[key];return typeof record==='string'?{url:localUrl(record)}:{url:localUrl(record?.faces?.[face]??record?.image??record?.url),animation:record?.animation};}
export type FaceInstance={selection:Selection;position:THREE.Vector3;rotation:THREE.Euler;color:string};

/** Render only actual sampled faces; shared instancing, bounded textures and no network render loop. */
export class VoxelView {
  protected renderer:THREE.WebGLRenderer;
  protected scene=new THREE.Scene();
  protected camera=new THREE.PerspectiveCamera(42,1,.1,1000);
  protected controls:OrbitControls;
  protected group=new THREE.Group();
  private raycaster=new THREE.Raycaster();
  private pointer=new THREE.Vector2();
  protected meshes:THREE.InstancedMesh[]=[];
  protected textures=new Map<string,THREE.Texture>();
  private animated=new Map<string,{texture:THREE.Texture;animation:Animation;frame:number}>();
  protected plane=new THREE.PlaneGeometry(1,1);
  private outline:THREE.LineSegments;
  private chosen:THREE.LineSegments;
  private previewGroup=new THREE.Group();
  private boundsLine:THREE.LineSegments;
  private markers:THREE.Group=new THREE.Group();
  protected grid:THREE.GridHelper;
  private observer:ResizeObserver;
  private frame=0;
  protected dirty=true;
  private closed=false;
  protected region:Region|null=null;
  private shift=false;
  private dragStart={x:0,y:0};
  private lastRender=0;
  private lastHover=0;
  private viewKey='';
  private onSelect:(selection:Selection,extend:boolean,button:number,inspect:boolean)=>void;
  private rightClick=false;
  private host:HTMLElement;
  constructor(host:HTMLElement,onSelect:(selection:Selection,extend:boolean,button:number,inspect:boolean)=>void,rightClick=false) {
    this.host=host;
    this.onSelect=onSelect;this.rightClick=rightClick;
    this.renderer=new THREE.WebGLRenderer({antialias:true,alpha:true,powerPreference:'low-power'});
    this.renderer.setPixelRatio(Math.min(devicePixelRatio,1.75));this.renderer.outputColorSpace=THREE.SRGBColorSpace;
    this.renderer.setClearColor(0x101e3d,0);this.renderer.domElement.tabIndex=0;this.renderer.domElement.setAttribute('aria-label','3D Minecraft. Drag to orbit, right drag to pan, wheel to zoom, click a block to select.');this.host.append(this.renderer.domElement);
    this.scene.add(this.group,this.previewGroup,this.markers);
    this.scene.add(new THREE.HemisphereLight(0xffffff,0x4f567e,2));const light=new THREE.DirectionalLight(0xffffff,2);light.position.set(-20,35,15);this.scene.add(light);
    this.controls=new OrbitControls(this.camera,this.renderer.domElement);this.controls.enableDamping=true;this.controls.dampingFactor=.13;this.controls.minDistance=2;this.controls.maxDistance=160;this.controls.maxPolarAngle=Math.PI*.95;this.controls.addEventListener('change',this.markDirty);
    this.controls.listenToKeyEvents(this.renderer.domElement);
    const edges=new THREE.EdgesGeometry(new THREE.BoxGeometry(1.005,1.005,1.005));
    this.outline=new THREE.LineSegments(edges,new THREE.LineBasicMaterial({color:0xe9f6ff,transparent:true,opacity:.85,depthTest:false}));this.outline.visible=false;this.outline.renderOrder=5;this.scene.add(this.outline);
    this.chosen=new THREE.LineSegments(edges,new THREE.LineBasicMaterial({color:0x72d8ff,depthTest:false}));this.chosen.visible=false;this.chosen.renderOrder=6;this.scene.add(this.chosen);
    this.boundsLine=new THREE.LineSegments(edges,new THREE.LineBasicMaterial({color:0xa993ff,depthTest:false,transparent:true,opacity:.9}));this.boundsLine.visible=false;this.boundsLine.renderOrder=7;this.scene.add(this.boundsLine);
    this.grid=new THREE.GridHelper(48,48,0x456691,0x253b62);this.grid.position.y=-.04;this.scene.add(this.grid);
    this.observer=new ResizeObserver(this.resize);this.observer.observe(host);this.resize();
    this.renderer.domElement.addEventListener('pointerdown',this.pointerDown);this.renderer.domElement.addEventListener('pointerup',this.pointerUp);this.renderer.domElement.addEventListener('pointermove',this.pointerMove);this.renderer.domElement.addEventListener('pointerleave',this.pointerLeave);this.renderer.domElement.addEventListener('webglcontextlost',this.contextLost);
    this.frame=requestAnimationFrame(this.animate);
  }
  private markDirty=()=>{this.dirty=true;};
  private resize=()=>{const width=this.host.clientWidth,height=this.host.clientHeight;if(!width||!height)return;this.renderer.setSize(width,height,false);this.camera.aspect=width/height;this.camera.updateProjectionMatrix();this.dirty=true;};
  private contextLost=(event:Event)=>{event.preventDefault();this.host.dispatchEvent(new CustomEvent('mcc-map-error',{detail:'WebGL context lost. Reload this page to restore the map.'}));};
  private pointerDown=(event:PointerEvent)=>{this.dragStart={x:event.clientX,y:event.clientY};this.shift=event.shiftKey;};
  private hit(event:PointerEvent){const rect=this.renderer.domElement.getBoundingClientRect();this.pointer.set((event.clientX-rect.left)/rect.width*2-1,-(event.clientY-rect.top)/rect.height*2+1);this.camera.updateMatrixWorld();this.raycaster.setFromCamera(this.pointer,this.camera);const hit=this.raycaster.intersectObjects(this.meshes,false)[0];return hit?.instanceId===undefined?null:(hit.object.userData.cells as Selection[])[hit.instanceId]??null;}
  private pointerUp=(event:PointerEvent)=>{if((event.button!==0&&!(this.rightClick&&event.button===2))||!isClick(this.dragStart,{x:event.clientX,y:event.clientY}))return;const selected=this.hit(event);if(selected){this.setSelection(selected);this.onSelect(selected,this.shift||event.shiftKey,event.button,event.ctrlKey||event.metaKey);}};
  private pointerMove=(event:PointerEvent)=>{if(event.buttons||performance.now()-this.lastHover<85)return;this.lastHover=performance.now();const selected=this.hit(event);this.outline.visible=!!selected;if(selected)this.outline.position.copy(this.toLocal(selected.point)).addScalar(.5);this.renderer.domElement.style.cursor=selected?'crosshair':'grab';this.dirty=true;};
  private pointerLeave=()=>{this.outline.visible=false;this.dirty=true;};
  private animate=(now:number)=>{
    if(this.closed)return;
    if(!document.hidden){this.controls.update();let animated=false;
      for(const item of this.animated.values()){const a=item.animation;const frame=Math.floor(now/(Math.max(1,a.frameTime)*50))%Math.max(1,a.frames.length);if(frame!==item.frame){item.frame=frame;item.texture.offset.y=1-(a.frames[frame]+1)*a.frameHeight/a.height;animated=true;}}
      if((this.dirty||animated)&&now-this.lastRender>=32){this.renderer.render(this.scene,this.camera);this.lastRender=now;this.dirty=false;}
    }this.frame=requestAnimationFrame(this.animate);
  };
  protected toLocal(point:Vec3){const o=this.region?.origin??{x:0,y:0,z:0};return new THREE.Vector3(point.x-o.x,point.y-o.y,point.z-o.z);}
  protected texture(url:string,animation?:Animation){let texture=this.textures.get(url);if(!texture){texture=new THREE.TextureLoader().load(url,()=>{this.dirty=true;},undefined,()=>{this.dirty=true;});texture.magFilter=THREE.NearestFilter;texture.minFilter=THREE.NearestFilter;texture.colorSpace=THREE.SRGBColorSpace;texture.generateMipmaps=false;this.textures.set(url,texture);if(animation&&animation.height>animation.frameHeight&&animation.frames?.length){texture.repeat.y=animation.frameHeight/animation.height;texture.offset.y=1-texture.repeat.y;this.animated.set(url,{texture,animation,frame:-1});}}return texture;}
  setRegion(region:Region,manifest:TextureManifest,cutY:number) {
    const newView=this.viewKey!==`${region.world}:${region.origin.x}:${region.origin.y}:${region.origin.z}:${region.size}`;
    this.viewKey=`${region.world}:${region.origin.x}:${region.origin.y}:${region.origin.z}:${region.size}`;this.region=region;
    this.disposeMeshes();this.outline.visible=false;this.chosen.visible=false;
    const groups=new Map<string,{url?:string;animation?:Animation;transparent:boolean;instances:FaceInstance[]}>();
    const s=region.size,o=region.origin;
    for(let y=0;y<s;y++)for(let z=0;z<s;z++)for(let x=0;x<s;x++) {
      if(y+o.y>cutY)continue;const index=region.blocks[x+s*(z+s*y)];if(index===null)continue;const block=region.palette[index];if(!block||block.air)continue;
      const point={x:x+o.x,y:y+o.y,z:z+o.z};
      for(const face of faces) {
        const n=face.normal;const neighbor=regionCell(region,{x:point.x+n.x,y:point.y+n.y,z:point.z+n.z});
        if(point.y+n.y<=cutY&&neighbor&&!neighbor.air&&(neighbor.occluding||neighbor.blockData===block.blockData))continue;
        const info=textureInfo(manifest,block.material,face.name);const transparent=/WATER|GLASS|ICE/.test(block.material);
        const key=`${info.url??block.color}:${transparent}`;let group=groups.get(key);if(!group){group={...info,transparent,instances:[]};groups.set(key,group);}
        group.instances.push({selection:{point,normal:{...n},blockData:block.blockData,material:block.material},position:new THREE.Vector3(x+.5+n.x*.5,y+.5+n.y*.5,z+.5+n.z*.5),rotation:new THREE.Euler(...face.rotation),color:info.url?'#ffffff':block.color});
      }
    }
    const dummy=new THREE.Object3D();const color=new THREE.Color();
    for(const group of groups.values()) {
      const material=new THREE.MeshLambertMaterial({map:group.url?this.texture(group.url,group.animation):null,alphaTest:.15,transparent:group.transparent,opacity:group.transparent ? .72 : 1,side:THREE.FrontSide});
      const mesh=new THREE.InstancedMesh(this.plane,material,group.instances.length);const cells:Selection[]=[];
      group.instances.forEach((instance,index)=>{dummy.position.copy(instance.position);dummy.rotation.copy(instance.rotation);dummy.updateMatrix();mesh.setMatrixAt(index,dummy.matrix);mesh.setColorAt(index,color.set(instance.color));cells.push(instance.selection);});
      mesh.userData.cells=cells;mesh.instanceMatrix.needsUpdate=true;if(mesh.instanceColor)mesh.instanceColor.needsUpdate=true;mesh.computeBoundingSphere();this.group.add(mesh);this.meshes.push(mesh);
    }
    this.grid.position.set(s/2,-.04,s/2);this.grid.scale.set(s/48,1,s/48);
    this.updateMarkers();if(newView)this.home();this.dirty=true;
  }
  protected updateMarkers(){for(const child of [...this.markers.children]){this.markers.remove(child);if(child instanceof THREE.Mesh){child.geometry.dispose();(child.material as THREE.Material).dispose();}}
    if(!this.region)return;for(const player of this.region.players){const marker=new THREE.Mesh(new THREE.BoxGeometry(.55,1.65,.55),new THREE.MeshLambertMaterial({color:0x61def4,transparent:true,opacity:.9}));marker.position.copy(this.toLocal(player)).add(new THREE.Vector3(0,.825,0));this.markers.add(marker);}}
  setSelection(selection:Selection|null){this.chosen.visible=!!selection;if(selection)this.chosen.position.copy(this.toLocal(selection.point)).addScalar(.5);this.dirty=true;}
  setBounds(bounds:Bounds|null){this.boundsLine.visible=!!bounds;if(bounds){const a=this.toLocal(bounds.min),b=this.toLocal(bounds.max);this.boundsLine.position.copy(a.clone().add(b).addScalar(1).multiplyScalar(.5));this.boundsLine.scale.copy(b.sub(a).addScalar(1));}this.dirty=true;}
  protected rebaseGhost(shift:THREE.Vector3){this.previewGroup.position.add(shift);}
  setGhost(points:Vec3[],color='#80dcff'){this.previewGroup.position.set(0,0,0);for(const child of [...this.previewGroup.children]){this.previewGroup.remove(child);if(child instanceof THREE.Mesh){child.geometry.dispose();(child.material as THREE.Material).dispose();}}
    if(points.length){const geometry=new THREE.BoxGeometry(.98,.98,.98),material=new THREE.MeshBasicMaterial({color,transparent:true,opacity:.25,depthWrite:false});const mesh=new THREE.InstancedMesh(geometry,material,Math.min(4096,points.length));const dummy=new THREE.Object3D();points.slice(0,4096).forEach((p,i)=>{dummy.position.copy(this.toLocal(p)).addScalar(.5);dummy.updateMatrix();mesh.setMatrixAt(i,dummy.matrix);});mesh.computeBoundingSphere();this.previewGroup.add(mesh);}this.dirty=true;}
  home(){if(!this.region)return;const size=this.region.size;this.controls.target.set(size/2,size*.4,size/2);this.camera.position.set(size*1.7,size*1.35,size*1.7);this.controls.update();this.controls.saveState();this.dirty=true;}
  top(){if(!this.region)return;const s=this.region.size;this.controls.target.set(s/2,s/2,s/2);this.camera.position.set(s/2,s*2.3,s/2+.01);this.controls.update();this.dirty=true;}
  focus(point:Vec3){const target=this.toLocal(point).addScalar(.5),delta=target.clone().sub(this.controls.target);this.camera.position.add(delta);this.controls.target.copy(target);this.controls.update();this.dirty=true;}
  protected disposeMeshes(){for(const mesh of this.meshes){this.group.remove(mesh);(mesh.material as THREE.Material).dispose();mesh.dispose();}this.meshes=[];}
  dispose(){this.closed=true;cancelAnimationFrame(this.frame);this.observer.disconnect();this.controls.dispose();this.disposeMeshes();for(const texture of this.textures.values())texture.dispose();this.textures.clear();this.plane.dispose();this.outline.geometry.dispose();(this.outline.material as THREE.Material).dispose();(this.chosen.material as THREE.Material).dispose();(this.boundsLine.material as THREE.Material).dispose();this.grid.geometry.dispose();for(const material of Array.isArray(this.grid.material)?this.grid.material:[this.grid.material])material.dispose();for(const marker of this.markers.children){if(marker instanceof THREE.Mesh){marker.geometry.dispose();(marker.material as THREE.Material).dispose();}}this.setGhost([]);this.renderer.domElement.removeEventListener('pointerdown',this.pointerDown);this.renderer.domElement.removeEventListener('pointerup',this.pointerUp);this.renderer.domElement.removeEventListener('pointermove',this.pointerMove);this.renderer.domElement.removeEventListener('pointerleave',this.pointerLeave);this.renderer.domElement.removeEventListener('webglcontextlost',this.contextLost);this.renderer.dispose();this.renderer.domElement.remove();}
}
