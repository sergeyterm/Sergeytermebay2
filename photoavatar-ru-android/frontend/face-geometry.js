import { FaceLandmarker, FilesetResolver } from '@mediapipe/tasks-vision';
import { geometryMetrics,compareGeometry } from './geometry-math.js';
import { openPhotoFile } from './photo-file.js';
let modelPromise;
export async function landmarker(){
  if(!modelPromise)modelPromise=(async()=>{
    const wasm=await FilesetResolver.forVisionTasks(location.origin+'/assets/mediapipe');
    return FaceLandmarker.createFromOptions(wasm,{
      baseOptions:{modelAssetPath:location.origin+'/assets/mediapipe/face_landmarker.task',delegate:'CPU'},
      runningMode:'IMAGE',numFaces:2,minFaceDetectionConfidence:.5,minFacePresenceConfidence:.5,
      outputFaceBlendshapes:true,outputFacialTransformationMatrixes:true
    });
  })().catch(e=>{modelPromise=null;throw e;});
  return modelPromise;
}
function decode(src){
  return new Promise((resolve,reject)=>{const image=new Image();image.onload=()=>resolve(image);image.onerror=()=>reject(Error('Не удалось открыть портрет'));image.src=src;});
}
export async function analyzeGeometry(source){
  const img=typeof source==='string'?await decode(source):source;
  const w=img.naturalWidth||img.width,h=img.naturalHeight||img.height;
  if(!w||!h)throw Error('Фотография не загружена');
  const factor=Math.min(1,1024/Math.max(w,h));
  const canvas=document.createElement('canvas');canvas.width=Math.max(1,Math.round(w*factor));canvas.height=Math.max(1,Math.round(h*factor));
  canvas.getContext('2d').drawImage(img,0,0,canvas.width,canvas.height);
  const detector=await landmarker();
  const r=detector.detect(canvas);
  const count=r.faceLandmarks?.length||0;
  if(count!==1)throw Error(count>1?'Найдено несколько лиц. Выбери один портрет.':'MediaPipe не нашёл лицо. Используй фронтальный кадр.');
  const points=r.faceLandmarks[0], metrics=geometryMetrics(points,canvas.width,canvas.height);
  return {image:img,points,metrics,blendshapes:r.faceBlendshapes?.[0]?.categories||[],matrix:r.facialTransformationMatrixes?.[0]||null};
}
export async function validateRestoration(before,after){
  try{
    const a=await analyzeGeometry(before),b=await analyzeGeometry(after);
    return {verified:true,...compareGeometry(a.metrics,b.metrics)};
  }catch(e){return {verified:false,changed:true,error:e.message||String(e)};}
}
function showMesh(canvas,result,full){
  const img=result.image, w=img.naturalWidth||img.width,h=img.naturalHeight||img.height, scale=Math.min(1,1000/Math.max(w,h));
  canvas.width=Math.round(w*scale);canvas.height=Math.round(h*scale);
  const g=canvas.getContext('2d'),W=canvas.width,H=canvas.height;
  g.drawImage(img,0,0,W,H);g.lineWidth=Math.max(1,W/650);
  function edges(connections,color){if(!connections)return;g.strokeStyle=color;g.beginPath();
    for(const e of connections){const a=result.points[e.start],b=result.points[e.end];if(!a||!b)continue;g.moveTo(a.x*W,a.y*H);g.lineTo(b.x*W,b.y*H);}
    g.stroke();
  }
  if(full)edges(FaceLandmarker.FACE_LANDMARKS_TESSELATION,'#3ee9ca80');
  edges(FaceLandmarker.FACE_LANDMARKS_FACE_OVAL,'#4ae3ce');
  edges(FaceLandmarker.FACE_LANDMARKS_LIPS,'#ed89ba');
  for(const key of ['LEFT_EYE','RIGHT_EYE','LEFT_IRIS','RIGHT_IRIS'])edges(FaceLandmarker['FACE_LANDMARKS_'+key],'#68b5ff');
  for(const key of ['LEFT_EYEBROW','RIGHT_EYEBROW'])edges(FaceLandmarker['FACE_LANDMARKS_'+key],'#ffbe54');
  if(full){g.fillStyle='#f7ffff';for(const p of result.points){g.beginPath();g.arc(p.x*W,p.y*H,Math.max(.7,W/1200),0,Math.PI*2);g.fill();}}
}
export function wireGeometry({getPhoto,getCandidates,selectCandidate,align,guardBusy}){
  const $=id=>document.getElementById(id);let last=null,lastSource='',running=false;
  const note=(s)=>{$('geometryStatus').textContent=s;};
  const fmt=n=>Number(n).toFixed(2);
  const setWorking=v=>{running=v;for(const id of ['geometryAnalyze','geometryCompare','geometryAlign'])$(id).disabled=v;};
  const reset=()=>{last=null;lastSource='';$('geometryCanvas').classList.add('hidden');$('geometryMetrics').textContent='';$('geometryAlign').disabled=true;};
  const render=r=>{
    last=r;showMesh($('geometryCanvas'),r,$('geometryFullMesh').checked);$('geometryCanvas').classList.remove('hidden');
    const m=r.metrics;
    $('geometryMetrics').textContent=r.points.length+' точек · '+r.blendshapes.length+' коэффициентов мимики · наклон '+m.roll.toFixed(1)+'°\n'+
      'Брови (расстояние до века / межглазное): '+fmt(m.browLeft)+' / '+fmt(m.browRight)+'\n'+
      'Открытость глаз: '+fmt(m.eyeLeft)+' / '+fmt(m.eyeRight)+'\n'+
      (m.warnings.length?m.warnings.join('\n'):'Явных предупреждений о кадрировании и наклоне нет.');
  };
  $('geometryAnalyze').onclick=async()=>{
    if(running||guardBusy())return;
    const src=getPhoto();if(!src){note('Сначала загрузи фотографию.');return;}
    setWorking(true);reset();note('Анализирую геометрию локально…');
    try{const r=await analyzeGeometry(src);if(getPhoto()!==src){note('Портрет изменился, повтори анализ.');return;}lastSource=src;render(r);note('✓ Сетка MediaPipe готова. Брови — золотые, глаза — голубые.');}
    catch(e){note('Анализ недоступен: '+(e.message||String(e)));}
    finally{setWorking(false);$('geometryAlign').disabled=!last;}
  };
  $('geometryFullMesh').onchange=()=>{if(last)showMesh($('geometryCanvas'),last,$('geometryFullMesh').checked);};
  $('geometryAlign').onclick=()=>{if(!last||getPhoto()!==lastSource){note('Сначала повтори анализ текущего портрета.');return;}
    const angle=last.metrics.roll;if(Math.abs(angle)>22){note('Слишком сильный наклон. Лучше выбрать другой кадр.');return;}
    align(angle);reset();note('Выравнивание применено без деформации лица. Проверь предпросмотр и повтори анализ.');};
  $('geometryCompare').onclick=async()=>{
    if(running||guardBusy())return;
    const candidates=getCandidates();if(!candidates.length){note('Загрузи до четырёх фотографий.');return;}
    setWorking(true);$('geometryCandidates').replaceChildren();note('Сравниваю кадры…');
    try{
      const results=[];
      for(const item of candidates){
        let opened;
        try{opened=await openPhotoFile(item.file);const result=await analyzeGeometry(opened.image);results.push({item,result,score:Math.round(.65*result.metrics.score+.35*(item.result?.score||0))});}
        catch(e){results.push({item,error:e.message||String(e),score:-1});}finally{opened?.dispose();}
      }
      if(getCandidates().map(x=>x.file).join('|')!==candidates.map(x=>x.file).join('|')){note('Набор снимков изменился. Повтори сравнение.');return;}
      const ranked=results.slice().sort((a,b)=>b.score-a.score);
      for(const row of ranked){const box=document.createElement('div');box.className='geometry-row';
        const title=document.createElement('strong');title.textContent='Снимок '+(candidates.indexOf(row.item)+1)+' · '+(row.score<0?'нет сетки':row.score+'/100');
        const sub=document.createElement('small');sub.textContent=row.error||row.result.metrics.warnings.join(' · ')||'Подходит для сравнения';
        box.append(title,sub);
        if(row.score>=0){const button=document.createElement('button');button.className='btn alt';button.textContent='Выбрать этот снимок';button.onclick=()=>selectCandidate(row.item).catch(e=>note(e.message));box.append(button);}
        $('geometryCandidates').append(box);
      }
      note(ranked[0]?.score>=0?'Сравнение готово. Оценка ориентировочная, не является оценкой личности или гарантией анимации.':'Ни на одном снимке не удалось построить сетку.');
    }finally{setWorking(false);$('geometryAlign').disabled=!last;}
  };
  reset();
  return {reset,validateRestoration};
}
