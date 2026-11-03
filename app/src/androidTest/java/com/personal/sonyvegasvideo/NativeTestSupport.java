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
    NativeTestSupport(Instrumentation test){this.test=test;context=test.getTargetContext();android.accessibilityservice.AccessibilityServiceInfo info=test.getUiAutomation().getServiceInfo();if(info!=null){info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS|android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;test.getUiAutomation().setServiceInfo(info);}}
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
    void picked(Instrumentation.ActivityMonitor monitor){await(()->monitor.getHits()>0,5000,"Document picker was not launched");test.removeMonitor(monitor);settle();}
    void settle(){test.waitForIdleSync();try{test.getUiAutomation().waitForIdle(150,2000);}catch(java.util.concurrent.TimeoutException continuouslyUpdating){/* State-specific bounded waits still decide each assertion. */}}
    void click(View view){require(view!=null,"Missing native control");main(()->{require(view.isEnabled(),"Control disabled: "+view.getContentDescription());view.requestRectangleOnScreen(new android.graphics.Rect(0,0,view.getWidth(),view.getHeight()),true);return null;});test.waitForIdleSync();main(()->{require(view.performClick(),"Control has no click handler: "+view.getContentDescription());return null;});settle();}
    void clickDescription(Activity activity,String description){View view=main(()->findDescription(activity.getWindow().getDecorView(),description));require(view!=null,"Missing control "+description);click(view);}
    void clickText(Activity activity,String text){View view=main(()->findText(activity.getWindow().getDecorView(),text));require(view!=null,"Missing text control "+text);click(view);}
    boolean hasText(String value){return node(value,false)!=null;}
    void menuReady(String expectedItem){await(()->hasText(expectedItem),8000,"Menu did not expose item "+expectedItem);}
    void windowClick(String value){
        await(()->node(value,false)!=null,5000,"Missing window item "+value);AccessibilityNodeInfo item=node(value,false);
        for(int i=0;i<6&&item!=null;i++,item=item.getParent())if(item.isClickable()){require(item.performAction(AccessibilityNodeInfo.ACTION_CLICK),"Window click failed: "+value);settle();return;}
        throw new AssertionError("Window item is not clickable: "+value+"\n"+windowText());
    }
    void setField(String description,String value){
        AccessibilityNodeInfo item=node(description,true);require(item!=null,"Missing field "+description);Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);require(item.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args),"Cannot set field "+description);test.waitForIdleSync();
    }
    void windowDescriptionClick(String description){await(()->node(description,true)!=null,5000,"Missing dialog action "+description);AccessibilityNodeInfo item=node(description,true);require(item.isEnabled()&&item.performAction(AccessibilityNodeInfo.ACTION_CLICK),"Cannot click dialog action "+description);settle();}
    void setProgressField(String description,float value){AccessibilityNodeInfo item=node(description,true);require(item!=null,"Missing slider "+description);Bundle args=new Bundle();args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,value);require(item.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),args),"Cannot adjust slider "+description);settle();}
    void setEditable(int index,String value){
        final AccessibilityNodeInfo[] selected=new AccessibilityNodeInfo[1];
        await(()->{selected[0]=editable(index);return selected[0]!=null;},8000,"Missing editable #"+index);
        Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,value);require(selected[0].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args),"Cannot edit field #"+index);settle();
        await(()->{AccessibilityNodeInfo actual=editable(index);return actual!=null&&value.contentEquals(actual.getText()==null?"":actual.getText());},5000,"Editable #"+index+" did not accept text "+value);
    }
    void back(){test.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);settle();}
    android.content.ComponentName systemVideoPicker()throws Exception{
        // The test inspects system routing; app PackageManager queries are visibility-filtered.
        String command="cmd package resolve-activity --brief --user current -a android.intent.action.OPEN_DOCUMENT -c android.intent.category.OPENABLE -t video/*";
        String output;
        try(InputStream in=new android.os.ParcelFileDescriptor.AutoCloseInputStream(test.getUiAutomation().executeShellCommand(command));java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream()){
            byte[] buffer=new byte[1024];int count;while((count=in.read(buffer))!=-1){bytes.write(buffer,0,count);require(bytes.size()<65536,"System picker resolver returned unexpected output");}output=new String(bytes.toByteArray(),java.nio.charset.StandardCharsets.UTF_8);
        }
        for(String line:output.split("\\r?\\n")){String value=line.trim();if(!value.matches("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+"))continue;android.content.ComponentName component=android.content.ComponentName.unflattenFromString(value);if(component!=null){step("System document picker: "+component.flattenToShortString());return component;}}
        throw new AssertionError("System document picker did not resolve: "+output);
    }
    boolean focusedApplication(String packageName,boolean interactive){
        for(android.view.accessibility.AccessibilityWindowInfo window:test.getUiAutomation().getWindows()){
            if(window.getType()!=android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION||!window.isFocused())continue;
            AccessibilityNodeInfo root=window.getRoot();if(root==null||!packageName.contentEquals(root.getPackageName()==null?"":root.getPackageName()))continue;
            if(!interactive||visibleClickable(root))return true;
        }
        return false;
    }
    private static boolean visibleClickable(AccessibilityNodeInfo node){if(node==null)return false;if(node.isEnabled()&&node.isClickable()&&node.isVisibleToUser())return true;for(int i=0;i<node.getChildCount();i++)if(visibleClickable(node.getChild(i)))return true;return false;}
    void dismissIfPresent(String value){settle();if(hasText(value))windowClick(value);}
    void screenshot(String name)throws Exception{
        Bitmap image=test.getUiAutomation().takeScreenshot();require(image!=null,"Screenshot unavailable");File out=new File(context.getFilesDir(),name+".png");
        try(FileOutputStream stream=new FileOutputStream(out)){image.compress(Bitmap.CompressFormat.PNG,100,stream);}finally{image.recycle();}
    }
    static View findDescription(View view,String value){if(value.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=findDescription(group.getChildAt(i),value);if(found!=null)return found;}}return null;}
    static View findText(View view,String value){if(view instanceof TextView&&value.contentEquals(((TextView)view).getText()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=findText(group.getChildAt(i),value);if(found!=null)return found;}}return null;}
    static TextView textPrefix(View view,String value){if(view instanceof TextView&&((TextView)view).getText().toString().startsWith(value))return (TextView)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){TextView found=textPrefix(group.getChildAt(i),value);if(found!=null)return found;}}return null;}
    static <T>T findClass(View view,Class<T> type){if(type.isInstance(view))return type.cast(view);if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){T found=findClass(group.getChildAt(i),type);if(found!=null)return found;}}return null;}
    String windowText(){StringBuilder out=new StringBuilder();for(android.view.accessibility.AccessibilityWindowInfo window:test.getUiAutomation().getWindows())if(window.isFocused()){AccessibilityNodeInfo root=window.getRoot();out.append("[focused package=").append(root==null?"?":root.getPackageName()).append(", type=").append(window.getType()).append("] ");}collectText(activeRoot(),out,0);return out.toString();}
    private AccessibilityNodeInfo activeRoot(){AccessibilityNodeInfo root=test.getUiAutomation().getRootInActiveWindow();if(root!=null)return root;for(android.view.accessibility.AccessibilityWindowInfo window:test.getUiAutomation().getWindows())if(window.isActive()||window.isFocused()){root=window.getRoot();if(root!=null)return root;}return null;}
    private AccessibilityNodeInfo node(String value,boolean description){return findNode(activeRoot(),value,description);}
    private static AccessibilityNodeInfo findNode(AccessibilityNodeInfo node,String value,boolean description){if(node==null)return null;CharSequence text=description?node.getContentDescription():node.getText();if(text!=null&&(description?value.contentEquals(text):value.trim().equalsIgnoreCase(text.toString().trim())))return node;for(int i=0;i<node.getChildCount();i++){AccessibilityNodeInfo found=findNode(node.getChild(i),value,description);if(found!=null)return found;}return null;}
    private static void collectText(AccessibilityNodeInfo node,StringBuilder out,int depth){if(node==null||depth>25||out.length()>7000)return;if(node.getText()!=null)out.append(node.getText()).append(" | ");for(int i=0;i<node.getChildCount();i++)collectText(node.getChild(i),out,depth+1);}
    private AccessibilityNodeInfo editable(int index){
        List<android.view.accessibility.AccessibilityWindowInfo> windows=new ArrayList<>(test.getUiAutomation().getWindows());
        java.util.Collections.sort(windows,(a,b)->Integer.compare(b.getLayer(),a.getLayer()));
        for(android.view.accessibility.AccessibilityWindowInfo window:windows){if(window.getType()!=android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION)continue;AccessibilityNodeInfo root=window.getRoot();if(root==null||!context.getPackageName().contentEquals(root.getPackageName()==null?"":root.getPackageName()))continue;List<AccessibilityNodeInfo> nodes=new ArrayList<>();collectEditable(root,nodes);if(index<nodes.size())return nodes.get(index);}
        List<AccessibilityNodeInfo> nodes=new ArrayList<>();collectEditable(activeRoot(),nodes);return index<nodes.size()?nodes.get(index):null;
    }
    private static void collectEditable(AccessibilityNodeInfo node,List<AccessibilityNodeInfo> out){if(node==null)return;boolean setText=false;for(AccessibilityNodeInfo.AccessibilityAction action:node.getActionList())if(action.getId()==AccessibilityNodeInfo.ACTION_SET_TEXT){setText=true;break;}if(node.isEnabled()&&setText)out.add(node);for(int i=0;i<node.getChildCount();i++)collectEditable(node.getChild(i),out);}
    Set<String> movieRows(){
        Set<String> rows=new HashSet<>();Bundle args=new Bundle();args.putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,"relative_path = ?");args.putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,new String[]{"Movies/Sony Vegas Video/"});args.putInt(MediaStore.QUERY_ARG_MATCH_PENDING,MediaStore.MATCH_INCLUDE);
        try(android.database.Cursor cursor=context.getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,new String[]{"_id"},args,null)){if(cursor!=null)while(cursor.moveToNext())rows.add(cursor.getString(0));}return rows;
    }
    static void restore(SharedPreferences preferences,Map<String,?> values){SharedPreferences.Editor edit=preferences.edit().clear();for(Map.Entry<String,?> item:values.entrySet()){Object value=item.getValue();String key=item.getKey();if(value instanceof String)edit.putString(key,(String)value);else if(value instanceof Integer)edit.putInt(key,(Integer)value);else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);else if(value instanceof Float)edit.putFloat(key,(Float)value);else if(value instanceof Long)edit.putLong(key,(Long)value);else if(value instanceof Set)edit.putStringSet(key,new HashSet<>((Set<String>)value));}edit.commit();}
    @Override public void close(){for(int i=owned.size()-1;i>=0;i--)try{context.getContentResolver().delete(owned.get(i),null,null);}catch(RuntimeException ignored){}}
}
