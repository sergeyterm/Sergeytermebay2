package com.personal.sonyvegasvideo;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.VideoView;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.io.File;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Drives real native views; screenshots and results are produced only on Android. */
public final class WorkspaceSmoke {
    public static void run(Instrumentation test,String screenshot)throws Exception{
        Context context=test.getTargetContext();SharedPreferences preferences=context.getSharedPreferences("editor",Context.MODE_PRIVATE);
        Map<String,?> before=preferences.getAll();Activity activity=null;Uri source=null;
        try{
            ContentValues values=new ContentValues();values.put(MediaStore.Video.Media.DISPLAY_NAME,"VegasUIFixture.mp4");values.put(MediaStore.Video.Media.MIME_TYPE,"video/mp4");values.put(MediaStore.Video.Media.RELATIVE_PATH,"Movies/VegasVideoTest");values.put(MediaStore.Video.Media.IS_PENDING,1);
            source=context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,values);
            try(InputStream in=test.getContext().getAssets().open("rotated-av.mp4");OutputStream out=context.getContentResolver().openOutputStream(source,"w")){
                byte[] b=new byte[8192];int n;while((n=in.read(b))>=0)out.write(b,0,n);
            }
            ContentValues ready=new ContentValues();ready.put(MediaStore.Video.Media.IS_PENDING,0);context.getContentResolver().update(source,ready,null,null);
            Intent intent=new Intent(Intent.ACTION_VIEW,source,context,MainActivity.class).setType("video/mp4");
            intent.setDataAndType(source,"video/mp4");intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_GRANT_READ_URI_PERMISSION);
            activity=test.startActivitySync(intent);test.waitForIdleSync();
            final Activity active=activity;View root=activity.getWindow().getDecorView();
            for(String menu:new String[]{"File","Edit","View","Insert","Tools","Options","Help"})require(findText(root,menu)!=null,"Missing menu "+menu);
            TimelineView timeline=findClass(root,TimelineView.class);VideoView preview=findClass(root,VideoView.class);
            require(timeline!=null&&timeline.getWidth()>100&&timeline.getHeight()>50,"Missing/empty timeline");
            View render=findDescription(root,"Render As… — сохранить MP4");
            await(test,()->render.isEnabled(),45000,"Media did not prepare");
            require(!onMain(test,preview::isPlaying),"Import unexpectedly started playback");
            View undo=findDescription(root,"Отменить изменение"),redo=findDescription(root,"Повторить изменение");
            require(!undo.isEnabled(),"Undo should be empty before editing");
            float d=context.getResources().getDisplayMetrics().density;
            float header=Math.max(98*d,Math.min(150*d,timeline.getWidth()*.27f));
            test.runOnMainSync(()->{
                long now=SystemClock.uptimeMillis();float y=18*d;
                MotionEvent down=MotionEvent.obtain(now,now,MotionEvent.ACTION_DOWN,header+2*d,y,0);timeline.dispatchTouchEvent(down);down.recycle();
                MotionEvent move=MotionEvent.obtain(now,now+100,MotionEvent.ACTION_MOVE,header+(timeline.getWidth()-header)*.20f,y,0);timeline.dispatchTouchEvent(move);move.recycle();
                MotionEvent up=MotionEvent.obtain(now,now+150,MotionEvent.ACTION_UP,header+(timeline.getWidth()-header)*.20f,y,0);timeline.dispatchTouchEvent(up);up.recycle();
            });
            test.waitForIdleSync();require(undo.isEnabled(),"Loop handle drag did not create an undo entry");
            test.runOnMainSync(undo::performClick);test.waitForIdleSync();require(redo.isEnabled(),"Undo did not enable redo");
            test.runOnMainSync(redo::performClick);test.waitForIdleSync();require(undo.isEnabled(),"Redo did not restore edit");
            View mute=findDescription(root,"Отключить звук исходного ролика");
            test.runOnMainSync(mute::performClick);require(mute.isSelected(),"Mute did not activate");
            test.runOnMainSync(mute::performClick);require(!mute.isSelected(),"Mute did not deactivate");
            View play=findDescription(root,"Воспроизвести / пауза");test.runOnMainSync(play::performClick);
            await(test,preview::isPlaying,10000,"Playback did not start");
            View pause=findDescription(root,"Пауза");test.runOnMainSync(pause::performClick);
            await(test,()->!preview.isPlaying(),10000,"Playback did not pause");
            // Wait for the actual thumbnail and PCM envelope task to finish, bounded in software emulation.
            long deadline=SystemClock.uptimeMillis()+20000;
            while(SystemClock.uptimeMillis()<deadline){test.waitForIdleSync();SystemClock.sleep(100);}
            Bitmap capture=test.getUiAutomation().takeScreenshot();require(capture!=null,"Screenshot was unavailable");
            File out=new File(context.getFilesDir(),"ui-"+screenshot+".png");
            try(FileOutputStream stream=new FileOutputStream(out)){capture.compress(Bitmap.CompressFormat.PNG,100,stream);}capture.recycle();
        }finally{
            if(activity!=null){Activity closing=activity;test.runOnMainSync(closing::finish);test.waitForIdleSync();}
            SharedPreferences.Editor restore=preferences.edit().clear();
            for(Map.Entry<String,?> entry:before.entrySet()){
                Object value=entry.getValue();String key=entry.getKey();
                if(value instanceof String)restore.putString(key,(String)value);else if(value instanceof Integer)restore.putInt(key,(Integer)value);
                else if(value instanceof Boolean)restore.putBoolean(key,(Boolean)value);else if(value instanceof Float)restore.putFloat(key,(Float)value);
                else if(value instanceof Long)restore.putLong(key,(Long)value);
            }
            restore.commit();if(source!=null)context.getContentResolver().delete(source,null,null);
        }
    }
    private static boolean onMain(Instrumentation test,BooleanSupplier supplier){boolean[] value=new boolean[1];test.runOnMainSync(()->value[0]=supplier.getAsBoolean());return value[0];}
    private static void await(Instrumentation test,BooleanSupplier condition,long timeout,String message){long end=SystemClock.uptimeMillis()+timeout;while(SystemClock.uptimeMillis()<end){if(onMain(test,condition))return;SystemClock.sleep(100);}throw new AssertionError(message);}
    private static View findText(View view,String text){if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=findText(group.getChildAt(i),text);if(found!=null)return found;}}return null;}
    private static View findDescription(View view,String text){if(text.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=findDescription(group.getChildAt(i),text);if(found!=null)return found;}}return null;}
    private static <T> T findClass(View view,Class<T> type){if(type.isInstance(view))return type.cast(view);if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){T found=findClass(group.getChildAt(i),type);if(found!=null)return found;}}return null;}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
}
