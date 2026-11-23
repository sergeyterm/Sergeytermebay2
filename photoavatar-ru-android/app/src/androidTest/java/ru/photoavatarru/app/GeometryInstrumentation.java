package ru.photoavatarru.app;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Intent;
import android.content.Context;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** End-to-end native file decode plus real bundled WebView geometry on emulator API 35. */
public final class GeometryInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);start();}
    @Override public void onStart(){
        Bundle result=new Bundle();
        Uri uri=null;Activity activity=null;
        try{
            Context target=getTargetContext();
            byte[] fixture;
            try(InputStream in=getContext().getAssets().open("portrait.jpg");ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);fixture=out.toByteArray();
            }
            ContentValues cv=new ContentValues();
            cv.put(MediaStore.Images.Media.DISPLAY_NAME,"photoavatar-geometry-test.jpg");
            cv.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");
            cv.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/PhotoAvatarTest");
            uri=target.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,cv);
            if(uri==null)throw new IllegalStateException("MediaStore insert failed");
            try(OutputStream out=target.getContentResolver().openOutputStream(uri)){out.write(fixture);}
            byte[] jpeg=PhotoImporter.readJpeg(target.getContentResolver(),uri);
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(jpeg,0,jpeg.length,bounds);
            require(bounds.outWidth>100&&bounds.outHeight>100&&Math.max(bounds.outWidth,bounds.outHeight)<=2560,"native import dimensions");
            Intent selection=new Intent();selection.setData(uri);
            require(PhotoImporter.selectedUris(selection).size()==1,"native URI selection");
            result.putString("native_import","PASS: Android ContentResolver -> JPEG");
            Intent launch=new Intent(target,RunwayActivity.class);launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity=startActivitySync(launch);waitForIdleSync();
            AtomicReference<WebView> webRef=new AtomicReference<>();Activity a=activity;
            runOnMainSync(()->webRef.set(findWeb(a.getWindow().getDecorView())));
            WebView web=webRef.get();require(web!=null,"WebView missing");
            waitFor(web,"!!document.getElementById('geometryAnalyze')&&typeof window.__nativeReply==='function'",30000);
            String b64=Base64.encodeToString(jpeg,Base64.NO_WRAP);
            eval(web,"(()=>{const bytes=Uint8Array.from(atob("+JSONObject.quote(b64)+"),c=>c.charCodeAt(0));const dt=new DataTransfer();dt.items.add(new File([bytes],'portrait.jpg',{type:'image/jpeg'}));const p=document.getElementById('photo');p.files=dt.files;p.dispatchEvent(new Event('change',{bubbles:true}));return true})()");
            waitFor(web,"!document.getElementById('photo').disabled&&document.getElementById('preparedPreview').src.startsWith('data:image/jpeg')",35000);
            eval(web,"document.getElementById('geometryDetails').open=true;document.getElementById('geometryAnalyze').click();true");
            waitFor(web,"document.getElementById('geometryStatus').textContent.includes('✓')",90000);
            require("true".equals(eval(web,"document.getElementById('geometryMetrics').textContent.includes('478 точек')&&document.getElementById('geometryMetrics').textContent.includes('52 коэффициентов')")),"wrong landmark counts");
            result.putString("geometry","PASS: Android WebView local MediaPipe 478 landmarks, 52 blendshapes");
            result.putString("stream","\nPASS: native import and real offline face geometry on Android\n");
            finish(Activity.RESULT_OK,result);
        }catch(Throwable e){
            result.putString("stream","\nFAIL: "+e.toString()+"\n");
            result.putString("error",e.toString());finish(Activity.RESULT_CANCELED,result);
        }finally{
            if(uri!=null)try{getTargetContext().getContentResolver().delete(uri,null,null);}catch(Exception ignored){}
            if(activity!=null){Activity a=activity;try{runOnMainSync(a::finish);}catch(Exception ignored){}}
        }
    }
    private static WebView findWeb(View view){if(view instanceof WebView)return (WebView)view;if(view instanceof ViewGroup){ViewGroup g=(ViewGroup)view;for(int i=0;i<g.getChildCount();i++){WebView w=findWeb(g.getChildAt(i));if(w!=null)return w;}}return null;}
    private static void require(boolean condition,String msg){if(!condition)throw new IllegalStateException(msg);}
    private String eval(WebView web,String js)throws Exception{
        CountDownLatch latch=new CountDownLatch(1);AtomicReference<String> response=new AtomicReference<>();
        runOnMainSync(()->web.evaluateJavascript(js,value->{response.set(value);latch.countDown();}));
        if(!latch.await(20,TimeUnit.SECONDS))throw new IllegalStateException("WebView evaluation timeout");
        return response.get();
    }
    private void waitFor(WebView web,String js,long timeout)throws Exception{
        long deadline=android.os.SystemClock.elapsedRealtime()+timeout;
        while(android.os.SystemClock.elapsedRealtime()<deadline){if("true".equals(eval(web,js)))return;Thread.sleep(350);}
        String info=eval(web,"document.getElementById('geometryStatus')?.textContent||document.getElementById('photoImportStatus')?.textContent||''");
        throw new IllegalStateException("WebView condition timeout: "+info);
    }
}
