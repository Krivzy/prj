import {useEffect, useRef} from 'react';
import type {CSSProperties} from 'react';
import {mountPixelCursor} from './pixel-cursor-controller';
import type {PixelCursorController} from './pixel-cursor-controller';
import {CURSOR_ASSETS, pixelCursorImageReady} from './cursor-assets';
import type {PixelCursorState} from './cursor-assets';
import './AnimatedPixelCursor.css';

/** Exact original Cursors Extended textures; only its busy sprite is animated. */
export default function AnimatedPixelCursor() {
  const ref = useRef<HTMLDivElement>(null);
  const images = useRef(new Map<PixelCursorState, HTMLImageElement>());
  const controller = useRef<PixelCursorController | null>(null);
  useEffect(() => {
    if (!ref.current) return;
    const cursor = mountPixelCursor(ref.current, {window, document, assetReady: state => pixelCursorImageReady(images.current.get(state), state)});
    controller.current = cursor;
    // React's async buttons already expose real work through aria-busy. Attribute
    // notifications update a stationary cursor, without polling or an idle loop.
    const busy = new MutationObserver(() => cursor.refresh());
    busy.observe(document.documentElement, {attributes: true, subtree: true, attributeFilter: ['aria-busy']});
    return () => {busy.disconnect(); cursor(); controller.current = null;};
  }, []);
  return <div className="mcc-pixel-cursor-layer" aria-hidden="true"><div ref={ref} className="mcc-pixel-cursor" data-state="arrow" hidden>
    {(Object.entries(CURSOR_ASSETS) as Array<[PixelCursorState, typeof CURSOR_ASSETS[PixelCursorState]]>).map(([state, asset]) => <span key={state} className="mcc-cursor-image" data-shape={state}>
      <img ref={image => {if (image) images.current.set(state, image); else images.current.delete(state);}} src={asset.src} alt="" draggable={false} width={asset.width} height={asset.textureHeight} decoding="async" onLoad={() => controller.current?.refresh()} onError={() => controller.current?.refresh()} style={{'--ce-sheet-height': `${asset.textureHeight}px`, '--ce-duration': `${asset.frames * asset.frameMs}ms`, '--ce-frames': asset.frames} as CSSProperties}/>
    </span>)}
  </div></div>;
}
