package com.personal.sonyvegasvideo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/** Touch-controlled Vegas-style ruler, event strip, loop handles and track list. */
public final class TimelineView extends View {
    public interface Listener {
        void seek(int millis); void editBegin(); void range(int start,int end); void editEnd();
        void mute(); void preciseRange();
    }
    private final Paint p=new Paint(3);
    private final Rect sourceRect=new Rect();
    private final RectF targetRect=new RectF();
    private final ScaleGestureDetector pinch;
    private Listener listener;
    private int duration,start,end,position;
    private float density;
    private double visible=10000,offset;
    private int drag;
    private float lastX;
    private long downTime;
    private boolean muted,hasAudio;
    private String name="";
    private Bitmap[] frames;
    private float[] waveform;
    private final List<Integer> markers=new ArrayList<>();
    public TimelineView(Context context){
        super(context);density=getResources().getDisplayMetrics().density;
        setContentDescription("Таймлайн. Перетащите золотые края для выбора фрагмента; коснитесь линейки для перемотки. Два пальца: масштаб.");
        setFocusable(true);
        pinch=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector detector){zoom(detector.getScaleFactor(),detector.getFocusX());return true;}
        });
    }
    public void setListener(Listener listener){this.listener=listener;}
    public void media(int length,String filename,boolean audio,Bitmap[] thumbnails,float[] peaks){
        boolean changed=duration!=length;
        duration=length;name=filename;hasAudio=audio;frames=thumbnails;waveform=peaks;
        if(changed)fit();invalidate();
    }
    public void selection(int begin,int finish,boolean mute){start=begin;end=finish;muted=mute;invalidate();}
    public void position(int millis){position=millis;invalidate();}
    public void markers(List<Integer> values){markers.clear();markers.addAll(values);invalidate();}
    public void fit(){offset=0;visible=Math.max(1000,duration>0?duration*1.06:10000);invalidate();}
    public void zoom(float factor){zoom(factor,header()+(getWidth()-header())/2f);}
    private void zoom(float factor,float focus){
        float width=Math.max(1,getWidth()-header());double frac=Math.max(0,Math.min(1,(focus-header())/width));
        double anchor=offset+visible*frac;
        visible=Math.max(200,Math.min(Math.max(10000,duration*1.1),visible/factor));
        offset=Math.max(0,Math.min(Math.max(0,duration-visible),anchor-visible*frac));invalidate();
    }
    private float header(){return Math.max(dp(98),Math.min(dp(150),getWidth()*.27f));}
    private float ruler(){return dp(44);}
    private float row(){return Math.max(dp(36),Math.min(dp(110),(getHeight()-ruler()-dp(16))/2f));}
    private float x(double time){return header()+(float)((time-offset)/visible*(getWidth()-header()));}
    private int time(float x){return ClipMath.clamp((int)Math.round(offset+(x-header())/Math.max(1,getWidth()-header())*visible),0,duration);}
    private float dp(float value){return value*density;}
    private void rect(Canvas c,float l,float t,float r,float b,int color){p.setColor(color);p.setStyle(Paint.Style.FILL);c.drawRect(l,t,r,b,p);}
    private void line(Canvas c,float x,float y,float x2,float y2,int color){p.setColor(color);p.setStrokeWidth(dp(1));c.drawLine(x,y,x2,y2,p);}
    private void text(Canvas c,String value,float x,float y,float size,int color){p.setColor(color);p.setTextSize(dp(size));p.setTypeface(Typeface.DEFAULT);c.drawText(value,x,y,p);}
    @Override protected void onDraw(Canvas c){
        float h=header(),r=ruler(),rh=row(),w=getWidth(),height=getHeight();
        rect(c,0,0,w,height,0xff898989);rect(c,0,0,h,r,0xff9e9e9e);
        p.setTypeface(Typeface.create(Typeface.MONOSPACE,Typeface.BOLD));p.setTextSize(dp(h/density<120?16:20));p.setColor(0xff223c62);
        String code=ClipMath.clockCode(position);if(p.measureText(code)>h-dp(10))p.setTextSize(p.getTextSize()*(h-dp(10))/p.measureText(code));c.drawText(code,dp(5),dp(29),p);
        drawHeader(c,0,r,h,rh,false);drawHeader(c,1,r+rh,h,rh,true);
        c.save();c.clipRect(h,0,w,height);
        rect(c,h,0,w,r,0xff9c9c9c);
        if(duration>0){
            rect(c,x(start),dp(14),x(end),dp(24),0xffd9bc6e);
            rect(c,x(start),r,x(end),r+rh*2,0xff929dab);
        }
        double[] steps={100,200,500,1000,2000,5000,10000,30000,60000,120000,300000,600000,1800000,3600000};
        double step=steps[steps.length-1];
        for(double candidate:steps)if(candidate/visible*(w-h)>=dp(66)){step=candidate;break;}
        for(double t=Math.floor(offset/step)*step;t<offset+visible;t+=step){
            float xx=x(t);line(c,xx,dp(34),xx,height-dp(15),0xff727272);
            text(c,ClipMath.clock((long)t),xx+dp(3),dp(33),9,0xff252525);
            for(int j=1;j<4;j++)line(c,x(t+j*step/4),dp(39),x(t+j*step/4),r,0xff777777);
        }
        line(c,h,r,w,r,0xff616161);line(c,h,r+rh,w,r+rh,0xff616161);line(c,h,r+rh*2,w,r+rh*2,0xff616161);
        if(duration>0){drawVideo(c,r+dp(5),rh-dp(10));if(hasAudio)drawAudio(c,r+rh+dp(5),rh-dp(10));}
        else{text(c,"Откройте видео или проект",h+dp(16),r+dp(36),12,0xff343434);}
        for(int i=0;i<markers.size();i++){
            float xx=x(markers.get(i));line(c,xx,dp(10),xx,height-dp(15),0xff9a602c);
            rect(c,xx,0,xx+dp(17),dp(13),0xffd9a363);text(c,Integer.toString(i+1),xx+dp(4),dp(10),9,0xff3f2c1c);
        }
        if(duration>0){
            handle(c,x(start),false);handle(c,x(end),true);
            float xx=x(position);line(c,xx,dp(24),xx,height-dp(15),0xff171717);
            Path head=new Path();head.moveTo(xx-dp(5),dp(23));head.lineTo(xx+dp(5),dp(23));head.lineTo(xx,dp(30));head.close();p.setColor(0xff272727);c.drawPath(head,p);
        }
        rect(c,h,height-dp(14),w,height,0xffaeaeae);
        float scrollWidth=(float)Math.max(dp(30),(w-h)*Math.min(1,visible/Math.max(visible,duration)));
        float left=h+(w-h-scrollWidth)*(float)(offset/Math.max(1,duration-visible));
        rect(c,left+1,height-dp(12),left+scrollWidth-1,height-dp(2),0xffd2d2d2);
        line(c,left+1,height-dp(12),left+scrollWidth-1,height-dp(12),0xffeeeeee);
        c.restore();line(c,h,0,h,height,0xff575757);
    }
    private void drawHeader(Canvas c,int index,float y,float h,float rh,boolean audio){
        rect(c,0,y,h,y+rh,index==0?0xff8c9eb6:0xff9b9b9b);line(c,0,y,h,y,0xffd1d1d1);line(c,0,y+rh,h,y+rh,0xff575757);
        rect(c,dp(5),y+dp(6),dp(24),y+dp(22),audio?0xff8c5b52:0xff445b83);
        text(c,Integer.toString(index+1),dp(11),y+dp(18),10,Color.WHITE);
        text(c,audio?"Audio":"Video",dp(30),y+dp(18),11,0xff202020);
        rect(c,h-dp(26),y+dp(5),h-dp(5),y+dp(26),audio&&muted?0xffd7b266:0xffababab);
        text(c,"M",h-dp(22),y+dp(20),11,audio?0xff252525:0xff6d6d6d);
        if(rh>dp(48))text(c,audio?(hasAudio?"Vol: 0.0 dB":"Нет аудио"):"Level: 100.0 %",dp(7),y+dp(43),9,0xff292929);
        if(rh>dp(64))text(c,audio?"Pan: Center":"Compositing: Source",dp(7),y+dp(59),9,0xff363636);
        if(rh>dp(88)){line(c,dp(9),y+dp(75),h-dp(10),y+dp(75),0xff4e4e4e);rect(c,h*.62f,y+dp(70),h*.62f+dp(7),y+dp(80),0xffd0d0d0);}
    }
    private void drawVideo(Canvas c,float y,float height){
        float l=x(0),right=x(duration);
        rect(c,l,y,right,y+height,0xff9dadc3);rect(c,l,y,right,y+dp(17),0xff516b92);
        text(c,name,l+dp(5),y+dp(12),10,Color.WHITE);
        if(frames!=null&&frames.length>0){
            c.save();c.clipRect(l,y+dp(18),right,y+height-dp(2));
            float th=height-dp(20),tw=th*1.7f;
            for(float px=Math.max(l,header());px<Math.min(right,getWidth());px+=tw){
                int ms=time(px);Bitmap bitmap=frames[Math.min(frames.length-1,(int)((long)ms*frames.length/Math.max(1,duration)))];
                if(bitmap==null)continue;
                int bw=bitmap.getWidth(),bh=bitmap.getHeight();float ratio=(tw-dp(2))/Math.max(1,th);
                if(bw/(float)bh>ratio){int crop=Math.max(1,Math.min(bw,Math.round(bh*ratio)));sourceRect.set((bw-crop)/2,0,(bw+crop)/2,bh);}
                else{int crop=Math.max(1,Math.min(bh,Math.round(bw/ratio)));sourceRect.set(0,(bh-crop)/2,bw,(bh+crop)/2);}
                targetRect.set(px,y+dp(18),px+tw-dp(2),y+height-dp(2));p.setColor(Color.WHITE);c.drawBitmap(bitmap,sourceRect,targetRect,p);
            }
            c.restore();
        }
        shade(c,l,right,y,height);
        line(c,l,y,right,y,0xffe5e5e5);line(c,l,y,l,y+height,0xff2f3e55);line(c,right-1,y,right-1,y+height,0xff26394f);
    }
    private void drawAudio(Canvas c,float y,float height){
        float l=x(0),right=x(duration),middle=y+height*.58f;
        rect(c,l,y,right,y+height,muted?0xffa7a19a:0xffc9b8ad);
        rect(c,l,y,right,y+dp(16),muted?0xff817d78:0xff8d5b51);text(c,name+(muted?"  [M]":""),l+dp(5),y+dp(12),10,Color.WHITE);
        line(c,l,middle,right,middle,0xff8f7a75);
        if(waveform!=null){
            p.setColor(muted?0xff8a817b:0xff713e3b);p.setStrokeWidth(Math.max(dp(1),(right-l)/waveform.length));
            for(int i=0;i<waveform.length;i++){
                float xx=x((double)i*duration/waveform.length);if(xx<header()||xx>getWidth())continue;
                float amp=Math.min(1,waveform[i])*height*.32f;c.drawLine(xx,middle-amp,xx,middle+amp,p);
            }
        }else text(c,"Аудиодорожка",Math.max(l,header())+dp(7),middle,10,0xff6d504b);
        shade(c,l,right,y,height);line(c,l,y,right,y,0xffe5d9d1);
    }
    private void shade(Canvas c,float l,float right,float y,float height){
        rect(c,l,y,x(start),y+height,0x65434343);rect(c,x(end),y,right,y+height,0x65434343);
        line(c,x(start),y,x(start),y+height,0xffe6ca73);line(c,x(end),y,x(end),y+height,0xffe6ca73);
    }
    private void handle(Canvas c,float x,boolean right){
        p.setColor(0xffffd572);Path path=new Path();path.moveTo(x,dp(12));path.lineTo(x+(right?-dp(10):dp(10)),dp(12));path.lineTo(x,dp(23));path.close();c.drawPath(path,p);
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(!isEnabled()||duration<=0)return false;
        pinch.onTouchEvent(e);
        if(e.getPointerCount()>1){if(drag==1||drag==2){if(listener!=null)listener.editEnd();}drag=0;getParent().requestDisallowInterceptTouchEvent(true);return true;}
        if(pinch.isInProgress())return true;
        float x=e.getX(),y=e.getY(),h=header();
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                downTime=System.currentTimeMillis();lastX=x;
                if(x<h){if(y>ruler()+row()&&y<ruler()+row()+dp(30)&&x>h-dp(34)&&hasAudio&&listener!=null)listener.mute();return true;}
                getParent().requestDisallowInterceptTouchEvent(true);
                if(y>getHeight()-dp(20)){drag=4;return true;}
                if(y<ruler()&&Math.abs(x-x(start))<dp(20)&&Math.abs(x-x(start))<=Math.abs(x-x(end))){drag=1;if(listener!=null)listener.editBegin();}
                else if(y<ruler()&&Math.abs(x-x(end))<dp(20)){drag=2;if(listener!=null)listener.editBegin();}
                else{drag=3;if(listener!=null)listener.seek(time(x));}
                return true;
            case MotionEvent.ACTION_MOVE:
                if(drag==1&&listener!=null)listener.range(Math.min(time(x),end-1),end);
                else if(drag==2&&listener!=null)listener.range(start,Math.max(time(x),start+1));
                else if(drag==3&&listener!=null)listener.seek(time(x));
                else if(drag==4){offset=Math.max(0,Math.min(Math.max(0,duration-visible),offset+(x-lastX)/Math.max(1,getWidth()-h)*duration));invalidate();}
                lastX=x;return true;
            case MotionEvent.ACTION_UP:
                if((drag==1||drag==2)&&listener!=null)listener.editEnd();
                else if(drag==3&&System.currentTimeMillis()-downTime>650&&listener!=null)listener.preciseRange();
                drag=0;getParent().requestDisallowInterceptTouchEvent(false);performClick();return true;
            case MotionEvent.ACTION_CANCEL:
                if((drag==1||drag==2)&&listener!=null)listener.editEnd();drag=0;getParent().requestDisallowInterceptTouchEvent(false);return true;
        }
        return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
