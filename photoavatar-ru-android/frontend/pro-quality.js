/**
 * PhotoAvatar Pro Quality: offline image checks, localized eye detailing, and
 * three reversible reference-image variants. No generative face synthesis.
 * These functions do not alter eye geometry or claim to reconstruct a 3D face.
 */
import { makeDetectionImage } from './portrait-crop.js';

function clip(v,min,max){return Math.max(min,Math.min(max,Number.isFinite(v)?v:min));}

export function qualityFromMetrics(metrics) {
  // Heuristic suitability score for a video avatar, not an identity or beauty score.
  const {face=null,brightness=.5,contrast=.20,sharpness=.13}=metrics||{};
  let score=42; const reasons=[];
  if(!face || !face.detected) {
    return {score:Math.round(clip(25 + sharpness*20,0,38)),
      reasons:['Лицо не определено — лучше выбрать фронтальный портрет'],
      label:'Требуется проверка'};
  }
  const area=clip((face.w||0)*(face.h||0),0,1);
  const yaw=Math.abs(Number(face.yaw)||0), roll=Math.abs(Number(face.roll)||0), pitch=Math.abs(Number(face.pitch)||0);
  score+=clip(25-yaw*.6-roll*.4-pitch*.3,0,25);
  if(yaw>14||roll>10||pitch>14)reasons.push('Лицо заметно повёрнуто или наклонено');
  const faceSize=clip((area-.045)/.15,0,1);
  score+=faceSize*15;
  if(faceSize<.3)reasons.push('Лицо занимает слишком малую часть кадра');
  const eyeValues=['leftEyeOpen','rightEyeOpen'].map(k=>face[k]).filter(v=>typeof v==='number'&&v>=0&&v<=1);
  if(eyeValues.length) {
    const open=Math.min(...eyeValues);
    score+=clip(open*10,0,10);
    if(open<.35)reasons.push('Один глаз частично закрыт');
  } else score+=5;
  const sharp=clip(sharpness/.15,0,1);
  score+=sharp*15;
  if(sharp<.30)reasons.push('Фото может быть нерезким, особенно вокруг глаз');
  const exposure=clip(1-Math.abs(brightness-.52)*2.3,0,1);
  score+=exposure*9;
  if(exposure<.55)reasons.push('Желательно более ровное освещение');
  score+=clip(contrast/.20,0,1)*8;
  if(contrast<.08)reasons.push('Слабый локальный контраст');
  score=Math.round(clip(score,0,100));
  return {score,reasons:reasons.length?reasons:['Хорошее освещение и фронтальный ракурс'],
    label:score>=82?'Отличный референс':score>=64?'Подходит':'Можно улучшить'};
}

export function photoPixelMetrics(image) {
  const canvas=document.createElement('canvas');canvas.width=192;canvas.height=192;
  const ctx=canvas.getContext('2d',{willReadFrequently:true});
  ctx.drawImage(image,0,0,192,192);
  const data=ctx.getImageData(0,0,192,192).data;
  const grey=new Float32Array(192*192);
  let mean=0,second=0;
  for(let i=0;i<grey.length;i++){
    const n=i*4;
    const l=(.2126*data[n]+.7152*data[n+1]+.0722*data[n+2])/255;
    grey[i]=l;mean+=l;second+=l*l;
  }
  mean/=grey.length;
  const contrast=Math.sqrt(Math.max(0,second/grey.length-mean*mean));
  let edge=0,count=0;
  for(let y=2;y<190;y+=2){
    for(let x=2;x<190;x+=2){
      const i=y*192+x;
      edge+=Math.abs(4*grey[i]-grey[i-1]-grey[i+1]-grey[i-192]-grey[i+192]);
      count++;
    }
  }
  return {brightness:mean,contrast,sharpness:Math.min(.6,edge/Math.max(1,count))};
}

export async function assessFile(file,native) {
  if(!file||!file.type.startsWith('image/'))throw new Error('Нужен JPEG/PNG/WebP');
  if(file.size>18_000_000)throw new Error('Файл слишком большой (максимум 18 МБ)');
  const url=URL.createObjectURL(file);const image=new Image();
  try {
    image.src=url;await image.decode();
    const pixels=photoPixelMetrics(image);
    let face=null; let warning='';
    try {face=await native('detectFace',{photo:makeDetectionImage(image)},35000);}
    catch(err){warning='Локальная проверка лица недоступна';}
    const evaluated=qualityFromMetrics({...pixels,face});
    if(warning)evaluated.reasons.unshift(warning);
    return evaluated;
  }finally{URL.revokeObjectURL(url);}
}

function imageFromData(dataUrl) {
  return new Promise((resolve,reject)=>{
    const image=new Image();image.onload=()=>resolve(image);image.onerror=()=>reject(new Error('Не удалось прочитать портрет'));image.src=dataUrl;
  });
}

function applyEyeDetail(ctx,source,eye,amount) {
  if(!eye||typeof eye.x!=='number'||typeof eye.y!=='number')return false;
  const W=ctx.canvas.width,H=ctx.canvas.height;
  const x=eye.x*W,y=eye.y*H;
  const rw=clip(W*.103,42,124),rh=clip(H*.074,24,67);
  const left=Math.max(0,Math.min(W-rw,x-rw/2)),top=Math.max(0,Math.min(H-rh,y-rh/2));
  const patch=document.createElement('canvas');patch.width=Math.round(rw);patch.height=Math.round(rh);
  const p=patch.getContext('2d');
  p.filter='contrast('+(1+amount*.20)+') brightness('+(1+amount*.023)+')';
  p.drawImage(source,left,top,rw,rh,0,0,patch.width,patch.height);
  p.filter='none';
  p.globalCompositeOperation='destination-in';
  const mask=p.createRadialGradient(rw/2,rh/2,1,rw/2,rh/2,Math.max(rw/2,rh/2));
  mask.addColorStop(0,'rgba(0,0,0,'+clip(amount,0,.8)+')');
  mask.addColorStop(.5,'rgba(0,0,0,'+clip(amount*.6,0,.7)+')');
  mask.addColorStop(1,'rgba(0,0,0,0)');
  p.fillStyle=mask;p.fillRect(0,0,rw,rh);
  ctx.drawImage(patch,left,top,rw,rh);
  return true;
}

export async function makeReferenceVariants(preparedImage,eyeLandmarks=null,eyeAmount=.20,codeFormerImage=null) {
  if(!/^data:image\/(jpeg|png|webp);base64,/.test(preparedImage||''))throw new Error('Подготовь фотографию');
  const options=[
    {id:'natural',name:'Натуральный',subtitle:'Без дополнительных изменений глаз',processing:'none',strength:0},
    {id:'balanced',name:'Сбалансированный',subtitle:'Мягкий цвет, контроль глаз',processing:'optimize',strength:.55},
    {id:'enhanced',name:codeFormerImage?'CodeFormer + глаза':'Детальный',subtitle:codeFormerImage?'CodeFormer + мягкая детализация':'Локальная детализация, без ИИ-реставрации',processing:'none',strength:1}
  ];
  const out=[];
  for(const option of options) {
    const original=await imageFromData(option.id==='enhanced'&&codeFormerImage?codeFormerImage:preparedImage);
    const canvas=document.createElement('canvas');canvas.width=1088;canvas.height=704;
    const ctx=canvas.getContext('2d');
    ctx.fillStyle='#25383e';ctx.fillRect(0,0,1088,704);
    ctx.filter=option.id==='balanced'?'contrast(1.025) saturate(1.01) brightness(1.015)':
      option.id==='enhanced'?'contrast(1.045) saturate(1.01) brightness(1.015)':'none';
    ctx.drawImage(original,0,0,1088,704);ctx.filter='none';
    const eyes=eyeLandmarks||{};
    const intensity=clip(eyeAmount,0,.75)*option.strength;
    const beforeEyes=document.createElement('canvas');beforeEyes.width=1088;beforeEyes.height=704;
    beforeEyes.getContext('2d').drawImage(canvas,0,0);
    if(intensity>0) {
      applyEyeDetail(ctx,beforeEyes,eyes.leftEye,intensity);
      applyEyeDetail(ctx,beforeEyes,eyes.rightEye,intensity);
    }
    out.push({...option,image:canvas.toDataURL('image/jpeg',.89)});
  }
  return out;
}
