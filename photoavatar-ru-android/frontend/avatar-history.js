/** Persistent history of selected Runway avatars and locally prepared references.
 * Version entries remain device-local (IndexedDB). Nothing is uploaded until
 * user explicitly creates/updates an avatar in Runway.
 */
const NAME='PhotoAvatarRUProHistory';
let dbPromise;
function open() {
  if(dbPromise)return dbPromise;
  dbPromise=new Promise((resolve,reject)=>{
    const request=indexedDB.open(NAME,1);
    request.onupgradeneeded=()=>{
      const store=request.result.createObjectStore('versions',{keyPath:'id',autoIncrement:true});
      store.createIndex('created','created',{unique:false});
    };
    request.onerror=()=>reject(request.error);
    request.onsuccess=()=>resolve(request.result);
  });
  return dbPromise;
}
function wait(req) {
  return new Promise((resolve,reject)=>{
    req.onsuccess=()=>resolve(req.result);
    req.onerror=()=>reject(req.error);
  });
}
async function transaction(mode,operation) {
  const db=await open();
  const tx=db.transaction('versions',mode);
  const store=tx.objectStore('versions');
  const result=await operation(store);
  await new Promise((resolve,reject)=>{
    tx.oncomplete=resolve;
    tx.onerror=()=>reject(tx.error);
    tx.onabort=()=>reject(tx.error || new Error('Ошибка истории'));
  });
  return result;
}
export async function addHistory(entry) {
  if(!entry || typeof entry.avatarId!=='string')throw new Error('Avatar ID обязателен для истории');
  const safeImage=typeof entry.image==='string'&& /^data:image\/jpeg;base64,/.test(entry.image) &&
    entry.image.length<2_800_000 ? entry.image : '';
  const record={
    avatarId:entry.avatarId,
    name:(entry.name||'Собеседник').slice(0,70),
    label:(entry.label||'Версия').slice(0,90),
    kind:entry.kind||'created',
    processing:entry.processing==='none'?'none':'optimize',
    created:Date.now(),
    image:safeImage,
    thumb:entry.thumb&&entry.thumb.length<120_000?entry.thumb:safeImage.length<100_000?safeImage:'',
    note:(entry.note||'').slice(0,260)
  };
  const id=await transaction('readwrite',store=>wait(store.add(record)));
  // Limit history entries so the app does not silently fill phone storage.
  const list=await listHistory();
  if(list.length>25) {
    for(const old of list.slice(25))await deleteHistory(old.id);
  }
  return id;
}
export async function listHistory() {
  const result=await transaction('readonly',store=>wait(store.getAll()));
  return result.sort((a,b)=>b.created-a.created || b.id-a.id);
}
export async function deleteHistory(id) {
  return transaction('readwrite',store=>wait(store.delete(Number(id))));
}
export async function getHistory(id) {
  return transaction('readonly',store=>wait(store.get(Number(id))));
}
export async function makeThumb(imageData) {
  if(!imageData)return '';
  const img=new Image();img.src=imageData;await img.decode();
  const canvas=document.createElement('canvas');canvas.width=185;canvas.height=120;
  canvas.getContext('2d').drawImage(img,0,0,185,120);
  return canvas.toDataURL('image/jpeg',.52);
}
