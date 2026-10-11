export type GraphKind='trigger'|'condition'|'action'|'delay';
export type GraphConfig=Record<string,unknown>;
export type GraphNodeSpec={id:string;type:GraphKind;config:GraphConfig;pos:{x:number;y:number}};
export type GraphEdgeSpec={id:string;source:string;target:string;sourceHandle:'next'|'true'|'false'};
export type GraphRule={id:string;profileId?:string;name:string;enabled:boolean;world:string;cooldownSeconds:number;graphVersion:1;nodes:GraphNodeSpec[];edges:GraphEdgeSpec[]};

export function graphChangesAffectDocument(changes:{type:string;id?:string;position?:{x:number;y:number}}[],nodes:GraphNodeSpec[]):boolean{
 return changes.some(change=>change.type==='add'||change.type==='replace'||change.type==='remove'||change.type==='position'&&change.position&&nodes.some(node=>node.id===change.id&&(node.pos.x!==change.position!.x||node.pos.y!==change.position!.y)));
}
export function graphRunsForRule<T extends {ruleId:string}>(runs:T[]|null,saved:boolean,ruleId:string):T[]{return saved?(runs??[]).filter(run=>run.ruleId===ruleId):[];}

export function graphIssues(nodes:GraphNodeSpec[],edges:GraphEdgeSpec[]):string[]{
 const issues:string[]=[];const byId=new Map(nodes.map(n=>[n.id,n]));
 if(nodes.length<2||nodes.length>32)issues.push('node_limit');
 if(!nodes.some(node=>node.type==='action'))issues.push('no_action');
 if(edges.length>64)issues.push('edge_limit');
 if(byId.size!==nodes.length)issues.push('duplicate_node');
 if(nodes.some(node=>!Number.isFinite(node.pos.x)||!Number.isFinite(node.pos.y)||Math.abs(node.pos.x)>10000||Math.abs(node.pos.y)>10000))issues.push('position_bounds');
 const triggers=nodes.filter(n=>n.type==='trigger');if(triggers.length!==1)issues.push('one_trigger');
 const seenEdges=new Set<string>();const adjacency=new Map(nodes.map(n=>[n.id,[] as string[]]));
 for(const edge of edges){
  const source=byId.get(edge.source),target=byId.get(edge.target);
  if(!source||!target){issues.push('dangling_edge');continue;}
  if(target.type==='trigger')issues.push('trigger_input');
  if(source.type==='condition'?!['true','false'].includes(edge.sourceHandle):edge.sourceHandle!=='next')issues.push('wrong_port');
  const key=[edge.source,edge.target,edge.sourceHandle].join('|');if(seenEdges.has(key))issues.push('duplicate_edge');seenEdges.add(key);
  adjacency.get(source.id)!.push(target.id);
 }
 const colors=new Map<string,number>();
 function visit(id:string):boolean {if(colors.get(id)===1)return false;if(colors.get(id)===2)return true;colors.set(id,1);for(const next of adjacency.get(id)??[])if(!visit(next))return false;colors.set(id,2);return true;}
 if(nodes.some(node=>!visit(node.id)))issues.push('cycle');
 if(triggers.length===1){const reachable=new Set<string>();const pending=[triggers[0].id];while(pending.length){const id=pending.pop()!;if(reachable.has(id))continue;reachable.add(id);pending.push(...adjacency.get(id)??[]);}if(nodes.some(n=>!reachable.has(n.id)))issues.push('disconnected');}
 return [...new Set(issues)];
}
export function canConnect(nodes:GraphNodeSpec[],edges:GraphEdgeSpec[],connection:Omit<GraphEdgeSpec,'id'>):boolean{
 if(connection.source===connection.target||edges.length>=64)return false;
 const problems=graphIssues(nodes,[...edges,{...connection,id:'candidate'}]);
 return !problems.some(code=>['cycle','dangling_edge','trigger_input','wrong_port','duplicate_edge'].includes(code));
}
export function newGraph(world='',name='Новая цепочка'):GraphRule{return {id:crypto.randomUUID(),name,enabled:false,world:'',cooldownSeconds:30,graphVersion:1,nodes:[{id:'trigger',type:'trigger',config:{trigger:'interval',intervalSeconds:60},pos:{x:50,y:80}},{id:'action',type:'action',config:{action:'world.time',params:{world,time:1000}},pos:{x:350,y:80}}],edges:[{id:'start-action',source:'trigger',target:'action',sourceHandle:'next'}]};}
