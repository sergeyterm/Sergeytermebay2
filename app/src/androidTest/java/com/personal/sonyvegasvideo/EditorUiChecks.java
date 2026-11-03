package com.personal.sonyvegasvideo;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import java.util.Map;

/** End-to-end native UI checks. Android document-result mocks keep CI independent of picker UI locale. */
final class EditorUiChecks {
    private final NativeTestSupport s;
    private Activity main,sequence;
    private String phase="setup";
    private int checks;
    private EditorUiChecks(NativeTestSupport support){s=support;}
    static String run(Instrumentation test,String size)throws Exception{
        try(NativeTestSupport support=new NativeTestSupport(test)){
            SharedPreferences editor=support.context.getSharedPreferences("editor",0),montage=support.context.getSharedPreferences("sequence_editor",0);
            Map<String,?> oldEditor=editor.getAll(),oldMontage=montage.getAll();editor.edit().clear().commit();montage.edit().clear().commit();
            EditorUiChecks suite=new EditorUiChecks(support);
            try{suite.execute(size);return "native UI "+size+": "+suite.checks+" checks; all menu groups, real controls/dialogs, mocked document result roundtrips, invalid inputs/recovery, undo/redo, sequence editing, external picker return, recreation, render/share intents, generated clip; screenshots captured";}
            catch(Throwable failure){try{support.screenshot("qa-ui-failure-"+size);}catch(Exception ignored){}throw new AssertionError("UI phase: "+suite.phase,failure);}
            finally{
                support.main(()->{ExportJob.get().cancel();return null;});support.await(()->!support.main(()->ExportJob.get().running),15000,"UI export did not stop");
                suite.finish(suite.sequence);suite.finish(suite.main);NativeTestSupport.restore(editor,oldEditor);NativeTestSupport.restore(montage,oldMontage);
            }
        }
    }
    private void execute(String size)throws Exception{
        Uri source=s.fixture("rotated-av.mp4"),red=s.fixture("color-red.mp4"),blue=s.fixture("color-blue.mp4"),music=s.fixture("different-av.mp4");
        Uri saved=s.document("roundtrip.vvp",""),sequenceSave=s.document("montage.vvp",""),invalid=s.document("bad.vvp","{broken json");
        step("project codec compatibility and malformed inputs");projectCodec(source);
        step("open native workspace and import through a document-result mock");
        main=s.test.startActivitySync(new Intent(s.context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));s.test.waitForIdleSync();
        s.await(()->s.main(()->main.hasWindowFocus()&&main.getWindow().getDecorView().getWidth()>0),10000,"Main workspace never received window focus");
        check(s.main(()->!description(main,"Render As… — сохранить MP4").isEnabled()),"Render enabled with no media");
        for(String[] menu:new String[][]{{"File","New"},{"Edit","Select all"},{"View","Hide docking area"},{"Insert","Marker"},{"Tools","Render As…"},{"Options","Loop playback"},{"Help","About…"}}){
            s.clickText(main,menu[0]);s.menuReady(menu[1]);check(s.hasText(menu[1]),"Menu item missing: "+menu[0]+" / "+menu[1]);s.back();
        }
        importMain(source);EditorPreview preview=preview(main);ready(main);check(!s.main(preview::isPlaying),"Import started playback");check(s.main(preview::getDuration)>5000,"Imported media duration missing");
        step("docking, Explorer, preview sizing and visible paused video");workspaceViews(source);
        crossClipPreview(red,blue,size);

        step("precise range validation, undo and redo");
        s.clickDescription(main,"Точные границы фрагмента");s.setEditable(0,"00:00:00.500");s.setEditable(1,"00:00:02.500");s.windowClick("OK");
        awaitRange("00:00:00.500","00:00:02.500");
        s.clickDescription(main,"Точные границы фрагмента");s.setEditable(0,"00:00:03.000");s.setEditable(1,"00:00:02.000");s.windowClick("OK");
        check(s.hasText("Loop region"),"Invalid trim dismissed its validation dialog");s.windowClick("Отмена");awaitRange("00:00:00.500","00:00:02.500");
        s.clickDescription(main,"Отменить изменение");check(!range().contains("00:00:02.500"),"Undo failed to restore range");
        s.clickDescription(main,"Повторить изменение");awaitRange("00:00:00.500","00:00:02.500");
        step("zoom, fit, both transport rows and local save");zoomAndLocalSave();
        step("drawn video track slider commits one undoable opacity edit");
        float oldOpacity=saveMain(saved).effects.videoOpacity;TimelineView headerTimeline=s.main(()->NativeTestSupport.findClass(main.getWindow().getDecorView(),TimelineView.class));
        s.main(()->{float density=headerTimeline.getResources().getDisplayMetrics().density;float header=Math.max(98*density,Math.min(150*density,headerTimeline.getWidth()*.27f)),y=99*density;long now=SystemClock.uptimeMillis();android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,header*.8f,y,0),move=android.view.MotionEvent.obtain(now,now+80,android.view.MotionEvent.ACTION_MOVE,header*.4f,y,0),up=android.view.MotionEvent.obtain(now,now+120,android.view.MotionEvent.ACTION_UP,header*.4f,y,0);try{headerTimeline.dispatchTouchEvent(down);headerTimeline.dispatchTouchEvent(move);headerTimeline.dispatchTouchEvent(up);}finally{down.recycle();move.recycle();up.recycle();}return null;});s.settle();
        float draggedOpacity=saveMain(saved).effects.videoOpacity;check(draggedOpacity>0&&draggedOpacity<.75f&&Math.abs(draggedOpacity-oldOpacity)>.1f,"Drawn video Level slider did not change saved output opacity");
        s.clickDescription(main,"Отменить изменение");ready(main);check(Math.abs(saveMain(saved).effects.videoOpacity-oldOpacity)<.01f,"One Undo did not restore the entire track-slider gesture");

        step("transport, frame stepping and loop playback");
        s.clickDescription(main,"Воспроизвести / пауза");s.await(()->s.main(preview::isPlaying),10000,"Playback never started");
        s.await(()->s.main(preview::getCurrentPosition)>650,10000,"Playing clock does not advance");
        s.clickDescription(main,"Пауза");s.await(()->!s.main(preview::isPlaying),5000,"Pause did not stop playback");
        s.clickDescription(main,"Остановить");s.await(()->Math.abs(s.main(preview::getCurrentPosition)-500)<100,5000,"Stop did not seek to selection start");
        int position=s.main(preview::getCurrentPosition);s.clickDescription(main,"Следующий кадр");s.await(()->s.main(preview::getCurrentPosition)>position,5000,"Next frame did not seek");
        s.clickDescription(main,"Предыдущий кадр");s.await(()->s.main(preview::getCurrentPosition)<=position+10,5000,"Previous frame did not seek");
        s.clickDescription(main,"Зациклить выделенный фрагмент");check(s.main(()->description(main,"Зациклить выделенный фрагмент").isSelected()),"Loop did not activate");
        s.clickDescription(main,"Воспроизвести / пауза");SystemClock.sleep(2700);
        s.await(()->s.main(preview::isPlaying)&&s.main(preview::getCurrentPosition)<2600,5000,"Loop stopped or escaped selection");check(true,"Loop continues");
        s.clickDescription(main,"Пауза");s.clickDescription(main,"Зациклить выделенный фрагмент");
        View rate=s.main(()->NativeTestSupport.textPrefix(main.getWindow().getDecorView(),"Rate:"));s.click(rate);s.windowClick("1.5×");
        check(s.main(()->((TextView)rate).getText().toString().contains("1.50")),"Playback rate label not updated");s.click(rate);s.windowClick("1.0×");
        step("properties, help and About report usable information");informationDialogs();

        step("mute, marker editing and history");
        s.clickDescription(main,"Отключить звук исходного ролика");check(s.main(()->description(main,"Отключить звук исходного ролика").isSelected()),"Mute did not activate");
        s.clickDescription(main,"Отменить изменение");check(s.main(()->!description(main,"Отключить звук исходного ролика").isSelected()),"Undo did not restore mute");
        s.clickDescription(main,"Добавить маркер");ProjectCodec.Project marked=saveMain(saved);check(marked.markers.size()==1,"Marker not saved");
        s.clickDescription(main,"Отменить изменение");check(saveMain(saved).markers.isEmpty(),"Undo did not remove marker");
        s.clickDescription(main,"Повторить изменение");check(saveMain(saved).markers.size()==1,"Redo did not restore marker");
        s.clickText(main,"Insert");s.windowClick("Markers…");check(s.hasText("Markers"),"Marker list missing");s.windowClick("Закрыть");

        step("all effect settings dialogs and title undo/redo");
        for(String[] setting:new String[][]{{"Настройки эффектов","Video FX"},{"Настройки переходов","Transitions — появление и исчезновение"},{"Слои видео и аудио","Видео- и аудиослои"}}){
            s.clickDescription(main,setting[0]);check(s.hasText(setting[1]),"Missing settings dialog "+setting[1]);s.windowClick("Отмена");
        }
        s.clickText(main,"Transitions");for(String item:new String[]{"Dissolve","Cross Effect","Wipe","3D Cascade"}){s.clickDescription(main,item);check(s.hasText("Transitions — появление и исчезновение"),"Transition palette item does not open settings: "+item);s.windowClick("Отмена");}
        s.clickText(main,"Video FX");for(String item:new String[]{"Brightness and Contrast","Color Corrector","Chroma Keyer","Gaussian Blur","Mosaic","Color Filters","Zoom & Rotation"}){s.clickDescription(main,item);check(s.hasText(item.equals("Zoom & Rotation")?"Анимация — два ключевых состояния":"Video FX"),"Effect palette item does not open settings: "+item);s.windowClick("Отмена");}
        s.clickDescription(main,"Настройки эффектов");s.setProgressField("brightness",650);s.windowClick("Применить");ready(main);
        check(Math.abs(saveMain(saved).effects.brightness-.3f)<.015,"Effect slider Apply was not committed");
        s.clickDescription(main,"Отменить изменение");ready(main);check(Math.abs(saveMain(saved).effects.brightness)<.015,"Effect slider Undo did not restore its value");
        s.clickDescription(main,"Повторить изменение");ready(main);check(Math.abs(saveMain(saved).effects.brightness-.3f)<.015,"Effect slider Redo did not restore its value");
        s.clickDescription(main,"Reset effects");ready(main);check(saveMain(saved).effects.isIdentity(),"Reset effects did not clear the applied filter");
        s.clickDescription(main,"Текст и титры");s.setField("title","VEGAS QA");s.setField("titleColor","not-a-color");s.windowClick("Применить");check(s.hasText("Titles & Text"),"Invalid title color dismissed the settings dialog");s.setField("titleColor","#ffffff");s.windowClick("Применить");ready(main);
        check("VEGAS QA".equals(saveMain(saved).effects.title),"Title settings not saved");
        s.clickDescription(main,"Отменить изменение");ready(main);check(saveMain(saved).effects.title.isEmpty(),"Undo did not remove title");
        s.clickDescription(main,"Повторить изменение");ready(main);check("VEGAS QA".equals(saveMain(saved).effects.title),"Redo did not restore title");
        s.clickText(main,"Options");s.windowClick("Project volume…");s.setEditable(0,"35");s.windowClick("OK");
        check(Math.abs(saveMain(saved).effects.masterGain-.35f)<.02,"Master gain not saved for export");

        step("video/audio layer pickers and common project save/open");
        Instrumentation.ActivityMonitor pick=s.picker(Intent.ACTION_OPEN_DOCUMENT,red);s.clickText(main,"Insert");s.windowClick("Video track");s.picked(pick);s.await(()->s.hasText("Видео- и аудиослои"),15000,"Video layer was not validated");s.windowClick("Применить");ready(main);
        check(red.toString().equals(saveMain(saved).effects.overlayUri),"Video layer picker did not update project");
        pick=s.picker(Intent.ACTION_OPEN_DOCUMENT,music);s.clickText(main,"Insert");s.windowClick("Audio track");s.picked(pick);s.await(()->s.hasText("Видео- и аудиослои"),15000,"Audio layer was not validated");s.windowClick("Применить");ready(main);
        ProjectCodec.Project configured=saveMain(saved);check(music.toString().equals(configured.effects.audioUri),"Audio picker did not update project");
        s.clickDescription(main,"Слои видео и аудио");s.windowDescriptionClick("removeOverlay");s.windowDescriptionClick("removeAudio");s.windowClick("Применить");ready(main);
        ProjectCodec.Project removed=saveMain(saved);check(removed.effects.overlayUri.isEmpty()&&removed.effects.audioUri.isEmpty()&&removed.effects.title.equals(configured.effects.title),"Removing layers changed unrelated settings or retained a layer");
        s.clickDescription(main,"Отменить изменение");ready(main);check(saveMain(saved).effects.equals(configured.effects),"Undo did not restore removed video/audio layers");
        openMain(saved);ready(main);ProjectCodec.Project reopened=saveMain(saved);
        check(reopened.effects.equals(configured.effects)&&reopened.sequence.length()==configured.sequence.length(),"Project roundtrip lost effects/layers/range");

        step("actual system picker cancel and activity recreation");
        android.content.ComponentName pickerComponent=s.systemVideoPicker();
        s.await(()->s.main(main::hasWindowFocus)&&s.focusedApplication(s.context.getPackageName(),true),10000,"Main workspace was not focused before opening real picker");
        s.clickDescription(main,"Открыть видео");s.await(()->s.focusedApplication(pickerComponent.getPackageName(),true),20000,"Real document picker did not expose a focused interactive window");s.settle();s.back();
        s.await(()->s.main(main::hasWindowFocus)&&s.focusedApplication(s.context.getPackageName(),true),15000,"Did not return from document picker");ready(main);
        main=recreate(main,MainActivity.class);ready(main);check(saveMain(saved).effects.equals(configured.effects),"Activity recreation lost project effects");
        s.screenshot("ui-full-"+size);

        step("render button and result open/share intents");
        final Uri[] rendered=new Uri[1];
        s.clickDescription(main,"Render As… — сохранить MP4");s.windowClick("Render");s.await(()->s.main(()->{ExportJob job=ExportJob.get();ClipExporter.Result result=job.result;if(job.running||result==null)return false;rendered[0]=result.uri;return true;}),120000,"UI Render did not complete: "+s.main(()->ExportJob.get().message));
        Uri output=rendered[0];s.own(output);s.dismissIfPresent("Закрыть");
        check(output.getScheme().equals("content"),"Rendered result is not shareable content");
        android.content.IntentFilter viewFilter=new android.content.IntentFilter(Intent.ACTION_VIEW);viewFilter.addDataType("video/mp4");viewFilter.addDataScheme("content");
        Instrumentation.ActivityMonitor opened=s.test.addMonitor(viewFilter,new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null),true);
        s.clickText(main,"File");s.windowClick("Open rendered video");s.await(()->opened.getHits()>0,5000,"Open rendered video did not launch");s.test.removeMonitor(opened);
        Instrumentation.ActivityMonitor shared=s.test.addMonitor(new android.content.IntentFilter(Intent.ACTION_CHOOSER),new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null),true);
        s.clickText(main,"File");s.windowClick("Share rendered video");s.await(()->shared.getHits()>0,5000,"Share rendered video did not launch a chooser");s.test.removeMonitor(shared);
        ProjectCodec.Project plain=new ProjectCodec.Project();plain.single=true;plain.sequence=new Sequence().append(new Sequence.Clip(source.toString(),"QA source",0,6000));
        Uri plainProject=s.document("plain-sequence.vvp",ProjectCodec.write(plain));openMain(plainProject);ready(main);

        step("sequence exact split, reorder, delete, trim, history and persistence");
        Instrumentation.ActivityMonitor sequenceMonitor=s.test.addMonitor(SequenceActivity.class.getName(),null,false);
        s.clickDescription(main,"Монтаж: разрезать, удалить и склеить");sequence=s.test.waitForMonitorWithTimeout(sequenceMonitor,15000);s.test.removeMonitor(sequenceMonitor);check(sequence!=null,"Timeline editor did not open");sequenceReady();
        for(String[] menu:new String[][]{{"File","New"},{"Edit","Split (S)"},{"View","Zoom in"},{"Insert","Video events…"},{"Tools","Video FX…"},{"Help","Editing help"}}){s.clickText(sequence,menu[0]);s.menuReady(menu[1]);check(s.hasText(menu[1]),"Missing sequence menu item "+menu[1]);s.back();}
        SequenceTimeline timeline=s.main(()->NativeTestSupport.findClass(sequence.getWindow().getDecorView(),SequenceTimeline.class));check(timeline!=null,"Missing sequence timeline");
        s.main(()->{Bundle args=new Bundle();args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,900);check(timeline.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),args),"Accessible exact seek not supported");return null;});
        s.clickDescription(sequence,"Разрезать в позиции курсора (S)");s.await(()->eventCount()==2,10000,"Exact split did not create two events");
        ProjectCodec.Project split=saveSequence(sequenceSave);check(split.sequence.clips.get(0).length()==900,"Split rounded away from requested project cursor");
        s.clickDescription(sequence,"Undo");s.await(()->eventCount()==1,5000,"Sequence undo failed");s.clickDescription(sequence,"Redo");s.await(()->eventCount()==2,5000,"Sequence redo failed");
        s.clickDescription(sequence,"Переместить фрагмент влево");ProjectCodec.Project moved=saveSequence(sequenceSave);check(moved.sequence.clips.get(0).inMs==split.sequence.clips.get(1).inMs,"Move left did not reorder events");
        s.clickDescription(sequence,"Удалить выбранный фрагмент (Delete)");s.await(()->eventCount()==1,5000,"Ripple delete did not remove event");s.clickDescription(sequence,"Undo");s.await(()->eventCount()==2,5000,"Undo did not restore deleted event");
        s.clickDescription(sequence,"Точные границы выбранного фрагмента");s.setEditable(0,"00:00:01.500");s.setEditable(1,"00:00:02.400");s.windowClick("Применить");
        ProjectCodec.Project trimmed=saveSequence(sequenceSave);check(trimmed.sequence.length()!=moved.sequence.length(),"Precise sequence trim did not change duration");
        for(String control:new String[]{"Zoom in","Zoom out","Fit project","Mute audio","Mute audio"})s.clickDescription(sequence,control);
        sequence=recreate(sequence,SequenceActivity.class);sequenceReady();check(saveSequence(sequenceSave).sequence.length()==trimmed.sequence.length(),"Sequence recreation lost edits");
        s.clickDescription(sequence,"Undo");check(saveSequence(sequenceSave).muted,"Mute undo was lost on recreation");
        s.clickDescription(sequence,"Undo");check(!saveSequence(sequenceSave).muted,"Second mute undo failed");
        s.clickDescription(sequence,"Undo");check(saveSequence(sequenceSave).sequence.length()==moved.sequence.length(),"Sequence trim history was lost on recreation");
        s.screenshot("qa-sequence-"+size);
        Instrumentation.ActivityMonitor reopen=s.picker(Intent.ACTION_OPEN_DOCUMENT,sequenceSave);s.clickText(sequence,"File");s.windowClick("Open project…");s.picked(reopen);sequenceReady();check(saveSequence(sequenceSave).sequence.length()==moved.sequence.length(),"Sequence Open project lost the saved edit list");
        finish(sequence);sequence=null;s.await(()->s.main(main::hasWindowFocus),10000,"Main workspace not restored");ready(main);

        step("invalid projects/media report errors and recover");
        openMain(invalid);check(s.main(()->!main.isFinishing()),"Malformed project closed editor");
        Uri broken=s.corruptVideo();importMain(broken);s.await(()->{String text=s.windowText().toLowerCase(java.util.Locale.ROOT);return text.contains("недоступ")||text.contains("не удалось")||text.contains("ошибка");},15000,"Invalid media did not report an error");
        check(!s.main(()->description(main,"Render As… — сохранить MP4").isEnabled()),"Invalid media enabled render");
        importMain(source);ready(main);check(!s.main(preview(main)::isPlaying),"Recovery import auto-started playback");

        step("generator button creates a playable clip");
        s.clickText(main,"Media Generators");for(String item:new String[]{"Solid Color","Titles & Text","Checkerboard","Color Gradient"}){s.clickDescription(main,item);check(s.hasText("Media Generator"),"Generator configuration missing: "+item);s.windowClick("Отмена");}
        s.clickText(main,"Media Generators");s.clickDescription(main,"Solid Color");check(s.hasText("Media Generator"),"Generator dialog did not open");
        Uri previous=s.main(()->ExportJob.get().result.uri);s.setField("Длительность генератора","1");s.setField("Цвет генератора","#2050D0");s.windowClick("Создать");
        final Uri[] generated=new Uri[1];
        s.await(()->s.main(()->{ExportJob job=ExportJob.get();ClipExporter.Result result=job.result;if(job.running||result==null||previous.equals(result.uri))return false;generated[0]=result.uri;return true;}),120000,"Generator failed: "+s.main(()->ExportJob.get().message));s.own(generated[0]);ready(main);
        check(s.main(preview(main)::getDuration)>=900&&s.main(preview(main)::getDuration)<=1250,"Generated clip was not imported into preview");
        step("new project clears state and disables media-only actions");s.clickDescription(main,"Новый проект");
        check(!s.main(()->description(main,"Render As… — сохранить MP4").isEnabled()),"New project retains enabled Render");
        s.screenshot("qa-empty-"+size);
        s.clickText(main,"File");s.windowClick("Restore previous project");ready(main);
        ProjectCodec.Project restored=saveMain(saved);check(restored.sequence.clips.size()==1&&restored.sequence.length()>=900&&restored.sequence.length()<=1250,"Restore previous project did not recover the generated clip");
    }
    private void crossClipPreview(Uri red,Uri blue,String size)throws Exception{
        step("real cross-clip preview: odd-only tail, overlap pixels and playback clock");
        ProjectCodec.Project overlapProject=new ProjectCodec.Project();overlapProject.sequence=new Sequence().append(new Sequence.Clip(red.toString(),"red",0,1000)).append(new Sequence.Clip(blue.toString(),"blue",0,1000));overlapProject.effects.transition="dissolve";overlapProject.effects.transitionMs=400;
        String json=ProjectCodec.write(overlapProject);Instrumentation.ActivityMonitor monitor=s.test.addMonitor(SequenceActivity.class.getName(),null,false);
        s.main(()->{main.startActivity(new Intent(main,SequenceActivity.class).putExtra("projectJson",json));return null;});sequence=s.test.waitForMonitorWithTimeout(monitor,15000);s.test.removeMonitor(monitor);check(sequence!=null,"Cross-clip preview workspace did not open");sequenceReady();
        s.await(()->s.main(preview(sequence)::getDuration)>=1550&&s.main(preview(sequence)::getDuration)<=1650,10000,"Preview did not use the shortened overlap timeline");
        for(int iteration=1;iteration<=2;iteration++){
            step("cross-clip backward seek iteration "+iteration+"/2");
            seekSequence(1300);s.await(()->{double[] color=previewRgb(sequence);return color[2]>.65&&color[0]<.2;},15000,"Odd-lane preview did not show the blue clip after the first clip ended, iteration "+iteration);
            s.screenshot("qa-transition-blue-"+size);seekSequence(800);
            try{s.await(()->{double[] color=previewRgb(sequence);return color[0]>.25&&color[2]>.25&&color[1]<.2;},15000,"Paused overlap preview did not blend both source clips, iteration "+iteration);}
            catch(AssertionError failure){String diagnostics;try{diagnostics=previewDiagnostics(sequence);}catch(Throwable unavailable){diagnostics="Private state diagnostics unavailable: "+unavailable;}s.step("Paused overlap private state, iteration "+iteration+": "+diagnostics);s.screenshot("qa-transition-blend-failure-"+size);throw new AssertionError("Paused overlap iteration="+iteration+", RGB="+java.util.Arrays.toString(previewRgb(sequence))+", position="+s.main(preview(sequence)::getCurrentPosition)+", duration="+s.main(preview(sequence)::getDuration)+", error="+s.main(preview(sequence)::getErrorMessage)+"\n"+diagnostics,failure);}
            s.screenshot("qa-transition-blend-"+size);
        }
        seekSequence(500);s.clickDescription(sequence,"Play / Pause");s.await(()->s.main(preview(sequence)::getCurrentPosition)>1100,10000,"Preview clock froze when playback entered the odd-only section");
        s.clickDescription(sequence,"Stop");check(s.main(preview(sequence)::getDuration)>=1550&&s.main(preview(sequence)::getDuration)<=1650,"Transition playback changed project duration");finish(sequence);sequence=null;
        s.await(()->s.main(main::hasWindowFocus)&&s.focusedApplication(s.context.getPackageName(),true),10000,"Main workspace was not restored after early cross-clip preview");ready(main);
    }
    private void workspaceViews(Uri source)throws Exception{
        EditorPreview video=preview(main);View libraryTab=s.main(()->NativeTestSupport.findText(main.getWindow().getDecorView(),"Project Media"));int width=s.main(video::getWidth);
        s.clickText(main,"View");s.windowClick("Hide docking area");s.await(()->!s.main(libraryTab::isShown),5000,"Hide docking area left the library visible");
        s.await(()->s.main(video::getWidth)>width,5000,"Hiding library did not enlarge preview");
        s.clickText(main,"View");s.windowClick("Show docking area");s.await(()->s.main(libraryTab::isShown),5000,"Show docking area did not restore library");
        s.clickText(main,"View");s.windowClick("Explorer");check(s.main(()->NativeTestSupport.findText(main.getWindow().getDecorView(),"▸  Открыть файл…")!=null),"Explorer did not expose file import");
        Instrumentation.ActivityMonitor monitor=s.picker(Intent.ACTION_OPEN_DOCUMENT,source);s.clickText(main,"▸  Открыть файл…");s.picked(monitor);ready(main);
        s.clickText(main,"View");s.windowClick("Project Media");check(s.main(libraryTab::isShown),"Project Media menu did not restore media library");
        s.await(()->colorfulPreview(),15000,"Paused video did not produce visible colored pixels");long fit=surfaceSize();for(String mode:new String[]{"Fill","Zoom"}){s.clickDescription(main,"Размер предпросмотра");s.windowClick(mode);check(s.main(()->((TextView)description(main,"Размер предпросмотра")).getText().toString().equals(mode+" ▾")),"Preview resize selector did not update");s.await(()->surfaceSize()!=fit,5000,"Resize mode did not change actual video surface geometry: "+mode);}
        s.clickDescription(main,"Размер предпросмотра");s.windowClick("Fit");s.await(()->surfaceSize()==fit,5000,"Fit did not restore video surface geometry");
        s.await(()->colorfulPreview(),15000,"Paused video did not produce visible colored pixels");check(true,"Paused video visible");
    }
    private void zoomAndLocalSave(){
        s.clickDescription(main,"К концу выделения");s.await(()->Math.abs(s.main(preview(main)::getCurrentPosition)-2500)<100,5000,"Preview end button did not seek");
        s.clickDescription(main,"К началу выделения");s.await(()->Math.abs(s.main(preview(main)::getCurrentPosition)-500)<100,5000,"Preview begin button did not seek");
        s.clickDescription(main,"К концу");s.await(()->Math.abs(s.main(preview(main)::getCurrentPosition)-2500)<100,5000,"Bottom end button did not seek");
        s.clickDescription(main,"К началу");s.await(()->Math.abs(s.main(preview(main)::getCurrentPosition)-500)<100,5000,"Bottom begin button did not seek");
        s.clickDescription(main,"Весь ролик");long fit=rulerHash();s.clickDescription(main,"Увеличить масштаб");long zoomed=rulerHash();check(zoomed!=fit,"Zoom in did not redraw timeline ruler");
        s.clickDescription(main,"Уменьшить масштаб");check(rulerHash()!=zoomed,"Zoom out did not redraw timeline ruler");s.clickDescription(main,"Весь ролик");check(rulerHash()==fit,"Fit project did not restore ruler scale/offset");
        s.clickDescription(main,"Сохранить проект");SharedPreferences prefs=s.context.getSharedPreferences("editor",0);check(prefs.getInt("start",-1)==500&&prefs.getInt("end",-1)==2500,"Local save did not store selected range");
        TextView status=s.main(()->NativeTestSupport.textPrefix(main.getWindow().getDecorView(),"Настройки сохранены."));if(status==null)status=s.main(()->NativeTestSupport.textPrefix(main.getWindow().getDecorView(),"Проект сохранён."));check(status!=null,"Local save did not report result");s.click(status);s.menuReady("Состояние");check(s.windowText().contains("сохран"),"Status detail dialog lost its message");s.windowClick("OK");
    }
    private void informationDialogs(){
        s.await(()->s.main(()->{TextView metadata=NativeTestSupport.textPrefix(main.getWindow().getDecorView(),"Project:");return metadata!=null&&metadata.getText().toString().contains("×");}),25000,"Media metadata did not load");
        s.clickDescription(main,"Свойства медиа");s.menuReady("Media properties");check(s.windowText().contains("Video:")&&s.windowText().contains("Audio:"),"Properties did not include actual video/audio metadata");s.windowClick("OK");
        s.clickDescription(main,"Справка");s.menuReady("Vegas Video — управление");check(s.windowText().contains("Video FX")&&s.windowText().contains("Render As"),"Help omitted current editing/export operations");s.windowClick("OK");
        s.clickText(main,"Help");s.windowClick("About…");s.await(()->s.windowText().contains("Личный Android-видеоредактор"),5000,"About did not describe this application");check(s.windowText().contains("Неофициальное"),"About misidentified the app");s.windowClick("OK");
    }
    private long surfaceSize(){return s.main(()->{EditorPreview player=NativeTestSupport.findClass(main.getWindow().getDecorView(),EditorPreview.class);View surface=NativeTestSupport.findClass(player,android.view.SurfaceView.class);if(surface==null)surface=NativeTestSupport.findClass(player,android.view.TextureView.class);NativeTestSupport.require(surface!=null&&surface.getWidth()>0&&surface.getHeight()>0,"Video surface was not laid out");return ((long)surface.getWidth()<<32)|surface.getHeight();});}
    private boolean colorfulPreview(){
        android.graphics.Rect bounds=s.main(()->{android.graphics.Rect out=new android.graphics.Rect();EditorPreview player=NativeTestSupport.findClass(main.getWindow().getDecorView(),EditorPreview.class);player.getGlobalVisibleRect(out);return out;});
        android.graphics.Bitmap image=s.test.getUiAutomation().takeScreenshot();if(image==null||bounds.isEmpty())return false;
        try{int colored=0,total=0;int left=Math.max(0,bounds.left+bounds.width()/5),right=Math.min(image.getWidth(),bounds.right-bounds.width()/5),top=Math.max(0,bounds.top+bounds.height()/5),bottom=Math.min(image.getHeight(),bounds.bottom-bounds.height()/5);for(int y=top;y<bottom;y+=4)for(int x=left;x<right;x+=4){int pixel=image.getPixel(x,y),r=android.graphics.Color.red(pixel),g=android.graphics.Color.green(pixel),b=android.graphics.Color.blue(pixel);if(Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))>35)colored++;total++;}return total>20&&colored>total*.03;}
        finally{image.recycle();}
    }
    private long rulerHash(){return s.main(()->{TimelineView timeline=NativeTestSupport.findClass(main.getWindow().getDecorView(),TimelineView.class);NativeTestSupport.require(timeline!=null&&timeline.getWidth()>0,"Timeline not laid out");android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(timeline.getWidth(),timeline.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);try{timeline.draw(new android.graphics.Canvas(bitmap));float density=s.context.getResources().getDisplayMetrics().density;int left=(int)Math.max(98*density,Math.min(150*density,timeline.getWidth()*.27f))+3;long hash=0xcbf29ce484222325L;for(int y=Math.round(14*density);y<Math.min(bitmap.getHeight(),Math.round(44*density));y+=2)for(int x=left;x<bitmap.getWidth();x+=2){hash^=bitmap.getPixel(x,y);hash*=0x100000001b3L;}return hash;}finally{bitmap.recycle();}});}
    private void seekSequence(int millis){s.main(()->{SequenceTimeline timeline=NativeTestSupport.findClass(sequence.getWindow().getDecorView(),SequenceTimeline.class);Bundle args=new Bundle();args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,millis);NativeTestSupport.require(timeline.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(),args),"Sequence timeline rejected exact seek");return null;});s.settle();}
    private double[] previewRgb(Activity activity){android.graphics.Rect bounds=s.main(()->{android.graphics.Rect rect=new android.graphics.Rect();EditorPreview player=NativeTestSupport.findClass(activity.getWindow().getDecorView(),EditorPreview.class);player.getGlobalVisibleRect(rect);return rect;});android.graphics.Bitmap image=s.test.getUiAutomation().takeScreenshot();if(image==null||bounds.isEmpty())return new double[]{0,0,0};try{int cx=bounds.centerX(),cy=bounds.centerY(),count=0;double[] rgb=new double[3];for(int y=Math.max(0,cy-3);y<=Math.min(image.getHeight()-1,cy+3);y++)for(int x=Math.max(0,cx-3);x<=Math.min(image.getWidth()-1,cx+3);x++){int pixel=image.getPixel(x,y);rgb[0]+=android.graphics.Color.red(pixel);rgb[1]+=android.graphics.Color.green(pixel);rgb[2]+=android.graphics.Color.blue(pixel);count++;}for(int i=0;i<3;i++)rgb[i]/=Math.max(1,count)*255;return rgb;}finally{image.recycle();}}
    /** Failure-only introspection: observe the native graph without changing application behavior. */
    private String previewDiagnostics(Activity activity){
        PreviewSnapshot snapshot=s.main(()->{
            PreviewSnapshot captured=new PreviewSnapshot();EditorPreview editor=NativeTestSupport.findClass(activity.getWindow().getDecorView(),EditorPreview.class);
            captured.text.append("EditorPreview{");for(String name:new String[]{"ready","wantPlay","released","preparedNotified","seekInFlight","queuedSeek","duration","pendingPosition","effectStart","effectEnd","generation","seekSerial","firstPresentedUs","lastPresentedUs","volume","speed","canvasAspect","resizeMode","errorMessage"}){
                try{captured.text.append(name).append('=').append(privateField(editor,name)).append(',');}catch(Exception missing){captured.text.append(name).append("=unavailable,");}
            }
            try{captured.text.append("seekReloadScheduled=").append(privateField(editor,"seekReload")!=null);}catch(Exception missing){captured.text.append("seekReloadScheduled=unavailable");}captured.text.append("}");
            try{captured.outer=privateField(editor,"player");if(captured.outer!=null){Object holders=privateField(captured.outer,"playerHolders");if(holders instanceof Iterable)for(Object holder:(Iterable<?>)holders)try{captured.lanes.add(privateField(holder,"player"));}catch(Exception missing){captured.text.append(" laneHolderRead=").append(missing);}else if(holders!=null&&holders.getClass().isArray())for(int i=0;i<java.lang.reflect.Array.getLength(holders);i++)captured.lanes.add(privateField(java.lang.reflect.Array.get(holders,i),"player"));}}
            catch(Exception missing){captured.text.append(" player/holdersRead=").append(missing);}
            android.view.SurfaceView surface=NativeTestSupport.findClass(editor,android.view.SurfaceView.class);
            if(surface!=null){android.graphics.Rect bounds=new android.graphics.Rect();surface.getGlobalVisibleRect(bounds);captured.text.append(" SurfaceView{size=").append(surface.getWidth()).append('x').append(surface.getHeight()).append(",shown=").append(surface.isShown()).append(",windowVisibility=").append(surface.getWindowVisibility()).append(",surfaceValid=").append(surface.getHolder().getSurface().isValid()).append(",surfaceFrame=").append(surface.getHolder().getSurfaceFrame()).append(",visibleRect=").append(bounds).append('}');}
            else captured.text.append(" SurfaceView=none");return captured;
        });
        StringBuilder out=snapshot.text;out.append("\nCompositionPlayer ").append(playerDiagnostics(snapshot.outer));for(int i=0;i<snapshot.lanes.size();i++)out.append("\nLane[").append(i).append("] ").append(playerDiagnostics(snapshot.lanes.get(i)));return out.toString();
    }
    private static final class PreviewSnapshot {final StringBuilder text=new StringBuilder();Object outer;final java.util.List<Object> lanes=new java.util.ArrayList<>();}
    private static Object privateField(Object owner,String name)throws Exception{if(owner==null)return null;for(Class<?> type=owner.getClass();type!=null;type=type.getSuperclass())try{java.lang.reflect.Field field=type.getDeclaredField(name);field.setAccessible(true);return field.get(owner);}catch(NoSuchFieldException absent){}throw new NoSuchFieldException(name);}
    private String playerDiagnostics(Object player){
        if(player==null)return "null";StringBuilder prefix=new StringBuilder(player.getClass().getName());android.os.Looper looper=null;
        try{Object value=invokeNoArgs(player,"getApplicationLooper");if(value instanceof android.os.Looper)looper=(android.os.Looper)value;}catch(Exception unavailable){prefix.append(" applicationLooperRead=").append(unavailable);}
        final String[] state=new String[1];final java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
        Runnable observe=()->{try{StringBuilder result=new StringBuilder("{");for(String method:new String[]{"getPlaybackState","getCurrentPosition","getBufferedPosition","getDuration","getPlayWhenReady","isPlaying","isLoading","getPlaybackSuppressionReason","getTotalBufferedDuration","getCurrentMediaItemIndex","getVideoSize","getSurfaceSize","getPlayerError"})try{result.append(method).append('=').append(invokeNoArgs(player,method)).append(',');}catch(Exception unavailable){result.append(method).append("=unavailable:").append(unavailable.getClass().getSimpleName()).append(',');}state[0]=result.append('}').toString();}finally{done.countDown();}};
        if(looper==null||looper==android.os.Looper.myLooper())observe.run();else {prefix.append(" applicationThread=").append(looper.getThread().getName());new android.os.Handler(looper).post(observe);}
        try{if(!done.await(2,java.util.concurrent.TimeUnit.SECONDS))return prefix.append(" getter observation timed out").toString();}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return prefix.append(" getter observation interrupted").toString();}return prefix.append(state[0]).toString();
    }
    private static Object invokeNoArgs(Object owner,String name)throws Exception{java.lang.reflect.Method method=owner.getClass().getMethod(name);method.setAccessible(true);return method.invoke(owner);}
    private void projectCodec(Uri source)throws Exception{
        ProjectCodec.Project original=new ProjectCodec.Project();original.single=true;original.sequence=new Sequence().append(new Sequence.Clip(source.toString(),"QA",137,1137));original.effects.title="Тест Ω";original.effects.brightness=.2f;original.effects.audioUri=source.toString();original.effects.overlayUri=source.toString();original.effects.transition="wipe";original.markers.add(400);original.cursor=350;
        ProjectCodec.Project restored=ProjectCodec.parse(ProjectCodec.write(original));check(restored.effects.equals(original.effects),"Common project lost effect parameters");check(restored.sequence.length()==1000&&restored.markers.size()==1,"Common project lost range/markers");
        String sourceJson=org.json.JSONObject.quote(source.toString());
        check(ProjectCodec.parse("{\"format\":\"VegasVideoProject\",\"version\":1,\"source\":"+sourceJson+",\"start\":0,\"end\":1000,\"muted\":true}").muted,"Legacy single project not supported");
        check(ProjectCodec.parse("{\"format\":\"VegasVideoSequence\",\"version\":1,\"clips\":[{\"source\":"+sourceJson+",\"in\":0,\"out\":1000}]}").sequence.length()==1000,"Legacy sequence project not supported");
        for(String bad:new String[]{"{}","{broken","{\"format\":\"Other\"}","{\"format\":\"VegasVideoProject\",\"version\":999,\"source\":"+sourceJson+",\"start\":0,\"end\":1000}","{\"format\":\"VegasVideoProject\",\"source\":\"file:///etc/passwd\",\"start\":0,\"end\":1000}","{\"format\":\"VegasVideoProject\",\"source\":"+sourceJson+",\"start\":1000,\"end\":500}"}){boolean rejected=false;try{ProjectCodec.parse(bad);}catch(Exception expected){rejected=true;}check(rejected,"Malformed project accepted: "+bad);}
    }
    private void importMain(Uri uri)throws Exception{Instrumentation.ActivityMonitor picker=s.picker(Intent.ACTION_OPEN_DOCUMENT,uri);s.clickDescription(main,"Открыть видео");s.picked(picker);}
    private ProjectCodec.Project saveMain(Uri uri)throws Exception{s.clearDocument(uri);Instrumentation.ActivityMonitor picker=s.picker(Intent.ACTION_CREATE_DOCUMENT,uri);s.clickText(main,"File");s.windowClick("Save project as…");s.picked(picker);return savedProject(uri);}
    private void openMain(Uri uri)throws Exception{Instrumentation.ActivityMonitor picker=s.picker(Intent.ACTION_OPEN_DOCUMENT,uri);s.clickText(main,"File");s.windowClick("Open project…");s.picked(picker);}
    private ProjectCodec.Project saveSequence(Uri uri)throws Exception{s.clearDocument(uri);Instrumentation.ActivityMonitor picker=s.picker(Intent.ACTION_CREATE_DOCUMENT,uri);s.clickText(sequence,"File");s.windowClick("Save project as…");s.picked(picker);return savedProject(uri);}
    private ProjectCodec.Project savedProject(Uri uri)throws Exception{s.await(()->{try{return s.read(uri).contains("\"format\"");}catch(Exception unavailable){return false;}},15000,"Project file was not written");return ProjectCodec.parse(s.read(uri));}
    private Activity recreate(Activity activity,Class<?> type){Instrumentation.ActivityMonitor monitor=s.test.addMonitor(type.getName(),null,false);s.main(()->{activity.recreate();return null;});Activity restored=s.test.waitForMonitorWithTimeout(monitor,15000);s.test.removeMonitor(monitor);check(restored!=null&&restored!=activity,"Activity recreation did not complete");s.test.waitForIdleSync();return restored;}
    private void ready(Activity activity){s.await(()->s.main(()->{View render=NativeTestSupport.findDescription(activity.getWindow().getDecorView(),"Render As… — сохранить MP4");EditorPreview player=NativeTestSupport.findClass(activity.getWindow().getDecorView(),EditorPreview.class);return player!=null&&player.getDuration()>0&&(render==null||render.isEnabled());}),45000,"Preview did not prepare");}
    private void sequenceReady(){ready(sequence);s.await(()->s.main(()->description(sequence,"Play / Pause").isEnabled()),15000,"Sequence playback control unavailable");}
    private EditorPreview preview(Activity activity){return s.main(()->NativeTestSupport.findClass(activity.getWindow().getDecorView(),EditorPreview.class));}
    private View description(Activity activity,String value){return NativeTestSupport.findDescription(activity.getWindow().getDecorView(),value);}
    private String range(){return s.main(()->{TextView label=NativeTestSupport.textPrefix(main.getWindow().getDecorView(),"Loop:");return label==null?"":label.getText().toString();});}
    private void awaitRange(String start,String end){s.await(()->range().contains(start)&&range().contains(end),5000,"Selection range not applied");}
    private int eventCount(){return s.main(()->events(sequence.getWindow().getDecorView()));}
    private static int events(View view){if(view instanceof TextView){String text=((TextView)view).getText().toString();java.util.regex.Matcher m=java.util.regex.Pattern.compile("[·•]\\s*(\\d+)\\s+events").matcher(text);if(m.find())return Integer.parseInt(m.group(1));}if(view instanceof android.view.ViewGroup){android.view.ViewGroup group=(android.view.ViewGroup)view;for(int i=0;i<group.getChildCount();i++){int count=events(group.getChildAt(i));if(count>=0)return count;}}return -1;}
    private void check(boolean value,String message){checks++;NativeTestSupport.require(value,message);}
    private void step(String value){phase=value;s.step(value);}
    private void finish(Activity activity){if(activity==null)return;s.main(()->{activity.finish();return null;});s.test.waitForIdleSync();}
}
