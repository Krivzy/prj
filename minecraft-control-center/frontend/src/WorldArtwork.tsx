import {useEffect,useId,useState} from 'react';
import './WorldArtwork.css';

type Dimension='overworld'|'nether'|'end';
function Island({top,left,right}:{top:string;left:string;right:string}) {
  return <><path d="M110 70 182 106 110 142 38 106Z" fill={top}/><path d="M38 106 110 142V159L38 123Z" fill={left}/><path d="M110 142 182 106V123L110 159Z" fill={right}/><path d="M38 106 110 142 182 106" fill="none" stroke="#ffffff" strokeOpacity=".13"/></>;
}
function Crown({x,y,scale=1}:{x:number;y:number;scale?:number}) {
  return <g transform={`translate(${x} ${y}) scale(${scale})`}><path d="M0 0 30 15 0 30-30-15Z" fill="#78ab62"/><path d="M-30 15 0 30V52L-30 37Z" fill="#457847"/><path d="M0 30 30 15V37L0 52Z" fill="#2e563d"/><path d="M-20 15-6 8 8 15-6 22Z" fill="#8dbb70" opacity=".6"/></g>;
}
/** Three small vector scenes; animation belongs to CSS, with no rendering loop. */
export default function WorldArtwork({environment,className=''}:{environment:string;className?:string}) {
  const dimension:Dimension=environment==='NETHER'?'nether':environment==='THE_END'?'end':'overworld';
  const id=`world-art-${useId().replace(/[^a-zA-Z0-9_-]/g,'')}`;
  const [hidden,setHidden]=useState(()=>typeof document!=='undefined'&&document.hidden);
  useEffect(()=>{const update=()=>setHidden(document.hidden);document.addEventListener('visibilitychange',update);update();return()=>document.removeEventListener('visibilitychange',update);},[]);
  return <svg className={`world-artwork world-artwork--${dimension} ${className}`} viewBox="0 0 220 182" width="220" height="182" aria-hidden="true" focusable="false" data-paused={hidden}>
    <defs><radialGradient id={`${id}-aura`}><stop stopColor="currentColor" stopOpacity=".16"/><stop offset="1" stopColor="currentColor" stopOpacity="0"/></radialGradient></defs>
    <ellipse cx="110" cy="88" rx="103" ry="84" fill={`url(#${id}-aura)`}/>
    <ellipse cx="110" cy="168" rx="65" ry="9" fill="#05090a" opacity=".35"/>
    {dimension==='overworld'?<>
      <Island top="#679b55" left="#72563e" right="#503e32"/>
      <path d="M116 93 145 107 121 119 100 108Z" fill="#478b9d"/><path d="M119 96 138 105 121 114 106 107Z" fill="#72b0b0"/>
      <path d="m53 109 13 6v10l-13-6Zm23 16 15 7v8l-15-7Z" fill="#967047"/><path d="m144 127 14-7v12l-14 7Z" fill="#40534a"/>
      <path d="M87 67 98 61 109 67V105L98 111 87 105Z" fill="#785b3b"/><path d="M98 73 109 67V105L98 111Z" fill="#493d2d"/>
      <Crown x={98} y={31}/><Crown x={78} y={46} scale={.65}/><Crown x={116} y={48} scale={.7}/>
      <path d="m57 91 6-3 6 3-6 3Zm92 30 7-3 6 3-6 3Z" fill="#91bd68"/>
      <g className="world-artwork__motes"><path d="M150 61h3v3h-3ZM64 50h3v3h-3Z" fill="#c4de92"/></g>
    </>:dimension==='nether'?<>
      <Island top="#563638" left="#603332" right="#38292d"/>
      <path d="M79 88 117 98 160 113 130 128 102 110 63 104Z" fill="#d86d31"/><path d="M87 94 117 103 149 113 132 121 105 105 74 103Z" fill="#ffc271"/>
      <path d="M132 127 145 120V147L132 154Z" fill="#e7843c"/><path d="M136 129 141 126V144L136 146Z" fill="#ffc376"/>
      <g><path d="M65 45 81 37 97 45 81 53Z" fill="#6d6262"/><path d="M65 45 81 53V104L65 96Z" fill="#3d393f"/><path d="M81 53 97 45V96L81 104Z" fill="#27272f"/><path d="M118 63 132 56 146 63 132 70Z" fill="#625a60"/><path d="M118 63 132 70V101L118 94Z" fill="#3e343c"/><path d="M132 70 146 63V94L132 101Z" fill="#25272d"/><path d="M70 52 76 55V93L70 90Z" fill="#817574" opacity=".35"/></g>
      <g className="world-artwork__embers" fill="#ffc58b"><path d="M105 68h3v4h-3ZM153 78h3v3h-3ZM56 69h2v3h-2Z"/></g>
      <path d="m153 99 10 5-9 5-10-5Z" fill="#9c4642"/>
    </>:<>
      <Island top="#cac7a0" left="#989776" right="#747b69"/>
      <path d="m49 107 10 5 11-5-10-5Zm93 16 14-7 9 4-14 8Z" fill="#e5dfb2"/>
      <path d="M87 105 110 93 133 105 110 117Z" fill="#44434f"/><path d="M87 105 110 117V130L87 118Z" fill="#242632"/><path d="M110 117 133 105V118L110 130Z" fill="#151d27"/>
      <g className="world-artwork__crystal"><path d="M110 22 132 54 110 91 88 54Z" fill="#c4a3ee"/><path d="M110 22V91L88 54Z" fill="#9362cc"/><path d="M110 22 132 54 110 59Z" fill="#ead2ff"/><path d="M110 59 132 54 110 91Z" fill="#ba83e9"/><path d="M110 28 110 85" fill="none" stroke="#f4e5ff" strokeOpacity=".48"/><path d="M110 15 139 54 110 99 81 54Z" fill="none" stroke="#c6a0ef" strokeOpacity=".38"/></g>
      <g className="world-artwork__motes" fill="#d9baf4"><path d="M57 59h3v3h-3ZM161 49h3v3h-3ZM148 78h2v2h-2Z"/></g>
    </>}
  </svg>;
}
