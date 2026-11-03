package com.personal.sonyvegasvideo;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.FrameLayout;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.effect.MultipleInputVideoGraph;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.CompositionPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Reusable, lifecycle-safe view over the same composition graph as export. */
public final class EditorPreview extends FrameLayout {
    public interface ErrorListener {boolean onError(EditorPreview player,int what,int extra);}
    private final Context application;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final PlayerView view;
    private ExecutorService worker=Executors.newSingleThreadExecutor();
    private CompositionPlayer player;
    private Composition composition;
    private Consumer<EditorPreview> preparedListener,completionListener;
    private ErrorListener errorListener;
    private EffectSpec settings=new EffectSpec();
    private Sequence sequence;
    private Uri source;
    private boolean muted,ready,wantPlay,released,preparedNotified;
    private int duration,pendingPosition,effectStart,effectEnd=-1;
    private long generation;
    private float volume=1,speed=1;
    private String errorMessage="";

    public EditorPreview(Context context){
        super(context);application=context.getApplicationContext();
        view=new PlayerView(context);view.setUseController(false);view.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
        view.setKeepContentOnPlayerReset(false);addView(view,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        setContentDescription("Предпросмотр видео с эффектами");
    }
    public void setOnPreparedListener(Consumer<EditorPreview> listener){preparedListener=listener;}
    public void setOnCompletionListener(Consumer<EditorPreview> listener){completionListener=listener;}
    public void setOnErrorListener(ErrorListener listener){errorListener=listener;}
    public String getErrorMessage(){return errorMessage;}
    public void setVideoURI(Uri uri){setVideoURI(uri,settings,muted);}
    public void setVideoURI(Uri uri,EffectSpec spec,boolean mute){
        if(released)return;source=uri;sequence=null;settings=(spec==null?new EffectSpec():spec.copy()).normalize();muted=mute;
        pendingPosition=0;wantPlay=false;duration=0;effectStart=0;effectEnd=-1;volume=settings.masterGain;if(hasLayers())speed=1;
        if(uri==null){stopPlayback();return;}reload();
    }
    public void setProject(Sequence project,EffectSpec spec,boolean mute){
        if(released)return;boolean same=sequence==project&&source==null;
        int position=same?getCurrentPosition():pendingPosition;boolean play=same&&isPlaying();
        source=null;sequence=project;effectStart=0;effectEnd=-1;settings=(spec==null?new EffectSpec():spec.copy()).normalize();muted=mute;
        pendingPosition=position;wantPlay=play;duration=project==null?0:TransitionTiming.lengthMs(project,settings);volume=settings.masterGain;if(hasLayers())speed=1;
        if(project==null||project.clips.isEmpty()){stopPlayback();return;}reload();
    }
    public void setEdits(EffectSpec spec){
        EffectSpec next=(spec==null?new EffectSpec():spec.copy()).normalize();if(settings.equals(next))return;
        int position=getCurrentPosition();boolean play=isPlaying();settings=next;volume=next.masterGain;if(hasLayers())speed=1;
        pendingPosition=position;wantPlay=play;reload();
    }
    public void setMuted(boolean value){if(muted==value)return;int position=getCurrentPosition();boolean play=isPlaying();muted=value;pendingPosition=position;wantPlay=play;reload();}
    /** Anchor entry/exit transitions and animation to the selected range of an untrimmed source. */
    public void setEffectRange(int startMs,int endMs){
        int start=Math.max(0,startMs),end=endMs;
        if(duration>0){start=Math.min(start,Math.max(0,duration-1));end=Math.min(end,duration);}
        if(end<=start)end=-1;
        if(effectStart==start&&effectEnd==end)return;
        effectStart=start;effectEnd=end;
        boolean temporal=!settings.transition.equals("none")||settings.zoomStart!=settings.zoomEnd||settings.rotationStart!=settings.rotationEnd;
        if(source==null||!temporal)return;
        int position=getCurrentPosition();boolean play=isPlaying();pendingPosition=position;wantPlay=play;reload();
    }
    public int getDuration(){
        if(player!=null)try{long value=player.getDuration();if(value!=C.TIME_UNSET&&value>=0)duration=(int)Math.min(Integer.MAX_VALUE,Math.round(value*speed));}catch(RuntimeException ignored){}
        return Math.max(0,duration);
    }
    public int getCurrentPosition(){
        if(player!=null&&ready)try{pendingPosition=(int)Math.min(Integer.MAX_VALUE,Math.max(0,Math.round(player.getCurrentPosition()*speed)));}catch(RuntimeException ignored){}
        return Math.max(0,pendingPosition);
    }
    public boolean isPlaying(){try{return player!=null&&ready&&player.isPlaying();}catch(RuntimeException ignored){return false;}}
    public void start(){
        if(released)return;wantPlay=true;
        if(player!=null)try{if(getDuration()>0&&getCurrentPosition()>=getDuration())seekTo(0);player.play();}catch(RuntimeException e){fail(e,generation);}
    }
    public void pause(){wantPlay=false;if(player!=null)try{player.pause();}catch(RuntimeException ignored){}}
    public void seekTo(int position){
        pendingPosition=Math.max(0,duration>0?Math.min(position,duration):position);
        if(player!=null)try{if(composition!=null)MediaPipeline.seekAudio(composition,pendingPosition*1000L);player.seekTo(Math.max(0,Math.round(pendingPosition/speed)));}catch(RuntimeException e){fail(e,generation);}
    }
    public void seekTo(long position,int ignoredMode){seekTo((int)Math.min(Integer.MAX_VALUE,Math.max(0,position)));}
    public void setVolume(float left,float right){volume=Math.max(0,Math.min(1,(left+right)/2));if(player!=null)try{player.setVolume(volume);}catch(RuntimeException ignored){}}
    public void setResizeMode(int mode){if(mode>=0&&mode<=4)view.setResizeMode(mode);}
    public float getPlaybackSpeed(){return speed;}
    public boolean setPlaybackSpeed(float rate){
        if(!Float.isFinite(rate)||rate<.25f||rate>4f){errorMessage="Допустимая скорость предпросмотра: 0,25×–4×.";return false;}
        if(rate!=1&&hasLayers()){errorMessage="Со слоями и переходами между фрагментами предпросмотр доступен на скорости 1×.";return false;}
        if(speed==rate)return true;int position=getCurrentPosition();boolean play=isPlaying();speed=rate;pendingPosition=position;wantPlay=play;reload();return true;
    }
    private boolean hasLayers(){return !settings.audioUri.isEmpty()||!settings.overlayUri.isEmpty()||(sequence!=null&&sequence.clips.size()>1&&!settings.transition.equals("none"));}
    /** Clear the source and release codecs. The view remains reusable for a new project. */
    public void stopPlayback(){
        generation++;releasePlayer();source=null;sequence=null;ready=false;wantPlay=false;duration=0;pendingPosition=0;effectStart=0;effectEnd=-1;speed=1;
        if(worker!=null){worker.shutdownNow();worker=null;}
    }
    /** Release permanently when the owning Activity is destroyed. */
    public void release(){if(released)return;stopPlayback();released=true;preparedListener=null;completionListener=null;errorListener=null;}
    private void reload(){
        if(released||(source==null&&(sequence==null||sequence.clips.isEmpty())))return;
        final long ticket=++generation;final Uri selected=source;final Sequence project=sequence;
        final EffectSpec spec=settings.copy();spec.masterGain=1;final boolean mute=muted;final float rate=speed;final int from=effectStart,to=effectEnd;
        releasePlayer();ready=false;preparedNotified=false;errorMessage="";
        if(worker==null||worker.isShutdown())worker=Executors.newSingleThreadExecutor();
        worker.execute(()->{
            try{
                Composition composition=selected!=null?MediaPipeline.single(application,selected,0,-1,spec,mute,rate,from,to):MediaPipeline.preview(application,project,spec,mute,rate);
                main.post(()->{if(!released&&ticket==generation)install(composition,ticket);});
            }catch(Exception error){main.post(()->fail(error,ticket));}
        });
    }
    private void install(Composition composition,long ticket){
        try{
            CompositionPlayer.Builder builder=new CompositionPlayer.Builder(application).setLooper(Looper.getMainLooper());
            if(composition.sequences.size()>1)builder.setVideoGraphFactory(new MultipleInputVideoGraph.Factory());
            CompositionPlayer current=builder.build();player=current;this.composition=composition;view.setPlayer(current);
            current.addListener(new Player.Listener(){
                @Override public void onPlaybackStateChanged(int state){
                    if(ticket!=generation||player!=current||released)return;
                    if(state==Player.STATE_READY){
                        ready=true;getDuration();
                        if(!preparedNotified){preparedNotified=true;seekTo(pendingPosition);if(preparedListener!=null)preparedListener.accept(EditorPreview.this);}
                        if(ticket!=generation||player!=current||released)return;
                        if(wantPlay)current.play();
                    }else if(state==Player.STATE_ENDED){ready=true;pendingPosition=getDuration();wantPlay=false;if(completionListener!=null)completionListener.accept(EditorPreview.this);}
                }
                @Override public void onPlayerError(PlaybackException error){fail(error,ticket);}
            });
            current.setComposition(composition);current.setVolume(volume);current.prepare();
            if(pendingPosition>0)current.seekTo(Math.round(pendingPosition/speed));if(wantPlay)current.play();
        }catch(RuntimeException error){fail(error,ticket);}
    }
    private void releasePlayer(){CompositionPlayer old=player;player=null;composition=null;view.setPlayer(null);if(old!=null)try{old.release();}catch(RuntimeException ignored){}}
    private void fail(Throwable error,long ticket){
        if(released||ticket!=generation)return;ready=false;wantPlay=false;
        String detail=error.getMessage();errorMessage="Не удалось открыть предпросмотр. "+(detail==null?"Импортируйте исправный локальный файл повторно.":detail);
        releasePlayer();if(errorListener!=null)errorListener.onError(this,error instanceof PlaybackException?((PlaybackException)error).errorCode:-1,0);
    }
}
