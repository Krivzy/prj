import test from 'node:test';
import assert from 'node:assert/strict';
import {canRemovePlugin,canReviewPlugin,catalogPath,pluginEntries} from '../src/plugin-catalog-view.ts';

test('A project metadata match cannot enable install without an exact stable version and Owner access',()=>{
  const release={projectId:'Example',compatibility:'metadata-confirmed',version:{id:'Version',number:'1.0',type:'release'}};
  assert.equal(canReviewPlugin(release,true,false),true);
  for(const candidate of [{...release,compatibility:'unknown'},{...release,compatibility:'unavailable'},{...release,version:undefined},{...release,version:{...release.version,type:'beta'}},{...release,stale:true},{...release,projectId:''}])assert.equal(canReviewPlugin(candidate,true,false),false);
  assert.equal(canReviewPlugin(release,false,false),false);
  assert.equal(canReviewPlugin(release,true,true),false);
});
test('Installed registry records keep installation disabled after restart, including disabled runtime plugins',()=>{
  const project={projectId:'Example',compatibility:'metadata-confirmed',version:{id:'V',number:'1.0',type:'release'}};
  for(const status of ['loaded','disabled','installed-pending-restart'])assert.equal(canReviewPlugin({...project,installation:{status}},true,false),false);
});
test('File identity and loaded name reconcile without inventing catalog aliases or duplicate AdvancedBanZ cards',()=>{
  const file={name:'AdvancedBanZ',version:'4.0.1',file:'manual.jar',loaded:true,canRemove:true,protected:false,blockedBy:[],status:'present'};
  const rows=pluginEntries([{name:'AdvancedBanZ',version:'4.0.1',enabled:true}], [file]);
  assert.equal(rows.length,1);assert.equal(rows[0].name,'AdvancedBanZ');assert.equal(rows[0].file,'manual.jar');assert.equal(rows[0].enabled,true);
  assert.equal(pluginEntries([], [{...file,loaded:false}])[0].enabled,false);
});
test('Removal requires server eligibility, Owner and no dependencies, protected identity or pending request',()=>{
  const plugin={canRemove:true,protected:false,blockedBy:[],status:'present'};
  assert.equal(canRemovePlugin(plugin,true,false),true);
  for(const candidate of [{...plugin,canRemove:false},{...plugin,protected:true},{...plugin,blockedBy:['Consumer']},{...plugin,status:'pending-removal'}])assert.equal(canRemovePlugin(candidate,true,false),false);
  assert.equal(canRemovePlugin(plugin,false,false),false);assert.equal(canRemovePlugin(plugin,true,true),false);
});
test('Catalog search cannot inject a different compatibility filter or pagination through the query',()=>{
  const url=new URL(catalogPath(' world &compatible=false&offset=900 ', 'downloads',24,12,true,false),'http://localhost');
  assert.equal(url.searchParams.get('query'),'world &compatible=false&offset=900');
  assert.equal(url.searchParams.getAll('compatible').length,1);
  assert.equal(url.searchParams.get('compatible'),'true');
  assert.equal(url.searchParams.get('offset'),'24');
  assert.equal(url.searchParams.get('limit'),'12');
  assert.equal(url.searchParams.get('featured'),'false');
});
