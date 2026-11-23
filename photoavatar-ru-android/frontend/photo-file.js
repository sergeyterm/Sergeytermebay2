// The gallery MIME type may be absent. Trust successful image decoding instead.
function loadUrl(url,timeout=12000){
  return new Promise((resolve,reject)=>{
    const image=new Image();
    let settled=false;
    const finish=error=>{
      if(settled)return;settled=true;clearTimeout(timer);image.onload=null;image.onerror=null;
      if(error)reject(error);else resolve(image);
    };
    const timer=setTimeout(()=>finish(new Error('Истекло время чтения фото')),timeout);
    image.onload=()=>image.naturalWidth&&image.naturalHeight?finish():finish(new Error('Пустое изображение'));
    image.onerror=()=>finish(new Error('Не удалось декодировать фото'));
    image.src=url;
  });
}
function readDataUrl(file){
  return new Promise((resolve,reject)=>{
    const reader=new FileReader();
    reader.onload=()=>resolve(reader.result);
    reader.onerror=()=>reject(new Error('Не удалось прочитать фото'));
    reader.onabort=()=>reject(new Error('Чтение отменено'));
    reader.readAsDataURL(file);
  });
}
export async function openPhotoFile(file){
  if(!file||!file.size)throw new Error('Файл пуст или недоступен');
  if(file.size>30*1024*1024)throw new Error('Файл больше 30 МБ. Используй системный выбор фотографий.');
  let url=URL.createObjectURL(file);
  try{const image=await loadUrl(url);return {image,url,dispose:()=>URL.revokeObjectURL(url)};}
  catch(first){
    URL.revokeObjectURL(url);
    url=await readDataUrl(file);
    const image=await loadUrl(url);
    return {image,url,dispose:()=>{}};
  }
}
