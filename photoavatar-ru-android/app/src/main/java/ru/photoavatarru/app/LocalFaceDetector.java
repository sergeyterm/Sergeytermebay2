package ru.photoavatarru.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.util.Base64;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetectorOptions;

import org.json.JSONObject;
import java.io.IOException;
import java.util.List;

/** Local, bundled ML Kit face detection. No pixels are sent to any third-party API. */
final class LocalFaceDetector {
    interface Callback {
        void success(JSONObject response);
        void error(Exception error);
    }

    static void detect(String photo, Callback callback) {
        try {
            if (photo == null || !photo.matches("(?s)^data:image/(jpeg|png|webp);base64,.*$") || photo.length() > 4_600_000) {
                callback.error(new IOException("Неверный формат изображения для поиска лица"));
                return;
            }
            int marker=photo.indexOf(',');
            byte[] decoded=Base64.decode(photo.substring(marker + 1),Base64.DEFAULT);
            if(decoded.length>3_400_000)throw new IOException("Фотография для анализа слишком большая");
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig=Bitmap.Config.ARGB_8888;
            Bitmap bitmap=BitmapFactory.decodeByteArray(decoded,0,decoded.length,opts);
            if(bitmap==null)throw new IOException("Android не смог прочитать фотографию");
            if(bitmap.getWidth()<150 || bitmap.getHeight()<150)throw new IOException("Слишком маленький портрет");

            FaceDetectorOptions options=new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setMinFaceSize(0.08f)
                .build();
            com.google.mlkit.vision.face.FaceDetector detector=FaceDetection.getClient(options);
            detector.process(InputImage.fromBitmap(bitmap,0))
              .addOnSuccessListener(faces -> {
                  try {
                      JSONObject result=serialize(faces,bitmap.getWidth(),bitmap.getHeight());
                      callback.success(result);
                  } catch(Exception e) { callback.error(e); }
                  finally { detector.close(); bitmap.recycle(); }
              })
              .addOnFailureListener(error -> {
                  detector.close();
                  bitmap.recycle();
                  callback.error(error instanceof Exception ? (Exception)error : new Exception(error));
              });
        } catch(Exception e) { callback.error(e); }
    }

    private static JSONObject serialize(List<Face> faces,int width,int height) throws Exception {
        JSONObject result=new JSONObject();
        result.put("facesCount",faces.size());
        result.put("detected",faces.size()==1);
        if(faces.size()!=1)return result; // Never select a random person in a multi-face image.
        Face face=faces.get(0);
        Rect bounds=face.getBoundingBox();
        double x=Math.max(0,Math.min(1,bounds.left/(double)width));
        double y=Math.max(0,Math.min(1,bounds.top/(double)height));
        double right=Math.max(0,Math.min(1,bounds.right/(double)width));
        double bottom=Math.max(0,Math.min(1,bounds.bottom/(double)height));
        result.put("x",x).put("y",y)
              .put("w",Math.max(0,right-x)).put("h",Math.max(0,bottom-y));
        result.put("yaw",face.getHeadEulerAngleY());
        result.put("roll",face.getHeadEulerAngleZ());
        return result;
    }

    private LocalFaceDetector() {}
}
