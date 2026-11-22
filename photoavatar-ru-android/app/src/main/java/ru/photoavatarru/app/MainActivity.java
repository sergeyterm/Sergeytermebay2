package ru.photoavatarru.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * PhotoAvatar RU: a phone-oriented wrapper around D-ID photo agents and ElevenLabs IVC.
 * The first photo selected by the user is the ONLY photo supplied to the avatar engine;
 * additional photos are retained for user-side reference/choice, not fused by D-ID.
 */
public final class MainActivity extends Activity {
    private static final int PHOTOS_REQUEST = 101;
    private static final int VOICE_REQUEST = 102;
    private static final int AUDIO_PERMISSION_REQUEST = 103;
    private static final int MAX_PHOTOS = 1;
    private static final int BG = Color.rgb(8, 23, 29);
    private static final int CARD = Color.rgb(20, 43, 51);
    private static final int PANEL = Color.rgb(29, 57, 65);
    private static final int TEXT = Color.rgb(239, 250, 248);
    private static final int MUTED = Color.rgb(163, 192, 188);
    private static final int GREEN = Color.rgb(32, 203, 146);
    private static final int RED = Color.rgb(226, 73, 79);
    private static final String ORIGIN = "https://appassets.androidplatform.net/";

    private final ExecutorService jobs = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<Uri> photos = new ArrayList<>();
    private SharedPreferences prefs;
    private int mainPhotoIndex = 0;
    private int selectedPhotoTile = -1; // -1 entire single portrait; 0..8 grid cell
    private Uri referenceAudio;
    private FrameLayout root;
    private LinearLayout photoGrid;
    private TextView photoInfo, voiceInfo, status, callState;
    private EditText didKey, elevenKey, nameField, topicField, personaField, voiceIdField;
    private Spinner voiceMode;
    private CheckBox consent;
    private Button createButton, muteButton, speakerButton;
    private WebView callWebView;
    private PermissionRequest waitingAudioRequest;
    private boolean micMuted, speakerMuted, busy;
    private Runnable statusPoll;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("photo_avatar_ru", MODE_PRIVATE);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        } else {
            root.setFitsSystemWindows(true);
        }
        setContentView(root);
        restoreSelectedPhotos();
        String audioUri = prefs.getString("voice_reference_uri", "");
        if (!audioUri.isEmpty()) {
            Uri candidate = Uri.parse(audioUri);
            try (InputStream in = getContentResolver().openInputStream(candidate)) {
                if (in != null) referenceAudio = candidate;
            } catch (Exception ignored) { prefs.edit().remove("voice_reference_uri").apply(); }
        }
        showSetup();
    }

    private void showSetup() {
        destroyCall();
        root.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout content = column();
        content.setPadding(dp(19), dp(26), dp(19), dp(38));
        scroll.addView(content);

        content.addView(label("PHOTOAVATAR  /  RU", 13, GREEN, true));
        content.addView(label("Разговор лицом к лицу", 30, TEXT, true), margin(0, 10, 0, 5));
        content.addView(label("Фото, голос и характер собеседника. Интерфейс видеозвонка для Android.", 14, MUTED, false), margin(0, 0, 0, 21));

        String savedAgent = prefs.getString("agent_id", "");
        String savedClientKey = prefs.getString("client_key", "");
        if (!savedAgent.isEmpty() && !savedClientKey.isEmpty()) {
            LinearLayout ready = card();
            content.addView(ready, margin(0, 0, 0, 18));
            ready.addView(label("●  Собеседник создан", 14, GREEN, true));
            ready.addView(label(prefs.getString("agent_name", "Собеседник"), 22, TEXT, true), margin(0, 7, 0, 12));
            Button call = button("Видеозвонок", GREEN, BG);
            ready.addView(call);
            call.setOnClickListener(v -> showCall());
            Button reset = button("Убрать подключение с телефона", PANEL, MUTED);
            ready.addView(reset, margin(0, 10, 0, 0));
            reset.setOnClickListener(v -> {
                // This only deletes local client credentials, NOT the server-side paid agent.
                prefs.edit().remove("agent_id").remove("client_key").remove("agent_name").apply();
                showSetup();
            });
        }

        LinearLayout avatar = card();
        content.addView(avatar, margin(0, 0, 0, 15));
        avatar.addView(label("01  Внешность собеседника", 18, TEXT, true));
        avatar.addView(label("Выбери одну фотографию или коллаж 3×3. Для коллажа нажми на ячейку с фронтальным портретом — только она будет отправлена в D-ID.", 13, MUTED, false), margin(0, 7, 0, 11));
        photoGrid = column();
        avatar.addView(photoGrid);
        photoInfo = label("", 12, MUTED, false);
        avatar.addView(photoInfo, margin(0, 8, 0, 8));
        Button selectPhotos = button("Выбрать портрет / коллаж 3×3", PANEL, TEXT);
        avatar.addView(selectPhotos);
        selectPhotos.setOnClickListener(v -> choosePhotos());
        avatar.addView(label("D-ID не объединяет девять ракурсов в 3D-модель. Он анимирует выбранный одиночный портрет. Для качества нужен крупный фронтальный ракурс без теней.", 12, MUTED, false), margin(0, 10, 0, 0));
        drawPhotos();

        LinearLayout character = card();
        content.addView(character, margin(0, 0, 0, 15));
        character.addView(label("02  Личность и разговор", 18, TEXT, true));
        character.addView(label("Имя собеседника", 13, MUTED, false), margin(0, 13, 0, 3));
        nameField = input("Например, Александр", false, false);
        nameField.setText(prefs.getString("draft_name", "Собеседник"));
        character.addView(nameField);
        character.addView(label("О чём будем разговаривать?", 13, MUTED, false), margin(0, 13, 0, 3));
        topicField = input("Опиши предысторию, тему и важные факты…", false, true);
        topicField.setText(prefs.getString("draft_topic", "Знакомство и свободная беседа на русском языке."));
        character.addView(topicField);
        character.addView(label("Характер, манера речи и поведение", 13, MUTED, false), margin(0, 13, 0, 3));
        personaField = input("Например, спокойный, дружелюбный, с юмором…", false, true);
        personaField.setText(prefs.getString("draft_persona", "Разговаривай естественно, с теплотой, слушай и задавай вопросы."));
        character.addView(personaField);

        LinearLayout voice = card();
        content.addView(voice, margin(0, 0, 0, 15));
        voice.addView(label("03  Голос", 18, TEXT, true));
        voice.addView(label("Готовый русский голос или голосовой референс через ElevenLabs.", 13, MUTED, false), margin(0, 7, 0, 10));
        voiceMode = new Spinner(this);
        String[] options = {"Женский — Светлана (RU)", "Мужской — Дмитрий (RU)", "Собственный голос — ElevenLabs"};
        voiceMode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, options));
        voiceMode.setSelection(Math.min(2, Math.max(0, prefs.getInt("draft_voice", 0))));
        voiceMode.setBackground(rounded(PANEL, 13));
        voice.addView(voiceMode, margin(0, 0, 0, 10));
        Button chooseVoice = button("Загрузить голосовой референс", PANEL, TEXT);
        voice.addView(chooseVoice);
        chooseVoice.setOnClickListener(v -> chooseAudio());
        voiceInfo = label("", 12, MUTED, false);
        voice.addView(voiceInfo, margin(0, 7, 0, 10));
        voiceInfo.setText(referenceAudio == null ? "Референс пока не выбран." : "Файл: " + fileName(referenceAudio));
        voice.addView(label("ID голоса ElevenLabs (можно вставить, если уже создан)", 12, MUTED, false));
        voiceIdField = input("Необязательно при загрузке аудио", false, false);
        voiceIdField.setText(prefs.getString("draft_voice_id", ""));
        voice.addView(voiceIdField, margin(0, 5, 0, 7));
        voice.addView(label("Для похожего голоса лучше 1–2 минуты чистой русской речи. Клонирование и использование собственного голоса зависят от тарифа и правил ElevenLabs.", 12, MUTED, false));

        LinearLayout credentials = card();
        content.addView(credentials, margin(0, 0, 0, 15));
        credentials.addView(label("04  Подключение API", 18, TEXT, true));
        credentials.addView(label("Нужен D-ID API Key. Для собственного голоса — ещё и ElevenLabs API Key. Ключ Nuance здесь не работает; постоянные секреты не сохраняются.", 13, MUTED, false), margin(0, 7, 0, 10));
        credentials.addView(label("D-ID API Key (USER:PASSWORD)", 12, MUTED, false));
        didKey = input("Ключ D-ID", true, false);
        credentials.addView(didKey, margin(0, 4, 0, 10));
        credentials.addView(label("ElevenLabs API Key (если свой голос)", 12, MUTED, false));
        elevenKey = input("Ключ ElevenLabs", true, false);
        credentials.addView(elevenKey, margin(0, 4, 0, 8));
        Button didLink = button("Открыть D-ID Studio", PANEL, TEXT);
        credentials.addView(didLink);
        didLink.setOnClickListener(v -> external("https://studio.d-id.com/"));
        Button elevenLink = button("Открыть ElevenLabs", PANEL, TEXT);
        credentials.addView(elevenLink, margin(0, 7, 0, 0));
        elevenLink.setOnClickListener(v -> external("https://elevenlabs.io/"));

        consent = new CheckBox(this);
        consent.setText("У меня есть разрешение на использование фотографий и голоса. Я понимаю, что это виртуальный персонаж, а создание может расходовать платные кредиты.");
        consent.setTextSize(13);
        consent.setTextColor(TEXT);
        consent.setButtonTintList(ColorStateList.valueOf(GREEN));
        content.addView(consent, margin(1, 0, 0, 14));
        createButton = button("Создать собеседника", GREEN, BG);
        content.addView(createButton);
        createButton.setOnClickListener(v -> createAvatar());
        status = label("", 13, TEXT, false);
        content.addView(status, margin(0, 10, 0, 0));
        String pendingAgent = prefs.getString("pending_agent_id", "");
        if (!pendingAgent.isEmpty()) {
            Button retry = button("Повторить подключение созданного аватара", PANEL, TEXT);
            content.addView(retry, margin(0, 9, 0, 0));
            retry.setOnClickListener(v -> finishPendingAgent(pendingAgent));
        }
        content.addView(label("Приложение не является WhatsApp. Внешний вид напоминает видеозвонок; связь с аватаром обеспечивает D-ID.", 12, MUTED, false), margin(1, 17, 1, 0));
    }

    private void choosePhotos() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        try { startActivityForResult(intent, PHOTOS_REQUEST); }
        catch (ActivityNotFoundException e) { toast("Не удалось открыть выбор фотографий"); }
    }

    private void chooseAudio() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("audio/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(intent, VOICE_REQUEST); }
        catch (ActivityNotFoundException e) { toast("Не удалось открыть выбор аудиофайла"); }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == PHOTOS_REQUEST) {
            Uri chosen = data.getData();
            if (chosen == null && data.getClipData() != null && data.getClipData().getItemCount() > 0)
                chosen = data.getClipData().getItemAt(0).getUri();
            if (chosen == null) return;
            photos.clear();
            photos.add(chosen);
            retainReadPermission(chosen, data.getFlags());
            mainPhotoIndex = 0;
            selectedPhotoTile = -1;
            saveSelectedPhotos();
            drawPhotos();
        } else if (requestCode == VOICE_REQUEST && data.getData() != null) {
            referenceAudio = data.getData();
            retainReadPermission(referenceAudio, data.getFlags());
            voiceIdField.setText("");
            prefs.edit().putString("voice_reference_uri", referenceAudio.toString()).remove("draft_voice_id").apply();
            voiceInfo.setText("Выбрано: " + fileName(referenceAudio));
            voiceMode.setSelection(2);
        }
    }

    private void retainReadPermission(Uri uri, int flags) {
        try {
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) { /* picker grant may be temporary */ }
    }

    private void saveSelectedPhotos() {
        JSONArray arr = new JSONArray();
        for (Uri uri : photos) arr.put(uri.toString());
        prefs.edit().putString("photo_uris", arr.toString())
            .putInt("main_photo_index", mainPhotoIndex)
            .putInt("selected_photo_tile", selectedPhotoTile).apply();
    }

    private void restoreSelectedPhotos() {
        try {
            JSONArray arr = new JSONArray(prefs.getString("photo_uris", "[]"));
            for (int i = 0; i < Math.min(arr.length(), MAX_PHOTOS); i++) {
                Uri uri = Uri.parse(arr.getString(i));
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in != null) photos.add(uri);
                } catch (Exception ignored) { /* selected file deleted/revoked */ }
            }
            mainPhotoIndex = Math.min(photos.size() - 1, Math.max(0, prefs.getInt("main_photo_index", 0)));
            selectedPhotoTile = Math.max(-1, Math.min(8, prefs.getInt("selected_photo_tile", -1)));
            if (mainPhotoIndex < 0) mainPhotoIndex = 0;
        } catch (JSONException ignored) { prefs.edit().remove("photo_uris").apply(); }
    }

    /** A 3x3 collage is a photo selector, never multi-view training data. */
    private void drawPhotos() {
        if (photoGrid == null || photoInfo == null) return;
        photoGrid.removeAllViews();
        if (photos.isEmpty()) {
            photoInfo.setText("Изображение пока не выбрано.");
            photoGrid.addView(label("▧     Добавь портрет или коллаж", 15, MUTED, false), margin(0, 14, 0, 12));
            return;
        }
        Bitmap image;
        try { image = readPhotoOriented(photos.get(0), 1200); }
        catch (Exception ex) {
            photoInfo.setText("Ошибка чтения фото: " + friendly(ex));
            return;
        }
        ImageView full = new ImageView(this);
        full.setImageBitmap(image);
        full.setScaleType(ImageView.ScaleType.FIT_CENTER);
        full.setAdjustViewBounds(true);
        full.setMaxHeight(dp(265));
        photoGrid.addView(full, margin(0, 0, 0, 8));
        Button single = button(selectedPhotoTile == -1 ? "✓ Использовать весь портрет" : "Использовать весь портрет", PANEL, TEXT);
        single.setOnClickListener(v -> {selectedPhotoTile = -1;saveSelectedPhotos();drawPhotos();});
        photoGrid.addView(single, margin(0, 4, 0, 10));
        photoGrid.addView(label("Если это коллаж 3×3, укажи фронтальный ракурс:", 13, MUTED, false), margin(0, 0, 0, 8));
        int fullW = image.getWidth(), fullH = image.getHeight();
        for (int row = 0; row < 3; row++) {
            LinearLayout line = new LinearLayout(this);
            line.setOrientation(LinearLayout.HORIZONTAL);
            photoGrid.addView(line, margin(0, 2, 0, 2));
            for (int col = 0; col < 3; col++) {
                int index = row * 3 + col;
                final int left = col * fullW / 3, top = row * fullH / 3;
                final int width = (col + 1) * fullW / 3 - left;
                final int height = (row + 1) * fullH / 3 - top;
                Bitmap cropped = Bitmap.createBitmap(image, left, top, width, height);
                FrameLayout frame = new FrameLayout(this);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(108), 1f);
                params.setMargins(dp(2), 0, dp(2), 0);
                line.addView(frame, params);
                frame.setBackground(rounded(selectedPhotoTile == index ? GREEN : PANEL, 12));
                frame.setClipToOutline(true);
                ImageView thumb = new ImageView(this);
                thumb.setImageBitmap(cropped);
                thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
                FrameLayout.LayoutParams thumbParams = new FrameLayout.LayoutParams(-1, -1);
                thumbParams.setMargins(dp(3), dp(3), dp(3), dp(3));
                frame.addView(thumb, thumbParams);
                TextView badge = label(selectedPhotoTile == index ? "✓ " + (index + 1) : "" + (index + 1), 12, Color.WHITE, true);
                badge.setGravity(Gravity.CENTER);
                badge.setBackground(rounded(selectedPhotoTile == index ? GREEN : BG, 6));
                FrameLayout.LayoutParams flag = new FrameLayout.LayoutParams(dp(35), dp(28), Gravity.BOTTOM | Gravity.RIGHT);
                flag.setMargins(0, 0, dp(4), dp(4));
                frame.addView(badge, flag);
                frame.setOnClickListener(v -> { selectedPhotoTile = index; saveSelectedPhotos(); drawPhotos(); });
            }
        }
        photoInfo.setText(selectedPhotoTile == -1 ? "Активный источник: весь портрет" :
            "Активный источник: ячейка №" + (selectedPhotoTile + 1) + " (отдельный портрет)");
    }

    private void createAvatar() {
        if (busy) return;
        if (photos.isEmpty() || mainPhotoIndex >= photos.size()) {
            showStatus("Нужно выбрать хотя бы одну фотографию."); return;
        }
        if (!consent.isChecked()) {
            showStatus("Подтверди разрешение на использование фотографий и голоса."); return;
        }
        final String rawDid = didKey.getText().toString().trim();
        if (rawDid.startsWith("nk_")) { showStatus("Это ключ Nuance. Для фотоаватара необходим D-ID API Key."); return; }
        if (!rawDid.contains(":")) { showStatus("Введи ключ D-ID в формате USER:PASSWORD."); return; }
        final String externalKey = elevenKey.getText().toString().trim();
        final int mode = voiceMode.getSelectedItemPosition();
        final String customVoiceId = voiceIdField.getText().toString().trim();
        if (mode == 2 && externalKey.isEmpty()) { showStatus("Для клонированного голоса введи ключ ElevenLabs."); return; }
        if (mode == 2 && customVoiceId.isEmpty() && referenceAudio == null) {
            showStatus("Выбери запись голоса или вставь Voice ID из ElevenLabs."); return;
        }
        final String name = nameField.getText().toString().trim();
        final String topic = topicField.getText().toString().trim();
        final String persona = personaField.getText().toString().trim();
        if (name.isEmpty()) { showStatus("Укажи имя собеседника."); return; }
        if (name.length() > 150 || topic.length() > 6000 || persona.length() > 6000) {
            showStatus("Слишком длинное имя или описание. Сократи текст."); return;
        }
        prefs.edit().putString("draft_name", name).putString("draft_topic", topic)
            .putString("draft_persona", persona).putInt("draft_voice", mode).apply();
        final Uri chosen = photos.get(0);
        final int tileForAvatar = selectedPhotoTile;
        setBusy(true);
        showStatus("Подготавливаю фотографию…");
        jobs.execute(() -> {
            try {
                String voiceProvider = "microsoft";
                String voiceId = mode == 1 ? "ru-RU-DmitryNeural" : "ru-RU-SvetlanaNeural";
                if (mode == 2) {
                    voiceProvider = "elevenlabs";
                    voiceId = customVoiceId;
                    if (voiceId.isEmpty()) {
                        step("Клонирую голос через ElevenLabs…");
                        String mime = audioMime(referenceAudio);
                        ElevenLabsApi.VoiceResult v;
                        try (InputStream audio = getContentResolver().openInputStream(referenceAudio)) {
                            v = ElevenLabsApi.createVoice(externalKey, audio, mime, name + " · Референс");
                        }
                        prefs.edit().putString("draft_voice_id", v.id).apply();
                        runOnUiThread(() -> { if (voiceIdField != null) voiceIdField.setText(v.id); });
                        if (v.requiresVerification) {
                            throw new IOException("Голос создан, но ElevenLabs требует подтверждения. Выполни его в аккаунте ElevenLabs. ID голоса сохранён.");
                        }
                        voiceId = v.id;
                    }
                }
                byte[] photo = photoJpeg(chosen, tileForAvatar);
                DIdApi did = new DIdApi(rawDid);
                step("Загружаю основную фотографию в D-ID…");
                String photoUrl = did.uploadPhoto(photo);
                String prompt = "Ты русскоязычный виртуальный собеседник по имени " + name + ". " +
                    "Всегда говори по-русски, естественно и доброжелательно. " +
                    "Не выдавай себя за реального человека на фотографии, если об этом спросят. " +
                    "Предыстория и тема разговора: " + topic + "\n" +
                    "Характер и манера общения: " + persona + "\n" +
                    "Запоминай контекст текущей беседы, отвечай по существу, задавай естественные встречные вопросы.";
                step("Создаю собеседника…");
                String agentId = did.createPhotoAgent(photoUrl, name, prompt,
                    voiceProvider, voiceId, mode == 2 ? externalKey : "");
                prefs.edit().putString("pending_agent_id", agentId).putString("pending_agent_name", name).apply();
                step("Ожидаю готовность видеоаватара…");
                did.requireReady(agentId);
                step("Подготавливаю видеосвязь…");
                String key = did.createClientKey();
                prefs.edit().putString("agent_id", agentId).putString("client_key", key)
                    .putString("agent_name", name).remove("pending_agent_id").apply();
                runOnUiThread(() -> { setBusy(false); showSetup(); toast("Собеседник создан. Нажми «Видеозвонок»."); });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false);
                    showStatus("Не удалось создать собеседника: " + friendly(error));
                });
            }
        });
    }

    private void finishPendingAgent(String agent) {
        String key = didKey.getText().toString().trim();
        if (!key.contains(":")) { showStatus("Введи ключ D-ID для восстановления подключения."); return; }
        setBusy(true);
        step("Подготавливаю видеосвязь ранее созданного аватара…");
        jobs.execute(() -> {
            try {
                DIdApi did = new DIdApi(key);
                did.requireReady(agent);
                String client = did.createClientKey();
                prefs.edit().putString("agent_id", agent).putString("client_key", client)
                    .putString("agent_name", prefs.getString("pending_agent_name", "Собеседник"))
                    .remove("pending_agent_id").apply();
                runOnUiThread(() -> { setBusy(false); showSetup(); });
            } catch (Exception ex) {
                runOnUiThread(() -> { setBusy(false); showStatus("Не удалось подключить аватара: " + friendly(ex)); });
            }
        });
    }

    private void showCall() {
        String agent = prefs.getString("agent_id", "");
        String client = prefs.getString("client_key", "");
        if (agent.isEmpty() || client.isEmpty()) { showSetup(); return; }
        root.removeAllViews();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        micMuted = false;
        speakerMuted = false;
        LinearLayout shell = column();
        root.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(17), dp(6), dp(17), dp(6));
        shell.addView(header, new LinearLayout.LayoutParams(-1, dp(70)));
        LinearLayout headText = column();
        header.addView(headText, new LinearLayout.LayoutParams(0, -2, 1));
        headText.addView(label(prefs.getString("agent_name", "Собеседник"), 20, TEXT, true));
        callState = label("Соединение… · Видеозвонок AI", 12, GREEN, false);
        headText.addView(callState, margin(0, 2, 0, 0));
        Button close = button("Закрыть", PANEL, TEXT);
        header.addView(close, new LinearLayout.LayoutParams(dp(93), dp(45)));
        close.setOnClickListener(v -> showSetup());

        callWebView = new WebView(this);
        callWebView.setBackgroundColor(BG);
        callWebView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        WebSettings web = callWebView.getSettings();
        web.setJavaScriptEnabled(true);
        web.setDomStorageEnabled(true);
        web.setMediaPlaybackRequiresUserGesture(false);
        web.setJavaScriptCanOpenWindowsAutomatically(false);
        web.setAllowFileAccess(false);
        web.setAllowContentAccess(false);
        web.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        callWebView.setWebViewClient(new WebViewClient() {
            @Override public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError error) {
                if (req.isForMainFrame() && callState != null) callState.setText("Ошибка загрузки страницы разговора");
            }
        });
        callWebView.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest req) {
                runOnUiThread(() -> handleAudioPermission(req));
            }
            @Override public void onPermissionRequestCanceled(PermissionRequest req) {
                if (waitingAudioRequest == req) waitingAudioRequest = null;
            }
        });
        shell.addView(callWebView, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(dp(13), dp(12), dp(13), dp(19));
        controls.setBackgroundColor(BG);
        shell.addView(controls, new LinearLayout.LayoutParams(-1, dp(100)));
        muteButton = button("🎙  Микрофон", PANEL, TEXT);
        speakerButton = button("🔊  Звук", PANEL, TEXT);
        Button hangup = button("●  Завершить", RED, Color.WHITE);
        for (Button b : new Button[]{muteButton, speakerButton, hangup}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(60), 1f);
            lp.setMargins(dp(3), 0, dp(3), 0);
            controls.addView(b, lp);
            b.setTextSize(12);
        }
        muteButton.setOnClickListener(v -> useEmbedControl("toggleMicState", true));
        speakerButton.setOnClickListener(v -> useEmbedControl("toggleSpeakerState", false));
        hangup.setOnClickListener(v -> showSetup());
        try {
            String html = readAsset("agent.html")
                .replace("__AGENT_ID__", TextUtils.htmlEncode(agent))
                .replace("__CLIENT_KEY__", TextUtils.htmlEncode(client));
            callWebView.loadDataWithBaseURL(ORIGIN, html, "text/html", "UTF-8", null);
            startConnectionPolling();
        } catch (Exception ex) {
            callState.setText("Не удалось открыть видеозвонок: " + friendly(ex));
        }
    }

    private void startConnectionPolling() {
        if (statusPoll != null) handler.removeCallbacks(statusPoll);
        statusPoll = new Runnable() {
            @Override public void run() {
                if (callWebView == null || callState == null) return;
                callWebView.evaluateJavascript("(function(){return window.__avatarConnectionState||'starting'})()", result -> {
                    if (callState == null || result == null) return;
                    String s = result.replace("\"", "");
                    switch (s) {
                        case "connected": callState.setText("●  На связи · Видеозвонок AI"); break;
                        case "connecting": callState.setText("Соединение с собеседником…"); break;
                        case "fail": callState.setText("Не удалось установить связь"); break;
                        case "disconnected": callState.setText("Связь прервана"); break;
                        case "closed": case "completed": callState.setText("Звонок завершён"); break;
                        default: break;
                    }
                });
                if (callWebView != null) handler.postDelayed(this, 1400);
            }
        };
        handler.postDelayed(statusPoll, 1200);
    }

    private void useEmbedControl(String method, boolean mic) {
        if (callWebView == null) return;
        String script = "(function(){try {var a=window.DID_AGENTS_API;" +
            "if(!a||!a.functions||!a.functions." + method + ")return 'not-ready';" +
            "a.functions." + method + "(" + ((mic ? !micMuted : !speakerMuted) ? "true" : "false") + ");return 'ok';}catch(e){return 'error';}})()";
        callWebView.evaluateJavascript(script, result -> {
            if (!"\"ok\"".equals(result)) {
                toast("Дождись подключения видеосвязи или используй кнопки в окне D-ID.");
                return;
            }
            if (mic) {
                micMuted = !micMuted;
                muteButton.setText(micMuted ? "🔇  Включить микрофон" : "🎙  Микрофон");
            } else {
                speakerMuted = !speakerMuted;
                speakerButton.setText(speakerMuted ? "🔈  Включить звук" : "🔊  Звук");
            }
        });
    }

    private void handleAudioPermission(PermissionRequest req) {
        String host = req.getOrigin() == null ? "" : req.getOrigin().getHost();
        if (host == null) host = "";
        if (req.getOrigin() == null || !"https".equalsIgnoreCase(req.getOrigin().getScheme()) ||
            !("appassets.androidplatform.net".equals(host) || "agent.d-id.com".equals(host) || host.endsWith(".d-id.com"))) {
            req.deny(); return;
        }
        String[] resources = req.getResources();
        if (resources.length != 1 || !PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resources[0])) {
            req.deny(); return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            req.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        } else {
            waitingAudioRequest = req;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION_REQUEST);
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(code, permissions, results);
        if (code == AUDIO_PERMISSION_REQUEST && waitingAudioRequest != null) {
            PermissionRequest req = waitingAudioRequest;
            waitingAudioRequest = null;
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED)
                req.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            else req.deny();
        }
    }

    private void destroyCall() {
        if (statusPoll != null) { handler.removeCallbacks(statusPoll); statusPoll = null; }
        if (waitingAudioRequest != null) { waitingAudioRequest.deny(); waitingAudioRequest = null; }
        if (callWebView != null) {
            try { callWebView.loadUrl("about:blank"); callWebView.stopLoading(); callWebView.destroy(); }
            catch (Exception ignored) {}
            callWebView = null;
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        callState = null;
        muteButton = null;
        speakerButton = null;
    }

    private String audioMime(Uri uri) {
        String mime = getContentResolver().getType(uri);
        if (mime != null && mime.startsWith("audio/")) return mime;
        String lower = fileName(uri).toLowerCase(Locale.ROOT);
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".m4a") || lower.endsWith(".mp4")) return "audio/mp4";
        if (lower.endsWith(".wav")) return "audio/wav";
        return "application/octet-stream";
    }

    private String fileName(Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String name = cursor.getString(0);
                if (name != null) return DIdApi.limit(name, 85);
            }
        } catch (Exception ignored) {}
        return "Выбранный файл";
    }

    private Bitmap readPhoto(Uri uri, int limit) throws IOException {
        BitmapFactory.Options option = new BitmapFactory.Options();
        option.inJustDecodeBounds = true;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Файл недоступен");
            BitmapFactory.decodeStream(in, null, option);
        }
        if (option.outWidth <= 0 || option.outHeight <= 0) throw new IOException("Формат фото не поддерживается");
        int sample = 1;
        while (Math.max(option.outWidth, option.outHeight) / sample > limit * 2) sample *= 2;
        option.inJustDecodeBounds = false;
        option.inSampleSize = sample;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Файл недоступен");
            Bitmap bitmap = BitmapFactory.decodeStream(in, null, option);
            if (bitmap == null) throw new IOException("Не удалось декодировать изображение");
            return bitmap;
        }
    }

    /** Decode and honor EXIF before cropping a 3x3 collage cell. */
    private Bitmap readPhotoOriented(Uri uri, int maxPx) throws IOException {
        Bitmap source = readPhoto(uri, maxPx);
        int orientation = ExifInterface.ORIENTATION_NORMAL;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in != null) orientation = new ExifInterface(in)
                .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch (Exception ignored) {}
        Matrix matrix = new Matrix();
        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90: matrix.postRotate(90); break;
            case ExifInterface.ORIENTATION_ROTATE_180: matrix.postRotate(180); break;
            case ExifInterface.ORIENTATION_ROTATE_270: matrix.postRotate(270); break;
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL: matrix.postScale(1, -1); break;
            case ExifInterface.ORIENTATION_TRANSPOSE: matrix.postRotate(90); matrix.postScale(-1, 1); break;
            case ExifInterface.ORIENTATION_TRANSVERSE: matrix.postRotate(270); matrix.postScale(-1, 1); break;
            default: break;
        }
        if (!matrix.isIdentity()) {
            Bitmap rotated = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
            if (rotated != source) source.recycle();
            source = rotated;
        }
        return source;
    }

    private byte[] photoJpeg(Uri uri, int selectedCell) throws IOException {
        Bitmap source = readPhotoOriented(uri, 3072);
        if (selectedCell >= 0 && selectedCell < 9) {
            int row = selectedCell / 3, col = selectedCell % 3;
            int x = col * source.getWidth() / 3;
            int y = row * source.getHeight() / 3;
            int w = (col + 1) * source.getWidth() / 3 - x;
            int h = (row + 1) * source.getHeight() / 3 - y;
            if (Math.min(w, h) < 200) {
                source.recycle();
                throw new IOException("Недостаточное разрешение отдельного ракурса. Нужно минимум 200×200 пикселей лица.");
            }
            Bitmap cell = Bitmap.createBitmap(source, x, y, w, h);
            if (cell != source) source.recycle();
            source = cell;
        }
        int max = Math.max(source.getWidth(), source.getHeight());
        if (max > 2048) {
            float ratio = 2048f / max;
            Bitmap scaled = Bitmap.createScaledBitmap(source,
                Math.max(1, Math.round(source.getWidth() * ratio)),
                Math.max(1, Math.round(source.getHeight() * ratio)), true);
            if (source != scaled) source.recycle();
            source = scaled;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        boolean ok = source.compress(Bitmap.CompressFormat.JPEG, 87, out);
        source.recycle();
        if (!ok) throw new IOException("Не удалось обработать фотографию");
        if (out.size() > 10 * 1024 * 1024) throw new IOException("Фото слишком большое даже после обработки");
        return out.toByteArray();
    }

    private String readAsset(String filename) throws IOException {
        try (InputStream in = getAssets().open(filename)) {
            return DIdApi.readLimited(in, 128 * 1024);
        }
    }

    private void step(String text) { runOnUiThread(() -> showStatus(text)); }
    private void showStatus(String message) { if (status != null) status.setText(message); }
    private void setBusy(boolean inProgress) {
        busy = inProgress;
        if (createButton != null) {
            createButton.setEnabled(!inProgress);
            createButton.setAlpha(inProgress ? .4f : 1f);
        }
    }
    private String friendly(Exception error) {
        String s = error.getMessage();
        return s == null ? error.getClass().getSimpleName() : DIdApi.limit(s, 440);
    }
    private void external(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (ActivityNotFoundException ex) { toast("На телефоне не найден браузер"); }
    }
    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private LinearLayout column() { LinearLayout l = new LinearLayout(this); l.setOrientation(LinearLayout.VERTICAL); return l; }
    private LinearLayout card() {
        LinearLayout c = column();
        c.setPadding(dp(16), dp(17), dp(16), dp(17));
        c.setBackground(rounded(CARD, 18));
        return c;
    }
    private TextView label(String content, int font, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(content); t.setTextColor(color); t.setTextSize(font);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        t.setLineSpacing(dp(2), 1.0f);
        return t;
    }
    private EditText input(String placeholder, boolean secret, boolean multi) {
        EditText field = new EditText(this);
        field.setTextColor(TEXT);
        field.setHintTextColor(MUTED);
        field.setTextSize(14);
        field.setHint(placeholder);
        field.setPadding(dp(12), dp(10), dp(12), dp(10));
        field.setBackground(rounded(PANEL, 12));
        if (multi) {
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            field.setSingleLine(false);
            field.setMinLines(3);
            field.setMaxLines(7);
            field.setGravity(Gravity.TOP | Gravity.START);
        } else if (secret) {
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            field.setSingleLine(true);
        } else field.setSingleLine(true);
        return field;
    }
    private Button button(String text, int background, int foreground) {
        Button b = new Button(this);
        b.setText(text); b.setAllCaps(false); b.setTextSize(14);
        b.setTextColor(foreground); b.setTypeface(null, Typeface.BOLD);
        b.setMinHeight(dp(48));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
            rounded(background, 14), null));
        return b;
    }
    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radiusDp)); return d;
    }
    private LinearLayout.LayoutParams margin(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.setMargins(dp(left), dp(top), dp(right), dp(bottom)); return lp;
    }
    private int dp(float value) { return Math.round(getResources().getDisplayMetrics().density * value); }

    @Override public void onBackPressed() {
        if (callWebView != null) showSetup(); else super.onBackPressed();
    }
    @Override protected void onPause() {
        if (callWebView != null) callWebView.onPause();
        super.onPause();
    }
    @Override protected void onResume() {
        super.onResume();
        if (callWebView != null) callWebView.onResume();
    }
    @Override protected void onDestroy() {
        destroyCall();
        jobs.shutdownNow();
        super.onDestroy();
    }
}
