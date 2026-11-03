package com.personal.sonyvegasvideo;
import java.util.*;
public final class SequenceTest {
    private static int count;
    public static void main(String[] args){
        Sequence empty=new Sequence();check(empty.length()==0&&empty.indexAt(0)==-1);
        Sequence original=empty.append(new Sequence.Clip("content://a","A",1000,7000));
        Sequence split=original.split(0,3000);check(original.clips.size()==1&&split.clips.size()==2);check(split.length()==6000);
        check(split.clips.get(0).inMs==1000&&split.clips.get(0).outMs==3000);check(split.clips.get(1).inMs==3000&&split.clips.get(1).outMs==7000);
        check(split.offset(1)==2000);check(split.indexAt(1999)==0&&split.indexAt(2000)==1&&split.indexAt(6000)==1);
        Sequence deleted=split.delete(0);check(deleted.length()==4000&&deleted.offset(0)==0&&deleted.clips.get(0).inMs==3000);
        Sequence moved=split.move(1,0);check(moved.length()==6000&&moved.clips.get(0).inMs==3000&&moved.offset(1)==4000);
        Sequence mixed=moved.append(new Sequence.Clip("content://b","B",0,2000));check(mixed.length()==8000&&mixed.clips.get(2).source.equals("content://b"));
        fails(()->original.split(0,1000));fails(()->original.split(0,7000));fails(()->new Sequence.Clip("x","bad",2,1));
        fails(()->new Sequence(Arrays.asList(new Sequence.Clip("a","A",0,Integer.MAX_VALUE),new Sequence.Clip("b","B",0,1))));
        fails(()->new Sequence(new ArrayList<>(Collections.nCopies(65,new Sequence.Clip("a","A",0,1)))));
        fails(()->mixed.clips.clear());
        for(int cut=1001;cut<7000;cut+=79){Sequence next=original.split(0,cut);check(next.length()==original.length());check(next.clips.get(0).outMs==next.clips.get(1).inMs);}
        System.out.println("SequenceTest OK: "+count+" checks");
    }
    private static void fails(Runnable action){boolean failed=false;try{action.run();}catch(RuntimeException expected){failed=true;}check(failed);}
    private static void check(boolean value){count++;if(!value)throw new AssertionError("Check "+count);}
}
