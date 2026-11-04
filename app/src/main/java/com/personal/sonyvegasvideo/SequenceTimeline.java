package com.personal.sonyvegasvideo;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

/** Accessible project cursor and real main, overlay, source-audio and music tracks. */
public final class SequenceTimeline extends View {
    public interface Listener {void seek(int projectMillis);}
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final ScaleGestureDetector pinch;
    private final GestureDetector gestures;
    private Sequence sequence=new Sequence();
    private EffectSpec effects=new EffectSpec();
    private TransitionTiming timing=new TransitionTiming(sequence,effects);
    private Listener listener;
    private int selected=-1,position;
    private double visible=10000,offset;
    private boolean muted,scrolling,longPressed;

    public SequenceTimeline(Context context) {
        super(context);
        setFocusable(true);
        updateDescription();
        pinch=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScale(ScaleGestureDetector detector){zoom(detector.getScaleFactor());return true;}
        });
        gestures=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent event){return true;}
            @Override public void onLongPress(MotionEvent event) {
                if(!scrolling&&event.getX()>header()&&!sequence.clips.isEmpty()) {
                    longPressed=true;
                    performLongClick();
                }
            }
        });
    }
    public void listener(Listener value){listener=value;}
    public void project(Sequence value,int index) {
        project(value,index,effects,muted);
    }
    public void project(Sequence value,int index,EffectSpec settings,boolean mute) {
        EffectSpec normalized=settings.copy().normalize();
        boolean changed=sequence!=value||!effects.transition.equals(normalized.transition)||effects.transitionMs!=normalized.transitionMs;
        int previous=timing.length;
        sequence=value;selected=index;effects=normalized;muted=mute;
        if(changed)timing=new TransitionTiming(sequence,effects);
        position=ClipMath.clamp(position,0,timing.length);
        if(previous!=timing.length)fit();
        updateDescription();invalidate();
    }
    public void settings(EffectSpec value,boolean mute) {
        project(sequence,selected,value,mute);
    }
    private int duration(){return timing.length;}
    public void position(int value) {
        int next=ClipMath.clamp(value,0,duration());
        if(next!=position) {
            position=next;
            if(position<offset||position>offset+visible)offset=bound(position-visible*.2);
            updateDescription();
        }
        invalidate();
    }
    public void fit(){visible=Math.max(1000,duration()*1.05);offset=0;invalidate();}
    public void zoom(float factor) {
        if(!Float.isFinite(factor)||factor<=0)return;
        double focus=position>=offset&&position<=offset+visible?position:offset+visible*.5;
        double ratio=(focus-offset)/visible;
        visible=Math.max(200,Math.min(Math.max(10000,duration()*1.10),visible/factor));
        offset=bound(focus-ratio*visible);invalidate();
    }
    private double bound(double value){return Math.max(0,Math.min(Math.max(0,duration()-visible),value));}
    private float dp(float value){return value*getResources().getDisplayMetrics().density;}
    private float header(){return Math.min(dp(120),getWidth()*.24f);}
    private float x(double millis){return header()+(float)((millis-offset)/visible*(getWidth()-header()));}
    private int time(float x){return ClipMath.clamp((int)(offset+(x-header())/Math.max(1,getWidth()-header())*visible),0,duration());}
    private void rect(Canvas canvas,float left,float top,float right,float bottom,int color){paint.setColor(color);canvas.drawRect(left,top,right,bottom,paint);}
    private void text(Canvas canvas,String value,float x,float y,int color,int size){paint.setColor(color);paint.setTextSize(dp(size));canvas.drawText(value,x,y,paint);}
    private int rows(){return 2+(effects.overlayUri.isEmpty()?0:1)+(effects.audioUri.isEmpty()?0:1);}
    private float thumb(){return (float)Math.min(getWidth()-header(),Math.max(dp(30),(getWidth()-header())*Math.min(1,visible/Math.max(visible,duration()))));}

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float h=header(),ruler=dp(38),rowHeight=Math.max(dp(22),(getHeight()-ruler-dp(16))/rows());
        canvas.drawColor(EditorStyle.SURFACE);
        rect(canvas,0,0,h,getHeight(),EditorStyle.TONAL);
        rect(canvas,h,0,getWidth(),ruler,EditorStyle.TONAL);
        paint.setTypeface(Typeface.MONOSPACE);paint.setTextSize(dp(13));
        String code=ClipMath.clockCode(position);
        float available=Math.max(1,h-dp(7));
        if(paint.measureText(code)>available)paint.setTextSize(paint.getTextSize()*available/paint.measureText(code));
        paint.setColor(EditorStyle.ACCENT);canvas.drawText(code,dp(3),dp(24),paint);paint.setTypeface(Typeface.DEFAULT);
        String[] labels=new String[rows()];
        int lane=0;labels[lane++]="1  Video";
        if(!effects.overlayUri.isEmpty())labels[lane++]="2  Overlay";
        int audioLane=lane;labels[lane]=(lane+1)+"  Audio";lane++;
        int musicLane=-1;if(!effects.audioUri.isEmpty()){musicLane=lane;labels[lane]=(lane+1)+"  Music";lane++;}
        for(int i=0;i<labels.length;i++) {
            float top=ruler+i*rowHeight;
            text(canvas,labels[i],dp(5),top+dp(17),EditorStyle.TEXT,10);
            if(rowHeight>=dp(40))text(canvas,i==0?(!effects.isIdentity()?"FX active":"Source video"):i==audioLane?(muted?"Muted":"Source audio"):i==musicLane?"Music / loop":"Picture in picture",dp(5),top+dp(35),EditorStyle.SECONDARY,8);
            rect(canvas,0,top+rowHeight-dp(1),getWidth(),top+rowHeight,EditorStyle.DIVIDER);
        }
        canvas.save();canvas.clipRect(h,0,getWidth(),getHeight());
        int step=1000;while(step/visible*(getWidth()-h)<dp(60)&&step<3600000)step*=2;
        for(double tick=Math.floor(offset/step)*step;tick<offset+visible;tick+=step) {
            float line=x(tick);rect(canvas,line,dp(28),line+dp(1),getHeight()-dp(13),EditorStyle.DIVIDER);
            text(canvas,ClipMath.clock((long)tick),line+dp(3),dp(24),EditorStyle.TEXT,9);
        }
        for(int i=0;i<sequence.clips.size();i++) {
            Sequence.Clip clip=sequence.clips.get(i);float left=x(timing.starts[i]),right=x(timing.ends[i]);
            event(canvas,left,right,ruler,rowHeight,i==selected?0xffd1e1ff:0xffe4edff,i==selected?EditorStyle.ACCENT:EditorStyle.VIDEO,(i+1)+"  "+clip.name,ClipMath.clock(clip.inMs)+" → "+ClipMath.clock(clip.outMs));
            event(canvas,left,right,ruler+audioLane*rowHeight,rowHeight,muted?0xffe3e8f0:i==selected?0xffc7e9dc:0xffe1f2ea,muted?0xff637084:EditorStyle.AUDIO,muted?"Muted":"Audio · "+(i+1),"");
        }
        drawTransitions(canvas,ruler,rowHeight,audioLane);
        if(!effects.overlayUri.isEmpty())event(canvas,x(0),x(duration()),ruler+rowHeight,rowHeight,0xffeee5fd,0xff66499b,"Video overlay","Loop to project");
        if(musicLane>=0)event(canvas,x(0),x(duration()),ruler+musicLane*rowHeight,rowHeight,0xffe1f0dc,0xff406c37,"Music","Loop to project");
        rect(canvas,x(position),ruler,x(position)+dp(1),getHeight()-dp(14),EditorStyle.ACCENT);
        rect(canvas,h,getHeight()-dp(13),getWidth(),getHeight(),EditorStyle.TONAL);
        float thumb=thumb(),left=h+(getWidth()-h-thumb)*(float)(offset/Math.max(1,duration()-visible));
        rounded(canvas,left,getHeight()-dp(11),left+thumb,getHeight()-dp(2),4,0xffaabbd7);
        canvas.restore();
    }
    private void drawTransitions(Canvas canvas,float ruler,float rowHeight,int audioLane) {
        for(int i=0;i<timing.overlaps.length;i++) {
            if(timing.overlaps[i]<=0)continue;
            float left=x(timing.starts[i+1]),right=x(timing.ends[i]);
            for(int lane:new int[]{0,audioLane}) {
                float top=ruler+lane*rowHeight+dp(3),bottom=ruler+(lane+1)*rowHeight-dp(3);
                rect(canvas,left,top,right,bottom,0x90765113);
                paint.setColor(0xfff9dd92);paint.setStrokeWidth(dp(1));
                canvas.drawLine(left,top,right,bottom,paint);canvas.drawLine(left,bottom,right,top,paint);
            }
            if(right-left>dp(45)) {
                canvas.save();canvas.clipRect(left,ruler+dp(3),right,ruler+rowHeight-dp(3));
                text(canvas,effects.transition,left+dp(3),ruler+dp(18),0xffffffff,9);canvas.restore();
            }
        }
    }
    private void rounded(Canvas canvas,float left,float top,float right,float bottom,int radius,int color) {
        paint.setColor(color);
        canvas.drawRoundRect(left,top,right,bottom,dp(radius),dp(radius),paint);
    }
    private void event(Canvas canvas,float left,float right,float top,float height,int body,int cap,String name,String detail) {
        rounded(canvas,left+1,top+dp(3),right-1,top+height-dp(3),5,body);
        rounded(canvas,left+1,top+dp(3),right-1,top+Math.min(dp(23),height-dp(3)),5,cap);
        canvas.save();canvas.clipRect(left+dp(3),top+dp(3),right-dp(2),top+height-dp(3));
        text(canvas,name,left+dp(6),top+dp(17),0xffffffff,10);
        if(height>=dp(44)&&!detail.isEmpty())text(canvas,detail,left+dp(6),top+dp(39),EditorStyle.TEXT,9);
        canvas.restore();
    }
    private void scrollTo(float x) {
        float track=Math.max(1,getWidth()-header()-thumb());
        offset=bound((x-header()-thumb()/2)/track*Math.max(0,duration()-visible));
        invalidate();
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if(!isEnabled()||sequence.clips.isEmpty())return false;
        gestures.onTouchEvent(event);pinch.onTouchEvent(event);
        if(event.getPointerCount()>1||pinch.isInProgress()){longPressed=false;return true;}
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                longPressed=false;scrolling=event.getY()>getHeight()-dp(20);
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);
                if(scrolling)scrollTo(event.getX());else if(event.getX()>header()&&listener!=null)listener.seek(time(event.getX()));
                return true;
            case MotionEvent.ACTION_MOVE:
                if(!longPressed) {
                    if(scrolling)scrollTo(event.getX());
                    else if(event.getX()>header()&&listener!=null)listener.seek(time(event.getX()));
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);
                if(!longPressed&&event.getActionMasked()==MotionEvent.ACTION_UP)performClick();
                scrolling=false;longPressed=false;return true;
            default:return true;
        }
    }
    @Override public boolean performClick(){super.performClick();return true;}
    private void updateDescription() {
        setContentDescription("Монтажный таймлайн. "+sequence.clips.size()+" фрагментов. Позиция "
                +ClipMath.clockCode(position)+" из "+ClipMath.clockCode(duration())
                +(sequence.clips.size()>1&&!effects.transition.equals("none")?". Переходы между клипами: "+effects.transition:"")
                +". Коснитесь для перемотки, удерживайте для точных границ. Два пальца меняют масштаб.");
    }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.SeekBar");
        info.setScrollable(!sequence.clips.isEmpty());
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,0,Math.max(1,duration()),position));
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK);
    }
    @Override public boolean performAccessibilityAction(int action,Bundle arguments) {
        if(isEnabled()&&listener!=null&&!sequence.clips.isEmpty()) {
            if(action==AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()&&arguments!=null) {
                float value=arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,position);
                if(Float.isFinite(value)){listener.seek(ClipMath.clamp(Math.round(value),0,duration()));return true;}
            }
            if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD){listener.seek((int)Math.min(duration(),(long)position+1000));return true;}
            if(action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD){listener.seek(Math.max(0,position-1000));return true;}
        }
        return super.performAccessibilityAction(action,arguments);
    }
}
