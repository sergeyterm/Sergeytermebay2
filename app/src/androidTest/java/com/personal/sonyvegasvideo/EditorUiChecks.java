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
        Uri source=s.fixture("rotated-av.mp4"),red=s.fixture("color-red.mp4"),music=s.fixture("different-av.mp4");
        Uri saved=s.document("roundtrip.vvp",""),sequenceSave=s.document("montage.vvp",""),invalid=s.document("bad.vvp","{broken json");
        step("project codec compatibility and malformed inputs");projectCodec(source);
        step("open native workspace and import through a document-result mock");
        main=s.test.startActivitySync(new Intent(s.context,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));s.test.waitForIdleSync();
        check(s.main(()->!description(main,"Render As… — сохранить MP4").isEnabled()),"Render enabled with no media");
        for(String menu:new String[]{"File","Edit","View","Insert","Tools","Options","Help"}){
            s.clickText(main,menu);check(!s.windowText().isEmpty(),"Empty menu "+menu);s.back();
        }
        importMain(source);EditorPreview preview=preview(main);ready(main);check(!s.main(preview::isPlaying),"Import started playback");check(s.main(preview::getDuration)>5000,"Imported media duration missing");

        step("precise range validation, undo and redo");
        s.clickDescription(main,"Точные границы фрагмента");s.setEditable(0,"00:00:00.500");s.setEditable(1,"00:00:02.500");s.windowClick("OK");
        awaitRange("00:00:00.500","00:00:02.500");
        s.clickDescription(main,"Точные границы фрагмента");s.setEditable(0,"00:00:03.000");s.setEditable(1,"00:00:02.000");s.windowClick("OK");
        check(s.hasText("Loop region"),"Invalid trim dismissed its validation dialog");s.windowClick("Отмена");awaitRange("00:00:00.500","00:00:02.500");
        s.clickDescription(main,"Отменить изменение");check(!range().contains("00:00:02.500"),"Undo failed to restore range");
        s.clickDescription(main,"Повторить изменение");awaitRange("00:00:00.500","00:00:02.500");

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
        s.clickDescription(main,"Текст и титры");s.setField("title","VEGAS QA");s.windowClick("Применить");ready(main);
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
        openMain(saved);ready(main);ProjectCodec.Project reopened=saveMain(saved);
        check(reopened.effects.equals(configured.effects)&&reopened.sequence.length()==configured.sequence.length(),"Project roundtrip lost effects/layers/range");

        step("actual system picker cancel and activity recreation");
        s.clickDescription(main,"Открыть видео");s.await(()->!s.main(main::hasWindowFocus),10000,"Real document picker did not open");s.back();
        s.await(()->s.main(main::hasWindowFocus),10000,"Did not return from document picker");ready(main);
        main=recreate(main,MainActivity.class);ready(main);check(saveMain(saved).effects.equals(configured.effects),"Activity recreation lost project effects");
        s.screenshot("ui-full-"+size);

        step("render button and result open/share intents");
        s.clickDescription(main,"Render As… — сохранить MP4");s.windowClick("Render");s.await(()->!s.main(()->ExportJob.get().running)&&s.main(()->ExportJob.get().result)!=null,120000,"UI Render did not complete: "+s.main(()->ExportJob.get().message));
        Uri output=s.main(()->ExportJob.get().result.uri);s.own(output);s.dismissIfPresent("Закрыть");
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
        for(String name:new String[]{"File","Edit","View","Insert","Tools","Help"}){s.clickText(sequence,name);check(!s.windowText().isEmpty(),"Empty sequence menu "+name);s.back();}
        SequenceTimeline timeline=s.main(()->NativeTestSupport.findClass(sequence.getWindow().getDecorView(),SequenceTimeline.class));check(timeline!=null,"Missing sequence timeline");
        s.main(()->{Bundle args=new Bundle();args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,900);check(timeline.performAccessibilityAction(AccessibilityNodeInfo.ACTION_SET_PROGRESS,args),"Accessible exact seek not supported");return null;});
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
        finish(sequence);sequence=null;s.await(()->s.main(main::hasWindowFocus),10000,"Main workspace not restored");ready(main);

        step("invalid projects/media report errors and recover");
        openMain(invalid);check(s.main(()->!main.isFinishing()),"Malformed project closed editor");
        Uri broken=s.corruptVideo();importMain(broken);s.await(()->{String text=s.windowText().toLowerCase(java.util.Locale.ROOT);return text.contains("недоступ")||text.contains("не удалось")||text.contains("ошибка");},15000,"Invalid media did not report an error");
        check(!s.main(()->description(main,"Render As… — сохранить MP4").isEnabled()),"Invalid media enabled render");
        importMain(source);ready(main);check(!s.main(preview(main)::isPlaying),"Recovery import auto-started playback");

        step("generator button creates a playable clip");
        s.clickText(main,"Media Generators");s.clickDescription(main,"Solid Color");check(s.hasText("Media Generator"),"Generator dialog did not open");
        Uri previous=s.main(()->ExportJob.get().result.uri);s.setField("Длительность генератора","1");s.setField("Цвет генератора","#2050D0");s.windowClick("Создать");
        s.await(()->!s.main(()->ExportJob.get().running)&&s.main(()->ExportJob.get().result)!=null&&!previous.equals(s.main(()->ExportJob.get().result.uri)),120000,"Generator failed: "+s.main(()->ExportJob.get().message));s.own(s.main(()->ExportJob.get().result.uri));ready(main);
        check(s.main(preview(main)::getDuration)>=900&&s.main(preview(main)::getDuration)<=1250,"Generated clip was not imported into preview");
        step("new project clears state and disables media-only actions");s.clickDescription(main,"Новый проект");
        check(!s.main(()->description(main,"Render As… — сохранить MP4").isEnabled()),"New project retains enabled Render");
        s.screenshot("qa-empty-"+size);
    }
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
