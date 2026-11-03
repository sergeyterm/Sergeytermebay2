package com.personal.sonyvegasvideo;

import java.util.Random;

public final class TransitionTimingTest {
    private static int count;
    public static void main(String[] args){
        EffectSpec effect=new EffectSpec();effect.transition="dissolve";effect.transitionMs=400;
        Sequence sequence=new Sequence().append(new Sequence.Clip("a","A",0,1000)).append(new Sequence.Clip("b","B",1000,2000));
        TransitionTiming cut=new TransitionTiming(sequence,new EffectSpec());check(cut.length==2000&&cut.starts[1]==1000);
        TransitionTiming blend=new TransitionTiming(sequence,effect);check(blend.length==1600&&blend.starts[1]==600&&blend.overlaps[0]==400);
        check(blend.indexAt(599)==0&&blend.indexAt(600)==1&&blend.indexAt(1600)==1);
        check(blend.sourceAt(1,800)==1200&&blend.sourceAt(1,-100)==1000&&blend.sourceAt(1,Integer.MAX_VALUE)==2000);
        check(new TransitionTiming(new Sequence(),effect).length==0&&new TransitionTiming(new Sequence(),effect).indexAt(0)==-1);
        check(new TransitionTiming(sequence.delete(1),effect).length==1000);
        effect.transitionMs=5000;check(new TransitionTiming(sequence,effect).overlaps[0]==500);
        Sequence tiny=new Sequence().append(new Sequence.Clip("a","A",0,1)).append(new Sequence.Clip("b","B",0,1000));
        check(new TransitionTiming(tiny,effect).length==1001);
        Random random=new Random(47);
        for(int attempt=0;attempt<200;attempt++){
            Sequence project=new Sequence();int clips=1+random.nextInt(20);
            for(int i=0;i<clips;i++){int start=random.nextInt(1000),length=1+random.nextInt(10000);project=project.append(new Sequence.Clip("source","Clip",start,start+length));}
            effect.transitionMs=50+random.nextInt(4951);TransitionTiming timing=new TransitionTiming(project,effect);int overlaps=0;
            for(int i=0;i<clips;i++){
                check(timing.ends[i]-timing.starts[i]==project.clips.get(i).length());
                check(timing.sourceAt(i,timing.starts[i])==project.clips.get(i).inMs&&timing.sourceAt(i,timing.ends[i])==project.clips.get(i).outMs);
                if(i>0){int overlap=timing.overlaps[i-1];overlaps+=overlap;check(overlap>=0&&overlap<=project.clips.get(i-1).length()/2&&overlap<=project.clips.get(i).length()/2);check(timing.starts[i]>=timing.starts[i-1]);}
                if(i>0&&i+1<clips)check(timing.ends[i-1]<=timing.starts[i+1]);
            }
            check(timing.length==project.length()-overlaps&&timing.length>0);
        }
        System.out.println("TransitionTimingTest OK: "+count+" checks");
    }
    private static void check(boolean value){count++;if(!value)throw new AssertionError("Check "+count);}
}
