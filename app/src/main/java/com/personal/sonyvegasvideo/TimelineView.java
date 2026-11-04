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
        default void videoMute(){} default void videoSettings(){} default void audioSettings(){}
        default void opacity(float value){} default void gain(float value){} default void pan(float value){}
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
    private float opacity=1,gain=1,pan;
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
    public void levels(float opacity,float gain,float pan){this.opacity=Math.max(0,Math.min(1,opacity));this.gain=Math.max(0,Math.min(1,gain));this.pan=Math.max(-1,Math.min(1,pan));invalidate();}
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
    private void rounded(Canvas c,float l,float t,float r,float b,int radius,int color) {
        p.setColor(color);p.setStyle(Paint.Style.FILL);
        c.drawRoundRect(l,t,r,b,dp(radius),dp(radius),p);
    }
    @Override protected void onDraw(Canvas c){
        float h=header(),r=ruler(),rh=row(),w=getWidth(),height=getHeight();
        rect(c,0,0,w,height,EditorStyle.SURFACE);rect(c,0,0,h,r,EditorStyle.TONAL);
        p.setTypeface(Typeface.create(Typeface.MONOSPACE,Typeface.BOLD));p.setTextSize(dp(h/density<120?16:20));p.setColor(EditorStyle.ACCENT);
        String code=ClipMath.clockCode(position);if(p.measureText(code)>h-dp(10))p.setTextSize(p.getTextSize()*(h-dp(10))/p.measureText(code));c.drawText(code,dp(5),dp(29),p);
        drawHeader(c,0,r,h,rh,false);drawHeader(c,1,r+rh,h,rh,true);
        c.save();c.clipRect(h,0,w,height);
        rect(c,h,0,w,r,EditorStyle.TONAL);
        if(duration>0){
            rect(c,x(start),dp(14),x(end),dp(24),0xffefcf82);
            rect(c,x(start),r,x(end),r+rh*2,0xffedf3ff);
        }
        double[] steps={100,200,500,1000,2000,5000,10000,30000,60000,120000,300000,600000,1800000,3600000};
        double step=steps[steps.length-1];
        for(double candidate:steps)if(candidate/visible*(w-h)>=dp(66)){step=candidate;break;}
        for(double t=Math.floor(offset/step)*step;t<offset+visible;t+=step){
            float xx=x(t);line(c,xx,dp(34),xx,height-dp(15),EditorStyle.DIVIDER);
            text(c,ClipMath.clock((long)t),xx+dp(3),dp(33),9,EditorStyle.TEXT);
            for(int j=1;j<4;j++)line(c,x(t+j*step/4),dp(39),x(t+j*step/4),r,0xffa9b8ce);
        }
        line(c,h,r,w,r,EditorStyle.DIVIDER);line(c,h,r+rh,w,r+rh,EditorStyle.DIVIDER);line(c,h,r+rh*2,w,r+rh*2,EditorStyle.DIVIDER);
        if(duration>0){drawVideo(c,r+dp(5),rh-dp(10));if(hasAudio)drawAudio(c,r+rh+dp(5),rh-dp(10));}
        else{text(c,"Откройте видео или проект",h+dp(16),r+dp(36),12,EditorStyle.SECONDARY);}
        for(int i=0;i<markers.size();i++){
            float xx=x(markers.get(i));line(c,xx,dp(10),xx,height-dp(15),0xff9a602c);
            rect(c,xx,0,xx+dp(17),dp(13),0xffd9a363);text(c,Integer.toString(i+1),xx+dp(4),dp(10),9,0xff3f2c1c);
        }
        if(duration>0){
            handle(c,x(start),false);handle(c,x(end),true);
            float xx=x(position);line(c,xx,dp(24),xx,height-dp(15),EditorStyle.ACCENT);
            Path head=new Path();head.moveTo(xx-dp(5),dp(23));head.lineTo(xx+dp(5),dp(23));head.lineTo(xx,dp(30));head.close();p.setColor(EditorStyle.ACCENT);c.drawPath(head,p);
        }
        rect(c,h,height-dp(14),w,height,EditorStyle.TONAL);
        float scrollWidth=(float)Math.max(dp(30),(w-h)*Math.min(1,visible/Math.max(visible,duration)));
        float left=h+(w-h-scrollWidth)*(float)(offset/Math.max(1,duration-visible));
        rounded(c,left+1,height-dp(12),left+scrollWidth-1,height-dp(2),5,0xffaabbd7);
        c.restore();line(c,h,0,h,height,EditorStyle.DIVIDER);
    }
    private void drawHeader(Canvas c,int index,float y,float h,float rh,boolean audio){
        rect(c,0,y,h,y+rh,index==0?EditorStyle.TONAL:0xfff0f6f3);line(c,0,y,h,y,EditorStyle.DIVIDER);line(c,0,y+rh,h,y+rh,EditorStyle.DIVIDER);
        rounded(c,dp(5),y+dp(6),dp(24),y+dp(22),4,audio?EditorStyle.AUDIO:EditorStyle.VIDEO);
        text(c,Integer.toString(index+1),dp(11),y+dp(18),10,Color.WHITE);
        text(c,audio?"Audio":"Video",dp(30),y+dp(18),11,EditorStyle.TEXT);
        rounded(c,h-dp(26),y+dp(5),h-dp(5),y+dp(26),5,(audio?muted:opacity==0)?0xfff7dd9c:EditorStyle.SELECTED);
        text(c,"M",h-dp(22),y+dp(20),11,EditorStyle.TEXT);
        if(rh>dp(48))text(c,audio?(gain==0?"Vol: −∞ dB":String.format(java.util.Locale.ROOT,"Vol: %.1f dB",20*Math.log10(gain))):"Level: "+Math.round(opacity*100)+" %",dp(7),y+dp(43),9,EditorStyle.SECONDARY);
        if(rh>dp(66)){float value=audio?gain:opacity;float thumb=dp(9)+(h-dp(19))*value;line(c,dp(9),y+dp(55),h-dp(10),y+dp(55),0xffa9b8ce);rounded(c,thumb-dp(4),y+dp(51),thumb+dp(4),y+dp(59),4,EditorStyle.ACCENT);}
        if(rh>dp(86))text(c,audio?"Pan: "+(Math.abs(pan)<.01f?"Center":(pan<0?"L ":"R ")+Math.round(Math.abs(pan)*100)):"Video FX…",dp(7),y+dp(79),9,EditorStyle.ACCENT);
        if(audio&&rh>dp(103)){float thumb=dp(9)+(h-dp(19))*(pan+1)/2;line(c,dp(9),y+dp(95),h-dp(10),y+dp(95),0xffa9b8ce);rounded(c,thumb-dp(4),y+dp(91),thumb+dp(4),y+dp(99),4,EditorStyle.ACCENT);}
    }
    private void drawVideo(Canvas c,float y,float height){
        float l=x(0),right=x(duration);
        rect(c,l,y,right,y+height,0xffdce8ff);rect(c,l,y,right,y+dp(17),EditorStyle.VIDEO);
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
        rect(c,l,y,right,y+height,muted?0xffe3e8f0:0xffd4ede2);
        rect(c,l,y,right,y+dp(16),muted?0xff637084:EditorStyle.AUDIO);text(c,name+(muted?"  [M]":""),l+dp(5),y+dp(12),10,Color.WHITE);
        line(c,l,middle,right,middle,0xff97c6b6);
        if(waveform!=null){
            p.setColor(muted?0xff798495:EditorStyle.AUDIO);p.setStrokeWidth(Math.max(dp(1),(right-l)/waveform.length));
            for(int i=0;i<waveform.length;i++){
                float xx=x((double)i*duration/waveform.length);if(xx<header()||xx>getWidth())continue;
                float amp=Math.min(1,waveform[i])*height*.32f;c.drawLine(xx,middle-amp,xx,middle+amp,p);
            }
        }else text(c,"Аудиодорожка",Math.max(l,header())+dp(7),middle,10,EditorStyle.AUDIO);
        shade(c,l,right,y,height);line(c,l,y,right,y,0xffb8ddce);
    }
    private void shade(Canvas c,float l,float right,float y,float height){
        rect(c,l,y,x(start),y+height,0x65142136);rect(c,x(end),y,right,y+height,0x65142136);
        line(c,x(start),y,x(start),y+height,0xffe8bf62);line(c,x(end),y,x(end),y+height,0xffe8bf62);
    }
    private void handle(Canvas c,float x,boolean right){
        p.setColor(0xffe8bf62);Path path=new Path();path.moveTo(x,dp(12));path.lineTo(x+(right?-dp(10):dp(10)),dp(12));path.lineTo(x,dp(23));path.close();c.drawPath(path,p);
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(!isEnabled()||duration<=0)return false;
        pinch.onTouchEvent(e);
        if(e.getPointerCount()>1){if(drag==1||drag==2||drag>=5&&drag<=7){if(listener!=null)listener.editEnd();}drag=0;getParent().requestDisallowInterceptTouchEvent(true);return true;}
        if(pinch.isInProgress())return true;
        float x=e.getX(),y=e.getY(),h=header();
        switch(e.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                downTime=System.currentTimeMillis();lastX=x;
                if(x<h){
                    float local=y-ruler();boolean audio=local>=row();if(audio)local-=row();
                    if(local<0||local>=row())return true;
                    if(local<dp(30)&&x>h-dp(34)){if(listener!=null){if(audio){if(hasAudio)listener.mute();}else listener.videoMute();}return true;}
                    if(row()>dp(66)&&local>=dp(45)&&local<=dp(66)){drag=audio?6:5;if(listener!=null)listener.editBegin();headerChange(x);}
                    else if(audio&&row()>dp(103)&&local>=dp(85)){drag=7;if(listener!=null)listener.editBegin();headerChange(x);}
                    else drag=audio?9:8;
                    getParent().requestDisallowInterceptTouchEvent(true);return true;
                }
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
                else if(drag>=5&&drag<=7)headerChange(x);
                lastX=x;return true;
            case MotionEvent.ACTION_UP:
                if((drag==1||drag==2||drag>=5&&drag<=7)&&listener!=null)listener.editEnd();
                else if(drag==8&&listener!=null)listener.videoSettings();
                else if(drag==9&&listener!=null)listener.audioSettings();
                else if(drag==3&&System.currentTimeMillis()-downTime>650&&listener!=null)listener.preciseRange();
                drag=0;getParent().requestDisallowInterceptTouchEvent(false);performClick();return true;
            case MotionEvent.ACTION_CANCEL:
                if((drag==1||drag==2||drag>=5&&drag<=7)&&listener!=null)listener.editEnd();drag=0;getParent().requestDisallowInterceptTouchEvent(false);return true;
        }
        return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
    private void headerChange(float x){if(listener==null)return;float value=Math.max(0,Math.min(1,(x-dp(9))/Math.max(1,header()-dp(19))));if(drag==5)listener.opacity(value);else if(drag==6)listener.gain(value);else if(drag==7)listener.pan(value*2-1);}
}
