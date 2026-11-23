import test from 'node:test';
import assert from 'node:assert/strict';
import { calculatePortraitCrop, selectedSource, OUTPUT_WIDTH, OUTPUT_HEIGHT } from './portrait-crop.js';

const within = (v,lo,hi) => assert.ok(v>=lo-1e-6 && v<=hi+1e-6);

test('1088x704 target is the Runway reference frame',()=>{
  assert.equal(OUTPUT_WIDTH,1088);
  assert.equal(OUTPUT_HEIGHT,704);
});

test('vertical 866x1536 portrait: crop stays in source and keeps face centered',()=>{
  const width=866,height=1536;
  const face={detected:true,x:.25,y:.27,w:.51,h:.39};
  const crop=calculatePortraitCrop(width,height,face,1,0);
  within(crop.x,0,width-crop.width);
  within(crop.y,0,height-crop.height);
  within(crop.width,0,width);
  within(crop.height,0,height);
  assert.ok(crop.height > height*.45);
  assert.ok(face.x*width + face.w*width/2 >= crop.x);
  assert.ok(face.x*width + face.w*width/2 <= crop.x+crop.width);
});

test('face near image edge does not create negative source coordinates',()=>{
  for(const face of [
    {detected:true,x:0,y:0,w:.23,h:.22},
    {detected:true,x:.75,y:.72,w:.24,h:.27}
  ]){
    const crop=calculatePortraitCrop(800,1200,face,1.5,.3);
    within(crop.x,0,800-crop.width);
    within(crop.y,0,1200-crop.height);
  }
});

test('failed face detection uses deterministic non-stretching central crop',()=>{
  const a=calculatePortraitCrop(866,1536,null,1,0);
  const b=calculatePortraitCrop(866,1536,{detected:false},1,0);
  assert.deepEqual(a,b);
  assert.equal(a.detected,false);
});

test('nine tiles are exactly the intended cells of the source photo',()=>{
  const image={naturalWidth:900,naturalHeight:1500};
  const rects=Array.from({length:9},(_,index)=>selectedSource(image,index));
  assert.deepEqual(rects[0],{x:0,y:0,width:300,height:500});
  assert.deepEqual(rects[8],{x:600,y:1000,width:300,height:500});
  assert.equal(rects.reduce((t,r)=>t+r.width*r.height,0),900*1500);
  assert.throws(()=>selectedSource(image,9));
});
