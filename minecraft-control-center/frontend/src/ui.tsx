import {cloneElement,isValidElement,useEffect,useId,useRef,useState} from 'react';
import type {ButtonHTMLAttributes, FormEvent, ReactNode} from 'react';
import {useApp} from './context';
import type {Catalogs, Item} from './types';
import {canExecute} from './policy';
import {licenseAllowsAction} from './license-view';
import {GameImage,useAssetName,useAssets,assetName} from './assets';
import WorkbenchScene from './WorkbenchScene';
import {CatalogPicker,CatalogInput} from './Catalog';

export function Icon({name, size = 20}: {name: string; size?: number}) {
  const paths: Record<string, ReactNode> = {
    users: <><circle cx="8" cy="7" r="3"/><path d="M2 21v-4a6 6 0 0 1 12 0v4M17 4a3 3 0 0 1 0 6m4 11v-4a6 6 0 0 0-3-5"/></>,
    user: <><circle cx="12" cy="7" r="4"/><path d="M4 22v-3a8 8 0 0 1 16 0v3"/></>,
    'arrow-right': <path d="M4 12h16m-6-6 6 6-6 6"/>,
    plus: <path d="M12 4v16M4 12h16"/>,
    shield: <><path d="m12 2 9 4v6c0 5-4 8-9 10-5-2-9-5-9-10V6l9-4Z"/><path d="m8 12 3 3 5-6"/></>,
    trash: <><path d="M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7m4-7v7"/></>,
    dashboard: <><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/></>,
    players: <><circle cx="9" cy="7" r="3"/><path d="M2 21v-4a7 7 0 0 1 14 0v4M16 4a3 3 0 0 1 0 6m3 11v-4a7 7 0 0 0-2-5"/></>,
    worlds: <><path d="m12 2 10 5v10l-10 5L2 17V7l10-5Zm0 10 10-5M12 12 2 7m10 5v10"/></>,
    mobs: <><path d="M4 8V4h5v4h6V4h5v16H4V8Z"/><rect x="7" y="10" width="2" height="2"/><rect x="15" y="10" width="2" height="2"/><path d="M10 15h4v3h-4z"/></>,
    events: <path d="m13 2-9 12h7l-1 8 10-12h-7l0-8Z"/>,
    console: <><rect x="2" y="4" width="20" height="16" rx="2"/><path d="m6 9 3 3-3 3m7 0h5"/></>,
    chat: <path d="M3 3h18v14H8l-5 4V3Zm4 5h10M7 12h7"/>,
    automations: <><path d="M12 3v6m0 6v6M3 12h6m6 0h6"/><rect x="9" y="9" width="6" height="6"/><circle cx="12" cy="3" r="2"/><circle cx="21" cy="12" r="2"/><circle cx="3" cy="12" r="2"/><circle cx="12" cy="21" r="2"/></>,
    backups: <><path d="M4 7h16v14H4V7ZM2 3h20v4H2V3Zm7 8h6"/></>,
    plugins: <path d="M4 4h6V2h4v2h6v6h2v4h-2v6h-6v2h-4v-2H4v-6H2v-4h2V4Z"/>,
    audit: <><path d="M5 2h14v20H5V2Zm4 5h6M9 12h6M9 17h4"/></>,
    ores: <><path d="m12 2 8 4v12l-8 4-8-4V6l8-4Zm0 0v20M4 6l8 5 8-5"/></>,
    luckperms: <><path d="m12 2 9 4v6c0 5-4 8-9 10-5-2-9-5-9-10V6l9-4Z"/><path d="m8 12 3 3 5-6"/></>,
    coreprotect: <><rect x="3" y="3" width="18" height="18"/><path d="M7 8h10M7 12h10M7 16h6"/></>,
    advancedban: <><circle cx="12" cy="12" r="9"/><path d="m6 6 12 12"/></>,
    settings: <><path d="m9 2-1 3-3 1-3 4 2 2-2 2 3 4 3 1 1 3h6l1-3 3-1 3-4-2-2 2-2-3-4-3-1-1-3H9Z"/><circle cx="12" cy="12" r="3"/></>,
    sun: <><circle cx="12" cy="12" r="4"/><path d="M12 1v3m0 16v3M1 12h3m16 0h3M4 4l2 2m12 12 2 2M4 20l2-2M18 6l2-2"/></>,
    moon: <path d="M20 16A10 10 0 0 1 8 3a10 10 0 1 0 12 13Z"/>,
    rain: <><path d="M5 14a4 4 0 1 1 1-8 6 6 0 0 1 11-1 4 4 0 1 1 2 9H5Z"/><path d="m7 17-2 4m8-4-2 4m8-4-2 4"/></>,
    heal: <path d="M9 3h6v6h6v6h-6v6H9v-6H3V9h6V3Z"/>,
    sword: <path d="m16 2 6 0v6L10 20l-6-6L16 2ZM3 21l4-4M2 12l10 10"/>,
    arrow: <path d="M4 12h16m-6-6 6 6-6 6"/>,
    close: <path d="m6 6 12 12M18 6 6 18"/>,
    refresh: <path d="M20 7v5h-5M4 17v-5h5M5 8a8 8 0 0 1 14-3l1 2M4 17l1 2a8 8 0 0 0 14-3"/>,
    logout: <><path d="M10 3H3v18h7m4-15 6 6-6 6M8 12h12"/></>,
    search: <><circle cx="10" cy="10" r="6"/><path d="m15 15 6 6"/></>,
    check: <path d="m4 12 5 5L20 6"/>,
    chevron: <path d="m9 5 7 7-7 7"/>,
    warning: <><path d="M12 2 1 22h22L12 2Z"/><path d="M12 9v5m0 4h.01"/></>,
  };
  return <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.65" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{paths[name] ?? paths.worlds}</svg>;
}

export function BlockIcon({variant = 'grass', size = 40}: {variant?: string; size?: number}) {
  const color = variant === 'nether' ? ['#a45750','#612d30','#7f3e3a'] : variant === 'end' ? ['#bb9ff8','#59437d','#8663b3'] : ['#6fb8ed','#264269','#41618f'];
  return <svg width={size} height={size} viewBox="0 0 40 44" aria-hidden="true" className="block-icon"><path d="m20 1 19 10v23L20 44 1 34V11Z" fill={color[2]}/><path d="m20 1 19 10-19 10L1 11Z" fill={color[0]}/><path d="m20 21 19-10v23L20 44Z" fill={color[1]}/><path d="m1 11 19 10v9l-5-3v-5l-4-2v5l-4-2v-6l-6-3Z" fill={color[0]}/><path d="m20 21 19-10v5l-5 3v4l-6 3v-4l-8 4Z" fill="#91c4ff" opacity=".6"/></svg>;
}

export function Island() {return <WorkbenchScene/>;}
export function ItemIcon({material='AIR',size=32}:{material?:string;size?:number}){return <GameImage id={material} size={size} fallback={<ItemGlyph material={material} size={size}/>}/>;}
function ItemGlyph({material = 'AIR', size = 32}: {material?: string; size?: number}) {
  const id = material.toUpperCase();
  const tool = /SWORD|AXE|SHOVEL|PICKAXE|HOE|TRIDENT/.test(id);
  const gem = /DIAMOND|EMERALD|AMETHYST|QUARTZ/.test(id);
  const color = /DIAMOND/.test(id) ? '#77d9d6' : /EMERALD/.test(id) ? '#7ad6a1' : /GOLD/.test(id) ? '#e6bf65' : /IRON/.test(id) ? '#bcc7c8' : /NETHERITE/.test(id) ? '#827778' : /AMETHYST/.test(id) ? '#b095d2' : '#7bb6fa';
  return <svg width={size} height={size} viewBox="0 0 32 32" fill="none" aria-hidden="true" className="item-icon">{tool ? <><path d="m5 27 13-13 4 4L9 31Z" fill="#947853"/><path d="m15 13 11-11h5v6L20 19Z" fill={color}/><path d="m12 16 5 5 3-3-5-5Z" fill="#556f61"/><path d="m20 11 8-8" stroke="#eff6e8" strokeWidth="2"/></> : gem ? <><path d="m8 3 16 0 6 9-14 18L2 12Z" fill={color}/><path d="m8 3 8 9 8-9M2 12h28M16 12v18" stroke="#fff" opacity=".35" strokeWidth="2"/></> : <><path d="m16 2 14 7v16l-14 7L2 25V9Z" fill={color}/><path d="m2 9 14 7 14-7M16 16v16" stroke="#13251e" opacity=".45" strokeWidth="2"/><path d="m6 7 10-5 10 5-10 5Z" fill="#fff" opacity=".15"/></>}</svg>;
}
export function Avatar({name, small = false}: {name: string; small?: boolean}) {
  const hue = [...name].reduce((x,c) => x+c.charCodeAt(0)*17, 0) % 360;
  return <div className={`avatar ${small ? 'small' : ''}`} style={{'--avatar-hue': hue} as React.CSSProperties} aria-hidden="true"><span/><i/><b/></div>;
}
export function Panel({children, className = ''}: {children: ReactNode; className?: string}) {return <section className={`panel ${className}`}>{children}</section>;}
export function Empty({title, children, icon = 'worlds'}: {title: string; children?: ReactNode; icon?: string}) {return <div className="empty"><Icon name={icon} size={34}/><strong>{title}</strong>{children && <p>{children}</p>}</div>;}
export function Loading() {const {t} = useApp(); return <div className="loading" role="status"><span className="pixel-loader"/>{t('Загружаем мир…', 'Loading your world…')}</div>;}
export function ErrorBox({error, retry}: {error: string; retry?: () => void}) {const {t} = useApp(); return <div className="error-box" role="alert"><Icon name="warning"/><span>{error}</span>{retry && <button onClick={retry}>{t('Повторить','Retry')}</button>}</div>;}
export function Button({children, className = '', ...props}: ButtonHTMLAttributes<HTMLButtonElement>) {return <button {...props} className={`button ${className}`}>{children}</button>;}
export function AsyncButton({run, children, className = '', disabled = false}: {run: () => Promise<unknown>; children: ReactNode; className?: string; disabled?: boolean}) {
  const [busy, setBusy] = useState(false); const {notify} = useApp();
  return <Button type="button" disabled={disabled || busy} className={className} aria-busy={busy} onClick={async () => {setBusy(true); try {await run();} catch(error) {notify(error instanceof Error ? error.message : String(error), true);} finally {setBusy(false);}}}>{busy ? <span className="spinner"/> : null}{children}</Button>;
}
export function ActionButton({action, params, danger, children, className = '', disabled = false}: {action: string; params: Record<string,unknown>; danger?: string; children: ReactNode; className?: string; disabled?: boolean}) {const app = useApp(); return <AsyncButton className={className} disabled={!canExecute(app.auth.user?.role??'Viewer',action)||!licenseAllowsAction(app.auth.license,action)||disabled} run={() => app.action(action,params,danger)}>{children}</AsyncButton>;}
export function Field({label, children, help}: {label: string; children: ReactNode; help?: string}) {const id=useId();const direct=isValidElement(children)&&typeof children.type==='string'&&['input','select','textarea'].includes(children.type);return <div className="field">{direct?<label htmlFor={id}>{label}</label>:<span>{label}</span>}{direct?cloneElement(children as React.ReactElement<{id?:string}>,{id}):children}{help&&<small>{help}</small>}</div>;}
const modalStack:string[]=[];let restoreOverflow='';
export function Modal({title, children, close, danger = false}: {title: string; children: ReactNode; close: () => void; danger?: boolean}) {
  const ref = useRef<HTMLDivElement>(null); const id = useId(); const {t} = useApp(); const closeRef = useRef(close); closeRef.current = close;
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null;
    const container = ref.current;
    const focusables = () => Array.from(container?.querySelectorAll<HTMLElement>('button,input,select,textarea,a[href],[tabindex]') ?? []).filter(element => element.tabIndex >= 0 && !element.matches(':disabled') && !element.closest('[hidden],[inert]') && element.getClientRects().length > 0 && getComputedStyle(element).visibility !== 'hidden');
    if(!modalStack.length)restoreOverflow=document.body.style.overflow;
    modalStack.push(id);(container?.querySelector<HTMLElement>('[data-autofocus]') ?? focusables()[0])?.focus();
    const handler = (event: KeyboardEvent) => {
      if(modalStack.at(-1)!==id)return;
      if(event.key === 'Escape') {event.preventDefault();closeRef.current();}
      if(event.key === 'Tab') {const items = focusables(); if(!items.length) return; const first = items[0], last = items[items.length-1]; if(event.shiftKey && document.activeElement === first) {event.preventDefault();last.focus();} else if(!event.shiftKey && document.activeElement === last) {event.preventDefault();first.focus();}}
    };
    document.addEventListener('keydown',handler);document.body.style.overflow='hidden';
    return () => {document.removeEventListener('keydown',handler);const index=modalStack.indexOf(id);if(index>=0)modalStack.splice(index,1);if(!modalStack.length)document.body.style.overflow=restoreOverflow;if(previous?.isConnected)previous.focus();};
  }, []);
  return <div className="modal-backdrop" onMouseDown={e => {if(e.target === e.currentTarget) close();}}><div ref={ref} className={`modal ${danger ? 'danger-modal' : ''}`} role="dialog" aria-modal="true" aria-labelledby={id}><header><h2 id={id}>{title}</h2><button className="icon-button" onClick={close} aria-label={t('Закрыть','Close')}><Icon name="close"/></button></header>{children}</div></div>;
}
export function ItemForm({catalogs, initial, save, button}: {catalogs: Catalogs | null; initial?: Item | null; save: (item: Item) => Promise<unknown>; button: string}) {
  const {t,lang,notify,administrator}=useApp();const [material,setMaterial]=useState(initial?.material??'DIAMOND_SWORD');const [amount,setAmount]=useState(initial?.amount??1);const [name,setName]=useState(initial?.name??'');const [lore,setLore]=useState(initial?.lore?.join('\n')??'');const [damage,setDamage]=useState(initial?.damage??0);const [unbreakable,setUnbreakable]=useState(initial?.unbreakable??false);const [enchantments,setEnchantments]=useState<Record<string,number>>(initial?.enchantments??{});const [advanced,setAdvanced]=useState(false);const [busy,setBusy]=useState(false);const [kind,setKind]=useState<'items'|'blocks'>('items');
  const assets=useAssets();const materialName=useAssetName('items',material,lang);const info=catalogs?.materials.find(m=>m.key===material);const max=info?.maxStackSize??64;
  async function submit(event:FormEvent){event.preventDefault();if(!info?.item){notify(t('Выберите доступный предмет','Choose an available item'),true);return;}setBusy(true);try{await save({material,amount:Math.min(amount,max),name,lore:lore?lore.split('\n'):[],damage,unbreakable,enchantments});}catch(error){notify(error instanceof Error?error.message:String(error),true);}finally{setBusy(false);}}
  return <form className="item-studio" onSubmit={submit}><div className="item-catalog"><div className="studio-heading"><h3>{t('Каталог Minecraft','Minecraft catalog')}</h3><div className="segmented"><button type="button" className={kind==='items'?'active':''} onClick={()=>setKind('items')}>{t('Предметы','Items')}</button><button type="button" className={kind==='blocks'?'active':''} onClick={()=>setKind('blocks')}>{t('Блоки','Blocks')}</button></div></div><CatalogPicker catalogs={catalogs} kind={kind} value={material} onChange={id=>{const target=catalogs?.materials.find(m=>m.key===id);if(target?.item){setMaterial(id);setAmount(x=>Math.min(x,target.maxStackSize));}else notify(t('Этот блок нельзя положить в инвентарь','This block cannot be placed in inventory'),true);}}/></div><div className="item-workbench"><div className="item-showcase"><div className="showcase-orbit"/><ItemIcon material={material} size={104}/><strong>{name||materialName}</strong><code>{material}</code></div><div className="amount-controls"><span>{t('Количество','Amount')}</span><button type="button" onClick={()=>setAmount(x=>Math.max(1,x-1))} disabled={amount<=1}>−</button><input aria-label={t('Количество','Amount')} type="number" required min={1} max={max} value={amount} onChange={e=>setAmount(Number(e.target.value))}/><button type="button" onClick={()=>setAmount(x=>Math.min(max,x+1))} disabled={amount>=max}>+</button><button type="button" className="stack-size" onClick={()=>setAmount(max)}>{max}</button></div><Field label={t('Имя предмета','Display name')}><input value={name} onChange={e=>setName(e.target.value)} placeholder={materialName} maxLength={120}/></Field><div className="enchant-workbench"><h3>{t('Зачарования','Enchantments')}</h3>{Object.entries(enchantments).map(([key,level])=><div className="enchant-row" key={key}><GameImage kind="enchantments" id={key} size={28}/><span>{assetName(assets,'enchantments',key,lang)}</span><input aria-label={`${key} level`} type="number" min={1} max={catalogs?.enchantments.find(e=>e.key===key)?.maxLevel??1} value={level} onChange={e=>setEnchantments({...enchantments,[key]:Number(e.target.value)})}/><button type="button" aria-label={t('Удалить зачарование','Remove enchantment')} onClick={()=>setEnchantments(Object.fromEntries(Object.entries(enchantments).filter(([id])=>id!==key)))}>×</button></div>)}<CatalogInput catalogs={catalogs} kind="enchantments" value="" label={t('Добавить зачарование','Add enchantment')} onChange={key=>setEnchantments({...enchantments,[key]:1})}/></div><button className="advanced-toggle" type="button" onClick={()=>setAdvanced(!advanced)} aria-expanded={advanced}>{t('Дополнительно','Advanced')}<span>{advanced?'−':'+'}</span></button>{advanced&&<div className="advanced-fields stack"><Field label={t('Описание','Lore')}><textarea value={lore} onChange={e=>setLore(e.target.value)} rows={3} maxLength={2000}/></Field><Field label={t('Износ','Damage')}><input type="number" min={0} max={100000} value={damage} onChange={e=>setDamage(Number(e.target.value))}/></Field><label className="toggle-field"><span>{t('Неразрушимый','Unbreakable')}</span><input type="checkbox" checked={unbreakable} onChange={e=>setUnbreakable(e.target.checked)}/></label></div>}<Button type="submit" className="primary item-save" disabled={!administrator||busy||!info?.item}>{busy&&<span className="spinner"/>}<Icon name="sword"/>{button}</Button></div></form>;
}
