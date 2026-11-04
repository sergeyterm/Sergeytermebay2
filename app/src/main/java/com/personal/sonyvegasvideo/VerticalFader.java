package com.personal.sonyvegasvideo;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
public final class VerticalFader extends View {
    public interface Listener{void changed(float gain);}
    private final Paint p=new Paint(3);private float gain=1;private Listener listener;
    public VerticalFader(Context context){super(context);setContentDescription("Громкость проекта");setTooltipText("Общая громкость предпросмотра и экспортируемого видео");}
    public void listener(Listener value){listener=value;}
    public void gain(float value){gain=Math.max(0,Math.min(1,value));invalidate();}
    @Override protected void onDraw(Canvas c){float d=getResources().getDisplayMetrics().density,x=getWidth()/2f,top=10*d,bottom=getHeight()-12*d,y=bottom-(bottom-top)*gain;
        p.setColor(0xffa9b8ce);c.drawRect(x-d,top,x+d,bottom,p);p.setColor(EditorStyle.ACCENT);c.drawRoundRect(2*d,y-5*d,getWidth()-2*d,y+5*d,4*d,4*d,p);}
    @Override public boolean onTouchEvent(MotionEvent e){if(!isEnabled())return false;float d=getResources().getDisplayMetrics().density;
        if(e.getAction()==MotionEvent.ACTION_DOWN||e.getAction()==MotionEvent.ACTION_MOVE){getParent().requestDisallowInterceptTouchEvent(true);gain(1-(e.getY()-10*d)/Math.max(1,getHeight()-22*d));if(listener!=null)listener.changed(gain);return true;}
        if(e.getAction()==MotionEvent.ACTION_UP){getParent().requestDisallowInterceptTouchEvent(false);performClick();return true;}return true;}
    @Override public boolean performClick(){super.performClick();return true;}
}
