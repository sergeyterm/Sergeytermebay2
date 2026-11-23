package ru.photoavatarru.app;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Single-screen Android shell. All Runway authentication happens in native Java. */
public final class RunwayActivity extends Activity {
    private static final String ORIGIN = "https://appassets.androidplatform.net";
    private static final int FILE_PICKER = 2102, AUDIO_PERMISSION = 2103, CAMERA_PERMISSION = 2104;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private ValueCallback<Uri[]> pendingFiles;
    private PermissionRequest pendingAudio;
    private PermissionRequest pendingCamera;
    private volatile boolean destroyed = false;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff07141c);
        getWindow().setNavigationBarColor(0xff07141c);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        FrameLayout frame = new FrameLayout(this);
        if (Build.VERSION.SDK_INT >= 35) {
            frame.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets system = insets.getInsets(WindowInsets.Type.systemBars());
                view.setPadding(system.left, system.top, system.right, system.bottom);
                return insets;
            });
        } else frame.setFitsSystemWindows(true);
        setContentView(frame);

        web = new WebView(this);
        web.setBackgroundColor(0xff07141c);
        web.setOverScrollMode(WebView.OVER_SCROLL_NEVER);
        frame.addView(web, new FrameLayout.LayoutParams(-1,-1));

        WebSettings cfg = web.getSettings();
        cfg.setJavaScriptEnabled(true);
        cfg.setDomStorageEnabled(true);
        cfg.setMediaPlaybackRequiresUserGesture(false);
        cfg.setJavaScriptCanOpenWindowsAutomatically(false);
        cfg.setAllowFileAccess(false);
        cfg.setAllowContentAccess(true); // User-selected content:// image and audio URIs from Android file picker
        cfg.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        WebView.setWebContentsDebuggingEnabled(false);

        web.addJavascriptInterface(new NativeApi(), "PhotoAvatarNative");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri uri = request.getUrl();
                return !("https".equals(uri.getScheme()) && "appassets.androidplatform.net".equals(uri.getHost())
                    && uri.getPath() != null && uri.getPath().startsWith("/assets/"));
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!"https".equals(uri.getScheme()) || !"appassets.androidplatform.net".equals(uri.getHost()))
                    return null;
                String path = uri.getPath();
                if (path == null || !path.startsWith("/assets/")) return emptyResponse();
                String name = path.substring("/assets/".length());
                if (!name.matches("[a-z0-9_.-]+") || name.contains("..") || name.equals("runway.key"))
                    return emptyResponse();
                try {
                    InputStream stream = getAssets().open(name);
                    String mime = name.endsWith(".js") ? "application/javascript"
                        : name.endsWith(".css") ? "text/css" : name.endsWith(".html") ? "text/html" : "text/plain";
                    return new WebResourceResponse(mime,"UTF-8",stream);
                } catch (IOException e) { return emptyResponse(); }
            }
            private WebResourceResponse emptyResponse() {
                return new WebResourceResponse("text/plain","UTF-8",new java.io.ByteArrayInputStream(new byte[0]));
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (pendingFiles != null) pendingFiles.onReceiveValue(null);
                pendingFiles = callback;
                try {
                    Intent intent = params.createIntent();
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    startActivityForResult(intent,FILE_PICKER);
                    return true;
                } catch (ActivityNotFoundException e) {
                    pendingFiles = null;
                    callback.onReceiveValue(null);
                    Toast.makeText(RunwayActivity.this, "Выбор файла недоступен", Toast.LENGTH_SHORT).show();
                    return true;
                }
            }
            @Override public void onPermissionRequest(PermissionRequest request) {
                ui.post(() -> {
                    Uri origin = request.getOrigin();
                    if (origin == null || !"https".equals(origin.getScheme())
                        || !"appassets.androidplatform.net".equals(origin.getHost())) {
                        request.deny(); return;
                    }
                    String[] resources = request.getResources();
                    if (resources.length != 1) { request.deny(); return; }
                    final boolean audio = PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resources[0]);
                    final boolean camera = PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resources[0]);
                    if (!audio && !camera) { request.deny(); return; }
                    final String androidPermission = audio ? Manifest.permission.RECORD_AUDIO : Manifest.permission.CAMERA;
                    final int requestCode = audio ? AUDIO_PERMISSION : CAMERA_PERMISSION;
                    if (checkSelfPermission(androidPermission) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{resources[0]});
                    } else {
                        if (audio) pendingAudio = request; else pendingCamera = request;
                        requestPermissions(new String[]{androidPermission}, requestCode);
                    }
                });
            }
            @Override public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingAudio == request) pendingAudio = null;
                if (pendingCamera == request) pendingCamera = null;
            }
        });
        web.loadUrl(ORIGIN + "/assets/index.html");
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data);
        if (requestCode != FILE_PICKER) return;
        if (pendingFiles != null) {
            pendingFiles.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode,data));
            pendingFiles = null;
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults) {
        super.onRequestPermissionsResult(requestCode,permissions,grantResults);
        if (requestCode != AUDIO_PERMISSION && requestCode != CAMERA_PERMISSION) return;
        PermissionRequest request = requestCode == AUDIO_PERMISSION ? pendingAudio : pendingCamera;
        if (requestCode == AUDIO_PERMISSION) pendingAudio = null; else pendingCamera = null;
        if (request == null) return;
        if (grantResults.length == 1 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
            request.grant(new String[]{requestCode == AUDIO_PERMISSION ?
                PermissionRequest.RESOURCE_AUDIO_CAPTURE : PermissionRequest.RESOURCE_VIDEO_CAPTURE});
        else request.deny();
    }

    private void deliver(String requestId, JSONObject payload) {
        if (destroyed) return;
        ui.post(() -> {
            if (destroyed || web == null) return;
            String script = "window.__nativeReply(" + JSONObject.quote(requestId) + "," + payload.toString() + ")";
            web.evaluateJavascript(script,null);
        });
    }

    private static JSONObject success(Object data) throws Exception {
        JSONObject reply = new JSONObject();
        reply.put("ok",true);
        reply.put("data",data);
        return reply;
    }
    private static JSONObject failure(Exception e) {
        JSONObject reply = new JSONObject();
        try {
            String message = e.getMessage();
            if (message == null || message.isEmpty()) message = "Неожиданная ошибка";
            reply.put("ok",false).put("error",message.length()>320?message.substring(0,320):message);
        } catch (Exception ignored){}
        return reply;
    }

    public final class NativeApi {
        @JavascriptInterface public void request(String id,String action,String raw) {
            if (id == null || id.length()>50 || action == null || action.length()>40 || raw == null || raw.length()>6_000_000)
                return;
            worker.execute(() -> {
                try {
                    JSONObject p = new JSONObject(raw);
                    Object result;
                    switch (action) {
                        case "state":
                            result = new JSONObject().put("embeddedKey",RunwayApi.embeddedKeyPresent(RunwayActivity.this))
                                .put("manualKey",getSharedPreferences("runway_ru",MODE_PRIVATE).contains("manual_key"));
                            break;
                        case "setKey":
                            RunwayApi.saveManualKey(RunwayActivity.this,p.optString("key",""));
                            result = new JSONObject().put("saved",true);
                            break;
                        case "check":
                            result = new RunwayApi(RunwayActivity.this).checkKey();
                            break;
                        case "createVoice":
                            result = new JSONObject().put("id",new RunwayApi(RunwayActivity.this).createVoice(
                                p.optString("name","Голос"),p.optString("audio","")));
                            break;
                        case "voiceReady":
                            new RunwayApi(RunwayActivity.this).waitForVoice(p.getString("voiceId"));
                            result = new JSONObject().put("ready",true);
                            break;
                        case "createAvatar":
                            result = new JSONObject().put("id",new RunwayApi(RunwayActivity.this).createAvatar(
                                p.getString("photo"),p.optString("name",""),p.getString("personality"),
                                p.optString("voiceType","preset"),p.optString("voiceId","clara")));
                            break;
                        case "avatarStatus":
                            result = new RunwayApi(RunwayActivity.this).getAvatarStatus(
                                p.getString("avatarId"));
                            break;
                        case "startSession":
                            result = new RunwayApi(RunwayActivity.this).startSession(
                                p.getString("avatarId"),p.optString("personality",""),
                                p.optString("startScript",""));
                            break;
                        case "cancelSession":
                            new RunwayApi(RunwayActivity.this).cancelSession(p.getString("sessionId"));
                            result = new JSONObject().put("cancelled",true);
                            break;
                        default: throw new IOException("Неизвестная операция");
                    }
                    deliver(id,success(result));
                } catch (Exception e) { deliver(id,failure(e)); }
            });
        }
    }

    @Override public void onBackPressed() {
        if (web != null) {
            web.evaluateJavascript("!!(window.__onAndroidBack && window.__onAndroidBack())",
                result -> { if (!"true".equals(result)) finish(); });
            return;
        }
        super.onBackPressed();
    }

    @Override protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }
    @Override protected void onPause() {
        if (web != null) web.onPause();
        super.onPause();
    }
    @Override protected void onDestroy() {
        destroyed = true;
        worker.shutdownNow();
        if (pendingFiles != null) {pendingFiles.onReceiveValue(null);pendingFiles=null;}
        if (pendingAudio != null) {pendingAudio.deny();pendingAudio=null;}
        if (pendingCamera != null) {pendingCamera.deny();pendingCamera=null;}
        if (web != null) {
            web.loadUrl("about:blank"); web.destroy();web=null;
        }
        super.onDestroy();
    }
}
