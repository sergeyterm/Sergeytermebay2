package com.personal.sonyvegasvideo;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;

final class NativeTestSupport implements AutoCloseable {
    final Instrumentation test;
    final Context context;
    private final List<Uri> owned=new ArrayList<>();
    private final String prefix="VegasQA_"+System.currentTimeMillis()+"_";
    NativeTestSupport(Instrumentation test){this.test=test;context=test.getTargetContext();}
    void step(String message){Bundle status=new Bundle();status.putString("stream","QA: "+message+"\n");test.sendStatus(1,status);}
    static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    <T>T main(Callable<T> action){
        final Object[] result=new Object[1];final Throwable[] error=new Throwable[1];
        test.runOnMainSync(()->{try{result[0]=action.call();}catch(Throwable failure){error[0]=failure;}});
        if(error[0]!=null)throw new AssertionError("Main-thread operation failed",error[0]);
        @SuppressWarnings("unchecked") T value=(T)result[0];return value;
    }
    void await(BooleanSupplier condition,long timeout,String message){
        long end=SystemClock.uptimeMillis()+timeout;
        while(SystemClock.uptimeMillis()<end){if(condition.getAsBoolean())return;SystemClock.sleep(80);}
        throw new AssertionError(message+"\nActive window: "+windowText());
    }
    Uri fixture(String asset)throws Exception{
        Uri uri=create(asset,"video/mp4",MediaStore.Video.Media.EXTERNAL_CONTENT_URI,"Movies/VegasVideoTest");
        try(InputStream in=test.getContext().getAssets().open(asset);OutputStream out=context.getContentResolver().openOutputStream(uri,"w")){
            require(out!=null,"Cannot write fixture "+asset);byte[] buffer=new byte[8192];int n;
            while((n=in.read(buffer))!=-1)out.write(buffer,0,n);
        }
        ContentValues ready=new ContentValues();ready.put(MediaStore.MediaColumns.IS_PENDING,0);context.getContentResolver().update(uri,ready,null,null);return uri;
    }
    Uri document(String name,String contents)throws Exception{
        Uri uri=create(name,"application/json",MediaStore.Downloads.EXTERNAL_CONTENT_URI,"Download/VegasVideoTest");
        try(OutputStream out=context.getContentResolver().openOutputStream(uri,"w")){require(out!=null,"Cannot write document");out.write(contents.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        ContentValues ready=new ContentValues();ready.put(MediaStore.MediaColumns.IS_PENDING,0);context.getContentResolver().update(uri,ready,null,null);return uri;
    }
    Uri corruptVideo()throws Exception{
        Uri uri=create("broken.mp4","video/mp4",MediaStore.Video.Media.EXTERNAL_CONTENT_URI,"Movies/VegasVideoTest");
        try(OutputStream out=context.getContentResolver().openOutputStream(uri,"w")){out.write("This is not a media file".getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        ContentValues ready=new ContentValues();ready.put(MediaStore.MediaColumns.IS_PENDING,0);context.getContentResolver().update(uri,ready,null,null);return uri;
    }
    private Uri create(String name,String mime,Uri table,String path){
        ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,prefix+name);values.put(MediaStore.MediaColumns.MIME_TYPE,mime);values.put(MediaStore.MediaColumns.RELATIVE_PATH,path);values.put(MediaStore.MediaColumns.IS_PENDING,1);
        Uri uri=context.getContentResolver().insert(table,values);require(uri!=null,"Cannot create test file "+name);owned.add(uri);return uri;
    }
    void own(Uri uri){if(uri!=null)owned.add(uri);}
    String read(Uri uri)throws Exception{
        try(InputStream in=context.getContentResolver().openInputStream(uri);java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream()){
            require(in!=null,"Missing saved project");byte[] bytes=new byte[4096];int n;while((n=in.read(bytes))!=-1){out.write(bytes,0,n);require(out.size()<262144,"Unexpectedly large saved project");}return new String(out.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);
        }
    }
    void clearDocument(Uri uri)throws Exception{try(OutputStream out=context.getContentResolver().openOutputStream(uri,"wt")){require(out!=null,"Cannot reset test document");}}
    Instrumentation.ActivityMonitor picker(String action,Uri response)throws Exception{
        IntentFilter filter=new IntentFilter(action);filter.addCategory(Intent.CATEGORY_OPENABLE);filter.addDataType("*/*");
        Intent result=new Intent().setData(response).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        return test.addMonitor(filter,new Instrumentation.ActivityResult(Activity.RESULT_OK,result),true);
    }
    void picked(Instrumentation.ActivityMonitor monitor){await(()->monitor.getHits()>0,5000,"Document picker was not launched");test.removeMonitor(monitor);test.waitForIdleSync();}
    void click(View view){require(view!=null,"Missing native control");main(()->{require(view.isEnabled(),"Control disabled: "+view.getContentDescription());require(view.performClick(),"Control has no click handler: "+view.getContentDescription());return null;});test.waitForIdleSync();}
    void clickDescription(Activity activity,String description){View view=main(()->findDescription(activity.getWindow().getDecorView(),description));require(view!=null,"Missing control "+description);click(view);}
    void clickText(Activity activity,String text){View view=main(()->findText(activity.getWindow().getDecorView(),text));require(view!=null,"Missing text control "+text);click(view);}
    boolean hasText(String value){return node(value,false)!=null;}
    void windowClick(String value){
        await(()->node(value,false)!=null,5000,"Missing window item "+value);AccessibilityNodeInfo item=node(value,false);
        for(int i=0;i<6&&item!=null;i++,item=item.getParent())if(item.isClickable()){require(item.performAction(AccessibilityNodeInfo.ACTION_CLICK),"Window click failed: "+value);test.waitForIdleSync();return;}
        throw new AssertionError("Window item is not clickable: "+value+"\n"+windowText());
    }
    void setField(String description,String value){
        AccessibilityNodeInfo item=node(description,true);require(item!=null,"Missing field "+description);Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);require(item.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args),"Cannot set field "+description);test.waitForIdleSync();
    }
    void setEditable(int index,String value){
        List<AccessibilityNodeInfo> nodes=new ArrayList<>();collectEditable(test.getUiAutomation().getRootInActiveWindow(),nodes);require(index<nodes.size(),"Missing editable #"+index+" "+windowText());
        Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);require(nodes.get(index).performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args),"Cannot edit field #"+index);test.waitForIdleSync();
    }
    void back(){test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);test.waitForIdleSync();}
    void dismissIfPresent(String value){if(hasText(value))windowClick(value);}
    void screenshot(String name)throws Exception{
        Bitmap image=test.getUiAutomation().takeScreenshot();require(image!=null,"Screenshot unavailable");File out=new File(context.getFilesDir(),name+".png");
        try(FileOutputStream stream=new FileOutputStream(out)){image.compress(Bitmap.CompressFormat.PNG,100,stream);}finally{image.recycle();}
    }
    static View findDescription(View view,String value){if(value.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=findDescription(group.getChildAt(i),value);if(found!=null)return found;}}return null;}
    static View findText(View view,String value){if(view instanceof TextView&&value.contentEquals(((TextView)view).getText()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=findText(group.getChildAt(i),value);if(found!=null)return found;}}return null;}
    static TextView textPrefix(View view,String value){if(view instanceof TextView&&((TextView)view).getText().toString().startsWith(value))return (TextView)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){TextView found=textPrefix(group.getChildAt(i),value);if(found!=null)return found;}}return null;}
    static <T>T findClass(View view,Class<T> type){if(type.isInstance(view))return type.cast(view);if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){T found=findClass(group.getChildAt(i),type);if(found!=null)return found;}}return null;}
    String windowText(){StringBuilder out=new StringBuilder();collectText(test.getUiAutomation().getRootInActiveWindow(),out,0);return out.toString();}
    private AccessibilityNodeInfo node(String value,boolean description){return findNode(test.getUiAutomation().getRootInActiveWindow(),value,description);}
    private static AccessibilityNodeInfo findNode(AccessibilityNodeInfo node,String value,boolean description){if(node==null)return null;CharSequence text=description?node.getContentDescription():node.getText();if(text!=null&&value.contentEquals(text))return node;for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=findNode(node.getChild(i),value,description);if(found!=null)return found;}return null;}
    private static void collectText(AccessibilityNodeInfo node,StringBuilder out,int depth){if(node==null||depth>25||out.length()>7000)return;if(node.getText()!=null)out.append(node.getText()).append(" | ");for(int i=0;i<node.getChildCount();i++)collectText(node.getChild(i),out,depth+1);}
    private static void collectEditable(AccessibilityNodeInfo node,List<AccessibilityNodeInfo> out){if(node==null)return;if(node.isEditable())out.add(node);for(int i=0;i<node.getChildCount();i++)collectEditable(node.getChild(i),out);}
    Set<String> movieRows(){
        Set<String> rows=new HashSet<>();Bundle args=new Bundle();args.putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,"relative_path = ?");args.putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,new String[]{"Movies/Sony Vegas Video/"});args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING,MediaStore.MATCH_INCLUDE);
        try(android.database.Cursor cursor=context.getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,new String[]{"_id"},args,null)){if(cursor!=null)while(cursor.moveToNext())rows.add(cursor.getString(0));}return rows;
    }
    static void restore(SharedPreferences preferences,Map<String,?> values){SharedPreferences.Editor edit=preferences.edit().clear();for(Map.Entry<String,?> item:values.entrySet()){Object value=item.getValue();String key=item.getKey();if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else if(value instanceof Float)edit.putFloat(key,(Float)value);else if(value instanceof Long)edit.putLong(key,(Long)value);else if(value instanceof Set)edit.putStringSet(key,new HashSet<>((Set<String>)value));}edit.commit();}
    @Override public void close(){for(int i=owned.size()-1;i>=0;i--)try{context.getContentResolver().delete(owned.get(i),null,null);}catch(RuntimeException ignored){}}
}
