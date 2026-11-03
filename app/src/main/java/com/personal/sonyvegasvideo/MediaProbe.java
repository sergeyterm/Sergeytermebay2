package com.personal.sonyvegasvideo;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Reads actual media thumbnails and decoded PCM peaks. No generated waveform or meter data. */
public final class MediaProbe {
    public static final class Info {
        public String name="Video", codec="", audioCodec="";
        public int width,height,durationMs,channels;
        public float fps;
        public boolean hasAudio;
        public Bitmap[] frames;
        public float[] peaks;
        public boolean waveformComplete;
    }
    public interface Listener {void loaded(Info info);}
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private AtomicBoolean token=new AtomicBoolean();
    public void stop(){token.set(true);worker.shutdownNow();}
    public void cancel(){token.set(true);}
    public void load(Context owner,Uri source,Listener listener){
        token.set(true);AtomicBoolean cancelled=new AtomicBoolean();token=cancelled;
        Context context=owner.getApplicationContext();
        worker.execute(()->{
            Info info=new Info();MediaMetadataRetriever reader=new MediaMetadataRetriever();
            try{
                try(Cursor c=context.getContentResolver().query(source,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){
                    if(c!=null&&c.moveToFirst())info.name=c.getString(0);
                }
                reader.setDataSource(context,source);
                info.width=number(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                info.height=number(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                info.durationMs=number(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                MediaExtractor extractor=new MediaExtractor();
                try{
                    extractor.setDataSource(context,source,null);
                    for(int i=0;i<extractor.getTrackCount();i++){
                        MediaFormat f=extractor.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);
                        if(mime==null)continue;
                        if(info.codec.isEmpty()&&mime.startsWith("video/")){
                            info.codec=mime.substring(6);
                            if(f.containsKey(MediaFormat.KEY_FRAME_RATE)){
                                try{info.fps=f.getInteger(MediaFormat.KEY_FRAME_RATE);}catch(ClassCastException ignored){info.fps=f.getFloat(MediaFormat.KEY_FRAME_RATE);}
                            }
                        }
                        if(!info.hasAudio&&mime.startsWith("audio/")){
                            info.hasAudio=true;info.audioCodec=mime.substring(6);
                            info.channels=f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)?f.getInteger(MediaFormat.KEY_CHANNEL_COUNT):1;
                        }
                    }
                }finally{extractor.release();}
                info.frames=new Bitmap[8];
                for(int i=0;i<info.frames.length&&!cancelled.get();i++){
                    try{info.frames[i]=reader.getScaledFrameAtTime((long)info.durationMs*1000*i/info.frames.length,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC,160,100);}catch(RuntimeException ignored){}
                }
            }catch(Exception ignored){}
            finally{try{reader.release();}catch(Exception ignored){}}
            if(cancelled.get())return;
            main.post(()->{if(!cancelled.get())listener.loaded(info);});
            if(info.hasAudio){
                Info decoded=copy(info);
                try{decodePeaks(context,source,decoded,cancelled);}catch(Exception ignored){}
                if(!cancelled.get())main.post(()->{if(!cancelled.get())listener.loaded(decoded);});
            }
        });
    }
    private static Info copy(Info old){Info n=new Info();n.name=old.name;n.codec=old.codec;n.audioCodec=old.audioCodec;
        n.width=old.width;n.height=old.height;n.durationMs=old.durationMs;n.channels=old.channels;n.fps=old.fps;n.hasAudio=old.hasAudio;n.frames=old.frames;return n;}
    private static int number(String value){try{return Integer.parseInt(value);}catch(Exception e){return 0;}}
    private static void decodePeaks(Context context,Uri source,Info info,AtomicBoolean cancelled)throws Exception{
        MediaExtractor extractor=new MediaExtractor();MediaCodec codec=null;
        try{
            extractor.setDataSource(context,source,null);int audio=-1;
            for(int i=0;i<extractor.getTrackCount();i++){
                String mime=extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if(mime!=null&&mime.startsWith("audio/")){audio=i;break;}
            }
            if(audio<0)return;
            extractor.selectTrack(audio);MediaFormat format=extractor.getTrackFormat(audio);
            String mime=format.getString(MediaFormat.KEY_MIME);
            codec=MediaCodec.createDecoderByType(mime);codec.configure(format,null,null,0);codec.start();
            float[] peaks=new float[1024];boolean inputDone=false,outputDone=false;
            MediaCodec.BufferInfo bufferInfo=new MediaCodec.BufferInfo();
            int sampleRate=format.getInteger(MediaFormat.KEY_SAMPLE_RATE),channels=Math.max(1,info.channels),encoding=AudioFormat.ENCODING_PCM_16BIT;
            long deadline=SystemClock.elapsedRealtime()+25000;
            while(!outputDone&&!cancelled.get()&&SystemClock.elapsedRealtime()<deadline){
                if(!inputDone){
                    int index=codec.dequeueInputBuffer(1000);
                    if(index>=0){
                        ByteBuffer input=codec.getInputBuffer(index);input.clear();int size=extractor.readSampleData(input,0);
                        if(size<0){codec.queueInputBuffer(index,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputDone=true;}
                        else{codec.queueInputBuffer(index,0,size,extractor.getSampleTime(),0);extractor.advance();}
                    }
                }
                int index=codec.dequeueOutputBuffer(bufferInfo,1000);
                if(index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                    MediaFormat out=codec.getOutputFormat();sampleRate=out.getInteger(MediaFormat.KEY_SAMPLE_RATE);channels=out.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    encoding=out.containsKey(MediaFormat.KEY_PCM_ENCODING)?out.getInteger(MediaFormat.KEY_PCM_ENCODING):AudioFormat.ENCODING_PCM_16BIT;
                    if(encoding!=AudioFormat.ENCODING_PCM_16BIT&&encoding!=AudioFormat.ENCODING_PCM_FLOAT)return;
                }else if(index>=0){
                    ByteBuffer output=codec.getOutputBuffer(index);
                    if(output!=null&&bufferInfo.size>0){
                        output=output.duplicate().order(ByteOrder.LITTLE_ENDIAN);
                        output.position(bufferInfo.offset);output.limit(bufferInfo.offset+bufferInfo.size);
                        int bytes=encoding==AudioFormat.ENCODING_PCM_FLOAT?4:2;
                        int frames=output.remaining()/Math.max(1,channels*bytes);
                        for(int f=0;f<frames;f++){
                            float peak=0;
                            for(int channel=0;channel<channels;channel++){
                                float sample=bytes==4?output.getFloat():output.getShort()/32768f;
                                if(Float.isFinite(sample))peak=Math.max(peak,Math.abs(sample));
                            }
                            long pts=bufferInfo.presentationTimeUs+(long)f*1_000_000/Math.max(1,sampleRate);
                            int bin=ClipMath.clamp((int)(pts*peaks.length/Math.max(1,(long)info.durationMs*1000)),0,peaks.length-1);
                            peaks[bin]=Math.max(peaks[bin],Math.min(1,peak));
                        }
                    }
                    outputDone=(bufferInfo.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;codec.releaseOutputBuffer(index,false);
                }
            }
            // Publish only a complete waveform; an interrupted analysis leaves a neutral event strip.
            if(outputDone&&!cancelled.get()){info.peaks=peaks;info.waveformComplete=true;}
        }finally{
            if(codec!=null){try{codec.stop();}catch(RuntimeException ignored){}codec.release();}
            extractor.release();
        }
    }
}
