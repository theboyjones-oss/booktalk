package com.booktalk.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.ComponentActivity;
import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends ComponentActivity implements Speaker.Listener {

    private static final String START_URL = "https://appassets.androidplatform.net/assets/index.html";

    static volatile MainActivity instance;

    private WebView webView;
    private Speaker speaker;
    private FrameLayout root;
    private SupertonicEngine supertonic;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingMic;
    private final ExecutorService net = Executors.newFixedThreadPool(4);
    private AudioCache cache;
    private KokoroEngine kokoro;
    private ActivityResultLauncher<Intent> filePicker;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        instance = this;

        if (java.net.CookieHandler.getDefault() == null) java.net.CookieHandler.setDefault(new java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL));
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        root.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
        WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        c.setAppearanceLightStatusBars(false);
        c.setAppearanceLightNavigationBars(false);

        speaker = new Speaker(this, this);
        supertonic = new SupertonicEngine(this);
        speaker.setSupertonic(supertonic);
        cache = new AudioCache(this);
        kokoro = new KokoroEngine(this, cache, speaker, new KokoroEngine.Events() {
            @Override public void tuning(boolean on) {
                try { emit(new JSONObject().put("type", "tuning").put("on", on)); } catch (JSONException ignored) { }
            }
            @Override public void install(String pack, String stage, int pct, String msg) {
                try { emit(new JSONObject().put("type", "kokinst").put("pack", pack).put("stage", stage).put("pct", pct).put("msg", msg)); } catch (JSONException ignored) { }
            }
            @Override public void prepared(String reqId, boolean ok, String msg) {
                try { emit(new JSONObject().put("type", "kokprep").put("reqId", reqId).put("ok", ok).put("msg", msg)); } catch (JSONException ignored) { }
            }
            @Override public void busy(int pending) {
                try { emit(new JSONObject().put("type", "kokbusy").put("n", pending)); } catch (JSONException ignored) { }
            }
        });
        setupLaunchers();
        setupWebView();
        setupBack();

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        webView.loadUrl(START_URL);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setTextZoom(100);
        // Keep the page's renderer alive while we read in the background
        webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();

        webView.setWebViewClient(new WebViewClientCompat() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("appassets.androidplatform.net".equals(uri.getHost())) return false;
                tryStart(new Intent(Intent.ACTION_VIEW, uri));
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> {
                    boolean wantsMic = false;
                    for (String r : request.getResources()) if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) wantsMic = true;
                    if (!wantsMic) { request.deny(); return; }
                    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                    } else {
                        if (pendingMic != null) pendingMic.deny();
                        pendingMic = request;
                        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2);
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                try {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    filePicker.launch(i);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        webView.addJavascriptInterface(new Bridge(), "BookTalkNative");
    }

    private void setupLaunchers() {
        filePicker = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (fileCallback == null) return;
            Uri[] uris = null;
            Intent data = result.getData();
            if (result.getResultCode() == RESULT_OK && data != null && data.getData() != null) {
                uris = new Uri[]{data.getData()};
            }
            fileCallback.onReceiveValue(uris);
            fileCallback = null;
        });
    }

    private void setupBack() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                webView.evaluateJavascript("window.BT ? BT.back() : 'exit'", value -> {
                    if (value == null || value.contains("exit")) moveTaskToBack(true);
                });
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == 2 && pendingMic != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
                pendingMic.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            } else {
                pendingMic.deny();
            }
            pendingMic = null;
        }
    }

    /* ---------------- Events to the web page ---------------- */

    private void fishReply(String reqId, FishClient.Result r, Exception err, boolean keepAudio) { fishReply(reqId, null, r, err, keepAudio); }

    private void fishReply(String reqId, String cacheKey, FishClient.Result r, Exception err, boolean keepAudio) {
        try {
            JSONObject o = new JSONObject().put("type", "fish").put("reqId", reqId);
            if (err != null) {
                o.put("ok", false).put("status", 0).put("msg", "Network error: " + err.getMessage());
            } else if (!r.ok()) {
                String msg = r.text();
                try { JSONObject j = new JSONObject(msg); msg = j.optString("message", msg); } catch (Exception ignored) { }
                o.put("ok", false).put("status", r.status).put("msg", msg.length() > 300 ? msg.substring(0, 300) : msg);
            } else if (keepAudio) {
                if (r.body == null || r.body.length < 200) {
                    o.put("ok", false).put("status", 0).put("msg", "Fish Audio returned no audio");
                } else {
                    cache.put(cacheKey, r.body);
                    o.put("ok", true).put("bytes", r.body.length);
                }
            } else {
                o.put("ok", true).put("body", r.text());
            }
            emit(o);
        } catch (JSONException ignored) { }
    }

    private void emit(JSONObject o) {
        final String js = "window.BT && BT.native(" + JSONObject.quote(o.toString()) + ")";
        runOnUiThread(() -> { if (webView != null) webView.evaluateJavascript(js, null); });
    }

    private void emit(String type, String id, String msg) {
        try {
            JSONObject o = new JSONObject().put("type", type);
            if (id != null) o.put("id", id);
            if (msg != null) o.put("msg", msg);
            emit(o);
        } catch (JSONException ignored) { }
    }

    /** From the notification / headset. Returns false if the app UI is gone. */
    static boolean sendMedia(String action) {
        MainActivity a = instance;
        if (a == null || a.webView == null) return false;
        try { a.emit(new JSONObject().put("type", "media").put("action", action)); } catch (JSONException ignored) { }
        return true;
    }

    @Override public void onDone(String id) { emit("done", id, null); }
    @Override public void onError(String id, String msg) { emit("error", id, msg); }
    @Override public void onVoicesReady() { emit("voices", null, null); }
    @Override public void onFocusLost() { sendMedia("pause"); }
    @Override public void onStart(String id, long durationMs) {
        try { emit(new JSONObject().put("type", "start").put("id", id).put("dur", durationMs)); } catch (JSONException ignored) { }
    }
    @Override public void onRange(String id, int start) {
        try { emit(new JSONObject().put("type", "range").put("id", id).put("start", start)); } catch (JSONException ignored) { }
    }

    /* ---------------- Bridge called from JavaScript ---------------- */

    private class Bridge {
        @JavascriptInterface
        public void ttsSpeak(String id, String text, double pitch, double rate, String voice, int pauseMs) {
            speaker.speak(id, text, (float) pitch, (float) rate, voice, pauseMs);
        }

        @JavascriptInterface
        public void ttsQueue(String id, String text, double pitch, double rate, String voice, int pauseMs, boolean flush) {
            speaker.queue(id, text, (float) pitch, (float) rate, voice, pauseMs, flush);
        }

        @JavascriptInterface
        public void pcmPlay(String id, String b64, int sampleRate, double speed, int pauseMs) {
            speaker.playPcm(id, b64, sampleRate, (float) speed, pauseMs);
        }

        @JavascriptInterface
        public void silence(String id, int ms) { speaker.silence(id, ms); }

        @JavascriptInterface
        public void fishCreate(String reqId, String key, String title, String wavB64) {
            net.execute(() -> {
                try { fishReply(reqId, FishClient.createVoice(key, title, Base64.decode(wavB64, Base64.DEFAULT)), null, false); }
                catch (Exception e) { fishReply(reqId, null, e, false); }
            });
        }

        @JavascriptInterface
        public void fishTts(String reqId, String cacheKey, String key, String model, String json) {
            net.execute(() -> {
                try { fishReply(reqId, cacheKey, FishClient.tts(key, model, json), null, true); }
                catch (Exception e) { fishReply(reqId, cacheKey, null, e, true); }
            });
        }

        @JavascriptInterface
        public void geminiTts(String reqId, String cacheKey, String key, String model, String json) {
            net.execute(() -> {
                JSONObject o = new JSONObject();
                try {
                    o.put("type", "gem").put("reqId", reqId);
                    GeminiClient.Result r = GeminiClient.tts(key, model, json);
                    if (r.pcm != null && r.pcm.length > 200) {
                        cache.put(cacheKey, r.pcm);
                        o.put("ok", true).put("rate", r.rate).put("bytes", r.pcm.length);
                    } else {
                        o.put("ok", false).put("status", r.status).put("msg", r.error);
                    }
                } catch (Exception e) {
                    try { o.put("ok", false).put("status", 0).put("msg", "Network error: " + e.getMessage()); } catch (JSONException ignored) { }
                }
                emit(o);
            });
        }

        @JavascriptInterface
        public boolean cacheHas(String cacheKey) { return cache.has(cacheKey); }

        @JavascriptInterface
        public boolean kokoroSupported() { return KokoroEngine.supported(); }

        @JavascriptInterface
        public boolean kokoroInstalled() { return kokoro.installed(); }

        @JavascriptInterface
        public boolean kokoroInstalling() { return kokoro.isInstalling(); }

        @JavascriptInterface
        public void kokoroInstall() { kokoro.install(); }

        @JavascriptInterface
        public void kokoroUninstall() { kokoro.uninstall(); }

        @JavascriptInterface
        public void kokoroPlay(String id, String key, String text, int sid, double speed, int pauseMs) {
            kokoro.play(id, key, text, sid, (float) speed, pauseMs);
        }

        @JavascriptInterface
        public void kokoroPrepare(String reqId, String key, String text, int sid) {
            kokoro.prepare(reqId == null || reqId.isEmpty() ? null : reqId, key, text, sid);
        }

        @JavascriptInterface
        public void kokoroCancel() { kokoro.cancelAll(); }

        // ---- Offline voice cloning (Pocket TTS) ----
        @JavascriptInterface
        public boolean packInstalled(String pack) { return kokoro.installed(pack); }

        @JavascriptInterface
        public boolean packInstalling(String pack) { return kokoro.isInstalling(pack); }

        @JavascriptInterface
        public void packInstall(String pack) { kokoro.install(pack); }

        @JavascriptInterface
        public void packUninstall(String pack) { kokoro.uninstall(pack); }

        @JavascriptInterface
        public boolean voiceSave(String id, String wavB64) {
            try { kokoro.saveVoice(id, Base64.decode(wavB64, Base64.DEFAULT)); return true; } catch (Exception e) { return false; }
        }

        @JavascriptInterface
        public void voiceDelete(String id) { kokoro.deleteVoice(id); }

        @JavascriptInterface
        public boolean voiceHas(String id) { return kokoro.hasVoice(id); }

        @JavascriptInterface
        public void clonePlay(String id, String key, String text, String voice, double temp, double speed, double pitch, int pauseMs) {
            kokoro.play(id, key, text, 0, voice, (float) temp, (float) speed, (float) pitch, pauseMs);
        }

        @JavascriptInterface
        public void clonePrepare(String reqId, String key, String text, String voice, double temp) {
            kokoro.prepare(reqId == null || reqId.isEmpty() ? null : reqId, key, text, 0, voice, (float) temp);
        }

        /** Download a small file (e.g. a sample voice) and hand it back as base64. */
        @JavascriptInterface
        public void httpGet(String reqId, String url) { httpGetRef(reqId, url, ""); }

        /** Download with a Referer (and the cookies kept from earlier pages), like a browser following a link. */
        @JavascriptInterface
        public void httpGetRef(String reqId, String url, String referer) {
            net.execute(() -> {
                JSONObject o = new JSONObject();
                try {
                    o.put("type", "http").put("reqId", reqId);
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                    c.setInstanceFollowRedirects(true);
                    c.setRequestProperty("User-Agent", "BookTalk/" + appVersion() + " (Android ebook reader; +https://github.com/theboyjones-oss/booktalk)");
                    c.setRequestProperty("Accept", "*/*");
                    if (referer != null && !referer.isEmpty()) c.setRequestProperty("Referer", referer);
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(60000);
                    int code = c.getResponseCode();
                    if (code >= 400) throw new IllegalStateException("HTTP " + code);
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    try (java.io.InputStream in = c.getInputStream()) {
                        byte[] buf = new byte[65536]; int n;
                        while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); if (out.size() > 40 * 1024 * 1024) throw new IllegalStateException("File too large"); }
                    }
                    o.put("ok", true).put("b64", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP));
                } catch (Throwable e) {
                    try { o.put("ok", false).put("msg", String.valueOf(e.getMessage())); } catch (JSONException ignored) { }
                }
                emit(o);
            });
        }

        @JavascriptInterface
        public void exportChapter(String reqId, String book, String name, String itemsJson, double pitch) { exportChapterFx(reqId, book, name, itemsJson, pitch, ""); }

        @JavascriptInterface
        public void setFx(String json) { speaker.setFx(json); }

        /** Full-screen reading: hide Android's status and navigation bars (swipe from an edge to peek). */
        @JavascriptInterface
        public void setFullscreen(boolean on) {
            runOnUiThread(() -> {
                WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
                c.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                if (on) c.hide(WindowInsetsCompat.Type.systemBars()); else c.show(WindowInsetsCompat.Type.systemBars());
            });
        }

        /* ---------- AI narrators (Supertonic) ---------- */
        @JavascriptInterface public boolean stSupported() { return true; }
        @JavascriptInterface public boolean stInstalled() { return supertonic.installed(); }
        @JavascriptInterface public boolean stInstalling() { return supertonic.installing(); }
        @JavascriptInterface public float stSpeedX() { return supertonic.speedX(); }
        @JavascriptInterface public int stSteps() { return supertonic.steps(); }
        @JavascriptInterface public void stSetSteps(int n) { supertonic.setQuality(n); }
        @JavascriptInterface public void stUninstall() { new Thread(supertonic::uninstall).start(); }
        @JavascriptInterface
        public void stInstall(String filesJson) {
            try {
                supertonic.install(new org.json.JSONArray(filesJson), (stage, pct, msg) -> {
                    try { emit(new JSONObject().put("type", "stinst").put("stage", stage).put("pct", pct).put("msg", msg)); } catch (JSONException ignored) { }
                });
            } catch (JSONException e) {
                try { emit(new JSONObject().put("type", "stinst").put("stage", "error").put("pct", 0).put("msg", e.getMessage())); } catch (JSONException ignored) { }
            }
        }

        /** Is the phone itself in dark mode? (for the "Match phone" theme) */
        @JavascriptInterface
        public boolean systemDark() {
            return (getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        }

        /** Light or dark app theme: match the status/navigation bar icons and the window colour. */
        @JavascriptInterface
        public void setThemeLight(boolean light) {
            runOnUiThread(() -> {
                int bg = light ? Color.rgb(0xfa, 0xf8, 0xf4) : Color.BLACK;
                if (root != null) root.setBackgroundColor(bg);
                webView.setBackgroundColor(bg);
                getWindow().getDecorView().setBackgroundColor(bg);
                WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
                c.setAppearanceLightStatusBars(light);
                c.setAppearanceLightNavigationBars(light);
            });
        }

        /** Keep the screen awake while a book is being read aloud. */
        @JavascriptInterface
        public void keepScreenOn(boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        /** Phone voice + Voice Tuner effects: rendered, then played back to back. */
        @JavascriptInterface
        public void renderQueue(String id, String text, double pitch, double rate, String voice, int pauseMs, boolean flush, String fxJson) {
            speaker.queueRendered(id, text, (float) pitch, (float) rate, voice, pauseMs, flush, fxJson);
        }

        /** Save a chapter read by a phone voice (with optional effects) as an audio file. */
        @JavascriptInterface
        public void exportPhone(String reqId, String book, String name, String itemsJson, String fxJson) {
            new Thread(() -> {
                JSONObject o = new JSONObject();
                try {
                    o.put("type", "export").put("reqId", reqId);
                    String where = AudiobookExporter.exportPhone(MainActivity.this, speaker.exportRouter(), book, name, new org.json.JSONArray(itemsJson), fxJson, (done, total) -> {
                        try { emit(new JSONObject().put("type", "expprog").put("reqId", reqId).put("done", done).put("total", total)); } catch (JSONException ignored) { }
                    });
                    o.put("ok", true).put("where", where);
                } catch (Throwable e) {
                    try { o.put("ok", false).put("msg", String.valueOf(e.getMessage())); } catch (JSONException ignored) { }
                }
                emit(o);
            }, "export-phone").start();
        }

        @JavascriptInterface
        public void exportChapterFx(String reqId, String book, String name, String itemsJson, double pitch, String fxJson) {
            new Thread(() -> {
                JSONObject o = new JSONObject();
                try {
                    o.put("type", "export").put("reqId", reqId);
                    String where = AudiobookExporter.export(MainActivity.this, cache, book, name, new org.json.JSONArray(itemsJson), (float) pitch, fxJson);
                    o.put("ok", true).put("where", where);
                } catch (Throwable e) {
                    try { o.put("ok", false).put("msg", String.valueOf(e.getMessage())); } catch (JSONException ignored) { }
                }
                emit(o);
            }, "export").start();
        }

        @JavascriptInterface
        public int kokoroPending() { return kokoro.pending(); }

        @JavascriptInterface
        public double voiceSpeed() { return kokoro.rtf(); }

        @JavascriptInterface
        public double cacheMB() { return cache.size() / 1048576.0; }

        @JavascriptInterface
        public void cacheClear() { cache.clear(); }

        @JavascriptInterface
        public void fishDelete(String key, String voiceId) {
            net.execute(() -> { try { FishClient.deleteVoice(key, voiceId); } catch (Exception ignored) { } });
        }

        @JavascriptInterface
        public void playStored(String id, String key, int sampleRate, double speed, int pauseMs) {
            final int g = speaker.currentGen();
            net.execute(() -> {
                byte[] pcm = cache.get(key);
                if (speaker.currentGen() != g) return; // stopped while loading
                if (pcm == null) { onError(id, "audio-missing"); return; }
                speaker.playPcmBytes(id, pcm, null, sampleRate, (float) speed, pauseMs);
            });
        }

        @JavascriptInterface
        public void dropStored(String key) { /* kept on disk; trimmed automatically */ }

        @JavascriptInterface
        public void stopAll() { speaker.stopAll(); }

        @JavascriptInterface
        public String ttsVoices() { return speaker.voicesJson(); }

        @JavascriptInterface
        public String ttsEngines() { return speaker.enginesJson(); }

        @JavascriptInterface
        public void setTtsEngine(String pkg) { speaker.setEngine(pkg); }

        @JavascriptInterface
        public String ttsEngine() { return speaker.defaultEngine(); }

        @JavascriptInterface
        public void setSession(boolean active, boolean playing, String title, String sub) {
            if (!playing) speaker.abandonFocus();
            PlaybackService.update(getApplicationContext(), active, playing, title, sub);
        }

        @JavascriptInterface
        public void openVoiceSettings() {
            runOnUiThread(() -> {
                Intent install = new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA);
                install.setPackage("com.google.android.tts");
                if (tryStart(install)) return;
                if (tryStart(new Intent("com.android.settings.TTS_SETTINGS"))) return;
                if (tryStart(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))) {
                    Toast.makeText(MainActivity.this, "Open Text-to-speech output, then the gear next to Speech Services by Google", Toast.LENGTH_LONG).show();
                }
            });
        }

        @JavascriptInterface
        public String appVersion() {
            try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (Exception e) { return ""; }
        }
    }

    private boolean tryStart(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration cfg) {
        super.onConfigurationChanged(cfg);
        try { emit(new JSONObject().put("type", "uimode")); } catch (JSONException ignored) { }
    }

    @Override
    protected void onResume() {
        super.onResume();
        emit("voices", null, null); // voice packs may have been installed meanwhile
    }

    @Override
    protected void onDestroy() {
        if (instance == this) instance = null;
        PlaybackService.update(getApplicationContext(), false, false, null, null);
        speaker.shutdown();
        net.shutdownNow();
        if (webView != null) { webView.destroy(); webView = null; }
        super.onDestroy();
    }
}
