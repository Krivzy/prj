import {useEffect,useId,useMemo,useRef,useState} from 'react';
import {postTracked} from './api';
import {useApp} from './context';
import {Button,Empty,ErrorBox,Field,Icon} from './ui';
import './LuckPermsEditor.css';

type Node={key:string;value:boolean;expiresAt:number;contexts:Record<string,string|string[]>;nodeType:string};
type Holder={type:string;name:string;target:string;nodes:Node[];revision:string;truncated:boolean;primaryGroup?:string};
type Member={name:string;target:string;primaryGroup?:string};
type Suggestion={key:string;description:string;source:string};
type Tab='permission'|'inheritance'|'metadata';
const message=(e:unknown)=>e instanceof Error?e.message:String(e);

/** The familiar holder list → permission table → add-node flow, backed by the public API. */
export default function LuckPermsEditor(){
  const app=useApp(),suggestionId=useId();
  const [type,setType]=useState('group'),[members,setMembers]=useState<Member[]>([]),[knownGroups,setKnownGroups]=useState<Member[]>([]),[memberFilter,setMemberFilter]=useState(''),[lookup,setLookup]=useState('');
  const [holder,setHolder]=useState<Holder|null>(null),[tab,setTab]=useState<Tab>('permission'),[filter,setFilter]=useState('');
  const [busy,setBusy]=useState(false),[error,setError]=useState(''),[key,setKey]=useState(''),[allow,setAllow]=useState(true),[expires,setExpires]=useState('');
  const [contexts,setContexts]=useState<{key:string;value:string}[]>([]),[advanced,setAdvanced]=useState(false),[create,setCreate]=useState(false),[group,setGroup]=useState('');
  const [suggestions,setSuggestions]=useState<Suggestion[]>([]),[suggestionsOpen,setSuggestionsOpen]=useState(false),[activeSuggestion,setActiveSuggestion]=useState(-1),[suggestionError,setSuggestionError]=useState('');
  const alive=useRef(true),generation=useRef(0),locked=useRef(false);
  const path='/api/integrations/luckperms';
  useEffect(()=>{alive.current=true;return ()=>{alive.current=false;generation.current++;};},[]);
  async function list(nextType=type){
    if(locked.current)return;locked.current=true;setBusy(true);setError('');
    try{const data=await postTracked<{items:Member[]}>(`${path}/query`,{action:nextType==='group'?'groups':'users'});if(alive.current){setMembers(data.items);if(nextType==='group')setKnownGroups(data.items);}}
    catch(e){if(alive.current)setError(message(e));}finally{locked.current=false;if(alive.current)setBusy(false);}
  }
  useEffect(()=>{void list(type);},[type]);
  async function open(target:string){
    if(locked.current)return;locked.current=true;setBusy(true);setError('');
    try{const data=await postTracked<Holder>(`${path}/query`,{action:'holder',holderType:type,target});if(alive.current){setHolder(data);setFilter('');setKey('');setContexts([]);setExpires('');setAdvanced(false);}}
    catch(e){if(alive.current)setError(message(e));}finally{locked.current=false;if(alive.current)setBusy(false);}
  }
  async function write(body:Record<string,unknown>,confirmation:string){
    if(locked.current||!app.owner||!await app.confirm(confirmation)||!alive.current)return false;
    locked.current=true;setBusy(true);setError('');
    try{const data=await postTracked<{holder?:Holder}>(`${path}/mutate`,{...body,confirmed:true});if(!alive.current)return false;if(data.holder)setHolder(data.holder);app.notify(app.t('LuckPerms сохранил изменения','LuckPerms saved the changes'));return true;}
    catch(e){if(alive.current)setError(message(e));return false;}finally{locked.current=false;if(alive.current)setBusy(false);}
  }
  async function edit(action:string,node?:Node){
    if(!holder)return;const parsed:Record<string,string>={};
    try{
      if(node){for(const [k,v] of Object.entries(node.contexts)){if(typeof v!=='string')throw new Error(app.t('Многозначный контекст доступен только для просмотра','Multi-value contexts are read-only'));parsed[k]=v;}}
      else for(const row of contexts){if(!row.key.trim()&&!row.value.trim())continue;if(!row.key.trim()||!row.value.trim()||Object.hasOwn(parsed,row.key.trim()))throw new Error(app.t('Укажите уникальный ключ и значение каждого контекста','Enter a unique key and a value for every context'));parsed[row.key.trim()]=row.value.trim();}
      const expiry=node?.expiresAt??(expires?Math.floor(new Date(expires).getTime()/1000):0);
      if(!Number.isFinite(expiry)||!node&&expiry>0&&expiry<=Date.now()/1000)throw new Error(app.t('Дата окончания должна быть в будущем','Expiry must be in the future'));
      const ok=await write({action,holderType:holder.type,target:holder.target,expectedRevision:holder.revision,nodeType:node?.nodeType??tab,key:node?(node.nodeType==='inheritance'?node.key.slice(6):node.key):key.trim(),value:node?.value??allow,contexts:parsed,expiresAt:expiry},app.t(`Сохранить изменение прав для ${holder.name}?`,`Save this permission change for ${holder.name}?`));
      if(ok&&!node){setKey('');setSuggestionsOpen(false);}
    }catch(e){setError(message(e));}
  }
  useEffect(()=>{
    const id=++generation.current;setActiveSuggestion(-1);setSuggestionError('');
    if(!suggestionsOpen||!key.trim()||tab==='metadata'){setSuggestions([]);return;}
    const timeout=window.setTimeout(()=>{
      const request=tab==='inheritance'
        ? Promise.resolve({items:knownGroups.filter(m=>m.target.toLowerCase().includes(key.toLowerCase())).slice(0,20).map(m=>({key:m.target,description:app.t('Наследовать права этой группы','Inherit permissions from this group'),source:'LuckPerms'}))})
        :postTracked<{items:Suggestion[]}>(`${path}/query`,{action:'suggestions',search:key.trim(),limit:20});
      void request.then(data=>{if(alive.current&&generation.current===id){
        const result=new Map(data.items.map(item=>[item.key,item]));
        for(const node of holder?.nodes??[]){const nodeKey=tab==='inheritance'?node.key.slice(6):node.key;if(node.nodeType===tab&&nodeKey.toLowerCase().includes(key.toLowerCase())&&!result.has(nodeKey))result.set(nodeKey,{key:nodeKey,description:app.t('Уже назначено этому объекту','Already assigned to this holder'),source:'LuckPerms'});}
        setSuggestions([...result.values()].slice(0,20));
      }}).catch(e=>{if(alive.current&&generation.current===id){setSuggestions([]);setSuggestionError(message(e));}});
    },200);
    return ()=>window.clearTimeout(timeout);
  },[key,tab,suggestionsOpen,holder,knownGroups,app.lang]);
  const visibleNodes=useMemo(()=>holder?.nodes.filter(node=>node.nodeType===tab&&node.key.toLowerCase().includes(filter.toLowerCase()))??[],[holder,tab,filter]);
  const filteredMembers=members.filter(member=>`${member.name} ${member.target}`.toLowerCase().includes(memberFilter.toLowerCase()));
  const editable=app.owner&&!!holder?.revision&&!holder.truncated;
  function choose(suggestion:Suggestion){setKey(suggestion.key);setSuggestionsOpen(false);setActiveSuggestion(-1);}
  return <div className="lp-editor" aria-busy={busy}>
    {error&&<div className="lp-error"><ErrorBox error={error}/></div>}
    <aside className="lp-sidebar" aria-label={app.t('Группы и игроки LuckPerms','LuckPerms groups and users')}>
      <div className="lp-holder-tabs" role="tablist" aria-label={app.t('Тип объекта','Holder type')}>
        {['group','user'].map(value=><button key={value} role="tab" aria-selected={type===value} disabled={busy} onClick={()=>{setType(value);setHolder(null);setMembers([]);setTab('permission');setLookup('');}}><Icon name={value==='group'?'users':'user'}/>{value==='group'?app.t('Группы','Groups'):app.t('Игроки','Users')}</button>)}
      </div>
      <label className="lp-search"><span className="sr-only">{app.t('Поиск в списке','Search the list')}</span><Icon name="search"/><input value={memberFilter} onChange={e=>setMemberFilter(e.target.value)} placeholder={app.t('Найти в списке…','Find in list…')}/></label>
      <div className="lp-members">{filteredMembers.map(member=><button className={holder?.target===member.target?'selected':''} key={member.target} disabled={busy} onClick={()=>void open(member.target)}><span className="lp-holder-mark"><Icon name={type==='group'?'users':'user'}/></span><span><strong>{member.name||member.target}</strong><small>{type==='user'?member.primaryGroup||member.target:app.t('Группа прав','Permission group')}</small></span><Icon name="arrow-right"/></button>)}
        {!busy&&!filteredMembers.length&&<p className="lp-hint">{app.t('В списке пока нет объектов','No holders in this list yet')}</p>}
      </div>
      <form className="lp-lookup" onSubmit={e=>{e.preventDefault();void open(lookup.trim());}}><Field label={type==='user'?app.t('Открыть игрока по имени или UUID','Open a player by name or UUID'):app.t('Открыть группу по имени','Open a group by name')}><input required maxLength={64} value={lookup} onChange={e=>setLookup(e.target.value)} placeholder={type==='user'?'Steve / UUID':'default'} autoComplete="off"/></Field><Button type="submit" disabled={busy}><Icon name="arrow-right"/>{app.t('Открыть','Open')}</Button></form>
      {type==='user'&&<p className="lp-hint">{app.t('В списке — загруженные LuckPerms игроки. Остальных можно найти по имени или UUID.','The list shows users loaded by LuckPerms. Find other users by name or UUID.')}</p>}
      <div className="lp-sidebar-actions"><Button disabled={busy} onClick={()=>void list()}><Icon name="refresh"/>{app.t('Обновить','Refresh')}</Button>{app.owner&&type==='group'&&<Button disabled={busy} onClick={()=>setCreate(!create)}><Icon name="plus"/>{app.t('Группа','Group')}</Button>}</div>
      {create&&type==='group'&&<form className="lp-create" onSubmit={async e=>{e.preventDefault();if(await write({action:'createGroup',target:group},app.t(`Создать группу ${group}?`,`Create group ${group}?`))){setCreate(false);setGroup('');void list();}}}><Field label={app.t('Имя новой группы','New group name')}><input required pattern="[a-z0-9_-]{1,64}" maxLength={64} value={group} onChange={e=>setGroup(e.target.value)} placeholder="builders"/></Field><Button type="submit" disabled={busy}>{app.t('Создать','Create')}</Button></form>}
    </aside>
    <section className="lp-main" aria-label={app.t('Редактор прав','Permission editor')}>
      {!holder?<div className="lp-welcome"><div className="lp-welcome-mark"><Icon name="shield"/></div><span>LUCKPERMS</span><h3>{app.t('Сначала выбери, кому выдаём права','Choose who gets the permissions')}</h3><p>{app.t('Группы слева объединяют права. Игрок наследует права группы и может иметь собственные.','Groups on the left collect permissions. A player inherits their group’s permissions and may have individual permissions.')}</p><div className="lp-explainer"><strong>01</strong><span>{app.t('Выбери группу или игрока','Choose a group or player')}</span><strong>02</strong><span>{app.t('Посмотри таблицу прав','Review the permission table')}</span><strong>03</strong><span>{app.t('Добавь право внизу редактора','Add a permission below the table')}</span></div></div>:<>
        <header className="lp-holder-header"><div><span className="lp-kicker">{holder.type==='group'?app.t('ГРУППА','GROUP'):app.t('ИГРОК','USER')}</span><h3>{holder.name}</h3>{holder.primaryGroup&&<p>{app.t('Основная группа','Primary group')}: <strong>{holder.primaryGroup}</strong></p>}</div><span className="lp-node-count">{holder.nodes.length} {app.t('записей','nodes')}</span></header>
        <div className="lp-node-tabs" role="tablist" aria-label={app.t('Раздел прав','Permission section')}>{(['permission','inheritance','metadata'] as Tab[]).map(value=><button key={value} role="tab" aria-selected={tab===value} onClick={()=>{setTab(value);setKey('');setSuggestionsOpen(false);setFilter('');}}>{value==='permission'?app.t('Права','Permissions'):value==='inheritance'?app.t('Наследование','Inheritance'):app.t('Метаданные','Metadata')}<span>{holder.nodes.filter(n=>n.nodeType===value).length}</span></button>)}</div>
        <p className="lp-hint lp-section-hint">{tab==='permission'?app.t('Разрешить — true, запретить — false. Здесь собственные записи, итоговые права также зависят от групп и контекстов.','Allow means true; deny means false. These are assigned nodes; effective permissions also depend on groups and contexts.'):tab==='inheritance'?app.t('Добавь родительскую группу, чтобы наследовать её права. Контекст и срок могут ограничивать наследование.','Add a parent group to inherit its permissions. Context and expiry can limit inheritance.'):app.t('Префиксы, суффиксы и другие служебные записи. Пока доступны для просмотра.','Prefixes, suffixes, and other metadata. Currently read-only.')}</p>
        <label className="lp-search lp-node-search"><span className="sr-only">{app.t('Фильтр записей','Filter nodes')}</span><Icon name="search"/><input value={filter} onChange={e=>setFilter(e.target.value)} placeholder={app.t('Фильтр записей…','Filter nodes…')}/></label>
        <div className="lp-table-scroll"><table className="lp-table"><thead><tr><th>{tab==='inheritance'?app.t('Группа','Group'):app.t('Право / запись','Permission / node')}</th><th>{app.t('Значение','Value')}</th><th>{app.t('Ограничения','Limits')}</th><th><span className="sr-only">{app.t('Действия','Actions')}</span></th></tr></thead><tbody>{visibleNodes.map((node,i)=><tr key={`${node.key}-${i}`}><td><code>{tab==='inheritance'?node.key.slice(6):node.key}</code></td><td><span className={`lp-value ${node.value?'allow':'deny'}`}>{node.value?app.t('Разрешено','Allowed'):app.t('Запрещено','Denied')}</span></td><td><div className="lp-node-limits">{Object.entries(node.contexts).map(([k,v])=><span key={k} title={`${k}=${Array.isArray(v)?v.join(', '):v}`}>{k}={Array.isArray(v)?v.join(', '):v}</span>)}{node.expiresAt>0&&<time dateTime={new Date(node.expiresAt*1000).toISOString()}>{new Date(node.expiresAt*1000).toLocaleString()}</time>}{!Object.keys(node.contexts).length&&!node.expiresAt&&<small>{app.t('Всегда','Always')}</small>}</div></td><td>{tab!=='metadata'&&app.owner&&<Button className="lp-remove" aria-label={app.t(`Удалить ${node.key}`,`Remove ${node.key}`)} title={app.t('Удалить запись','Remove node')} disabled={busy||!editable||Object.values(node.contexts).some(v=>Array.isArray(v))} onClick={()=>void edit('removeNode',node)}><Icon name="trash"/></Button>}</td></tr>)}</tbody></table></div>
        {!visibleNodes.length&&<Empty title={app.t('Здесь пока нет записей','No nodes here yet')}/>}
        {holder.truncated&&<p className="lp-hint">{app.t('Первые 500 записей. Редактирование отключено: открой полный объект в оригинальном редакторе.','First 500 nodes. Editing is disabled: open the full holder in the original editor.')}</p>}
        {tab!=='metadata'&&app.owner&&<form className="lp-add" onSubmit={e=>{e.preventDefault();void edit('setNode');}}>
          <div className="lp-add-heading"><Icon name="plus"/><h4>{tab==='inheritance'?app.t('Добавить родительскую группу','Add a parent group'):app.t('Добавить право','Add a permission')}</h4></div>
          <div className="lp-add-row"><Field label={tab==='inheritance'?app.t('Имя группы','Group name'):app.t('Название права','Permission name')}><div className="lp-combobox"><input required maxLength={200} value={key} onChange={e=>{setKey(e.target.value);setSuggestionsOpen(true);}} onFocus={()=>setSuggestionsOpen(true)} onBlur={()=>{setSuggestionsOpen(false);}} placeholder={tab==='inheritance'?'default':'minecraft.command.help'} autoComplete="off" spellCheck={false} role="combobox" aria-autocomplete="list" aria-expanded={suggestionsOpen&&suggestions.length>0} aria-controls={suggestionId} aria-activedescendant={activeSuggestion>=0?`${suggestionId}-${activeSuggestion}`:undefined} onKeyDown={e=>{if(e.key==='Escape'){setSuggestionsOpen(false);return;}if(!suggestionsOpen||!suggestions.length)return;if(e.key==='ArrowDown'||e.key==='ArrowUp'){e.preventDefault();setActiveSuggestion(current=>(current+(e.key==='ArrowDown'?1:-1)+suggestions.length)%suggestions.length);}else if(e.key==='Enter'&&activeSuggestion>=0){e.preventDefault();choose(suggestions[activeSuggestion]);}}}/>
            {suggestionsOpen&&suggestions.length>0&&<ul id={suggestionId} className="lp-suggestions" role="listbox" aria-label={app.t('Подсказки прав','Permission suggestions')}>{suggestions.map((item,i)=><li id={`${suggestionId}-${i}`} key={item.key} role="option" aria-selected={i===activeSuggestion} className={i===activeSuggestion?'active':''} onMouseDown={e=>{e.preventDefault();choose(item);}}><code>{item.key}</code><small>{item.source}{item.description&&` · ${item.description}`}</small></li>)}</ul>}
          </div></Field><Field label={app.t('Значение','Value')}><select value={String(allow)} onChange={e=>setAllow(e.target.value==='true')}><option value="true">{app.t('Разрешить · true','Allow · true')}</option><option value="false">{app.t('Запретить · false','Deny · false')}</option></select></Field><Button className="primary" type="submit" disabled={busy||!editable||!key.trim()}><Icon name="plus"/>{app.t('Добавить','Add')}</Button></div>
          {suggestionError&&<p className="lp-hint" role="status">{app.t('Подсказки временно недоступны. Право можно ввести вручную.','Suggestions are temporarily unavailable. You can type the permission manually.')}</p>}
          <p className="lp-hint">{tab==='permission'?app.t('Подсказки — объявленные сервером права и записи этого объекта. Не все плагины объявляют все права; точное имя можно ввести вручную.','Suggestions include server-declared permissions and this holder’s nodes. Plugins may not declare every permission; enter an exact name manually.'):app.t('Введи точное имя существующей группы. Циклическое наследование будет отклонено.','Enter the exact name of an existing group. Cyclic inheritance will be rejected.')}</p>
          <button type="button" className="lp-advanced-toggle" aria-expanded={advanced} onClick={()=>setAdvanced(!advanced)}><Icon name="settings"/>{app.t('Срок и контексты','Expiry and contexts')}<span>{advanced?'−':'+'}</span></button>
          {advanced&&<div className="lp-advanced"><Field label={app.t('Истекает — необязательно','Expiry — optional')}><input type="datetime-local" value={expires} onChange={e=>setExpires(e.target.value)}/></Field><div className="lp-contexts"><p>{app.t('Контекст ограничивает, где действует право. Без контекста право действует везде.','A context limits where a permission applies. Without a context, it applies everywhere.')}</p>{contexts.map((row,i)=><div className="lp-context-row" key={i}><input aria-label={app.t(`Ключ контекста ${i+1}`,`Context key ${i+1}`)} maxLength={32} value={row.key} placeholder="server / world" onChange={e=>setContexts(current=>current.map((item,n)=>n===i?{...item,key:e.target.value}:item))}/><input aria-label={app.t(`Значение контекста ${i+1}`,`Context value ${i+1}`)} maxLength={64} value={row.value} placeholder="survival" onChange={e=>setContexts(current=>current.map((item,n)=>n===i?{...item,value:e.target.value}:item))}/><Button type="button" aria-label={app.t('Убрать контекст','Remove context')} onClick={()=>setContexts(current=>current.filter((_,n)=>n!==i))}>×</Button></div>)}<Button type="button" disabled={contexts.length>=8} onClick={()=>setContexts(current=>[...current,{key:'',value:''}])}><Icon name="plus"/>{app.t('Контекст','Context')}</Button></div></div>}
        </form>}
        {!app.owner&&<p className="lp-hint">{app.t('Доступен просмотр. Изменять права может владелец панели.','Read-only access. The panel owner can edit permissions.')}</p>}
      </>}
      {busy&&<div className="lp-progress" role="status"><span className="spinner"/>{app.t('LuckPerms обрабатывает запрос…','LuckPerms is processing the request…')}</div>}
    </section>
  </div>;
}
