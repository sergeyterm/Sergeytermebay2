package com.personal.sonyvegasvideo;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/** Event boundaries and project cursor; a tap selects an event and seeks within it. */
public final class SequenceTimeline extends View {
    public interface Listener{void seek(int projectMillis);}
    private final Paint p=new Paint(3);
    private Sequence sequence=new Sequence();private int selected=-1,position;private double visible=10000,offset;
    private Listener listener;private final ScaleGestureDetector pinch;private float lastX;private boolean scrolling;
    public SequenceTimeline(Context context){super(context);setContentDescription("Монтажный таймлайн. Коснитесь фрагмента для выбора. Два пальца меняют масштаб.");pinch=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector d){zoom(d.getScaleFactor());return true;}});}
    public void listener(Listener value){listener=value;}
    public void project(Sequence value,int index){boolean resized=sequence.length()!=value.length();sequence=value;selected=index;if(resized)fit();invalidate();}
    public void position(int value){position=value;invalidate();}
    public void fit(){visible=Math.max(1000,sequence.length()*1.05);offset=0;invalidate();}
    public void zoom(float factor){visible=Math.max(200,Math.min(Math.max(10000,sequence.length()*1.1),visible/factor));offset=Math.max(0,Math.min(offset,sequence.length()-visible));invalidate();}
    private float dp(float value){return value*getResources().getDisplayMetrics().density;}
    private float header(){return Math.min(dp(120),getWidth()*.24f);}
    private float x(double ms){return header()+(float)((ms-offset)/visible*(getWidth()-header()));}
    private int time(float x){return ClipMath.clamp((int)(offset+(x-header())/Math.max(1,getWidth()-header())*visible),0,sequence.length());}
    private void rect(Canvas c,float l,float t,float r,float b,int color){p.setColor(color);c.drawRect(l,t,r,b,p);}
    private void text(Canvas c,String s,float x,float y,int color,int size){p.setColor(color);p.setTextSize(dp(size));c.drawText(s,x,y,p);}
    @Override protected void onDraw(Canvas c){float h=header(),r=dp(38),rh=Math.max(dp(34),(getHeight()-r-dp(16))/2);
        c.drawColor(0xff8a8a8a);rect(c,0,0,h,getHeight(),0xff9ca5b1);rect(c,h,0,getWidth(),r,0xffa6a6a6);
        p.setTypeface(Typeface.MONOSPACE);p.setTextSize(dp(13));String code=ClipMath.clockCode(position);if(p.measureText(code)>h-dp(7))p.setTextSize(p.getTextSize()*(h-dp(7))/p.measureText(code));p.setColor(0xff183756);c.drawText(code,dp(3),dp(24),p);p.setTypeface(Typeface.DEFAULT);
        text(c,"1   Video",dp(6),r+dp(19),0xff222222,11);text(c,"Level: 100 %",dp(6),r+dp(39),0xff333333,9);
        text(c,"2   Audio",dp(6),r+rh+dp(19),0xff222222,11);text(c,"Source audio",dp(6),r+rh+dp(39),0xff333333,9);
        c.save();c.clipRect(h,0,getWidth(),getHeight());
        int step=1000;while(step/visible*(getWidth()-h)<dp(60)&&step<3600000)step*=2;
        for(double t=Math.floor(offset/step)*step;t<offset+visible;t+=step){float x=x(t);rect(c,x,dp(28),x+dp(1),getHeight()-dp(13),0xff727272);text(c,ClipMath.clock((long)t),x+dp(3),dp(24),0xff222222,9);}
        int start=0;for(int i=0;i<sequence.clips.size();i++){
            Sequence.Clip clip=sequence.clips.get(i);float l=x(start),right=x(start+clip.length());
            rect(c,l+1,r+dp(4),right-1,r+rh-dp(4),i==selected?0xffa6b8ce:0xff8496ac);rect(c,l+1,r+dp(4),right-1,r+dp(23),i==selected?0xff3c5b83:0xff526a87);
            rect(c,l+1,r+rh+dp(4),right-1,r+2*rh-dp(4),i==selected?0xffcdb7a4:0xffae978b);
            c.save();c.clipRect(l+dp(3),r+dp(4),right-dp(2),r+2*rh);text(c,(i+1)+"  "+clip.name,l+dp(6),r+dp(18),0xffffffff,10);
            text(c,ClipMath.clock(clip.inMs)+" → "+ClipMath.clock(clip.outMs),l+dp(6),r+dp(43),0xff263445,10);
            text(c,"Audio · "+(i+1),l+dp(6),r+rh+dp(22),0xff5f3b32,10);c.restore();start+=clip.length();
        }
        rect(c,x(position),r,x(position)+dp(1),getHeight()-dp(14),0xff111111);
        rect(c,h,getHeight()-dp(13),getWidth(),getHeight(),0xffb2b2b2);
        float thumb=(float)Math.max(dp(30),(getWidth()-h)*Math.min(1,visible/Math.max(visible,sequence.length())));
        float left=h+(getWidth()-h-thumb)*(float)(offset/Math.max(1,sequence.length()-visible));rect(c,left,getHeight()-dp(11),left+thumb,getHeight()-dp(2),0xffd8d8d8);c.restore();
    }
    @Override public boolean onTouchEvent(MotionEvent e){if(!isEnabled()||sequence.clips.isEmpty())return false;pinch.onTouchEvent(e);if(e.getPointerCount()>1||pinch.isInProgress())return true;
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:scrolling=e.getY()>getHeight()-dp(20);lastX=e.getX();getParent().requestDisallowInterceptTouchEvent(true);if(!scrolling&&e.getX()>header()&&listener!=null)listener.seek(time(e.getX()));return true;
            case MotionEvent.ACTION_MOVE:if(scrolling){offset=Math.max(0,Math.min(Math.max(0,sequence.length()-visible),offset+(e.getX()-lastX)/Math.max(1,getWidth()-header())*sequence.length()));invalidate();}else if(e.getX()>header()&&listener!=null)listener.seek(time(e.getX()));lastX=e.getX();return true;
            case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:getParent().requestDisallowInterceptTouchEvent(false);performClick();return true;
        }return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
