import * as T from 'three';
import {assetUrl} from './assets';
import type {Manifest} from './assets';

export function createWorkbench(host:HTMLElement,assets:Manifest) {
  const renderer=new T.WebGLRenderer({antialias:true,alpha:true,powerPreference:'low-power'});
  renderer.setPixelRatio(Math.min(devicePixelRatio,1.6));
  renderer.outputColorSpace=T.SRGBColorSpace;
  renderer.setClearColor(0xffffff,0);
  renderer.domElement.setAttribute('aria-hidden','true');
  host.appendChild(renderer.domElement);
  const scene=new T.Scene();
  const camera=new T.OrthographicCamera(-6,6,4,-4,.1,80);
  camera.position.set(9,8,11);camera.lookAt(0,1,0);
  scene.add(new T.AmbientLight(0xffffff,2.5));
  const light=new T.DirectionalLight(0xfff3dc,3.2);light.position.set(-6,10,7);scene.add(light);
  const fill=new T.DirectionalLight(0x87d6e5,1.2);fill.position.set(6,4,-6);scene.add(fill);
  const geometries=new Set<T.BufferGeometry>();
  const materials=new Set<T.Material>();
  const textures=new Set<T.Texture>();
  const loader=new T.TextureLoader();
  const loaded=new Map<string,T.Texture>();
  let disposed=false;
  let visible=true;
  let manualPause=false;
  let raf=0,lastFrame=0,elapsed=6;
  const reduced=matchMedia('(prefers-reduced-motion: reduce)');
  function material(color:number,map?:T.Texture){const m=new T.MeshStandardMaterial({color,map,roughness:.9});materials.add(m);return m;}
  function texture(id:string){const url=assetUrl(assets,'blocks',id)??assetUrl(assets,'items',id);if(!url)return undefined;if(loaded.has(url))return loaded.get(url);const tex=loader.load(url,()=>{if(disposed){tex.dispose();return;}render();});tex.magFilter=T.NearestFilter;tex.minFilter=T.NearestFilter;tex.colorSpace=T.SRGBColorSpace;textures.add(tex);loaded.set(url,tex);return tex;}
  function mesh(w:number,h:number,d:number,mat:T.Material,parent:T.Object3D=scene){const geo=new T.BoxGeometry(w,h,d);geometries.add(geo);const obj=new T.Mesh(geo,mat);parent.add(obj);return obj;}
  const stone=material(0xb4c0ca,texture('stone_bricks'));
  const wood=material(0xffffff,texture('oak_planks'));
  const copper=material(0xd8753c,texture('copper_block'));
  const obsidian=material(0x303a46,texture('obsidian'));
  const grass=material(0xffffff,texture('moss_block'));
  const cyan=material(0x36ced9);
  const skin=material(0xe7ae80),shirt=material(0x159db3),pants=material(0x34434d),hair=material(0x55392b),boots=material(0x69564a),white=material(0xffffff),eye=material(0x24313c);
  const base=new T.Group();scene.add(base);base.position.y=.1;
  mesh(8,.35,5.5,stone,base).position.set(0,-.3,0);
  for(let x=-3;x<=3;x++)for(let z=-2;z<=2;z++){const tile=mesh(.98,.16,.98,(x===-3||z===-2)?grass:wood,base);tile.position.set(x,-.05,z);}
  const edge=mesh(8,.13,.14,copper,base);edge.position.set(0,-.28,2.83);
  for(const x of [-3.7,3.7]){mesh(.16,.65,5.5,copper,base).position.set(x,-.2,0);}
  // Portal is made from individual blocks; its shader moves inside the frame.
  const portal=new T.Group();portal.position.set(1.9,0,-1.35);scene.add(portal);
  for(let x=-1;x<=1;x++){mesh(.72,.72,.72,obsidian,portal).position.set(x*.72,.45,0);mesh(.72,.72,.72,obsidian,portal).position.set(x*.72,3.33,0);}
  for(let y=1;y<=3;y++)for(const x of [-1,1])mesh(.72,.72,.72,obsidian,portal).position.set(x*.72,.45+y*.72,0);
  const portalGeo=new T.PlaneGeometry(1.2,2.28,1,1);geometries.add(portalGeo);
  const portalMaterial=new T.ShaderMaterial({transparent:true,side:T.DoubleSide,uniforms:{time:{value:0}},vertexShader:'varying vec2 vUv; void main(){vUv=uv;gl_Position=projectionMatrix*modelViewMatrix*vec4(position,1.0);}',fragmentShader:'varying vec2 vUv; uniform float time; void main(){vec2 p=vUv-.5;float r=length(p*vec2(1.,.7));float a=atan(p.y,p.x);float swirl=sin(r*32.-a*3.-time*1.8)*.5+.5;float veins=pow(swirl,5.);vec3 c=mix(vec3(.035,.30,.38),vec3(.16,.83,.87),swirl);c+=veins*.25;float alpha=.82*(1.-smoothstep(.34,.72,r));gl_FragColor=vec4(c,alpha);}'});materials.add(portalMaterial);
  const gate=new T.Mesh(portalGeo,portalMaterial);gate.position.set(0,1.88,.38);portal.add(gate);
  // Separate pivots: head, upper body, left/right arms and left/right legs.
  const character=new T.Group();character.position.set(-2,0,.7);scene.add(character);
  const body=mesh(.68,.95,.38,shirt,character);body.position.y=1.65;
  const head=new T.Group();head.position.y=2.5;character.add(head);
  mesh(.7,.7,.7,skin,head);
  mesh(.72,.18,.72,hair,head).position.y=.29;
  mesh(.72,.5,.12,hair,head).position.set(0,.03,-.32);
  for(const x of [-.16,.16]){mesh(.15,.10,.015,white,head).position.set(x,.015,.36);mesh(.055,.10,.021,eye,head).position.set(x-.017,.015,.37);}
  mesh(.22,.045,.015,hair,head).position.set(0,-.18,.36);
  function limb(x:number,y:number,w:number,h:number,upper:T.Material,lower?:T.Material){const pivot=new T.Group();pivot.position.set(x,y,0);character.add(pivot);mesh(w,h,.34,upper,pivot).position.y=-h/2;if(lower)mesh(w+.01,.18,.37,lower,pivot).position.y=-h+.08;return pivot;}
  const leftArm=limb(-.51,2.04,.31,.94,skin),rightArm=limb(.51,2.04,.31,.94,skin);
  for(const arm of [leftArm,rightArm])mesh(.32,.32,.35,shirt,arm).position.y=-.16;
  const leftLeg=limb(-.19,1.17,.32,1.03,pants,boots),rightLeg=limb(.19,1.17,.32,1.03,pants,boots);
  const pickaxe=new T.Group();rightArm.add(pickaxe);pickaxe.position.set(0,-.78,.14);pickaxe.rotation.z=-2.6;
  mesh(.09,.78,.09,wood,pickaxe).position.y=.28;
  for(const p of [[-.27,.66],[0,.69],[.27,.66],[.39,.50]])mesh(.25,.15,.17,cyan,pickaxe).position.set(p[0],p[1],0);
  // Mining target and a build sequence. Blocks are actual separate meshes.
  const target=mesh(.8,.8,.8,copper);target.position.set(-.6,.48,1.5);
  const construction:T.Mesh[]=[];
  for(const p of [[-.7,.43,-1.6],[-1.5,.43,-1.6],[-1.5,1.23,-1.6],[-.7,1.23,-1.6],[-.7,2.03,-1.6]]){const b=mesh(.78,.78,.78,stone);b.position.set(...p as [number,number,number]);construction.push(b);}
  const debris:T.Mesh[]=[];
  for(let i=0;i<18;i++){const bit=mesh(.09,.09,.09,i%3?copper:cyan);debris.push(bit);}
  const sparkGeo=new T.BufferGeometry();geometries.add(sparkGeo);
  const positions=new Float32Array(90*3);for(let i=0;i<90;i++){positions[i*3]=1.9+Math.sin(i*2.4)*(.9+(i%6)*.09);positions[i*3+1]=.5+(i%29)*.11;positions[i*3+2]=-1.25+Math.cos(i*2.4)*.48;}
  sparkGeo.setAttribute('position',new T.BufferAttribute(positions,3));
  const sparkMat=new T.PointsMaterial({color:0x16b8cc,size:.055,transparent:true,opacity:.8});materials.add(sparkMat);const sparks=new T.Points(sparkGeo,sparkMat);scene.add(sparks);
  const resize=()=>{const w=Math.max(1,host.clientWidth),h=Math.max(1,host.clientHeight);renderer.setSize(w,h,false);const ratio=w/h;const span=host.clientWidth<550?4.3:3.6;camera.left=-span*ratio;camera.right=span*ratio;camera.top=span;camera.bottom=-span;camera.updateProjectionMatrix();render();};
  function pose(t:number){const phase=t%13;const walking=phase<3;const walk=walking?Math.sin(t*7)*.55:0;character.position.x=walking?-2.8+phase*.46:-1.42;character.position.z=.8;character.rotation.y=walking?1.05:-.2;leftLeg.rotation.x=walk;rightLeg.rotation.x=-walk;leftArm.rotation.x=-walk*.7;rightArm.rotation.x=walking?walk*.7:-.35-Math.max(0,Math.sin(t*5.7));head.rotation.y=walking?.08:Math.sin(t*.8)*.1;body.position.y=1.65+Math.abs(walk)*.025;target.scale.setScalar(phase>3&&phase<9?.9+Math.sin(t*5.7)*.07:1);construction.forEach((b,i)=>{const scale=phase>5+i*.5?1:.001;b.scale.setScalar(scale);});debris.forEach((b,i)=>{const p=(t*1.4+i*.055)%1;b.visible=!walking&&phase<9;b.position.set(-.6+Math.sin(i*2.3)*p*.65,.8+Math.sin(p*Math.PI)*.55,1.5+Math.cos(i*2.3)*p*.65);b.rotation.set(t+i,t*.7+i,0);b.scale.setScalar(1-p);});portalMaterial.uniforms.time.value=t;sparks.rotation.y=Math.sin(t*.5)*.04;sparkMat.opacity=.55+Math.sin(t*1.5)*.15;}
  function render(){if(disposed)return;pose(elapsed);renderer.render(scene,camera);}
  function run(now:number){raf=0;if(disposed||!visible||document.hidden||manualPause||reduced.matches)return;if(now-lastFrame>=1000/30){const delta=lastFrame?Math.min((now-lastFrame)/1000,.1):0;lastFrame=now;elapsed+=delta;render();}raf=requestAnimationFrame(run);}
  function sync(){cancelAnimationFrame(raf);raf=0;lastFrame=0;if(disposed)return;if(visible&&!document.hidden&&!manualPause&&!reduced.matches)raf=requestAnimationFrame(run);else render();}
  const observer=new IntersectionObserver(entries=>{visible=entries[0]?.isIntersecting??false;sync();},{threshold:.05});observer.observe(host);
  const resizeObserver=new ResizeObserver(resize);resizeObserver.observe(host);
  document.addEventListener('visibilitychange',sync);reduced.addEventListener('change',sync);
  resize();sync();
  return {setPaused(value:boolean){manualPause=value;sync();},dispose(){disposed=true;cancelAnimationFrame(raf);observer.disconnect();resizeObserver.disconnect();document.removeEventListener('visibilitychange',sync);reduced.removeEventListener('change',sync);geometries.forEach(g=>g.dispose());materials.forEach(m=>m.dispose());textures.forEach(t=>t.dispose());renderer.dispose();renderer.forceContextLoss();renderer.domElement.remove();scene.clear();}};
}
