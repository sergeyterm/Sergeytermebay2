package com.personal.sonyvegasvideo;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable edit decision list; source offsets are independent of project time. */
public final class Sequence {
    public static final class Clip {
        public final String source,name;
        public final int inMs,outMs;
        public Clip(String source,String name,int inMs,int outMs){
            if(source==null||source.isEmpty()||inMs<0||outMs<=inMs)throw new IllegalArgumentException("Пустой или неверный фрагмент");
            this.source=source;this.name=name==null?"Video":name;this.inMs=inMs;this.outMs=outMs;
        }
        public int length(){return outMs-inMs;}
    }
    public final List<Clip> clips;
    public Sequence(){this(Collections.emptyList());}
    public Sequence(List<Clip> values){
        if(values.size()>64)throw new IllegalArgumentException("Максимум 64 фрагмента");
        long length=0;for(Clip c:values)length+=c.length();
        if(length>Integer.MAX_VALUE)throw new IllegalArgumentException("Проект слишком длинный");
        clips=Collections.unmodifiableList(new ArrayList<>(values));
    }
    public int length(){int length=0;for(Clip c:clips)length+=c.length();return length;}
    public int offset(int index){int offset=0;for(int i=0;i<index;i++)offset+=clips.get(i).length();return offset;}
    public int indexAt(int millis){if(clips.isEmpty())return -1;int offset=0;for(int i=0;i<clips.size();i++){offset+=clips.get(i).length();if(millis<offset)return i;}return clips.size()-1;}
    public Sequence append(Clip clip){List<Clip> next=new ArrayList<>(clips);next.add(clip);return new Sequence(next);}
    public Sequence split(int index,int sourceMillis){
        Clip clip=clips.get(index);if(sourceMillis<=clip.inMs||sourceMillis>=clip.outMs)throw new IllegalArgumentException("Нет ключевого кадра внутри фрагмента");
        List<Clip> next=new ArrayList<>(clips);next.set(index,new Clip(clip.source,clip.name,clip.inMs,sourceMillis));
        next.add(index+1,new Clip(clip.source,clip.name,sourceMillis,clip.outMs));return new Sequence(next);
    }
    public Sequence delete(int index){List<Clip> next=new ArrayList<>(clips);next.remove(index);return new Sequence(next);}
    public Sequence move(int from,int to){List<Clip> next=new ArrayList<>(clips);Clip clip=next.remove(from);next.add(Math.max(0,Math.min(to,next.size())),clip);return new Sequence(next);}
    public Sequence trim(int index,int inMs,int outMs){Clip c=clips.get(index);List<Clip> next=new ArrayList<>(clips);next.set(index,new Clip(c.source,c.name,inMs,outMs));return new Sequence(next);}
}
