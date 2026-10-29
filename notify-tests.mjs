import test from 'node:test';
import assert from 'node:assert/strict';
import {buildReminders,defaults} from './notify.js';
import {base,weekday,reflow} from './engine.js';

const today='2026-10-27'; // вторник
const now=new Date('2026-10-27T05:00:00Z').getTime(); // 08:00 МСК
const plan={};
const dayPlan=d=>plan[d]||base(weekday(d));
const s=defaults();
const run=(extra={})=>buildReminders({dayPlan,today,now,role:'owner',s,days:2,...extra});

test('одно напоминание о начале на блок и стабильные ключи',()=>{
 const a=run(),b=run();
 assert.deepEqual(a.map(r=>r.key),b.map(r=>r.key));
 assert.equal(new Set(a.map(r=>r.key)).size,a.length);
 const work=a.find(r=>r.key.startsWith('start:2026-10-27:'+base(1)[1].id));
 assert.equal(new Date(work.at).toISOString(),'2026-10-27T06:55:00.000Z'); // 10:00 МСК − 5 мин
});

test('перенос занятия меняет ключ — старое напоминание отменяется, дубля нет',()=>{
 const before=run().filter(r=>r.key.startsWith('start:'));
 const blocks=base(1),id=blocks[1].id;
 plan[today]=reflow(blocks,id,630,blocks[1].end-blocks[1].start).blocks;
 const after=run().filter(r=>r.key.startsWith('start:'));
 delete plan[today];
 const moved=after.filter(r=>r.key.includes(':'+id+':'));
 assert.equal(moved.length,1);
 assert.ok(!before.some(r=>r.key===moved[0].key));
});

test('завершённые занятия не напоминают, тихие часы соблюдаются',()=>{
 plan[today]=base(1).map((b,i)=>i===1?{...b,actualStart:'x',actualEnd:'y'}:b);
 const list=run();delete plan[today];
 assert.ok(!list.some(r=>r.key.includes(':'+base(1)[1].id+':')));
 for(const r of list){const m=(new Date(r.at).getUTCHours()+3)%24*60+new Date(r.at).getUTCMinutes();assert.ok(!(m>=30&&m<570),r.key)}
});

test('профиль просмотра не получает дневниковых напоминаний',()=>{
 const list=run({role:'viewer'});
 assert.ok(!list.some(r=>r.sensitive||r.channel==='diaries'||r.channel==='sleep'||r.channel==='breaks'));
});

test('перерывы выключены по умолчанию и не меняют план',()=>{
 assert.ok(!run().some(r=>r.channel==='breaks'));
 const withBreaks=run({s:{...s,types:{...s.types,breaks:true}}});
 assert.ok(withBreaks.some(r=>r.channel==='breaks'));
 assert.equal(plan[today],undefined);
});

test('напоминание об эффекте только по явно заданному времени',()=>{
 const health=[{id:'m1',kind:'migraine',day:today,payload:{treatments:[{name:'x',effect:'unknown',remindAt:'2026-10-27T09:00:00Z'},{name:'y',effect:'full',remindAt:'2026-10-27T09:00:00Z'},{name:'z',effect:'unknown'}]}}];
 const list=run({health}).filter(r=>r.channel==='diaries'&&r.key.startsWith('effect:'));
 assert.equal(list.length,1);
 assert.equal(list[0].title,'Пора дополнить дневник');
});

test('утреннее напоминание закреплено, ведёт к отметке подъёма и пропадает, если подъём записан',()=>{
 const morning=run().find(r=>r.key==='morning:2026-10-27');
 assert.equal(morning.sticky,true);assert.equal(morning.route,'wake');assert.equal(morning.channel,'wake');
 const woke=run({sleep:[{bed:'2026-10-26T21:40:00Z',wake:'2026-10-27T05:50:00Z'}]});
 assert.ok(!woke.some(r=>r.key==='morning:2026-10-27'));
 assert.ok(woke.some(r=>r.key==='morning:2026-10-28'));
});

test('вечернее напоминание открывает «Лёг спать» и не приходит, если отход уже отмечен',()=>{
 const bed=run().find(r=>r.key==='bed:2026-10-27');
 assert.equal(bed.route,'bed');
 const open=run({sleepOpen:{id:'x',bed:'2026-10-27T21:00:00Z'}});
 assert.ok(!open.some(r=>r.key==='bed:2026-10-27'));
 assert.match(open.find(r=>r.key==='morning:2026-10-28').text,/Вы легли в 00:00/);
});

test('«Отметьте» через 5 минут после конца блока, кроме сна и уже отмеченных',()=>{
 const r=run().filter(x=>x.key.startsWith('mark:'+today));
 const bl=base(weekday(today)),morning=bl[0];
 const m=r.find(x=>x.key===`mark:${today}:${morning.id}`);
 assert.ok(m);assert.equal(m.at,new Date(`${today}T10:05:00+03:00`).getTime());
 assert.deepEqual(m.buttons.map(b=>b.action.op),['done','skip']);assert.equal(m.action.op,'mark');
 assert.ok(!r.some(x=>x.key.includes(bl.find(b=>b.kind==='sleep').id)));
 plan[today]=bl.map((b,i)=>i===0?{...b,actualStart:'x',actualEnd:'y'}:i===1?{...b,skipped:true}:b);
 const after=run().filter(x=>x.key.startsWith('mark:'+today));delete plan[today];
 assert.ok(!after.some(x=>x.key.endsWith(bl[0].id)||x.key.endsWith(bl[1].id)));
 assert.equal(buildReminders({dayPlan,today,now,role:'viewer',s,days:1}).filter(x=>x.key.startsWith('mark:')).length,0);
});

test('за 5 и за 1 минуту до конца — одно уведомление, второе заменяет первое',()=>{
 const bl=base(weekday(today)),work=bl.find(b=>b.kind==='work');
 const r=run().filter(x=>x.key.startsWith('soon')&&x.key.endsWith(`${today}:${work.id}`));
 assert.deepEqual(r.map(x=>x.at),[5,1].map(l=>new Date(`${today}T00:00:00+03:00`).getTime()+(work.end-l)*60000));
 assert.ok(r.every(x=>x.notifyKey===`soon:${today}:${work.id}`&&x.buttons[0].action.op==='done'));
 assert.ok(!run().some(x=>x.key.startsWith('soon')&&x.key.includes(bl.find(b=>b.kind==='sleep').id)));
 plan[today]=bl.map(b=>b.id===work.id?{...b,actualEnd:'x'}:b);const after=run().filter(x=>x.key.startsWith('soon')&&x.key.endsWith(work.id));delete plan[today];assert.equal(after.length,0);
});

test('вечером «Как прошёл день?», если вечерней оценки ещё нет',()=>{
 const e=run().find(x=>x.key===`evening:${today}`);
 assert.ok(e);assert.equal(e.route,'wellbeing');assert.equal(e.at,new Date(`${today}T23:30:00+03:00`).getTime());assert.ok(e.sensitive);
 const done=run({health:[{kind:'wellbeing',day:today,payload:{at:`${today}T19:00:00+03:00`,score:7}}]});assert.ok(!done.some(x=>x.key===`evening:${today}`));
 const morningOnly=run({health:[{kind:'wellbeing',day:today,payload:{at:`${today}T10:00:00+03:00`,score:7}}]});assert.ok(morningOnly.some(x=>x.key===`evening:${today}`));
 assert.ok(!buildReminders({dayPlan,today,now,role:'viewer',s,days:1}).some(x=>x.key.startsWith('evening:')));
});
