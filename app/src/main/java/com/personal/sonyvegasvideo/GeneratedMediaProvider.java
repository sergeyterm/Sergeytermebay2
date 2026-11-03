package com.personal.sonyvegasvideo;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/** Read-only provider confined to generated PNG assets; never exposes arbitrary app files. */
public final class GeneratedMediaProvider extends ContentProvider {
    @Override public boolean onCreate(){return true;}
    private File resolve(Uri uri)throws FileNotFoundException {
        List<String> parts=uri.getPathSegments();
        if(!"content".equals(uri.getScheme())||!(getContext().getPackageName()+".generated").equals(uri.getAuthority())||parts.size()!=2||!"images".equals(parts.get(0))||!parts.get(1).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.png")||uri.getQuery()!=null||uri.getFragment()!=null)throw new FileNotFoundException("Недопустимый адрес изображения.");
        try{
            File directory=new File(getContext().getFilesDir(),"generated").getCanonicalFile();File file=new File(directory,parts.get(1)).getCanonicalFile();
            if(!directory.equals(file.getParentFile())||!file.isFile())throw new FileNotFoundException("Изображение недоступно. Создайте фон повторно.");return file;
        }catch(IOException error){throw new FileNotFoundException(error.getMessage());}
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException {
        if(!"r".equals(mode))throw new FileNotFoundException("Изображения доступны только для чтения.");return ParcelFileDescriptor.open(resolve(uri),ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri){try{resolve(uri);return "image/png";}catch(FileNotFoundException error){return null;}}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){
        try{
            File file=resolve(uri);if(projection!=null&&projection.length>16)return null;String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor cursor=new MatrixCursor(columns);Object[] values=new Object[columns.length];
            for(int i=0;i<columns.length;i++){if(OpenableColumns.DISPLAY_NAME.equals(columns[i]))values[i]=file.getName();else if(OpenableColumns.SIZE.equals(columns[i]))values[i]=file.length();}
            cursor.addRow(values);return cursor;
        }catch(FileNotFoundException error){return null;}
    }
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException("Read-only images");}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException("Read-only images");}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException("Read-only images");}
}
