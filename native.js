// Мост к нативному модулю Android. На сайте все функции безопасно отключены.
const cap=globalThis.Capacitor;
export const isNative=Boolean(cap?.isNativePlatform?.());
// Без сборщика @capacitor/core не подключается, поэтому вызываем мост Capacitor напрямую.
const call=(name,args={})=>isNative?cap.nativePromise('RitmNative',name,args):Promise.resolve(null);

export const secureGet=async key=>isNative?(await call('secureGet',{key}))?.value??null:localStorage.getItem(key);
export const secureSet=(key,value)=>isNative?call('secureSet',{key,value}):value===null?localStorage.removeItem(key):localStorage.setItem(key,value);
export const status=()=>call('status');
export const requestNotifications=()=>call('requestNotifications');
export const openSettings=(target,channel)=>call('openSettings',{target,channel});
export const schedule=(reminders,settings)=>call('schedule',{reminders,settings});
export const card=(data,restore=false)=>call('card',{card:data,restore});
export const test=channel=>call('test',{channel});
export const haptic=(kind='tick')=>{if(isNative)call('haptic',{kind}).catch(()=>{})};
export function onAction(handler){if(isNative)cap.nativeCallback('RitmNative','addListener',{eventName:'action'},handler)}

function download(name,body,type){const url=URL.createObjectURL(new Blob([body],{type}));const a=document.createElement('a');a.href=url;a.download=name;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000)}
// mode: 'save' — системный выбор места, 'share' — меню «Поделиться». На сайте — обычное скачивание.
export async function exportFile(name,body,type,mode='save'){
 if(!isNative){download(name,body,type);return true}
 if(mode==='share'){await call('shareFile',{name,content:body,mime:type});return true}
 return Boolean((await call('saveFile',{name,content:body,mime:type}))?.saved);
}
