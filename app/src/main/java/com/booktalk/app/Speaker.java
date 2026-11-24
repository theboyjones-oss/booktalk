package com.booktalk.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.PlaybackParams;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Set;

/**
 * Plays speech two ways:
 *  - Android's built-in TextToSpeech (offline voices)
 *  - raw 16-bit PCM returned by Gemini TTS, through an AudioTrack
 * Every finished item reports back with its id so the web UI can move on.
 * Pauses (after headings, scene breaks) are done here, not with JS timers,
 * because the WebView throttles timers while the screen is off.
 */
public class Speaker {

    public interface Listener {
        void onDone(String id);
        void onError(String id, String msg);
        void onVoicesReady();
        void onFocusLost();
        void onStart(String id, long durationMs);
        void onRange(String id, int start);
    }

    private static final String TAIL = "#t";

    private final Context ctx;
    private final Listener listener;
    private final AudioManager audio;
    private final AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();
    private AudioFocusRequest focusReq;

    private TextToSpeech tts;
    private volatile boolean ttsReady = false;

    private volatile int gen = 0;            // bumped on every stop/new item
    private volatile AudioTrack current;     // track currently playing (if any)

    public Speaker(Context ctx, Listener listener) {
        this.ctx = ctx.getApplicationContext();
        this.listener = listener;
        this.audio = (AudioManager) this.ctx.getSystemService(Context.AUDIO_SERVICE);
        initTts(null);
    }

    /** Switch the text-to-speech app (engine package), or null for the phone default. */
    public void setEngine(String pkg) {
        String want = pkg == null || pkg.isEmpty() ? null : pkg;
        if (tts != null) {
            try { tts.stop(); tts.shutdown(); } catch (Exception ignored) { }
        }
        ttsReady = false;
        initTts(want);
    }

    public String enginesJson() {
        JSONArray arr = new JSONArray();
        try {
            if (tts != null) for (TextToSpeech.EngineInfo e : tts.getEngines()) {
                JSONObject o = new JSONObject();
                o.put("name", e.name);
                o.put("label", e.label);
                arr.put(o);
            }
        } catch (Exception ignored) { }
        return arr.toString();
    }

    /* ---------------- Built-in TTS ---------------- */

    private void initTts(String engine) {
        tts = new TextToSpeech(ctx, status -> {
            if (status != TextToSpeech.SUCCESS) return;
            try { tts.setLanguage(Locale.UK); } catch (Exception ignored) { }
            tts.setAudioAttributes(attrs);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override public void onStart(String id) {
                    if (id != null && id.endsWith(TAIL)) listener.onStart(id.substring(0, id.length() - TAIL.length()), -1);
                    else if (id != null && !id.startsWith("sil")) listener.onStart(id, -1);
                }
                @Override public void onRangeStart(String id, int start, int end, int frame) {
                    if (id == null) return;
                    listener.onRange(id.endsWith(TAIL) ? id.substring(0, id.length() - TAIL.length()) : id, start);
                }
                @Override public void onDone(String id) {
                    if (id == null || id.endsWith(TAIL)) return;
                    listener.onDone(id);
                }
                @Override public void onError(String id) { onError(id, -1); }
                @Override public void onError(String id, int code) {
                    if (id == null) return;
                    listener.onError(id.endsWith(TAIL) ? id.substring(0, id.length() - TAIL.length()) : id, "tts-error-" + code);
                }
                @Override public void onStop(String id, boolean interrupted) { }
            });
            ttsReady = true;
            listener.onVoicesReady();
        }, engine);
    }

    public void speak(String id, String text, float pitch, float rate, String voiceName, int pauseMs) {
        stopPcm();
        if (!ttsReady) { listener.onError(id, "Built-in voice is still starting"); return; }
        requestFocus();
        tts.setPitch(pitch);
        tts.setSpeechRate(rate);
        boolean set = false;
        if (voiceName != null && !voiceName.isEmpty()) {
            try {
                Set<Voice> vs = tts.getVoices();
                if (vs != null) for (Voice v : vs) {
                    if (v.getName().equals(voiceName)) { tts.setVoice(v); set = true; break; }
                }
            } catch (Exception ignored) { }
        }
        if (!set) {
            try {
                Voice def = tts.getDefaultVoice();
                if (def != null) tts.setVoice(def); else tts.setLanguage(Locale.UK);
            } catch (Exception ignored) { }
        }
        Bundle params = new Bundle();
        if (pauseMs > 0) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id + TAIL);
            tts.playSilentUtterance(pauseMs, TextToSpeech.QUEUE_ADD, id);
        } else {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, id);
        }
    }

    public String voicesJson() {
        JSONArray arr = new JSONArray();
        if (!ttsReady) return arr.toString();
        try {
            Set<Voice> vs = tts.getVoices();
            if (vs != null) for (Voice v : vs) {
                if (v.getFeatures() != null && v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                JSONObject o = new JSONObject();
                o.put("name", v.getName());
                o.put("lang", v.getLocale().toLanguageTag());
                o.put("network", v.isNetworkConnectionRequired());
                o.put("quality", v.getQuality());
                arr.put(o);
            }
        } catch (Exception ignored) { }
        return arr.toString();
    }

    public String defaultEngine() {
        try { return tts != null ? tts.getDefaultEngine() : ""; } catch (Exception e) { return ""; }
    }

    /* ---------------- Gemini PCM ---------------- */

    public void playPcm(String id, String b64, int rate, float speed, int pauseMs) {
        playPcmBytes(id, null, b64, rate, speed, pauseMs);
    }

    /** Either raw bytes or base64 may be given. */
    public void playPcmBytes(String id, byte[] raw, String b64, int rate, float speed, int pauseMs) {
        stopAll();
        final int myGen = gen;
        requestFocus();
        new Thread(() -> {
            AudioTrack t = null;
            try {
                byte[] pcm = raw != null ? raw : Base64.decode(b64, Base64.DEFAULT);
                if (myGen != gen) return;
                int minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                t = new AudioTrack.Builder()
                        .setAudioAttributes(attrs)
                        .setAudioFormat(new AudioFormat.Builder()
                                .setSampleRate(rate)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build())
                        .setBufferSizeInBytes(Math.max(minBuf * 4, 64 * 1024))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                try {
                    t.setPlaybackParams(new PlaybackParams().setSpeed(speed).setPitch(1f));
                } catch (Exception ignored) { }
                current = t;
                int bufBytes = Math.max(minBuf * 4, 64 * 1024);
                long frames = pcm.length / 2;
                long pauseFrames = (long) rate * pauseMs / 1000;
                long total = frames + pauseFrames;
                t.play();
                listener.onStart(id, (long) (frames * 1000.0 / rate / Math.max(speed, 0.25f)));
                int off = 0;
                while (off < pcm.length && myGen == gen) {
                    int n = t.write(pcm, off, Math.min(8192, pcm.length - off));
                    if (n < 0) throw new IllegalStateException("AudioTrack write " + n);
                    off += n;
                }
                // Trailing silence: the requested pause plus a full buffer of padding so the real
                // audio is pushed all the way through (no stop() call, so the position keeps counting).
                byte[] zeros = new byte[8192];
                long padBytes = pauseFrames * 2 + bufBytes;
                long written = 0;
                long deadline = System.currentTimeMillis() + (long) (total * 1000.0 / rate / Math.max(speed, 0.25f)) + 2500;
                while (myGen == gen) {
                    long head = t.getPlaybackHeadPosition() & 0xffffffffL;
                    if (head >= total) break;
                    if (System.currentTimeMillis() > deadline) break;
                    if (written < padBytes) {
                        int n = t.write(zeros, 0, (int) Math.min(zeros.length, padBytes - written), AudioTrack.WRITE_NON_BLOCKING);
                        if (n < 0) break;
                        written += n;
                        if (n == 0) Thread.sleep(10);
                    } else {
                        Thread.sleep(10);
                    }
                }
                if (myGen == gen) listener.onDone(id);
            } catch (Throwable e) {
                if (myGen == gen) listener.onError(id, String.valueOf(e.getMessage()));
            } finally {
                if (t != null) {
                    if (current == t) current = null;
                    try { t.release(); } catch (Exception ignored) { }
                }
            }
        }, "pcm").start();
    }

    /** Report done after ms of silence (scene breaks). */
    public void silence(String id, int ms) {
        stopAll();
        final int myGen = gen;
        new Thread(() -> {
            try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
            if (myGen == gen) listener.onDone(id);
        }, "silence").start();
    }

    private void stopPcm() {
        gen++;
        AudioTrack t = current;
        if (t != null) {
            try { t.pause(); t.flush(); } catch (Exception ignored) { }
        }
    }

    public int currentGen() { return gen; }

    public void stopAll() {
        stopPcm();
        if (tts != null) {
            try { tts.stop(); } catch (Exception ignored) { }
        }
    }

    /* ---------------- Audio focus ---------------- */

    private void requestFocus() {
        if (focusReq == null) {
            focusReq = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(attrs)
                    .setOnAudioFocusChangeListener(change -> {
                        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            listener.onFocusLost();
                        }
                    })
                    .build();
        }
        try { audio.requestAudioFocus(focusReq); } catch (Exception ignored) { }
    }

    public void abandonFocus() {
        if (focusReq != null) try { audio.abandonAudioFocusRequest(focusReq); } catch (Exception ignored) { }
    }

    public void shutdown() {
        stopAll();
        abandonFocus();
        if (tts != null) { try { tts.shutdown(); } catch (Exception ignored) { } }
    }
}
