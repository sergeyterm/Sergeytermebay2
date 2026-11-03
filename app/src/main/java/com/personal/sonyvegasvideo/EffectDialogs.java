package com.personal.sonyvegasvideo;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.text.InputFilter;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Native settings forms. Edits are committed as one undoable change on Apply. */
public final class EffectDialogs {
    private EffectDialogs() { }
    public static void show(Activity activity,EffectSpec current,String section,Consumer<EffectSpec> accepted){
        if(activity.isFinishing()||activity.isDestroyed())return;
        EffectSpec before=current==null?new EffectSpec():current.copy().normalize();EffectSpec draft=before.copy();
        LinearLayout body=new LinearLayout(activity);body.setOrientation(LinearLayout.VERTICAL);int pad=dp(activity,16);body.setPadding(pad,pad,pad,pad);
        ScrollView scroll=new ScrollView(activity);scroll.setFillViewport(true);scroll.addView(body);
        List<Runnable> validation=new ArrayList<>();String title;
        if("transitions".equals(section)){
            title="Transitions — появление и исчезновение";
            note(activity,body,"Переходы применяются в начале и конце экспортируемого диапазона. В середине изображение остаётся полным. Это переходы из/в чёрный фон.");
            choose(activity,body,"transition","Переход",new String[]{"none","dissolve","cross","wipe","cascade"},new String[]{"Нет","Dissolve — плавное появление","Cross Effect — раскрытие крестом","Wipe — шторка","3D Cascade — каскадные полосы"},draft.transition,value->draft.transition=value);
            slider(activity,body,"transitionMs","Длительность, мс",50,3000,draft.transitionMs,0,value->draft.transitionMs=Math.round(value));
        }else if("title".equals(section)){
            title="Titles & Text";note(activity,body,"Текст накладывается поверх видео и записывается в экспорт. Пустой текст убирает титр.");
            EditText text=new EditText(activity);text.setContentDescription("title");text.setHint("Текст титра");text.setText(draft.title);text.setMinLines(2);text.setMaxLines(4);text.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200)});body.addView(text);validation.add(()->draft.title=text.getText().toString());
            color(activity,body,"titleColor","Цвет текста · #RRGGBB или #AARRGGBB",draft.titleColor,value->draft.titleColor=value,validation);
            slider(activity,body,"titleSize","Размер текста, % высоты",2.5f,20,draft.titleSize*100,1,value->draft.titleSize=value/100);
            slider(activity,body,"titleY","Положение: низ − / верх +",-.9f,.9f,draft.titleY,2,value->draft.titleY=value);
        }else if("layers".equals(section)){
            title="Видео- и аудиослои";
            note(activity,body,"Добавить файлы: Insert → Video track / Audio track. Музыка и видео в окне повторяются до конца основного проекта.");
            note(activity,body,draft.overlayUri.isEmpty()?"Видеослой не добавлен.":"Видеослой добавлен. Можно менять его размер и положение.");
            TextView removeVideo=action(activity,body,"removeOverlay","Убрать видеослой",()->{draft.overlayUri="";});removeVideo.setEnabled(!draft.overlayUri.isEmpty());
            slider(activity,body,"overlayScale","Размер видеослоя, %",10,100,draft.overlayScale*100,0,value->draft.overlayScale=value/100);
            slider(activity,body,"overlayX","Видеослой: влево − / вправо +",-1,1,draft.overlayX,2,value->draft.overlayX=value);
            slider(activity,body,"overlayY","Видеослой: вниз − / вверх +",-1,1,draft.overlayY,2,value->draft.overlayY=value);
            check(activity,body,"chromaKey","Удалять цвет видеослоя (Chroma Key)",draft.chromaKey,value->draft.chromaKey=value);
            note(activity,body,"Цвет и порог хромакея настраиваются в Video FX. Без видеослоя хромакей применяется к основному видео.");
            note(activity,body,draft.audioUri.isEmpty()?"Музыка не добавлена.":"Музыкальная дорожка добавлена.");
            TextView removeAudio=action(activity,body,"removeAudio","Убрать музыкальную дорожку",()->{draft.audioUri="";});removeAudio.setEnabled(!draft.audioUri.isEmpty());
            slider(activity,body,"audioGain","Громкость музыки, %",0,100,draft.audioGain*100,0,value->draft.audioGain=value/100);
            slider(activity,body,"masterGain","Общая громкость экспорта, %",0,100,draft.masterGain*100,0,value->draft.masterGain=value/100);
        }else if("animation".equals(section)){
            title="Анимация — два ключевых состояния";animation(activity,body,draft);
        }else{
            title="Video FX";
            choose(activity,body,"preset","Цветовой фильтр",new String[]{"none","mono","sepia","vivid","cool","warm","invert","vintage"},new String[]{"Без фильтра","Monochrome — чёрно-белый","Sepia","Vivid — яркий","Cool — холодный","Warm — тёплый","Invert — негатив","Vintage"},draft.preset,value->draft.preset=value);
            slider(activity,body,"brightness","Яркость",-100,100,draft.brightness*100,0,value->draft.brightness=value/100);
            slider(activity,body,"contrast","Контраст",-95,95,draft.contrast*100,0,value->draft.contrast=value/100);
            slider(activity,body,"saturation","Насыщенность, %",0,200,draft.saturation*100,0,value->draft.saturation=value/100);
            slider(activity,body,"hue","Оттенок, °",-180,180,draft.hue,0,value->draft.hue=value);
            slider(activity,body,"blur","Gaussian Blur · радиус",0,20,draft.blur,1,value->draft.blur=value);
            slider(activity,body,"mosaic","Mosaic · размер блока, px",0,100,draft.mosaic,0,value->draft.mosaic=value);
            check(activity,body,"chromaKey","Chroma Key — удалить выбранный цвет",draft.chromaKey,value->draft.chromaKey=value);
            color(activity,body,"chromaColor","Удаляемый цвет · #RRGGBB",draft.chromaColor,value->draft.chromaColor=value,validation);
            slider(activity,body,"chromaThreshold","Допуск хромакея",.03f,.8f,draft.chromaThreshold,2,value->draft.chromaThreshold=value);
            note(activity,body,"При добавленном видеослое хромакей удаляет его фон. Иначе удалённый цвет основного видео становится прозрачным/чёрным.");
            animation(activity,body,draft);
        }
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(title).setView(scroll).setPositiveButton("Применить",null).setNegativeButton("Отмена",null).setNeutralButton("Сбросить",(d,w)->accepted.accept(reset(before,section))).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            try{for(Runnable check:validation)check.run();accepted.accept(draft.copy().normalize());dialog.dismiss();}
            catch(IllegalArgumentException invalid){/* The invalid input already carries its field error. */}
        }));dialog.show();
    }
    private static void animation(Activity a,LinearLayout body,EffectSpec spec){
        note(a,body,"Анимация между начальным и конечным состоянием длится весь выбранный диапазон. При одинаковых значениях трансформация постоянная.");
        slider(a,body,"zoomStart","Масштаб в начале, %",25,300,spec.zoomStart*100,0,v->spec.zoomStart=v/100);
        slider(a,body,"zoomEnd","Масштаб в конце, %",25,300,spec.zoomEnd*100,0,v->spec.zoomEnd=v/100);
        slider(a,body,"rotationStart","Поворот в начале, °",-180,180,spec.rotationStart,0,v->spec.rotationStart=v);
        slider(a,body,"rotationEnd","Поворот в конце, °",-180,180,spec.rotationEnd,0,v->spec.rotationEnd=v);
    }
    private static EffectSpec reset(EffectSpec before,String section){
        EffectSpec out=before.copy(),defaults=new EffectSpec();
        if("transitions".equals(section)){out.transition="none";out.transitionMs=defaults.transitionMs;}
        else if("title".equals(section)){out.title="";out.titleColor=defaults.titleColor;out.titleSize=defaults.titleSize;out.titleY=defaults.titleY;}
        else if("layers".equals(section)){out.overlayUri="";out.audioUri="";out.overlayScale=defaults.overlayScale;out.overlayX=defaults.overlayX;out.overlayY=defaults.overlayY;out.audioGain=defaults.audioGain;out.masterGain=1;}
        else {if(!"animation".equals(section)){out.brightness=0;out.contrast=0;out.saturation=1;out.hue=0;out.blur=0;out.mosaic=0;out.chromaKey=false;out.chromaColor=defaults.chromaColor;out.chromaThreshold=defaults.chromaThreshold;out.preset="none";}out.zoomStart=1;out.zoomEnd=1;out.rotationStart=0;out.rotationEnd=0;}
        return out.normalize();
    }
    private static void note(Activity a,LinearLayout body,String text){TextView view=new TextView(a);view.setText(text);view.setTextSize(12);view.setTextColor(0xff444444);view.setPadding(0,dp(a,8),0,dp(a,8));body.addView(view);}
    private interface FloatChange{void change(float value);}
    private static void slider(Activity a,LinearLayout body,String name,String caption,float low,float high,float value,int decimals,FloatChange changed){
        TextView label=new TextView(a);label.setTextSize(13);body.addView(label);SeekBar bar=new SeekBar(a);bar.setContentDescription(name);bar.setMax(1000);int progress=Math.round((value-low)*1000/(high-low));bar.setProgress(Math.max(0,Math.min(1000,progress)));body.addView(bar,new LinearLayout.LayoutParams(-1,dp(a,44)));
        Runnable show=()->label.setText(caption+": "+String.format(Locale.ROOT,"%."+decimals+"f",low+(high-low)*bar.getProgress()/1000));show.run();
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){@Override public void onProgressChanged(SeekBar view,int progress,boolean user){float next=low+(high-low)*progress/1000;changed.change(next);show.run();}@Override public void onStartTrackingTouch(SeekBar view){}@Override public void onStopTrackingTouch(SeekBar view){}});
    }
    private static void choose(Activity a,LinearLayout body,String name,String caption,String[] ids,String[] labels,String selected,Consumer<String> changed){
        note(a,body,caption);Spinner spinner=new Spinner(a);spinner.setContentDescription(name);ArrayAdapter<String> adapter=new ArrayAdapter<>(a,android.R.layout.simple_spinner_item,labels);adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);spinner.setAdapter(adapter);int index=0;for(int i=0;i<ids.length;i++)if(ids[i].equals(selected))index=i;spinner.setSelection(index);body.addView(spinner,new LinearLayout.LayoutParams(-1,dp(a,48)));
        spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){@Override public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){changed.accept(ids[position]);}@Override public void onNothingSelected(android.widget.AdapterView<?> parent){}});
    }
    private static void check(Activity a,LinearLayout body,String name,String label,boolean checked,Consumer<Boolean> changed){CheckBox box=new CheckBox(a);box.setContentDescription(name);box.setText(label);box.setChecked(checked);box.setOnCheckedChangeListener((v,value)->changed.accept(value));body.addView(box);}
    private static void color(Activity a,LinearLayout body,String name,String caption,int initial,Consumer<Integer> changed,List<Runnable> validation){
        note(a,body,caption);EditText field=new EditText(a);field.setContentDescription(name);field.setSingleLine();field.setText(String.format(Locale.ROOT,"#%08X",initial));field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(9)});body.addView(field);
        validation.add(()->{String text=field.getText().toString().trim();if(!text.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")){field.setError("Введите #RRGGBB или #AARRGGBB");field.requestFocus();throw new IllegalArgumentException("Цвет");}changed.accept(Color.parseColor(text));});
    }
    private static TextView action(Activity a,LinearLayout body,String name,String label,Runnable action){TextView view=new TextView(a);view.setText(label);view.setContentDescription(name);view.setTextColor(0xff2255aa);view.setTextSize(14);view.setPadding(0,dp(a,12),0,dp(a,12));view.setOnClickListener(v->{action.run();view.setText("Будет удалено после применения");view.setEnabled(false);});body.addView(view);return view;}
    private static int dp(Activity a,int value){return Math.round(value*a.getResources().getDisplayMetrics().density);}
}
