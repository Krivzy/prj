import {memo,useId,useMemo} from 'react';
import {projectModel,textureTransform} from './BlockModel';
import type {BlockModel} from './BlockModel';
import {materialTint,tintMatrix} from './MaterialTint';
export default memo(function ModelIcon({model,size,className=''}:{model:BlockModel;size:number;className?:string}){
  const id=useId().replaceAll(':',''),faces=useMemo(()=>projectModel(model),[model]);
  if(!faces.length)return null;
  return <svg viewBox="0 0 64 64" width={size} height={size} className={`game-image model-icon ${className}`} aria-hidden="true" data-model-elements={model.elements.length}>
    <defs>{faces.map((face,i)=>{const tint=materialTint(face.texture,face.tintindex);return <g key={i}><clipPath id={`${id}-${i}`}><polygon points={face.points.map(p=>p.join(',')).join(' ')}/></clipPath>{tint&&<filter id={`${id}-tint-${i}`} colorInterpolationFilters="sRGB" x="0" y="0" width="100%" height="100%"><feColorMatrix type="matrix" values={tintMatrix(tint)}/></filter>}</g>;})}</defs>
    {faces.map((face,i)=><g key={i} clipPath={`url(#${id}-${i})`}><image href={face.texture} y={-face.frameIndex*16} width={16} height={face.textureHeight} transform={textureTransform(face)??undefined} preserveAspectRatio="none" filter={materialTint(face.texture,face.tintindex)?`url(#${id}-tint-${i})`:undefined} style={{imageRendering:'pixelated'}}/>{face.shade>0&&<polygon points={face.points.map(p=>p.join(',')).join(' ')} fill="#080f10" opacity={face.shade}/>}</g>)}
  </svg>;
});
