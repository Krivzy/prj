export type Vec3={x:number;y:number;z:number};
export type Bounds={min:Vec3;max:Vec3};
export type Selection={point:Vec3;normal:Vec3;blockData:string;material:string};
export function normalizedBounds(a:Vec3,b:Vec3):Bounds{return{min:{x:Math.min(a.x,b.x),y:Math.min(a.y,b.y),z:Math.min(a.z,b.z)},max:{x:Math.max(a.x,b.x),y:Math.max(a.y,b.y),z:Math.max(a.z,b.z)}};}
export function volume(bounds:Bounds){const{min:a,max:b}=bounds;return(b.x-a.x+1)*(b.y-a.y+1)*(b.z-a.z+1);}
export function adjacent(selection:Selection):Vec3{return{x:selection.point.x+selection.normal.x,y:selection.point.y+selection.normal.y,z:selection.point.z+selection.normal.z};}
export function sampleIndex(origin:Vec3,size:number,point:Vec3):number|undefined{const x=point.x-origin.x,y=point.y-origin.y,z=point.z-origin.z;if(x<0||y<0||z<0||x>=size||y>=size||z>=size)return undefined;return x+size*(z+size*y);}
