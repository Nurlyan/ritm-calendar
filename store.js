const config=window.RITM_CONFIG||{};
export const cloud=Boolean(config.supabaseUrl&&config.supabaseKey&&config.calendarId);
let client,user,role='owner',version=0;
export const getRole=()=>role;
export const session=()=>user;
const empty=()=>({plan:{},backlog:[],history:[],sleep:[]});
export let data=empty();
export async function init(onChange){if(!cloud){try{data=JSON.parse(localStorage.getItem('ritm-local-v1'))||empty()}catch{data=empty()}window.addEventListener('storage',()=>{try{data=JSON.parse(localStorage.getItem('ritm-local-v1'))||empty();onChange()}catch{}});return}
 const {createClient}=await import('https://esm.sh/@supabase/supabase-js@2.57.4');client=createClient(config.supabaseUrl,config.supabaseKey);const {data:s,error}=await client.auth.getSession();if(error)throw error;user=s.session?.user;role='viewer';if(!user)return;await refresh();client.channel('ritm').on('postgres_changes',{event:'UPDATE',schema:'public',table:'calendars',filter:`id=eq.${config.calendarId}`},async()=>{try{await refresh();onChange()}catch(e){window.dispatchEvent(new CustomEvent('sync-error',{detail:e.message}))}}).subscribe(status=>{window.dispatchEvent(new CustomEvent('sync-status',{detail:status}))});
 // Reconcile after reconnect and across missed websocket events.
 setInterval(async()=>{try{await refresh();onChange()}catch{}},20000);
}
export async function refresh(){const {data:row,error}=await client.from('calendars').select('*').eq('id',config.calendarId).single();if(error)throw error;role=row.owner_id===user.id?'owner':'viewer';version=row.version;data={...empty(),...row.state,sleep:[]};if(role==='owner'){const r=await client.from('sleep_entries').select('*').eq('owner_id',user.id).order('bed',{ascending:false});if(r.error)throw r.error;data.sleep=r.data}}
export async function save(next,message){if(role!=='owner')throw Error('Доступ только для просмотра');const history=[{id:crypto.randomUUID(),at:new Date().toISOString(),message},...data.history].slice(0,200);const updated={...next,history};if(cloud){if(!user)throw Error('Сначала войдите');const {sleep,...state}=updated;const {data:row,error}=await client.from('calendars').update({state,version:version+1}).eq('id',config.calendarId).eq('version',version).select('version').maybeSingle();if(error)throw error;if(!row){await refresh();throw Error('Расписание изменилось на другом устройстве. Повторите действие.')}version=row.version}else localStorage.setItem('ritm-local-v1',JSON.stringify(updated));data=updated}
export async function addSleep(entry){if(role!=='owner')throw Error('Дневник доступен только владельцу');if(cloud){const {error}=await client.from('sleep_entries').insert({...entry,owner_id:user.id});if(error)throw error;await refresh()}else{const next={...data,sleep:[{...entry,id:crypto.randomUUID()},...data.sleep]};localStorage.setItem('ritm-local-v1',JSON.stringify(next));data=next}}
export async function login(email){if(!client)throw Error('Облако ещё не подключено');const {error}=await client.auth.signInWithOtp({email,options:{shouldCreateUser:false,emailRedirectTo:location.origin+location.pathname}});if(error)throw error}
export async function logout(){await client.auth.signOut();location.reload()}
