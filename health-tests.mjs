import test from 'node:test';
import assert from 'node:assert/strict';
import {periodBounds,productivity,average,headacheDays,csv} from './metrics.js';
test('periods include leap day and cross-year week',()=>{
 assert.deepEqual(periodBounds('2024-02-15','month'),['2024-02-01','2024-02-29']);
 assert.deepEqual(periodBounds('2026-01-01','week'),['2025-12-29','2026-01-04']);
});
test('missing ratings are not zeros',()=>{assert.equal(average([null,undefined,'']),null);assert.equal(average([0,null,10]),5)});
test('productivity excludes future, untracked and routine activities',()=>{
 const plan={'2026-10-01':[{title:'Work',kind:'work',actualStart:'2026-10-01T07:00:00Z',actualEnd:'2026-10-01T08:00:00Z'},{kind:'sport'},{kind:'meal',actualEnd:'2026-10-01T09:00:00Z'}],'2026-10-02':[{kind:'work'}],'2026-10-30':[{kind:'work',actualEnd:'2026-10-30T08:00:00Z'}]};
 const r=productivity(plan,'2026-10-01','2026-10-31','2026-10-28');assert.equal(r.days,1);assert.equal(r.done,1);assert.equal(r.total,2);assert.equal(r.rate,50);assert.equal(r.minutes,60);
 assert.equal(productivity({},'2026-10-01','2026-10-31','2026-10-28').rate,null);
});
test('overlapping and overnight episodes count unique days in selected period',()=>{
 const rows=[{kind:'migraine',day:'2026-09-30',payload:{end:'2026-10-02T01:00:00Z'}},{kind:'migraine',day:'2026-10-01',payload:{end:'2026-10-01T13:00:00Z'}}];
 assert.equal(headacheDays(rows,'2026-10-01','2026-10-31','2026-10-28'),2);
 assert.equal(headacheDays([{kind:'migraine',day:'2026-10-27',payload:{}}],'2026-10-01','2026-10-31','2026-10-28'),2);
});
test('CSV quotes multiline values and neutralizes spreadsheet formulas',()=>{const s=csv([['=1+1','a"b','line\nbreak',0]]);assert.ok(s.startsWith('\uFEFF'));assert.ok(s.includes('"\'=1+1"'));assert.ok(s.includes('"a""b"'));assert.ok(s.includes('"0"'))});
