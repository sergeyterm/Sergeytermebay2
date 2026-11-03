package com.personal.sonyvegasvideo;

/** Shared presentation timeline for real overlaps between adjacent source clips. */
public final class TransitionTiming {
    public final int[] starts,ends,overlaps;
    public final int length;
    private final Sequence sequence;
    public TransitionTiming(Sequence sequence,EffectSpec settings){
        if(sequence==null)throw new IllegalArgumentException("Нет монтажного проекта.");
        this.sequence=sequence;int count=sequence.clips.size();starts=new int[count];ends=new int[count];overlaps=new int[Math.max(0,count-1)];
        EffectSpec spec=settings==null?new EffectSpec():settings.copy().normalize();
        int transition=spec.transition.equals("none")?0:spec.transitionMs;
        for(int i=0;i<count;i++){
            Sequence.Clip clip=sequence.clips.get(i);
            if(i>0){overlaps[i-1]=Math.min(transition,Math.min(sequence.clips.get(i-1).length()/2,clip.length()/2));starts[i]=ends[i-1]-overlaps[i-1];}
            ends[i]=starts[i]+clip.length();
        }
        length=count==0?0:ends[count-1];
    }
    /** During an overlap, editing targets the incoming clip whose head is visible. */
    public int indexAt(int millis){for(int i=starts.length-1;i>=0;i--)if(millis>=starts[i])return i;return starts.length==0?-1:0;}
    public int sourceAt(int index,int millis){Sequence.Clip clip=sequence.clips.get(index);long local=Math.max(0,Math.min((long)clip.length(),(long)millis-starts[index]));return clip.inMs+(int)local;}
    public static int lengthMs(Sequence sequence,EffectSpec settings){return new TransitionTiming(sequence,settings).length;}
}
