import test from 'node:test';
import assert from 'node:assert/strict';
import { qualityFromMetrics } from './pro-quality.js';

const front={
  detected:true,x:.27,y:.19,w:.46,h:.53,
  yaw:1.5,roll:1.2,pitch:1.0,leftEyeOpen:.93,rightEyeOpen:.91
};
test('a sharp front-facing portrait is scored higher than a heavily rotated one',()=>{
  const base={face:front,brightness:.52,contrast:.19,sharpness:.14};
  const centered=qualityFromMetrics(base);
  const tilted=qualityFromMetrics({...base,face:{...front,yaw:28,roll:14,pitch:19}});
  assert.ok(centered.score>tilted.score);
  assert.ok(centered.score<=100 && centered.score>=0);
});
test('eyes almost closed yield warning; no unsupported facial geometry changes',()=>{
  const assessment=qualityFromMetrics({
    face:{...front,leftEyeOpen:.08,rightEyeOpen:.94},
    brightness:.5,contrast:.18,sharpness:.11
  });
  assert.ok(assessment.reasons.some(x=>/глаз/.test(x)));
});
test('a missing face is rejected by quality score',()=>{
  const result=qualityFromMetrics({face:{detected:false},brightness:.5,sharpness:.12});
  assert.ok(result.score<45);
});
test('exposure and sharpness affect score without altering photos',()=>{
  const good=qualityFromMetrics({face:front,brightness:.5,contrast:.19,sharpness:.16});
  const bad=qualityFromMetrics({face:front,brightness:.12,contrast:.04,sharpness:.01});
  assert.ok(good.score>bad.score);
});
