package com.personal.sonyvegasvideo;

import android.content.ContentValues;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import java.io.IOException;
import java.io.FileInputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Conservative MP4 remux concatenation. Rejects differing codec configuration before creating output. */
public final class SequenceExporter {
    private static final class Part {Sequence.Clip clip;MediaFormat video,audio;long in,out;int videoTrack,audioTrack,rotation;}
    private SequenceExporter(){}
    private static void cancel(AtomicBoolean token){if(token.get()||Thread.currentThread().isInterrupted())throw new CancellationException();}
    public static ClipExporter.Result export(Context context,Sequence sequence,boolean mute,AtomicBoolean token,ClipExporter.Progress progress)throws Exception{
        if(sequence.clips.isEmpty())throw new IOException("Проект пуст.");
        List<Part> parts=new ArrayList<>();long total=0;
        for(Sequence.Clip clip:sequence.clips){
            cancel(token);Part part=inspect(context,clip);if(!parts.isEmpty())compatible(parts.get(0),part,mute);parts.add(part);total+=part.out-part.in;
        }
        cancel(token);Part first=parts.get(0);Uri output=null;boolean complete=false;
        try{
            ContentValues values=new ContentValues();values.put(MediaStore.Video.Media.DISPLAY_NAME,"VegasEdit_"+System.currentTimeMillis()+".mp4");values.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");values.put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/Sony Vegas Video");values.put(MediaStore.Video.Media.IS_PENDING,1);
            output=context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values);if(output==null)throw new IOException("Не удалось создать MP4.");
            try(ParcelFileDescriptor descriptor=context.getContentResolver().openFileDescriptor(output,"rw")){
                if(descriptor==null)throw new IOException("Не удалось открыть выходной файл.");
                MediaMuxer muxer=new MediaMuxer(descriptor.getFileDescriptor(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                try{
                    int videoOut=muxer.addTrack(first.video),audioOut=!mute&&first.audio!=null?muxer.addTrack(first.audio):-1;
                    if(first.rotation==90||first.rotation==180||first.rotation==270)muxer.setOrientationHint(first.rotation);
                    muxer.start();ByteBuffer buffer=ByteBuffer.allocateDirect(1024*1024);MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();long offset=0;int lastProgress=-1;
                    for(Part part:parts){
                        cancel(token);MediaExtractor extractor=new MediaExtractor();int frames=0;
                        try{
                            extractor.setDataSource(context,Uri.parse(part.clip.source),null);extractor.selectTrack(part.videoTrack);extractor.seekTo(part.in,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
                            if(extractor.getSampleTime()!=part.in)throw new IOException("Источник изменился после импорта. Откройте файл повторно.");
                            if(audioOut>=0)extractor.selectTrack(part.audioTrack);
                            while(extractor.getSampleTrackIndex()>=0){
                                cancel(token);int track=extractor.getSampleTrackIndex();long time=extractor.getSampleTime();
                                if(time>=part.out){extractor.unselectTrack(track);continue;}
                                if(time>=part.in){
                                    int flags=extractor.getSampleFlags();if((flags&(MediaExtractor.SAMPLE_FLAG_ENCRYPTED|MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME))!=0)throw new IOException("Защищённые или неполные кадры не поддерживаются.");
                                    long needed=extractor.getSampleSize();if(needed<0)break;if(needed>64*1024*1024)throw new IOException("Сжатый кадр превышает 64 МБ.");if(needed>buffer.capacity())buffer=ByteBuffer.allocateDirect((int)needed);buffer.clear();
                                    int size=extractor.readSampleData(buffer,0);if(size<0)break;
                                    info.set(0,size,offset+time-part.in,(flags&MediaExtractor.SAMPLE_FLAG_SYNC)!=0?MediaCodec.BUFFER_FLAG_KEY_FRAME:0);
                                    muxer.writeSampleData(track==part.videoTrack?videoOut:audioOut,buffer,info);if(track==part.videoTrack)frames++;
                                    int value=ClipMath.progress(offset+time-part.in,0,total);if(value>lastProgress){progress.update(value);lastProgress=value;}
                                }
                                if(!extractor.advance())break;
                            }
                        }finally{extractor.release();}
                        if(frames==0)throw new IOException("В одном из фрагментов нет видеокадров.");offset+=part.out-part.in;
                    }
                    cancel(token);muxer.stop();
                }finally{muxer.release();}
            }
            cancel(token);ContentValues published=new ContentValues();published.put(MediaStore.Video.Media.IS_PENDING,0);
            if(context.getContentResolver().update(output,published,null,null)!=1)throw new IOException("Не удалось опубликовать результат.");
            complete=true;progress.update(100);return new ClipExporter.Result(output,0,total);
        }finally{if(!complete&&output!=null)context.getContentResolver().delete(output,null,null);}
    }
    private static Part inspect(Context context,Sequence.Clip clip)throws IOException{
        Part p=new Part();p.clip=clip;p.videoTrack=p.audioTrack=-1;MediaExtractor e=new MediaExtractor();
        try{
            e.setDataSource(context,Uri.parse(clip.source),null);
            for(int i=0;i<e.getTrackCount();i++){MediaFormat f=e.getTrackFormat(i);String mime=f.getString(MediaFormat.KEY_MIME);if(mime==null)continue;
                if(p.video==null&&mime.startsWith("video/")){p.video=f;p.videoTrack=i;}if(p.audio==null&&mime.startsWith("audio/")){p.audio=f;p.audioTrack=i;}}
            if(p.video==null)throw new IOException("Нет видео: "+clip.name);
            p.rotation=p.video.containsKey(MediaFormat.KEY_ROTATION)?p.video.getInteger(MediaFormat.KEY_ROTATION):0;
            long sourceDuration=p.video.containsKey(MediaFormat.KEY_DURATION)?p.video.getLong(MediaFormat.KEY_DURATION):clip.outMs*1000L;
            // Android track metadata can include padding excluded by the MP4 movie timeline.
            long movieDuration=movieDuration(context,Uri.parse(clip.source));
            if(movieDuration>0)sourceDuration=Math.min(sourceDuration,movieDuration);
            if(sourceDuration<=0||clip.inMs*1000L>=sourceDuration||clip.outMs*1000L>sourceDuration+500_000L)throw new IOException("Границы выходят за длительность исходника: "+clip.name);
            long requestedEnd=Math.min(clip.outMs*1000L,sourceDuration);
            e.selectTrack(p.videoTrack);e.seekTo(clip.inMs*1000L,MediaExtractor.SEEK_TO_CLOSEST_SYNC);
            long closeIn=e.getSampleTime();
            if(closeIn<0||Math.abs(closeIn-clip.inMs*1000L)>1000)e.seekTo(clip.inMs*1000L,MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            p.in=e.getSampleTime();
            if(p.in<0||p.in>=clip.outMs*1000L||(e.getSampleFlags()&MediaExtractor.SAMPLE_FLAG_SYNC)==0)throw new IOException("Нет ключевого кадра: "+clip.name);
            e.seekTo(requestedEnd,MediaExtractor.SEEK_TO_CLOSEST_SYNC);long closeOut=e.getSampleTime();
            if(closeOut<0||Math.abs(closeOut-requestedEnd)>1000)e.seekTo(requestedEnd,MediaExtractor.SEEK_TO_NEXT_SYNC);
            long next=e.getSampleTime();
            p.out=next>=requestedEnd-1000?Math.min(next,sourceDuration):sourceDuration;
            if(p.out<=p.in)throw new IOException("Пустой фрагмент: "+clip.name);return p;
        }finally{e.release();}
    }
    private static long movieDuration(Context context,Uri source){
        try(AssetFileDescriptor asset=context.getContentResolver().openAssetFileDescriptor(source,"r")){
            if(asset==null)return -1;
            try(FileInputStream stream=asset.createInputStream()){
                return Mp4Duration.read(stream.getChannel(),asset.getStartOffset(),asset.getDeclaredLength());
            }
        }catch(IOException|RuntimeException unavailable){return -1;}
    }
    private static void compatible(Part a,Part b,boolean mute)throws IOException{
        if(a.rotation!=b.rotation||!same(a.video,b.video,new String[]{MediaFormat.KEY_MIME,MediaFormat.KEY_WIDTH,MediaFormat.KEY_HEIGHT,"csd-0","csd-1","csd-2","color-standard","color-range","color-transfer","hdr-static-info"}))
            throw new IOException("Для склейки без перекодирования нужны одинаковые кодек, разрешение, поворот и параметры кодека. Несовместим: "+b.clip.name);
        if(!mute&&((a.audio==null)!=(b.audio==null)||a.audio!=null&&!same(a.audio,b.audio,new String[]{MediaFormat.KEY_MIME,MediaFormat.KEY_SAMPLE_RATE,MediaFormat.KEY_CHANNEL_COUNT,"csd-0","csd-1"})))
            throw new IOException("Аудиодорожки несовместимы. Включите Mute для экспорта без звука или выберите совместимые файлы.");
    }
    private static boolean same(MediaFormat a,MediaFormat b,String[] keys){
        for(String key:keys){if(a.containsKey(key)!=b.containsKey(key))return false;if(!a.containsKey(key))continue;
            if(key.startsWith("csd-")||key.equals("hdr-static-info")){ByteBuffer x=a.getByteBuffer(key),y=b.getByteBuffer(key);if(x==null?y!=null:y==null||!x.duplicate().equals(y.duplicate()))return false;}
            else if(key.equals(MediaFormat.KEY_MIME)){if(!a.getString(key).equals(b.getString(key)))return false;}
            else if(a.getInteger(key)!=b.getInteger(key))return false;
        }return true;
    }
    public static int nearestCut(Context context,Sequence.Clip clip,int sourceMs)throws IOException{
        MediaExtractor e=new MediaExtractor();try{e.setDataSource(context,Uri.parse(clip.source),null);
            for(int i=0;i<e.getTrackCount();i++){String mime=e.getTrackFormat(i).getString(MediaFormat.KEY_MIME);if(mime!=null&&mime.startsWith("video/")){e.selectTrack(i);e.seekTo(sourceMs*1000L,MediaExtractor.SEEK_TO_CLOSEST_SYNC);long time=e.getSampleTime();if(time<0)break;return (int)(time/1000);}}
            throw new IOException("Нет ключевых кадров.");
        }finally{e.release();}
    }
}
