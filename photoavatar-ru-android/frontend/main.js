import { streamTo, AvatarEvent } from '@runwayml/avatars';
import { composePortrait, makeDetectionImage } from './portrait-crop.js';
import { createProController } from './pro-controller.js';

const $ = (id) => document.getElementById(id);
const pending = new Map();
let sequence = 0;
let image = null;
let imageUrl = '';
let activeTile = -1;
let currentFace = null;
let preparedImage = '';
let detectionToken = 0;
let avatarId = '';
let avatarPreview = '';
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
let remoteVideoProbe = null;
let remoteVideoWarningShown = false;
let proController = null;


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
  for (const id of ['createAvatar','testConnection','startCall','saveKey',
    'generateVariants','runCodeFormer','generateAvatarCandidates','replaceAvatarImage']) {
    if($(id))$(id).disabled = state;
  }
}
function remember() {
  for (const id of ['name','topic','persona','voiceMode','voiceId','processingMode']) {
    try {localStorage.setItem('runway_'+id, $(id).value);}catch(e){}
  }
}
function restore() {
  for (const id of ['name','topic','persona','voiceMode','voiceId','processingMode']) {
    const s = localStorage.getItem('runway_'+id);
    if (s !== null) $(id).value = s;
    $(id).addEventListener('change', remember);
  }
  avatarId = localStorage.getItem('runway_avatar_id') || '';
  avatarPreview = localStorage.getItem('runway_avatar_preview') || '';
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
  currentFace = null;
  preparedImage = '';
  $('photoTools').classList.remove('hidden');
  $('portraitZoom').value='1';
  $('portraitVertical').value='0';
  $('replaceAvatarConsent').checked=false;
  $('preview').src = imageUrl;
  $('preview').style.display = 'block';
  $('tileBlock').classList.remove('hidden');
  $('photoHint').textContent = 'Выбран полный кадр. Для коллажа 3×3 нажми на один фронтальный ракурс ниже.';
  drawTiles();
  if(proController)proController.photoUpdated();
  await detectFaceAndRender();
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
      detectFaceAndRender();
    };
    el.append(button);
  }
}

$('photo').addEventListener('change',async(e)=>{
  try {
    const files=Array.from(e.target.files || []);
    if(!files.length)return;
    if(proController)await proController.chooseFiles(files);
    else await loadImage(files[0]);
    status('Фотографии проверены. Выбери лучший кадр и при необходимости открой Pro Quality.');
  }catch(ex){labelError(ex);}
});
$('useFull').onclick=()=> {
  activeTile=-1;
  drawTiles();
  $('photoHint').textContent='Будет использован один фронтальный портрет.';
  detectFaceAndRender();
};
$('redetectFace').onclick=()=>detectFaceAndRender();
$('portraitZoom').addEventListener('input',refreshPreparedPreview);
$('portraitVertical').addEventListener('input',refreshPreparedPreview);



function refreshPreparedPreview() {
  if(!image)return;
  try {
    const portrait=composePortrait(
      image,activeTile,currentFace,
      Number($('portraitZoom').value),Number($('portraitVertical').value)
    );
    if(portrait.dataUrl.length>4_800_000)throw new Error('Референс слишком большой');
    preparedImage=portrait.dataUrl;
    $('preparedPreview').src=preparedImage;
    if(proController)proController.clearPreparedVariants();
  }catch(e){
    preparedImage='';
    $('faceStatus').textContent='Ошибка подготовки кадра: '+(e.message||String(e));
  }
}

async function detectFaceAndRender() {
  if(!image)return;
  const token=++detectionToken;
  currentFace=null;
  $('faceStatus').textContent='Определяю лицо на телефоне (без отправки фото в интернет)…';
  refreshPreparedPreview(); // preview remains usable even if detection fails
  try {
    const source=makeDetectionImage(image,activeTile);
    const result=await native('detectFace',{photo:source},35000);
    if(token!==detectionToken || !image)return;
    if(result.detected){
      currentFace=result;
      $('faceStatus').textContent='✓ Лицо найдено. Подготовлен портрет с правильными пропорциями.';
    } else if(result.facesCount>1){
      $('faceStatus').textContent='На снимке несколько лиц. Выбери кадр с одним человеком.';
    } else {
      $('faceStatus').textContent='Лицо не распознано. Используется центральное кадрирование; можно настроить масштаб вручную.';
    }
  }catch(e) {
    if(token!==detectionToken)return;
    $('faceStatus').textContent='Автопоиск недоступен. Можно настроить масштаб вручную: '+(e.message||'ошибка');
  }
  refreshPreparedPreview();
  if(proController)proController.updateQuality();
}

async function encodePortrait() {
  if(!image)throw new Error('Сначала выбери фотографию.');
  if(!preparedImage)refreshPreparedPreview();
  if(!preparedImage || !preparedImage.startsWith('data:image/jpeg;base64,')){
    throw new Error('Не получилось подготовить референсное изображение.');
  }
  if($('processingMode').value==='enhanced' && proController && !proController.currentVariant()) {
    await proController.generateThree();
    proController.selectVariant('enhanced');
  }
  return proController?.currentPhoto() || preparedImage;
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
      photo,name:$('name').value.trim(),personality:personaText(),voiceType,voiceId,
      imageProcessing:$('processingMode').value==='enhanced'?'none':$('processingMode').value
    },80000);
    avatarId=created.id;
    localStorage.setItem('runway_avatar_id',avatarId);
    if(proController)await proController.saveHistory({
      avatarId,name:$('name').value.trim(),label:proController.currentVariant()?.name||'Создан',
      kind:'created',image:photo,
      processing:$('processingMode').value==='enhanced'?'none':$('processingMode').value
    });
    // Save a low-resolution local portrait so the call backdrop does not show
    // a logo or a stretched technical placeholder after app restart.
    try {
      const im = new Image(); im.src = photo; await im.decode();
      const thumb = document.createElement('canvas');
      thumb.width=320;thumb.height=208;
      thumb.getContext('2d').drawImage(im,0,0,320,208);
      avatarPreview=thumb.toDataURL('image/jpeg',0.65);
      localStorage.setItem('runway_avatar_preview',avatarPreview);
    } catch(e) {avatarPreview='';}
    updateReady();
    status('Персонаж отправлен в Runway и обрабатывается. При звонке приложение автоматически дождётся статуса READY. Не создавай его повторно.');
    $('readyCard').scrollIntoView({behavior:'smooth'});
  }catch(e){labelError(e);}
  finally{setBusy(false);}
};
$('reuseAvatar').onclick=()=>{
  const existing=$('reuseId').value.trim();
  if(!UUID.test(existing)){status('Укажи корректный Runway Avatar ID (UUID).',true);return;}
  avatarId=existing;
  avatarPreview='';
  localStorage.removeItem('runway_avatar_preview');
  localStorage.setItem('runway_avatar_id',existing);
  updateReady();
  status('ID сохранён в приложении. Персонаж будет проверен при начале звонка.');
  $('readyCard').scrollIntoView({behavior:'smooth'});
};
$('replaceAvatarImage').onclick=async()=>{
  if(busy || inCall)return;
  if(!UUID.test(avatarId)){status('Персонаж не выбран.',true);return;}
  if(!image){status('Сначала загрузи новую фотографию и проверь подготовленный портрет.',true);return;}
  if(!$('replaceAvatarConsent').checked || !$('consent').checked){
    status('Подтверди замену фото и разрешение на возможные расходы Runway.',true);
    return;
  }
  remember();
  setBusy(true);
  try {
    const photo=await encodePortrait();
    status('Заменяю референсную фотографию существующего персонажа…');
    const result=await native('updateAvatarImage',{
      avatarId,
      photo,
      imageProcessing:$('processingMode').value==='enhanced'?'none':$('processingMode').value
    },95000);
    if(!result.updated || result.id!==avatarId)throw new Error('Не удалось подтвердить обновление персонажа');
    // Only after the API confirms the operation, update local call preview.
    const thumbnail=document.createElement('canvas');
    thumbnail.width=320;thumbnail.height=208;
    const im=new Image();im.src=photo;await im.decode();
    thumbnail.getContext('2d').drawImage(im,0,0,320,208);
    avatarPreview=thumbnail.toDataURL('image/jpeg',0.65);
    localStorage.setItem('runway_avatar_preview',avatarPreview);
    $('replaceAvatarConsent').checked=false;
    if(proController)await proController.saveHistory({
      avatarId,name:$('name').value.trim(),label:'Изображение обновлено',kind:'update',
      image:photo,processing:$('processingMode').value==='enhanced'?'none':$('processingMode').value
    });
    status('✓ Фото заменено. Avatar ID не изменился. Runway может заново обрабатывать персонажа. Перед звонком приложение проверит статус READY.');
  }catch(e){labelError(e);}
  finally{setBusy(false);}
};
$('resetAvatar').onclick = () => {
  avatarId='';
  avatarPreview='';
  localStorage.removeItem('runway_avatar_preview');
  localStorage.removeItem('runway_avatar_id');
  updateReady();
  status('Можно создать нового персонажа. Ранее созданный персонаж остаётся в Runway Dev.');
};


function applyCallBackdrop(uri) {
  const safeUri = uri && (uri.startsWith('data:image/') || uri.startsWith('https://') || uri.startsWith('blob:https://appassets.androidplatform.net/')) ? uri : '';
  const element = $('callBackdrop');
  if(!element)return;
  element.style.backgroundImage = safeUri ? 'url('+JSON.stringify(safeUri)+')' : 'none';
  element.classList.toggle('with-image',!!safeUri);
  // If image is unavailable, show a neutral dark call background, never a logo.
  $('callPhoto').classList.toggle('hidden',!safeUri);
  $('callPhotoFallback').classList.toggle('hidden',!!safeUri);
  if(safeUri)$('callPhoto').src=safeUri;
}
const delay=(ms)=>new Promise(resolve=>setTimeout(resolve,ms));
async function awaitAvatarReady(avatar,generation,timeoutMs=180000) {
  const started=Date.now();
  let attempt=0;
  while(Date.now()-started < timeoutMs) {
    if(!inCall || generation!==callGeneration)return false;
    const data=await native('avatarStatus',{avatarId:avatar},30000);
    if(!inCall || generation!==callGeneration)return false;
    if(data.previewUri && !imageUrl)applyCallBackdrop(data.previewUri);
    const state=String(data.status || 'UNKNOWN').toUpperCase();
    if(state==='READY'){
      setCallStage('1/3 — аватар готов','Аватар готов. Начинаем видеозвонок…');
      return true;
    }
    if(state==='FAILED'){
      throw new Error('Runway не смог обработать фотографию: '+(data.failureReason||'Статус FAILED. Проверь исходный портрет.'));
    }
    if(!['PROCESSING','PENDING','NOT_READY','UNKNOWN'].includes(state)){
      throw new Error('Неизвестный статус аватара: '+state);
    }
    attempt++;
    const elapsed=Math.floor((Date.now()-started)/1000);
    setCallStage('0/3 — подготовка аватара','Обработка фотографии в Runway… '+elapsed+' сек.');
    // These are read-only requests. Do not create a paid session while PROCESSING.
    await delay(3000);
  }
  throw new Error('Аватар всё ещё обрабатывается. Повтори звонок позже. Повторно создавать персонажа не нужно.');
}
function setCallState(value) {
  $('callState').textContent=value;
  $('loadingText').textContent=value;
}
function setCallStage(stage, message) {
  callStage = stage;
  setCallState(message);
  $('diagnosticOutput').textContent = 'Этап: ' + stage + '\nСтатус: ' + message;
}
/**
 * Runway SDK may emit AvatarVideoReady while streamTo() is still awaiting
 * LiveKit _connect(). Listen on the real HTMLVideoElement instead of relying
 * on the SDK event, which can be missed by a late session.on(...) subscriber.
 */
function remoteVideoState() {
  const video=$('avatarVideo');
  const stream=video.srcObject;
  let tracks=[];
  try { if(stream && typeof stream.getVideoTracks==='function') tracks=stream.getVideoTracks(); }
  catch(e) {}
  return {
    tracks:tracks.length, liveTracks:tracks.filter(t=>t.readyState!=='ended').length,
    width:video.videoWidth||0, height:video.videoHeight||0,
    readyState:video.readyState||0, paused:video.paused
  };
}
function revealRemoteVideo() {
  if(!inCall)return false;
  const video=$('avatarVideo'), state=remoteVideoState();
  if(state.liveTracks===0 || state.width===0 || state.height===0 || state.readyState<2)return false;
  video.classList.add('playing');
  remoteVideoWarningShown=false;
  if(remoteVideoProbe){clearInterval(remoteVideoProbe);remoteVideoProbe=null;}
  $('diagnosticOutput').textContent='Видеопоток: кадры доступны ('+state.width+'×'+state.height+')';
  return true;
}
function installRemoteVideoListeners() {
  const video=$('avatarVideo');
  // These DOM callbacks are registered before starting a call.
  for(const event of ['loadedmetadata','loadeddata','canplay','playing','resize','timeupdate']){
    video.addEventListener(event,()=>{revealRemoteVideo();});
  }
  if(typeof video.requestVideoFrameCallback==='function'){
    const frameLoop=()=>{
      video.requestVideoFrameCallback(()=>{
        revealRemoteVideo();
        frameLoop();
      });
    };
    frameLoop();
  }
}
function startRemoteVideoProbe() {
  if(remoteVideoProbe)clearInterval(remoteVideoProbe);
  remoteVideoWarningShown=false;
  const started=Date.now();
  remoteVideoProbe=setInterval(()=>{
    if(!inCall){clearInterval(remoteVideoProbe);remoteVideoProbe=null;return;}
    if(revealRemoteVideo())return;
    const v=$('avatarVideo'), state=remoteVideoState();
    if(state.liveTracks>0 && v.paused){
      // WebView may pause autoplay despite the Runway track being subscribed.
      v.play().catch(()=>{});
    }
    if(Date.now()-started>18000 && !remoteVideoWarningShown){
      remoteVideoWarningShown=true;
      const diagnostic='Видео: '+state.liveTracks+' видеодорожек, '+state.width+'×'+state.height+
        ', readyState='+state.readyState+', paused='+state.paused;
      $('diagnosticOutput').textContent+='\n'+diagnostic;
      showCallToast(state.liveTracks===0
        ? 'Разговор подключён, но видеоаватар ещё не передал видеопоток'
        : 'Видеопоток подключён, но кадры ещё не появились. Открой диагностику (ⓘ)');
    }
  },600);
}
function stopRemoteVideoProbe() {
  if(remoteVideoProbe){clearInterval(remoteVideoProbe);remoteVideoProbe=null;}
  remoteVideoWarningShown=false;
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
  stopRemoteVideoProbe();
  const oldCall=currentCall;currentCall=null;
  const oldSession=callSessionId;callSessionId='';
  try {if(oldCall) await oldCall.end();}catch(e){}
  if(oldSession) native('cancelSession',{sessionId:oldSession},25000).catch(()=>{});
  await stopSelfCamera();
  const remoteVideo=$('avatarVideo'), remoteAudio=$('avatarAudio');
  remoteVideo.pause(); remoteVideo.srcObject=null;
  remoteVideo.classList.remove('playing');
  $('callBackdrop').classList.remove('hidden');
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
  stopRemoteVideoProbe();
  $('avatarVideo').classList.remove('playing');
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
  applyCallBackdrop(imageUrl || avatarPreview);
  $('avatarVideo').classList.remove('playing');
  stopRemoteVideoProbe();
  $('callBackdrop').classList.remove('hidden');
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
    setCallStage('0/3 — подготовка аватара','Проверяю готовность персонажа…');
    const ready=await awaitAvatarReady(avatarId,generation);
    if(!ready || !inCall || generation!==callGeneration)return;
    setCallStage('1/3 — создание сессии','Вызываем собеседника…');
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
    session.on(AvatarEvent.AvatarVideoReady,()=>{
      // Secondary event: its first emission can precede streamTo() resolution.
      // The HTMLVideoElement events and srcObject checks are authoritative.
      setTimeout(revealRemoteVideo,0);
    });
    session.on(AvatarEvent.LocalVideoReady,attachSelfPreview);
    session.on(AvatarEvent.Error,e=>showCallToast('Runway: '+(e.message||e.code||'ошибка медиа')));
    if(!session.mic.isEnabled)showCallToast('Разреши доступ к микрофону в настройках Android');
    setCallStage('3/3 — на связи','Видеозвонок · на связи');
    // Check a stream that arrived before event subscription.
    revealRemoteVideo();
    startRemoteVideoProbe();
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

proController=createProController({
  native,status,setBusy,isBusy:()=>busy,inCall:()=>inCall,
  getImage:()=>image,getFace:()=>currentFace,getPrepared:()=>preparedImage,
  getAvatarId:()=>avatarId,
  setAvatarId:(id,preview='')=>{
    avatarId=id;avatarPreview=preview;
    localStorage.setItem('runway_avatar_id',id);
    if(preview)localStorage.setItem('runway_avatar_preview',preview);
    else localStorage.removeItem('runway_avatar_preview');
    updateReady();
    status('Персонаж выбран из истории. Он готов к проверке статуса Runway.');
  },
  loadImage,getPersona:personaText,
  getName:()=>$('name').value.trim()||'Собеседник',
  getVoice:()=>{
    if($('voiceMode').value==='custom')return {type:'custom',id:UUID.test($('voiceId').value.trim())?
        $('voiceId').value.trim():''};
    return {type:'preset',id:$('voiceMode').value};
  },
  getProcessing:()=>$('processingMode').value,
  setProcessing:(mode)=>{$('processingMode').value=mode;remember();},
  getConsent:()=>$('consent').checked,
  setAvatarPreview:(data)=>{avatarPreview=data;}
});
proController.wire();
installRemoteVideoListeners();
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
