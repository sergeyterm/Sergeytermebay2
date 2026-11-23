package ru.photoavatarru.app;
import android.content.ContentResolver;
import android.content.Intent;
import android.content.ClipData;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/** Decode only user-granted gallery URIs and bound memory before passing JPEG to the WebView. */
final class PhotoImporter {
    static final int MAX_EDGE = 2560, MAX_FILES = 4;
    static List<Uri> selectedUris(Intent data) {
        LinkedHashSet<Uri> unique = new LinkedHashSet<>();
        if (data == null) return new ArrayList<>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i=0; i<clip.getItemCount() && unique.size()<MAX_FILES; i++) {
                Uri uri=clip.getItemAt(i).getUri();
                if (uri!=null && "content".equals(uri.getScheme())) unique.add(uri);
            }
        }
        Uri uri=data.getData();
        if (unique.size()<MAX_FILES && uri!=null && "content".equals(uri.getScheme())) unique.add(uri);
        return new ArrayList<>(unique);
    }
    static byte[] readJpeg(ContentResolver resolver, Uri uri) throws IOException {
        if(uri==null || !"content".equals(uri.getScheme())) throw new IOException("Недопустимый источник");
        Bitmap bitmap;
        if(Build.VERSION.SDK_INT>=28){
            bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver,uri),(decoder,info,source)->{
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                android.util.Size size=info.getSize();
                int edge=Math.max(size.getWidth(),size.getHeight());
                if(edge>MAX_EDGE){
                    float scale=MAX_EDGE/(float)edge;
                    decoder.setTargetSize(Math.max(1,Math.round(size.getWidth()*scale)),
                        Math.max(1,Math.round(size.getHeight()*scale)));
                }
            });
        }else bitmap=legacyDecode(resolver,uri);
        if(bitmap==null)throw new IOException("Не удалось декодировать изображение");
        try{
            ByteArrayOutputStream out=new ByteArrayOutputStream();
            for(int quality:new int[]{94,86,76}){
                out.reset();
                if(!bitmap.compress(Bitmap.CompressFormat.JPEG,quality,out))throw new IOException("Ошибка JPEG");
                if(out.size()<=5_000_000)return out.toByteArray();
            }
            throw new IOException("Изображение слишком большое");
        }finally{bitmap.recycle();}
    }
    private static InputStream open(ContentResolver resolver,Uri uri)throws IOException{
        InputStream in=resolver.openInputStream(uri);
        if(in==null)throw new IOException("Файл недоступен");
        return in;
    }
    private static Bitmap legacyDecode(ContentResolver resolver,Uri uri)throws IOException{
        BitmapFactory.Options options=new BitmapFactory.Options();
        options.inJustDecodeBounds=true;
        try(InputStream in=open(resolver,uri)){BitmapFactory.decodeStream(in,null,options);}
        if(options.outWidth<=0||options.outHeight<=0)throw new IOException("Неподдерживаемый формат");
        options.inSampleSize=1;
        while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>MAX_EDGE)options.inSampleSize*=2;
        options.inJustDecodeBounds=false;
        options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        Bitmap bitmap;
        try(InputStream in=open(resolver,uri)){bitmap=BitmapFactory.decodeStream(in,null,options);}
        if(bitmap==null)throw new IOException("Не удалось прочитать фото");
        int orientation=ExifInterface.ORIENTATION_NORMAL;
        try(InputStream in=open(resolver,uri)){
            orientation=new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);
        }catch(IOException|RuntimeException ignored){}
        Matrix matrix=new Matrix();
        switch(orientation){
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL:matrix.setScale(-1,1);break;
            case ExifInterface.ORIENTATION_ROTATE_180:matrix.setRotate(180);break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:matrix.setScale(1,-1);break;
            case ExifInterface.ORIENTATION_TRANSPOSE:matrix.setRotate(90);matrix.postScale(-1,1);break;
            case ExifInterface.ORIENTATION_ROTATE_90:matrix.setRotate(90);break;
            case ExifInterface.ORIENTATION_TRANSVERSE:matrix.setRotate(-90);matrix.postScale(-1,1);break;
            case ExifInterface.ORIENTATION_ROTATE_270:matrix.setRotate(-90);break;
        }
        if(!matrix.isIdentity()){
            Bitmap rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);
            if(rotated!=bitmap)bitmap.recycle();
            bitmap=rotated;
        }
        return bitmap;
    }
    private PhotoImporter(){}
}
