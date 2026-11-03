package com.personal.sonyvegasvideo;

import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Versioned, shared project format; reads both workspaces' original version-one files. */
public final class ProjectCodec {
    public static final int MAX_BYTES=131072;
    public static final class Project {
        public Sequence sequence=new Sequence();
        public EffectSpec effects=new EffectSpec();
        public boolean muted,single;
        public int cursor;
        public List<Integer> markers=new ArrayList<>();
    }
    private ProjectCodec() { }
    public static Project parse(String text)throws Exception {
        if(text==null||text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_BYTES)throw new IOException("Проект слишком большой.");
        JSONObject data=new JSONObject(text);String format=data.getString("format");
        if(!format.equals("VegasVideoProject")&&!format.equals("VegasVideoSequence"))throw new IOException("Выберите проект Vegas Video (.vvp).");
        int version=integer(data,"version",1,1,2);
        Project project=new Project();project.muted=booleanValue(data,"muted",false);
        JSONArray clips=data.optJSONArray("clips");
        if(clips!=null){
            if(clips.length()>64)throw new IOException("В проекте больше 64 фрагментов.");
            List<Sequence.Clip> values=new ArrayList<>();
            for(int i=0;i<clips.length();i++){
                JSONObject item=clips.getJSONObject(i);String source=source(item.getString("source"));
                int in=integer(item,"in",0,0,Integer.MAX_VALUE-1),out=integer(item,"out",-1,1,Integer.MAX_VALUE);
                String name=item.optString("name","Video");if(name.length()>512)throw new IOException("Слишком длинное название медиа.");
                values.add(new Sequence.Clip(source,name,in,out));
            }
            project.sequence=new Sequence(values);project.single=booleanValue(data,"single",false)&&values.size()==1;
        }else if(format.equals("VegasVideoProject")&&version==1){
            int start=integer(data,"start",0,0,Integer.MAX_VALUE-1),end=integer(data,"end",-1,1,Integer.MAX_VALUE);
            project.sequence=project.sequence.append(new Sequence.Clip(source(data.getString("source")),data.optString("name","Video"),start,end));
            project.single=true;
        }else throw new IOException("В проекте нет списка фрагментов.");
        project.cursor=integer(data,"cursor",0,0,Integer.MAX_VALUE);project.cursor=Math.min(project.cursor,project.sequence.length());
        JSONObject effects=data.optJSONObject("effects");if(effects!=null)project.effects=readEffects(effects);
        JSONArray markers=data.optJSONArray("markers");
        if(markers!=null){
            if(markers.length()>64)throw new IOException("В проекте больше 64 маркеров.");
            for(int i=0;i<markers.length();i++){
                Object value=markers.get(i);if(!(value instanceof Number))throw new IOException("Некорректный маркер.");
                double number=((Number)value).doubleValue();if(!Double.isFinite(number)||number<0||number>Integer.MAX_VALUE||number!=(int)number)throw new IOException("Некорректный маркер.");
                int position=(int)number;if(!project.markers.contains(position))project.markers.add(position);
            }
            Collections.sort(project.markers);
        }
        return project;
    }
    public static String write(Project project)throws Exception {
        if(project==null||project.sequence==null)throw new IOException("Пустой проект.");
        JSONObject data=new JSONObject();data.put("format","VegasVideoProject");data.put("version",2);
        data.put("single",project.single&&project.sequence.clips.size()==1);data.put("muted",project.muted);data.put("cursor",Math.max(0,Math.min(project.cursor,project.sequence.length())));
        JSONArray clips=new JSONArray();for(Sequence.Clip clip:project.sequence.clips){
            JSONObject item=new JSONObject();item.put("source",source(clip.source));item.put("name",clip.name);item.put("in",clip.inMs);item.put("out",clip.outMs);clips.put(item);
        }
        data.put("clips",clips);data.put("effects",writeEffects(project.effects));data.put("markers",new JSONArray(project.markers));
        String text=data.toString(2);if(text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>MAX_BYTES)throw new IOException("Проект слишком большой.");
        return text;
    }
    public static JSONObject writeEffects(EffectSpec value)throws Exception {
        EffectSpec spec=value==null?new EffectSpec():value.copy().normalize();JSONObject data=new JSONObject();
        for(Field field:EffectSpec.class.getFields())if(!Modifier.isStatic(field.getModifiers()))data.put(field.getName(),field.get(spec));
        return data;
    }
    public static EffectSpec readEffects(JSONObject data)throws Exception {
        EffectSpec spec=new EffectSpec();
        for(Field field:EffectSpec.class.getFields()){
            if(Modifier.isStatic(field.getModifiers())||!data.has(field.getName()))continue;
            Object value=data.get(field.getName());Class<?> type=field.getType();
            if(type==String.class){if(!(value instanceof String)||((String)value).length()>4096)throw new IOException("Некорректный параметр "+field.getName());field.set(spec,value);}
            else if(type==boolean.class){if(!(value instanceof Boolean))throw new IOException("Некорректный переключатель "+field.getName());field.setBoolean(spec,(Boolean)value);}
            else if(type==float.class){if(!(value instanceof Number)||!Double.isFinite(((Number)value).doubleValue())||!Float.isFinite(((Number)value).floatValue()))throw new IOException("Некорректное число "+field.getName());field.setFloat(spec,((Number)value).floatValue());}
            else if(type==int.class){field.setInt(spec,integer(data,field.getName(),0,Integer.MIN_VALUE,Integer.MAX_VALUE));}
        }
        if(!spec.audioUri.isEmpty())source(spec.audioUri);if(!spec.overlayUri.isEmpty())source(spec.overlayUri);
        return spec.normalize();
    }
    private static String source(String value)throws IOException {
        if(value==null||value.isEmpty()||value.length()>4096||!"content".equals(Uri.parse(value).getScheme())||Uri.parse(value).getAuthority()==null)throw new IOException("Недопустимый источник медиа. Выберите файл через проводник Android.");
        return value;
    }
    private static boolean booleanValue(JSONObject data,String key,boolean fallback)throws Exception {
        if(!data.has(key))return fallback;Object value=data.get(key);if(!(value instanceof Boolean))throw new IOException("Некорректный переключатель "+key);return (Boolean)value;
    }
    private static int integer(JSONObject data,String key,int fallback,int min,int max)throws Exception {
        if(!data.has(key)){if(fallback<min||fallback>max)throw new IOException("Не указан параметр "+key);return fallback;}
        Object value=data.get(key);if(!(value instanceof Number))throw new IOException("Некорректное число "+key);
        double number=((Number)value).doubleValue();if(!Double.isFinite(number)||number<min||number>max||number!=(long)number)throw new IOException("Некорректное число "+key);
        return (int)number;
    }
}
