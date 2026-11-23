package ru.photoavatarru.app;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Native-only Runway Dev REST client. The long-lived API key is NEVER returned to JS/WebView. */
final class RunwayApi {
    static final String ROOT = "https://api.dev.runwayml.com";
    static final String VERSION = "2024-11-06";
    private final String apiKey;

    RunwayApi(Context context) throws IOException {
        String key = "";
        try (InputStream in = context.getAssets().open("runway.key")) {
            byte[] bytes = readBytes(in, 1024);
            key = new String(bytes, StandardCharsets.UTF_8).trim();
        } catch (IOException ignored) { }
        if (!key.startsWith("key_") || key.length() != 132) {
            // Allows testing the open-source/debug build without embedding a personal secret.
            key = context.getSharedPreferences("runway_ru", Context.MODE_PRIVATE).getString("manual_key", "").trim();
        }
        if (!key.startsWith("key_") || key.length() != 132) {
            throw new IOException("API-ключ отсутствует. Укажи ключ в настройках или установи личную сборку.");
        }
        apiKey = key;
    }

    static void saveManualKey(Context context, String key) throws IOException {
        String trimmed = key == null ? "" : key.trim();
        if (!trimmed.matches("key_[0-9a-fA-F]{128}")) throw new IOException("Ожидается ключ Runway Dev вида key_ + 128 шестнадцатеричных символов");
        context.getSharedPreferences("runway_ru", Context.MODE_PRIVATE)
            .edit().putString("manual_key", trimmed).apply();
    }

    static boolean embeddedKeyPresent(Context context) {
        try (InputStream in = context.getAssets().open("runway.key")) {
            String key = new String(readBytes(in, 1024), StandardCharsets.UTF_8).trim();
            return key.matches("key_[0-9a-fA-F]{128}");
        } catch (Exception ignored) { return false; }
    }

    JSONObject checkKey() throws Exception {
        return request("GET", "/v1/avatars?limit=1", null, apiKey);
    }

    String createVoice(String name, String audioUri) throws Exception {
        requireImageAudioUri(audioUri, "audio");
        JSONObject body = new JSONObject()
            .put("name", cap(name, 100))
            .put("from", new JSONObject().put("type","audio").put("audio", audioUri));
        JSONObject response = request("POST", "/v1/voices", body, apiKey);
        String id = response.optString("id", "");
        if (!isUuid(id)) throw new IOException("Runway не вернул ID голоса");
        return id;
    }

    void waitForVoice(String voiceId) throws Exception {
        if (!isUuid(voiceId)) throw new IOException("Неверный ID голоса");
        for (int i = 0; i < 90; i++) {
            JSONObject resp = request("GET", "/v1/voices/" + voiceId, null, apiKey);
            String status = resp.optString("status", "");
            if ("READY".equals(status)) return;
            if ("FAILED".equals(status)) throw new IOException("Клонирование голоса завершилось ошибкой: " + cap(resp.optString("failureReason"), 240));
            if (i < 89) Thread.sleep(2000);
        }
        throw new IOException("Голос ещё обрабатывается в Runway. Повтори действие позднее.");
    }

    String createAvatar(String photoDataUri, String name, String personality, String voiceType, String voiceId) throws Exception {
        requireImageAudioUri(photoDataUri, "image");
        if (name == null || name.trim().isEmpty()) throw new IOException("Укажи имя персонажа");
        if (personality == null || personality.trim().isEmpty()) throw new IOException("Укажи описание персонажа");
        JSONObject voice = new JSONObject();
        if ("custom".equals(voiceType)) {
            if (!isUuid(voiceId)) throw new IOException("Неверный ID клонированного голоса");
            voice.put("type", "custom").put("id", voiceId);
        } else {
            if (!voiceId.matches("clara|victoria|vincent|drew|skye|mia|marcus|ruby|maya|roman|leo|nina|emma|sam|david")) {
                voiceId = "clara";
            }
            voice.put("type", "runway-live-preset").put("presetId", voiceId);
        }
        JSONObject body = new JSONObject()
            .put("name", cap(name, 100))
            .put("referenceImage", photoDataUri)
            .put("personality", cap(personality, 10000))
            .put("voice", voice);
        JSONObject response = request("POST","/v1/avatars",body,apiKey);
        String id = response.optString("id", "");
        if (!isUuid(id)) throw new IOException("Runway не вернул ID персонажа: " + safeMessage(response));
        return id;
    }

    JSONObject startSession(String avatarId, String personality, String startScript) throws Exception {
        if (!isUuid(avatarId)) throw new IOException("Неверный ID персонажа Runway");
        JSONObject body = new JSONObject()
            .put("model", "gwm1_avatars")
            .put("avatar", new JSONObject().put("type", "custom").put("avatarId", avatarId));
        if (personality != null && !personality.trim().isEmpty()) body.put("personality", cap(personality, 10000));
        if (startScript != null && !startScript.trim().isEmpty()) body.put("startScript", cap(startScript, 2000));
        JSONObject created = request("POST", "/v1/realtime_sessions", body, apiKey);
        String sessionId = created.optString("id","");
        if (!isUuid(sessionId)) throw new IOException("Не создана сессия: " + safeMessage(created));
        String sessionKey = null;
        for (int i = 0; i < 60; i++) {
            JSONObject session = request("GET", "/v1/realtime_sessions/" + sessionId, null, apiKey);
            String status = session.optString("status", "");
            if ("READY".equals(status)) {
                sessionKey = session.optString("sessionKey","");
                break;
            }
            if ("FAILED".equals(status) || "CANCELLED".equals(status)) {
                throw new IOException("Сессия Runway завершена до подключения: " + safeMessage(session));
            }
            if (i < 59) Thread.sleep(1000);
        }
        if (sessionKey == null || sessionKey.isEmpty()) throw new IOException("Runway не подготовил видеосвязь за 60 секунд.");
        // The consume token is one-time and is NOT the long-lived Dev API key.
        JSONObject creds = request("POST", "/v1/realtime_sessions/" + sessionId + "/consume", null, sessionKey);
        String url = creds.optString("url","");
        String token = creds.optString("token","");
        if (!url.startsWith("wss://") || token.isEmpty()) throw new IOException("Runway не вернул WebRTC-данные");
        return new JSONObject().put("sessionId",sessionId)
            .put("serverUrl",url).put("token",token)
            .put("roomName",creds.optString("roomName",""));
    }

    private static boolean isUuid(String s) {
        return s != null && s.matches("(?i)[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    }

    private static void requireImageAudioUri(String s, String type) throws IOException {
        if (s == null || !s.startsWith("data:" + type + "/") || s.length() > 5_242_880) {
            throw new IOException(type.equals("image") ? "Фотография должна быть до 5 МБ в JPEG/PNG" : "Референс голоса должен быть до 5 МБ");
        }
    }

    private static String cap(String text, int limit) {
        if (text == null) return "";
        return text.length() > limit ? text.substring(0, limit) : text;
    }

    private static String safeMessage(JSONObject json) {
        JSONObject err = json.optJSONObject("error");
        if (err != null) return cap(err.optString("message",err.optString("description","Ошибка API")),240);
        String errString = json.optString("error","");
        if (!errString.isEmpty()) return cap(errString,240);
        return cap(json.optString("message","Ошибка API Runway"),240);
    }

    private JSONObject request(String method, String path, JSONObject body, String bearer) throws Exception {
        HttpURLConnection conn = (HttpURLConnection)new URL(ROOT + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(35000);
        conn.setUseCaches(false);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("Authorization","Bearer " + bearer);
        conn.setRequestProperty("X-Runway-Version",VERSION);
        conn.setRequestProperty("Accept","application/json");
        try {
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type","application/json");
                try (OutputStream out = conn.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = conn.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? conn.getInputStream() : conn.getErrorStream();
            String response = stream == null ? "" : new String(readBytes(stream, 1024 * 1024), StandardCharsets.UTF_8);
            JSONObject data;
            try { data = response.isEmpty() ? new JSONObject() : new JSONObject(response); }
            catch (JSONException e) { throw new IOException("Runway вернул некорректный JSON (HTTP "+status+")"); }
            if (status < 200 || status >= 300) throw new IOException("Runway HTTP " + status + ": " + safeMessage(data));
            return data;
        } finally { conn.disconnect(); }
    }

    private static byte[] readBytes(InputStream in, int maximum) throws IOException {
        try (InputStream stream=in; ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[8192]; int n;
            while((n=stream.read(buf))!=-1) {
                if(out.size()+n>maximum) throw new IOException("Ответ или файл превышает допустимый размер");
                out.write(buf,0,n);
            }
            return out.toByteArray();
        }
    }
}
