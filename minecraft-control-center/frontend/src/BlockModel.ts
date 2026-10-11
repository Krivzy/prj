export type Point3=[number,number,number];
export type ModelFace={texture:string;uv?:[number,number,number,number];rotation?:number;textureHeight?:number;frameIndex?:number};
export type ModelElement={from:Point3;to:Point3;faces:Record<string,ModelFace>;rotation?:{origin:Point3;axis:'x'|'y'|'z';angle:number;rescale?:boolean}};
export type BlockModel={elements:ModelElement[]};
export type ProjectedFace={points:[number,number][];uv:[number,number][];texture:string;shade:number;depth:number;textureHeight:number;frameIndex:number};
const validPoint=(p:Point3)=>Array.isArray(p)&&p.length===3&&p.every(n=>Number.isFinite(n)&&Math.abs(n)<=64);
const project=([x,y,z]:Point3):[number,number]=>[(x+z)*Math.SQRT1_2,(x-z)*.3535533905932738-y*.8660254037844386];
function rotate(p:Point3,r:ModelElement['rotation']):Point3{
  if(!r)return p;const indices=r.axis==='x'?[1,2]:r.axis==='y'?[2,0]:[0,1];
  const a=indices[0],b=indices[1],v=p.map((n,i)=>n-r.origin[i]) as Point3,rad=r.angle*Math.PI/180,c=Math.cos(rad),s=Math.sin(rad);
  const out=[...v] as Point3;out[a]=v[a]*c-v[b]*s;out[b]=v[a]*s+v[b]*c;
  if(r.rescale&&Math.abs(c)>.5){out[a]/=Math.abs(c);out[b]/=Math.abs(c);}
  return out.map((n,i)=>n+r.origin[i]) as Point3;
}
/** Static bounded geometry. No WebGL contexts, timers, server calls, or name-based shape guesses. */
export function projectModel(model:BlockModel):ProjectedFace[]{
  if(!Array.isArray(model?.elements)||model.elements.length>32)return [];
  const faces:ProjectedFace[]=[];
  for(const element of model.elements){
    if(!validPoint(element.from)||!validPoint(element.to)||!element.faces)continue;
    if(element.rotation&&(!validPoint(element.rotation.origin)||!['x','y','z'].includes(element.rotation.axis)||!Number.isFinite(element.rotation.angle)||Math.abs(element.rotation.angle)>45))continue;
    const [x0,y0,z0]=element.from,[x1,y1,z1]=element.to;
    if(x1<x0||y1<y0||z1<z0)continue;
    const shapes:{name:string;points:Point3[];uv:[number,number,number,number];shade:number}[]=[
      {name:'north',points:[[x1,y1,z0],[x0,y1,z0],[x0,y0,z0],[x1,y0,z0]],uv:[16-x1,16-y1,16-x0,16-y0],shade:.19},
      {name:'east',points:[[x1,y1,z1],[x1,y1,z0],[x1,y0,z0],[x1,y0,z1]],uv:[16-z1,16-y1,16-z0,16-y0],shade:.34},
      {name:'up',points:[[x0,y1,z0],[x1,y1,z0],[x1,y1,z1],[x0,y1,z1]],uv:[x0,z0,x1,z1],shade:0},
    ];
    for(const shape of shapes){
      const face=element.faces[shape.name];if(!face?.texture?.startsWith('/game-assets/textures/')||face.texture.includes('..'))continue;
      const corners=shape.points.map(p=>rotate(p,element.rotation)),points=corners.map(project);
      const area=Math.abs(points.reduce((sum,p,i)=>sum+p[0]*points[(i+1)%4][1]-p[1]*points[(i+1)%4][0],0));if(area<.001)continue;
      const bounds=face.uv??shape.uv;if(bounds.length!==4||!bounds.every(n=>Number.isFinite(n)&&Math.abs(n)<=64))continue;
      const [u0,v0,u1,v1]=bounds;let uv:[number,number][]=[[u0,v0],[u1,v0],[u1,v1],[u0,v1]];
      const turns=((face.rotation??0)/90)%4;if(!Number.isInteger(turns))continue;
      uv=uv.map((_,i)=>uv[(i+turns+4)%4]);
      const textureHeight=face.textureHeight??16,frameIndex=face.frameIndex??0;
      if(!Number.isFinite(textureHeight)||textureHeight<16||textureHeight>16384||!Number.isInteger(frameIndex)||frameIndex<0||frameIndex*16>=textureHeight)continue;
      faces.push({points,uv,texture:face.texture,shade:shape.shade,depth:corners.reduce((sum,[x,y,z])=>sum+x+y-z,0)/4,textureHeight,frameIndex});
    }
  }
  if(!faces.length)return [];
  // Keep a common block scale: a half slab stays half-height, rather than expanding to a cube.
  const base=[project([0,0,0]),project([16,0,0]),project([0,0,16]),project([16,16,0]),project([0,16,16]),project([16,0,16])];
  const all=[...base,...faces.flatMap(face=>face.points)],xs=all.map(p=>p[0]),ys=all.map(p=>p[1]);
  const minX=Math.min(...xs),maxX=Math.max(...xs),minY=Math.min(...ys),maxY=Math.max(...ys),scale=56/Math.max(maxX-minX,maxY-minY);
  const dx=(64-(maxX-minX)*scale)/2,dy=(64-(maxY-minY)*scale)/2;
  return faces.sort((a,b)=>a.depth-b.depth).map(face=>({...face,points:face.points.map(([x,y])=>[(x-minX)*scale+dx,(y-minY)*scale+dy])}));
}
/** Map the 16×16 texture plane onto a projected face using three corresponding corners. */
export function textureTransform(face:ProjectedFace):string|null{
  const [q0,q1,q2]=face.uv,[p0,p1,p2]=face.points;
  const ux=q1[0]-q0[0],uy=q1[1]-q0[1],vx=q2[0]-q0[0],vy=q2[1]-q0[1],det=ux*vy-uy*vx;if(Math.abs(det)<.00001)return null;
  const sx=p1[0]-p0[0],sy=p1[1]-p0[1],tx=p2[0]-p0[0],ty=p2[1]-p0[1];
  const a=(sx*vy-tx*uy)/det,c=(tx*ux-sx*vx)/det,b=(sy*vy-ty*uy)/det,d=(ty*ux-sy*vx)/det;
  return `matrix(${a} ${b} ${c} ${d} ${p0[0]-a*q0[0]-c*q0[1]} ${p0[1]-b*q0[0]-d*q0[1]})`;
}
