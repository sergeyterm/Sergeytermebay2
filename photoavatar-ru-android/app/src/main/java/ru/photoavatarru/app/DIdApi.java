package ru.photoavatarru.app;

import android.util.Base64;
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
import java.util.UUID;

/** The private D-ID credential lives only in memory, and is never passed into WebView. */
final class DIdApi {
    private static final String API = "https://api.d-id.com";
    private final String basicHeader;

    DIdApi(String rawKey) {
        String key = rawKey == null ? "" : rawKey.trim();
        if (!key.contains(":")) {
            throw new IllegalArgumentException("Ключ D-ID должен иметь формат USER:PASSWORD. Ключ Nuance не подходит.");
        }
        basicHeader = "Basic " + Base64.encodeToString(
            key.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    String uploadPhoto(byte[] jpeg) throws IOException, JSONException {
        if (jpeg == null || jpeg.length < 100 || jpeg.length > 10 * 1024 * 1024) {
            throw new IOException("Фотография должна быть JPEG до 10 МБ.");
        }
        String boundary = "PhotoAvatar-" + UUID.randomUUID();
        HttpURLConnection conn = open("/images", "POST");
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        conn.setChunkedStreamingMode(8192);
        conn.setDoOutput(true);
        try {
            try (OutputStream out = conn.getOutputStream()) {
                write(out, "--" + boundary + "\r\n" +
                    "Content-Disposition: form-data; name=\"image\"; filename=\"portrait.jpg\"\r\n" +
                    "Content-Type: image/jpeg\r\n\r\n");
                out.write(jpeg);
                write(out, "\r\n--" + boundary + "--\r\n");
            }
            JSONObject reply = checkedJson(conn);
            String url = reply.optString("url", reply.optString("source_url", ""));
            if (!url.startsWith("https://")) {
                throw new IOException("D-ID не вернул URL фотографии: " + message(reply));
            }
            return url;
        } finally {
            conn.disconnect();
        }
    }

    String createPhotoAgent(String photoUrl, String name, String prompt, String ttsType,
                            String voiceId, String elevenLabsApiKey) throws IOException, JSONException {
        JSONObject voice = new JSONObject().put("type", ttsType).put("voice_id", voiceId);
        if ("elevenlabs".equals(ttsType)) voice.put("model_id", "eleven_flash_v2_5");
        // D-ID currently accepts only one source_url for a photo avatar (NOT multi-view fusion).
        JSONObject presenter = new JSONObject().put("type", "talk")
            .put("source_url", photoUrl).put("voice", voice);
        JSONObject llm = new JSONObject().put("provider", "openai")
            .put("model", "gpt-4.1-mini").put("instructions", prompt);
        JSONObject payload = new JSONObject()
            .put("preview_name", name)
            .put("preview_description", "Русскоязычный фотоаватар для видеозвонков")
            .put("presenter", presenter)
            .put("llm", llm)
            .put("embed", true)
            .put("greetings", new JSONArray().put("Привет! Я на связи. О чём поговорим?"));
        String externalKey = "";
        if ("elevenlabs".equals(ttsType)) {
            if (elevenLabsApiKey == null || elevenLabsApiKey.trim().isEmpty()) {
                throw new IOException("Для собственного голоса нужен API-ключ ElevenLabs.");
            }
            externalKey = new JSONObject().put("elevenlabs", elevenLabsApiKey.trim()).toString();
        }
        JSONObject reply = postJson("/agents", payload, externalKey);
        String agentId = reply.optString("id", "");
        if (!agentId.matches("[A-Za-z0-9_-]{3,100}")) {
            throw new IOException("D-ID не вернул корректный ID агента: " + message(reply));
        }
        return agentId;
    }

    String createClientKey() throws IOException, JSONException {
        JSONObject req = new JSONObject().put("allowed_domains",
            new JSONArray().put("https://appassets.androidplatform.net"));
        JSONObject reply = postJson("/agents/client-key", req, "");
        String key = reply.optString("client_key", "");
        if (key.isEmpty() || key.length() > 4096 || key.indexOf('"') >= 0 || key.indexOf('<') >= 0) {
            throw new IOException("D-ID не вернул корректный ключ видеосессии: " + message(reply));
        }
        return key;
    }

    /** Most photo agents are prepared asynchronously; don't launch a call until ready. */
    void requireReady(String agentId) throws IOException, JSONException {
        if (!agentId.matches("[A-Za-z0-9_-]{3,100}")) throw new IOException("Неверный ID агента.");
        for (int attempt = 0; attempt < 12; attempt++) {
            HttpURLConnection conn = open("/agents/" + agentId, "GET");
            JSONObject info;
            try { info = checkedJson(conn); } finally { conn.disconnect(); }
            String status = info.optString("status", "");
            if ("done".equalsIgnoreCase(status) || "ready".equalsIgnoreCase(status)) return;
            if ("error".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status)) {
                throw new IOException("D-ID не смог обработать фотоаватар: " + message(info));
            }
            if (attempt < 11) {
                try { Thread.sleep(2500); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException("Ожидание прервано.", ex); }
            }
        }
        throw new IOException("D-ID ещё подготавливает аватара. Подожди немного и нажми «Повторить подключение». ");
    }

    private JSONObject postJson(String path, JSONObject data, String externalKey)
        throws IOException, JSONException {
        HttpURLConnection conn = open(path, "POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (!externalKey.isEmpty()) conn.setRequestProperty("x-api-key-external", externalKey);
        conn.setDoOutput(true);
        try {
            try (OutputStream out = conn.getOutputStream()) {
                out.write(data.toString().getBytes(StandardCharsets.UTF_8));
            }
            return checkedJson(conn);
        } finally {
            conn.disconnect();
        }
    }

    private HttpURLConnection open(String path, String method) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(API + path).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(60000);
        conn.setUseCaches(false);
        conn.setInstanceFollowRedirects(false);
        conn.setRequestProperty("Authorization", basicHeader);
        conn.setRequestProperty("Accept", "application/json");
        return conn;
    }

    static JSONObject checkedJson(HttpURLConnection conn) throws IOException, JSONException {
        int code = conn.getResponseCode();
        InputStream body = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        String raw = body == null ? "" : readLimited(body, 256 * 1024);
        if (code < 200 || code >= 300) {
            String detail = raw;
            try { detail = message(new JSONObject(raw)); } catch (JSONException ignored) {}
            throw new IOException("HTTP " + code + ": " + limit(detail, 300));
        }
        if (raw.trim().isEmpty()) throw new IOException("API вернул пустой ответ (HTTP " + code + ")");
        return new JSONObject(raw);
    }

    static String message(JSONObject json) {
        JSONObject error = json.optJSONObject("error");
        if (error != null) {
            String detail = error.optString("description", error.optString("message", ""));
            if (!detail.isEmpty()) return limit(detail, 300);
        }
        return limit(json.optString("message", json.optString("description", "Неожиданный ответ API")), 300);
    }

    static String readLimited(InputStream stream, int cap) throws IOException {
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                if (out.size() + n > cap) throw new IOException("Ответ сервера слишком большой.");
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    static void write(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
    }

    static String limit(String s, int n) { return s == null ? "" : s.length() > n ? s.substring(0,n) + "…" : s; }
}
