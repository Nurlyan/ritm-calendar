// Напоминания и карточка дня. Настройки хранятся на этом устройстве и не синхронизируются между телефонами.
import * as native from './native.js';
import {clock,minutes} from './engine.js';
const KEY='ritm-notify-v1',$=s=>document.querySelector(s),esc=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
export const kindNames={work:'Работа',sport:'Спорт',together:'Вместе',personal:'Для себя',meal:'Обед / отдых',call:'Созвон'};
export const defaults=()=>({enabled:true,card:false,lockDetails:false,snoozeMinutes:10,lead:5,endingLead:10,workMinutes:50,breakMinutes:10,morning:'09:35',bedtime:'00:00',quietFrom:'00:30',quietTo:'09:30',
 types:{start:true,ending:false,breaks:false,mark:true,morning:true,bedtime:true,effect:true},kinds:{work:true,sport:true,together:true,personal:false,meal:false,call:true}});
export function settings(){try{const s=JSON.parse(localStorage.getItem(KEY));if(s)return {...defaults(),...s,types:{...defaults().types,...s.types},kinds:{...defaults().kinds,...s.kinds}}}catch{}return defaults()}
function saveSettings(s){localStorage.setItem(KEY,JSON.stringify(s))}

const at=(day,m)=>new Date(day+'T00:00:00+03:00').getTime()+m*60000; // Москва — постоянный UTC+3
const shift=(day,n)=>{const d=new Date(day+'T12:00:00Z');d.setUTCDate(d.getUTCDate()+n);return d.toISOString().slice(0,10)};
function quiet(s,ms){const t=new Date(ms+3*3600000),m=t.getUTCHours()*60+t.getUTCMinutes();const [a,b]=[s.quietFrom,s.quietTo].map(v=>{const [h,x]=v.split(':').map(Number);return h*60+x});return a<=b?m>=a&&m<b:m>=a||m<b}

/** Полный набор напоминаний на ближайшие дни. Чистая функция: тот же план даёт те же ключи, поэтому дублей нет. */
export function buildReminders({dayPlan,today,now=Date.now(),role,health=[],sleep=[],sleepOpen=null,s=settings(),days=7}){
 // Дни, где подъём уже записан: утреннее напоминание не нужно.
 const woke=new Set(sleep.map(x=>new Intl.DateTimeFormat('sv-SE',{timeZone:'Europe/Moscow'}).format(new Date(x.wake))));
 if(!s.enabled)return [];
 const out=[],push=r=>{if(r.at>now&&!quiet(s,r.at))out.push(r)},owner=role==='owner';
 for(let i=-1;i<days;i++){const day=shift(today,i);
  for(const b of dayPlan(day)){
   // Через 5 минут после конца блока — «Отметьте»: по плану одним нажатием или открыть отметку. Для всех занятий, кроме подготовки ко сну.
   if(owner&&s.types.mark&&!b.actualEnd&&!b.skipped&&b.kind!=='sleep'){const act=op=>({route:'block',op,date:day,id:b.id});push({key:`mark:${day}:${b.id}`,at:at(day,b.end+5),channel:'activities',title:`Отметьте: ${b.title}`,text:`${clock(b.start)}–${clock(b.end)} · получилось по плану?`,action:act('mark'),buttons:[{title:'По плану',action:act('done')},{title:'Не делал',action:act('skip')}],snooze:false})}
   if(b.actualEnd||b.skipped||!s.kinds[b.kind==='call'?'call':b.kind])continue;
   if(s.types.start&&!b.actualStart)push({key:`start:${day}:${b.id}:${b.start}`,at:at(day,b.start-s.lead),channel:'activities',title:s.lead?`Через ${s.lead} мин: ${b.title}`:`Пора: ${b.title}`,text:`${clock(b.start)}–${clock(b.end)} по плану`,route:'today'});
   if(s.types.ending&&b.end-b.start>s.endingLead)push({key:`end:${day}:${b.id}:${b.end}`,at:at(day,b.end-s.endingLead),channel:'activities',title:`Скоро завершение: ${b.title}`,text:`Блок заканчивается в ${clock(b.end)}`,route:'today'});
   if(owner&&s.types.breaks&&b.kind==='work'&&s.workMinutes>=15&&s.breakMinutes>=5)
    for(let t=b.start+s.workMinutes;t+s.breakMinutes<=b.end-10;t+=s.workMinutes+s.breakMinutes){
     push({key:`break:${day}:${b.id}:${t}`,at:at(day,t),channel:'breaks',title:'Сделайте перерыв',text:`${s.breakMinutes} мин. Расписание не меняется.`,route:'today'});
     push({key:`back:${day}:${b.id}:${t}`,at:at(day,t+s.breakMinutes),channel:'breaks',title:'Можно вернуться к работе',text:`${b.title} до ${clock(b.end)}`,route:'today'});
    }
  }
  // Утром — закреплённое напоминание «Я проснулся»; висит, пока подъём не отмечен.
  if(owner&&s.types.morning&&!woke.has(day))push({key:`morning:${day}`,at:at(day,minutes(s.morning)%1440),channel:'wake',title:'Доброе утро',text:sleepOpen?`Вы легли в ${new Date(sleepOpen.bed).toLocaleTimeString('ru-RU',{timeZone:'Europe/Moscow',hour:'2-digit',minute:'2-digit'})}. Нажмите, чтобы отметить подъём`:'Нажмите, чтобы отметить сон и самочувствие',route:'wake',sensitive:true,sticky:true});
  // Вечером — нажатие открывает сразу «Лёг спать».
  if(owner&&s.types.bedtime&&!(sleepOpen&&i===0))push({key:`bed:${day}`,at:at(day,minutes(s.bedtime)),channel:'sleep',title:'Пора готовиться ко сну',text:'Нажмите, когда ляжете: «Лёг спать»',route:'bed'});
 }
 if(owner&&s.types.effect)for(const r of health.filter(r=>r.kind==='migraine'))(r.payload.treatments||[]).forEach((t,i)=>{if(t.remindAt&&t.effect==='unknown')push({key:`effect:${r.id}:${i}:${t.remindAt}`,at:new Date(t.remindAt).getTime(),channel:'diaries',title:'Пора дополнить дневник',text:'Оцените, как подействовало средство',route:`migraine:${r.id}`,sensitive:true,snooze:true})});
 return out.sort((a,b)=>a.at-b.at).slice(0,300);
}

function cardDays(dayPlan,today){const days={};for(const d of [shift(today,-1),today,shift(today,1)])days[d]=dayPlan(d).map(b=>({id:b.id,title:b.title,start:b.start,end:b.end,kind:b.kind,locked:Boolean(b.locked),actualStart:Boolean(b.actualStart),actualEnd:Boolean(b.actualEnd)}));return days}

let last='';
/** Передаёт план в Android. Вызывается после загрузки и любых изменений; одинаковые данные повторно не отправляются. */
export async function sync({dayPlan,today,role,health,sleep=[],sleepOpen=null,loggedIn},restore=false){
 if(!native.isNative)return;
 if(restore)last='';
 const s=settings();
 const reminders=loggedIn?buildReminders({dayPlan,today,role,health,sleep,sleepOpen,s}):[];
 const card={enabled:loggedIn&&s.enabled&&s.card,lockDetails:s.lockDetails,canEdit:role==='owner',sleepOpen:role==='owner'&&sleepOpen?sleepOpen.bed:null,days:loggedIn?cardDays(dayPlan,today):{}};
 const body=JSON.stringify([reminders,card,s.snoozeMinutes,s.lockDetails]);
 if(body===last)return;last=body;
 try{await native.schedule(reminders,{snoozeMinutes:s.snoozeMinutes,lockDetails:s.lockDetails});await native.card(card,restore)}catch(e){last=''}
}
export const resync=()=>{last=''};

const sw=(name,label,on,hint='')=>`<label class="switch"><span><strong>${label}</strong>${hint?`<small>${hint}</small>`:''}</span><input type="checkbox" data-set="${name}" ${on?'checked':''}><i aria-hidden="true"></i></label>`;
export async function settingsView(role){
 const s=settings(),owner=role==='owner';
 if(!native.isNative)return `<p class="eyebrow">УВЕДОМЛЕНИЯ</p><h1>Напоминания</h1><section class="glass section"><p>Напоминания и карточка дня работают в Android-приложении «Ритм». На сайте они недоступны.</p></section>`;
 const st=await native.status().catch(()=>null)||{};
 const problem=!st.notifications?`<div class="notice warning"><strong>Уведомления выключены</strong><p>Без разрешения Android не покажет ни напоминания, ни карточку дня.</p><button class="primary" id="perm-notify">Разрешить уведомления</button> <button data-open-settings="notifications">Открыть настройки Android</button></div>`:'';
 return `<p class="eyebrow">УВЕДОМЛЕНИЯ · ЭТОТ ТЕЛЕФОН</p><h1>Напоминания</h1><p class="lead">Настройки действуют только на этом устройстве. Отложенное уведомление не переносит занятие, сон или созвон.</p>${problem}
 <section class="glass section settings-list"><h2>Главное</h2>${sw('enabled','Напоминания включены',s.enabled)}${sw('card','Карточка дня в шторке',s.card,'Текущее и следующее занятие. Обновляется на границах занятий, без постоянной работы в фоне.')}${s.card&&st.notifications&&!st.cardVisible?`<button id="card-restore">Вернуть карточку в шторку</button>`:''}${sw('lockDetails','Подробности на экране блокировки',s.lockDetails,'Записи дневников на заблокированном экране всегда показываются нейтрально.')}
 <div class="form-row"><label>Отложить на, мин<input type="number" min="5" max="120" data-num="snoozeMinutes" value="${s.snoozeMinutes}"></label><label>Тихие часы<span class="pair"><input type="time" data-time="quietFrom" value="${s.quietFrom}"><input type="time" data-time="quietTo" value="${s.quietTo}"></span></label></div></section>
 <section class="glass section settings-list"><h2>Занятия</h2>${sw('types.start','Начало занятия',s.types.start)}<label>Предупреждать за<select data-num="lead">${[0,5,10,15].map(n=>`<option value="${n}" ${s.lead===n?'selected':''}>${n?n+' мин':'в момент начала'}</option>`).join('')}${[0,5,10,15].includes(s.lead)?'':`<option value="${s.lead}" selected>${s.lead} мин</option>`}</select></label><label>Своё значение, мин<input type="number" min="0" max="120" data-num="lead" value="${s.lead}"></label>${sw('types.mark','Отметить прошедшее занятие',s.types.mark)}${sw('types.ending','Скоро закончить блок',s.types.ending)}<label>За сколько минут до конца<input type="number" min="1" max="60" data-num="endingLead" value="${s.endingLead}"></label><p class="subtle">Категории занятий</p><div class="chips">${Object.entries(kindNames).map(([k,v])=>`<label class="chip-check"><input type="checkbox" data-set="kinds.${k}" ${s.kinds[k]?'checked':''}>${v}</label>`).join('')}</div></section>
 ${owner?`<section class="glass section settings-list"><h2>Перерывы</h2>${sw('types.breaks','Перерывы в рабочих блоках',s.types.breaks,'По умолчанию выключены. Только уведомления — расписание не меняется.')}<div class="form-row"><label>Работать, мин<input type="number" min="15" max="180" data-num="workMinutes" value="${s.workMinutes}"></label><label>Перерыв, мин<input type="number" min="5" max="60" data-num="breakMinutes" value="${s.breakMinutes}"></label></div></section>
 <section class="glass section settings-list"><h2>Сон и дневники</h2>${sw('types.morning','Утром отметить подъём',s.types.morning,'Закреплённое уведомление; исчезает, когда подъём отмечен.')}<label>Время утреннего напоминания<input type="time" data-time="morning" value="${s.morning}"></label>${sw('types.bedtime','Подготовка ко сну',s.types.bedtime)}<label>Время напоминания<input type="time" data-time="bedtime" value="${s.bedtime}"></label>${sw('types.effect','Оценить эффект средства',s.types.effect,'Только если вы сами указали время напоминания в записи приступа.')}</section>`:''}
 <section class="glass section settings-list"><h2>Состояние Android</h2>
 <div class="status-row"><span>Уведомления</span><strong>${st.notifications?'разрешены':'выключены'}</strong></div>
 <div class="status-row"><span>Точное время</span><strong>${st.exactAlarms?'доступно':'примерное (до ~10 мин)'}</strong></div>
 <div class="status-row"><span>«Не беспокоить»</span><strong>${st.dnd?'включено — Android может скрыть звук':'выключено'}</strong></div>
 <div class="status-row"><span>Запланировано напоминаний</span><strong>${st.scheduled??0}</strong></div><div class="status-row"><span>Проверка предложений</span><strong>${st.inboxStatus?esc(st.inboxStatus)+(st.inboxAt?" · "+new Date(st.inboxAt).toLocaleTimeString("ru-RU",{hour:"2-digit",minute:"2-digit"}):""):"ещё не запускалась"}</strong></div>
 <p class="subtle">Звук, вибрация и важность каждого типа настраиваются в каналах Android: «Текущий день», «Занятия», «Перерывы», «Дневники», «Сон», «Подъём», «Предложения».</p>
 <div class="export-buttons"><button id="test-notify">Тестовое уведомление</button><button data-open-settings="notifications">Каналы и звук</button>${st.exactAlarms?'':'<button data-open-settings="exactAlarms">Разрешить точное время</button>'}</div>
 <p class="subtle">Изменения с другого устройства попадают в напоминания после открытия приложения и синхронизации — мгновенной фоновой доставки нет. Новые предложения и ответы на них проверяются в фоне примерно раз в 15 минут, когда есть сеть; в режиме экономии Android может проверять реже. После принудительной остановки приложения Android не показывает его напоминания до следующего запуска.</p></section>`;
}
export function bindSettings({render,notify,refresh}){
 const update=fn=>{const s=settings();fn(s);saveSettings(s);resync();native.haptic();refresh();render()};
 document.querySelectorAll('[data-set]').forEach(el=>el.onchange=()=>update(s=>{const [a,b]=el.dataset.set.split('.');if(b)s[a][b]=el.checked;else s[a]=el.checked}));
 document.querySelectorAll('[data-num]').forEach(el=>el.onchange=()=>{const v=Math.round(Number(el.value));if(!Number.isFinite(v)||v<Number(el.min)||v>Number(el.max))return notify(`Допустимо от ${el.min} до ${el.max}`);update(s=>{s[el.dataset.num]=v})});
 document.querySelectorAll('[data-time]').forEach(el=>el.onchange=()=>{if(el.value)update(s=>{s[el.dataset.time]=el.value})});
 document.querySelectorAll('[data-open-settings]').forEach(el=>el.onclick=()=>native.openSettings(el.dataset.openSettings).catch(e=>notify(e.message)));
 if($('#perm-notify'))$('#perm-notify').onclick=async()=>{const st=await native.requestNotifications().catch(()=>null);if(!st?.notifications)notify('Разрешение не выдано. Его можно включить в настройках Android.');resync();refresh();render()};
 if($('#test-notify'))$('#test-notify').onclick=()=>native.test('activities').then(()=>notify('Тестовое уведомление отправлено')).catch(e=>notify(e.message));
 if($('#card-restore'))$('#card-restore').onclick=async()=>{resync();await refresh(true);notify('Карточка возвращена');render()};
}
