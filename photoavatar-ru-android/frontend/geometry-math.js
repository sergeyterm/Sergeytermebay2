// Geometry is expressed in eye-distance units, after translation, rotation and scale normalization.
export const REGIONS={
  browRight:[70,63,105,66,107,46,53,52,65,55],
  browLeft:[336,296,334,293,300,285,295,282,283,276],
  eyeRight:[33,160,158,133,153,144],
  eyeLeft:[362,385,387,263,373,380],
  mouth:[61,13,291,14], nose:[1,2,98,327], oval:[10,152,234,454]
};
const dist=(a,b)=>Math.hypot(a.x-b.x,a.y-b.y);
const mean=arr=>arr.reduce((a,b)=>a+b,0)/arr.length;
const clamp=(x,a,b)=>Math.max(a,Math.min(b,x));
const midpoint=(a,b)=>({x:(a.x+b.x)/2,y:(a.y+b.y)/2});
export function geometryMetrics(points,width,height){
  if(!Array.isArray(points)||points.length<468||!(width>0&&height>0))throw Error('Нет полной сетки лица');
  if(points.some(p=>!Number.isFinite(p.x)||!Number.isFinite(p.y)))throw Error('Неверные координаты сетки');
  const px=points.map(p=>({x:p.x*width,y:p.y*height}));
  const a=midpoint(px[33],px[133]), b=midpoint(px[362],px[263]);
  const eye=dist(a,b);if(eye<8)throw Error('Лицо слишком маленькое для точной проверки');
  const center=midpoint(a,b),angle=Math.atan2(b.y-a.y,b.x-a.x),cos=Math.cos(angle),sin=Math.sin(angle);
  const norm=px.map(p=>{const x=p.x-center.x,y=p.y-center.y;return{x:(x*cos+y*sin)/eye,y:(-x*sin+y*cos)/eye};});
  const browGap=(ids,eyeIds)=>mean(eyeIds.map(i=>norm[i].y))-mean(ids.map(i=>norm[i].y));
  const browRight=browGap(REGIONS.browRight,[160,158]),browLeft=browGap(REGIONS.browLeft,[385,387]);
  const eyeRight=dist(norm[160],norm[144])/Math.max(.001,dist(norm[33],norm[133]));
  const eyeLeft=dist(norm[385],norm[380])/Math.max(.001,dist(norm[362],norm[263]));
  const roll=angle*180/Math.PI;
  const faceHeight=dist(px[10],px[152])/height;
  const border=Math.min(...[10,152,234,454].map(i=>Math.min(points[i].x,1-points[i].x,points[i].y,1-points[i].y)));
  const asym=Math.abs(browLeft-browRight);
  const warnings=[];
  if(Math.abs(roll)>8)warnings.push('Наклон головы: рекомендуется мягкое выравнивание');
  if(eyeLeft<.12||eyeRight<.12)warnings.push('Один или оба глаза прищурены');
  if(asym>.10)warnings.push('Заметна асимметрия бровей: сравни с оригиналом (это может быть естественной особенностью)');
  if(faceHeight<.22)warnings.push('Лицо мало в кадре');
  if(border<.025)warnings.push('Контур лица близок к краю изображения');
  const score=Math.round(clamp(100-Math.min(35,Math.abs(roll)*1.5)-Math.max(0,.22-faceHeight)*100-(border<.025?16:0)-(eyeLeft<.12||eyeRight<.12?12:0),0,100));
  return {norm,eye,roll,faceHeight,browLeft,browRight,eyeLeft,eyeRight,score,warnings};
}
export function compareGeometry(a,b){
  if(!a?.norm||!b?.norm)throw Error('Не удалось сопоставить две сетки');
  const regions={};
  for(const [key,ids] of Object.entries(REGIONS))regions[key]=mean(ids.map(i=>dist(a.norm[i],b.norm[i])));
  const brow=Math.max(regions.browLeft,regions.browRight),eyes=Math.max(regions.eyeLeft,regions.eyeRight);
  const max=Math.max(...Object.values(regions));
  return {regions,brow,eyes,max,changed:brow>.075||eyes>.065||max>.12};
}
export function normalizeZoom(value){return clamp(Number.isFinite(Number(value))?Number(value):1,.3,1.5);}
export function normalizePan(value){return clamp(Number.isFinite(Number(value))?Number(value):0,-50,50);}
