// Последняя минута дня плана: 02:00 следующих суток. Сам отбой — конец блока «Подготовка ко сну», его можно двигать.
export const DAY_END=1560;
export const names=['Понедельник','Вторник','Среда','Четверг','Пятница','Суббота','Воскресенье'];
export const short=['ПН','ВТ','СР','ЧТ','ПТ','СБ','ВС'];
export const clock=m=>`${String(Math.floor(m/60)%24).padStart(2,'0')}:${String(m%60).padStart(2,'0')}`;
export const key=d=>`${d.getFullYear()}-${String(d.getMonth()+1).padStart(2,'0')}-${String(d.getDate()).padStart(2,'0')}`;
export const weekday=d=>(new Date(d+'T12:00:00').getDay()+6)%7;
export function minutes(s){let [h,m]=s.split(':').map(Number);return h*60+m+(h<9?1440:0)}
export function base(day){const rows=[];const a=(title,start,end,kind)=>rows.push({id:`${day}-${rows.length}`,title,start,end,kind});
 a('Утро для себя',570,600,'personal');
 if(day===0){a('Глубокая работа',600,840,'work');a('Обед и перезагрузка',840,900,'meal');a('Зал / велосипед',900,1020,'sport');a('Работа · второй фокус',1020,1140,'work');a('Время вместе / для себя',1140,1425,'together')}
 if(day===1){a('Глубокая работа',600,810,'work');a('Обед и отдых',810,870,'meal');a('Работа · второй фокус',870,1110,'work');a('Ужин',1110,1140,'meal');a('Завершение задач',1140,1200,'work');a('Созвон с Максимом',1200,1280,'call');a('Время вместе / для себя',1280,1425,'together')}
 if(day===2){a('Глубокая работа',600,840,'work');a('Обед',840,870,'meal');a('Завершение задач',870,930,'work');a('Прогулка вдвоём',930,1170,'together');a('Ужин',1170,1230,'meal');a('Личное время',1230,1425,'personal')}
 if(day===3||day===4){a('Глубокая работа',600,810,'work');a('Обед',810,870,'meal');a('Зал / велосипед',870,990,'sport');a('Работа · второй фокус',990,1140,'work');a('Ужин и время вместе',1140,1425,'together')}
 if(day===5){a('Работа / свои проекты',600,810,'work');a('Обед',810,870,'meal');a('Зал / велосипед',870,990,'sport');a('Личные проекты',990,1110,'work');a('Игры и свои дела',1110,1200,'personal');a('Вечер вместе',1200,1425,'together')}
 if(day===6){a('Свободное воскресенье',600,840,'personal');a('Неспешный обед',840,900,'meal');a('Отдых и любимые дела',900,1200,'personal');a('Вечер вместе',1200,1425,'together')}
 a('Подготовка ко сну',1425,1470,'sleep');return rows.map(b=>({...b,locked:b.kind==='call'}))}
export function validate(blocks,day){if(!Array.isArray(blocks)||blocks.length>40)throw Error('Некорректный план');let end=570;const ids=new Set();for(const b of [...blocks].sort((a,b)=>a.start-b.start)){if(ids.has(b.id)||!Number.isInteger(b.start)||!Number.isInteger(b.end)||b.start<end||b.end<=b.start||b.end>DAY_END||!b.title?.trim()||b.title.length>100||!['work','meal','sport','personal','together','sleep','call'].includes(b.kind))throw Error('Занятия пересекаются или выходят за границы 09:30–02:00');ids.add(b.id);end=b.end}const call=blocks.filter(b=>b.kind==='call');if(day===1&&(call.length!==1||call[0].start!==1200||call[0].end!==1280||!call[0].locked))throw Error('Созвон закреплён на вторник 20:00–21:20');if(day!==1&&call.length)throw Error('Созвон доступен только во вторник')}
// Earlier events stay in place. Later events use remaining gaps; the call and bedtime preparation never move.
// Overflow is explicitly deferred rather than silently dropped or pushed into sleep.
export function reflow(blocks,id,start,duration,patch={}){
 const list=structuredClone(blocks).sort((a,b)=>a.start-b.start);
 const chosen=list.find(b=>b.id===id);
 if(!chosen||chosen.locked)throw Error('Это событие закреплено');
 if(!Number.isInteger(start)||!Number.isInteger(duration)||start<570||start+duration>DAY_END||duration<5)throw Error('Выберите время в пределах 09:30–02:00');
 const fixed=list.filter(b=>b!==chosen&&(b.locked||b.kind==='sleep'));
 const hit=fixed.find(b=>start<b.end&&start+duration>b.start);
 if(hit)throw Error(hit.locked?'Это время занято созвоном с Максимом':`С ${clock(hit.start)} — подготовка ко сну, её время не меняется`);
 const other=list.filter(b=>b!==chosen&&!fixed.includes(b));
 const before=other.filter(b=>b.end<=start);
 const remaining=other.filter(b=>b.end>start);
 Object.assign(chosen,patch,{start,end:start+duration});
 let cursor=chosen.end;const after=[],deferred=[],limit=limitOf(fixed);
 for(const b of remaining){let s=Math.max(b.start,cursor);const len=b.end-b.start;s=clear(s,len,fixed);
 if(s+len>limit){deferred.push(b);continue}
 after.push({...b,start:s,end:s+len});cursor=s+len}
 return {blocks:[...before,chosen,...after,...fixed].sort((a,b)=>a.start-b.start),deferred};
}
// First start at or after `s` where a block of `len` minutes does not overlap fixed blocks.
function clear(s,len,fixed){for(let moved=true;moved;){moved=false;for(const f of fixed)if(s<f.end&&s+len>f.start){s=f.end;moved=true}}return s}
// Конец дня для обычных блоков: начало подготовки ко сну, а без неё — DAY_END.
const limitOf=fixed=>Math.min(DAY_END,...fixed.filter(b=>b.kind==='sleep').map(b=>b.start));
function place(seq,fixed,cursor){const placed=[],deferred=[],limit=limitOf(fixed);for(const b of seq){const len=b.end-b.start,s=clear(cursor,len,fixed);if(s+len>limit){deferred.push(b);continue}placed.push({...b,start:s,end:s+len});cursor=s+len}return {placed,deferred}}
const fixedOf=(blocks,pinned)=>blocks.filter(b=>b.locked||b.kind==='sleep'||pinned.includes(b.id));
const minutesOf=list=>list.reduce((n,b)=>n+b.end-b.start,0);
export function swapDays(wed,thu){return [structuredClone(thu).map(b=>({...b,id:crypto.randomUUID()})),structuredClone(wed).map(b=>({...b,id:crypto.randomUUID()}))]}
// Day editor: movable blocks follow each other in the chosen order starting at `from`.
// A block with `at` (start set by hand) starts there: later than the previous block leaves a gap, earlier shortens
// the previous block (never below 5 minutes). The call, the bedtime block and anything in `pinned` keep their time;
// what no longer fits before bedtime is deferred.
export function pack(blocks,order,{from=570,pinned=[]}={}){
 const fixed=fixedOf(blocks,pinned),placed=[],deferred=[];let cursor=from;
 for(const b of order){if(fixed.some(f=>f.id===b.id))continue;const len=b.end-b.start;let s=cursor;
  if(Number.isInteger(b.at)&&b.at>=570){s=b.at;const prev=placed.at(-1);if(s<cursor){if(prev&&s-prev.start>=5)prev.end=s;else s=cursor}}
  s=clear(s,len,fixed);
  if(s+len>limitOf(fixed)){deferred.push(b);continue}
  placed.push({...b,start:s,end:s+len});cursor=s+len}
 const strip=({at,...b})=>b;
 return {blocks:[...placed,...fixed].map(strip).sort((a,b)=>a.start-b.start),deferred:deferred.map(strip)};
}
// Minutes the day would run past bedtime preparation if deferred blocks were appended after the placed ones.
export function overrun(blocks,deferred,{pinned=[]}={}){
 const fixed=fixedOf(blocks,pinned),bed=fixed.find(b=>b.kind==='sleep'),wall=fixed.filter(b=>b!==bed),limit=bed?bed.start:DAY_END;
 let cursor=Math.max(570,...blocks.filter(b=>!fixed.includes(b)).map(b=>b.end));
 for(const b of deferred){const len=b.end-b.start;cursor=clear(cursor,len,wall)+len}
 return deferred.length?Math.max(0,cursor-limit):0;
}
// «Чем пожертвовать»: shortens one block (not below `min` minutes) by up to the missing minutes so deferred blocks fit back before bedtime.
// Blocks before it stay put; it and everything after are laid out again in the same order. Null when it frees nothing.
export function sacrifice(blocks,deferred,victimId,{min=15,pinned=[]}={}){
 const fixed=fixedOf(blocks,pinned),placed=blocks.filter(b=>!fixed.includes(b)).sort((a,b)=>a.start-b.start),seq=[...placed,...deferred];
 const i=seq.findIndex(b=>b.id===victimId);if(i<0)return null;const v=seq[i],len=v.end-v.start,need=overrun(blocks,deferred,{pinned});if(!need||len-min<1)return null;
 const k=Math.min(i,placed.length),keep=seq.slice(0,k),cursor=i<placed.length?v.start:Math.max(570,...keep.map(b=>b.end));
 const lay=cut=>{const r=place(seq.slice(k).map(b=>b===v?{...b,end:b.end-cut}:b),fixed,cursor),bl=[...keep,...r.placed,...fixed].sort((a,b)=>a.start-b.start);return {blocks:bl,deferred:r.deferred,cut,victim:v,left:overrun(bl,r.deferred,{pinned})}};
 let best=lay(Math.min(need,len-min));for(let cut=best.cut+5;best.left&&cut<=len-min;cut+=5){const r=lay(cut);if(r.left<best.left)best=r}
 return best.left<need?best:null;
}
// «Понемногу из всех»: недостающие минуты снимаются со всех гибких блоков начиная с `from`, пропорционально их длине
// (каждый остаётся не короче `min`). Свободные окна между ними сначала закрываются — это тоже даёт время.
export function squeezeAll(blocks,deferred,{from=0,pinned=[],min=15}={}){
 if(!deferred.length)return null;
 const fixed=fixedOf(blocks,pinned),placed=blocks.filter(b=>!fixed.includes(b)).sort((a,b)=>a.start-b.start);
 const k=placed.findIndex(b=>b.start>=from),keep=k<0?placed:placed.slice(0,k),flex=[...(k<0?[]:placed.slice(k)),...deferred];
 const cursor=k<0?Math.max(570,...keep.map(b=>b.end)):placed[k].start;
 const lay=cuts=>{const r=place(flex.map((b,i)=>({...b,end:b.end-cuts[i]})),fixed,cursor),bl=[...keep,...r.placed,...fixed].sort((a,b)=>a.start-b.start);return {blocks:bl,deferred:r.deferred,left:overrun(bl,r.deferred,{pinned})}};
 const zero=flex.map(()=>0),first=lay(zero);if(!first.left&&!first.deferred.length)return {...first,cut:0,count:flex.length};
 const need=first.left,room=flex.map(b=>Math.max(0,b.end-b.start-min)),total=room.reduce((a,b)=>a+b,0);if(!total)return null;
 const take=Math.min(need,total),cuts=room.map(r=>Math.floor(take*r/total));
 for(let rest=take-cuts.reduce((a,b)=>a+b,0),i=0;rest>0&&i<flex.length*take;i++){const j=i%flex.length;if(cuts[j]<room[j]){cuts[j]++;rest--}}
 let best=lay(cuts);for(let extra=1;best.left&&extra<=total-take;extra++){const more=[...cuts];let add=extra;for(let j=0;add>0&&j<flex.length;j++){const d=Math.min(add,room[j]-more[j]);more[j]+=d;add-=d}const r=lay(more);if(r.left<best.left)best=r;if(!r.left)break}
 return {...best,cut:cuts.reduce((a,b)=>a+b,0),count:flex.length};
}
