import {normalizeZoom,normalizePan} from './geometry-math.js';
export function wireSmartZoom(getId){
  const $=id=>document.getElementById(id),video=$('avatarVideo'),backdrop=$('callBackdrop');
  let z=1,x=0,y=0,fit='contain',key='',pointers=new Map(),gesture=null;
  const storageKey=()=> 'photoavatar_view_'+(getId()||'default');
  function save(){try{localStorage.setItem(key,JSON.stringify({z,x,y,fit}));}catch(e){}}
  function apply(persist=true){
    z=normalizeZoom(z);x=normalizePan(x);y=normalizePan(y);
    video.style.objectFit=fit;video.style.transform='translate('+x+'%,'+y+'%) scale('+z+')';
    backdrop.style.transform=video.style.transform;
    $('callZoom').value=String(z);$('callPanX').value=String(x);$('callPanY').value=String(y);
    $('callFit').value=fit;$('callZoomValue').textContent=Math.round(z*100)+'%';
    if(persist)save();
  }
  function load(){key=storageKey();z=1;x=0;y=0;fit='contain';
    try{const s=JSON.parse(localStorage.getItem(key)||'null');if(s){z=normalizeZoom(s.z);x=normalizePan(s.x);y=normalizePan(s.y);fit=s.fit==='cover'?'cover':'contain';}}catch(e){}
    pointers.clear();gesture=null;apply(false);$('callZoomPanel').classList.add('hidden');
  }
  $('callZoomToggle').onclick=()=>{$('callZoomPanel').classList.toggle('hidden');};
  $('callZoom').oninput=()=>{z=Number($('callZoom').value);apply();};
  $('callPanX').oninput=()=>{x=Number($('callPanX').value);apply();};
  $('callPanY').oninput=()=>{y=Number($('callPanY').value);apply();};
  $('callFit').onchange=()=>{fit=$('callFit').value==='cover'?'cover':'contain';apply();};
  $('callZoomReset').onclick=()=>{z=1;x=0;y=0;fit='contain';apply();};
  const pt=e=>({x:e.clientX,y:e.clientY});
  function snapshot(){const a=[...pointers.values()];gesture=a.length===2?{d:Math.max(1,Math.hypot(a[0].x-a[1].x,a[0].y-a[1].y)),cx:(a[0].x+a[1].x)/2,cy:(a[0].y+a[1].y)/2,z,x,y}:a.length===1?{cx:a[0].x,cy:a[0].y,z,x,y}:null;}
  const area=$('callScreen');
  area.addEventListener('pointerdown',e=>{
    if(e.pointerType==='mouse'||e.target.closest('button,input,select,textarea,.call-topbar,.call-bottom,.call-selffeed,.call-error,.call-diagnostics,#callZoomPanel'))return;
    if(pointers.size>=2)return;
    pointers.set(e.pointerId,pt(e));try{area.setPointerCapture(e.pointerId);}catch(err){}snapshot();
  });
  area.addEventListener('pointermove',e=>{
    if(!pointers.has(e.pointerId)||!gesture)return;
    pointers.set(e.pointerId,pt(e));const a=[...pointers.values()],w=Math.max(1,area.clientWidth),h=Math.max(1,area.clientHeight);
    if(a.length===2&&gesture.d){const d=Math.hypot(a[0].x-a[1].x,a[0].y-a[1].y);z=gesture.z*d/gesture.d;x=gesture.x+((a[0].x+a[1].x)/2-gesture.cx)/w*100;y=gesture.y+((a[0].y+a[1].y)/2-gesture.cy)/h*100;}
    else if(a.length===1){x=gesture.x+(a[0].x-gesture.cx)/w*100;y=gesture.y+(a[0].y-gesture.cy)/h*100;}
    apply();
  });
  for(const name of ['pointerup','pointercancel','lostpointercapture'])area.addEventListener(name,e=>{if(pointers.delete(e.pointerId))snapshot();});
  return {load};
}
