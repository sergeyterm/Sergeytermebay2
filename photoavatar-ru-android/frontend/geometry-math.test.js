import test from 'node:test';
import assert from 'node:assert/strict';
import {geometryMetrics,compareGeometry,normalizeZoom,normalizePan} from './geometry-math.js';
import {calculatePortraitCrop} from './portrait-crop.js';
const sample=()=>{
 const a=Array.from({length:478},()=>({x:.5,y:.5}));
 const set=(i,x,y)=>a[i]={x,y};
 set(33,.25,.4);set(133,.43,.4);set(362,.57,.4);set(263,.75,.4);
 set(160,.31,.37);set(144,.31,.43);set(158,.36,.37);set(385,.64,.37);set(380,.64,.43);set(387,.69,.37);
 set(10,.5,.1);set(152,.5,.9);set(234,.1,.5);set(454,.9,.5);
 for(const i of [70,63,105,66,107,46,53,52,65,55])set(i,.34,.30);
 for(const i of [336,296,334,293,300,285,295,282,283,276])set(i,.66,.30);
 return a;
};
test('478 points: similarity transform leaves normalized geometry unchanged',()=>{
 const a=sample(),r=.2,s=.7;
 const b=a.map(p=>({x:.5+s*(Math.cos(r)*(p.x-.5)-Math.sin(r)*(p.y-.5)),y:.5+s*(Math.sin(r)*(p.x-.5)+Math.cos(r)*(p.y-.5))}));
 const m=compareGeometry(geometryMetrics(a,1000,1000),geometryMetrics(b,1000,1000));assert.ok(m.max<1e-10);assert.equal(m.changed,false);
});
test('brow distortion is detected without changing the eye coordinates',()=>{
 const a=sample(),b=sample();for(const i of [70,63,105,66,107,46,53,52,65,55])b[i].y-=.04;
 const m=compareGeometry(geometryMetrics(a,1000,1000),geometryMetrics(b,1000,1000));assert.equal(m.changed,true);assert.ok(m.brow>.075);assert.ok(m.eyes<1e-10);
});
test('reject incomplete or invalid landmark arrays',()=>{assert.throws(()=>geometryMetrics([],100,100));const a=sample();a[0].x=NaN;assert.throws(()=>geometryMetrics(a,100,100));});
test('zoom and pan bounds',()=>{assert.equal(normalizeZoom(.1),.3);assert.equal(normalizeZoom(4),1.5);assert.equal(normalizePan(-100),-50);assert.equal(normalizeZoom('bad'),1);});
test('portrait zoom out adds padding once full source is reached',()=>{const a=calculatePortraitCrop(866,1536,{detected:true,x:.2,y:.2,w:.5,h:.5},.3,0);assert.equal(a.height,1536);assert.ok(a.paddingScale<.5);});
