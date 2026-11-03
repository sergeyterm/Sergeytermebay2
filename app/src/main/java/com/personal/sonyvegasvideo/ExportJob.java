package com.personal.sonyvegasvideo;

import android.content.ContentValues;
import android.content.ContentUris;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import androidx.media3.common.MimeTypes;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-scoped exports; Activities observe state without being retained by a job. */
public final class ExportJob {
    public interface Listener {void changed();}
    private interface CompositionFactory {Composition create()throws Exception;}
    private static final ExportJob INSTANCE=new ExportJob();
    public static ExportJob get(){return INSTANCE;}
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Set<Listener> listeners=new LinkedHashSet<>();
    private AtomicBoolean cancelled=new AtomicBoolean();
    private Transformer transformer;
    private Context application;
    private File temporary;
    private long epoch;
    private Runnable progressPoll;
    public boolean running,generated;
    public int percent;
    public String message="";
    public ClipExporter.Result result;
    private ExportJob() { }
    public void attach(Listener listener){listeners.add(listener);listener.changed();}
    public void detach(Listener listener){listeners.remove(listener);}
    private void notifyChanged(){for(Listener listener:new ArrayList<>(listeners))listener.changed();}

    public void cancel(){
        if(!running)return;cancelled.set(true);message="Отменяю экспорт…";notifyChanged();
        if(transformer!=null){
            try{transformer.cancel();}catch(RuntimeException ignored){}transformer=null;stopPolling();
            final long ticket=epoch;final File path=temporary;temporary=null;
            worker.execute(()->{delete(path);finish(ticket,null,cancelMessage());});
        }
    }
    public void start(Context owner,Uri source,long startUs,long endUs,boolean mute){startRemux(owner,source,startUs,endUs,mute,null);}
    public void startSequence(Context owner,Sequence sequence,boolean mute){startRemux(owner,null,0,0,mute,sequence);}
    public void startEdited(Context owner,Sequence sequence,EffectSpec effects,boolean mute){
        if(running)return;Context context=owner.getApplicationContext();
        EffectSpec settings=(effects==null?new EffectSpec():effects.copy()).normalize();
        startTransform(context,()->MediaPipeline.composition(context,sequence,settings,mute),sequence==null?0:TransitionTiming.lengthMs(sequence,settings)*1000L,false);
    }
    public void startGenerated(Context owner,Uri image,int durationMs){
        if(running)return;Context context=owner.getApplicationContext();
        startTransform(context,()->MediaPipeline.generated(image,durationMs),durationMs*1000L,true);
    }
    private long begin(Context context,boolean generator){
        epoch++;application=context.getApplicationContext();cancelled=new AtomicBoolean();
        running=true;generated=generator;percent=0;result=null;
        message=generator?"Создаю видеофон…":"Сохраняю MP4…";notifyChanged();return epoch;
    }
    private void startRemux(Context owner,Uri source,long startUs,long endUs,boolean mute,Sequence sequence){
        if(running)return;Context context=owner.getApplicationContext();long ticket=begin(context,false);AtomicBoolean token=cancelled;
        worker.execute(()->{
            try{
                ClipExporter.Progress progress=value->main.post(()->{if(ticket==epoch&&running&&!token.get()){percent=value;notifyChanged();}});
                ClipExporter.Result output=sequence==null?ClipExporter.export(context,source,startUs,endUs,mute,token,progress):SequenceExporter.export(context,sequence,mute,token,progress);
                finish(ticket,output,null);
            }catch(Exception error){finish(ticket,null,errorMessage(error));}
        });
    }
    private void startTransform(Context context,CompositionFactory factory,long durationUs,boolean generator){
        long ticket=begin(context,generator);AtomicBoolean token=cancelled;
        worker.execute(()->{
            File file=null;
            try{
                checkCancelled(token);Composition composition=factory.create();checkCancelled(token);
                File folder=new File(context.getCacheDir(),"vegas_exports");if(!folder.exists()&&!folder.mkdirs())throw new IOException("Недостаточно места для временного видео.");
                file=File.createTempFile("render-",".mp4",folder);if(!file.delete())throw new IOException("Не удалось подготовить временный файл.");
                File path=file;
                main.post(()->{
                    if(ticket!=epoch||!running||token.get()){delete(path);finish(ticket,null,cancelMessage());return;}
                    temporary=path;
                    try{
                        transformer=new Transformer.Builder(context).setLooper(Looper.getMainLooper())
                                .setMuxerFactory(new TimelineMuxerFactory(durationUs))
                                .setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC).setPortraitEncodingEnabled(true)
                                .addListener(new Transformer.Listener(){
                                    @Override public void onCompleted(Composition completed,ExportResult exportResult){
                                        if(ticket!=epoch||!running)return;transformer=null;temporary=null;stopPolling();percent=95;message="Публикую готовое видео…";notifyChanged();
                                        worker.execute(()->{
                                            try{ClipExporter.Result output=publish(context,path,durationUs,token);finish(ticket,output,null);}
                                            catch(Exception error){finish(ticket,null,errorMessage(error));}
                                            finally{delete(path);}
                                        });
                                    }
                                    @Override public void onError(Composition failed,ExportResult exportResult,ExportException error){
                                        if(ticket!=epoch||!running)return;transformer=null;temporary=null;stopPolling();
                                        worker.execute(()->{delete(path);finish(ticket,null,errorMessage(error));});
                                    }
                                }).build();
                        transformer.start(composition,path.getAbsolutePath());pollProgress(ticket);
                    }catch(RuntimeException error){
                        if(transformer!=null)try{transformer.cancel();}catch(RuntimeException ignored){}
                        transformer=null;temporary=null;delete(path);finish(ticket,null,errorMessage(error));
                    }
                });
            }catch(Exception error){delete(file);finish(ticket,null,errorMessage(error));}
        });
    }
    private void pollProgress(long ticket){
        stopPolling();progressPoll=new Runnable(){@Override public void run(){
            if(ticket!=epoch||!running||transformer==null)return;
            try{ProgressHolder holder=new ProgressHolder();if(transformer.getProgress(holder)==Transformer.PROGRESS_STATE_AVAILABLE){percent=Math.min(94,Math.max(percent,holder.progress*94/100));notifyChanged();}}catch(RuntimeException ignored){}
            main.postDelayed(this,250);
        }};main.post(progressPoll);
    }
    private void stopPolling(){if(progressPoll!=null)main.removeCallbacks(progressPoll);progressPoll=null;}
    private ClipExporter.Result publish(Context context,File file,long durationUs,AtomicBoolean token)throws Exception {
        checkCancelled(token);if(!file.isFile()||file.length()<32)throw new IOException("Кодировщик не создал видео. Попробуйте другой источник.");
        // A completion callback is not enough: reject a malformed/empty MP4 before
        // creating a public item, then verify the URI after MediaStore publishes it.
        validateMovie(context,Uri.fromFile(file),file.length());checkCancelled(token);
        Uri collection=MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String name="VegasVideo_"+System.currentTimeMillis()+"_"+UUID.randomUUID().toString().substring(0,8)+".mp4";
        String relative="Movies/Sony Vegas Video/";
        Uri uri=null;boolean complete=false;
        try{
            ContentValues values=new ContentValues();values.put(MediaStore.Video.Media.DISPLAY_NAME,name);values.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");values.put(MediaStore.Video.Media.RELATIVE_PATH,relative);values.put(MediaStore.Video.Media.IS_PENDING,1);
            uri=context.getContentResolver().insert(collection,values);if(uri==null)throw new IOException("Не удалось создать видео в Галерее.");
            try(FileInputStream input=new FileInputStream(file);OutputStream output=context.getContentResolver().openOutputStream(uri,"w")){
                if(output==null)throw new IOException("Не удалось открыть выходной файл.");byte[] buffer=new byte[256*1024];int size;
                while((size=input.read(buffer))!=-1){checkCancelled(token);output.write(buffer,0,size);}output.flush();
            }
            checkCancelled(token);ContentValues ready=new ContentValues();ready.put(MediaStore.Video.Media.IS_PENDING,0);
            if(context.getContentResolver().update(uri,ready,null,null)!=1)throw new IOException("Не удалось опубликовать видео.");
            uri=awaitPublishedMovie(context,collection,uri,name,relative,file.length(),token);
            checkCancelled(token);complete=true;return new ClipExporter.Result(uri,0,durationUs);
        }finally{if(!complete){safeDelete(context,uri);deleteNamedPublication(context,collection,name,relative);}}
    }
    private static Uri awaitPublishedMovie(Context context,Uri collection,Uri initial,String name,String relative,long bytes,AtomicBoolean token)throws Exception {
        Uri candidate=initial;int stable=0;IOException last=null;
        for(int attempt=0;attempt<12;attempt++){
            checkCancelled(token);
            try{
                validateMovie(context,candidate,bytes);
                // Media scanners may still reconcile the pending-file rename. Two
                // successful reads apart keep us from exposing a transient old row.
                if(++stable>=2)return candidate;
            }catch(IOException unavailable){
                last=unavailable;stable=0;
                Uri fresh=findNamedPublication(context,collection,name,relative);
                if(fresh!=null)candidate=fresh;
            }
            try{Thread.sleep(100);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new CancellationException();}
        }
        checkCancelled(token);throw new IOException("Галерея ещё не предоставила доступ к готовому видео. Повторите сохранение.",last);
    }
    private static Uri findNamedPublication(Context context,Uri collection,String name,String relative){
        try(Cursor cursor=context.getContentResolver().query(collection,new String[]{MediaStore.Video.Media._ID},
                MediaStore.Video.Media.DISPLAY_NAME+" = ? AND "+MediaStore.Video.Media.RELATIVE_PATH+" = ?",new String[]{name,relative},MediaStore.Video.Media._ID+" DESC")){
            return cursor!=null&&cursor.moveToFirst()?ContentUris.withAppendedId(collection,cursor.getLong(0)):null;
        }catch(RuntimeException unavailable){return null;}
    }
    private static void deleteNamedPublication(Context context,Uri collection,String name,String relative){
        // The randomized name belongs to this job, so cleanup never selects a user's source.
        try{context.getContentResolver().delete(collection,MediaStore.Video.Media.DISPLAY_NAME+" = ? AND "+MediaStore.Video.Media.RELATIVE_PATH+" = ?",new String[]{name,relative});}
        catch(RuntimeException ignored){}
    }
    private static void safeDelete(Context context,Uri uri){if(uri!=null)try{context.getContentResolver().delete(uri,null,null);}catch(RuntimeException ignored){}}
    private static void validateMovie(Context context,Uri uri,long expectedBytes)throws IOException {
        MediaExtractor extractor=new MediaExtractor();MediaMetadataRetriever reader=new MediaMetadataRetriever();
        try(AssetFileDescriptor descriptor=context.getContentResolver().openAssetFileDescriptor(uri,"r")){
            if(descriptor==null||descriptor.getParcelFileDescriptor().getStatSize()<expectedBytes)throw new IOException("Готовое видео ещё недоступно для чтения.");
            long length=descriptor.getDeclaredLength();
            if(length>=0){extractor.setDataSource(descriptor.getFileDescriptor(),descriptor.getStartOffset(),length);reader.setDataSource(descriptor.getFileDescriptor(),descriptor.getStartOffset(),length);}
            else{extractor.setDataSource(descriptor.getFileDescriptor());reader.setDataSource(descriptor.getFileDescriptor());}
            int video=-1;
            for(int i=0;i<extractor.getTrackCount();i++){
                MediaFormat format=extractor.getTrackFormat(i);String mime=format.getString(MediaFormat.KEY_MIME);
                if(mime!=null&&mime.startsWith("video/")){video=i;break;}
            }
            if(video<0)throw new IOException("Кодировщик не создал видеодорожку.");
            extractor.selectTrack(video);if(extractor.getSampleTime()<0||extractor.getSampleSize()<=0)throw new IOException("Кодировщик не создал видеокадры.");
            String duration=reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if(duration==null||Long.parseLong(duration)<=0)throw new IOException("Некорректная длительность готового MP4.");
        }catch(RuntimeException unavailable){throw new IOException("Не удалось проверить готовое видео.",unavailable);}
        finally{extractor.release();try{reader.release();}catch(Exception ignored){}}
    }
    private void finish(long ticket,ClipExporter.Result output,String failure){
        main.post(()->{
            if(ticket!=epoch||!running)return;
            if(cancelled.get()&&output!=null){
                Context context=application;
                worker.execute(()->{
                    try{context.getContentResolver().delete(output.uri,null,null);}catch(RuntimeException ignored){}
                    finish(ticket,null,cancelMessage());
                });
                return;
            }
            stopPolling();running=false;result=output;percent=output==null?percent:100;
            String finalFailure=cancelled.get()?cancelMessage():failure;
            message=finalFailure!=null?finalFailure:output==null?"Экспорт не завершён.":"Готово! Галерея → Фильмы → Sony Vegas Video\nДлительность: "+ClipMath.clockCode(output.endUs/1000);
            notifyChanged();
        });
    }
    private static String cancelMessage(){return "Экспорт отменён. Незавершённый файл удалён.";}
    private static String errorMessage(Throwable error){
        if(error instanceof CancellationException)return cancelMessage();
        Throwable cause=error;while(cause.getCause()!=null&&cause.getCause()!=cause)cause=cause.getCause();
        if(cause instanceof SecurityException||cause instanceof java.io.FileNotFoundException)return "Нет доступа к источнику или месту сохранения. Импортируйте файл повторно и проверьте свободное место.";
        String detail=error.getMessage();if(detail==null||detail.isEmpty())detail=cause.getMessage();
        return "Ошибка экспорта: "+(detail==null?error.getClass().getSimpleName():detail)+". Проверьте исходник и свободное место, затем повторите экспорт.";
    }
    private static void checkCancelled(AtomicBoolean token){if(token.get()||Thread.currentThread().isInterrupted())throw new CancellationException();}
    private static void delete(File file){if(file!=null&&file.exists())file.delete();}
}
