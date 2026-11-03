package com.personal.sonyvegasvideo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native sequence editor. All edits remain non-destructive until MP4 export. */
public final class SequenceActivity extends Activity {
    private static final int IMPORT=210, OPEN=211, SAVE=212, AUDIO=213, OVERLAY=214;
    private static final int BG=0xffa2a2a2, FG=0xff222222;
    private static final int PROJECT_LIMIT=131072, HISTORY_LIMIT=64;
    private Sequence sequence=new Sequence();
    private EffectSpec effects=new EffectSpec();
    private TransitionTiming timing=new TransitionTiming(sequence,effects);
    private final List<Integer> markers=new ArrayList<>();
    private final ArrayDeque<String> undo=new ArrayDeque<>(), redo=new ArrayDeque<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final ExportJob job=ExportJob.get();
    private final ExportJob.Listener jobListener=this::showJob;
    private final List<View> clipActions=new ArrayList<>(), generalActions=new ArrayList<>();
    private SequenceTimeline timeline;
    private EditorPreview preview;
    private LinearLayout clipList;
    private TextView status, time, label;
    private ClassicIcon undoIcon, redoIcon, playIcon, muteIcon, cancelIcon, moveLeftIcon, moveRightIcon;
    private ProgressBar progress;
    private int selected=-1, cursor, operation;
    private boolean prepared, busy, muted, resumed, previewStopped, exportWasRunning;
    private String pendingJson="";
    private Uri lastExport;
    private final ArrayList<String> pendingImports=new ArrayList<>();

    private final Runnable tick=new Runnable() {
        @Override public void run() {
            if(resumed && prepared && !busy && !job.running && preview.isPlaying()) {
                cursor=ClipMath.clamp(preview.getCurrentPosition(),0,duration());
                updateSelection();
                updateTime();
            }
            if(resumed)handler.postDelayed(this,80);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        String failure=null;
        String exported=getSharedPreferences("sequence_editor",MODE_PRIVATE).getString("export",null);
        if(exported!=null)lastExport=Uri.parse(exported);
        try {
            String json;
            if(saved!=null) {
                json=saved.getString("project","");
                pendingJson=saved.getString("pendingJson","");
                restoreStack(saved.getStringArrayList("undo"),undo);
                restoreStack(saved.getStringArrayList("redo"),redo);
                ArrayList<String> waiting=saved.getStringArrayList("pendingImports");
                if(waiting!=null)pendingImports.addAll(waiting);
            } else if(getIntent().hasExtra("projectJson")) {
                json=getIntent().getStringExtra("projectJson");
            } else if(getIntent().getStringExtra("source")!=null) {
                String source=getIntent().getStringExtra("source");
                requireContent(Uri.parse(source));
                sequence=sequence.append(new Sequence.Clip(source,getIntent().getStringExtra("name"),
                        getIntent().getIntExtra("start",0),getIntent().getIntExtra("end",0)));
                json="";
            } else {
                json=getSharedPreferences("sequence_editor",MODE_PRIVATE).getString("project","");
            }
            if(json!=null&&!json.isEmpty())restore(ProjectCodec.parse(json));
        } catch(Exception error) {
            failure="Не удалось восстановить проект: "+detail(error);
        }
        timing=new TransitionTiming(sequence,effects);
        cursor=ClipMath.clamp(cursor,0,duration());
        build();
        refresh();
        if(!sequence.clips.isEmpty())loadPreview(false);
        if(failure!=null)status(failure);
        if(!pendingImports.isEmpty()) {
            ArrayList<Uri> waiting=new ArrayList<>();
            for(String value:pendingImports)waiting.add(Uri.parse(value));
            pendingImports.clear();
            importVideos(waiting);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if(!intent.hasExtra("projectJson")||busy||job.running)return;
        try {applyProject(ProjectCodec.parse(intent.getStringExtra("projectJson")),"Проект открыт.");}
        catch(Exception error){status("Не удалось открыть проект: "+detail(error));}
    }

    private void build() {
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                |View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                |View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        LinearLayout root=column();
        root.setOnApplyWindowInsetsListener((view,insets)->{
            root.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(root);
        TextView title=text("Sony Vegas Video — Timeline Editor",13);
        title.setPadding(dp(7),0,0,0);
        root.addView(title,lp(-1,27));
        HorizontalScrollView menuScroll=scroll();
        LinearLayout menu=row();
        menuScroll.addView(menu);
        root.addView(menuScroll,lp(-1,29));
        for(String name:new String[]{"File","Edit","View","Insert","Tools","Help"}) {
            TextView item=text(name,12);
            item.setPadding(dp(10),0,dp(10),0);
            menu.addView(item,lp(-2,29));
            item.setOnClickListener(view->menu(item,name));
        }
        HorizontalScrollView toolbarScroll=scroll();
        LinearLayout bar=row();
        toolbarScroll.addView(bar);
        root.addView(toolbarScroll,lp(-1,36));
        button(bar,"new","Новый монтаж",this::newProject,false);
        button(bar,"open","Добавить видео",this::pickVideos,false);
        button(bar,"save","Сохранить проект",this::saveAs,false);
        undoIcon=button(bar,"undo","Undo",()->history(false),false);
        redoIcon=button(bar,"redo","Redo",()->history(true),false);
        button(bar,"cut","Разрезать в позиции курсора (S)",this::split,true);
        button(bar,"select","Точные границы выбранного фрагмента",this::trim,true);
        button(bar,"delete","Удалить выбранный фрагмент (Delete)",this::delete,true);
        moveLeftIcon=button(bar,"back","Переместить фрагмент влево",()->move(-1),true);
        moveRightIcon=button(bar,"forward","Переместить фрагмент вправо",()->move(1),true);
        button(bar,"settings","Эффекты монтажа",()->editEffects("effects"),false);
        button(bar,"render","Render As…",this::render,true);
        LinearLayout docking=row();
        root.addView(docking,new LinearLayout.LayoutParams(-1,0,.50f));
        LinearLayout left=column();
        docking.addView(left,new LinearLayout.LayoutParams(0,-1,.38f));
        left.addView(text("Project Media / Events",11),lp(-1,27));
        ScrollView list=new ScrollView(this);
        left.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        clipList=column();
        list.addView(clipList);
        LinearLayout right=column();
        docking.addView(right,new LinearLayout.LayoutParams(0,-1,.62f));
        right.addView(text("Video Preview",11),lp(-1,27));
        FrameLayout screen=new FrameLayout(this);
        screen.setBackgroundColor(Color.BLACK);
        right.addView(screen,new LinearLayout.LayoutParams(-1,0,1));
        preview=new EditorPreview(this);
        screen.addView(preview,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        label=text("Выберите или импортируйте видео",10);
        label.setSingleLine();
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        right.addView(label,lp(-1,28));
        preview.setOnPreparedListener(player->{
            if(isDestroyed()||previewStopped)return;
            prepared=true;
            preview.setVolume(effects.masterGain,effects.masterGain);
            updateControls();
        });
        preview.setOnCompletionListener(player->{
            cursor=duration();
            updateSelection();
            updateTime();
            updateControls();
        });
        preview.setOnErrorListener((player,what,extra)->{
            prepared=false;
            status("Предпросмотр: "+preview.getErrorMessage()
                    +". Проверьте исходный файл или импортируйте его повторно.");
            updateControls();
            return true;
        });
        timeline=new SequenceTimeline(this);
        timeline.listener(this::seek);
        timeline.setOnLongClickListener(view->{trim();return true;});
        root.addView(timeline,new LinearLayout.LayoutParams(-1,0,.50f));
        time=text("00:00:00.000",12);
        time.setTypeface(Typeface.MONOSPACE);
        time.setContentDescription("Позиция и длительность монтажного проекта");
        root.addView(time,lp(-1,25));
        HorizontalScrollView transportScroll=scroll();
        LinearLayout transport=row();
        transportScroll.addView(transport);
        root.addView(transportScroll,lp(-1,36));
        button(transport,"begin","В начало",()->seek(0),true);
        playIcon=button(transport,"play","Play / Pause",this::toggle,true);
        button(transport,"stop","Stop",()->seek(0),true);
        muteIcon=button(transport,"mute","Mute audio",this::toggleMute,false);
        button(transport,"zoomOut","Zoom out",()->timeline.zoom(.7f),false);
        button(transport,"zoomIn","Zoom in",()->timeline.zoom(1.5f),false);
        button(transport,"fit","Fit project",()->timeline.fit(),false);
        cancelIcon=button(transport,"stop","Отменить экспорт",()->job.cancel(),false);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        root.addView(progress,lp(-1,5));
        status=text("Импорт → разрезать / эффекты → Render As…",11);
        status.setMaxLines(3);
        status.setPadding(dp(5),dp(3),dp(5),dp(3));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setContentDescription("Состояние монтажного редактора");
        status.setOnClickListener(view->message("Состояние",status.getText().toString()));
        root.addView(status,lp(-1,58));
    }

    private void menu(View anchor,String name) {
        PopupMenu popup=new PopupMenu(this,anchor);
        Menu menu=popup.getMenu();
        boolean active=!busy&&!job.running, has=selected>=0;
        if(name.equals("File")) {
            item(menu,"New",active,this::newProject);
            item(menu,"Add videos…",active,this::pickVideos);
            item(menu,"Open project…",active,this::pickProject);
            item(menu,"Save project as…",active,this::saveAs);
            item(menu,"Render As…",active&&!sequence.clips.isEmpty(),this::render);
            item(menu,"Open result",lastExport!=null,()->result(false));
            item(menu,"Share result",lastExport!=null,()->result(true));
        } else if(name.equals("Edit")) {
            item(menu,"Undo",active&&!undo.isEmpty(),()->history(false));
            item(menu,"Redo",active&&!redo.isEmpty(),()->history(true));
            item(menu,"Split (S)",active&&has,this::split);
            item(menu,"Trim selected event…",active&&has,this::trim);
            item(menu,"Duplicate selected event",active&&has&&sequence.clips.size()<64,this::duplicate);
            item(menu,"Delete (ripple)",active&&has,this::delete);
            item(menu,"Move left",active&&selected>0,()->move(-1));
            item(menu,"Move right",active&&has&&selected+1<sequence.clips.size(),()->move(1));
        } else if(name.equals("View")) {
            item(menu,"Zoom in",active,()->timeline.zoom(1.5f));
            item(menu,"Zoom out",active,()->timeline.zoom(.7f));
            item(menu,"Fit project",active,()->timeline.fit());
        } else if(name.equals("Insert")) {
            item(menu,"Video events…",active,this::pickVideos);
            item(menu,"Video overlay…",active,()->pickLayer(OVERLAY));
            item(menu,"Audio track…",active,()->pickLayer(AUDIO));
            item(menu,"Titles & Text…",active,()->editEffects("title"));
        } else if(name.equals("Tools")) {
            item(menu,"Video FX…",active,()->editEffects("effects"));
            item(menu,"Transitions…",active,()->editEffects("transitions"));
            item(menu,"Titles & Text…",active,()->editEffects("title"));
            item(menu,"Add audio track…",active,()->pickLayer(AUDIO));
            item(menu,"Add video overlay…",active,()->pickLayer(OVERLAY));
            item(menu,"Layer settings…",active,()->editEffects("layers"));
            item(menu,"Remove audio track",active&&!effects.audioUri.isEmpty(),()->removeLayer(false));
            item(menu,"Remove video overlay",active&&!effects.overlayUri.isEmpty(),()->removeLayer(true));
            item(menu,(muted?"✓ ":"")+"Mute audio",active,this::toggleMute);
            item(menu,"Cancel render",job.running,()->job.cancel());
        } else {
            item(menu,"Editing help",true,()->message("Timeline Editor",
                    "Добавляйте видео через File → Add videos. Коснитесь дорожки, чтобы поставить курсор. "
                    +"S / ножницы разрезают фрагмент точно в этой позиции. Точные границы позволяют обрезать "
                    +"и восстановить края исходника. Delete удаляет фрагмент только из проекта; "
                    +"стрелки меняют порядок. Undo / Redo отменяют монтаж, звук и эффекты.\n\n"
                    +"Tools → Video FX / Transitions / Titles & Text изменяют весь монтажный проект. "
                    +"Между соседними клипами переходы перекрывают конец и начало роликов: движущееся видео и звук плавно смешиваются. "
                    +"Длительность проекта уменьшается на сумму перекрытий. У единственного клипа переход работает на входе и выходе. "
                    +"Insert добавляет музыку и видео поверх основной дорожки. "
                    +"Render As сохраняет совместимый MP4 H.264/AAC с эффектами; исходники могут иметь разные параметры.\n\n"
                    +"Проект автоматически восстанавливается при возврате. Save project as сохраняет отдельный .vvp; "
                    +"для открытия исходников нужны сохранённые разрешения Android. Оригинальные видео не изменяются."));
        }
        popup.show();
    }

    private void item(Menu menu,String name,boolean enabled,Runnable action) {
        menu.add(name).setEnabled(enabled).setOnMenuItemClickListener(item->{
            try {action.run();}catch(RuntimeException error){status("Не удалось выполнить действие: "+detail(error));}
            return true;
        });
    }

    private ProjectCodec.Project project() {
        ProjectCodec.Project value=new ProjectCodec.Project();
        value.sequence=sequence;
        value.effects=effects.copy();
        value.muted=muted;
        value.cursor=cursor;
        value.markers=new ArrayList<>(markers);
        value.single=false;
        return value;
    }
    private String json()throws Exception {return ProjectCodec.write(project());}
    private int duration(){return timing.length;}
    private int offset(int index){return timing.starts[index];}
    private int offset(Sequence next,int index){return new TransitionTiming(next,effects).starts[index];}
    private void restore(ProjectCodec.Project value) {
        sequence=value.sequence;
        effects=value.effects==null?new EffectSpec():value.effects.copy().normalize();
        timing=new TransitionTiming(sequence,effects);
        muted=value.muted;
        cursor=ClipMath.clamp(value.cursor,0,duration());
        markers.clear();
        if(value.markers!=null)for(int marker:value.markers)if(marker>=0&&marker<=duration())markers.add(marker);
        selected=timing.indexAt(cursor);
    }
    private void remember() {
        try {undo.addLast(json());while(undo.size()>HISTORY_LIMIT)undo.removeFirst();redo.clear();}
        catch(Exception error){status("Не удалось записать отмену: "+detail(error));}
    }
    private void applyProject(ProjectCodec.Project value,String message) {
        if(busy||job.running)return;
        try {value=ProjectCodec.parse(ProjectCodec.write(value));}
        catch(Exception error){status("Проект не изменён: "+detail(error));return;}
        remember();
        restore(value);
        preview.pause();
        refresh();
        persist();
        loadPreview(false);
        status(message);
    }
    private void commit(Sequence next,int at,String message) {
        ProjectCodec.Project value=project();
        value.sequence=next;
        value.cursor=ClipMath.clamp(at,0,new TransitionTiming(next,effects).length);
        applyProject(value,message);
    }
    private void newProject() {
        if(busy||job.running)return;
        applyProject(new ProjectCodec.Project(),"Новый монтаж. Добавьте видео или откройте проект.");
    }
    private void history(boolean forward) {
        if(busy||job.running)return;
        ArrayDeque<String> from=forward?redo:undo,to=forward?undo:redo;
        if(from.isEmpty())return;
        try {
            ProjectCodec.Project value=ProjectCodec.parse(from.peekLast());
            String current=json();
            from.removeLast();
            to.addLast(current);
            while(to.size()>HISTORY_LIMIT)to.removeFirst();
            restore(value);
            refresh();
            persist();
            loadPreview(false);
            status(forward?"Изменение повторено.":"Изменение отменено.");
        } catch(Exception error){status("Не удалось восстановить изменение: "+detail(error));}
    }
    private void refresh() {
        clipList.removeAllViews();
        if(sequence.clips.isEmpty()) {
            TextView empty=text("＋ Добавить видео",12);
            empty.setGravity(Gravity.CENTER);
            clipList.addView(empty,lp(-1,100));
            empty.setOnClickListener(view->pickVideos());
        } else for(int i=0;i<sequence.clips.size();i++) {
            final int index=i;
            Sequence.Clip clip=sequence.clips.get(i);
            TextView item=text((i+1)+"  "+clip.name+"\n"+ClipMath.clockCode(clip.inMs)+" — "+ClipMath.clockCode(clip.outMs),10);
            item.setPadding(dp(5),dp(5),dp(5),dp(5));
            item.setContentDescription("Фрагмент "+(i+1)+": "+clip.name);
            item.setBackgroundColor(i==selected?0xffb7c6d8:0xffb5b5b5);
            clipList.addView(item,lp(-1,65));
            item.setOnClickListener(view->seek(offset(index)));
        }
        timeline.project(sequence,selected,effects,muted);
        updateSelection();
        updateTime();
        updateControls();
    }
    private void loadPreview(boolean play) {
        prepared=false;
        if(sequence.clips.isEmpty()) {
            preview.stopPlayback();
            previewStopped=false;
            label.setText("Выберите или импортируйте видео");
        } else if(job.running||busy) {
            previewStopped=true;
        } else {
            previewStopped=false;
            preview.setProject(sequence,effects.copy(),muted);
            preview.setVolume(effects.masterGain,effects.masterGain);
            preview.seekTo(cursor);
            if(play)preview.start();else preview.pause();
        }
        updateControls();
    }
    private void updateSelection() {
        selected=timing.indexAt(cursor);
        for(int i=0;i<clipList.getChildCount();i++) {
            if(!sequence.clips.isEmpty())clipList.getChildAt(i).setBackgroundColor(i==selected?0xffb7c6d8:0xffb5b5b5);
        }
        timeline.project(sequence,selected);
        if(selected>=0)label.setText(sequence.clips.get(selected).name);
    }
    private void seek(int at) {
        if(busy||job.running||sequence.clips.isEmpty())return;
        cursor=ClipMath.clamp(at,0,duration());
        preview.pause();
        if(previewStopped)loadPreview(false);
        preview.seekTo(cursor);
        updateSelection();
        updateTime();
        updateControls();
    }
    private void toggle() {
        if(busy||job.running||sequence.clips.isEmpty())return;
        if(preview.isPlaying())preview.pause();
        else {
            if(cursor>=duration())seek(0);
            if(previewStopped)loadPreview(false);
            preview.start();
        }
        updateControls();
    }
    private void split() {
        if(selected<0||busy||job.running)return;
        if(sequence.clips.size()>=64){status("Максимум 64 фрагмента. Удалите ненужный фрагмент.");return;}
        captureCursor();
        int index=timing.indexAt(cursor);
        Sequence.Clip clip=sequence.clips.get(index);
        int source=timing.sourceAt(index,cursor);
        if(source<=clip.inMs||source>=clip.outMs) {
            status("Разрез: поставьте курсор внутри фрагмента, между его началом и концом.");
            return;
        }
        preview.pause();
        try {
            Sequence next=sequence.split(index,source);
            commit(next,offset(next,index+1),"Фрагмент разрезан: "+ClipMath.clockCode(source)+" исходного файла.");
        }
        catch(RuntimeException error){status("Разрез: "+detail(error));}
    }
    private void trim() {
        if(selected<0||busy||job.running)return;
        final int index=selected, token=++operation;
        final Sequence before=sequence;
        final Sequence.Clip clip=before.clips.get(index);
        busy=true;
        preview.pause();
        status("Проверяю длительность исходника…");
        updateControls();
        worker.execute(()->{
            try {
                int duration=MediaPipeline.durationMs(getApplicationContext(),Uri.parse(clip.source));
                runOnUiThread(()->{
                    if(isDestroyed()||token!=operation)return;
                    busy=false;updateControls();
                    if(sequence!=before)return;
                    showTrim(index,clip,duration);
                });
            } catch(Exception error) {
                runOnUiThread(()->{if(!isDestroyed()&&token==operation){busy=false;status("Границы: "+detail(error));updateControls();}});
            }
        });
    }
    private void showTrim(int index,Sequence.Clip clip,int duration) {
        LinearLayout form=column();
        form.setPadding(dp(16),dp(8),dp(16),dp(8));
        form.addView(text("Начало · ЧЧ:ММ:СС.мс",12));
        EditText start=new EditText(this);start.setSingleLine();start.setHint("Начало · ЧЧ:ММ:СС.мс");
        start.setText(ClipMath.clockCode(clip.inMs));form.addView(start);
        form.addView(text("Конец · ЧЧ:ММ:СС.мс",12));
        EditText end=new EditText(this);end.setSingleLine();end.setHint("Конец · ЧЧ:ММ:СС.мс");
        end.setText(ClipMath.clockCode(clip.outMs));form.addView(end);
        form.addView(text("Исходник: "+ClipMath.clockCode(duration),11));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Границы фрагмента").setView(form)
                .setPositiveButton("Применить",null).setNegativeButton("Отмена",null).create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view->{
            try {
                int a=ClipMath.parseClock(start.getText().toString()),b=ClipMath.parseClock(end.getText().toString());
                if(!ClipMath.validRange(a,b)||b>duration)throw new IllegalArgumentException("Границы должны быть внутри исходника, конец позже начала.");
                if(busy||job.running)throw new IllegalStateException("Дождитесь завершения текущей операции.");
                Sequence next=sequence.trim(index,a,b);
                commit(next,offset(next,index),"Границы фрагмента изменены.");
                dialog.dismiss();
            } catch(Exception error){end.setError(detail(error));}
        }));
        dialog.show();
    }
    private void delete() {
        if(selected<0||busy||job.running)return;
        int index=selected;
        Sequence next=sequence.delete(index);
        TransitionTiming changed=new TransitionTiming(next,effects);
        int at=index<next.clips.size()?changed.starts[index]:changed.length;
        commit(next,at,"Фрагмент удалён из проекта. Исходный файл сохранён.");
    }
    private void duplicate() {
        if(selected<0||busy||job.running||sequence.clips.size()>=64)return;
        try {
            List<Sequence.Clip> clips=new ArrayList<>(sequence.clips);
            clips.add(selected+1,sequence.clips.get(selected));
            Sequence next=new Sequence(clips);
            commit(next,offset(next,selected+1),"Фрагмент продублирован.");
        } catch(RuntimeException error){status("Дублирование: "+detail(error));}
    }
    private void move(int direction) {
        if(selected<0||busy||job.running)return;
        int target=selected+direction;
        if(target<0||target>=sequence.clips.size())return;
        Sequence next=sequence.move(selected,target);
        commit(next,offset(next,target),"Порядок фрагментов изменён.");
    }
    private void toggleMute() {
        if(busy||job.running)return;
        ProjectCodec.Project value=project();value.muted=!muted;
        applyProject(value,value.muted?"Звук исходных клипов отключён.":"Звук исходных клипов включён.");
    }
    private void editEffects(String section) {
        if(busy||job.running)return;
        String dialogSection=section.equals("transitions")&&sequence.clips.size()>1?"sequenceTransitions":section;
        EffectDialogs.show(this,effects.copy(),dialogSection,next->{
            if(isDestroyed()||busy||job.running)return;
            if(effects.equals(next))return;
            captureCursor();
            int index=timing.indexAt(cursor),source=index<0?0:timing.sourceAt(index,cursor);
            ProjectCodec.Project value=project();value.effects=next.copy().normalize();
            TransitionTiming changed=new TransitionTiming(sequence,value.effects);
            if(index>=0)value.cursor=ClipMath.clamp(changed.starts[index]+source-sequence.clips.get(index).inMs,0,changed.length);
            applyProject(value,section.equals("transitions")?"Переходы применены. Длительность монтажа: "+ClipMath.clockCode(changed.length):"Эффекты применены к предпросмотру и экспорту проекта.");
        });
    }
    private void removeLayer(boolean overlay) {
        ProjectCodec.Project value=project();
        if(overlay)value.effects.overlayUri="";else value.effects.audioUri="";
        applyProject(value,overlay?"Видео поверх основной дорожки удалено.":"Дополнительная аудиодорожка удалена.");
    }
    private void pickVideos() {
        if(busy||job.running)return;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("video/*")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        launch(intent,IMPORT);
    }
    private void pickLayer(int request) {
        if(busy||job.running)return;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType(request==AUDIO?"audio/*":"video/*")
                .addCategory(Intent.CATEGORY_OPENABLE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        launch(intent,request);
    }
    private void pickProject() {
        if(busy||job.running)return;
        launch(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),OPEN);
    }
    private void launch(Intent intent,int request) {
        try {startActivityForResult(intent,request);}
        catch(ActivityNotFoundException|SecurityException error){status("Не удалось открыть проводник Android: "+detail(error));}
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(result!=RESULT_OK||data==null)return;
        if(busy||job.running){status("Дождитесь завершения текущей операции и повторите выбор файла.");return;}
        if(request==IMPORT) {
            ArrayList<Uri> uris=new ArrayList<>();
            if(data.getClipData()!=null)for(int i=0;i<data.getClipData().getItemCount();i++)uris.add(data.getClipData().getItemAt(i).getUri());
            else if(data.getData()!=null)uris.add(data.getData());
            for(Uri uri:uris)keepGrant(uri);
            importVideos(uris);
        } else if(data.getData()!=null) {
            Uri uri=data.getData();
            if(request==SAVE)writeProject(uri);
            else if(request==OPEN)readProject(uri);
            else if(request==AUDIO||request==OVERLAY){keepGrant(uri);importLayer(uri,request==OVERLAY);}
        }
    }
    private void keepGrant(Uri source) {
        if(source==null)return;
        try {getContentResolver().takePersistableUriPermission(source,Intent.FLAG_GRANT_READ_URI_PERMISSION);}
        catch(SecurityException ignored) { /* A session grant still permits this editing session. */ }
    }
    private void importVideos(List<Uri> uris) {
        if(uris.isEmpty()||busy||job.running)return;
        for(Uri uri:uris)if(uri==null){status("Выберите видеофайл через проводник Android.");return;}
        if(sequence.clips.size()+uris.size()>64){status("Максимум 64 фрагмента. Выберите меньше файлов.");return;}
        final int token=++operation;
        final Sequence before=sequence;
        pendingImports.clear();for(Uri uri:uris)pendingImports.add(uri.toString());
        busy=true;preview.pause();status("Импортирую медиа…");updateControls();
        worker.execute(()->{
            try {
                Sequence next=before;
                for(Uri uri:uris) {
                    if(Thread.currentThread().isInterrupted())return;
                    requireContent(uri);
                    int duration=MediaPipeline.durationMs(getApplicationContext(),uri);
                    if(duration<=0)throw new IOException("Нет видеодорожки или длительности: "+uri);
                    requireTrack(uri,"video/");
                    next=next.append(new Sequence.Clip(uri.toString(),displayName(uri),0,duration));
                }
                final Sequence imported=next;
                runOnUiThread(()->{
                    if(isDestroyed()||token!=operation)return;
                    busy=false;pendingImports.clear();
                    commit(imported,before.clips.isEmpty()?0:cursor,"Импортировано видео: "+uris.size()+".");
                });
            } catch(Exception error) {
                runOnUiThread(()->{
                    if(isDestroyed()||token!=operation)return;
                    busy=false;pendingImports.clear();status("Ошибка импорта: "+detail(error));updateControls();
                });
            }
        });
    }
    private void importLayer(Uri uri,boolean overlay) {
        final int token=++operation;
        busy=true;preview.pause();status("Проверяю дополнительную дорожку…");updateControls();
        worker.execute(()->{
            try {
                requireContent(uri);requireTrack(uri,overlay?"video/":"audio/");
                if(overlay&&MediaPipeline.durationMs(getApplicationContext(),uri)<=0)throw new IOException("Длительность видео недоступна.");
                final String name=displayName(uri);
                runOnUiThread(()->{
                    if(isDestroyed()||token!=operation)return;
                    busy=false;
                    ProjectCodec.Project value=project();
                    if(overlay)value.effects.overlayUri=uri.toString();else value.effects.audioUri=uri.toString();
                    applyProject(value,(overlay?"Видео-наложение: ":"Аудиодорожка: ")+name+". Tools → Layer settings — настройки.");
                });
            } catch(Exception error) {
                runOnUiThread(()->{if(!isDestroyed()&&token==operation){busy=false;status("Не удалось добавить дорожку: "+detail(error));updateControls();}});
            }
        });
    }
    private void requireTrack(Uri uri,String prefix)throws Exception {
        MediaExtractor extractor=new MediaExtractor();
        try {
            extractor.setDataSource(getApplicationContext(),uri,null);
            for(int i=0;i<extractor.getTrackCount();i++) {
                String mime=extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if(mime!=null&&mime.startsWith(prefix))return;
            }
            throw new IOException(prefix.equals("video/")?"В файле нет видеодорожки.":"В файле нет аудиодорожки.");
        } finally {extractor.release();}
    }
    private String displayName(Uri uri) {
        try(android.database.Cursor names=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)) {
            if(names!=null&&names.moveToFirst()&&!names.isNull(0)){
                String name=names.getString(0);
                return name.length()>512?name.substring(0,512):name;
            }
        } catch(RuntimeException ignored) { }
        return "Video";
    }
    private static void requireContent(Uri source)throws IOException {
        if(source==null||!"content".equals(source.getScheme())||source.getAuthority()==null||source.toString().length()>4096)
            throw new IOException("Выберите файл через проводник Android.");
    }
    private void readProject(Uri uri) {
        final int token=++operation;
        busy=true;updateControls();status("Открываю проект…");
        worker.execute(()->{
            try {
                String text;
                try(InputStream input=getContentResolver().openInputStream(uri)) {
                    if(input==null)throw new IOException("Не удалось открыть файл проекта.");
                    ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                    byte[] buffer=new byte[4096];int size;
                    while((size=input.read(buffer))!=-1) {
                        if(size==0)continue;
                        if(bytes.size()+size>PROJECT_LIMIT)throw new IOException("Файл проекта превышает 128 КиБ.");
                        bytes.write(buffer,0,size);
                    }
                    text=new String(bytes.toByteArray(),StandardCharsets.UTF_8);
                }
                ProjectCodec.Project loaded=ProjectCodec.parse(text);
                runOnUiThread(()->{if(!isDestroyed()&&token==operation){busy=false;applyProject(loaded,"Монтажный проект открыт.");}});
            } catch(Exception error) {
                runOnUiThread(()->{if(!isDestroyed()&&token==operation){busy=false;status("Не удалось открыть проект: "+detail(error));updateControls();}});
            }
        });
    }
    private void writeProject(Uri uri) {
        final String text=pendingJson;
        if(text.isEmpty()){status("Содержимое проекта недоступно. Повторите Save project as.");return;}
        final int token=++operation;
        busy=true;updateControls();status("Сохраняю проект…");
        worker.execute(()->{
            try(OutputStream output=getContentResolver().openOutputStream(uri,"wt")) {
                if(output==null)throw new IOException("Не удалось открыть файл для записи.");
                output.write(text.getBytes(StandardCharsets.UTF_8));
                runOnUiThread(()->{if(!isDestroyed()&&token==operation){busy=false;pendingJson="";status("Монтажный проект сохранён.");updateControls();}});
            } catch(Exception error) {
                runOnUiThread(()->{if(!isDestroyed()&&token==operation){busy=false;status("Не удалось сохранить проект: "+detail(error));updateControls();}});
            }
        });
    }
    private void render() {
        if(sequence.clips.isEmpty()||busy||job.running)return;
        new AlertDialog.Builder(this).setTitle("Render As…")
                .setMessage("MP4 · H.264 / AAC · до 720p\n"+sequence.clips.size()+" фрагментов · "
                        +ClipMath.clockCode(duration())+"\n"+transitionSummary()+"\n\nТочные границы, эффекты, титры и дополнительные дорожки "
                        +"будут сохранены в видео. Исходники могут иметь разные параметры.\n\n"
                        +"Звук исходников: "+(muted?"выключен":"включён"))
                .setPositiveButton("Render",(dialog,which)->{
                    if(busy||job.running)return;
                    captureCursor();preview.pause();preview.stopPlayback();previewStopped=true;prepared=false;
                    job.startEdited(this,sequence,effects.copy(),muted);
                }).setNegativeButton("Отмена",null).show();
    }
    private String transitionSummary(){
        if(effects.transition.equals("none"))return "Переходы: выключены.";
        if(sequence.clips.size()<2)return "Переход на входе и выходе клипа: "+effects.transition;
        int overlap=sequence.length()-duration();
        return "Переходы между клипами: "+effects.transition+" · до "+effects.transitionMs+" мс\nСуммарное перекрытие: "+ClipMath.clockCode(overlap);
    }
    private void showJob() {
        if(progress==null)return;
        progress.setVisibility(job.running?View.VISIBLE:View.GONE);
        progress.setProgress(job.percent);
        cancelIcon.setVisibility(job.running?View.VISIBLE:View.GONE);
        if(!job.message.isEmpty())status(job.message+(job.running?" "+job.percent+"%":""));
        if(job.result!=null&&!job.generated) {
            lastExport=job.result.uri;
            getSharedPreferences("sequence_editor",MODE_PRIVATE).edit().putString("export",lastExport.toString()).apply();
        }
        if(exportWasRunning&&!job.running&&resumed&&!isFinishing()) {
            if(job.result!=null&&!job.generated)new AlertDialog.Builder(this).setTitle("Rendering complete")
                    .setMessage(job.message).setPositiveButton("Открыть",(dialog,which)->result(false))
                    .setNeutralButton("Поделиться",(dialog,which)->result(true)).setNegativeButton("Закрыть",null).show();
            if(previewStopped&&!sequence.clips.isEmpty())loadPreview(false);
        }
        exportWasRunning=job.running;
        updateControls();
    }
    private void result(boolean share) {
        if(lastExport==null)return;
        Intent intent=new Intent(share?Intent.ACTION_SEND:Intent.ACTION_VIEW);
        if(share){intent.setType("video/mp4");intent.putExtra(Intent.EXTRA_STREAM,lastExport);}
        else intent.setDataAndType(lastExport,"video/mp4");
        intent.setClipData(android.content.ClipData.newRawUri("Vegas edit",lastExport));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {startActivity(share?Intent.createChooser(intent,"Поделиться"):intent);}
        catch(ActivityNotFoundException|SecurityException error){status("Не удалось открыть результат. Проверьте его в Галерее: "+detail(error));}
    }
    private void saveAs() {
        if(busy||job.running)return;
        try {
            captureCursor();pendingJson=json();
            launch(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json")
                    .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"VegasTimeline.vvp"),SAVE);
        } catch(Exception error){status("Не удалось подготовить проект: "+detail(error));}
    }
    private void persist() {
        try {getSharedPreferences("sequence_editor",MODE_PRIVATE).edit().putString("project",json()).apply();}
        catch(Exception error){status("Не удалось сохранить восстановление проекта: "+detail(error));}
    }
    private void captureCursor() {
        if(prepared&&!previewStopped&&!busy&&!job.running)cursor=ClipMath.clamp(preview.getCurrentPosition(),0,duration());
    }
    private void updateTime() {
        timeline.position(cursor);
        time.setText(ClipMath.clockCode(cursor)+" / "+ClipMath.clockCode(duration())+"   ·   "+sequence.clips.size()+" events");
    }
    private void updateControls() {
        if(playIcon==null)return;
        boolean active=!busy&&!job.running,has=!sequence.clips.isEmpty();
        if(resumed&&active&&has&&previewStopped){loadPreview(false);return;}
        for(View action:generalActions)action.setEnabled(active);
        for(View action:clipActions)action.setEnabled(active&&has);
        for(int i=0;i<clipList.getChildCount();i++)clipList.getChildAt(i).setEnabled(active);
        undoIcon.setEnabled(active&&!undo.isEmpty());
        redoIcon.setEnabled(active&&!redo.isEmpty());
        moveLeftIcon.setEnabled(active&&selected>0);
        moveRightIcon.setEnabled(active&&selected>=0&&selected+1<sequence.clips.size());
        playIcon.select(preview.isPlaying());
        muteIcon.select(muted);
        timeline.setEnabled(active&&has);
        cancelIcon.setEnabled(job.running);
        cancelIcon.setVisibility(job.running?View.VISIBLE:View.GONE);
        progress.setVisibility(job.running?View.VISIBLE:View.GONE);
    }

    @Override public boolean onKeyDown(int key,KeyEvent event) {
        if(getCurrentFocus() instanceof EditText)return super.onKeyDown(key,event);
        if(event.isCtrlPressed()) {
            if(key==KeyEvent.KEYCODE_Z){history(event.isShiftPressed());return true;}
            if(key==KeyEvent.KEYCODE_Y){history(true);return true;}
            if(key==KeyEvent.KEYCODE_O){pickProject();return true;}
            if(key==KeyEvent.KEYCODE_I){pickVideos();return true;}
            if(key==KeyEvent.KEYCODE_S){saveAs();return true;}
        }
        if(key==KeyEvent.KEYCODE_S){split();return true;}
        if(key==KeyEvent.KEYCODE_FORWARD_DEL||key==KeyEvent.KEYCODE_DEL){delete();return true;}
        if(key==KeyEvent.KEYCODE_SPACE){toggle();return true;}
        if(key==KeyEvent.KEYCODE_DPAD_LEFT){seek(cursor-1000);return true;}
        if(key==KeyEvent.KEYCODE_DPAD_RIGHT){seek((int)Math.min(duration(),(long)cursor+1000));return true;}
        if(key==KeyEvent.KEYCODE_HOME){seek(0);return true;}
        if(key==KeyEvent.KEYCODE_MOVE_END){seek(duration());return true;}
        return super.onKeyDown(key,event);
    }
    @Override protected void onSaveInstanceState(Bundle out) {
        captureCursor();
        try {out.putString("project",json());}catch(Exception ignored) { }
        out.putString("pendingJson",pendingJson);
        out.putStringArrayList("pendingImports",new ArrayList<>(pendingImports));
        out.putStringArrayList("undo",saveStack(undo));
        out.putStringArrayList("redo",saveStack(redo));
        super.onSaveInstanceState(out);
    }
    private static ArrayList<String> saveStack(ArrayDeque<String> stack) {
        ArrayList<String> saved=new ArrayList<>();
        int chars=0;
        java.util.Iterator<String> values=stack.descendingIterator();
        while(values.hasNext()) {
            String item=values.next();
            if(chars+item.length()>32768)break;
            saved.add(0,item);chars+=item.length();
        }
        return saved;
    }
    private static void restoreStack(ArrayList<String> saved,ArrayDeque<String> stack) {
        if(saved==null)return;
        for(String item:saved)if(item!=null&&!item.isEmpty()&&stack.size()<HISTORY_LIMIT)stack.addLast(item);
    }
    @Override protected void onStart(){super.onStart();job.attach(jobListener);}
    @Override protected void onResume() {
        super.onResume();resumed=true;
        if(previewStopped&&!job.running&&!busy)loadPreview(false);
        handler.removeCallbacks(tick);handler.post(tick);
    }
    @Override protected void onPause() {
        captureCursor();resumed=false;handler.removeCallbacks(tick);preview.pause();persist();super.onPause();
    }
    @Override protected void onStop() {
        job.detach(jobListener);
        preview.stopPlayback();previewStopped=true;prepared=false;
        super.onStop();
    }
    @Override protected void onDestroy() {
        operation++;handler.removeCallbacks(tick);worker.shutdownNow();preview.release();super.onDestroy();
    }
    private void status(String message) {
        if(status==null||message==null)return;
        status.setText(message.replace('\n',' '));status.setTooltipText(message);
    }
    private void message(String title,String body) {
        if(!isFinishing()&&!isDestroyed())new AlertDialog.Builder(this).setTitle(title).setMessage(body).setPositiveButton("OK",null).show();
    }
    private static String detail(Throwable error) {
        return error.getMessage()!=null&&!error.getMessage().trim().isEmpty()?error.getMessage():error.getClass().getSimpleName();
    }
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private LinearLayout row(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.HORIZONTAL);view.setBackgroundColor(BG);return view;}
    private LinearLayout column(){LinearLayout view=new LinearLayout(this);view.setOrientation(LinearLayout.VERTICAL);view.setBackgroundColor(BG);return view;}
    private LinearLayout.LayoutParams lp(int width,int height){return new LinearLayout.LayoutParams(width<0?width:dp(width),height<0?height:dp(height));}
    private TextView text(String value,int size){TextView view=new TextView(this);view.setText(value);view.setTextSize(size);view.setTextColor(FG);view.setGravity(Gravity.CENTER_VERTICAL);return view;}
    private HorizontalScrollView scroll(){HorizontalScrollView view=new HorizontalScrollView(this);view.setHorizontalScrollBarEnabled(false);return view;}
    private ClassicIcon button(LinearLayout parent,String kind,String name,Runnable action,boolean needsClip) {
        ClassicIcon view=new ClassicIcon(this,kind,name);parent.addView(view,lp(36,36));
        view.setOnClickListener(ignored->{
            if(view!=cancelIcon&&(busy||job.running))return;
            try {action.run();}catch(RuntimeException error){status("Не удалось выполнить действие: "+detail(error));}
        });
        if(needsClip)clipActions.add(view);else generalActions.add(view);
        return view;
    }
}
