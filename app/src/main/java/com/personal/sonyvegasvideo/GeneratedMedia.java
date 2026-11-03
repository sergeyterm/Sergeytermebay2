package com.personal.sonyvegasvideo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

/** Persists small source images so generated clips can be reopened with their projects. */
public final class GeneratedMedia {
    private GeneratedMedia() { }
    public static Uri create(Context context,String kind,String text,int color,int width,int height)throws IOException {
        width=Math.max(64,Math.min(1920,width));height=Math.max(64,Math.min(1080,height));
        if(!"solid".equals(kind)&&!"text".equals(kind)&&!"checker".equals(kind)&&!"gradient".equals(kind))throw new IOException("Неизвестный генератор: "+kind);
        File directory=new File(context.getFilesDir(),"generated");
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IOException("Не удалось сохранить фон. Проверьте свободное место.");
        File file=new File(directory,UUID.randomUUID().toString()+".png");boolean complete=false;
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        try{
            Canvas canvas=new Canvas(bitmap);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);color|=0xff000000;
            if("gradient".equals(kind)){
                float[] hsv=new float[3];Color.colorToHSV(color,hsv);hsv[0]=(hsv[0]+130)%360;hsv[2]=Math.max(.2f,hsv[2]*.55f);
                paint.setShader(new LinearGradient(0,0,width,height,color,Color.HSVToColor(hsv),Shader.TileMode.CLAMP));canvas.drawRect(0,0,width,height,paint);
            }else if("checker".equals(kind)){
                int cell=Math.max(12,Math.min(width,height)/10);for(int y=0;y<height;y+=cell)for(int x=0;x<width;x+=cell){paint.setColor(((x/cell+y/cell)&1)==0?color:Color.WHITE);canvas.drawRect(x,y,Math.min(width,x+cell),Math.min(height,y+cell),paint);}
            }else if("text".equals(kind)){
                canvas.drawColor(Color.BLACK);paint.setColor(color);paint.setTextSize(Math.max(14,height*.10f));paint.setTextAlign(Paint.Align.CENTER);paint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.SANS_SERIF,android.graphics.Typeface.BOLD));
                String caption=text==null||text.trim().isEmpty()?"Sony Vegas Video":text.trim();if(caption.length()>200)caption=caption.substring(0,200);
                List<String> lines=VideoEffects.lines(caption,paint,width*.85f,4);float spacing=paint.getTextSize()*1.25f;float y=(height-lines.size()*spacing)/2-paint.ascent();
                for(String line:lines){canvas.drawText(line,width/2f,y,paint);y+=spacing;}
            }else canvas.drawColor(color);
            try(FileOutputStream out=new FileOutputStream(file)){if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IOException("Не удалось создать изображение.");}
            complete=true;return new Uri.Builder().scheme("content").authority(context.getPackageName()+".generated").appendPath("images").appendPath(file.getName()).build();
        }finally{bitmap.recycle();if(!complete)file.delete();}
    }
}
