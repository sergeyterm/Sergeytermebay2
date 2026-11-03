package com.personal.sonyvegasvideo;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
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
    private PlayerView view;
    private ExecutorService worker=Executors.newSingleThreadExecutor();
    private CompositionPlayer player;
    private Composition composition;
    private Consumer<EditorPreview> preparedListener,completionListener;
    private ErrorListener errorListener;
    private EffectSpec settings=new EffectSpec();
    private Sequence sequence;
    private Uri source;
    private boolean muted,ready,wantPlay,released,suspended,preparedNotified,seekInFlight;
    private int queuedSeek=-1;
    private Runnable seekReload;
    private int duration,pendingPosition,effectStart,effectEnd=-1;
    private volatile long generation;
    private long seekSerial;
    private volatile long firstPresentedUs=-1,lastPresentedUs=-1;
    private float volume=1,speed=1,canvasAspect;
    private int resizeMode=AspectRatioFrameLayout.RESIZE_MODE_FIT;
    private String errorMessage="";

    public EditorPreview(Context context){
        super(context);application=context.getApplicationContext();
        setBackgroundColor(0xff000000);createPlayerView();
        setContentDescription("Предпросмотр видео с эффектами");
    }
    private void createPlayerView(){
        view=new PlayerView(getContext());view.setUseController(false);view.setResizeMode(resizeMode);
        view.setKeepContentOnPlayerReset(false);addView(view,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
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
        if(player!=null&&ready&&!seekInFlight&&queuedSeek<0)try{pendingPosition=(int)Math.min(Integer.MAX_VALUE,Math.max(0,Math.round(player.getCurrentPosition()*speed)));}catch(RuntimeException ignored){}
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
        queuedSeek=pendingPosition;drainSeek();
    }
    private void drainSeek(){
        if(player==null||!ready||seekInFlight||queuedSeek<0)return;
        try{
            int state=player.getPlaybackState();if(state!=Player.STATE_READY&&state!=Player.STATE_ENDED)return;
            int target=queuedSeek;
            // Repeating the same seek from prepare/listener/UI unnecessarily flushes
            // CompositionPlayer's video graph. Serialize new seeks until it is ready.
            if(Math.abs(player.getCurrentPosition()*speed-target)<=2){queuedSeek=-1;cancelSeekReload();return;}
            if(Build.VERSION.SDK_INT<=30||(composition!=null&&composition.sequences.size()>1)){scheduleSeekReload();return;}
            queuedSeek=-1;
            seekInFlight=true;
            if(composition!=null)MediaPipeline.seekAudio(composition,target*1000L);
            player.seekTo(Math.max(0,Math.round(target/speed)));
            final long ticket=generation,request=++seekSerial;
            main.postDelayed(()->{
                if(!released&&ticket==generation&&request==seekSerial&&player!=null&&seekInFlight&&player.getPlaybackState()==Player.STATE_READY){seekInFlight=false;drainSeek();}
            },120);
        }catch(RuntimeException e){fail(e,generation);}
    }
    private void scheduleSeekReload(){
        cancelSeekReload();final long ticket=generation;
        seekReload=()->{
            seekReload=null;
            if(released||ticket!=generation||queuedSeek<0)return;
            pendingPosition=queuedSeek;
            // MultipleInputVideoGraph's partial flush retains compositor state;
            // older platform codecs can also stall a paused single-input flush.
            // Rebuild both cases with fresh processors at the requested position.
            // This is a seek within already prepared media, not another import.
            reload(false);
        };
        main.postDelayed(seekReload,100);
    }
    private void cancelSeekReload(){if(seekReload!=null)main.removeCallbacks(seekReload);seekReload=null;}
    public void seekTo(long position,int ignoredMode){seekTo((int)Math.min(Integer.MAX_VALUE,Math.max(0,position)));}
    public void setVolume(float left,float right){volume=Math.max(0,Math.min(1,(left+right)/2));if(player!=null)try{player.setVolume(volume);}catch(RuntimeException ignored){}}
    public void setResizeMode(int mode){if(mode>=0&&mode<=4){resizeMode=mode;if(view!=null)view.setResizeMode(mode);applyCanvasAspect();}}
    private void applyCanvasAspect(){
        // Supply the same rotated, normalized canvas used by export so Fit, Fill
        // and Zoom remain consistent across decoder and compositor stream changes.
        if(view==null)return;AspectRatioFrameLayout frame=view.findViewById(androidx.media3.ui.R.id.exo_content_frame);
        if(frame!=null&&canvasAspect>0)frame.setAspectRatio(canvasAspect);
    }
    public float getPlaybackSpeed(){return speed;}
    public boolean setPlaybackSpeed(float rate){
        if(!Float.isFinite(rate)||rate<.25f||rate>4f){errorMessage="Допустимая скорость предпросмотра: 0,25×–4×.";return false;}
        if(rate!=1&&hasLayers()){errorMessage="Со слоями и переходами между фрагментами предпросмотр доступен на скорости 1×.";return false;}
        if(speed==rate)return true;int position=getCurrentPosition();boolean play=isPlaying();speed=rate;pendingPosition=position;wantPlay=play;reload();return true;
    }
    private boolean hasLayers(){return !settings.audioUri.isEmpty()||!settings.overlayUri.isEmpty()||(sequence!=null&&sequence.clips.size()>1&&!settings.transition.equals("none"));}
    /** Release hidden video/audio resources while retaining the editable project. */
    public void suspend(){
        if(released||suspended)return;
        pendingPosition=getCurrentPosition();wantPlay=false;suspended=true;generation++;
        releasePlayer();ready=false;
        if(worker!=null){worker.shutdownNow();worker=null;}
    }
    /** Recreate the visible preview at its saved cursor, without restarting playback. */
    public void resume(){
        if(released||!suspended)return;
        boolean notifyPrepared=!preparedNotified;suspended=false;wantPlay=false;reload(notifyPrepared);
    }
    /** Clear the source and release codecs. The view remains reusable for a new project. */
    public void stopPlayback(){
        generation++;releasePlayer();source=null;sequence=null;ready=false;wantPlay=false;duration=0;pendingPosition=0;effectStart=0;effectEnd=-1;speed=1;
        if(worker!=null){worker.shutdownNow();worker=null;}
    }
    /** Release permanently when the owning Activity is destroyed. */
    public void release(){if(released)return;stopPlayback();released=true;preparedListener=null;completionListener=null;errorListener=null;}
    private void reload(){reload(true);}
    private void reload(boolean notifyPrepared){
        if(released||(source==null&&(sequence==null||sequence.clips.isEmpty())))return;
        if(suspended){if(notifyPrepared)preparedNotified=false;return;}
        final long ticket=++generation;final Uri selected=source;final Sequence project=sequence;
        final EffectSpec spec=settings.copy();spec.masterGain=1;final boolean mute=muted;final float rate=speed;final int from=effectStart,to=effectEnd;
        releasePlayer();ready=false;preparedNotified=!notifyPrepared;errorMessage="";
        if(worker==null||worker.isShutdown())worker=Executors.newSingleThreadExecutor();
        worker.execute(()->{
            try{
                int knownDuration=selected!=null?MediaPipeline.durationMs(application,selected):TransitionTiming.lengthMs(project,spec);
                Composition composition=selected!=null?MediaPipeline.single(application,selected,0,-1,spec,mute,rate,from,to):MediaPipeline.preview(application,project,spec,mute,rate);
                float aspect=MediaPipeline.previewAspectRatio(application,selected!=null?selected:Uri.parse(project.clips.get(0).source));
                main.post(()->{if(!released&&ticket==generation)install(composition,aspect,knownDuration,ticket);});
            }catch(Exception error){main.post(()->fail(error,ticket));}
        });
    }
    private void install(Composition composition,float aspect,int knownDuration,long ticket){
        try{
            CompositionPlayer.Builder builder=new CompositionPlayer.Builder(application)
                    .setLooper(Looper.getMainLooper()).setPerStreamMediaProgressionEnabled(true);
            if(Build.VERSION.SDK_INT<=30){
                builder.setVideoGraphFactory(new PreviewVideoGraphFactory(composition.sequences.size()>1))
                        .experimentalSetLateThresholdToDropInputUs(C.TIME_UNSET);
            }else if(composition.sequences.size()>1)builder.setVideoGraphFactory(new MultipleInputVideoGraph.Factory());
            if(view==null)createPlayerView();
            CompositionPlayer current=builder.build();player=current;this.composition=composition;canvasAspect=aspect;view.setPlayer(current);applyCanvasAspect();
            current.addListener(new Player.Listener(){
                @Override public void onPlaybackStateChanged(int state){
                    if(ticket!=generation||player!=current||released)return;
                    if(state==Player.STATE_READY){
                        ready=true;seekInFlight=false;getDuration();applyCanvasAspect();
                        if(!preparedNotified){preparedNotified=true;if(preparedListener!=null)preparedListener.accept(EditorPreview.this);}
                        if(ticket!=generation||player!=current||released)return;
                        drainSeek();
                        if(wantPlay)current.play();
                    }else if(state==Player.STATE_ENDED){ready=true;pendingPosition=getDuration();wantPlay=false;if(completionListener!=null)completionListener.accept(EditorPreview.this);}
                }
                @Override public void onPlayerError(PlaybackException error){fail(error,ticket);}
            });
            // Initialize every sequence at the restored position, before decoders
            // and the compositor prepare. No first-READY graph flush is needed.
            int initial=queuedSeek>=0?queuedSeek:pendingPosition;queuedSeek=-1;
            duration=Math.max(0,knownDuration);
            pendingPosition=Math.max(0,duration>0?Math.min(initial,duration-1):initial);
            MediaPipeline.seekAudio(composition,pendingPosition*1000L);
            final int requestedInitialMs=pendingPosition;final int[] presentedCount={0};
            firstPresentedUs=lastPresentedUs=-1;
            current.setVideoFrameMetadataListener((presentationTimeUs,releaseTimeNs,format,mediaFormat)->{
                if(ticket!=generation)return;
                if(presentedCount[0]==0)firstPresentedUs=presentationTimeUs;
                lastPresentedUs=presentationTimeUs;
                if(requestedInitialMs>0&&presentedCount[0]<4)Log.i("VegasFrames","Presented frame generation="+ticket+" requestedMs="+requestedInitialMs+" index="+presentedCount[0]+" ptsUs="+presentationTimeUs);
                presentedCount[0]++;
            });
            current.setComposition(composition,Math.max(0,Math.round(pendingPosition/speed)));current.setVolume(volume);
            current.prepare();
            if(wantPlay)current.play();
        }catch(RuntimeException error){fail(error,ticket);}
    }
    private void releasePlayer(){
        cancelSeekReload();
        CompositionPlayer old=player;player=null;composition=null;seekInFlight=false;queuedSeek=-1;
        // A timed-out codec release can still own the old EGL window. Detach the
        // SurfaceView so the next graph receives a new native window, never that
        // still-connected BufferQueue. Preserve the outer view and its listeners.
        PlayerView oldView=view;view=null;
        if(oldView!=null){oldView.setPlayer(null);removeView(oldView);}
        if(old!=null)try{old.release();}catch(RuntimeException error){Log.w("VegasPreview","Previous preview release failed",error);}
    }
    private void fail(Throwable error,long ticket){
        if(released||ticket!=generation)return;ready=false;wantPlay=false;preparedNotified=false;
        Log.e("VegasPreview","Composition preview failed (generation "+ticket+")",error);
        String detail=error.getMessage();errorMessage="Не удалось открыть предпросмотр. "+(detail==null?"Импортируйте исправный локальный файл повторно.":detail);
        releasePlayer();if(errorListener!=null)errorListener.onError(this,error instanceof PlaybackException?((PlaybackException)error).errorCode:-1,0);
    }
}
