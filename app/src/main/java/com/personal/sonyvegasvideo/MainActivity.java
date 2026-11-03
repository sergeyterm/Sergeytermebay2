package com.personal.sonyvegasvideo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Classic Sony-era workspace with native Android media playback and remux export. */
public final class MainActivity extends Activity {
    private static final int PICK_VIDEO=110,SAVE_PROJECT=120,OPEN_PROJECT=121;
    private static final int PANEL=0xff999999,CHROME=0xffb7b7b7,TEXT=0xff202020;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExportJob job=ExportJob.get();
    private final ExportJob.Listener jobListener=this::renderJob;
    private final MediaProbe probe=new MediaProbe();
    private final EditHistory history=new EditHistory();
    private final List<View> mediaControls=new ArrayList<>();
    private final List<Integer> markers=new ArrayList<>();
    private final List<TextView> tabLabels=new ArrayList<>();
    private VideoView preview;
    private MediaPlayer currentPlayer;
    private TimelineView timeline;
    private FrameLayout libraryContent;
    private LinearLayout libraryPane,masterPane;
    private TextView status,placeholder,previewMeta,sourceLabel,selectionLabel,windowTitle,volumeLabel;
    private ClassicIcon playIcon,loopIcon,muteIcon,undoIcon,redoIcon,openIcon,renderIcon,cancelIcon;
    private ProgressBar exportProgress;
    private MasterMeter meter;
    private VerticalFader fader;
    private Uri videoUri,lastExport;
    private int durationMs,beginMs,endMs,restorePosition,restoreEnd=-1,selectedTab;
    private boolean persistedGrant,muted,looping,prepared,exportWasRunning;
    private boolean libraryVisible=true;
    private float previewGain=1,playbackRate=1;
    private String mediaName="Untitled",pendingProject="";
    private MediaProbe.Info mediaInfo;
    private SharedPreferences preferences;
    private EditHistory.State gestureBefore;

    private final Runnable tick=new Runnable(){@Override public void run(){
        if(prepared){
            int position=preview.getCurrentPosition();
            if(preview.isPlaying()&&position>=endMs){
                if(looping){seekPlayer(beginMs);position=beginMs;}else{preview.pause();seekPlayer(endMs);position=endMs;}
            }
            timeline.position(position);playIcon.select(preview.isPlaying());
            float peak=0;
            if(preview.isPlaying()&&!muted&&mediaInfo!=null&&mediaInfo.peaks!=null){
                float[] peaks=mediaInfo.peaks;peak=peaks[Math.min(peaks.length-1,(int)((long)position*peaks.length/Math.max(1,durationMs)))];
            }
            meter.signal(peak*previewGain);
        }
        handler.postDelayed(this,90);
    }};

    @Override public void onCreate(Bundle state){
        super.onCreate(state);preferences=getSharedPreferences("editor",MODE_PRIVATE);
        Bundle saved=state==null?readPreferences():state;
        beginMs=saved.getInt("start",0);restoreEnd=saved.getInt("end",-1);restorePosition=saved.getInt("position",0);
        muted=saved.getBoolean("mute",false);persistedGrant=saved.getBoolean("persisted",false);
        looping=saved.getBoolean("loop",false);previewGain=saved.getFloat("gain",1);pendingProject=saved.getString("pendingProject","");
        libraryVisible=saved.getBoolean("library",true);selectedTab=saved.getInt("tab",0);
        int[] savedMarkers=saved.getIntArray("markers");if(savedMarkers!=null)for(int marker:savedMarkers)markers.add(marker);
        String exported=preferences.getString("export",null);if(exported!=null)lastExport=Uri.parse(exported);
        String source=saved.getString("uri");
        if(state==null&&Intent.ACTION_VIEW.equals(getIntent().getAction())&&getIntent().getData()!=null
                &&"content".equals(getIntent().getData().getScheme())){
            source=getIntent().getData().toString();beginMs=0;restoreEnd=-1;restorePosition=0;muted=false;markers.clear();
            persistedGrant=hasGrant(getIntent().getData());
        }
        buildUi();if(source!=null)openVideo(Uri.parse(source));
    }
    private Bundle readPreferences(){
        Bundle b=new Bundle();b.putString("uri",preferences.getString("uri",null));b.putInt("start",preferences.getInt("start",0));
        b.putInt("end",preferences.getInt("end",-1));b.putInt("position",preferences.getInt("position",0));
        b.putBoolean("mute",preferences.getBoolean("mute",false));b.putBoolean("persisted",preferences.getBoolean("persisted",false));
        b.putBoolean("loop",preferences.getBoolean("loop",false));b.putFloat("gain",preferences.getFloat("gain",1));
        b.putBoolean("library",preferences.getBoolean("library",true));b.putInt("tab",preferences.getInt("tab",0));
        try{JSONArray a=new JSONArray(preferences.getString("markers","[]"));int[] values=new int[Math.min(64,a.length())];for(int i=0;i<values.length;i++)values[i]=a.getInt(i);b.putIntArray("markers",values);}catch(Exception ignored){}
        return b;
    }
    private void buildUi(){
        getWindow().setStatusBarColor(CHROME);getWindow().setNavigationBarColor(CHROME);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout root=column(CHROME);root.setOnApplyWindowInsetsListener((v,insets)->{root.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        setContentView(root);
        windowTitle=text("Untitled — Sony Vegas Video",12,TEXT);windowTitle.setPadding(dp(8),0,0,0);windowTitle.setBackgroundColor(0xffc9c9c9);root.addView(windowTitle,lp(-1,25));
        HorizontalScrollView menuScroll=horizontalScroll();LinearLayout menuRow=row(CHROME);
        String[] menus={"File","Edit","View","Insert","Tools","Options","Help"};
        for(String name:menus){TextView item=text(name,12,TEXT);item.setPadding(dp(9),0,dp(9),0);menuRow.addView(item,lp(-2,29));item.setOnClickListener(v->showMenu(item,name));}
        menuScroll.addView(menuRow);root.addView(menuScroll,lp(-1,29));
        LinearLayout toolbar=row(0xffa7a7a7);HorizontalScrollView toolbarScroll=horizontalScroll();toolbarScroll.addView(toolbar);root.addView(toolbarScroll,lp(-1,36));
        tool(toolbar,"new","Новый проект",this::newProject,false);
        openIcon=tool(toolbar,"open","Открыть видео",this::pickVideo,false);
        tool(toolbar,"save","Сохранить проект",this::saveLocalProject,true);separator(toolbar);
        undoIcon=tool(toolbar,"undo","Отменить изменение",()->applyHistory(false),false);
        redoIcon=tool(toolbar,"redo","Повторить изменение",()->applyHistory(true),false);separator(toolbar);
        tool(toolbar,"cut","Монтаж: разрезать, удалить и склеить",this::openSequence,false);
        tool(toolbar,"select","Точные границы фрагмента",this::preciseRange,true);
        tool(toolbar,"marker","Добавить маркер",this::addMarker,true);separator(toolbar);
        renderIcon=tool(toolbar,"render","Render As… — сохранить MP4",this::renderDialog,true);
        tool(toolbar,"settings","Свойства медиа",this::properties,true);tool(toolbar,"help","Справка",this::help,false);

        boolean wide=getResources().getConfiguration().screenWidthDp>=640;
        LinearLayout docking=row(PANEL);root.addView(docking,new LinearLayout.LayoutParams(-1,0,.52f));
        libraryPane=column(PANEL);libraryPane.setBackground(border(PANEL));
        docking.addView(libraryPane,new LinearLayout.LayoutParams(0,-1,wide?.42f:.34f));
        LinearLayout libraryTools=row(PANEL);libraryPane.addView(libraryTools,lp(-1,30));
        tool(libraryTools,"open","Импортировать медиа",this::pickVideo,false,28);
        sourceLabel=text("Project Media",10,TEXT);sourceLabel.setSingleLine();sourceLabel.setEllipsize(TextUtils.TruncateAt.END);libraryTools.addView(sourceLabel,new LinearLayout.LayoutParams(0,-1,1));
        libraryContent=new FrameLayout(this);libraryPane.addView(libraryContent,new LinearLayout.LayoutParams(-1,0,1));
        HorizontalScrollView tabsScroll=horizontalScroll();LinearLayout tabs=row(PANEL);tabsScroll.addView(tabs);libraryPane.addView(tabsScroll,lp(-1,30));
        String[] tabNames={"Project Media","Explorer","Transitions","Video FX","Media Generators"};
        for(int i=0;i<tabNames.length;i++){final int index=i;TextView tab=text(tabNames[i],10,TEXT);tab.setPadding(dp(9),0,dp(9),0);tabs.addView(tab,lp(-2,29));tabLabels.add(tab);tab.setOnClickListener(v->{selectedTab=index;renderLibrary();});}
        libraryPane.setVisibility(libraryVisible?View.VISIBLE:View.GONE);
        View divider=new View(this);divider.setBackgroundColor(0xff626262);docking.addView(divider,lp(3,-1));
        LinearLayout previewPane=column(PANEL);previewPane.setBackground(border(PANEL));docking.addView(previewPane,new LinearLayout.LayoutParams(0,-1,wide?.58f:.66f));
        LinearLayout previewTools=row(PANEL);previewPane.addView(previewTools,lp(-1,30));
        tool(previewTools,"settings","Свойства медиа",this::properties,true,28);
        TextView previewTitle=text("Video Preview",11,TEXT);previewTools.addView(previewTitle,new LinearLayout.LayoutParams(0,-1,1));
        TextView quality=text("Auto ▾",10,TEXT);quality.setPadding(dp(3),0,dp(6),0);quality.setTooltipText("Автоматический предпросмотр Android");previewTools.addView(quality,lp(-2,-1));
        FrameLayout screen=new FrameLayout(this);screen.setBackgroundColor(Color.BLACK);LinearLayout.LayoutParams screenParams=new LinearLayout.LayoutParams(-1,0,1);screenParams.setMargins(dp(5),0,dp(5),0);previewPane.addView(screen,screenParams);
        preview=new VideoView(this);screen.addView(preview,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        placeholder=text("Video Preview\n\nОткройте видео",12,0xff929292);placeholder.setGravity(Gravity.CENTER);screen.addView(placeholder,new FrameLayout.LayoutParams(-1,-1));
        preview.setOnPreparedListener(this::onVideoPrepared);
        preview.setOnCompletionListener(player->{if(looping&&prepared){seekPlayer(beginMs);preview.start();}else playIcon.select(false);});
        preview.setOnErrorListener((player,what,extra)->{prepared=false;currentPlayer=null;placeholder.setVisibility(View.VISIBLE);placeholder.setText("Видео недоступно\nВыберите файл повторно");setStatus("Не удалось открыть видео. Выберите файл повторно через File → Open video.");updateEnabled();return true;});
        LinearLayout previewTransport=row(PANEL);previewTransport.setGravity(Gravity.CENTER);previewPane.addView(previewTransport,lp(-1,32));
        tool(previewTransport,"begin","К началу выделения",()->seek(beginMs),true,28);
        tool(previewTransport,"back","Предыдущий кадр",()->step(-1),true,28);
        playIcon=tool(previewTransport,"play","Воспроизвести / пауза",this::togglePreview,true,28);
        tool(previewTransport,"pause","Пауза",()->preview.pause(),true,28);
        tool(previewTransport,"stop","Остановить",()->seek(beginMs),true,28);
        tool(previewTransport,"forward","Следующий кадр",()->step(1),true,28);
        tool(previewTransport,"end","К концу выделения",()->seek(endMs),true,28);
        previewMeta=text("Project: —\nPreview: —",9,TEXT);previewMeta.setPadding(dp(6),0,0,0);previewPane.addView(previewMeta,lp(-1,29));
        masterPane=column(PANEL);masterPane.setBackground(border(PANEL));docking.addView(masterPane,lp(wide?70:0,-1));
        masterPane.addView(text("Master",10,TEXT),lp(-1,26));TextView meterCaption=text("Preview",8,TEXT);meterCaption.setGravity(Gravity.CENTER);masterPane.addView(meterCaption,lp(-1,20));
        LinearLayout bus=row(PANEL);masterPane.addView(bus,new LinearLayout.LayoutParams(-1,0,1));
        fader=new VerticalFader(this);fader.gain(previewGain);fader.listener(value->{previewGain=value;applyVolume();updateVolume();});bus.addView(fader,lp(21,-1));
        meter=new MasterMeter(this);bus.addView(meter,new LinearLayout.LayoutParams(0,-1,1));
        volumeLabel=text("0.0 dB",9,TEXT);volumeLabel.setGravity(Gravity.CENTER);masterPane.addView(volumeLabel,lp(-1,23));
        muteIcon=tool(masterPane,"mute","Отключить звук исходного ролика",this::toggleMute,true,28);
        timeline=new TimelineView(this);timeline.setBackground(border(0xff898989));root.addView(timeline,new LinearLayout.LayoutParams(-1,0,.48f));
        timeline.setListener(new TimelineView.Listener(){
            @Override public void seek(int millis){MainActivity.this.seek(millis);}
            @Override public void editBegin(){gestureBefore=snapshot();preview.pause();}
            @Override public void range(int start,int end){setRange(start,end);}
            @Override public void editEnd(){if(gestureBefore!=null){history.record(gestureBefore,snapshot());gestureBefore=null;updateEnabled();}}
            @Override public void mute(){toggleMute();}
            @Override public void preciseRange(){MainActivity.this.preciseRange();}
        });
        selectionLabel=text("Loop: 00:00:00.000 — 00:00:00.000",10,TEXT);selectionLabel.setPadding(dp(6),0,0,0);selectionLabel.setSingleLine();selectionLabel.setEllipsize(TextUtils.TruncateAt.END);selectionLabel.setOnClickListener(v->preciseRange());root.addView(selectionLabel,lp(-1,23));
        HorizontalScrollView bottomScroll=horizontalScroll();LinearLayout transport=row(0xffa2a2a2);bottomScroll.addView(transport);root.addView(bottomScroll,lp(-1,36));
        loopIcon=tool(transport,"loop","Зациклить выделенный фрагмент",()->{looping=!looping;loopIcon.select(looping);},false);
        tool(transport,"begin","К началу",()->seek(beginMs),true);
        tool(transport,"play","Воспроизвести",this::togglePreview,true);tool(transport,"pause","Пауза",()->preview.pause(),true);tool(transport,"stop","Стоп",()->seek(beginMs),true);
        tool(transport,"end","К концу",()->seek(endMs),true);separator(transport);
        tool(transport,"select","Выделение / точные границы",this::preciseRange,true);
        tool(transport,"marker","Маркер",this::addMarker,true);
        tool(transport,"zoomOut","Уменьшить масштаб",()->timeline.zoom(.66f),true);
        tool(transport,"zoomIn","Увеличить масштаб",()->timeline.zoom(1.5f),true);tool(transport,"fit","Весь ролик",()->timeline.fit(),true);separator(transport);
        TextView rate=text("Rate: 1.00 ▾",10,TEXT);rate.setPadding(dp(6),0,dp(6),0);transport.addView(rate,lp(-2,36));rate.setOnClickListener(v->speedMenu(rate));rate.setTooltipText("Скорость только предпросмотра; экспорт 1×");
        cancelIcon=tool(transport,"stop","Отменить экспорт",()->job.cancel(),false);cancelIcon.setVisibility(View.GONE);
        exportProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);exportProgress.setMax(100);root.addView(exportProgress,lp(-1,4));
        status=text("File → Open video: импортируйте ролик для монтажа.",10,TEXT);status.setSingleLine();status.setEllipsize(TextUtils.TruncateAt.END);status.setPadding(dp(6),0,dp(6),0);status.setBackground(border(0xffb2b2b2));status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);status.setOnClickListener(v->message("Состояние",status.getText().toString()));root.addView(status,lp(-1,25));
        loopIcon.select(looping);updateVolume();renderLibrary();updateEnabled();updateSelection();
    }
    private void renderLibrary(){
        if(libraryContent==null)return;libraryContent.removeAllViews();
        for(int i=0;i<tabLabels.size();i++){TextView t=tabLabels.get(i);t.setBackground(border(i==selectedTab?0xffbcbcbc:PANEL));t.setTypeface(null,i==selectedTab?Typeface.BOLD:Typeface.NORMAL);}
        if(selectedTab==0){
            LinearLayout body=row(0xffababab);libraryContent.addView(body,new FrameLayout.LayoutParams(-1,-1));
            boolean wide=getResources().getConfiguration().screenWidthDp>=640;
            LinearLayout tree=column(0xffa3a3a3);body.addView(tree,lp(wide?95:48,-1));
            String[] nodes={"▾ All Media","▾ Media Bins","  Video","  Audio","▸ Tags","▸ Smart Bins"};
            for(String s:nodes){TextView node=text(s,wide?10:8,TEXT);node.setSingleLine();node.setEllipsize(TextUtils.TruncateAt.END);node.setPadding(dp(3),0,0,0);tree.addView(node,lp(-1,23));node.setOnClickListener(v->{if(videoUri==null)pickVideo();else properties();});}
            ScrollView list=new ScrollView(this);body.addView(list,new LinearLayout.LayoutParams(0,-1,1));LinearLayout files=column(0xffb0b0b0);files.setPadding(dp(6),dp(6),dp(6),dp(6));list.addView(files);
            if(videoUri!=null){
                ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setBackgroundColor(0xff555555);
                if(mediaInfo!=null&&mediaInfo.frames!=null&&mediaInfo.frames.length>0&&mediaInfo.frames[0]!=null)image.setImageBitmap(mediaInfo.frames[0]);
                files.addView(image,lp(-1,wide?85:58));image.setOnClickListener(v->seek(beginMs));
                TextView name=text(mediaName,10,TEXT);name.setGravity(Gravity.CENTER);name.setMaxLines(3);files.addView(name,lp(-1,-2));
                files.addView(text(mediaInfo==null?"Медиа загружается…":mediaInfo.width+" × "+mediaInfo.height+"\n"+ClipMath.clock(durationMs),9,0xff444444),lp(-1,-2));
            }else{
                TextView importText=text("＋\nImport Media…",11,0xff314e74);importText.setGravity(Gravity.CENTER);files.addView(importText,lp(-1,100));importText.setOnClickListener(v->pickVideo());
            }
        }else if(selectedTab==1){
            LinearLayout explorer=column(0xffa6a6a6);explorer.setPadding(dp(8),dp(6),dp(8),0);libraryContent.addView(explorer);
            TextView choose=text("▸  Открыть файл…",12,0xff284c7a);explorer.addView(choose,lp(-1,38));choose.setOnClickListener(v->pickVideo());
            TextView project=text("▸  Открыть проект…",11,TEXT);explorer.addView(project,lp(-1,38));project.setOnClickListener(v->pickProject());
            explorer.addView(text("Документы и накопители\nчерез проводник Android",10,0xff474747),lp(-1,-2));
        }else{
            LinearLayout palette=column(0xffa6a6a6);palette.setPadding(dp(7),dp(4),dp(7),0);ScrollView scroll=new ScrollView(this);scroll.addView(palette);libraryContent.addView(scroll);
            String[] names=selectedTab==2?new String[]{"Dissolve","Cross Effect","Wipe","3D Cascade"}:selectedTab==3?new String[]{"Brightness and Contrast","Color Corrector","Chroma Keyer","Gaussian Blur"}:new String[]{"Solid Color","Titles & Text","Checkerboard","Color Gradient"};
            for(String n:names){TextView t=text(n,10,0xff686868);t.setBackground(border(0xffababab));palette.addView(t,lp(-1,31));}
            palette.addView(text("Панель интерфейса.\nПрименение пока недоступно.",10,0xff383838),lp(-1,-2));
        }
    }
    private void showMenu(View anchor,String name){
        PopupMenu popup=new PopupMenu(this,anchor);Menu m=popup.getMenu();
        if(name.equals("File")){
            item(m,"New",!job.running,this::newProject);item(m,"Timeline editor…",!job.running,this::openSequence);item(m,"Open video…",!job.running,this::pickVideo);item(m,"Open project…",!job.running,this::pickProject);
            item(m,"Save project",prepared,this::saveLocalProject);item(m,"Save project as…",prepared,this::saveProjectAs);
            item(m,"Render As…",prepared&&!job.running,this::renderDialog);item(m,"Open rendered video",lastExport!=null,()->openResult(false));item(m,"Share rendered video",lastExport!=null,()->openResult(true));
        }else if(name.equals("Edit")){
            item(m,"Undo",history.canUndo()&&!job.running,()->applyHistory(false));item(m,"Redo",history.canRedo()&&!job.running,()->applyHistory(true));
            item(m,"Select all",prepared&&!job.running,()->{EditHistory.State old=snapshot();setRange(0,durationMs);history.record(old,snapshot());updateEnabled();});
            item(m,"Set loop region…",prepared&&!job.running,this::preciseRange);item(m,"Timeline editor — Split / Delete / Move…",!job.running,this::openSequence);
        }else if(name.equals("View")){
            item(m,"Project Media",true,()->{selectedTab=0;libraryVisible=true;libraryPane.setVisibility(View.VISIBLE);renderLibrary();});
            item(m,libraryVisible?"Hide docking area":"Show docking area",true,()->{libraryVisible=!libraryVisible;libraryPane.setVisibility(libraryVisible?View.VISIBLE:View.GONE);});
            item(m,"Explorer",true,()->selectTab(1));item(m,"Transitions",true,()->selectTab(2));item(m,"Video FX",true,()->selectTab(3));item(m,"Media Generators",true,()->selectTab(4));item(m,"Zoom to project",prepared,()->timeline.fit());
        }else if(name.equals("Insert")){
            item(m,"Marker",prepared&&!job.running,this::addMarker);item(m,"Markers…",!markers.isEmpty(),this::markerList);item(m,"Video track",false,null);item(m,"Audio track",false,null);
        }else if(name.equals("Tools")){
            item(m,"Render As…",prepared&&!job.running,this::renderDialog);item(m,"Media properties…",prepared,this::properties);item(m,"Mute audio",prepared&&!job.running,this::toggleMute);item(m,"Cancel rendering",job.running,()->job.cancel());
        }else if(name.equals("Options")){
            item(m,(looping?"✓ ":"")+"Loop playback",true,()->{looping=!looping;loopIcon.select(looping);});
            item(m,"Preview volume…",prepared,this::volumeDialog);item(m,"Select loop region…",prepared&&!job.running,this::preciseRange);
        }else{item(m,"Help",true,this::help);item(m,"About…",true,()->message("Sony Vegas Video 2.3","Личный Android-видеоредактор.\nКлассическая компоновка Sony Vegas Pro 13.\n\nНеофициальное приложение; не продукт Sony/VEGAS."));}
        popup.show();
    }
    private void selectTab(int index){selectedTab=index;libraryVisible=true;libraryPane.setVisibility(View.VISIBLE);renderLibrary();}
    private void item(Menu menu,String name,boolean enabled,Runnable action){menu.add(name).setEnabled(enabled).setOnMenuItemClickListener(i->{if(action!=null)action.run();return true;});}
    private void openSequence(){
        if(job.running)return;
        Intent intent=new Intent(this,SequenceActivity.class);
        if(prepared&&videoUri!=null){intent.putExtra("source",videoUri.toString());intent.putExtra("name",mediaName);intent.putExtra("start",beginMs);intent.putExtra("end",endMs);}
        startActivity(intent);
    }
    private void pickVideo(){
        if(job.running)return;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);launch(i,PICK_VIDEO);
    }
    private void launch(Intent intent,int code){try{startActivityForResult(intent,code);}catch(ActivityNotFoundException e){setStatus("Не найден проводник Android.");}}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();
        if(request==PICK_VIDEO&&!job.running){
            boolean grant=false;try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);grant=true;}catch(SecurityException ignored){}
            // Keep document grants: saved projects may still refer to earlier imported files.
            persistedGrant=grant;beginMs=0;restoreEnd=-1;restorePosition=0;markers.clear();history.clear();openVideo(uri);
        }else if(request==SAVE_PROJECT){
            try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new java.io.IOException("Не удалось создать проект");out.write(pendingProject.getBytes(StandardCharsets.UTF_8));setStatus("Проект сохранён.");}catch(Exception e){setStatus("Ошибка сохранения проекта: "+e.getMessage());}
        }else if(request==OPEN_PROJECT&&!job.running){
            try(InputStream in=getContentResolver().openInputStream(uri)){
                if(in==null)throw new java.io.IOException("Не удалось открыть проект");java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
                while((n=in.read(buffer))>=0){bytes.write(buffer,0,n);if(bytes.size()>65536)throw new java.io.IOException("Слишком большой файл проекта");}
                JSONObject project=new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
                if(!"VegasVideoProject".equals(project.getString("format")))throw new java.io.IOException("Поддерживаются проекты Vegas Video .vvp");
                Uri source=Uri.parse(project.getString("source"));if(!"content".equals(source.getScheme()))throw new java.io.IOException("Недопустимый путь к видео");
                beginMs=Math.max(0,project.getInt("start"));restoreEnd=project.getInt("end");restorePosition=beginMs;muted=project.optBoolean("muted",false);
                persistedGrant=hasGrant(source);markers.clear();JSONArray a=project.optJSONArray("markers");if(a!=null)for(int j=0;j<Math.min(64,a.length());j++)markers.add(a.getInt(j));history.clear();openVideo(source);
            }catch(Exception e){setStatus("Не удалось открыть проект: "+e.getMessage());}
        }
    }
    private boolean hasGrant(Uri uri){for(android.content.UriPermission permission:getContentResolver().getPersistedUriPermissions())if(permission.getUri().equals(uri)&&permission.isReadPermission())return true;return false;}
    private void releaseGrant(){try{getContentResolver().releasePersistableUriPermission(videoUri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}}
    private void openVideo(Uri uri){
        videoUri=uri;durationMs=0;prepared=false;currentPlayer=null;mediaInfo=null;mediaName="Video";placeholder.setText("Загрузка видео…");placeholder.setVisibility(View.VISIBLE);updateEnabled();renderLibrary();
        timeline.media(0,"",false,null,null);timeline.selection(0,0,muted);timeline.position(0);
        setStatus("Загрузка медиа…");try{preview.setVideoURI(uri);}catch(RuntimeException e){setStatus("Не удалось открыть файл. Выберите его повторно.");}
        probe.load(this,uri,info->{
            if(!uri.equals(videoUri)||isDestroyed())return;mediaInfo=info;if(info.name!=null)mediaName=info.name;
            windowTitle.setText(mediaName+" — Sony Vegas Video");sourceLabel.setText(mediaName);
            previewMeta.setText("Project: "+info.width+" × "+info.height+(info.fps>0?" · "+info.fps+" fps":"")+"\nPreview: Auto · "+info.codec+(info.hasAudio?" / "+info.audioCodec:""));
            timeline.media(durationMs,mediaName,info.hasAudio,info.frames,info.peaks);updateSelection();renderLibrary();updateEnabled();
        });
    }
    private void onVideoPrepared(MediaPlayer player){
        currentPlayer=player;durationMs=Math.max(0,player.getDuration());prepared=durationMs>0;
        beginMs=ClipMath.clamp(beginMs,0,Math.max(0,durationMs-1));endMs=restoreEnd<0?durationMs:ClipMath.clamp(restoreEnd,beginMs+1,durationMs);
        markers.removeIf(value->value<0||value>durationMs);
        applyVolume();applyRate();seekPlayer(ClipMath.clamp(restorePosition,0,Math.max(0,durationMs-1)));placeholder.setVisibility(prepared?View.GONE:View.VISIBLE);
        timeline.media(durationMs,mediaName,mediaInfo==null||mediaInfo.hasAudio,mediaInfo==null?null:mediaInfo.frames,mediaInfo==null?null:mediaInfo.peaks);
        timeline.markers(markers);updateSelection();updateEnabled();
        if(!job.running)setStatus(prepared?"Готово. Выделение: золотые ручки; Render As… — экспорт MP4.":"Длительность видео недоступна.");
    }
    private void setRange(int start,int end){if(!prepared||job.running)return;beginMs=ClipMath.clamp(start,0,durationMs-1);endMs=ClipMath.clamp(end,beginMs+1,durationMs);restoreEnd=endMs;updateSelection();}
    private void updateSelection(){if(timeline==null)return;if(videoUri!=null)windowTitle.setText(mediaName+((beginMs>0||endMs<durationMs||muted)?" *":"")+" — Sony Vegas Video");timeline.selection(beginMs,endMs,muted);selectionLabel.setText("Loop: "+ClipMath.clockCode(beginMs)+" — "+ClipMath.clockCode(endMs)+"    Length: "+ClipMath.clockCode(Math.max(0,endMs-beginMs)));muteIcon.select(muted);}
    private void togglePreview(){if(!prepared||job.running)return;if(preview.isPlaying())preview.pause();else{int p=preview.getCurrentPosition();if(p<beginMs||p>=endMs)seekPlayer(beginMs);preview.start();applyRate();}}
    private void seek(int position){if(!prepared||job.running)return;preview.pause();restorePosition=ClipMath.clamp(position,0,durationMs);seekPlayer(restorePosition);timeline.position(restorePosition);playIcon.select(false);}
    private void seekPlayer(int position){if(currentPlayer!=null)try{currentPlayer.seekTo((long)position,MediaPlayer.SEEK_CLOSEST);return;}catch(IllegalStateException ignored){}preview.seekTo(position);}
    private void step(int direction){float fps=mediaInfo!=null&&mediaInfo.fps>0?mediaInfo.fps:30;seek(preview.getCurrentPosition()+Math.round(1000/fps)*direction);}
    private EditHistory.State snapshot(){return new EditHistory.State(beginMs,endMs,muted);}
    private void toggleMute(){if(!prepared||job.running)return;EditHistory.State before=snapshot();muted=!muted;applyVolume();updateSelection();history.record(before,snapshot());updateEnabled();}
    private void applyHistory(boolean redo){if(!prepared||job.running)return;EditHistory.State value=redo?history.redo(snapshot()):history.undo(snapshot());muted=value.muted;setRange(value.start,value.end);applyVolume();updateEnabled();}
    private void applyVolume(){if(currentPlayer!=null)try{float gain=muted?0:previewGain;currentPlayer.setVolume(gain,gain);}catch(IllegalStateException ignored){}}
    private void updateVolume(){fader.gain(previewGain);volumeLabel.setText(previewGain==0?"−∞ dB":String.format(java.util.Locale.ROOT,"%.1f dB",20*Math.log10(previewGain)));}
    private void applyRate(){if(currentPlayer!=null)try{boolean playing=preview.isPlaying();currentPlayer.setPlaybackParams(currentPlayer.getPlaybackParams().setSpeed(playbackRate));if(!playing)currentPlayer.pause();}catch(RuntimeException e){playbackRate=1;}}
    private void speedMenu(TextView label){PopupMenu popup=new PopupMenu(this,label);for(float speed:new float[]{.5f,1,1.5f,2})item(popup.getMenu(),speed+"×",prepared,()->{boolean playing=preview.isPlaying();playbackRate=speed;applyRate();if(!playing)preview.pause();label.setText(String.format(java.util.Locale.ROOT,"Rate: %.2f ▾",playbackRate));});popup.show();}
    private void volumeDialog(){final EditText value=new EditText(this);value.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);value.setText(Integer.toString(Math.round(previewGain*100)));new AlertDialog.Builder(this).setTitle("Громкость предпросмотра, %").setView(value).setPositiveButton("OK",(d,w)->{try{previewGain=ClipMath.clamp(Integer.parseInt(value.getText().toString()),0,100)/100f;applyVolume();updateVolume();}catch(NumberFormatException ignored){}}).setNegativeButton("Отмена",null).show();}
    private void preciseRange(){
        if(!prepared||job.running)return;LinearLayout form=column(CHROME);form.setPadding(dp(16),dp(8),dp(16),dp(8));
        form.addView(text("Начало · ЧЧ:ММ:СС.мс",12,TEXT));EditText start=new EditText(this);start.setText(ClipMath.clockCode(beginMs));start.setSingleLine();form.addView(start);
        form.addView(text("Конец · ЧЧ:ММ:СС.мс",12,TEXT));EditText end=new EditText(this);end.setText(ClipMath.clockCode(endMs));end.setSingleLine();form.addView(end);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Loop region").setView(form).setPositiveButton("OK",null).setNegativeButton("Отмена",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{int a=ClipMath.parseClock(start.getText().toString()),b=ClipMath.parseClock(end.getText().toString());if(!ClipMath.validRange(a,b)||b>durationMs)throw new IllegalArgumentException("Укажите границы внутри ролика, конец позже начала.");EditHistory.State old=snapshot();setRange(a,b);history.record(old,snapshot());seek(a);updateEnabled();dialog.dismiss();}catch(Exception e){end.setError(e.getMessage());}}));dialog.show();
    }
    private void addMarker(){if(!prepared||job.running)return;if(markers.size()>=64){setStatus("Максимум 64 маркера.");return;}int value=preview.getCurrentPosition();if(!markers.contains(value)){markers.add(value);java.util.Collections.sort(markers);timeline.markers(markers);}setStatus("Маркер: "+ClipMath.clockCode(value));}
    private void markerList(){String[] names=new String[markers.size()];for(int i=0;i<names.length;i++)names[i]=(i+1)+" · "+ClipMath.clockCode(markers.get(i));new AlertDialog.Builder(this).setTitle("Markers").setItems(names,(d,w)->seek(markers.get(w))).setNeutralButton("Удалить маркеры",(d,w)->{markers.clear();timeline.markers(markers);}).setNegativeButton("Закрыть",null).show();}
    private void renderDialog(){if(!prepared||job.running)return;new AlertDialog.Builder(this).setTitle("Render As…").setMessage("MP4 · Без перекодирования\n\nLoop region: "+ClipMath.clockCode(beginMs)+" — "+ClipMath.clockCode(endMs)+"\nAudio: "+(muted?"Muted":"Source")+"\n\nMovies / Sony Vegas Video\nГраницы округляются к ключевым кадрам.").setPositiveButton("Render",(d,w)->saveClip()).setNeutralButton("Границы…",(d,w)->preciseRange()).setNegativeButton("Отмена",null).show();}
    private void saveClip(){if(!prepared||job.running||videoUri==null)return;preview.pause();job.start(this,videoUri,beginMs*1000L,endMs*1000L,muted);}
    private void renderJob(){
        if(exportProgress==null)return;exportProgress.setVisibility(job.running?View.VISIBLE:View.GONE);exportProgress.setProgress(job.percent);cancelIcon.setVisibility(job.running?View.VISIBLE:View.GONE);
        if(!job.message.isEmpty())setStatus(job.message+(job.running?"  "+job.percent+"%":""));
        if(job.result!=null){lastExport=job.result.uri;preferences.edit().putString("export",lastExport.toString()).apply();}
        if(exportWasRunning&&!job.running&&!isFinishing()){
            if(job.result!=null)new AlertDialog.Builder(this).setTitle("Rendering complete").setMessage(job.message).setPositiveButton("Открыть",(d,w)->openResult(false)).setNeutralButton("Поделиться",(d,w)->openResult(true)).setNegativeButton("Закрыть",null).show();
            else message("Rendering",job.message);
        }
        exportWasRunning=job.running;updateEnabled();
    }
    private void updateEnabled(){
        boolean ready=prepared&&!job.running;for(View v:mediaControls)v.setEnabled(ready);if(muteIcon!=null)muteIcon.setEnabled(ready&&(mediaInfo==null||mediaInfo.hasAudio));
        if(openIcon==null)return;openIcon.setEnabled(!job.running);undoIcon.setEnabled(ready&&history.canUndo());redoIcon.setEnabled(ready&&history.canRedo());
        if(timeline!=null)timeline.setEnabled(ready);if(cancelIcon!=null)cancelIcon.setEnabled(job.running);
    }
    private void openResult(boolean share){if(lastExport==null)return;Intent i=new Intent(share?Intent.ACTION_SEND:Intent.ACTION_VIEW);if(share){i.setType("video/mp4");i.putExtra(Intent.EXTRA_STREAM,lastExport);}else i.setDataAndType(lastExport,"video/mp4");i.setClipData(android.content.ClipData.newRawUri("Vegas Video",lastExport));i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);try{startActivity(share?Intent.createChooser(i,"Поделиться видео"):i);}catch(ActivityNotFoundException|SecurityException e){setStatus("Не удалось открыть результат. Проверьте файл в Галерее.");}}
    private void newProject(){if(job.running)return;probe.cancel();preview.stopPlayback();videoUri=null;currentPlayer=null;prepared=false;persistedGrant=false;durationMs=beginMs=endMs=restorePosition=0;restoreEnd=-1;mediaInfo=null;mediaName="Untitled";muted=false;history.clear();markers.clear();timeline.markers(markers);timeline.media(0,"",false,null,null);timeline.position(0);placeholder.setText("Video Preview\n\nОткройте видео");placeholder.setVisibility(View.VISIBLE);windowTitle.setText("Untitled — Sony Vegas Video");sourceLabel.setText("Project Media");previewMeta.setText("Project: —\nPreview: —");updateSelection();renderLibrary();updateEnabled();savePreferences();setStatus("Новый проект. File → Open video…");}
    private void saveLocalProject(){capture();savePreferences();setStatus(persistedGrant?"Проект сохранён. File → Save project as… — сохранить отдельный .vvp.":"Настройки сохранены. Для доступа после перезапуска выберите файл через проводник с постоянным доступом.");}
    private String projectJson()throws Exception{JSONObject object=new JSONObject();object.put("format","VegasVideoProject");object.put("version",1);object.put("source",videoUri.toString());object.put("name",mediaName);object.put("start",beginMs);object.put("end",endMs);object.put("muted",muted);object.put("markers",new JSONArray(markers));return object.toString(2);}
    private void saveProjectAs(){if(videoUri==null)return;try{pendingProject=projectJson();Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"VegasVideo.vvp");launch(i,SAVE_PROJECT);}catch(Exception e){setStatus(e.getMessage());}}
    private void pickProject(){if(job.running)return;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");launch(i,OPEN_PROJECT);}
    private void properties(){if(mediaInfo==null){message("Media properties","Свойства ещё загружаются.");return;}message("Media properties",mediaName+"\n\nVideo: "+mediaInfo.width+" × "+mediaInfo.height+" · "+mediaInfo.codec+(mediaInfo.fps>0?" · "+mediaInfo.fps+" fps":"")+"\nDuration: "+ClipMath.clockCode(durationMs)+"\nAudio: "+(mediaInfo.hasAudio?mediaInfo.audioCodec+" · "+mediaInfo.channels+" ch":"None")+"\nWaveform: "+(mediaInfo.waveformComplete?"Decoded PCM":"Недоступна / анализируется")+"\n\nExport: MP4, исходный кодек, без перекодирования.");}
    private void help(){message("Vegas Video — управление","File → Open video — импорт.\nЗолотые ручки на линейке — границы фрагмента.\nКасание таймлайна — перемотка.\nДва пальца — масштаб; нижний скроллбар — прокрутка.\nДолгое нажатие — точный ввод границ.\nM на аудиодорожке — отключить звук.\nUndo / Redo — отменить / повторить правки.\nRender As… — сохранить MP4.\n\nДля монтажа нескольких фрагментов откройте Timeline editor через File или кнопку ножниц. Панели Transitions / Video FX / Media Generators воспроизводят компоновку, применение эффектов ещё недоступно. Громкость Master и Rate меняют только предпросмотр.");}
    private void message(String title,String body){new AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("OK",null).show();}
    private void capture(){if(prepared){restorePosition=preview.getCurrentPosition();restoreEnd=endMs;}}
    private void savePreferences(){JSONArray a=new JSONArray(markers);preferences.edit().putString("uri",persistedGrant&&videoUri!=null?videoUri.toString():null).putBoolean("persisted",persistedGrant).putInt("start",beginMs).putInt("end",restoreEnd).putInt("position",restorePosition).putBoolean("mute",muted).putBoolean("loop",looping).putFloat("gain",previewGain).putBoolean("library",libraryVisible).putInt("tab",selectedTab).putString("markers",a.toString()).apply();}
    @Override protected void onSaveInstanceState(Bundle out){capture();if(videoUri!=null)out.putString("uri",videoUri.toString());out.putString("pendingProject",pendingProject);out.putInt("start",beginMs);out.putInt("end",restoreEnd);out.putInt("position",restorePosition);out.putBoolean("persisted",persistedGrant);out.putBoolean("mute",muted);out.putBoolean("loop",looping);out.putFloat("gain",previewGain);out.putBoolean("library",libraryVisible);out.putInt("tab",selectedTab);int[] a=new int[markers.size()];for(int i=0;i<a.length;i++)a[i]=markers.get(i);out.putIntArray("markers",a);super.onSaveInstanceState(out);}
    @Override protected void onStart(){super.onStart();job.attach(jobListener);}
    @Override protected void onResume(){super.onResume();handler.post(tick);}
    @Override protected void onPause(){handler.removeCallbacks(tick);capture();preview.pause();meter.signal(0);savePreferences();super.onPause();}
    @Override protected void onStop(){job.detach(jobListener);super.onStop();}
    @Override protected void onDestroy(){handler.removeCallbacks(tick);probe.stop();currentPlayer=null;preview.stopPlayback();super.onDestroy();}
    @Override public boolean onKeyDown(int key,android.view.KeyEvent event){
        if(getCurrentFocus() instanceof EditText)return super.onKeyDown(key,event);
        if(event.isCtrlPressed()){
            if(key==android.view.KeyEvent.KEYCODE_Z){applyHistory(event.isShiftPressed());return true;}
            if(key==android.view.KeyEvent.KEYCODE_Y){applyHistory(true);return true;}
            if(key==android.view.KeyEvent.KEYCODE_O){pickVideo();return true;}
            if(key==android.view.KeyEvent.KEYCODE_S){saveLocalProject();return true;}
        }
        if(key==android.view.KeyEvent.KEYCODE_SPACE){togglePreview();return true;}
        if(key==android.view.KeyEvent.KEYCODE_M){addMarker();return true;}
        if(key==android.view.KeyEvent.KEYCODE_DPAD_LEFT){step(-1);return true;}
        if(key==android.view.KeyEvent.KEYCODE_DPAD_RIGHT){step(1);return true;}
        if(key==android.view.KeyEvent.KEYCODE_HOME){seek(beginMs);return true;}
        if(key==android.view.KeyEvent.KEYCODE_MOVE_END){seek(endMs);return true;}
        return super.onKeyDown(key,event);
    }
    private void setStatus(String message){if(message==null)return;status.setText(message.replace('\n',' '));status.setTooltipText(message);}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private LinearLayout.LayoutParams lp(int width,int height){return new LinearLayout.LayoutParams(width<0?width:dp(width),height<0?height:dp(height));}
    private LinearLayout row(int color){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setBackgroundColor(color);return l;}
    private LinearLayout column(int color){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);l.setBackgroundColor(color);return l;}
    private TextView text(String text,int size,int color){TextView t=new TextView(this);t.setText(text);t.setTextColor(color);t.setTextSize(size);t.setGravity(Gravity.CENTER_VERTICAL);t.setIncludeFontPadding(false);return t;}
    private HorizontalScrollView horizontalScroll(){HorizontalScrollView s=new HorizontalScrollView(this);s.setHorizontalScrollBarEnabled(false);s.setFillViewport(false);return s;}
    private GradientDrawable border(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setStroke(dp(1),0xff707070);return d;}
    private void separator(LinearLayout parent){View line=new View(this);line.setBackgroundColor(0xff787878);LinearLayout.LayoutParams p=lp(1,24);p.gravity=Gravity.CENTER_VERTICAL;p.setMargins(dp(3),0,dp(3),0);parent.addView(line,p);}
    private ClassicIcon tool(LinearLayout parent,String kind,String description,Runnable action,boolean needsMedia){return tool(parent,kind,description,action,needsMedia,36);}
    private ClassicIcon tool(LinearLayout parent,String kind,String description,Runnable action,boolean needsMedia,int size){ClassicIcon v=new ClassicIcon(this,kind,description);parent.addView(v,lp(size,size));if(action!=null)v.setOnClickListener(view->action.run());if(needsMedia)mediaControls.add(v);return v;}
}
