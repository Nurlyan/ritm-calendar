import {secureGet,secureSet,isNative} from './native.js';
const config=window.RITM_CONFIG||{};
export const cloud=Boolean(config.supabaseUrl&&config.supabaseKey&&config.emailAccess);
const SESSION='ritm-session-v2';
let user=null,role='viewer',version=-1,onChange=()=>{},timer,reading=false,sleepStamp=null,healthStamp=null;
export let health=[];
// Записи дневников, которые не удалось отправить из-за сети. Хранятся зашифрованно на устройстве, отдельно для каждого профиля.
export let queue=[];
export const getRole=()=>role;
export const session=()=>user;
const empty=()=>({plan:{},backlog:[],history:[],sleep:[]});
export let data=empty();
const queueKey=()=>'ritm-queue-v1:'+(user?.email||'');
async function persistQueue(){await secureSet(queueKey(),queue.length?JSON.stringify(queue):null)}
async function loadQueue(){try{queue=JSON.parse(await secureGet(queueKey()))||[]}catch{queue=[]}}
const offline=e=>e instanceof TypeError||e?.name==='TimeoutError'||e?.name==='AbortError';
async function rpc(name,args){const response=await fetch(config.supabaseUrl+'/rest/v1/rpc/'+name,{method:'POST',headers:{apikey:config.supabaseKey,'Content-Type':'application/json'},body:JSON.stringify(args),signal:AbortSignal.timeout(15000)});const text=await response.text();const body=text?JSON.parse(text):null;if(!response.ok)throw Error(body?.message||'Не удалось связаться с базой');return body}
async function tick(){if(!user||document.hidden||reading)return;reading=true;try{if(queue.length)await flush();if(document.querySelector('dialog[open]')||document.activeElement?.closest('form'))return;const changed=await refresh();window.dispatchEvent(new CustomEvent('sync-status',{detail:'SUBSCRIBED'}));if(changed)onChange()}catch(e){window.dispatchEvent(new CustomEvent('sync-error',{detail:offline(e)?'Нет связи с базой. Записи дневника сохранятся при восстановлении сети.':e.message}))}finally{reading=false}}
function watch(){clearInterval(timer);timer=setInterval(tick,5000)}
// Активный опрос идёт только при видимом экране; при возвращении в приложение данные проверяются сразу.
document.addEventListener('visibilitychange',()=>{if(!document.hidden)tick()});
window.addEventListener('online',()=>tick());
export async function init(callback){onChange=callback;if(!cloud){role='owner';try{health=JSON.parse(localStorage.getItem('ritm-health-v1'))||[];data=JSON.parse(localStorage.getItem('ritm-local-v1'))||empty()}catch{}return}
 try{user=JSON.parse(await secureGet(SESSION))}catch{}
 if(isNative&&!user){try{const old=JSON.parse(localStorage.getItem(SESSION));if(old){user=old;await secureSet(SESSION,JSON.stringify(old));localStorage.removeItem(SESSION)}}catch{}}
 if(user){await loadQueue();try{await refresh();watch()}catch(e){if(e.message.includes('Сессия')){await secureSet(SESSION,null);user=null}else{watch();throw e}}}
 if(!isNative)window.addEventListener('storage',e=>{if(e.key===SESSION)location.reload()});
}
export async function refresh(){
 const meta=await rpc('ritm_poll',{p_token:user.token});
 let changed=false;role=meta.role;
 if(version!==meta.version||sleepStamp!==meta.sleepStamp){
  const row=await rpc('ritm_read',{p_token:user.token});version=row.version;data={...empty(),...row.state,sleep:row.sleep||[]};sleepStamp=meta.sleepStamp;changed=true;
 }
 if(role==='owner'&&healthStamp!==meta.healthStamp){await loadHealth();healthStamp=meta.healthStamp;changed=true}
 if(role!=='owner'){health=[];healthStamp=null}
 else if(changed)queue.forEach(applyLocal);
 return changed;
}
async function loadHealth(){const rows=[];for(let offset=0;;offset+=500){const page=await rpc('ritm_health_read',{p_token:user.token,p_offset:offset});rows.push(...page);if(page.length<500)break}health=rows}
async function pages(name){const rows=[];for(let offset=0;;offset+=500){const page=await rpc(name,{p_token:user.token,p_offset:offset});rows.push(...page);if(page.length<500)break}return rows}
// Состояние записи для интерфейса: 'pending' — ждёт отправки, 'error' — требуется действие, иначе сохранено в облаке.
export const pendingState=id=>queue.find(q=>q.id===id)?.state||null;
const send=op=>op.kind==='sleep'?rpc('ritm_sleep_save',{p_token:user.token,p_id:op.id,p_entry:op.entry,p_revision:op.revision}):rpc('ritm_health_save',{p_token:user.token,p_id:op.id,p_kind:op.kind,p_day:op.day,p_payload:op.payload,p_revision:op.revision});
async function enqueue(op){queue=[...queue.filter(q=>q.id!==op.id),{...op,state:'pending',at:new Date().toISOString()}];await persistQueue()}
export async function flush(){let sent=false;for(const op of [...queue]){if(op.state==='error')continue;try{await send(op);queue=queue.filter(q=>q!==op);sent=true}catch(e){if(offline(e))break;queue=queue.map(q=>q===op?{...q,state:'error',error:e.message}:q)}}await persistQueue();if(sent){sleepStamp=healthStamp=null;await refresh();onChange()}}
export async function dropQueued(id){queue=queue.filter(q=>q.id!==id);await persistQueue()}
// Пока запись ждёт сети, она видна в списке локально, с пометкой «Ожидает отправки».
function applyLocal(op){if(op.kind==='sleep')data={...data,sleep:[{id:op.id,...op.entry,revision:op.revision},...data.sleep.filter(s=>s.id!==op.id)].sort((a,b)=>b.bed.localeCompare(a.bed))};else health=[{id:op.id,kind:op.kind,day:op.day,payload:op.payload,revision:op.revision},...health.filter(h=>h.id!==op.id)]}
async function saveDiary(op){if(role!=='owner')throw Error('Дневник доступен только владельцу');try{await send(op);await dropQueued(op.id);sleepStamp=healthStamp=null;await refresh();return {queued:false}}catch(e){if(offline(e)){await enqueue(op);applyLocal(op);return {queued:true}}if(!e.message.includes('другом устройстве'))throw e;await refresh();throw e}}
export async function saveHealth(entry){if(role!=='owner')throw Error('Дневник доступен только владельцу');
 if(cloud)return saveDiary({kind:entry.kind,id:entry.id,day:entry.day,payload:entry.payload,revision:entry.revision||0});
 const i=health.findIndex(x=>x.id===entry.id);const row={...entry,revision:(entry.revision||0)+1,updated_at:new Date().toISOString()};if(i<0)health.unshift(row);else health[i]=row;localStorage.setItem('ritm-health-v1',JSON.stringify(health));return {queued:false}
}
export async function saveSleep(entry){if(role!=='owner')throw Error('Дневник доступен в профиле владельца');
 const {id,revision=0,...rest}=entry;
 if(cloud)return saveDiary({kind:'sleep',id,entry:rest,revision});
 const row={...rest,id,revision:revision+1};data={...data,sleep:[row,...data.sleep.filter(s=>s.id!==id)].sort((a,b)=>b.bed.localeCompare(a.bed))};localStorage.setItem('ritm-local-v1',JSON.stringify(data));return {queued:false}
}
export async function exportAll(){let history=data.history;if(cloud&&user){await refresh();try{history=await pages('ritm_history_read')}catch{}}return {schemaVersion:3,exportedAt:new Date().toISOString(),timezone:'Europe/Moscow',note:'План: минуты от полуночи МСК даты плана (значения больше 1440 — после полуночи). Фактические отметки и сон — ISO 8601 UTC.',...data,history,...(role==='owner'?{health}:{})}}
export async function save(next,message){if(role!=='owner')throw Error('Доступ только для просмотра');if(cloud){const {sleep,...state}=next;try{await rpc('ritm_save',{p_token:user.token,p_version:version,p_state:state,p_message:message})}catch(e){if(offline(e))throw Error('Нет связи с базой. Изменение плана не сохранено — повторите, когда появится сеть.');if(e.message.includes('другом устройстве'))await refresh();throw e}await refresh()}else{data={...next,history:[{id:crypto.randomUUID(),at:new Date().toISOString(),message},...data.history].slice(0,200)};localStorage.setItem('ritm-local-v1',JSON.stringify(data))}}
export async function login(email){version=-1;sleepStamp=null;healthStamp=null;user=await rpc('ritm_login',{p_email:email.trim().toLowerCase()});await secureSet(SESSION,JSON.stringify(user));await loadQueue();await refresh();watch()}
export async function logout(force=false){if(queue.length&&!force)throw Error(`Есть неотправленные записи дневника (${queue.length}). Дождитесь сети или подтвердите выход с их удалением.`);if(user){try{await rpc('ritm_logout',{p_token:user.token})}catch{}await secureSet(queueKey(),null)}await secureSet(SESSION,null);user=null;data=empty();queue=[];location.reload()}
