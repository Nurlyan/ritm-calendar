import test from 'node:test';import assert from 'node:assert/strict';import {base,reflow,validate,swapDays} from './engine.js';
test('all seven days respect sleep boundaries and call',()=>{for(let d=0;d<7;d++)validate(base(d),d)});
test('four sports blocks and no Tuesday training',()=>{assert.equal(Array.from({length:7},(_,d)=>base(d).filter(b=>b.kind==='sport').length).reduce((a,b)=>a+b),4);assert.equal(base(1).filter(b=>b.kind==='sport').length,0)});
test('late start keeps sleep boundary and preserves overflow',()=>{const b=base(0),r=reflow(b,b[1].id,620,240);validate(r.blocks,0);assert.ok(r.blocks.every(x=>x.end<=1470));assert.equal(r.blocks.length+r.deferred.length,b.length);assert.ok(r.deferred.length>0)});
test('Tuesday call cannot move or overlap',()=>{const b=base(1);assert.throws(()=>reflow(b,b.find(x=>x.locked).id,1210,80));assert.throws(()=>reflow(b,b[4].id,1190,60));const r=reflow(b,b[2].id,870,300);validate(r.blocks,1);assert.deepEqual(r.blocks.find(x=>x.locked),b.find(x=>x.locked))});
test('wake and sleep are hard limits',()=>{const b=base(0);assert.throws(()=>reflow(b,b[0].id,560,30));assert.throws(()=>reflow(b,b[1].id,1460,60))});
test('weekday swap keeps four sports days',()=>{const [w,t]=swapDays(base(2),base(3));validate(w,2);validate(t,3);assert.equal(w.filter(x=>x.kind==='sport').length,1);assert.equal(t.find(x=>x.kind==='together').start,930)});
