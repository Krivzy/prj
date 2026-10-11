import test from 'node:test';
import assert from 'node:assert/strict';
import {acknowledgeCoverage,coverageNoticeVisible,readCoverageAck} from '../src/ore-coverage-view.ts';
const first='12345678-1234-1234-1234-123456789012',second='87654321-4321-4321-4321-210987654321';
test('Acknowledgement survives reopening but a new server boot shows incomplete coverage again',()=>{
 const values=new Map(),storage={getItem:k=>values.get(k)??null,setItem:(k,v)=>values.set(k,v)};
 assert(coverageNoticeVisible(true,first,readCoverageAck(storage)));
 const ack=acknowledgeCoverage(storage,first);assert.equal(coverageNoticeVisible(true,first,ack),false);
 assert.equal(coverageNoticeVisible(true,first,readCoverageAck(storage)),false);
 assert.equal(coverageNoticeVisible(true,second,readCoverageAck(storage)),true);
 assert.equal(coverageNoticeVisible(false,second,ack),false);
});
test('Blocked browser storage keeps an in-memory acknowledgement without hiding a new boot',()=>{
 const denied={getItem:()=>{throw Error('denied');},setItem:()=>{throw Error('denied');}};
 assert.equal(readCoverageAck(denied),'');const ack=acknowledgeCoverage(denied,first);
 assert.equal(coverageNoticeVisible(true,first,ack),false);assert(coverageNoticeVisible(true,second,ack));
});
test('Unknown or malformed boot identity never suppresses a real coverage warning',()=>{
 assert.equal(acknowledgeCoverage({setItem:()=>assert.fail()},'bad'),'');
 assert(coverageNoticeVisible(true,undefined,first));assert(coverageNoticeVisible(true,'bad','bad'));
 assert.equal(readCoverageAck({getItem:()=>'<script>bad</script>'}),'');
});
