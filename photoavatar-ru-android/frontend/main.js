import { streamTo, AvatarEvent } from '@runwayml/avatars';

const $ = (id) => document.getElementById(id);
const pending = new Map();
let sequence = 0;
let image = null;
let imageUrl = '';
let activeTile = -1;
let avatarId = '';
let currentCall = null;
let inCall = false;
let micMuted = false;
let speakerMuted = false;
let busy = false;
let callSessionId = '';
let callGeneration = 0;
let callStartedAt = 0;
let callTimer = null;
let callStage = 'Не подключено';
let lastCallError = '';
let localStream = null;
let cameraFacing = 'user';
let toastTimer = null;


const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

window.__nativeReply = function(id, reply) {
  const task = pending.get(id);
  if (!task) return;
  pending.delete(id);
  clearTimeout(task.timer);
  if (reply && reply.ok) task.resolve(reply.data);
  else task.reject(new Error((reply && reply.error) || 'Ошибка Android API'));
};

function native(action, request, timeout = 60000) {
  return new Promise((resolve, reject) => {
    if (!window.PhotoAvatarNative) {reject(new Error('Приложение нужно запускать через Android APK.'));return;}
    const id = String(++sequence);
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error('Runway не ответил в отведённое время'));
    }, timeout);
    pending.set(id, {resolve,reject,timer});
    try {window.PhotoAvatarNative.request(id, action, JSON.stringify(request || {}));}
    catch (e) {clearTimeout(timer);pending.delete(id);reject(e);}
  });
}

function status(message, failed = false) {
  const element = $('status');
  element.textContent = message;
  element.className = 'status' + (failed ? ' bad' : ' ok');
}
function labelError(e) {
  const detail = e && e.message ? e.message : String(e);
  status(detail,true);
}
function setBusy(state) {
  busy = state;
  for (const id of ['createAvatar','testConnection','startCall','saveKey']) $(id).disabled = state;
}
function remember() {
  for (const id of ['name','topic','persona','voiceMode','voiceId']) {
    try {localStorage.setItem('runway_'+id, $(id).value);}catch(e){}
  }
}
function restore() {
  for (const id of ['name','topic','persona','voiceMode','voiceId']) {
    const s = localStorage.getItem('runway_'+id);
    if (s !== null) $(id).value = s;
    $(id).addEventListener('change', remember);
  }
  avatarId = localStorage.getItem('runway_avatar_id') || '';
  updateReady();
  updateVoiceMode();
}

function updateVoiceMode() {
  $('voiceFields').classList.toggle('hidden', $('voiceMode').value !== 'custom');
}
$('voiceMode').addEventListener('change',updateVoiceMode);

function updateReady() {
  $('readyCard').classList.toggle('hidden', !UUID.test(avatarId));
  $('readyId').textContent = UUID.test(avatarId) ? 'Avatar ID: ' + avatarId : '';
}

function asDataUrl(file) {
  return new Promise((resolve,reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = () => reject(new Error('Не удалось прочитать файл'));
    reader.readAsDataURL(file);
  });
}

async function loadImage(file) {
  if (!file || !file.type.startsWith('image/')) throw new Error('Выбери фотографию JPEG или PNG.');
  if (file.size > 18 * 1024 * 1024) throw new Error('Для фотографии допускается файл не более 18 МБ.');
  if (imageUrl) URL.revokeObjectURL(imageUrl);
  imageUrl = URL.createObjectURL(file);
  const img = new Image();
  img.src = imageUrl;
  await img.decode();
  image = img;
  activeTile = -1;
  $('preview').src = imageUrl;
  $('preview').style.display = 'block';
  $('tileBlock').classList.remove('hidden');
  $('photoHint').textContent = 'Выбран полный кадр. Для коллажа 3×3 нажми на один фронтальный ракурс ниже.';
  drawTiles();
}

function drawTiles() {
  const el = $('tiles');
  el.replaceChildren();
  if (!image) return;
  for(let tile=0; tile<9; tile++) {
    const col=tile%3, row=Math.floor(tile/3);
    const button=document.createElement('button');
    button.type='button';
    button.setAttribute('aria-label','Использовать ракурс '+(tile+1));
    button.setAttribute('aria-pressed', String(activeTile===tile));
    const canvas=document.createElement('canvas');
    canvas.width=200;canvas.height=165;
    const ctx=canvas.getContext('2d');
    const sw=image.naturalWidth/3, sh=image.naturalHeight/3;
    const scale=Math.max(canvas.width/sw,canvas.height/sh);
    const cropW=canvas.width/scale,cropH=canvas.height/scale;
    const sx=col*sw+(sw-cropW)/2,sy=row*sh+(sh-cropH)/2;
    ctx.drawImage(image,sx,sy,cropW,cropH,0,0,canvas.width,canvas.height);
    const badge=document.createElement('em'); badge.textContent=(tile+1);
    button.append(canvas,badge);
    button.onclick=()=> {
      activeTile=tile;
      $('photoHint').textContent='Выбран ракурс №'+(tile+1)+'. В Runway будет передана только эта ячейка.';
      drawTiles();
    };
    el.append(button);
  }
}

$('photo').addEventListener('change',async(e)=>{
  try { await loadImage(e.target.files[0]);status('Фото загружено. Теперь настрой голос и описание собеседника.');}
  catch(ex){labelError(ex);}
});
$('useFull').onclick=()=> {activeTile=-1;drawTiles();$('photoHint').textContent='Будет использована вся фотография целиком.';};

async function encodePortrait() {
  if(!image)throw new Error('Сначала выбери фотографию.');
  const canvas=document.createElement('canvas'), W=1088,H=704;
  canvas.width=W;canvas.height=H;
  const ctx=canvas.getContext('2d');
  ctx.fillStyle='#233b41';
  ctx.fillRect(0,0,W,H);
  const rect= activeTile<0
    ? {x:0,y:0,w:image.naturalWidth,h:image.naturalHeight}
    : {x:(activeTile%3)*image.naturalWidth/3,y:Math.floor(activeTile/3)*image.naturalHeight/3,w:image.naturalWidth/3,h:image.naturalHeight/3};
  const fit=Math.min(W/rect.w,H/rect.h);
  const dw=rect.w*fit,dh=rect.h*fit;
  ctx.drawImage(image,rect.x,rect.y,rect.w,rect.h,(W-dw)/2,(H-dh)/2,dw,dh);
  for (const quality of [0.9,0.8,0.65]) {
    const encoded=canvas.toDataURL('image/jpeg',quality);
    if(encoded.length<4_800_000)return encoded;
  }
  throw new Error('Портрет слишком большой для Runway. Используй более компактный файл.');
}

function personaText() {
  const name=$('name').value.trim() || 'Собеседник';
  const description=$('persona').value.trim();
  const topic=$('topic').value.trim();
  return [
    'Ты интерактивный видеоаватар по имени '+name+'.',
    'Всегда говори на русском языке. Если спросили об идентичности, объясни, что ты цифровой персонаж, созданный по фотографии.',
    'Говори естественно, поддерживай диалог, используй короткие осмысленные ответы. Не переходи на английский без просьбы.',
    'Характер и стиль: '+description,
    'Предыстория и тема разговора: '+topic
  ].join('\n');
}

$('saveKey').onclick = async () => {
  try {
    const key=$('manualKey').value.trim();
    await native('setKey',{key});
    $('manualKey').value='';
    $('keyStatus').textContent='Ключ сохранён только на этом устройстве.';
    status('Ключ сохранён. Теперь проверь подключение.');
  }catch(e){labelError(e);}
};

$('testConnection').onclick = async () => {
  if(busy)return;setBusy(true);status('Проверяю доступ к Runway Dev…');
  try {
    await native('check',{},35000);
    status('Runway Dev подтвердил авторизацию. Персонажей можно создавать.');
  }catch(e){labelError(e);}
  finally{setBusy(false);}
};

$('createAvatar').onclick = async () => {
  if(busy)return;
  if(!$('consent').checked){status('Подтверди право использовать фото и голос, а также возможные расходы Runway.',true);return;}
  if(!image){status('Добавь фото или коллаж и выбери ракурс.',true);return;}
  remember(); setBusy(true);
  try {
    const photo=await encodePortrait();
    let voiceType='preset', voiceId=$('voiceMode').value;
    if(voiceId==='custom') {
      voiceType='custom';
      voiceId=$('voiceId').value.trim();
      if(!UUID.test(voiceId)) {
        const file=$('voiceFile').files[0];
        if(!file)throw new Error('Загрузи аудиореференс или укажи существующий ID голоса Runway.');
        if(file.size>3_500_000)throw new Error('Сожми аудиофайл до 3,5 МБ для загрузки в API.');
        status('Создаю голос по референсу. Эта операция может расходовать кредиты…');
        const ref=await asDataUrl(file);
        const voice=await native('createVoice',{name:$('name').value+' — голос',audio:ref},60000);
        voiceId=voice.id;
        $('voiceId').value=voiceId;
        remember();
        status('Ожидаю обработки клонированного голоса…');
        await native('voiceReady',{voiceId},205000);
      }
    }
    status('Создаю персонажа в Runway Characters…');
    const created=await native('createAvatar',{
      photo,name:$('name').value.trim(),personality:personaText(),voiceType,voiceId
    },80000);
    avatarId=created.id;
    localStorage.setItem('runway_avatar_id',avatarId);
    updateReady();
    status('Персонаж создан. Нажми «Начать видеозвонок».');
    $('readyCard').scrollIntoView({behavior:'smooth'});
  }catch(e){labelError(e);}
  finally{setBusy(false);}
};
$('resetAvatar').onclick = () => {
  avatarId='';
  localStorage.removeItem('runway_avatar_id');
  updateReady();
  status('Можно создать нового персонажа. Ранее созданный персонаж остаётся в Runway Dev.');
};


function setCallState(value) {
  $('callState').textContent=value;
  $('loadingText').textContent=value;
}
function setCallStage(stage, message) {
  callStage = stage;
  setCallState(message);
  $('diagnosticOutput').textContent = 'Этап: ' + stage + '\nСтатус: ' + message;
}
function showCallToast(text) {
  const el = $('callToast');
  el.textContent = text;
  el.classList.remove('hidden');
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.add('hidden'), 4800);
}
function appendTranscript(entry) {
  if(!entry || !entry.text)return;
  const d=document.createElement('div');
  d.textContent=(entry.role==='user'?'Вы: ':'Аватар: ')+entry.text;
  $('transcript').append(d);
  $('transcript').scrollTop=$('transcript').scrollHeight;
  while($('transcript').children.length>14)$('transcript').firstElementChild.remove();
}
function updateSpeaker() {
  $('avatarAudio').muted = speakerMuted;
  // Core SDK may have created an automatic audio element before attachAudio() ran.
  // Mute that fallback to avoid duplicate playback; use our controllable audio element.
  if(currentCall && currentCall.autoAudioElement) currentCall.autoAudioElement.muted = true;
  $('speaker').classList.toggle('off',speakerMuted);
  $('speaker').setAttribute('aria-pressed',String(!speakerMuted));
  $('speakerLabel').textContent=speakerMuted?'Звук выкл.':'Звук';
}
function updateCameraUI() {
  const enabled = !!(currentCall && currentCall.camera && currentCall.camera.isEnabled);
  $('selfVideo').classList.toggle('hidden',!enabled || !localStream);
  $('selfPlaceholder').classList.toggle('hidden',enabled && !!localStream);
  $('flipCamera').classList.toggle('hidden',!enabled);
  $('selfCamera').classList.toggle('off',!enabled);
  $('selfCamera').setAttribute('aria-pressed',String(enabled));
  $('cameraLabel').textContent=enabled?'Камера':'Камера выкл.';
}
function attachSelfPreview(track) {
  if(!inCall || !track)return;
  // Use the exact LiveKit track already published to Runway, not a second camera stream.
  localStream=new MediaStream([track]);
  $('selfVideo').srcObject=localStream;
  $('selfVideo').play().catch(()=>{});
  updateCameraUI();
}
async function stopSelfCamera() {
  if(currentCall && currentCall.camera && currentCall.camera.isEnabled)
    await currentCall.camera.disable();
  localStream=null;
  const video=$('selfVideo');
  video.pause(); video.srcObject=null;
  updateCameraUI();
}
async function startSelfCamera() {
  if(!currentCall || !currentCall.camera)throw new Error('Камера будет доступна после соединения');
  await currentCall.camera.enable();
  if(!currentCall.camera.isEnabled)
    throw new Error('Камера недоступна для этого голоса или не разрешена в Android');
  if(currentCall.localVideoTrack)attachSelfPreview(currentCall.localVideoTrack);
  updateCameraUI();
}
async function toggleSelfCamera() {
  if(!inCall)return;
  try {if(currentCall?.camera?.isEnabled)await stopSelfCamera();else await startSelfCamera();}
  catch(e){showCallToast('Камера: '+(e.message || 'нет разрешения'));}
}
async function endCall() {
  if(!inCall&&!currentCall&&!callSessionId)return;
  callGeneration++;
  inCall = false;
  if(callTimer){clearInterval(callTimer);callTimer=null;}
  const oldCall=currentCall;currentCall=null;
  const oldSession=callSessionId;callSessionId='';
  try {if(oldCall) await oldCall.end();}catch(e){}
  if(oldSession) native('cancelSession',{sessionId:oldSession},25000).catch(()=>{});
  await stopSelfCamera();
  const remoteVideo=$('avatarVideo'), remoteAudio=$('avatarAudio');
  remoteVideo.pause(); remoteVideo.srcObject=null;
  remoteAudio.pause(); remoteAudio.srcObject=null;
  $('callScreen').classList.remove('active');
  $('connecting').classList.remove('hidden');
  $('callError').classList.add('hidden');
  $('callDiagnostics').classList.add('hidden');
  $('callToast').classList.add('hidden');
  if(lastCallError)status(lastCallError,true);
  else status('Видеозвонок завершён. Можно позвонить снова.');
}
function showCallFailure(error, stage) {
  const msg = String(error && error.message ? error.message : error).slice(0,400);
  lastCallError = 'Ошибка видеозвонка ['+stage+']: '+msg;
  $('callErrorMessage').textContent = msg;
  $('callErrorStage').textContent = 'Этап: '+stage;
  $('diagnosticOutput').textContent =
    'Время: '+new Date().toLocaleString('ru-RU')+'\nЭтап: '+stage+'\nОшибка: '+msg+
    '\nAndroid: '+(navigator.userAgent || 'неизвестно').slice(0,200);
  setCallState('Не удалось подключиться');
  $('connecting').classList.add('hidden');
  $('callError').classList.remove('hidden');
  // Keep advanced diagnostics behind the info button; show the readable error card.
  $('callDiagnostics').classList.add('hidden');
  status(lastCallError,true);
}
window.__onAndroidBack=()=>{if(inCall){endCall();return true;}return false;};
$('callBack').onclick=()=>endCall();
$('callInfo').onclick=()=>$('callDiagnostics').classList.toggle('hidden');
$('callErrorClose').onclick=()=>endCall();
$('selfCamera').onclick=()=>toggleSelfCamera();
$('flipCamera').onclick=async()=>{
  if(!currentCall || !currentCall.camera.isEnabled)return;
  try {
    const devices=(await navigator.mediaDevices.enumerateDevices()).filter(d=>d.kind==='videoinput');
    const currentId=currentCall.localVideoTrack?.getSettings()?.deviceId || '';
    const alternate=devices.find(d=>d.deviceId && d.deviceId!==currentId);
    if(!alternate)throw new Error('Другая камера недоступна');
    await currentCall.camera.setDevice(alternate.deviceId);
    if(currentCall.localVideoTrack)attachSelfPreview(currentCall.localVideoTrack);
    showCallToast('Камера переключена');
  } catch(e){showCallToast('Смена камеры: '+(e.message||'не получилось'));}
};

$('startCall').onclick = async () => {
  if(busy||!UUID.test(avatarId)) {
    if(!UUID.test(avatarId))status('Персонаж не готов. Сначала создай аватар.',true);
    return;
  }
  if(!$('consent').checked){
    status('Подтверди согласие на использование фото/голоса и возможные расходы Runway.',true);
    return;
  }
  remember();setBusy(true);inCall=true;
  const generation=++callGeneration;
  lastCallError=''; callSessionId='';
  micMuted=false;speakerMuted=false;
  $('mute').classList.remove('off');
  $('mute').setAttribute('aria-pressed','true');
  $('micLabel').textContent='Микрофон';
  $('callName').textContent=$('name').value.trim() || 'Собеседник';
  if(imageUrl){
    $('callPhoto').src=imageUrl;
    $('callPhoto').classList.remove('hidden');
    $('callPhotoFallback').classList.add('hidden');
  } else {
    $('callPhoto').classList.add('hidden');
    $('callPhotoFallback').classList.remove('hidden');
  }
  $('transcript').replaceChildren();
  $('callScreen').classList.add('active');
  $('connecting').classList.remove('hidden');
  $('callError').classList.add('hidden');
  $('callDiagnostics').classList.add('hidden');
  updateCameraUI(); updateSpeaker();
  $('callTime').textContent='00:00';
  setCallStage('1/3 — создание сессии','Вызываем собеседника…');
  callStartedAt=Date.now();
  if(callTimer)clearInterval(callTimer);
  callTimer=setInterval(()=>{
    if(!inCall)return;
    const seconds=Math.floor((Date.now()-callStartedAt)/1000);
    $('callTime').textContent=String(Math.floor(seconds/60)).padStart(2,'0')+':'+String(seconds%60).padStart(2,'0');
  },1000);
  try {
    const creds=await native('startSession',{
      avatarId,
      personality:personaText(),
      startScript:'Привет! Рад нашей встрече. О чём поговорим?'
    },120000);
    if(!inCall||generation!==callGeneration) {
      if(creds.sessionId)native('cancelSession',{sessionId:creds.sessionId},25000).catch(()=>{});
      return;
    }
    callSessionId=creds.sessionId||'';
    setCallStage('2/3 — WebRTC','Подключаем видео и микрофон…');
    // @runwayml/avatars 0.17.0 consumes the one-time sessionKey itself.
    // The target MUST be HTMLVideoElement; default camera publishing is off
    // until the user explicitly enables it with the camera button.
    const session=await streamTo({credentials:creds,target:$('avatarVideo'),audio:true,video:false});
    if(!inCall||generation!==callGeneration){await session.end();return;}
    currentCall=session;
    session.attachAudio($('avatarAudio'));
    if(session.autoAudioElement) {
      session.autoAudioElement.muted=true;
      session.autoAudioElement.pause();
    }
    session.on(AvatarEvent.Transcript,appendTranscript);
    session.on(AvatarEvent.LocalVideoReady,attachSelfPreview);
    session.on(AvatarEvent.Error,e=>showCallToast('Runway: '+(e.message||e.code||'ошибка медиа')));
    if(!session.mic.isEnabled)showCallToast('Разреши доступ к микрофону в настройках Android');
    setCallStage('3/3 — на связи','Видеозвонок · на связи');
    $('connecting').classList.add('hidden');
    updateCameraUI();
    updateSpeaker();
  }catch(e){
    if(inCall&&generation===callGeneration)showCallFailure(e,callStage);
  }finally{setBusy(false);}
};
$('mute').onclick=async()=>{
  if(!currentCall)return;
  try{
    await currentCall.mic.toggle();
    micMuted=!micMuted;
    $('mute').classList.toggle('off',micMuted);
    $('mute').setAttribute('aria-pressed',String(!micMuted));
    $('micLabel').textContent=micMuted?'Микрофон выкл.':'Микрофон';
  }catch(e){showCallToast('Микрофон: '+(e.message||'ошибка'));}
};
$('speaker').onclick=()=>{speakerMuted=!speakerMuted;updateSpeaker();};
$('hangup').onclick=()=>endCall();

restore();
native('state',{}).then(state=>{
  if(state.embeddedKey){
    $('keyStatus').textContent='● Ключ Runway встроен в установленное приложение.';
    $('manualKeyBox').classList.add('hidden');
  } else {
    $('keyStatus').textContent=state.manualKey?'● Ключ сохранён на устройстве.':'Ключ не найден внутри APK.';
    $('manualKeyBox').classList.remove('hidden');
  }
}).catch(e=>{$('keyStatus').textContent=e.message;});
