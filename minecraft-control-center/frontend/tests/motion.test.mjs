import test from 'node:test';
import assert from 'node:assert/strict';
import {easeScroll,wheelDistance} from '../src/motion.ts';
test('wheel distances normalize lines/pages and cap coarse input',()=>{assert.equal(wheelDistance(3,1,800),48);assert.equal(wheelDistance(1,2,800),240);assert.equal(wheelDistance(-1000,0,800),-240);});
test('inertia moves monotonically without overshooting',()=>{let current=0;for(let i=0;i<80;i++){const next=easeScroll(current,100);assert.ok(next>=current&&next<=100);current=next;}assert.equal(current,100);});
test('inertia settles negative and small distances',()=>{assert.equal(easeScroll(100,0),78);assert.equal(easeScroll(1,1.4),1.4);});
test('integer-rounded browser scrolling reaches the target and stops',()=>{let current=0;let frames=0;while(current!==120&&frames<100){current=Math.round(easeScroll(current,120));frames++;}assert.equal(current,120);assert.ok(frames<30);});
