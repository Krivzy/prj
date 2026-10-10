import test from 'node:test';
import assert from 'node:assert/strict';
import {graphIssues,canConnect,graphChangesAffectDocument,graphRunsForRule,newGraph} from '../src/GraphModel.ts';
const n=(id,type='action')=>({id,type,config:{},pos:{x:0,y:0}});
const e=(source,target,sourceHandle='next')=>({id:source+target+sourceHandle,source,target,sourceHandle});
test('Condition branches and a shared downstream node form a valid reachable DAG',()=>{
 const nodes=[n('start','trigger'),n('if','condition'),n('a'),n('b'),n('join')];
 assert.deepEqual(graphIssues(nodes,[e('start','if'),e('if','a','true'),e('if','b','false'),e('a','join'),e('b','join')]),[]);
});
test('Connection rejects cycles, duplicate ports, trigger input and unknown endpoints',()=>{
 const nodes=[n('start','trigger'),n('if','condition'),n('done')];const edges=[e('start','if'),e('if','done','true')];
 for(const connection of [e('done','if'),e('done','start'),e('if','done','true'),e('if','done','next'),e('unknown','done'),e('done','done')])assert.equal(canConnect(nodes,edges,connection),false);
 assert.equal(canConnect(nodes,edges,e('if','done','false')),true);
});
test('Saving catches disconnected actions and missing or multiple triggers',()=>{
 assert.ok(graphIssues([n('start','trigger'),n('a'),n('orphan')],[e('start','a')]).includes('disconnected'));
 assert.ok(graphIssues([n('a'),n('b')],[e('a','b')]).includes('one_trigger'));
 assert.ok(graphIssues([n('a','trigger'),n('b','trigger')],[]).includes('one_trigger'));
});
test('Keyboard movement marks document dirty, selection and layout measurements do not',()=>{
 const nodes=[n('start','trigger'),n('action')];
 assert.equal(graphChangesAffectDocument([{type:'position',id:'action',position:{x:20,y:0},dragging:false}],nodes),true);
 assert.equal(graphChangesAffectDocument([{type:'position',id:'action',position:{x:0,y:0},dragging:false}],nodes),false);
 assert.equal(graphChangesAffectDocument([{type:'select',id:'action',selected:true},{type:'dimensions',id:'action',dimensions:{width:200,height:100}}],nodes),false);
 assert.equal(graphChangesAffectDocument([{type:'remove',id:'action'}],nodes),true);
});
test('A new flow and another selected rule do not inherit previous run history',()=>{
 const runs=[{ruleId:'old',runId:'run-1'},{ruleId:'new',runId:'run-2'}];
 assert.deepEqual(graphRunsForRule(runs,false,'new'),[]);
 assert.deepEqual(graphRunsForRule(runs,true,'new'),[{ruleId:'new',runId:'run-2'}]);
 assert.deepEqual(graphRunsForRule(null,true,'new'),[]);
});
test('Editor position bounds reject non-finite and out-of-range coordinates before save',()=>{
 for(const x of [NaN,Infinity,-10001,10001]){const nodes=[n('start','trigger'),{...n('action'),pos:{x,y:0}}];assert.ok(graphIssues(nodes,[e('start','action')]).includes('position_bounds'));}
 const nodes=[n('start','trigger'),{...n('action'),pos:{x:10000,y:-10000}}];assert.deepEqual(graphIssues(nodes,[e('start','action')]),[]);
});
test('New drafts preserve selected world and use the current language for their name',()=>{
 const draft=newGraph('fixture_nether','New flow');assert.equal(draft.name,'New flow');assert.equal(draft.nodes[1].config.params.world,'fixture_nether');assert.deepEqual(graphIssues(draft.nodes,draft.edges),[]);
});
