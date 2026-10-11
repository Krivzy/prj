import test from 'node:test';
import assert from 'node:assert/strict';
import {canReviewPlugin,catalogPath} from '../src/plugin-catalog-view.ts';

test('A project metadata match cannot enable install without an exact stable version and Owner access',()=>{
  const release={projectId:'Example',compatibility:'metadata-confirmed',version:{id:'Version',number:'1.0',type:'release'}};
  assert.equal(canReviewPlugin(release,true,false),true);
  for(const candidate of [{...release,compatibility:'unknown'},{...release,compatibility:'unavailable'},{...release,version:undefined},{...release,version:{...release.version,type:'beta'}},{...release,stale:true},{...release,projectId:''}])assert.equal(canReviewPlugin(candidate,true,false),false);
  assert.equal(canReviewPlugin(release,false,false),false);
  assert.equal(canReviewPlugin(release,true,true),false);
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
