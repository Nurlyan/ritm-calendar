export const moscowDay=value=>new Intl.DateTimeFormat('sv-SE',{timeZone:'Europe/Moscow'}).format(new Date(value));
export function shiftDay(day,n){const d=new Date(day+'T12:00:00Z');d.setUTCDate(d.getUTCDate()+n);return d.toISOString().slice(0,10)}
export function periodBounds(day,period){
 if(period==='day')return [day,day];
 if(period==='month')return [day.slice(0,7)+'-01',shiftDay(new Date(Date.UTC(+day.slice(0,4),+day.slice(5,7),1)).toISOString().slice(0,10),-1)];
 const start=shiftDay(day,-((new Date(day+'T12:00:00Z').getUTCDay()+6)%7));return [start,shiftDay(start,6)];
}
export const average=xs=>{const v=xs.filter(x=>x!==null&&x!==undefined&&x!=='').map(Number);return v.length?v.reduce((a,b)=>a+b,0)/v.length:null};
export function productivity(plan,from,to,today){
 const days=Object.entries(plan).filter(([d,bs])=>d>=from&&d<=to&&d<=today&&bs.some(b=>b.actualStart||b.actualEnd));
 const tasks=days.flatMap(([day,bs])=>bs.filter(b=>['work','sport','together','personal','call'].includes(b.kind)).map(b=>({...b,day})));
 const done=tasks.filter(b=>b.actualEnd);
 return {days:days.length,total:tasks.length,done:done.length,rate:tasks.length?Math.round(done.length/tasks.length*100):null,tasks:done,minutes:done.reduce((s,b)=>s+(b.actualStart?Math.max(0,(new Date(b.actualEnd)-new Date(b.actualStart))/60000):0),0)};
}
export function headacheDays(rows,from,to,today){const days=new Set();for(const r of rows.filter(r=>r.kind==='migraine')){let start=r.day;const end=r.payload.end?moscowDay(r.payload.end):today;if(start<from)start=from;for(let d=start;d<=end&&d<=to&&d<=today;d=shiftDay(d,1))days.add(d)}return days.size}
export function csv(rows){return '\uFEFF'+rows.map(row=>row.map(v=>{let s=String(v??'');if(/^[=+@-]/.test(s))s="'"+s;return '"'+s.replaceAll('"','""')+'"'}).join(';')).join('\r\n')}
