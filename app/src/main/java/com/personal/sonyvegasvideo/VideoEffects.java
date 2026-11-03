package com.personal.sonyvegasvideo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.opengl.GLES20;
import androidx.media3.common.Effect;
import androidx.media3.common.OverlaySettings;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.GlProgram;
import androidx.media3.common.util.GlUtil;
import androidx.media3.common.util.Size;
import androidx.media3.effect.BaseGlShaderProgram;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.Brightness;
import androidx.media3.effect.Contrast;
import androidx.media3.effect.GaussianBlur;
import androidx.media3.effect.GlEffect;
import androidx.media3.effect.GlShaderProgram;
import androidx.media3.effect.HslAdjustment;
import androidx.media3.effect.MatrixTransformation;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.RgbMatrix;
import androidx.media3.effect.StaticOverlaySettings;
import androidx.media3.effect.TextureOverlay;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One deterministic GPU graph shared by the editor preview and exported MP4. */
public final class VideoEffects {
    private VideoEffects() { }
    public static List<Effect> create(EffectSpec parameters,long durationUs,long originUs){
        EffectSpec s=parameters==null?new EffectSpec():parameters.copy().normalize();
        List<Effect> effects=new ArrayList<>();
        if(s.brightness!=0)effects.add(new Brightness(s.brightness));
        if(s.contrast!=0)effects.add(new Contrast(s.contrast));
        if(s.saturation!=1||s.hue!=0)effects.add(new HslAdjustment.Builder().adjustHue(s.hue).adjustSaturation((s.saturation-1)*100).build());
        switch(s.preset){
            case "mono":effects.add(rgb(new float[]{.2126f,.2126f,.2126f,0,.7152f,.7152f,.7152f,0,.0722f,.0722f,.0722f,0,0,0,0,1}));break;
            case "sepia":effects.add(rgb(new float[]{.393f,.349f,.272f,0,.769f,.686f,.534f,0,.189f,.168f,.131f,0,0,0,0,1}));break;
            case "vivid":effects.add(new HslAdjustment.Builder().adjustSaturation(30).build());effects.add(new Contrast(.12f));break;
            case "cool":effects.add(rgb(new float[]{.9f,0,0,0,0,.98f,0,0,0,0,1.12f,0,0,0,0,1}));break;
            case "warm":effects.add(rgb(new float[]{1.12f,0,0,0,0,1,0,0,0,0,.88f,0,0,0,0,1}));break;
            case "invert":effects.add(rgb(new float[]{-1,0,0,0,0,-1,0,0,0,0,-1,0,1,1,1,1}));break;
            case "vintage":effects.add(rgb(new float[]{.73f,.16f,.10f,0,.34f,.81f,.24f,0,.08f,.08f,.61f,0,.025f,.012f,0,1}));break;
            default:break;
        }
        if(s.mosaic>0||s.chromaKey||s.videoOpacity!=1){EffectSpec pixels=s.copy();pixels.transition="none";effects.add(new FrameEffect(pixels,durationUs,originUs));}
        if(s.blur>.01f)effects.add(new GaussianBlur(s.blur));
        if(s.zoomStart!=1||s.zoomEnd!=1||s.rotationStart!=0||s.rotationEnd!=0){
            effects.add((MatrixTransformation)time->{
                float t=progress(time,durationUs,originUs);float zoom=s.zoomStart+(s.zoomEnd-s.zoomStart)*t;
                Matrix matrix=new Matrix();matrix.postScale(zoom,zoom);matrix.postRotate(s.rotationStart+(s.rotationEnd-s.rotationStart)*t);return matrix;
            });
        }
        if(!s.title.trim().isEmpty())effects.add(new OverlayEffect(Collections.<TextureOverlay>singletonList(new TitleOverlay(s))));
        if(!s.transition.equals("none")){EffectSpec transition=new EffectSpec();transition.transition=s.transition;transition.transitionMs=s.transitionMs;effects.add(new FrameEffect(transition,durationUs,originUs));}
        return effects;
    }
    /** Masks a front-lane clip only at its overlap boundaries, never at an outer movie edge. */
    public static Effect transitionMask(EffectSpec parameters,long clipDurationUs,long fadeInUs,long fadeOutUs){
        return transitionMask(parameters,clipDurationUs,fadeInUs,fadeOutUs,0);
    }
    /** Variant for graph stages whose timestamps include the clip's offset in the composition. */
    public static Effect transitionMask(EffectSpec parameters,long clipDurationUs,long fadeInUs,long fadeOutUs,long originUs){
        EffectSpec source=parameters==null?new EffectSpec():parameters.copy().normalize();
        EffectSpec mask=new EffectSpec();mask.transition=source.transition;mask.transitionMs=source.transitionMs;
        long duration=Math.max(0,clipDurationUs);
        // Media3 DefaultVideoCompositor uses SRC_ALPHA: keep RGB straight for lane blending.
        return new FrameEffect(mask,duration,originUs,Math.max(0,Math.min(duration,fadeInUs)),Math.max(0,Math.min(duration,fadeOutUs)),true);
    }
    private static RgbMatrix rgb(float[] matrix){return (time,hdr)->matrix.clone();}
    private static float progress(long time,long duration,long origin){return duration>0?Math.max(0,Math.min(1,(time-origin)/(float)duration)):0;}

    private static final class TitleOverlay extends BitmapOverlay {
        private final EffectSpec spec;private Bitmap bitmap;private int width,height;
        TitleOverlay(EffectSpec source){spec=source.copy();}
        @Override public void configure(Size size){
            int w=Math.min(1600,Math.max(64,size.getWidth()));int h=Math.min(640,Math.max(48,Math.round(size.getHeight()*spec.titleSize*5.2f)));
            if(w==width&&h==height&&bitmap!=null)return;
            // The previous texture has already been consumed when the graph is reconfigured.
            if(bitmap!=null)bitmap.recycle();width=w;height=h;bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
            Canvas canvas=new Canvas(bitmap);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(spec.titleColor);paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF,android.graphics.Typeface.BOLD));
            paint.setTextSize(Math.max(14,Math.min(144,size.getHeight()*spec.titleSize)));paint.setShadowLayer(Math.max(1,paint.getTextSize()*.08f),0,2,Color.BLACK);
            List<String> lines=lines(spec.title,paint,w*.9f,4);float line=paint.getTextSize()*1.2f;
            float y=(h-lines.size()*line)/2-paint.ascent();for(String text:lines){canvas.drawText(text,w/2f,y,paint);y+=line;}
        }
        @Override public Bitmap getBitmap(long time){if(bitmap==null)configure(new Size(1280,720));return bitmap;}
        @Override public OverlaySettings getOverlaySettings(long time){return new StaticOverlaySettings.Builder().setBackgroundFrameAnchor(0,spec.titleY).setOverlayFrameAnchor(0,0).build();}
        @Override public void release()throws VideoFrameProcessingException{super.release();if(bitmap!=null){bitmap.recycle();bitmap=null;}}
    }
    static List<String> lines(String text,Paint paint,float width,int max){
        List<String> result=new ArrayList<>();String remaining=text.replace('\r',' ').trim();
        while(!remaining.isEmpty()&&result.size()<max){int newline=remaining.indexOf('\n');int end=paint.breakText(remaining,true,width,null);if(newline>=0&&newline<end)end=newline;
            end=Math.max(1,end);if(end<remaining.length()&&remaining.charAt(end)!='\n'){int space=remaining.lastIndexOf(' ',end);if(space>0)end=space;}
            result.add(remaining.substring(0,end).trim());remaining=remaining.substring(end).trim();}
        return result;
    }

    private static final class FrameEffect implements GlEffect {
        private final EffectSpec spec;private final long duration,origin,fadeIn,fadeOut;private final boolean alphaOnly;
        FrameEffect(EffectSpec spec,long duration,long origin){this(spec,duration,origin,-1,-1);}
        FrameEffect(EffectSpec spec,long duration,long origin,long fadeIn,long fadeOut){this(spec,duration,origin,fadeIn,fadeOut,false);}
        FrameEffect(EffectSpec spec,long duration,long origin,long fadeIn,long fadeOut,boolean alphaOnly){this.spec=spec.copy().normalize();this.duration=duration;this.origin=origin;this.fadeIn=fadeIn;this.fadeOut=fadeOut;this.alphaOnly=alphaOnly;}
        @Override public GlShaderProgram toGlShaderProgram(Context context,boolean hdr)throws VideoFrameProcessingException{return new FrameProgram(spec,duration,origin,fadeIn,fadeOut,alphaOnly,hdr);}
    }
    private static final class FrameProgram extends BaseGlShaderProgram {
        private final GlProgram program;private final EffectSpec spec;private final long duration,origin,fadeIn,fadeOut;private final boolean alphaOnly;private int width,height;
        FrameProgram(EffectSpec spec,long duration,long origin,long fadeIn,long fadeOut,boolean alphaOnly,boolean hdr)throws VideoFrameProcessingException{
            super(hdr,1);this.spec=spec;this.duration=duration;this.origin=origin;this.fadeIn=fadeIn;this.fadeOut=fadeOut;this.alphaOnly=alphaOnly;
            try{
                program=new GlProgram(VERTEX,FRAGMENT);program.setBufferAttribute("aFramePosition",GlUtil.getNormalizedCoordinateBounds(),4);
            }catch(GlUtil.GlException error){throw new VideoFrameProcessingException(error);}
        }
        @Override public Size configure(int width,int height){this.width=width;this.height=height;return new Size(width,height);}
        @Override public void drawFrame(int tex,long time)throws VideoFrameProcessingException{
            try{
                program.use();program.setSamplerTexIdUniform("uTexSampler",tex,0);program.setFloatsUniform("uPixelStep",new float[]{1f/Math.max(1,width),1f/Math.max(1,height)});
                program.setFloatUniform("uMosaic",spec.mosaic);program.setIntUniform("uChroma",spec.chromaKey?1:0);
                program.setFloatsUniform("uKey",new float[]{linear(Color.red(spec.chromaColor)),linear(Color.green(spec.chromaColor)),linear(Color.blue(spec.chromaColor))});
                program.setFloatUniform("uThreshold",spec.chromaThreshold);
                program.setFloatUniform("uOpacity",spec.videoOpacity);
                program.setIntUniform("uAlphaOnly",alphaOnly?1:0);
                int kind=spec.transition.equals("dissolve")?1:spec.transition.equals("cross")?2:spec.transition.equals("wipe")?3:spec.transition.equals("cascade")?4:0;
                program.setIntUniform("uTransition",kind);
                float edge=1;if(kind!=0&&duration>0){
                    long local=Math.max(0,time-origin);
                    if(fadeIn<0&&fadeOut<0){long fade=Math.max(1,Math.min(spec.transitionMs*1000L,duration/2));edge=Math.min(local/(float)fade,(duration-local)/(float)fade);}
                    else{if(fadeIn>0)edge=Math.min(edge,local/(float)fadeIn);if(fadeOut>0)edge=Math.min(edge,(duration-local)/(float)fadeOut);}
                    edge=Math.max(0,Math.min(1,edge));
                }
                program.setFloatUniform("uProgress",edge);program.bindAttributesAndUniforms();GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);GlUtil.checkGlError();
            }catch(GlUtil.GlException error){throw new VideoFrameProcessingException(error,time);}
        }
        @Override public void release()throws VideoFrameProcessingException{try{program.delete();}catch(GlUtil.GlException error){throw new VideoFrameProcessingException(error);}finally{super.release();}}
        private static float linear(int channel){return (float)Math.pow(channel/255f,2.2);}
    }
    private static final String VERTEX="attribute vec4 aFramePosition;\nvarying vec2 vTexCoord;\nvoid main(){gl_Position=aFramePosition;vTexCoord=aFramePosition.xy*0.5+0.5;}\n";
    private static final String FRAGMENT="precision mediump float;\n"
            +"uniform sampler2D uTexSampler;uniform vec2 uPixelStep;uniform float uMosaic;uniform int uChroma;uniform vec3 uKey;uniform float uThreshold;uniform float uOpacity;uniform int uAlphaOnly;uniform int uTransition;uniform float uProgress;varying vec2 vTexCoord;\n"
            +"void main(){vec2 uv=vTexCoord;float alpha=1.0;float q=clamp(uProgress,0.0,1.0);"
            +"if(uTransition==1){alpha=q;}else if(uTransition==2){float cross=min(abs(uv.x-0.5),abs(uv.y-0.5));alpha=step(cross,q*0.501);}else if(uTransition==3){alpha=step(uv.x,q);}else if(uTransition==4){float band=floor(uv.y*6.0);float p=clamp(q*1.5-band*0.1,0.0,1.0);float angle=(1.0-p)*1.35;uv.x=(uv.x-0.5)/max(0.04,cos(angle))+0.5+(1.0-p)*0.6;alpha=p;}"
            +"if(uv.x<0.0||uv.x>1.0||uv.y<0.0||uv.y>1.0){gl_FragColor=vec4(0.0);return;}"
            +"if(uMosaic>0.5){vec2 block=max(uPixelStep*uMosaic,uPixelStep);uv=(floor(uv/block)+0.5)*block;}"
            +"vec4 color=texture2D(uTexSampler,clamp(uv,vec2(0.0),vec2(1.0)));if(uChroma==1){float keyAlpha=smoothstep(uThreshold*0.6,uThreshold,distance(color.rgb,uKey));if(uAlphaOnly==1){color.a*=keyAlpha;}else{color*=keyAlpha;}}gl_FragColor=uAlphaOnly==1?vec4(color.rgb,color.a*alpha*uOpacity):color*alpha*uOpacity;}\n";
}
