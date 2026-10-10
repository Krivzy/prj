import {useEffect} from 'react';
import {easeScroll,wheelDistance} from './motion';

export function useWorkbenchMotion(route:string) {
  useEffect(()=>{
    const reduced=matchMedia('(prefers-reduced-motion: reduce)');
    if(reduced.matches||!('IntersectionObserver' in window))return;
    const seen=new WeakSet<Element>();
    const observer=new IntersectionObserver(entries=>entries.forEach(entry=>{if(entry.isIntersecting){entry.target.classList.add('revealed');observer.unobserve(entry.target);}}),{threshold:.04,rootMargin:'0px 0px -12px'});
    function scan(){document.querySelectorAll('.main-content .panel, [data-reveal]').forEach(node=>{if(seen.has(node))return;seen.add(node);node.classList.add('will-reveal');observer.observe(node);});}
    scan();
    const mutation=new MutationObserver(scan);mutation.observe(document.body,{childList:true,subtree:true});
    const disable=()=>{if(reduced.matches){document.querySelectorAll('.will-reveal').forEach(node=>node.classList.add('revealed'));observer.disconnect();}};
    reduced.addEventListener('change',disable);
    return()=>{observer.disconnect();mutation.disconnect();reduced.removeEventListener('change',disable);};
  },[route]);
  useEffect(()=>{
    const reduced=matchMedia('(prefers-reduced-motion: reduce)');
    let raf=0,target=0;
    const stop=()=>{cancelAnimationFrame(raf);raf=0;};
    function frame(){const next=easeScroll(window.scrollY,target);window.scrollTo({top:next,behavior:'instant'});if(next!==target&&!document.hidden&&!reduced.matches)raf=requestAnimationFrame(frame);else raf=0;}
    function wheel(event:WheelEvent){
      if(reduced.matches||document.hidden||event.ctrlKey||event.metaKey||event.shiftKey||Math.abs(event.deltaX)>Math.abs(event.deltaY)||document.body.style.overflow==='hidden')return;
      // Trackpads keep native precision; only coarse mouse-wheel input is eased.
      if(event.deltaMode===0&&Math.abs(event.deltaY)<40)return;
      const element=event.target instanceof Element?event.target:null;
      if(element?.closest('input,textarea,select,[role="dialog"],[data-smooth-scroll="off"],.react-flow,.map-viewport'))return;
      for(let node=element?.parentElement;node&&node!==document.body;node=node.parentElement){const style=getComputedStyle(node);if(/auto|scroll/.test(style.overflowY)&&node.scrollHeight>node.clientHeight+2)return;}
      const max=document.documentElement.scrollHeight-innerHeight;if(max<=0)return;
      event.preventDefault();
      target=Math.max(0,Math.min(max,(raf?target:window.scrollY)+wheelDistance(event.deltaY,event.deltaMode,innerHeight)));
      if(!raf)raf=requestAnimationFrame(frame);
    }
    window.addEventListener('wheel',wheel,{passive:false});window.addEventListener('pointerdown',stop);window.addEventListener('keydown',stop);window.addEventListener('touchstart',stop,{passive:true});document.addEventListener('visibilitychange',stop);reduced.addEventListener('change',stop);
    return()=>{stop();window.removeEventListener('wheel',wheel);window.removeEventListener('pointerdown',stop);window.removeEventListener('keydown',stop);window.removeEventListener('touchstart',stop);document.removeEventListener('visibilitychange',stop);reduced.removeEventListener('change',stop);};
  },[route]);
}
