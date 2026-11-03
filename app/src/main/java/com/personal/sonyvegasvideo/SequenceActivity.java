package com.personal.sonyvegasvideo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Real cut/list assembly workspace. Source files are never changed or deleted. */
public final class SequenceActivity extends Activity {
    private static final int IMPORT=210,OPEN=211,SAVE=212,BG=0xffa2a2a2,FG=0xff222222;
    private Sequence sequence=new Sequence();
    private final ArrayDeque<Sequence> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ExportJob job=ExportJob.get();
    private final ExportJob.Listener jobListener=this::showJob;
    private final List<View> actions=new ArrayList<>();
    private SequenceTimeline timeline;private VideoView preview;private MediaPlayer player;
    private LinearLayout clipList;private TextView status,time,label;private ClassicIcon undoIcon,redoIcon,playIcon,muteIcon,cancelIcon;
    private ProgressBar progress;private int selected=-1,cursor,pendingSource;
    private boolean prepared,wantPlay,seekInFlight,busy,muted;
    private String loaded="",pendingJson="";
    private final Runnable tick=new Runnable(){@Override public void run(){
        if(prepared&&!seekInFlight&&selected>=0&&selected<sequence.clips.size()&&preview.isPlaying()){
            Sequence.Clip clip=sequence.clips.get(selected);int at=preview.getCurrentPosition();
            cursor=ClipMath.clamp(sequence.offset(selected)+at-clip.inMs,0,sequence.length());
            if(at>=clip.outMs){advance();}else updateTime();
        }
        handler.postDelayed(this,60);
    }};
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);
        String json=saved!=null?saved.getString("project",""):getSharedPreferences("sequence_editor",MODE_PRIVATE).getString("project","");
        muted=saved!=null?saved.getBoolean("mute"):getSharedPreferences("sequence_editor",MODE_PRIVATE).getBoolean("mute",false);
        cursor=saved!=null?saved.getInt("cursor"):getSharedPreferences("sequence_editor",MODE_PRIVATE).getInt("cursor",0);
        pendingJson=saved!=null?saved.getString("pendingJson",""):"";
        try{if(!json.isEmpty())sequence=parse(json);}catch(Exception ignored){}
        if(sequence.clips.isEmpty()&&getIntent().getStringExtra("source")!=null){
            try{sequence=sequence.append(new Sequence.Clip(getIntent().getStringExtra("source"),getIntent().getStringExtra("name"),getIntent().getIntExtra("start",0),getIntent().getIntExtra("end",0)));cursor=0;}catch(Exception ignored){}
        }
        build();refresh();if(!sequence.clips.isEmpty())seek(cursor,false);
    }
    private void build(){
        getWindow().setStatusBarColor(BG);getWindow().setNavigationBarColor(BG);getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout root=column();root.setOnApplyWindowInsetsListener((v,i)->{root.setPadding(i.getSystemWindowInsetLeft(),i.getSystemWindowInsetTop(),i.getSystemWindowInsetRight(),i.getSystemWindowInsetBottom());return i;});setContentView(root);
        TextView title=text("Sony Vegas Video — Timeline Editor",13);title.setPadding(dp(7),0,0,0);root.addView(title,lp(-1,27));
        LinearLayout menu=row();root.addView(menu,lp(-1,29));for(String name:new String[]{"File","Edit","View","Tools","Help"}){TextView item=text(name,12);item.setPadding(dp(10),0,dp(10),0);menu.addView(item,lp(-2,29));item.setOnClickListener(v->menu(item,name));}
        HorizontalScrollView scroll=new HorizontalScrollView(this);scroll.setHorizontalScrollBarEnabled(false);LinearLayout bar=row();scroll.addView(bar);root.addView(scroll,lp(-1,36));
        button(bar,"new","Новый монтаж",()->commit(new Sequence()),false);button(bar,"open","Добавить видео",this::pickVideos,false);
        button(bar,"save","Сохранить проект",this::saveAs,false);undoIcon=button(bar,"undo","Undo",()->history(false),false);redoIcon=button(bar,"redo","Redo",()->history(true),false);
        button(bar,"cut","Разрезать в позиции курсора (S)",this::split,true);button(bar,"delete","Удалить выбранный фрагмент (Delete)",this::delete,true);
        button(bar,"back","Переместить фрагмент влево",()->move(-1),true);button(bar,"forward","Переместить фрагмент вправо",()->move(1),true);button(bar,"render","Render As…",this::render,true);
        LinearLayout docking=row();root.addView(docking,new LinearLayout.LayoutParams(-1,0,.5f));
        LinearLayout left=column();docking.addView(left,new LinearLayout.LayoutParams(0,-1,.38f));left.addView(text("Project Media / Events",11),lp(-1,27));ScrollView list=new ScrollView(this);left.addView(list,new LinearLayout.LayoutParams(-1,0,1));clipList=column();list.addView(clipList);
        LinearLayout right=column();docking.addView(right,new LinearLayout.LayoutParams(0,-1,.62f));right.addView(text("Video Preview",11),lp(-1,27));
        android.widget.FrameLayout screen=new android.widget.FrameLayout(this);screen.setBackgroundColor(Color.BLACK);right.addView(screen,new LinearLayout.LayoutParams(-1,0,1));preview=new VideoView(this);screen.addView(preview,new android.widget.FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        label=text("Выберите или импортируйте видео",10);right.addView(label,lp(-1,28));
        preview.setOnPreparedListener(mp->{player=mp;prepared=true;mp.setVolume(muted?0:1,muted?0:1);mp.setOnSeekCompleteListener(done->{if(player!=done)return;seekInFlight=false;if(wantPlay)preview.start();updateControls();});seekInFlight=true;mp.seekTo((long)pendingSource,MediaPlayer.SEEK_CLOSEST);updateControls();});
        preview.setOnCompletionListener(mp->{if(wantPlay)advance();});
        preview.setOnErrorListener((mp,what,extra)->{prepared=false;wantPlay=false;player=null;status("Видео недоступно. Исходник мог быть перемещён или удалён. Импортируйте его повторно.");updateControls();return true;});
        timeline=new SequenceTimeline(this);timeline.listener(ms->seek(ms,false));root.addView(timeline,new LinearLayout.LayoutParams(-1,0,.5f));
        time=text("00:00:00.000",12);time.setTypeface(Typeface.MONOSPACE);root.addView(time,lp(-1,25));
        LinearLayout transport=row();root.addView(transport,lp(-1,36));button(transport,"begin","В начало",()->seek(0,false),true);playIcon=button(transport,"play","Play / Pause",this::toggle,true);button(transport,"stop","Stop",()->seek(0,false),true);
        muteIcon=button(transport,"mute","Mute audio",()->{muted=!muted;if(player!=null)player.setVolume(muted?0:1,muted?0:1);persist();updateControls();},false);
        button(transport,"zoomOut","Zoom out",()->timeline.zoom(.7f),false);button(transport,"zoomIn","Zoom in",()->timeline.zoom(1.5f),false);button(transport,"fit","Fit project",()->timeline.fit(),false);
        cancelIcon=button(transport,"stop","Отменить экспорт",()->job.cancel(),false);progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);root.addView(progress,lp(-1,5));
        status=text("Импорт → разрезать → удалить/переставить → Render As…",11);status.setMaxLines(3);status.setPadding(dp(5),dp(3),dp(5),dp(3));root.addView(status,lp(-1,58));
    }
    private void menu(View anchor,String name){PopupMenu popup=new PopupMenu(this,anchor);Menu menu=popup.getMenu();boolean active=!busy&&!job.running;
        if(name.equals("File")){item(menu,"New",active,()->commit(new Sequence()));item(menu,"Add videos…",active,this::pickVideos);item(menu,"Open project…",active,()->launch(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),OPEN));item(menu,"Save project as…",active,this::saveAs);item(menu,"Render As…",active&&!sequence.clips.isEmpty(),this::render);item(menu,"Open result",job.result!=null,()->result(false));item(menu,"Share result",job.result!=null,()->result(true));}
        else if(name.equals("Edit")){item(menu,"Undo",active&&!undo.isEmpty(),()->history(false));item(menu,"Redo",active&&!redo.isEmpty(),()->history(true));item(menu,"Split (S)",active&&selected>=0,this::split);item(menu,"Delete (ripple)",active&&selected>=0,this::delete);item(menu,"Move left",active&&selected>0,()->move(-1));item(menu,"Move right",active&&selected>=0&&selected+1<sequence.clips.size(),()->move(1));}
        else if(name.equals("View")){item(menu,"Zoom in",true,()->timeline.zoom(1.5f));item(menu,"Zoom out",true,()->timeline.zoom(.7f));item(menu,"Fit project",true,()->timeline.fit());}
        else if(name.equals("Tools")){item(menu,(muted?"✓ ":"")+"Mute audio",active,()->{muted=!muted;if(player!=null)player.setVolume(muted?0:1,muted?0:1);persist();updateControls();});item(menu,"Cancel render",job.running,()->job.cancel());}
        else item(menu,"Editing help",true,()->new AlertDialog.Builder(this).setTitle("Timeline Editor").setMessage("Импортируйте один или несколько роликов. Коснитесь дорожки для выбора фрагмента и позиции курсора. S / ножницы — разрез по ближайшему внутреннему ключевому кадру. Delete удаляет только фрагмент проекта. Стрелки переставляют выбранный фрагмент. Undo / Redo отменяют монтаж.\n\nЭкспорт без перекодирования: у файлов должны совпадать кодеки, разрешение, поворот и параметры кодеков. Разрезы выполняются по ключевым кадрам. Несовместимые источники отклоняются с пояснением.").setPositiveButton("OK",null).show());popup.show();
    }
    private void item(Menu menu,String name,boolean enabled,Runnable action){menu.add(name).setEnabled(enabled).setOnMenuItemClickListener(i->{action.run();return true;});}
    private void commit(Sequence next){if(busy||job.running)return;undo.addLast(sequence);if(undo.size()>64)undo.removeFirst();redo.clear();sequence=next;cursor=ClipMath.clamp(cursor,0,sequence.length());selected=sequence.indexAt(cursor);loaded="";prepared=false;wantPlay=false;player=null;preview.stopPlayback();refresh();persist();if(selected>=0)seek(cursor,false);}
    private void history(boolean forward){if(busy||job.running)return;ArrayDeque<Sequence> from=forward?redo:undo,to=forward?undo:redo;if(from.isEmpty())return;to.addLast(sequence);sequence=from.removeLast();cursor=ClipMath.clamp(cursor,0,sequence.length());loaded="";prepared=false;wantPlay=false;player=null;preview.stopPlayback();refresh();persist();if(!sequence.clips.isEmpty())seek(cursor,false);}
    private void refresh(){
        clipList.removeAllViews();for(int i=0;i<sequence.clips.size();i++){final int index=i;Sequence.Clip clip=sequence.clips.get(i);TextView item=text((i+1)+"  "+clip.name+"\n"+ClipMath.clockCode(clip.inMs)+" — "+ClipMath.clockCode(clip.outMs),10);item.setPadding(dp(5),dp(5),dp(5),dp(5));item.setBackgroundColor(i==selected?0xffb7c6d8:0xffb5b5b5);clipList.addView(item,lp(-1,65));item.setOnClickListener(v->seek(sequence.offset(index),false));}
        timeline.project(sequence,selected);updateTime();updateControls();
    }
    private void seek(int at,boolean play){if(busy||job.running||sequence.clips.isEmpty())return;cursor=ClipMath.clamp(at,0,sequence.length());int index=sequence.indexAt(cursor);Sequence.Clip clip=sequence.clips.get(index);selected=index;pendingSource=ClipMath.clamp(clip.inMs+cursor-sequence.offset(index),clip.inMs,clip.outMs);wantPlay=play;
        label.setText(clip.name);for(int i=0;i<clipList.getChildCount();i++)clipList.getChildAt(i).setBackgroundColor(i==selected?0xffb7c6d8:0xffb5b5b5);timeline.project(sequence,selected);updateTime();
        if(loaded.equals(clip.source)&&prepared&&player!=null){preview.pause();seekInFlight=true;player.seekTo((long)pendingSource,MediaPlayer.SEEK_CLOSEST);}else{prepared=false;seekInFlight=true;player=null;loaded=clip.source;preview.setVideoURI(Uri.parse(clip.source));}updateControls();
    }
    private void advance(){if(selected+1<sequence.clips.size())seek(sequence.offset(selected+1),true);else{wantPlay=false;preview.pause();cursor=sequence.length();updateTime();updateControls();}}
    private void toggle(){if(busy||job.running||sequence.clips.isEmpty())return;if(wantPlay||preview.isPlaying()){wantPlay=false;preview.pause();}else{if(cursor>=sequence.length())seek(0,true);else if(prepared&&!seekInFlight){wantPlay=true;preview.start();}else seek(cursor,true);}updateControls();}
    private void split(){
        if(selected<0||busy||job.running)return;preview.pause();wantPlay=false;Sequence before=sequence;int index=selected;Sequence.Clip clip=before.clips.get(index);int source=clip.inMs+cursor-before.offset(index);busy=true;status("Ищу ближайший ключевой кадр…");updateControls();
        worker.execute(()->{try{int cut=SequenceExporter.nearestCut(getApplicationContext(),clip,source);Sequence after=before.split(index,cut);runOnUiThread(()->{if(isDestroyed())return;busy=false;commit(after);status("Разрез: "+ClipMath.clockCode(cut)+" исходного файла.");});}catch(Exception e){runOnUiThread(()->{if(isDestroyed())return;busy=false;status("Разрез недоступен: "+e.getMessage());updateControls();});}});
    }
    private void delete(){if(selected<0||busy||job.running)return;int at=sequence.offset(selected);Sequence next=sequence.delete(selected);cursor=at;commit(next);status("Фрагмент удалён из проекта. Исходный файл сохранён.");}
    private void move(int direction){if(selected<0||busy||job.running)return;int target=selected+direction;if(target<0||target>=sequence.clips.size())return;Sequence next=sequence.move(selected,target);cursor=next.offset(target);commit(next);}
    private void pickVideos(){if(busy||job.running)return;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("video/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);launch(i,IMPORT);}
    private void launch(Intent i,int code){try{startActivityForResult(i,code);}catch(android.content.ActivityNotFoundException e){status("Не найден проводник Android.");}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null)return;
        if(request==IMPORT){List<Uri> uris=new ArrayList<>();if(data.getClipData()!=null)for(int i=0;i<data.getClipData().getItemCount();i++)uris.add(data.getClipData().getItemAt(i).getUri());else if(data.getData()!=null)uris.add(data.getData());if(uris.isEmpty())return;
            for(Uri uri:uris)try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}
            if(sequence.clips.size()+uris.size()>64){status("Максимум 64 фрагмента.");return;}
            busy=true;updateControls();Sequence before=sequence;status("Импортирую медиа…");worker.execute(()->{try{Sequence next=before;for(Uri uri:uris){MediaMetadataRetriever reader=new MediaMetadataRetriever();try{reader.setDataSource(this,uri);int duration=Integer.parseInt(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));if(reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)==null)throw new IOException("Нет видеодорожки");String name="Video";try(android.database.Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())name=c.getString(0);}next=next.append(new Sequence.Clip(uri.toString(),name,0,duration));}finally{reader.release();}}Sequence finished=next;runOnUiThread(()->{if(isDestroyed())return;busy=false;commit(finished);status("Импортировано: "+uris.size()+". Выберите позицию курсора и нажмите ножницы.");});}catch(Exception e){runOnUiThread(()->{if(isDestroyed())return;busy=false;status("Ошибка импорта: "+e.getMessage());updateControls();});}});
        }else if(request==SAVE&&data.getData()!=null){try(OutputStream out=getContentResolver().openOutputStream(data.getData(),"wt")){if(out==null)throw new IOException("Не удалось открыть файл");out.write(pendingJson.getBytes(StandardCharsets.UTF_8));status("Монтажный проект сохранён.");}catch(Exception e){status("Ошибка: "+e.getMessage());}}
        else if(request==OPEN&&data.getData()!=null){try(InputStream in=getContentResolver().openInputStream(data.getData())){if(in==null)throw new IOException("Нет файла");ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))>=0){bytes.write(b,0,n);if(bytes.size()>131072)throw new IOException("Слишком большой проект");}JSONObject object=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));Sequence next=parse(object.toString());commit(next);muted=object.optBoolean("muted",false);if(player!=null)player.setVolume(muted?0:1,muted?0:1);persist();updateControls();}catch(Exception e){status("Не удалось открыть проект: "+e.getMessage());}}
    }
    private void render(){if(sequence.clips.isEmpty()||busy||job.running)return;new AlertDialog.Builder(this).setTitle("Render As…").setMessage("MP4 · "+sequence.clips.size()+" фрагментов\n"+ClipMath.clockCode(sequence.length())+"\n\nБез перекодирования. Кодеки и параметры источников должны совпадать. Границы округляются к ключевым кадрам.\n\nAudio: "+(muted?"Muted":"Source")).setPositiveButton("Render",(d,w)->{wantPlay=false;preview.pause();job.startSequence(this,sequence,muted);}).setNegativeButton("Отмена",null).show();}
    private void showJob(){if(progress==null)return;progress.setVisibility(job.running?View.VISIBLE:View.GONE);progress.setProgress(job.percent);cancelIcon.setVisibility(job.running?View.VISIBLE:View.GONE);if(!job.message.isEmpty())status(job.message+(job.running?" "+job.percent+"%":""));updateControls();}
    private void result(boolean share){if(job.result==null)return;Intent i=new Intent(share?Intent.ACTION_SEND:Intent.ACTION_VIEW);if(share){i.setType("video/mp4");i.putExtra(Intent.EXTRA_STREAM,job.result.uri);}else i.setDataAndType(job.result.uri,"video/mp4");i.setClipData(android.content.ClipData.newRawUri("Vegas edit",job.result.uri));i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);try{startActivity(share?Intent.createChooser(i,"Поделиться"):i);}catch(Exception e){status("Откройте результат через Галерею.");}}
    private void saveAs(){try{pendingJson=json();launch(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"VegasTimeline.vvp"),SAVE);}catch(Exception e){status(e.getMessage());}}
    private String json()throws Exception{JSONObject object=new JSONObject();object.put("format","VegasVideoSequence");object.put("version",1);object.put("muted",muted);JSONArray clips=new JSONArray();for(Sequence.Clip c:sequence.clips){JSONObject item=new JSONObject();item.put("source",c.source);item.put("name",c.name);item.put("in",c.inMs);item.put("out",c.outMs);clips.put(item);}object.put("clips",clips);return object.toString(2);}
    private Sequence parse(String json)throws Exception{JSONObject object=new JSONObject(json);if(!"VegasVideoSequence".equals(object.getString("format")))throw new IOException("Нужен монтажный проект VegasVideoSequence");JSONArray array=object.getJSONArray("clips");if(array.length()>64)throw new IOException("Слишком много фрагментов");List<Sequence.Clip> clips=new ArrayList<>();for(int i=0;i<array.length();i++){JSONObject c=array.getJSONObject(i);String uri=c.getString("source");if(!"content".equals(Uri.parse(uri).getScheme()))throw new IOException("Недопустимый источник");clips.add(new Sequence.Clip(uri,c.optString("name","Video"),c.getInt("in"),c.getInt("out")));}return new Sequence(clips);}
    private void persist(){try{getSharedPreferences("sequence_editor",MODE_PRIVATE).edit().putString("project",json()).putInt("cursor",cursor).putBoolean("mute",muted).apply();}catch(Exception ignored){}}
    private void updateTime(){timeline.position(cursor);time.setText(ClipMath.clockCode(cursor)+" / "+ClipMath.clockCode(sequence.length())+"   ·   "+sequence.clips.size()+" events");}
    private void updateControls(){if(playIcon==null)return;boolean enabled=!busy&&!job.running;for(View v:actions)v.setEnabled(enabled&&!sequence.clips.isEmpty());undoIcon.setEnabled(enabled&&!undo.isEmpty());redoIcon.setEnabled(enabled&&!redo.isEmpty());playIcon.select(wantPlay);muteIcon.select(muted);muteIcon.setEnabled(enabled);timeline.setEnabled(enabled);cancelIcon.setEnabled(job.running);cancelIcon.setVisibility(job.running?View.VISIBLE:View.GONE);progress.setVisibility(job.running?View.VISIBLE:View.GONE);}
    @Override public boolean onKeyDown(int key,android.view.KeyEvent e){if(key==android.view.KeyEvent.KEYCODE_S&&!e.isCtrlPressed()){split();return true;}if(key==android.view.KeyEvent.KEYCODE_FORWARD_DEL){delete();return true;}if(key==android.view.KeyEvent.KEYCODE_SPACE){toggle();return true;}if(e.isCtrlPressed()&&key==android.view.KeyEvent.KEYCODE_Z){history(e.isShiftPressed());return true;}return super.onKeyDown(key,e);}
    @Override protected void onSaveInstanceState(Bundle out){try{out.putString("project",json());}catch(Exception ignored){}out.putInt("cursor",cursor);out.putBoolean("mute",muted);out.putString("pendingJson",pendingJson);super.onSaveInstanceState(out);}
    @Override protected void onStart(){super.onStart();job.attach(jobListener);}
    @Override protected void onResume(){super.onResume();handler.post(tick);}
    @Override protected void onPause(){wantPlay=false;preview.pause();if(selected>=0&&selected<sequence.clips.size())pendingSource=sequence.clips.get(selected).inMs+cursor-sequence.offset(selected);handler.removeCallbacks(tick);persist();super.onPause();}
    @Override protected void onStop(){job.detach(jobListener);player=null;prepared=false;super.onStop();}
    @Override protected void onDestroy(){handler.removeCallbacks(tick);worker.shutdownNow();player=null;preview.stopPlayback();super.onDestroy();}
    private void status(String text){status.setText(text);}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private LinearLayout row(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);v.setBackgroundColor(BG);return v;}
    private LinearLayout column(){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.VERTICAL);v.setBackgroundColor(BG);return v;}
    private LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w<0?w:dp(w),h<0?h:dp(h));}
    private TextView text(String value,int size){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(FG);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    private ClassicIcon button(LinearLayout parent,String kind,String name,Runnable action,boolean needsClip){ClassicIcon v=new ClassicIcon(this,kind,name);parent.addView(v,lp(36,36));v.setOnClickListener(w->{if(!busy&&(!job.running||v==cancelIcon))action.run();});if(needsClip)actions.add(v);return v;}
}
