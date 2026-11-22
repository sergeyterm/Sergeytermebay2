package ru.photoavatarru.app;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** ElevenLabs IVC: create a voice from an explicitly authorized, user-chosen recording. */
final class ElevenLabsApi {
    static final class VoiceResult {
        final String id;
        final boolean requiresVerification;
        VoiceResult(String id, boolean requiresVerification) {
            this.id = id;
            this.requiresVerification = requiresVerification;
        }
    }

    static VoiceResult createVoice(String apiKey, InputStream source, String mime, String name)
        throws IOException, JSONException {
        if (apiKey == null || apiKey.trim().isEmpty()) throw new IOException("Нужен ключ ElevenLabs.");
        if (source == null) throw new IOException("Не удалось открыть запись голоса.");
        if (mime == null || !mime.toLowerCase(java.util.Locale.ROOT).startsWith("audio/")) {
            throw new IOException("Выбери аудиофайл MP3, M4A или WAV.");
        }
        String ext;
        if (mime.contains("mpeg") || mime.contains("mp3")) ext = "mp3";
        else if (mime.contains("mp4") || mime.contains("m4a")) ext = "m4a";
        else if (mime.contains("wav")) ext = "wav";
        else throw new IOException("Формат аудио пока не поддерживается. Используй MP3, M4A или WAV.");
        String boundary = "VoiceRef-" + UUID.randomUUID();
        HttpURLConnection conn = (HttpURLConnection) new URL("https://api.elevenlabs.io/v1/voices/add").openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(65000);
        conn.setUseCaches(false);
        conn.setInstanceFollowRedirects(false);
        conn.setDoOutput(true);
        conn.setChunkedStreamingMode(8192);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("xi-api-key", apiKey.trim());
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        try {
            try (InputStream in = source; OutputStream out = conn.getOutputStream()) {
                DIdApi.write(out, "--" + boundary + "\r\n" +
                    "Content-Disposition: form-data; name=\"name\"\r\n\r\n" +
                    name.replace("\r", " ").replace("\n", " ") + "\r\n");
                DIdApi.write(out, "--" + boundary + "\r\n" +
                    "Content-Disposition: form-data; name=\"files[]\"; filename=\"voice." + ext + "\"\r\n" +
                    "Content-Type: " + mime + "\r\n\r\n");
                byte[] buf = new byte[8192];
                int n;
                int count = 0;
                final int maxBytes = 30 * 1024 * 1024;
                while ((n = in.read(buf)) != -1) {
                    count += n;
                    if (count > maxBytes) throw new IOException("Файл голоса превышает 30 МБ.");
                    out.write(buf, 0, n);
                }
                if (count < 10000) throw new IOException("Запись слишком короткая. Рекомендуется 1–2 минуты.");
                DIdApi.write(out, "\r\n--" + boundary + "--\r\n");
            }
            JSONObject reply = DIdApi.checkedJson(conn);
            String voiceId = reply.optString("voice_id", "");
            if (!voiceId.matches("[A-Za-z0-9_-]{3,100}")) {
                throw new IOException("ElevenLabs не вернул ID голоса: " + DIdApi.message(reply));
            }
            return new VoiceResult(voiceId, reply.optBoolean("requires_verification", false));
        } finally {
            conn.disconnect();
        }
    }
}
