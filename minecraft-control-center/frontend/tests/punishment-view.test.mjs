import test from 'node:test';
import assert from 'node:assert/strict';
import {canRevoke,durationParts,durationSeconds,recordKey} from '../src/punishment-view.ts';
const row={id:7,historyId:7,activeId:2,status:'active',identityVerified:true,identityUuid:'00000000-0000-0000-0000-000000000001',revision:'a'.repeat(64),type:'BAN',source:{id:'advancedban'}};
test('History and active IDs never collide in selection keys',()=>{assert.notEqual(recordKey(row),recordKey({...row,historyId:0,activeId:7}));assert.equal(recordKey(row),'advancedban:history:7');});
test('Only a verified current active record can be revoked',()=>{assert.equal(canRevoke(row),true);for(const change of [{status:'inactive'},{status:'unknown'},{activeId:0},{identityVerified:false},{identityUuid:'generated'},{revision:''},{type:'KICK'}])assert.equal(canRevoke({...row,...change}),false);});
test('Typed duration units preserve permanent and note semantics',()=>{assert.equal(durationSeconds('2','hours'),7200);assert.equal(durationSeconds('3','days'),259200);assert.equal(durationSeconds('1','permanent'),0);assert.equal(durationParts(1,-1),null);assert.equal(durationParts(1000,61000),60);});
test('Invalid or oversized durations cannot become a sanction',()=>{for(const [amount,unit] of [['0','hours'],['1.5','days'],['-1','hours'],['Infinity','hours'],['4000','days'],['2','months']])assert.throws(()=>durationSeconds(amount,unit));});
