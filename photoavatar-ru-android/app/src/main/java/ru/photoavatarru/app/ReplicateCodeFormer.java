package ru.photoavatarru.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.MediaType;

/**
 * Optional CodeFormer on Replicate (separate provider and billable account).
 * Images are uploaded ONLY following the explicit Enhance button press.
 * No Replicate token or API result is recorded in GitHub or Android logs.
 */
final class ReplicateCodeFormer {
  private static final String VERSION =
      "cc4956dd26fa5a7185d5660cc9100fab1b8070a1d1654a8bb5eb6d443b020bb2";
  private static final String ROOT = "https://api.replicate.com";
  private final String token;
  private final OkHttpClient client = new OkHttpClient.Builder()
      .connectTimeout(20, TimeUnit.SECONDS)
      .readTimeout(65, TimeUnit.SECONDS)
      .build();

  ReplicateCodeFormer(Context context) throws IOException {
    token = context.getSharedPreferences("replicate_quality", Context.MODE_PRIVATE)
        .getString("api_token", "");
    if (token.length() < 16 || token.length() > 250 || !token.matches("r8_[a-zA-Z0-9_]+"))
      throw new IOException("CodeFormer не подключён: введи личный Replicate API-токен в настройках качества");
  }

  static boolean configured(Context context) {
    String token = context.getSharedPreferences("replicate_quality", Context.MODE_PRIVATE)
        .getString("api_token", "");
    return token.startsWith("r8_") && token.length() > 16;
  }

  static void setToken(Context context, String input) throws IOException {
    String value = input == null ? "" : input.trim();
    if(!value.matches("r8_[a-zA-Z0-9_]{13,248}"))
      throw new IOException("Неверный формат Replicate API-токена");
    context.getSharedPreferences("replicate_quality", Context.MODE_PRIVATE)
        .edit().putString("api_token", value).apply();
  }

  static void removeToken(Context context) {
    context.getSharedPreferences("replicate_quality", Context.MODE_PRIVATE)
        .edit().remove("api_token").apply();
  }

  JSONObject enhance(String dataUri, double fidelity) throws Exception {
    if(dataUri==null || !dataUri.matches("(?s)^data:image/(jpeg|png|webp);base64,.*$")
        || dataUri.length() > 4_800_000)
      throw new IOException("Подготовь JPEG/PNG/WebP до 3,5 МБ перед CodeFormer");
    fidelity = Math.max(.6, Math.min(.95, fidelity)); // Favor likeness over hallucination.
    JSONObject input = new JSONObject()
      .put("image", dataUri)
      .put("codeformer_fidelity", fidelity)
      .put("upscale", 1)
      .put("face_upsample", false)
      .put("background_enhance", false);
    JSONObject result = new JSONObject()
      .put("version", VERSION)
      .put("input", input);
    JSONObject prediction = json("POST","/v1/predictions",result,true);
    String id = prediction.optString("id","");
    if(!id.matches("[a-z0-9]{10,80}")) throw new IOException("Replicate не вернул ID обработки");
    for(int attempt=0;attempt<75;attempt++) {
      String status = prediction.optString("status","");
      if("succeeded".equals(status)) {
        String url = "";
        JSONArray array = prediction.optJSONArray("output");
        if(array!=null && array.length()>0) url = array.optString(0);
        else url = prediction.optString("output","");
        if (!url.startsWith("https://replicate.delivery/") &&
            !url.startsWith("https://pbxt.replicate.delivery/"))
          throw new IOException("CodeFormer не вернул разрешённый HTTPS URL результата");
        String encoded = fetchAndEncodeJpeg(url);
        return new JSONObject().put("photo",encoded)
          .put("model","CodeFormer").put("fidelity",fidelity).put("predictionId",id);
      }
      if("failed".equals(status) || "canceled".equals(status)) {
        JSONObject error = prediction.optJSONObject("error");
        throw new IOException("CodeFormer завершился неудачно: "
          +(error!=null?error.optString("message","ошибка"):prediction.optString("error","ошибка")));
      }
      if(attempt<74) Thread.sleep(2000);
      prediction=json("GET","/v1/predictions/"+id,null,false);
    }
    throw new IOException("CodeFormer ещё обрабатывает изображение. Не отправляй его повторно без проверки расходов.");
  }

  private JSONObject json(String method,String path,JSONObject body,boolean preferWait) throws Exception {
    Request.Builder req=new Request.Builder()
      .url(ROOT+path)
      .header("Authorization","Bearer "+token)
      .header("Accept","application/json");
    if(preferWait)req.header("Prefer","wait=20");
    if("POST".equals(method)) {
      RequestBody data=RequestBody.create(MediaType.parse("application/json; charset=utf-8"),body.toString());
      req.post(data);
    } else req.get();
    try(Response response=client.newCall(req.build()).execute()) {
      String responseBody=response.body()==null?"":bounded(response.body().byteStream(),1_000_000);
      JSONObject json;
      try { json=new JSONObject(responseBody); }
      catch(Exception e){ throw new IOException("Некорректный ответ Replicate (HTTP "+response.code()+")"); }
      if(!response.isSuccessful()) {
        String err=json.optString("detail",json.optString("error",""));
        throw new IOException("Replicate HTTP "+response.code()+": "+(err.length()>180?err.substring(0,180):err));
      }
      return json;
    }
  }

  private String fetchAndEncodeJpeg(String url) throws Exception {
    Request req=new Request.Builder().url(url).get().build();
    try(Response response=client.newCall(req).execute()) {
      if(!response.isSuccessful())throw new IOException("Не удалось скачать результат CodeFormer");
      byte[] bytes=boundedBytes(response.body().byteStream(),8_000_000);
      BitmapFactory.Options opts = new BitmapFactory.Options();
      opts.inPreferredConfig=Bitmap.Config.ARGB_8888;
      Bitmap bmp=BitmapFactory.decodeByteArray(bytes,0,bytes.length,opts);
      if(bmp==null)throw new IOException("Результат CodeFormer не является изображением");
      Bitmap resized=Bitmap.createScaledBitmap(bmp,1088,704,true);
      ByteArrayOutputStream output=new ByteArrayOutputStream();
      resized.compress(Bitmap.CompressFormat.JPEG,90,output);
      if(resized!=bmp) resized.recycle();
      bmp.recycle();
      if(output.size()>3_800_000)throw new IOException("Результат CodeFormer слишком большой");
      return "data:image/jpeg;base64,"+Base64.encodeToString(output.toByteArray(),Base64.NO_WRAP);
    }
  }

  private static String bounded(InputStream in,int max) throws IOException {
    return new String(boundedBytes(in,max),java.nio.charset.StandardCharsets.UTF_8);
  }
  private static byte[] boundedBytes(InputStream in,int max) throws IOException {
    try(InputStream stream=in;ByteArrayOutputStream out=new ByteArrayOutputStream()) {
      byte[] buf=new byte[8192];int count;
      while((count=stream.read(buf))!=-1) {
        if(out.size()+count>max)throw new IOException("Ответ CodeFormer слишком большой");
        out.write(buf,0,count);
      }
      return out.toByteArray();
    }
  }
}
