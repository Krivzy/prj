import {useId,useState} from 'react';
import {useAssets} from './assets';
import './WorldEmblem.css';

type Material='grass'|'netherrack'|'end-stone';
type Face='top'|'left'|'right';

const faces:Record<Face,{path:string;matrix:string}>={
  top:{path:'M32 4 60 18 32 32 4 18Z',matrix:'matrix(1.75 .875 -1.75 .875 32 4)'},
  left:{path:'M4 18 32 32 32 64 4 50Z',matrix:'matrix(1.75 .875 0 2 4 18)'},
  right:{path:'M32 32 60 18 60 50 32 64Z',matrix:'matrix(1.75 -.875 0 2 32 32)'},
};

const materials:Record<Material,{id:string;top:string;side:string;fleck:string}>={
  grass:{id:'grass_block',top:'#669447',side:'#805b3d',fleck:'#aa7951'},
  netherrack:{id:'netherrack',top:'#924c48',side:'#85443f',fleck:'#b0655c'},
  'end-stone':{id:'end_stone',top:'#dedda1',side:'#c8c890',fleck:'#a8aa74'},
};

function localTexture(url:unknown):string|undefined {
  return typeof url==='string'&&/^\/game-assets\/textures\/block\/[a-z0-9_/-]+\.png$/.test(url)?url:undefined;
}

function FaceTexture({url,face,clip,tint}:{url?:string;face:Face;clip:string;tint?:string}) {
  const [failed,setFailed]=useState<string>();
  if(!url||failed===url)return null;
  return <g clipPath={`url(#${clip})`}><image href={url} width="16" height="16" preserveAspectRatio="none" transform={faces[face].matrix} filter={tint?`url(#${tint})`:undefined} onError={()=>setFailed(url)}/></g>;
}

/** Static 2D projection: all three faces share the same edge coordinates. */
export default function WorldEmblem({environment,size=42,className=''}:{environment:string;size?:number;className?:string}) {
  const prefix=`world-emblem-${useId().replace(/[^a-zA-Z0-9_-]/g,'')}`;
  const material:Material=environment==='NETHER'?'netherrack':environment==='THE_END'?'end-stone':'grass';
  const spec=materials[material];
  const assets=useAssets();
  const entry=assets.blocks?.[`minecraft:${spec.id}`]??assets.blocks?.[spec.id];
  const record=typeof entry==='object'?entry:undefined;
  const primary=localTexture(typeof entry==='string'?entry:record?.image??record?.url);
  const urls={top:localTexture(record?.faces?.up)??primary,left:localTexture(record?.faces?.north)??primary,right:localTexture(record?.faces?.east)??primary};
  return <svg className={`game-image world-emblem ${className}`} width={size} height={size} viewBox="0 0 64 72" aria-hidden="true" focusable="false" data-material={material}>
    <defs>
      {(['top','left','right'] as const).map(face=><clipPath key={face} id={`${prefix}-${face}`}><path d={faces[face].path}/></clipPath>)}
      <pattern id={`${prefix}-grain`} width="8" height="8" patternUnits="userSpaceOnUse"><rect width="8" height="8" fill={spec.side}/><path d="M0 1h3v2H0zm5 4h3v2H5z" fill={spec.fleck}/><path d="M2 6h2v2H2zm4-6h2v2H6z" fill="#000" opacity=".13"/></pattern>
      <pattern id={`${prefix}-top-grain`} width="8" height="8" patternUnits="userSpaceOnUse"><rect width="8" height="8" fill={spec.top}/><path d="M1 1h3v2H1zm4 4h2v3H5z" fill="#fff" opacity=".12"/><path d="M0 6h3v2H0zm5-6h3v2H5z" fill="#000" opacity=".1"/></pattern>
      <filter id={`${prefix}-grass-tint`} colorInterpolationFilters="sRGB" x="0" y="0" width="100%" height="100%"><feColorMatrix type="matrix" values=".58 0 0 0 0  0 .9 0 0 0  0 0 .39 0 0  0 0 0 1 0"/></filter>
    </defs>
    <path d="M32 49 61 63 32 71 3 63Z" fill="var(--world-emblem-shadow,#080d0a)" opacity=".3"/>
    <path d="M32 4 60 18 60 50 32 64 4 50 4 18Z" fill={spec.side}/>
    <path d={faces.left.path} fill={`url(#${prefix}-grain)`}/>
    <path d={faces.right.path} fill={`url(#${prefix}-grain)`}/>
    {material==='grass'&&<><path d="M4 18 32 32V38L28 36V39L22 36V33L17 30V33L11 30V27L4 24Z" fill={spec.top}/><path d="M32 32 60 18V24L54 27V30L49 32V29L43 32V35L37 38V35L32 38Z" fill={spec.top}/></>}
    <path d={faces.top.path} fill={`url(#${prefix}-top-grain)`}/>
    <FaceTexture face="left" url={urls.left} clip={`${prefix}-left`}/>
    <FaceTexture face="right" url={urls.right} clip={`${prefix}-right`}/>
    <FaceTexture face="top" url={urls.top} clip={`${prefix}-top`} tint={material==='grass'?`${prefix}-grass-tint`:undefined}/>
    <path d={faces.left.path} fill="#000" opacity=".1"/>
    <path d={faces.right.path} fill="#000" opacity=".27"/>
    <path d="M4 18 32 32 60 18M32 32V64" fill="none" stroke="#000" strokeOpacity=".17" strokeWidth=".65" strokeLinejoin="round"/>
    <path d="M4 18 32 4 60 18" fill="none" stroke="#fff" strokeOpacity=".16" strokeWidth=".55" strokeLinejoin="round"/>
  </svg>;
}
