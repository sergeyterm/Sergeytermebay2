import { assessFile, photoPixelMetrics, qualityFromMetrics, makeReferenceVariants } from './pro-quality.js';
import { addHistory,listHistory,deleteHistory,makeThumb,getHistory } from './avatar-history.js';

/** Bridges Pro Quality UI to already existing native Runway / Replicate actions. */
export function createProController(hooks) {
  const {
    native, status, setBusy, isBusy, getImage, getFace, getPrepared,
    getAvatarId, setAvatarId, loadImage, detectFaceAndRender, inCall, getPersona, getName,
    getVoice, getProcessing, setProcessing, getConsent, setAvatarPreview, validateRestoration, onVariantChanged
  }=hooks;
  const $=id=>document.getElementById(id);
  const state={photos:[],variants:[],selected:null,codeFormerPhoto:null,quality:null};
  let listRenderBusy=false,pendingRestoration=null;
  const e=(type,text)=>{const p=document.createElement(type);p.textContent=text;return p;};
  const note=(id,text)=>{const target=$(id);if(target)target.textContent=text;};

  function clearPreparedVariants() {
    state.variants=[];state.selected=null;state.codeFormerPhoto=null;pendingRestoration=null;
    $('acceptRestoration').classList.add('hidden');$('restorationCompare').classList.add('hidden');$('restorationCompare').removeAttribute('src');
    $('variantsGrid').replaceChildren();
    note('selectedVariantStatus','По умолчанию используется подготовленный оригинальный портрет.');
    $('preparedPreview').src=getPrepared()||'';
  }
  function photoUpdated() {
    clearPreparedVariants();
    updateQuality();
  }
  function updateQuality() {
    const img=getImage();
    if(!img){note('qualitySummary','Загрузи фотографию для проверки качества.');return;}
    try {
      const pixel=photoPixelMetrics(img);
      const quality=qualityFromMetrics({...pixel,face:getFace()});
      state.quality=quality;
      note('qualitySummary',quality.score+'/100 · '+quality.label+'\n'+quality.reasons.join(' · '));
    }catch(err){note('qualitySummary','Не удалось оценить кадр: '+err.message);}
  }
  async function chooseFiles(fileList) {
    const files=Array.from(fileList||[]).slice(0,4);
    if(!files.length)return;
    state.photos=[];
    $('photoCandidates').classList.remove('hidden');
    $('bestShotList').replaceChildren();
    note('bestShotStatus','Читаю снимки…');
    let first=null;
    for(const file of files){
      try{await loadImage(file,{analyze:false});first=file;break;}catch(err){}
    }
    if(!first)throw new Error('Нет читаемых изображений. Выбери JPEG, PNG или WEBP.');
    if(files.length===1){
      await detectFaceAndRender();
      const result=state.quality||{score:0,label:'Предпросмотр готов',reasons:[]};
      state.photos=[{file:first,result,selected:true}];
      renderPhotos();
      note('bestShotStatus','Снимок загружен. Можно подготовить три варианта.');
      return;
    }
    for(const file of files){
      try{const result=await assessFile(file,native);state.photos.push({file,result,selected:file===first});}
      catch(err){state.photos.push({file,invalid:true,result:{score:0,label:'Не читается',reasons:[err.message]}});}
      note('bestShotStatus','Проверено '+state.photos.length+'/'+files.length+'. Предпросмотр уже доступен.');
      renderPhotos();
    }
    const valid=state.photos.filter(p=>!p.invalid);
    if(!valid.length)throw new Error('Не удалось оценить снимки. Предпросмотр первого читаемого кадра сохранён.');
    const best=valid.reduce((a,b)=>b.result.score>a.result.score?b:a,valid[0]);
    if(best.file===first){await detectFaceAndRender();renderPhotos();updateQuality();}
    else await activateFile(best);
    note('bestShotStatus','Рекомендован снимок №'+(state.photos.indexOf(best)+1)+' ('+best.result.score+'/100). Можно выбрать любой вручную.');
  }
  async function activateFile(item){
    if(item.invalid||!state.photos.includes(item))return;
    await loadImage(item.file);
    for(const photo of state.photos)photo.selected=photo===item;
    renderPhotos();updateQuality();
  }
  function renderPhotos() {
    const grid=$('bestShotList');grid.replaceChildren();
    state.photos.forEach((item,i)=>{
      const card=document.createElement('div');
      card.className='quality-item'+(item.selected?' selected':'');
      const img=new Image(),url=URL.createObjectURL(item.file);img.src=url;
      img.addEventListener('load',()=>URL.revokeObjectURL(url),{once:true});
      img.addEventListener('error',()=>URL.revokeObjectURL(url),{once:true});
      const title=e('strong','Снимок '+(i+1)+' · '+item.result.score+'/100');
      const subtitle=e('small',item.result.label);
      const button=e('button',item.selected?'Выбран':'Выбрать');
      button.disabled=!!item.invalid;
      button.onclick=()=>activateFile(item).catch(ex=>status(ex.message,true));
      card.append(img,title,subtitle,button);grid.append(card);
    });
  }

  async function generateThree() {
    const prepared=getPrepared();
    if(!prepared)throw new Error('Сначала загрузи фото');
    note('selectedVariantStatus','Подготавливаю три варианта без платной генерации…');
    let landmarks=null;
    try {landmarks=await native('detectFace',{photo:prepared},35000);}
    catch(e){note('qualityEyeStatus','ML Kit не смог распознать глаза: изображение будет улучшено без локальной обработки глаз.');}
    if(landmarks&&!landmarks.leftEye&&!landmarks.rightEye)
      note('qualityEyeStatus','Глаза не распознаны точно, поэтому их форма и текстура не изменяются.');
    else if(landmarks)
      note('qualityEyeStatus','Найдены глаза. Применяется мягкое локальное повышение контраста без изменения формы.');
    const eyeStrength=Number($('qualityEyeDetail').value)||0;
    let restoredEyes=null;
    if(state.codeFormerPhoto){try{restoredEyes=await native('detectFace',{photo:state.codeFormerPhoto},15000);}catch(e){}}
    state.variants=await makeReferenceVariants(prepared,landmarks,eyeStrength,state.codeFormerPhoto,restoredEyes);
    state.selected=null;
    renderVariants();
    note('selectedVariantStatus','Три варианта готовы. Выбери вариант перед созданием или заменой персонажа.');
    return state.variants;
  }

  function renderVariants() {
    const grid=$('variantsGrid');grid.replaceChildren();
    state.variants.forEach(v=>{
      const card=document.createElement('div');
      card.className='quality-item'+(state.selected===v.id?' selected':'');
      const img=new Image();img.src=v.image;
      const h=e('strong',v.name),desc=e('small',v.subtitle);
      const btn=e('button',state.selected===v.id?'Выбран':'Выбрать');
      btn.onclick=()=>selectVariant(v.id);
      card.append(img,h,desc,btn);grid.append(card);
    });
  }
  function selectVariant(id) {
    const variant=state.variants.find(v=>v.id===id);
    if(!variant)throw new Error('Неизвестный вариант');
    state.selected=id;
    $('preparedPreview').src=variant.image;
    setProcessing(variant.id==='enhanced'?'enhanced':variant.processing);
    note('selectedVariantStatus','Выбран: '+variant.name+'. Этот кадр будет отправлен в Runway после подтверждения.');
    renderVariants();onVariantChanged?.();
  }
  function currentPhoto() {
    const result=state.variants.find(v=>v.id===state.selected);
    return result?.image || getPrepared();
  }
  function currentVariant() {
    return state.variants.find(v=>v.id===state.selected) || null;
  }
  async function enhanceCodeFormer() {
    if(isBusy())return;
    if(!$('codeformerConsent').checked)throw new Error('Подтверди отправку изображения в Replicate и возможную оплату');
    const input=getPrepared();
    if(!input)throw new Error('Загрузи фотографию');
    setBusy(true);
    note('codeformerState','Replicate выполняет CodeFormer. Это отдельная платная операция…');
    try {
      const restored=await native('enhanceCodeFormer',{photo:input,fidelity:Number($('codeformerFidelity').value)},205000);
      if(!restored.photo?.startsWith('data:image/jpeg;base64,'))throw new Error('Неверный результат CodeFormer');
      $('codeformerConsent').checked=false;
      if(getPrepared()!==input){note('codeformerState','Портрет изменился во время реставрации. Результат не применён.');return;}
      const validation=await validateRestoration(input,restored.photo);
      if(getPrepared()!==input){note('codeformerState','Портрет изменился. Результат не применён.');return;}
      if(!validation.verified||validation.changed){
        pendingRestoration={photo:restored.photo,input};$('acceptRestoration').classList.remove('hidden');
        $('restorationCompare').src=restored.photo;$('restorationCompare').classList.remove('hidden');
        note('codeformerState',validation.verified?'ВНИМАНИЕ: пропорции изменились (брови '+(validation.brow*100).toFixed(1)+'%, глаза '+(validation.eyes*100).toFixed(1)+'% межглазного расстояния). Оригинал сохранён.':'Не удалось проверить геометрию CodeFormer: '+validation.error+'. Оригинал сохранён.');return;
      }
      state.codeFormerPhoto=restored.photo;
      note('codeformerState','✓ Реставрация проверена: заметного смещения по порогам не найдено. Это не гарантия мимики Runway.');
      await generateThree();selectVariant('enhanced');
    } finally {setBusy(false);}
  }

  async function saveHistory(item) {
    if(!item?.avatarId)return;
    try {
      const thumb=item.image?await makeThumb(item.image):'';
      await addHistory({...item,thumb});
      await renderHistory();
    } catch(err) {
      note('historyStatus','Не удалось записать историю: '+err.message);
    }
  }
  async function renderHistory() {
    if(listRenderBusy)return;
    listRenderBusy=true;
    try{
      const list=await listHistory();
      const holder=$('historyList');holder.replaceChildren();
      if(!list.length){holder.append(e('p','История пока пуста.'));return;}
      for(const v of list) {
        const row=document.createElement('div');row.className='quality-history-item';
        const img=new Image();if(v.thumb||v.image)img.src=v.thumb||v.image;
        const content=document.createElement('div');
        const name=e('strong',v.label+' · '+v.name);
        const meta=e('small',new Date(v.created).toLocaleString('ru-RU')+' · '+v.processing);
        const id=e('small',v.avatarId.slice(0,16)+'…');
        const actions=document.createElement('div');actions.className='quality-history-actions';
        const select=e('button','Выбрать');
        select.onclick=()=>{
          setAvatarId(v.avatarId,v.thumb||v.image||'');
          note('historyStatus','Выбран персонаж '+v.name+'. Новый персонаж не создаётся.');
        };
        const restore=e('button','Восстановить фото');
        restore.disabled=!v.image;
        restore.title='Повторный PATCH в Runway — может расходовать кредиты';
        restore.onclick=async()=>{
          if(inCall())return note('historyStatus','Сначала заверши видеозвонок.');
          if(!$('replaceAvatarConsent').checked || !getConsent())
            return note('historyStatus','Для восстановления отметь согласие на замену фото и кредиты Runway.');
          if(isBusy())return;
          setBusy(true);
          try{
            const result=await native('updateAvatarImage',{
              avatarId:v.avatarId,photo:v.image,imageProcessing:v.processing
            },100000);
            if(!result.updated)throw new Error('Runway не подтвердил обновление');
            setAvatarId(v.avatarId,v.thumb||v.image||'');
            await saveHistory({...v,label:'Восстановлена '+v.label,kind:'restore'});
            note('historyStatus','✓ Изображение восстановлено. Runway может повторно обрабатывать аватара.');
            $('replaceAvatarConsent').checked=false;
          }catch(err){note('historyStatus','Не удалось восстановить: '+err.message);}
          finally{setBusy(false);}
        };
        const del=e('button','Удалить запись');
        del.onclick=async()=>{await deleteHistory(v.id);await renderHistory();};
        actions.append(select,restore,del);
        content.append(name,meta,id,actions);row.append(img,content);holder.append(row);
      }
    }catch(err){note('historyStatus','История недоступна: '+err.message);}
    finally{listRenderBusy=false;}
  }

  async function createSeveral() {
    if(isBusy()||inCall())return;
    if(!getConsent()||!$('qualityGenerateConsent').checked)
      throw new Error('Подтверди создание нескольких платных персонажей Runway');
    const requested=Math.min(3,Math.max(2,Number($('qualityGenerateCount').value)||2));
    if(!state.variants.length)await generateThree();
    const voice=getVoice();
    if(voice.type==='custom'&&!voice.id)
      throw new Error('Для нескольких персонажей с клонированным голосом сначала создай голос один раз');
    setBusy(true);
    const made=[];
    try {
      for(let i=0;i<requested;i++){
        const variant=state.variants[i];
        note('variantBatchStatus','Создаю персонажа '+(i+1)+'/'+requested+' · '+variant.name+' (платная операция)…');
        try{
          const name=(getName()+' · '+variant.name).slice(0,50);
          const result=await native('createAvatar',{
            photo:variant.image,name,personality:getPersona(),
            voiceType:voice.type,voiceId:voice.id,
            imageProcessing:variant.processing
          },100000);
          if(!result?.id)throw new Error('Runway не вернул ID');
          made.push(result.id);
          await saveHistory({avatarId:result.id,name:getName(),label:variant.name,kind:'candidate',
            image:variant.image,processing:variant.processing});
          if(made.length===1)setAvatarId(result.id,await makeThumb(variant.image));
        } catch(err) {
          note('variantBatchStatus','Создано '+made.length+'/'+requested+'. Ошибка '+variant.name+': '+err.message);
          return;
        }
      }
      note('variantBatchStatus','✓ Создано '+made.length+' персонажей. Выбирай их по очереди из истории и сравнивай в звонках.');
      $('qualityGenerateConsent').checked=false;
      $('avatarVersionsDetails').open=true;
    }finally{setBusy(false);}
  }

  function wire() {
    $('qualityAudit').onclick=()=>updateQuality();
    $('generateVariants').onclick=()=>generateThree().catch(err=>status(err.message,true));
    $('qualityEyeDetail').oninput=()=>{
      note('qualityEyeStatus','Интенсивность: '+Math.round(Number($('qualityEyeDetail').value)*100)+'%. Нажми «Подготовить 3 варианта».');
    };
    $('saveReplicateToken').onclick=async()=>{
      try{
        await native('setCodeFormerToken',{token:$('replicateToken').value.trim()});
        $('replicateToken').value='';
        note('codeformerState','✓ Replicate подключён на этом телефоне.');
      }catch(err){note('codeformerState','Ошибка: '+err.message);}
    };
    $('clearReplicateToken').onclick=async()=>{
      try{await native('clearCodeFormerToken',{});note('codeformerState','Replicate отключён');}
      catch(err){note('codeformerState','Ошибка: '+err.message);}
    };
    $('acceptRestoration').onclick=async()=>{if(isBusy()||!pendingRestoration||getPrepared()!==pendingRestoration.input)return;setBusy(true);try{state.codeFormerPhoto=pendingRestoration.photo;pendingRestoration=null;$('acceptRestoration').classList.add('hidden');await generateThree();selectVariant('enhanced');note('codeformerState','Результат применён вручную.');}catch(e){note('codeformerState',e.message);}finally{setBusy(false);}};
    $('runCodeFormer').onclick=()=>enhanceCodeFormer().catch(err=>{note('codeformerState','CodeFormer: '+err.message);});
    $('generateAvatarCandidates').onclick=()=>createSeveral().catch(err=>note('variantBatchStatus',err.message));
    $('refreshHistory').onclick=()=>renderHistory();
    native('qualityState',{}).then(state=>note('codeformerState',state.codeformerConfigured?
      '✓ Репликация CodeFormer подключена — доступна платная реставрация.':
      'Не подключено. Остальные инструменты Pro Quality работают без Replicate.'))
      .catch(()=>note('codeformerState','Не удалось проверить подключение'));
    renderHistory();
  }

  return {wire,chooseFiles,photoUpdated,updateQuality,generateThree,currentPhoto,currentVariant,
    saveHistory,renderHistory,clearPreparedVariants,getCandidates:()=>state.photos.filter(x=>!x.invalid),selectCandidate:activateFile};
}
