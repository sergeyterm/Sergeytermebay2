package com.personal.sonyvegasvideo;
public final class WorkspaceLogicTest {
    private static int checks;
    public static void main(String[] args){
        check(ClipMath.clockCode(0).equals("00:00:00.000"));check(ClipMath.clockCode(3723456).equals("01:02:03.456"));
        check(ClipMath.parseClock("01:02:03.456")==3723456);check(ClipMath.parseClock("90:00")==5400000);
        check(ClipMath.parseClock("1,25")==1250);
        for(String value:new String[]{"-1","NaN","Infinity","1:60","1:00:60","1:","1::2","1:2:3:4","99999999999"}){
            boolean failed=false;try{ClipMath.parseClock(value);}catch(IllegalArgumentException expected){failed=true;}check(failed);
        }
        for(int ms=0;ms<10_000_000;ms+=1337)check(ClipMath.parseClock(ClipMath.clockCode(ms))==ms);
        EditHistory h=new EditHistory();EditHistory.State a=new EditHistory.State(0,6000,false),b=new EditHistory.State(1000,5000,false),c=new EditHistory.State(1000,5000,true);
        h.record(a,a);check(!h.canUndo());h.record(a,b);h.record(b,c);check(h.undo(c).same(b));check(h.undo(b).same(a));check(!h.canUndo());check(h.redo(a).same(b));
        h.record(b,new EditHistory.State(2000,4000,false));check(!h.canRedo());h.clear();check(!h.canUndo());
        for(int i=0;i<100;i++)h.record(new EditHistory.State(i,6000,false),new EditHistory.State(i+1,6000,false));
        int undoCount=0;EditHistory.State current=new EditHistory.State(100,6000,false);while(h.canUndo()){current=h.undo(current);undoCount++;}check(undoCount==64);check(current.start==36);
        System.out.println("WorkspaceLogicTest OK: "+checks+" checks");
    }
    private static void check(boolean value){checks++;if(!value)throw new AssertionError("Check "+checks);}
}
