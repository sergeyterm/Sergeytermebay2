package com.personal.sonyvegasvideo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
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
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Classic Sony-era workspace with native Android media playback and remux export. */
public final class MainActivity extends Activity {
    private static final int PICK_VIDEO=110,SAVE_PROJECT=120,OPEN_PROJECT=121,PICK_AUDIO=122,PICK_OVERLAY=123;
    private static final int PANEL=EditorStyle.SURFACE,CHROME=EditorStyle.BACKGROUND,TEXT=EditorStyle.TEXT;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExportJob job=ExportJob.get();
    private final ExportJob.Listener jobListener=this::renderJob;
    private final MediaProbe probe=new MediaProbe();
    private final EditHistory history=new EditHistory();
    private final List<View> mediaControls=new ArrayList<>();
    private final List<Integer> markers=new ArrayList<>();
    private final List<TextView> tabLabels=new ArrayList<>();
    private EditorPreview preview;
    private EditorPreview currentPlayer;
    private EffectSpec effects=new EffectSpec();
    private final java.util.concurrent.ExecutorService files=java.util.concurrent.Executors.newSingleThreadExecutor();
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
    private String mediaName="Untitled",pendingProject="",handledGeneration="";
    private TextView rateLabel;
    private MediaProbe.Info mediaInfo;
    private SharedPreferences preferences;
    private EditHistory.State gestureBefore;
    private int sourceGeneration;
    private float lastVideoOpacity=1;
    private final Runnable rangePreview=()->{if(preview!=null&&prepared)preview.setEffectRange(beginMs,endMs);};

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
        playbackRate=saved.getFloat("rate",1);
        try{String encoded=saved.getString("effects","{}");effects=ProjectCodec.readEffects(new JSONObject(encoded));if(encoded.equals("{}"))effects.masterGain=previewGain;previewGain=effects.masterGain;}catch(Exception ignored){effects=new EffectSpec();effects.masterGain=previewGain;}
        if(state!=null)history.restore(decodeHistory(state.getStringArrayList("undoHistory")),decodeHistory(state.getStringArrayList("redoHistory")));
        if(state==null)try{handledGeneration=preferences.getString("handledGeneration","");}catch(Exception ignored){}
        if(state==null&&Intent.ACTION_VIEW.equals(getIntent().getAction())&&getIntent().getData()!=null
                &&"content".equals(getIntent().getData().getScheme())){
            source=getIntent().getData().toString();beginMs=0;restoreEnd=-1;restorePosition=0;muted=false;markers.clear();effects=new EffectSpec();previewGain=1;
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
        b.putString("effects",preferences.getString("effects","{}"));b.putFloat("rate",preferences.getFloat("rate",1));
        try{JSONArray a=new JSONArray(preferences.getString("markers","[]"));int[] values=new int[Math.min(64,a.length())];for(int i=0;i<values.length;i++)values[i]=a.getInt(i);b.putIntArray("markers",values);}catch(Exception ignored){}
        return b;
    }
    private void buildUi(){
        getWindow().setStatusBarColor(CHROME);getWindow().setNavigationBarColor(CHROME);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout root=column(CHROME);root.setOnApplyWindowInsetsListener((v,insets)->{root.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        setContentView(root);
        windowTitle=text("Untitled — Sony Vegas Video",16,TEXT);
        EditorStyle.heading(windowTitle);
        windowTitle.setPadding(dp(12),0,dp(12),0);
        root.addView(windowTitle,lp(-1,40));
        HorizontalScrollView menuScroll=horizontalScroll();LinearLayout menuRow=row(CHROME);
        String[][] menus = {{"File", "open"}, {"Edit", "cut"}, {"View", "view"}, {"Insert", "plus"},
                {"Эффекты", "effects"}, {"Tools", "settings"}, {"Options", "sliders"}, {"Help", "help"}};
        for (String[] entry : menus) {
            String name = entry[0];
            TextView item = text(name, 12, TEXT);
            EditorStyle.heading(item);
            EditorStyle.touch(item, name.equals("Эффекты") ? EditorStyle.TONAL : Color.TRANSPARENT, 11, 2);
            item.setPadding(dp(6), 0, dp(6), 0);
            ClassicIcon.decorate(item, entry[1], 14);
            menuRow.addView(item, lp(-2, 36));
            item.setOnClickListener(v -> showMenu(item, name));
        }
        menuRow.setPadding(dp(4),0,dp(4),0);menuScroll.addView(menuRow);root.addView(menuScroll,lp(-1,36));
        LinearLayout toolbar=row(PANEL);toolbar.setPadding(dp(4),0,dp(4),0);
        HorizontalScrollView toolbarScroll=horizontalScroll();toolbarScroll.addView(toolbar);
        root.addView(toolbarScroll,lp(-1,40));
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
        separator(toolbar);tool(toolbar,"settings","Настройки эффектов",()->effectDialog("effects"),true);
        tool(toolbar,"loop","Настройки переходов",()->effectDialog("transitions"),true);
        tool(toolbar,"marker","Текст и титры",()->effectDialog("title"),true);
        tool(toolbar,"settings","Слои видео и аудио",()->effectDialog("layers"),true);

        boolean wide=getResources().getConfiguration().screenWidthDp>=640;
        LinearLayout docking=row(CHROME);docking.setPadding(dp(6),dp(6),dp(6),dp(6));
        root.addView(docking,new LinearLayout.LayoutParams(-1,0,.52f));
        libraryPane=column(PANEL);EditorStyle.panel(libraryPane);
        libraryPane.setPadding(dp(3),dp(2),dp(3),dp(3));
        docking.addView(libraryPane,new LinearLayout.LayoutParams(0,-1,wide?.42f:.34f));
        LinearLayout libraryTools=row(Color.TRANSPARENT);libraryPane.addView(libraryTools,lp(-1,34));
        tool(libraryTools,"open","Импортировать медиа",this::pickVideo,false,28);
        sourceLabel=text("Project Media",11,TEXT);EditorStyle.heading(sourceLabel);libraryTools.addView(sourceLabel,new LinearLayout.LayoutParams(0,-1,1));
        libraryContent=new FrameLayout(this);libraryPane.addView(libraryContent,new LinearLayout.LayoutParams(-1,0,1));
        HorizontalScrollView tabsScroll=horizontalScroll();LinearLayout tabs=row(Color.TRANSPARENT);tabsScroll.addView(tabs);libraryPane.addView(tabsScroll,lp(-1,34));
        String[][] tabsWithIcons = {{"Project Media", "video"}, {"Explorer", "open"},
                {"Transitions", "transition"}, {"Video FX", "effects"}, {"Media Generators", "gradient"}};
        for (int i = 0; i < tabsWithIcons.length; i++) {
            final int index = i;
            TextView tab = text(tabsWithIcons[i][0], 10, TEXT);
            EditorStyle.touch(tab, Color.TRANSPARENT, 10, 2);
            tab.setPadding(dp(8), 0, dp(8), 0);
            ClassicIcon.decorate(tab, tabsWithIcons[i][1], 13);
            tabs.addView(tab, lp(-2, 34));
            tabLabels.add(tab);
            tab.setOnClickListener(v -> { selectedTab = index; renderLibrary(); });
        }
        libraryPane.setVisibility(libraryVisible?View.VISIBLE:View.GONE);
        View divider=new View(this);docking.addView(divider,lp(6,-1));
        LinearLayout previewPane=column(PANEL);EditorStyle.panel(previewPane);docking.addView(previewPane,new LinearLayout.LayoutParams(0,-1,wide?.58f:.66f));
        LinearLayout previewTools=row(Color.TRANSPARENT);previewPane.addView(previewTools,lp(-1,34));
        tool(previewTools,"settings","Свойства медиа",this::properties,true,28);
        TextView previewTitle=text("Video Preview",12,TEXT);EditorStyle.heading(previewTitle);previewTools.addView(previewTitle,new LinearLayout.LayoutParams(0,-1,1));
        TextView quality=text("Fit ▾",10,EditorStyle.ACCENT);EditorStyle.touch(quality,Color.TRANSPARENT,10,2);quality.setPadding(dp(3),0,dp(6),0);quality.setTooltipText("Размер предпросмотра");previewTools.addView(quality,lp(-2,-1));quality.setContentDescription("Размер предпросмотра");quality.setOnClickListener(v->{PopupMenu p=new PopupMenu(this,quality);p.setForceShowIcon(true);String[] labels={"Fit","Fill","Zoom"};int[] modes={0,3,4};for(int q=0;q<labels.length;q++){final int mode=modes[q];final String label=labels[q];item(p.getMenu(),q==2?"zoomIn":"fit",label,true,()->{preview.setResizeMode(mode);quality.setText(label+" ▾");});}p.show();});
        FrameLayout screen=new FrameLayout(this);screen.setBackgroundColor(EditorStyle.PREVIEW);LinearLayout.LayoutParams screenParams=new LinearLayout.LayoutParams(-1,0,1);screenParams.setMargins(dp(5),0,dp(5),0);previewPane.addView(screen,screenParams);
        preview=new EditorPreview(this);screen.addView(preview,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        placeholder=text("Video Preview\n\nОткройте видео",12,0xffd7e3fa);placeholder.setBackgroundColor(EditorStyle.PREVIEW);placeholder.setGravity(Gravity.CENTER);screen.addView(placeholder,new FrameLayout.LayoutParams(-1,-1));
        preview.setOnPreparedListener(this::onVideoPrepared);
        preview.setOnCompletionListener(player->{if(looping&&prepared){seekPlayer(beginMs);preview.start();}else playIcon.select(false);});
        preview.setOnErrorListener((player,what,extra)->{prepared=false;currentPlayer=null;placeholder.setVisibility(View.VISIBLE);placeholder.setText("Видео недоступно\nВыберите файл повторно");setStatus("Не удалось открыть видео: "+preview.getErrorMessage()+". File → Open video — выбрать повторно.");updateEnabled();return true;});
        LinearLayout previewTransport=row(Color.TRANSPARENT);previewTransport.setGravity(Gravity.CENTER);previewPane.addView(previewTransport,lp(-1,32));
        tool(previewTransport,"begin","К началу выделения",()->seek(beginMs),true,28);
        tool(previewTransport,"back","Предыдущий кадр",()->step(-1),true,28);
        playIcon=tool(previewTransport,"play","Воспроизвести / пауза",this::togglePreview,true,28);
        tool(previewTransport,"pause","Пауза",()->preview.pause(),true,28);
        tool(previewTransport,"stop","Остановить",()->seek(beginMs),true,28);
        tool(previewTransport,"forward","Следующий кадр",()->step(1),true,28);
        tool(previewTransport,"end","К концу выделения",()->seek(endMs),true,28);
        previewMeta=text("Project: —\nPreview: —",9,EditorStyle.SECONDARY);previewMeta.setPadding(dp(6),0,0,0);previewPane.addView(previewMeta,lp(-1,29));
        masterPane=column(PANEL);EditorStyle.panel(masterPane);
        LinearLayout.LayoutParams masterSize=lp(wide?70:0,-1);
        if(wide)masterSize.setMarginStart(dp(6));
        docking.addView(masterPane,masterSize);
        masterPane.setPadding(dp(3),0,dp(3),0);
        masterPane.addView(text("Master",10,TEXT),lp(-1,26));TextView meterCaption=text("Preview",8,TEXT);meterCaption.setGravity(Gravity.CENTER);masterPane.addView(meterCaption,lp(-1,20));
        LinearLayout bus=row(Color.TRANSPARENT);masterPane.addView(bus,new LinearLayout.LayoutParams(-1,0,1));
        fader=new VerticalFader(this);fader.gain(previewGain);fader.listener(value->{previewGain=value;applyVolume();updateVolume();});bus.addView(fader,lp(21,-1));
        fader.setOnTouchListener((v,event)->{if(event.getActionMasked()==android.view.MotionEvent.ACTION_DOWN)gestureBefore=snapshot();if(event.getActionMasked()==android.view.MotionEvent.ACTION_UP||event.getActionMasked()==android.view.MotionEvent.ACTION_CANCEL){if(gestureBefore!=null){history.record(gestureBefore,snapshot());gestureBefore=null;updateEnabled();}}return false;});
        meter=new MasterMeter(this);bus.addView(meter,new LinearLayout.LayoutParams(0,-1,1));
        volumeLabel=text("0.0 dB",9,TEXT);volumeLabel.setGravity(Gravity.CENTER);masterPane.addView(volumeLabel,lp(-1,23));
        muteIcon=tool(masterPane,"mute","Отключить звук исходного ролика",this::toggleMute,true,28);
        timeline=new TimelineView(this);EditorStyle.panel(timeline);timeline.setClipToOutline(true);
        LinearLayout.LayoutParams timelineSize=new LinearLayout.LayoutParams(-1,0,.48f);
        timelineSize.setMargins(dp(6),0,dp(6),0);root.addView(timeline,timelineSize);
        timeline.setListener(new TimelineView.Listener(){
            @Override public void seek(int millis){MainActivity.this.seek(millis);}
            @Override public void editBegin(){gestureBefore=snapshot();preview.pause();}
            @Override public void range(int start,int end){setRange(start,end);}
            @Override public void editEnd(){if(gestureBefore!=null){history.record(gestureBefore,snapshot());gestureBefore=null;preview.setEdits(effects);applyVolume();updateSelection();updateEnabled();}}
            @Override public void mute(){toggleMute();}
            @Override public void preciseRange(){MainActivity.this.preciseRange();}
            @Override public void videoMute(){EffectSpec next=effects.copy();if(next.videoOpacity>0){lastVideoOpacity=next.videoOpacity;next.videoOpacity=0;}else next.videoOpacity=lastVideoOpacity;applyEffects(next);}
            @Override public void videoSettings(){effectDialog("effects");}
            @Override public void audioSettings(){volumeDialog();}
            @Override public void opacity(float value){effects.videoOpacity=value;updateSelection();}
            @Override public void gain(float value){previewGain=value;applyVolume();updateVolume();}
            @Override public void pan(float value){effects.audioPan=value;updateSelection();}
        });
        selectionLabel=text("Loop: 00:00:00.000 — 00:00:00.000",10,TEXT);selectionLabel.setPadding(dp(10),0,dp(10),0);selectionLabel.setSingleLine();selectionLabel.setEllipsize(TextUtils.TruncateAt.END);selectionLabel.setOnClickListener(v->preciseRange());root.addView(selectionLabel,lp(-1,23));
        HorizontalScrollView bottomScroll=horizontalScroll();LinearLayout transport=row(PANEL);transport.setPadding(dp(4),0,dp(4),0);bottomScroll.addView(transport);root.addView(bottomScroll,lp(-1,40));
        loopIcon=tool(transport,"loop","Зациклить выделенный фрагмент",()->{looping=!looping;loopIcon.select(looping);},false);
        tool(transport,"begin","К началу",()->seek(beginMs),true);
        tool(transport,"play","Воспроизвести",this::togglePreview,true);tool(transport,"pause","Пауза",()->preview.pause(),true);tool(transport,"stop","Стоп",()->seek(beginMs),true);
        tool(transport,"end","К концу",()->seek(endMs),true);separator(transport);
        tool(transport,"select","Выделение / точные границы",this::preciseRange,true);
        tool(transport,"marker","Маркер",this::addMarker,true);
        tool(transport,"zoomOut","Уменьшить масштаб",()->timeline.zoom(.66f),true);
        tool(transport,"zoomIn","Увеличить масштаб",()->timeline.zoom(1.5f),true);tool(transport,"fit","Весь ролик",()->timeline.fit(),true);separator(transport);
        rateLabel=text(String.format(java.util.Locale.ROOT,"Rate: %.2f ▾",playbackRate),10,TEXT);EditorStyle.touch(rateLabel,EditorStyle.TONAL,10,3);rateLabel.setPadding(dp(9),0,dp(9),0);transport.addView(rateLabel,lp(-2,40));rateLabel.setOnClickListener(v->speedMenu(rateLabel));rateLabel.setContentDescription("Скорость предпросмотра");rateLabel.setTooltipText("Скорость предпросмотра; экспорт проекта 1×");
        cancelIcon=tool(transport,"stop","Отменить экспорт",()->job.cancel(),false);cancelIcon.setVisibility(View.GONE);
        exportProgress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);exportProgress.setMax(100);root.addView(exportProgress,lp(-1,4));
        status=text("File → Open video: импортируйте ролик для монтажа.",10,TEXT);status.setSingleLine();status.setEllipsize(TextUtils.TruncateAt.END);status.setPadding(dp(6),0,dp(6),0);status.setTextColor(EditorStyle.SECONDARY);EditorStyle.touch(status,Color.TRANSPARENT,8,2);status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);status.setOnClickListener(v->message("Состояние",status.getText().toString()));root.addView(status,lp(-1,25));
        loopIcon.select(looping);updateVolume();renderLibrary();updateEnabled();updateSelection();
    }
    private void renderLibrary(){
        if(libraryContent==null)return;libraryContent.removeAllViews();
        for(int i=0;i<tabLabels.size();i++){TextView t=tabLabels.get(i);boolean active=i==selectedTab;t.setSelected(active);
            t.setTextColor(active?EditorStyle.ACCENT:EditorStyle.SECONDARY);t.setTypeface(null,active?Typeface.BOLD:Typeface.NORMAL);}
        if(selectedTab==0){
            LinearLayout body=row(PANEL);libraryContent.addView(body,new FrameLayout.LayoutParams(-1,-1));
            boolean wide=getResources().getConfiguration().screenWidthDp>=640;
            LinearLayout tree=column(EditorStyle.TONAL);body.addView(tree,lp(wide?95:48,-1));
            String[] nodes={"▾ All Media","▾ Media Bins","  Video","  Audio","▸ Tags","▸ Smart Bins"};
            for(String s:nodes){TextView node=text(s,wide?10:8,TEXT);node.setSingleLine();node.setEllipsize(TextUtils.TruncateAt.END);EditorStyle.touch(node,Color.TRANSPARENT,8,1);node.setPadding(dp(3),0,0,0);tree.addView(node,lp(-1,23));node.setOnClickListener(v->{if(videoUri==null)pickVideo();else properties();});}
            ScrollView list=new ScrollView(this);body.addView(list,new LinearLayout.LayoutParams(0,-1,1));LinearLayout files=column(PANEL);files.setPadding(dp(6),dp(6),dp(6),dp(6));list.addView(files);
            if(videoUri!=null){
                ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);image.setBackground(EditorStyle.shape(this,EditorStyle.PREVIEW,8));image.setClipToOutline(true);
                if(mediaInfo!=null&&mediaInfo.frames!=null&&mediaInfo.frames.length>0&&mediaInfo.frames[0]!=null)image.setImageBitmap(mediaInfo.frames[0]);
                files.addView(image,lp(-1,wide?85:58));image.setOnClickListener(v->seek(beginMs));
                TextView name=text(mediaName,10,TEXT);name.setGravity(Gravity.CENTER);name.setMaxLines(3);files.addView(name,lp(-1,-2));
                files.addView(text(mediaInfo==null?"Медиа загружается…":mediaInfo.width+" × "+mediaInfo.height+"\n"+ClipMath.clock(durationMs),9,0xff444444),lp(-1,-2));
            }else{
                TextView importText=text("＋\nImport Media…",11,EditorStyle.ACCENT);EditorStyle.touch(importText,EditorStyle.TONAL,12,1);importText.setGravity(Gravity.CENTER);files.addView(importText,lp(-1,100));importText.setOnClickListener(v->pickVideo());
            }
        }else if(selectedTab==1){
            LinearLayout explorer=column(PANEL);explorer.setPadding(dp(8),dp(6),dp(8),0);libraryContent.addView(explorer);
            TextView choose=text("▸  Открыть файл…",12,0xff284c7a);explorer.addView(choose,lp(-1,38));choose.setOnClickListener(v->pickVideo());
            TextView project=text("▸  Открыть проект…",11,TEXT);explorer.addView(project,lp(-1,38));project.setOnClickListener(v->pickProject());
            ClassicIcon.decorate(choose, "video", 17);
            ClassicIcon.decorate(project, "open", 17);
            explorer.addView(text("Документы и накопители\nчерез проводник Android",10,0xff474747),lp(-1,-2));
        }else{
            LinearLayout palette=column(PANEL);palette.setPadding(dp(7),dp(4),dp(7),0);ScrollView scroll=new ScrollView(this);scroll.addView(palette);libraryContent.addView(scroll);
            String[] names=selectedTab==2?new String[]{"Dissolve","Cross Effect","Wipe","3D Cascade"}:selectedTab==3?new String[]{"Визуальные эффекты","Brightness and Contrast","Color Corrector","Chroma Keyer","Gaussian Blur","Mosaic","Color Filters","Zoom & Rotation","Reset effects"}:new String[]{"Solid Color","Titles & Text","Checkerboard","Color Gradient"};
            final int section=selectedTab;
            String[] icons = section == 3
                    ? new String[]{"effects", "brightness", "palette", "key", "blur", "mosaic", "palette", "rotate", "undo"}
                    : new String[]{"solid", "text", "checker", "gradient"};
            for (int i = 0; i < names.length; i++) {
                final int index = i;
                TextView entry = text(names[i] + "  ▸", 11, TEXT);
                entry.setContentDescription(names[i]);
                EditorStyle.touch(entry,EditorStyle.TONAL,9,1);
                entry.setPadding(dp(5), 0, dp(5), 0);
                if (section == 2) {
                    ClassicIcon.decorate(entry, new EffectPreview(this, EffectPreview.transitionTile(i + 1)), 42, 26);
                } else {
                    ClassicIcon.decorate(entry, icons[i], 18);
                }
                palette.addView(entry, lp(-1, 42));
                entry.setOnClickListener(v -> paletteAction(section, index));
            }
            palette.addView(text(section==2?"Появление и исчезновение\nвыделенного фрагмента.":section==3?"Эффекты видны в Preview\nи сохраняются в MP4.":"Создать самостоятельный клип.\nТитры поверх видео: кнопка Text.",10,0xff383838),lp(-1,-2));
        }
    }
    private void showMenu(View anchor,String name){
        if ("Эффекты".equals(name)) {
            effectDialog("creative");
            return;
        }
        PopupMenu popup=new PopupMenu(this,anchor);popup.setForceShowIcon(true);Menu m=popup.getMenu();
        if(name.equals("File")){
            item(m,"new","New",!job.running,this::newProject);item(m,"cut","Timeline editor…",!job.running,this::openSequence);item(m,"open","Open video…",!job.running,this::pickVideo);item(m,"open","Open project…",!job.running,this::pickProject);
            item(m,"save","Save project",prepared,this::saveLocalProject);item(m,"save","Save project as…",prepared,this::saveProjectAs);
            item(m,"undo","Restore previous project",!job.running&&!preferences.getString("previousProject","").isEmpty(),()->{try{applyProject(preferences.getString("previousProject",""));}catch(Exception e){setStatus("Не удалось восстановить проект: "+e.getMessage());}});
            item(m,"render","Render As…",prepared&&!job.running,this::renderDialog);item(m,"video","Open rendered video",lastExport!=null,()->openResult(false));item(m,"share","Share rendered video",lastExport!=null,()->openResult(true));
        }else if(name.equals("Edit")){
            item(m,"undo","Undo",history.canUndo()&&!job.running,()->applyHistory(false));item(m,"redo","Redo",history.canRedo()&&!job.running,()->applyHistory(true));
            item(m,"range","Select all",prepared&&!job.running,()->{EditHistory.State old=snapshot();setRange(0,durationMs);history.record(old,snapshot());updateEnabled();});
            item(m,"range","Set loop region…",prepared&&!job.running,this::preciseRange);item(m,"cut","Timeline editor — Split / Delete / Move…",!job.running,this::openSequence);
        }else if(name.equals("View")){
            item(m,"video","Project Media",true,()->{selectedTab=0;libraryVisible=true;libraryPane.setVisibility(View.VISIBLE);renderLibrary();});
            item(m,"layout",libraryVisible?"Hide docking area":"Show docking area",true,()->{libraryVisible=!libraryVisible;libraryPane.setVisibility(libraryVisible?View.VISIBLE:View.GONE);});
            item(m,"open","Explorer",true,()->selectTab(1));item(m,"transition","Transitions",true,()->selectTab(2));item(m,"effects","Video FX",true,()->selectTab(3));item(m,"gradient","Media Generators",true,()->selectTab(4));item(m,"fit","Zoom to project",prepared,()->timeline.fit());
        }else if(name.equals("Insert")){
            item(m,"marker","Marker",prepared&&!job.running,this::addMarker);item(m,"list","Markers…",!markers.isEmpty(),this::markerList);item(m,"video","Video track",prepared&&!job.running,()->pickLayer(false));item(m,"audio","Audio track",prepared&&!job.running,()->pickLayer(true));item(m,"text","Titles & Text",prepared&&!job.running,()->effectDialog("title"));item(m,"gradient","Generated media…",!job.running,()->selectTab(4));
        }else if(name.equals("Tools")){
            item(m,"render","Render As…",prepared&&!job.running,this::renderDialog);item(m,"info","Media properties…",prepared,this::properties);item(m,"mute","Mute audio",prepared&&!job.running,this::toggleMute);item(m,"stop","Cancel rendering",job.running,()->job.cancel());
            item(m,"effects","Video effects…",prepared&&!job.running,()->effectDialog("effects"));item(m,"transition","Transitions…",prepared&&!job.running,()->effectDialog("transitions"));item(m,"text","Titles & Text…",prepared&&!job.running,()->effectDialog("title"));item(m,"layers","Layers…",prepared&&!job.running,()->effectDialog("layers"));item(m,"undo","Reset effects",prepared&&!job.running,this::resetEffects);
        }else if(name.equals("Options")){
            item(m,"loop",(looping?"✓ ":"")+"Loop playback",true,()->{looping=!looping;loopIcon.select(looping);});
            item(m,"volume","Project volume…",prepared,this::volumeDialog);item(m,"range","Select loop region…",prepared&&!job.running,this::preciseRange);
        }else{item(m,"help","Help",true,this::help);item(m,"info","About…",true,()->message("Sony Vegas Video 2.7","Личный Android-видеоредактор.\nКлассическая компоновка Sony Vegas Pro 13.\nВидеоэффекты, титры, переходы, слои и монтаж.\n\nНеофициальное приложение; не продукт Sony/VEGAS."));}
        popup.show();
    }
    private void selectTab(int index){selectedTab=index;libraryVisible=true;libraryPane.setVisibility(View.VISIBLE);renderLibrary();}
    private void effectDialog(String section){if(!prepared||job.running){if(!prepared)message("Сначала откройте видео","File → Open video…");return;}EffectSpec input=effects.copy();input.masterGain=previewGain;EffectDialogs.show(this,input,section,this::applyEffects);}
    private void applyEffects(EffectSpec value){if(!prepared||job.running)return;EditHistory.State before=snapshot();capture();effects=value.copy().normalize();previewGain=effects.masterGain;if(!effects.audioUri.isEmpty()||!effects.overlayUri.isEmpty())playbackRate=1;preview.setEdits(effects);applyVolume();updateVolume();applyRate();history.record(before,snapshot());updateSelection();updateEnabled();savePreferences();setStatus("Настройки применены к предпросмотру и экспорту.");}
    private void resetEffects(){EffectSpec next=new EffectSpec();next.audioUri=effects.audioUri;next.audioGain=effects.audioGain;next.overlayUri=effects.overlayUri;next.overlayScale=effects.overlayScale;next.overlayX=effects.overlayX;next.overlayY=effects.overlayY;next.masterGain=previewGain;next.audioPan=effects.audioPan;applyEffects(next);}
    private void paletteAction(int section,int index){
        if(job.running)return;
        if(section==4){generate(new String[]{"solid","text","checker","gradient"}[index]);return;}
        if(!prepared){message("Откройте видео","Для эффекта сначала выберите File → Open video…");return;}
        EffectSpec next=effects.copy();next.masterGain=previewGain;
        if(section==2){next.transition=new String[]{"dissolve","cross","wipe","cascade"}[index];EffectDialogs.show(this,next,"transitions",this::applyEffects);}
        else {
            String dialog = "effects";
            switch (index) {
                case 0: dialog = "creative"; break;
                case 3: next.chromaKey = true; break;
                case 4: next.blur = 3; break;
                case 5: next.mosaic = 24f; break;
                case 6: if (next.preset.equals("none")) next.preset = "vivid"; break;
                case 7: dialog = "animation"; break;
                case 8: resetEffects(); return;
                default: break;
            }
            EffectDialogs.show(this,next,dialog,this::applyEffects);
        }
    }
    private void generate(String kind){
        if(job.running)return;LinearLayout form=column(CHROME);form.setPadding(dp(16),dp(8),dp(16),dp(8));
        EditText title=new EditText(this);title.setContentDescription("Текст генератора");title.setText("Vegas Video");title.setHint("Текст");form.addView(title);if(!kind.equals("text"))title.setVisibility(View.GONE);
        EditText color=new EditText(this);color.setContentDescription("Цвет генератора");color.setText("#274b82");color.setHint("Цвет #RRGGBB");form.addView(color);
        EditText duration=new EditText(this);duration.setContentDescription("Длительность генератора");duration.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);duration.setText("5");duration.setHint("Длительность, 1–30 секунд");form.addView(duration);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Media Generator").setView(form).setPositiveButton("Создать",null).setNegativeButton("Отмена",null).create();
        dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{
            int seconds,fill;try{seconds=Integer.parseInt(duration.getText().toString());if(seconds<1||seconds>30)throw new IllegalArgumentException();}catch(Exception invalid){duration.setError("Введите от 1 до 30 секунд.");return;}
            try{fill=Color.parseColor(color.getText().toString().trim());}catch(Exception invalid){color.setError("Цвет в виде #RRGGBB.");return;}
            String text=title.getText().toString().trim();if(kind.equals("text")&&(text.isEmpty()||text.length()>200)){title.setError("Введите от 1 до 200 символов.");return;}
            dialog.dismiss();final int milliseconds=seconds*1000,background=fill,generation=sourceGeneration;setStatus("Создаю клип…");
            files.execute(()->{try{Uri image=GeneratedMedia.create(getApplicationContext(),kind,text,background,1280,720);runOnUiThread(()->{if(!isDestroyed()&&generation==sourceGeneration&&!job.running)job.startGenerated(this,image,milliseconds);});}catch(Exception error){runOnUiThread(()->{if(!isDestroyed())setStatus("Не удалось создать клип: "+error.getMessage());});}});
        }));dialog.show();
    }
    private void validateLayer(Uri uri,boolean audio){
        final int generation=sourceGeneration;setStatus("Проверяю "+(audio?"аудиодорожку":"видеослой")+"…");
        files.execute(()->{android.media.MediaExtractor reader=new android.media.MediaExtractor();try{
            reader.setDataSource(getApplicationContext(),uri,null);boolean found=false;for(int i=0;i<reader.getTrackCount();i++){String mime=reader.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME);if(mime!=null&&mime.startsWith(audio?"audio/":"video/")){found=true;break;}}
            if(!found)throw new java.io.IOException(audio?"В файле нет аудио.":"В файле нет видео.");
            runOnUiThread(()->{if(isDestroyed()||generation!=sourceGeneration||job.running)return;EffectSpec next=effects.copy();if(audio)next.audioUri=uri.toString();else next.overlayUri=uri.toString();playbackRate=1;applyEffects(next);effectDialog("layers");});
        }catch(Exception error){runOnUiThread(()->{if(!isDestroyed()&&generation==sourceGeneration)message("Не удалось добавить слой",error.getMessage()+"\nВыберите другой файл.");});}finally{reader.release();}});
    }
    private void item(Menu menu, String glyph, String name, boolean enabled, Runnable action) {
        android.graphics.drawable.Drawable icon = ClassicIcon.drawable(this, glyph);
        if (!enabled) icon.setAlpha(90);
        menu.add(name).setIcon(icon).setEnabled(enabled).setOnMenuItemClickListener(i -> {
            if (action != null) action.run();
            return true;
        });
    }
    private void openSequence(){
        if(job.running)return;
        Intent intent=new Intent(this,SequenceActivity.class);
        if(prepared&&videoUri!=null){try{intent.putExtra("projectJson",projectJson());}catch(Exception e){setStatus("Не удалось передать проект: "+e.getMessage());return;}}
        startActivity(intent);
    }
    private void pickVideo(){
        if(job.running)return;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("video/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);launch(i,PICK_VIDEO);
    }
    private void pickLayer(boolean audio){if(!prepared||job.running)return;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(audio?"audio/*":"video/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);launch(i,audio?PICK_AUDIO:PICK_OVERLAY);}
    private void launch(Intent intent,int code){try{startActivityForResult(intent,code);}catch(ActivityNotFoundException e){setStatus("Не найден проводник Android.");}}
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();if(!"content".equals(uri.getScheme())){setStatus("Выберите файл через проводник Android.");return;}
        if(request==PICK_VIDEO&&!job.running){
            rememberProject();effects=new EffectSpec();previewGain=1;playbackRate=1;muted=false;updateVolume();
            boolean grant=false;try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);grant=true;}catch(SecurityException ignored){}
            // Keep document grants: saved projects may still refer to earlier imported files.
            persistedGrant=grant||hasGrant(uri);beginMs=0;restoreEnd=-1;restorePosition=0;markers.clear();history.clear();openVideo(uri);
        }else if((request==PICK_AUDIO||request==PICK_OVERLAY)&&!job.running){
            try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}
            validateLayer(uri,request==PICK_AUDIO);
        }else if(request==SAVE_PROJECT){
            try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new java.io.IOException("Не удалось создать проект");out.write(pendingProject.getBytes(StandardCharsets.UTF_8));setStatus("Проект сохранён.");}catch(Exception e){setStatus("Ошибка сохранения проекта: "+e.getMessage());}
        }else if(request==OPEN_PROJECT&&!job.running){
            try(InputStream in=getContentResolver().openInputStream(uri)){
                if(in==null)throw new java.io.IOException("Не удалось открыть проект");java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
                while((n=in.read(buffer))>=0){bytes.write(buffer,0,n);if(bytes.size()>ProjectCodec.MAX_BYTES)throw new java.io.IOException("Слишком большой файл проекта");}
                applyProject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));
            }catch(Exception e){setStatus("Не удалось открыть проект: "+e.getMessage());}
        }
    }
    private boolean hasGrant(Uri uri){
        for(android.content.UriPermission permission:getContentResolver().getPersistedUriPermissions())if(permission.getUri().equals(uri)&&permission.isReadPermission())return true;
        if((getPackageName()+".generated").equals(uri.getAuthority()))return true;
        try(android.database.Cursor row=getContentResolver().query(uri,new String[]{android.provider.MediaStore.MediaColumns.OWNER_PACKAGE_NAME},null,null,null)){return row!=null&&row.moveToFirst()&&getPackageName().equals(row.getString(0));}catch(Exception unavailable){return false;}
    }
    private void releaseGrant(){try{getContentResolver().releasePersistableUriPermission(videoUri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){}}
    private void openVideo(Uri uri){
        sourceGeneration++;
        videoUri=uri;durationMs=0;prepared=false;currentPlayer=null;mediaInfo=null;mediaName="Video";placeholder.setText("Загрузка видео…");placeholder.setVisibility(View.VISIBLE);updateEnabled();renderLibrary();
        timeline.media(0,"",false,null,null);timeline.selection(0,0,muted);timeline.position(0);
        setStatus("Загрузка медиа…");try{preview.setVideoURI(uri,effects,muted);preview.seekTo(restorePosition);}catch(RuntimeException e){setStatus("Не удалось открыть файл: "+e.getMessage());}
        probe.load(this,uri,info->{
            if(!uri.equals(videoUri)||isDestroyed())return;mediaInfo=info;if(info.name!=null)mediaName=info.name;
            windowTitle.setText(mediaName+" — Sony Vegas Video");sourceLabel.setText(mediaName);
            previewMeta.setText("Project: "+info.width+" × "+info.height+(info.fps>0?" · "+info.fps+" fps":"")+"\nPreview: Auto · "+info.codec+(info.hasAudio?" / "+info.audioCodec:""));
            timeline.media(durationMs,mediaName,info.hasAudio,info.frames,info.peaks);updateSelection();renderLibrary();updateEnabled();
        });
    }
    private void onVideoPrepared(EditorPreview player){
        currentPlayer=player;durationMs=Math.max(0,player.getDuration());prepared=durationMs>0;
        beginMs=ClipMath.clamp(beginMs,0,Math.max(0,durationMs-1));endMs=restoreEnd<0?durationMs:ClipMath.clamp(restoreEnd,beginMs+1,durationMs);
        markers.removeIf(value->value<0||value>durationMs);
        applyVolume();applyRate();seekPlayer(ClipMath.clamp(restorePosition,0,Math.max(0,durationMs-1)));placeholder.setVisibility(prepared?View.GONE:View.VISIBLE);
        timeline.media(durationMs,mediaName,mediaInfo==null||mediaInfo.hasAudio,mediaInfo==null?null:mediaInfo.frames,mediaInfo==null?null:mediaInfo.peaks);
        timeline.markers(markers);updateSelection();updateEnabled();
        preview.setEffectRange(beginMs,endMs);
        if(!job.running)setStatus(prepared?"Готово. Выделение: золотые ручки; Render As… — экспорт MP4.":"Длительность видео недоступна.");
    }
    private void setRange(int start,int end){if(!prepared||job.running)return;beginMs=ClipMath.clamp(start,0,durationMs-1);endMs=ClipMath.clamp(end,beginMs+1,durationMs);restoreEnd=endMs;updateSelection();handler.removeCallbacks(rangePreview);handler.postDelayed(rangePreview,180);}
    private void updateSelection(){if(timeline==null)return;if(videoUri!=null)windowTitle.setText(mediaName+((beginMs>0||endMs<durationMs||muted||!effects.isIdentity()||!markers.isEmpty())?" *":"")+" — Sony Vegas Video");timeline.selection(beginMs,endMs,muted);timeline.levels(effects.videoOpacity,previewGain,effects.audioPan);selectionLabel.setText("Loop: "+ClipMath.clockCode(beginMs)+" — "+ClipMath.clockCode(endMs)+"    Length: "+ClipMath.clockCode(Math.max(0,endMs-beginMs)));muteIcon.select(muted);}
    private void togglePreview(){if(!prepared||job.running)return;if(preview.isPlaying())preview.pause();else{int p=preview.getCurrentPosition();if(p<beginMs||p>=endMs)seekPlayer(beginMs);preview.start();applyRate();}}
    private void seek(int position){if(!prepared||job.running)return;preview.pause();restorePosition=ClipMath.clamp(position,0,durationMs);seekPlayer(restorePosition);timeline.position(restorePosition);playIcon.select(false);}
    private void seekPlayer(int position){if(currentPlayer!=null)try{currentPlayer.seekTo((long)position,MediaPlayer.SEEK_CLOSEST);return;}catch(IllegalStateException ignored){}preview.seekTo(position);}
    private void step(int direction){float fps=mediaInfo!=null&&mediaInfo.fps>0?mediaInfo.fps:30;seek(preview.getCurrentPosition()+Math.round(1000/fps)*direction);}
    private EditHistory.State snapshot(){EffectSpec current=effects.copy();current.masterGain=previewGain;return new EditHistory.State(beginMs,endMs,muted,current,markers);}
    private void toggleMute(){if(!prepared||job.running)return;EditHistory.State before=snapshot();muted=!muted;applyVolume();updateSelection();history.record(before,snapshot());updateEnabled();}
    private void applyHistory(boolean redo){if(videoUri==null||job.running)return;EditHistory.State value=redo?history.redo(snapshot()):history.undo(snapshot());muted=value.muted;effects=value.effects.copy();previewGain=effects.masterGain;markers.clear();markers.addAll(value.markers);timeline.markers(markers);if(prepared){capture();setRange(value.start,value.end);preview.setEdits(effects);applyVolume();}else{beginMs=value.start;restoreEnd=value.end;openVideo(videoUri);}updateVolume();updateSelection();updateEnabled();}
    private void applyVolume(){effects.masterGain=previewGain;if(currentPlayer!=null)try{currentPlayer.setMuted(muted);currentPlayer.setVolume(previewGain,previewGain);}catch(IllegalStateException ignored){}}
    private void updateVolume(){fader.gain(previewGain);volumeLabel.setText(previewGain==0?"−∞ dB":String.format(java.util.Locale.ROOT,"%.1f dB",20*Math.log10(previewGain)));if(timeline!=null)timeline.levels(effects.videoOpacity,previewGain,effects.audioPan);}
    private void applyRate(){if(currentPlayer!=null&&!currentPlayer.setPlaybackSpeed(playbackRate))playbackRate=1;if(rateLabel!=null)rateLabel.setText(String.format(java.util.Locale.ROOT,"Rate: %.2f ▾",playbackRate));}
    private void speedMenu(TextView label){PopupMenu popup=new PopupMenu(this,label);popup.setForceShowIcon(true);boolean layered=!effects.audioUri.isEmpty()||!effects.overlayUri.isEmpty();for(float speed:new float[]{.5f,1,1.5f,2})item(popup.getMenu(),"speed",speed+"×",prepared&&(!layered||speed==1),()->{playbackRate=speed;applyRate();});if(layered)item(popup.getMenu(),"speed","Слои: предпросмотр 1×",false,null);popup.show();}
    private void volumeDialog(){final EditText value=new EditText(this);value.setContentDescription("Громкость проекта");value.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);value.setText(Integer.toString(Math.round(previewGain*100)));AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Громкость проекта, %").setView(value).setPositiveButton("OK",null).setNegativeButton("Отмена",null).create();dialog.setOnShowListener(d->dialog.getButton(-1).setOnClickListener(v->{try{int number=Integer.parseInt(value.getText().toString());if(number<0||number>100)throw new NumberFormatException();EditHistory.State before=snapshot();previewGain=number/100f;applyVolume();updateVolume();history.record(before,snapshot());updateEnabled();dialog.dismiss();}catch(NumberFormatException invalid){value.setError("Введите число от 0 до 100.");}}));dialog.show();}
    private void preciseRange(){
        if(!prepared||job.running)return;LinearLayout form=column(CHROME);form.setPadding(dp(16),dp(8),dp(16),dp(8));
        form.addView(text("Начало · ЧЧ:ММ:СС.мс",12,TEXT));EditText start=new EditText(this);start.setText(ClipMath.clockCode(beginMs));start.setSingleLine();form.addView(start);
        form.addView(text("Конец · ЧЧ:ММ:СС.мс",12,TEXT));EditText end=new EditText(this);end.setText(ClipMath.clockCode(endMs));end.setSingleLine();form.addView(end);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Loop region").setView(form).setPositiveButton("OK",null).setNegativeButton("Отмена",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{int a=ClipMath.parseClock(start.getText().toString()),b=ClipMath.parseClock(end.getText().toString());if(!ClipMath.validRange(a,b)||b>durationMs)throw new IllegalArgumentException("Укажите границы внутри ролика, конец позже начала.");EditHistory.State old=snapshot();setRange(a,b);history.record(old,snapshot());seek(a);updateEnabled();dialog.dismiss();}catch(Exception e){end.setError(e.getMessage());}}));dialog.show();
    }
    private void addMarker(){if(!prepared||job.running)return;if(markers.size()>=64){setStatus("Максимум 64 маркера.");return;}EditHistory.State before=snapshot();int value=preview.getCurrentPosition();if(!markers.contains(value)){markers.add(value);java.util.Collections.sort(markers);timeline.markers(markers);history.record(before,snapshot());updateEnabled();}setStatus("Маркер: "+ClipMath.clockCode(value));}
    private void markerList(){String[] names=new String[markers.size()];for(int i=0;i<names.length;i++)names[i]=(i+1)+" · "+ClipMath.clockCode(markers.get(i));new AlertDialog.Builder(this).setTitle("Markers").setItems(names,(d,w)->seek(markers.get(w))).setNeutralButton("Удалить маркеры",(d,w)->{EditHistory.State before=snapshot();markers.clear();timeline.markers(markers);history.record(before,snapshot());updateEnabled();}).setNegativeButton("Закрыть",null).show();}
    private void renderDialog(){if(!prepared||job.running)return;new AlertDialog.Builder(this).setTitle("Render As…").setMessage("MP4 · H.264 / AAC · до 720p\n\nLoop region: "+ClipMath.clockCode(beginMs)+" — "+ClipMath.clockCode(endMs)+"\nЗвук исходника: "+(muted?"отключён":"включён")+"\nГромкость проекта: "+Math.round(previewGain*100)+"%\n\nПрименяются эффекты, титры и выбранные слои.\nMovies / Sony Vegas Video").setPositiveButton("Render",(d,w)->saveClip()).setNeutralButton("Границы…",(d,w)->preciseRange()).setNegativeButton("Отмена",null).show();}
    private void saveClip(){if(!prepared||job.running||videoUri==null)return;preview.pause();EffectSpec output=effects.copy();output.masterGain=previewGain;Sequence sequence=new Sequence().append(new Sequence.Clip(videoUri.toString(),mediaName,beginMs,endMs));job.startEdited(this,sequence,output,muted);}
    private void renderJob(){
        if(exportProgress==null)return;exportProgress.setVisibility(job.running?View.VISIBLE:View.GONE);exportProgress.setProgress(job.percent);cancelIcon.setVisibility(job.running?View.VISIBLE:View.GONE);
        if(!job.message.isEmpty())setStatus(job.message+(job.running?"  "+job.percent+"%":""));
        if(job.result!=null){lastExport=job.result.uri;preferences.edit().putString("export",lastExport.toString()).apply();}
        if(!job.running&&job.result!=null&&job.generated&&!job.result.uri.toString().equals(handledGeneration)){
            handledGeneration=job.result.uri.toString();preferences.edit().putString("handledGeneration",handledGeneration).apply();rememberProject();
            effects=new EffectSpec();previewGain=1;muted=false;playbackRate=1;beginMs=0;restoreEnd=-1;restorePosition=0;history.clear();markers.clear();persistedGrant=hasGrant(job.result.uri);exportWasRunning=false;openVideo(job.result.uri);return;
        }
        if(exportWasRunning&&!job.running&&!isFinishing()){
            if(job.result!=null)new AlertDialog.Builder(this).setTitle("Rendering complete").setMessage(job.message).setPositiveButton("Открыть",(d,w)->openResult(false)).setNeutralButton("Поделиться",(d,w)->openResult(true)).setNegativeButton("Закрыть",null).show();
            else message("Rendering",job.message);
        }
        exportWasRunning=job.running;updateEnabled();
    }
    private void updateEnabled(){
        boolean ready=prepared&&!job.running;for(View v:mediaControls)v.setEnabled(ready);if(muteIcon!=null)muteIcon.setEnabled(ready&&(mediaInfo==null||mediaInfo.hasAudio));
        if(fader!=null)fader.setEnabled(ready);
        if(openIcon==null)return;openIcon.setEnabled(!job.running);undoIcon.setEnabled(videoUri!=null&&!job.running&&history.canUndo());redoIcon.setEnabled(videoUri!=null&&!job.running&&history.canRedo());
        if(timeline!=null)timeline.setEnabled(ready);if(cancelIcon!=null)cancelIcon.setEnabled(job.running);
    }
    private void openResult(boolean share){if(lastExport==null)return;Intent i=new Intent(share?Intent.ACTION_SEND:Intent.ACTION_VIEW);if(share){i.setType("video/mp4");i.putExtra(Intent.EXTRA_STREAM,lastExport);}else i.setDataAndType(lastExport,"video/mp4");i.setClipData(android.content.ClipData.newRawUri("Vegas Video",lastExport));i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);try{startActivity(share?Intent.createChooser(i,"Поделиться видео"):i);}catch(ActivityNotFoundException|SecurityException e){setStatus("Не удалось открыть результат. Проверьте файл в Галерее.");}}
    private void newProject(){if(job.running)return;rememberProject();sourceGeneration++;probe.cancel();preview.stopPlayback();videoUri=null;currentPlayer=null;prepared=false;persistedGrant=false;durationMs=beginMs=endMs=restorePosition=0;restoreEnd=-1;mediaInfo=null;mediaName="Untitled";muted=false;effects=new EffectSpec();previewGain=playbackRate=1;history.clear();markers.clear();timeline.markers(markers);timeline.media(0,"",false,null,null);timeline.position(0);placeholder.setText("Video Preview\n\nОткройте видео");placeholder.setVisibility(View.VISIBLE);windowTitle.setText("Untitled — Sony Vegas Video");sourceLabel.setText("Project Media");previewMeta.setText("Project: —\nPreview: —");updateVolume();updateSelection();renderLibrary();updateEnabled();savePreferences();setStatus("Новый проект. File → Open video… Предыдущий проект: File → Restore previous project.");}
    private void saveLocalProject(){capture();savePreferences();setStatus(persistedGrant?"Проект сохранён. File → Save project as… — сохранить отдельный .vvp.":"Настройки сохранены. Для доступа после перезапуска выберите файл через проводник с постоянным доступом.");}
    private String projectJson()throws Exception{if(videoUri==null||endMs<=beginMs)throw new java.io.IOException("Сначала откройте видео.");ProjectCodec.Project p=new ProjectCodec.Project();p.single=true;p.sequence=p.sequence.append(new Sequence.Clip(videoUri.toString(),mediaName,beginMs,endMs));p.effects=effects.copy();p.effects.masterGain=previewGain;p.muted=muted;p.cursor=ClipMath.clamp(preview.getCurrentPosition()-beginMs,0,endMs-beginMs);p.markers=new ArrayList<>(markers);return ProjectCodec.write(p);}
    private void rememberProject(){if(videoUri==null||!prepared)return;try{preferences.edit().putString("previousProject",projectJson()).apply();}catch(Exception ignored){}}
    private void applyProject(String json)throws Exception{ProjectCodec.Project p=ProjectCodec.parse(json);if(!p.single){startActivity(new Intent(this,SequenceActivity.class).putExtra("projectJson",json));return;}if(p.sequence.clips.isEmpty())throw new java.io.IOException("В проекте нет видео.");rememberProject();Sequence.Clip c=p.sequence.clips.get(0);effects=p.effects.copy();previewGain=effects.masterGain;playbackRate=1;muted=p.muted;beginMs=c.inMs;restoreEnd=c.outMs;restorePosition=c.inMs+p.cursor;markers.clear();markers.addAll(p.markers);history.clear();Uri source=Uri.parse(c.source);persistedGrant=hasGrant(source);updateVolume();openVideo(source);}
    private void saveProjectAs(){if(videoUri==null)return;try{pendingProject=projectJson();Intent i=new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/json").putExtra(Intent.EXTRA_TITLE,"VegasVideo.vvp");launch(i,SAVE_PROJECT);}catch(Exception e){setStatus(e.getMessage());}}
    private void pickProject(){if(job.running)return;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");launch(i,OPEN_PROJECT);}
    private void properties(){if(mediaInfo==null){message("Media properties","Свойства ещё загружаются.");return;}message("Media properties",mediaName+"\n\nVideo: "+mediaInfo.width+" × "+mediaInfo.height+" · "+mediaInfo.codec+(mediaInfo.fps>0?" · "+mediaInfo.fps+" fps":"")+"\nDuration: "+ClipMath.clockCode(durationMs)+"\nAudio: "+(mediaInfo.hasAudio?mediaInfo.audioCodec+" · "+mediaInfo.channels+" ch":"None")+"\nWaveform: "+(mediaInfo.waveformComplete?"Decoded PCM":"Недоступна / анализируется")+"\n\nExport: MP4 H.264/AAC, до 720p. Эффекты, титры, слои и громкость применяются к готовому видео.");}
    private void help(){message("Vegas Video — управление","File → Open video — импорт.\nЗолотые ручки на линейке — границы фрагмента.\nКасание таймлайна — перемотка; два пальца — масштаб.\nДолгое нажатие — точный ввод границ.\nM на аудиодорожке отключает исходный звук; M на видео отключает изображение.\nПолзунки дорожек: прозрачность, громкость и панорама.\nUndo / Redo отменяют границы, маркеры, эффекты и громкость.\n\nVideo FX — цвет, фильтры, размытие, мозаика и хромакей.\nTransitions — появление и исчезновение фрагмента.\nText — титры поверх видео.\nInsert → Video / Audio track — видеослой или музыка.\nMedia Generators — цвет, текст, шахматный фон, градиент.\nMaster меняет громкость готового видео. Rate — только скорость предпросмотра, без дополнительных слоёв.\n\nTimeline editor — монтаж нескольких клипов.\nRender As… — MP4 H.264/AAC до 720p с эффектами. Исходные файлы сохраняются. Проекты .vvp содержат ссылки на медиа, сами исходники нужно хранить на устройстве.");}
    private void message(String title,String body){new AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("OK",null).show();}
    private void capture(){if(prepared){restorePosition=preview.getCurrentPosition();restoreEnd=endMs;}}
    private void savePreferences(){JSONArray a=new JSONArray(markers);SharedPreferences.Editor edit=preferences.edit().putString("uri",persistedGrant&&videoUri!=null?videoUri.toString():null).putBoolean("persisted",persistedGrant).putInt("start",beginMs).putInt("end",restoreEnd).putInt("position",restorePosition).putBoolean("mute",muted).putBoolean("loop",looping).putFloat("gain",previewGain).putFloat("rate",playbackRate).putBoolean("library",libraryVisible).putInt("tab",selectedTab).putString("markers",a.toString());try{effects.masterGain=previewGain;edit.putString("effects",ProjectCodec.writeEffects(effects).toString());}catch(Exception ignored){}edit.apply();}
    @Override protected void onSaveInstanceState(Bundle out){capture();if(videoUri!=null)out.putString("uri",videoUri.toString());out.putString("pendingProject",pendingProject);out.putStringArrayList("undoHistory",encodeHistory(history.undoStates()));out.putStringArrayList("redoHistory",encodeHistory(history.redoStates()));out.putInt("start",beginMs);out.putInt("end",restoreEnd);out.putInt("position",restorePosition);out.putBoolean("persisted",persistedGrant);out.putBoolean("mute",muted);out.putBoolean("loop",looping);out.putFloat("gain",previewGain);out.putFloat("rate",playbackRate);try{out.putString("effects",ProjectCodec.writeEffects(effects).toString());}catch(Exception ignored){}out.putBoolean("library",libraryVisible);out.putInt("tab",selectedTab);int[] a=new int[markers.size()];for(int i=0;i<a.length;i++)a[i]=markers.get(i);out.putIntArray("markers",a);super.onSaveInstanceState(out);}
    @Override protected void onStart(){super.onStart();job.attach(jobListener);}
    @Override protected void onResume(){super.onResume();preview.resume();handler.post(tick);handler.post(rangePreview);}
    @Override protected void onPause(){handler.removeCallbacks(tick);handler.removeCallbacks(rangePreview);capture();preview.pause();meter.signal(0);savePreferences();super.onPause();}
    @Override protected void onStop(){preview.suspend();job.detach(jobListener);super.onStop();}
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);probe.stop();files.shutdownNow();currentPlayer=null;preview.release();super.onDestroy();}
    private ArrayList<String> encodeHistory(List<EditHistory.State> values){ArrayList<String> out=new ArrayList<>();int bytes=0;for(int i=values.size()-1;i>=0&&out.size()<16;i--){try{EditHistory.State state=values.get(i);JSONObject value=new JSONObject();value.put("start",state.start);value.put("end",state.end);value.put("muted",state.muted);value.put("effects",ProjectCodec.writeEffects(state.effects));value.put("markers",new JSONArray(state.markers));String text=value.toString();bytes+=text.length()*2;if(bytes>32000)break;out.add(0,text);}catch(Exception ignored){}}return out;}
    private List<EditHistory.State> decodeHistory(ArrayList<String> values){List<EditHistory.State> out=new ArrayList<>();if(values==null)return out;int bytes=0;for(String text:values){try{bytes+=text.length()*2;if(bytes>32000||out.size()>=16)break;JSONObject value=new JSONObject(text);int a=value.getInt("start"),b=value.getInt("end");if(a<0||b<a)continue;List<Integer> marks=new ArrayList<>();JSONArray array=value.optJSONArray("markers");if(array!=null)for(int i=0;i<Math.min(64,array.length());i++)marks.add(array.getInt(i));out.add(new EditHistory.State(a,b,value.optBoolean("muted"),ProjectCodec.readEffects(value.getJSONObject("effects")),marks));}catch(Exception ignored){}}return out;}
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
    private HorizontalScrollView horizontalScroll(){HorizontalScrollView s=new HorizontalScrollView(this);s.setHorizontalScrollBarEnabled(false);s.setFillViewport(true);return s;}
    private void separator(LinearLayout parent){View line=new View(this);line.setBackgroundColor(EditorStyle.DIVIDER);LinearLayout.LayoutParams p=lp(1,20);p.gravity=Gravity.CENTER_VERTICAL;p.setMargins(dp(3),0,dp(3),0);parent.addView(line,p);}
    private ClassicIcon tool(LinearLayout parent,String kind,String description,Runnable action,boolean needsMedia){return tool(parent,kind,description,action,needsMedia,40);}
    private ClassicIcon tool(LinearLayout parent,String kind,String description,Runnable action,boolean needsMedia,int size){ClassicIcon v=new ClassicIcon(this,kind,description);parent.addView(v,lp(size,size));if(action!=null)v.setOnClickListener(view->{if(v.isEnabled()&&(!job.running||v==cancelIcon||description.equals("Справка")))action.run();});if(needsMedia)mediaControls.add(v);return v;}
}
