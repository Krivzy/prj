import {useEffect,useRef,useState} from 'react';
import {useAssets} from './assets';

/** Original decorative character rig. Server terrain lives in MapPage. */
export default function WorkbenchScene({compact=false,className='',language='ru'}:{compact?:boolean;className?:string;language?:'ru'|'en'}) {
  const host=useRef<HTMLDivElement>(null);
  const controller=useRef<{dispose:()=>void;setPaused:(paused:boolean)=>void}|null>(null);
  const assets=useAssets();
  const [failed,setFailed]=useState(false);
  const [paused,setPaused]=useState(false);
  const pausedRef=useRef(paused);pausedRef.current=paused;
  useEffect(()=>{
    let active=true;
    const element=host.current;
    if(!element)return;
    void import('./workbench3d').then(({createWorkbench})=>{
      if(!active)return;
      controller.current=createWorkbench(element,assets);
      controller.current.setPaused(pausedRef.current);
    }).catch(()=>{if(active)setFailed(true);});
    return()=>{active=false;controller.current?.dispose();controller.current=null;};
  },[assets]);
  useEffect(()=>controller.current?.setPaused(paused),[paused]);
  const ru=language==='ru';
  return <div className={`workbench-scene ${compact?'compact':''} ${className}`}>
    <div className="scene-canvas" ref={host} role="img" aria-label={ru?'3D-верстак: персонаж добывает блоки и строит портал':'3D workbench: a character mines blocks and builds a portal'}/>
    {failed&&<div className="scene-unavailable"><span className="static-craft-cube"/><small>{ru?'3D недоступно в этом браузере':'3D unavailable in this browser'}</small></div>}
    {!failed&&<button type="button" className="scene-motion" onClick={()=>setPaused(x=>!x)} aria-label={paused?(ru?'Продолжить анимацию':'Resume animation'):(ru?'Приостановить анимацию':'Pause animation')} aria-pressed={paused}><span aria-hidden="true">{paused?'▶':'Ⅱ'}</span></button>}
    <span className="scene-stamp" aria-hidden="true">MCC / WORKBENCH</span>
  </div>;
}
