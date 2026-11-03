package com.personal.sonyvegasvideo;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Pair;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.OverlaySettings;
import androidx.media3.common.VideoCompositorSettings;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.ChannelMixingAudioProcessor;
import androidx.media3.common.audio.ChannelMixingMatrix;
import androidx.media3.common.audio.SpeedProvider;
import androidx.media3.common.util.Size;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.StaticOverlaySettings;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Shared composition graph for preview and MP4 output. Call metadata methods off the UI thread. */
public final class MediaPipeline {
    private MediaPipeline() { }
    private static Uri previewGapImage;
    private static final class Source {long durationUs;boolean video,audio;int width,height;}

    public static int durationMs(Context context,Uri uri)throws IOException {
        Source source=inspect(context,uri,true,false);
        return (int)Math.min(Integer.MAX_VALUE,Math.max(1,source.durationUs/1000));
    }
    static float previewAspectRatio(Context context,Uri uri)throws IOException {
        Source source=inspect(context,uri,true,false);int[] size=outputSize(source.width,source.height);
        return (float)size[0]/size[1];
    }
    public static Composition composition(Context context,Sequence sequence,EffectSpec settings,boolean muted)throws IOException {
        return compose(context,sequence,settings,muted,1f);
    }
    public static Composition single(Context context,Uri uri,int startMs,int endMs,EffectSpec settings,boolean muted)throws IOException {
        return single(context,uri,startMs,endMs,settings,muted,1f);
    }
    static Composition single(Context context,Uri uri,int startMs,int endMs,EffectSpec settings,boolean muted,float speed)throws IOException {
        return single(context,uri,startMs,endMs,settings,muted,speed,0,-1);
    }
    static Composition single(Context context,Uri uri,int startMs,int endMs,EffectSpec settings,boolean muted,float speed,int effectStartMs,int effectEndMs)throws IOException {
        Source source=inspect(context,uri,true,false);
        int end=endMs<0?(int)Math.min(Integer.MAX_VALUE,source.durationUs/1000):endMs;
        return compose(context,new Sequence().append(new Sequence.Clip(uri.toString(),"Video",Math.max(0,startMs),end)),settings,muted,speed,effectStartMs*1000L,effectEndMs>effectStartMs?(effectEndMs-effectStartMs)*1000L:-1);
    }
    static Composition preview(Context context,Sequence sequence,EffectSpec settings,boolean muted,float speed)throws IOException {
        return compose(context,sequence,settings,muted,speed,0,-1,true);
    }

    private static Composition compose(Context context,Sequence sequence,EffectSpec settings,boolean muted,float speed)throws IOException {
        return compose(context,sequence,settings,muted,speed,0,-1);
    }
    private static Composition compose(Context context,Sequence sequence,EffectSpec settings,boolean muted,float speed,long effectOriginUs,long effectDurationUs)throws IOException {
        return compose(context,sequence,settings,muted,speed,effectOriginUs,effectDurationUs,false);
    }
    private static Composition compose(Context context,Sequence sequence,EffectSpec settings,boolean muted,float speed,long effectOriginUs,long effectDurationUs,boolean preview)throws IOException {
        if(sequence==null||sequence.clips.isEmpty())throw new IOException("Проект пуст. Импортируйте видео.");
        EffectSpec spec=(settings==null?new EffectSpec():settings.copy()).normalize();
        if(!Float.isFinite(speed)||speed<=0)throw new IOException("Некорректная скорость предпросмотра.");
        boolean crossClips=sequence.clips.size()>1&&!spec.transition.equals("none");
        if(speed!=1f&&(!spec.audioUri.isEmpty()||!spec.overlayUri.isEmpty()||crossClips))throw new IOException("Со слоями и переходами между фрагментами предпросмотр доступен на скорости 1×.");
        Map<String,Source> sources=new HashMap<>();boolean sourceAudio=false;Source first=null;
        for(Sequence.Clip clip:sequence.clips){
            Source input=sources.get(clip.source);
            if(input==null){input=inspect(context,Uri.parse(clip.source),true,false);sources.put(clip.source,input);}
            if(first==null)first=input;
            if(clip.inMs*1000L>=input.durationUs||clip.outMs*1000L>input.durationUs+500_000L)throw new IOException("Фрагмент выходит за длительность файла: "+clip.name);
            sourceAudio|=input.audio;
        }
        final int[] output=outputSize(first.width,first.height);
        final TransitionTiming timing=new TransitionTiming(sequence,spec);
        boolean hasMusic=!spec.audioUri.isEmpty(),pip=!spec.overlayUri.isEmpty();
        boolean forceAudio=(sourceAudio&&!muted)||hasMusic||speed!=1f;
        List<EditedMediaItemSequence> bases=new ArrayList<>();
        final long totalUs=timing.length*1000L;
        if(crossClips){
            for(int lane=0;lane<2;lane++){
                EditedMediaItemSequence.Builder events=new EditedMediaItemSequence.Builder();long positionUs=0;
                for(int i=lane;i<sequence.clips.size();i+=2){
                    Sequence.Clip clip=sequence.clips.get(i);Source input=sources.get(clip.source);
                    long startUs=timing.starts[i]*1000L,lengthUs=clip.length()*1000L;
                    long fadeInUs=i>0?timing.overlaps[i-1]*1000L:0,fadeOutUs=i<timing.overlaps.length?timing.overlaps[i]*1000L:0;
                    if(startUs>positionUs)addGap(context,events,startUs-positionUs,preview);
                    List<Effect> video=new ArrayList<>();video.add(Presentation.createForWidthAndHeight(output[0],output[1],Presentation.LAYOUT_SCALE_TO_FIT));
                    // Front/even clips fade in/out over the fully visible moving back/odd clip.
                    // Internal effects receive project timestamps after Media3 adds the event offset.
                    if(lane==0)video.add(VideoEffects.transitionMask(spec,lengthUs,fadeInUs,fadeOutUs,startUs));
                    List<AudioProcessor> audio=new ArrayList<>(audioProcessors(1f,true));
                    if(fadeInUs>0||fadeOutUs>0)audio.add(new AudioFadeProcessor(lengthUs,fadeInUs,fadeOutUs,startUs));
                    events.addItem(item(Uri.parse(clip.source),input.durationUs,clip.inMs*1000L,Math.min(clip.outMs*1000L,input.durationUs),muted,false,audio,video,1f));
                    positionUs=timing.ends[i]*1000L;
                }
                if(positionUs<totalUs)addGap(context,events,totalUs-positionUs,preview);
                bases.add(events.experimentalSetForceVideoTrack(true).experimentalSetForceAudioTrack(forceAudio).build());
            }
        }else{
            List<EditedMediaItem> videos=new ArrayList<>();
            for(Sequence.Clip clip:sequence.clips){
                Source input=sources.get(clip.source);long endUs=Math.min(clip.outMs*1000L,input.durationUs);
                videos.add(item(Uri.parse(clip.source),input.durationUs,clip.inMs*1000L,endUs,muted,false,audioProcessors(1f,true),
                        Collections.singletonList(Presentation.createForWidthAndHeight(output[0],output[1],Presentation.LAYOUT_SCALE_TO_FIT)),speed));
            }
            bases.add(new EditedMediaItemSequence.Builder(videos).experimentalSetForceAudioTrack(forceAudio).build());
        }
        List<EditedMediaItemSequence> sequences=new ArrayList<>();
        if(pip){
            Uri overlay=Uri.parse(spec.overlayUri);Source source=inspect(context,overlay,true,false);
            long count=(totalUs+source.durationUs-1)/source.durationUs;
            if(count>4096)throw new IOException("Видеослой слишком короткий для такого длинного проекта. Выберите более длинный ролик.");
            List<EditedMediaItem> repeated=new ArrayList<>();
            EffectSpec key=new EffectSpec();key.chromaKey=spec.chromaKey;key.chromaColor=spec.chromaColor;key.chromaThreshold=spec.chromaThreshold;
            for(long at=0;at<totalUs;){long length=Math.min(source.durationUs,totalUs-at);
                repeated.add(item(overlay,source.durationUs,0,length,true,false,Collections.emptyList(),VideoEffects.create(key,length,0),1f));at+=length;}
            // Media3 draws its primary stream on top. Explicit repetition also gives preview
            // a full-length primary timeline; primary looping is unsupported in Media3 1.8.
            sequences.add(new EditedMediaItemSequence.Builder(repeated).build());
        }
        sequences.addAll(bases);
        if(hasMusic){
            Uri audio=Uri.parse(spec.audioUri);Source source=inspect(context,audio,false,true);
            EditedMediaItem music=item(audio,source.durationUs,0,source.durationUs,false,true,audioProcessors(spec.audioGain,true),Collections.emptyList(),1f);
            sequences.add(new EditedMediaItemSequence.Builder(music).setIsLooping(true).build());
        }
        EffectSpec visual=spec.copy();if(pip)visual.chromaKey=false;if(crossClips)visual.transition="none";
        List<Effect> effects=new ArrayList<>(VideoEffects.create(visual,(long)((effectDurationUs>0?effectDurationUs:totalUs)/speed),(long)(effectOriginUs/speed)));
        effects.add(Presentation.createForWidthAndHeight(output[0],output[1],Presentation.LAYOUT_SCALE_TO_FIT));
        Composition.Builder builder=new Composition.Builder(sequences).setEffects(new Effects(audioProcessors(spec.masterGain,false,spec.audioPan),effects))
                .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL);
        if(pip||crossClips)builder.setVideoCompositorSettings(new VideoCompositorSettings(){
                    @Override public Size getOutputSize(List<Size> sizes){return new Size(output[0],output[1]);}
                    @Override public OverlaySettings getOverlaySettings(int inputId,long timeUs){
                        if(pip&&inputId==0)return new StaticOverlaySettings.Builder().setScale(spec.overlayScale,spec.overlayScale).setBackgroundFrameAnchor(spec.overlayX,spec.overlayY).build();
                        if(crossClips){
                            int lane=inputId-(pip?1:0);
                            if(lane==0||lane==1)return new StaticOverlaySettings.Builder().setAlphaScale(activeLane(timing,lane,timeUs)?1:0).build();
                        }
                        return new StaticOverlaySettings.Builder().build();
                    }
                });
        return builder.build();
    }

    private static void addGap(Context context,EditedMediaItemSequence.Builder sequence,long durationUs,boolean preview)throws IOException {
        if(!preview){sequence.addGap(durationUs);return;}
        // CompositionPlayer 1.8 turns addGap into audio silence only. An invisible
        // image stream also supplies the video clock needed by the compositor.
        Uri image;
        synchronized(MediaPipeline.class){
            if(previewGapImage==null)previewGapImage=GeneratedMedia.create(context,"solid","",android.graphics.Color.BLACK,64,64);
            image=previewGapImage;
        }
        MediaItem item=new MediaItem.Builder().setUri(image).setMimeType("image/png").setImageDurationMs(Math.max(1,(durationUs+999)/1000)).build();
        sequence.addItem(new EditedMediaItem.Builder(item).setDurationUs(durationUs).setFrameRate(30).build());
    }

    static Composition generated(Uri image,int durationMs){
        if(durationMs<250||durationMs>600_000)throw new IllegalArgumentException("Длительность фона: от 0,25 до 600 секунд.");
        MediaItem media=new MediaItem.Builder().setUri(image).setMimeType("image/png").setImageDurationMs(durationMs).build();
        EditedMediaItem item=new EditedMediaItem.Builder(media).setRemoveAudio(true).setFrameRate(24).setDurationUs(durationMs*1000L).build();
        return new Composition.Builder(new EditedMediaItemSequence.Builder(item).build())
                .setEffects(new Effects(Collections.emptyList(),Collections.singletonList(Presentation.createForWidthAndHeight(1280,720,Presentation.LAYOUT_SCALE_TO_FIT))))
                .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build();
    }
    private static EditedMediaItem item(Uri uri,long durationUs,long startUs,long endUs,boolean removeAudio,boolean removeVideo,List<AudioProcessor> audio,List<Effect> video,float speed){
        MediaItem media=new MediaItem.Builder().setUri(uri).setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder().setStartPositionUs(startUs).setEndPositionUs(endUs).build()).build();
        List<AudioProcessor> processors=new ArrayList<>(audio);List<Effect> visual=new ArrayList<>(video);
        if(speed!=1f){
            SpeedProvider provider=new SpeedProvider(){@Override public float getSpeed(long timeUs){return speed;}@Override public long getNextSpeedChangeTimeUs(long timeUs){return C.TIME_UNSET;}};
            Pair<AudioProcessor,Effect> pair=Effects.createExperimentalSpeedChangingEffect(provider);processors.add(pair.first);visual.add(pair.second);
        }
        return new EditedMediaItem.Builder(media).setDurationUs(durationUs).setRemoveAudio(removeAudio).setRemoveVideo(removeVideo).setEffects(new Effects(processors,visual)).build();
    }
    private static List<AudioProcessor> audioProcessors(float gain,boolean stereo){
        return audioProcessors(gain,stereo,0);
    }
    private static List<AudioProcessor> audioProcessors(float gain,boolean stereo,float pan){
        if(gain==1f&&!stereo&&pan==0)return Collections.emptyList();ChannelMixingAudioProcessor processor=new ChannelMixingAudioProcessor();
        for(int channels=1;channels<=8;channels++){
            int output=stereo||pan!=0?2:channels;float[] values=new float[channels*output];
            if(output==channels){for(int i=0;i<channels;i++)values[i*output+i]=gain;}
            else if(channels==1){values[0]=values[1]=gain;}
            else{
                for(int i=0;i<channels;i++){if(i<2)values[i*2+i]=1;else if(i==2||i==3){values[i*2]=values[i*2+1]=i==2?.7071f:.5f;}else values[i*2+(i%2)]=.7071f;}
                float left=0,right=0;for(int i=0;i<channels;i++){left+=values[i*2];right+=values[i*2+1];}float normal=Math.max(1f,Math.max(left,right));for(int i=0;i<values.length;i++)values[i]*=gain/normal;
            }
            if(pan!=0&&output>=2)for(int i=0;i<channels;i++){values[i*output]*=Math.min(1,1-pan);values[i*output+1]*=Math.min(1,1+pan);}
            processor.putChannelMixingMatrix(new ChannelMixingMatrix(channels,output,values));
        }
        return Collections.singletonList(processor);
    }
    private static boolean activeLane(TransitionTiming timing,int lane,long timeUs){
        for(int i=lane;i<timing.starts.length;i+=2)if(timeUs>=timing.starts[i]*1000L&&timeUs<timing.ends[i]*1000L)return true;
        return false;
    }
    /** Called before a preview seek; each processor starts the next flush at its event-relative time. */
    static void seekAudio(Composition composition,long projectTimeUs){
        for(EditedMediaItemSequence sequence:composition.sequences)for(EditedMediaItem item:sequence.editedMediaItems)
            for(AudioProcessor processor:item.effects.audioProcessors)if(processor instanceof AudioFadeProcessor)((AudioFadeProcessor)processor).seekProjectPosition(projectTimeUs);
    }
    private static Source inspect(Context context,Uri uri,boolean requireVideo,boolean requireAudio)throws IOException {
        String scheme=uri==null?null:uri.getScheme();
        if(!"content".equals(scheme)&&!"file".equals(scheme))throw new IOException("Выберите локальный файл через проводник Android.");
        MediaExtractor extractor=new MediaExtractor();Source result=new Source();
        try{
            extractor.setDataSource(context,uri,null);long videoDuration=0,audioDuration=0;
            for(int i=0;i<extractor.getTrackCount();i++){
                MediaFormat format=extractor.getTrackFormat(i);String mime=format.getString(MediaFormat.KEY_MIME);if(mime==null)continue;
                long duration=format.containsKey(MediaFormat.KEY_DURATION)?format.getLong(MediaFormat.KEY_DURATION):0;
                if(mime.startsWith("video/")&&!result.video){result.video=true;videoDuration=duration;result.width=format.getInteger(MediaFormat.KEY_WIDTH);result.height=format.getInteger(MediaFormat.KEY_HEIGHT);
                    int rotation=format.containsKey(MediaFormat.KEY_ROTATION)?format.getInteger(MediaFormat.KEY_ROTATION):0;if(rotation==90||rotation==270){int width=result.width;result.width=result.height;result.height=width;}}
                else if(mime.startsWith("audio/")){result.audio=true;audioDuration=Math.max(audioDuration,duration);}
            }
            if(requireVideo&&!result.video)throw new IOException("В выбранном файле нет видеодорожки.");
            if(requireAudio&&!result.audio)throw new IOException("В выбранном файле нет аудиодорожки.");
            result.durationUs=requireVideo?videoDuration:audioDuration;
            long movie=movieDuration(context,uri);if(movie>0)result.durationUs=result.durationUs>0?Math.min(result.durationUs,movie):movie;
            if(result.durationUs<=0||result.durationUs/1000>Integer.MAX_VALUE)throw new IOException("Недоступна или слишком велика длительность файла.");return result;
        }catch(SecurityException e){throw new IOException("Нет доступа к источнику. Импортируйте файл повторно через проводник.",e);}
        catch(RuntimeException e){throw new IOException("Не удалось прочитать медиа. Выберите исправный видео- или аудиофайл.",e);}
        finally{extractor.release();}
    }
    private static long movieDuration(Context context,Uri uri){
        try(AssetFileDescriptor asset=context.getContentResolver().openAssetFileDescriptor(uri,"r")){
            if(asset==null)return -1;try(FileInputStream stream=asset.createInputStream()){return Mp4Duration.read(stream.getChannel(),asset.getStartOffset(),asset.getDeclaredLength());}
        }catch(IOException|RuntimeException ignored){return -1;}
    }
    private static int[] outputSize(int width,int height){
        if(width<=0||height<=0)return new int[]{1280,720};double scale=Math.min(1,Math.min(1280d/Math.max(width,height),720d/Math.min(width,height)));
        return new int[]{Math.max(2,((int)(width*scale)/2)*2),Math.max(2,((int)(height*scale)/2)*2)};
    }
}
