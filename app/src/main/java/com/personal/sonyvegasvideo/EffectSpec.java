package com.personal.sonyvegasvideo;

import java.util.Objects;

/** Serializable edit parameters. This value is copied at every edit/history boundary. */
public final class EffectSpec implements Cloneable {
    public float brightness=0, contrast=0, saturation=1, hue=0, blur=0, mosaic=0;
    public float videoOpacity=1, audioPan=0;
    public boolean chromaKey=false;
    public int chromaColor=0xff00ff00;
    public float chromaThreshold=.25f;
    public String preset="none", transition="none";
    public int transitionMs=600;
    public String title="";
    public int titleColor=0xffffffff;
    public float titleSize=.075f, titleY=-.75f;
    public float masterGain=1;
    public String audioUri="";
    public float audioGain=.7f;
    public String overlayUri="";
    public float overlayScale=.35f, overlayX=.6f, overlayY=-.6f;
    public float zoomStart=1,zoomEnd=1,rotationStart=0,rotationEnd=0;

    public EffectSpec copy(){try{return (EffectSpec)super.clone();}catch(CloneNotSupportedException impossible){throw new AssertionError(impossible);}}
    public EffectSpec normalize(){
        brightness=limit(brightness,-1,1,0);contrast=limit(contrast,-.95f,.95f,0);
        saturation=limit(saturation,0,2,1);hue=limit(hue,-180,180,0);
        blur=limit(blur,0,20,0);mosaic=limit(mosaic,0,100,0);
        videoOpacity=limit(videoOpacity,0,1,1);audioPan=limit(audioPan,-1,1,0);
        chromaThreshold=limit(chromaThreshold,.03f,.8f,.25f);chromaColor|=0xff000000;
        preset=choice(preset,new String[]{"none","mono","sepia","vivid","cool","warm","invert","vintage"});
        transition=choice(transition,new String[]{"none","dissolve","cross","wipe","cascade"});
        transitionMs=Math.max(50,Math.min(5000,transitionMs));
        title=title==null?"":title;if(title.length()>200)title=title.substring(0,200);
        titleSize=limit(titleSize,.025f,.2f,.075f);titleY=limit(titleY,-.9f,.9f,-.75f);
        masterGain=limit(masterGain,0,1,1);audioGain=limit(audioGain,0,1,.7f);
        audioUri=boundedUri(audioUri);overlayUri=boundedUri(overlayUri);
        overlayScale=limit(overlayScale,.1f,1,.35f);overlayX=limit(overlayX,-1,1,.6f);overlayY=limit(overlayY,-1,1,-.6f);
        zoomStart=limit(zoomStart,.25f,3,1);zoomEnd=limit(zoomEnd,.25f,3,1);
        rotationStart=limit(rotationStart,-180,180,0);rotationEnd=limit(rotationEnd,-180,180,0);
        return this;
    }
    private static String boundedUri(String value){return value==null||value.length()>8192?"":value;}
    private static String choice(String value,String[] allowed){for(String item:allowed)if(item.equals(value))return item;return "none";}
    private static float limit(float v,float low,float high,float fallback){return Float.isFinite(v)?Math.max(low,Math.min(high,v)):fallback;}
    public boolean isIdentity(){EffectSpec s=copy().normalize();return s.brightness==0&&s.contrast==0&&s.saturation==1&&s.hue==0&&s.blur==0&&s.mosaic==0&&s.videoOpacity==1&&s.audioPan==0&&!s.chromaKey&&s.preset.equals("none")&&s.transition.equals("none")&&s.title.isEmpty()&&s.masterGain==1&&s.audioUri.isEmpty()&&s.overlayUri.isEmpty()&&s.zoomStart==1&&s.zoomEnd==1&&s.rotationStart==0&&s.rotationEnd==0;}
    @Override public boolean equals(Object value){
        if(this==value)return true;if(!(value instanceof EffectSpec))return false;EffectSpec s=(EffectSpec)value;
        return Float.compare(brightness,s.brightness)==0&&Float.compare(contrast,s.contrast)==0&&Float.compare(saturation,s.saturation)==0&&Float.compare(hue,s.hue)==0&&Float.compare(blur,s.blur)==0&&Float.compare(mosaic,s.mosaic)==0&&Float.compare(videoOpacity,s.videoOpacity)==0&&Float.compare(audioPan,s.audioPan)==0&&chromaKey==s.chromaKey&&chromaColor==s.chromaColor&&Float.compare(chromaThreshold,s.chromaThreshold)==0&&Objects.equals(preset,s.preset)&&Objects.equals(transition,s.transition)&&transitionMs==s.transitionMs&&Objects.equals(title,s.title)&&titleColor==s.titleColor&&Float.compare(titleSize,s.titleSize)==0&&Float.compare(titleY,s.titleY)==0&&Float.compare(masterGain,s.masterGain)==0&&Objects.equals(audioUri,s.audioUri)&&Float.compare(audioGain,s.audioGain)==0&&Objects.equals(overlayUri,s.overlayUri)&&Float.compare(overlayScale,s.overlayScale)==0&&Float.compare(overlayX,s.overlayX)==0&&Float.compare(overlayY,s.overlayY)==0&&Float.compare(zoomStart,s.zoomStart)==0&&Float.compare(zoomEnd,s.zoomEnd)==0&&Float.compare(rotationStart,s.rotationStart)==0&&Float.compare(rotationEnd,s.rotationEnd)==0;
    }
    @Override public int hashCode(){return Objects.hash(brightness,contrast,saturation,hue,blur,mosaic,chromaKey,chromaColor,chromaThreshold,preset,transition,transitionMs,title,titleColor,titleSize,titleY,masterGain,audioUri,audioGain,overlayUri,overlayScale,overlayX,overlayY,zoomStart,zoomEnd,rotationStart,rotationEnd,videoOpacity,audioPan);}
}
