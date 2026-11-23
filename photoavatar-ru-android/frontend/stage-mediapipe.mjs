import {mkdir,cp,readdir,readFile} from 'node:fs/promises';
const dst=new URL('../app/src/main/assets/mediapipe/',import.meta.url);
await mkdir(dst,{recursive:true});
const src=new URL('./node_modules/@mediapipe/tasks-vision/wasm/',import.meta.url);
for(const name of await readdir(src)){if(/\.(js|wasm)$/.test(name))await cp(new URL(name,src),new URL(name,dst));}
const names=await readdir(dst);
if(!names.some(n=>n.endsWith('.wasm'))||!names.some(n=>n.endsWith('.js')))throw Error('MediaPipe runtime missing');
const model=await readFile(new URL('face_landmarker.task',dst));if(model.length<1_000_000)throw Error('MediaPipe model missing');
