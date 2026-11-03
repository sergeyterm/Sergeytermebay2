package com.personal.sonyvegasvideo;

import android.content.ContentValues;
import android.content.Context;
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
        startTransform(context,()->MediaPipeline.composition(context,sequence,settings,mute),sequence==null?0:sequence.length()*1000L,false);
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
        Uri uri=null;boolean complete=false;
        try{
            ContentValues values=new ContentValues();values.put(MediaStore.Video.Media.DISPLAY_NAME,"VegasVideo_"+System.currentTimeMillis()+".mp4");values.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");values.put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/Sony Vegas Video");values.put(MediaStore.Video.Media.IS_PENDING,1);
            uri=context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values);if(uri==null)throw new IOException("Не удалось создать видео в Галерее.");
            try(FileInputStream input=new FileInputStream(file);OutputStream output=context.getContentResolver().openOutputStream(uri,"w")){
                if(output==null)throw new IOException("Не удалось открыть выходной файл.");byte[] buffer=new byte[256*1024];int size;
                while((size=input.read(buffer))!=-1){checkCancelled(token);output.write(buffer,0,size);}output.flush();
            }
            checkCancelled(token);ContentValues ready=new ContentValues();ready.put(MediaStore.Video.Media.IS_PENDING,0);
            if(context.getContentResolver().update(uri,ready,null,null)!=1)throw new IOException("Не удалось опубликовать видео.");
            checkCancelled(token);complete=true;return new ClipExporter.Result(uri,0,durationUs);
        }finally{if(!complete&&uri!=null)context.getContentResolver().delete(uri,null,null);}
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
            message=failure!=null?failure:output==null?"Экспорт не завершён.":"Готово! Галерея → Фильмы → Sony Vegas Video\nДлительность: "+ClipMath.clockCode(output.endUs/1000);
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
