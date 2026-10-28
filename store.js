const config=window.RITM_CONFIG||{};
export const cloud=Boolean(config.supabaseUrl&&config.supabaseKey&&config.emailAccess);
let user=null,role='viewer',version=0,onChange=()=>{},timer,reading=false;
export const getRole=()=>role;
export const session=()=>user;
const empty=()=>({plan:{},backlog:[],history:[],sleep:[]});
export let data=empty();
async function rpc(name,args){const response=await fetch(config.supabaseUrl+'/rest/v1/rpc/'+name,{method:'POST',headers:{apikey:config.supabaseKey,'Content-Type':'application/json'},body:JSON.stringify(args),signal:AbortSignal.timeout(15000)});const text=await response.text();const body=text?JSON.parse(text):null;if(!response.ok)throw Error(body?.message||'Не удалось связаться с базой');return body}
function watch(){clearInterval(timer);timer=setInterval(async()=>{if(!user||document.hidden||reading||document.querySelector('dialog[open]')||document.activeElement?.closest('form'))return;reading=true;try{const changed=await refresh();window.dispatchEvent(new CustomEvent('sync-status',{detail:'SUBSCRIBED'}));if(changed)onChange()}catch(e){window.dispatchEvent(new CustomEvent('sync-error',{detail:e.message}))}finally{reading=false}},5000)}
export async function init(callback){onChange=callback;if(!cloud){role='owner';try{data=JSON.parse(localStorage.getItem('ritm-local-v1'))||empty()}catch{}return}
 try{user=JSON.parse(localStorage.getItem('ritm-session-v2'))}catch{}
 if(user){try{await refresh();watch()}catch(e){if(e.message.includes('Сессия')){localStorage.removeItem('ritm-session-v2');user=null}else throw e}}
 window.addEventListener('storage',e=>{if(e.key==='ritm-session-v2')location.reload()});
}
export async function refresh(){const row=await rpc('ritm_read',{p_token:user.token});const changed=version!==row.version||JSON.stringify(data.sleep)!==JSON.stringify(row.sleep||[]);role=row.role;version=row.version;data={...empty(),...row.state,sleep:row.sleep||[]};return changed}
export async function save(next,message){if(role!=='owner')throw Error('Доступ только для просмотра');if(cloud){const {sleep,...state}=next;try{await rpc('ritm_save',{p_token:user.token,p_version:version,p_state:state,p_message:message})}catch(e){if(e.message.includes('другом устройстве'))await refresh();throw e}await refresh()}else{data={...next,history:[{id:crypto.randomUUID(),at:new Date().toISOString(),message},...data.history].slice(0,200)};localStorage.setItem('ritm-local-v1',JSON.stringify(data))}}
export async function addSleep(entry){if(role!=='owner')throw Error('Дневник доступен в профиле владельца');if(cloud){await rpc('ritm_add_sleep',{p_token:user.token,p_entry:entry});await refresh()}else{data={...data,sleep:[{...entry,id:crypto.randomUUID()},...data.sleep]};localStorage.setItem('ritm-local-v1',JSON.stringify(data))}}
export async function login(email){user=await rpc('ritm_login',{p_email:email.trim().toLowerCase()});localStorage.setItem('ritm-session-v2',JSON.stringify(user));await refresh();watch()}
export async function logout(){if(user)await rpc('ritm_logout',{p_token:user.token});localStorage.removeItem('ritm-session-v2');user=null;data=empty();location.reload()}
