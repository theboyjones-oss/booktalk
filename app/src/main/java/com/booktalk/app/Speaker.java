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
            lastVoice = null;
            ttsReady = true;
            listener.onVoicesReady();
        }, engine);
    }

    public void speak(String id, String text, float pitch, float rate, String voiceName, int pauseMs) {
        lastVoice = null;
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

    /** BibleTalk-style playback: queue passages back to back in the phone's own TTS engine so there are no gaps.
     *  flush=true starts a new queue (stops anything playing); flush=false appends. Empty text = a pause. */
    public void queue(String id, String text, float pitch, float rate, String voiceName, int pauseMs, boolean flush) {
        if (flush) stopPcm();
        if (!ttsReady) { listener.onError(id, "Phone voice is still starting"); return; }
        if (flush) requestFocus();
        int mode = flush ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD;
        if (text == null || text.trim().isEmpty()) { tts.playSilentUtterance(Math.max(1, pauseMs), mode, id); return; }
        tts.setPitch(pitch);
        tts.setSpeechRate(rate);
        applyVoice(voiceName);
        Bundle params = new Bundle();
        if (pauseMs > 0) {
            tts.speak(text, mode, params, id + TAIL);
            tts.playSilentUtterance(pauseMs, TextToSpeech.QUEUE_ADD, id);
        } else {
            tts.speak(text, mode, params, id);
        }
    }

    private String lastVoice = null;
    private void applyVoice(String voiceName) {
        String want = voiceName == null ? "" : voiceName;
        if (want.equals(lastVoice)) return;
        boolean set = false;
        if (!want.isEmpty()) {
            try {
                Set<Voice> vs = tts.getVoices();
                if (vs != null) for (Voice v : vs) {
                    if (v.getName().equals(want)) { tts.setVoice(v); set = true; break; }
                }
            } catch (Exception ignored) { }
        }
        if (!set) {
            try {
                Voice def = tts.getDefaultVoice();
                if (def != null) tts.setVoice(def); else tts.setLanguage(Locale.UK);
            } catch (Exception ignored) { }
        }
        lastVoice = want;
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
    public void playPcmBytes(String id, byte[] raw, String b64, int rate, float speed, int pauseMs) { playPcmBytes(id, raw, b64, rate, speed, 1f, pauseMs); }

    public void playPcmBytes(String id, byte[] raw, String b64, int rate, float speed, float pitch, int pauseMs) {
        stopAll();
        final int myGen = gen;
        requestFocus();
        new Thread(() -> {
            AudioTrack t = null;
            try {
                byte[] pcm = raw != null ? raw : Base64.decode(b64, Base64.DEFAULT);
                if (myGen != gen) return;
                Fx fx = Fx.make(fxJson, rate);
                if (fx != null) {
                    short[] sh = new short[pcm.length / 2];
                    java.nio.ByteBuffer.wrap(pcm).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(sh);
                    sh = fx.process(sh, 0, sh.length);
                    byte[] b2 = new byte[sh.length * 2];
                    java.nio.ByteBuffer.wrap(b2).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(sh);
                    pcm = b2;
                }
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
                    t.setPlaybackParams(new PlaybackParams().setSpeed(speed).setPitch(pitch));
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

    /** A piece of generated speech for streaming playback. */
    /** Voice Tuner sound effects for the AI narrators (JSON from the app; "" = none). */
    volatile String fxJson = "";
    public void setFx(String json) { fxJson = json == null ? "" : json; }

    public static class Chunk {
        public final short[] pcm; public final int offset; public final boolean end;
        public Chunk(short[] pcm, int offset, boolean end) { this.pcm = pcm; this.offset = offset; this.end = end; }
    }

    /**
     * Play speech that is still being generated (Kokoro): chunks arrive on the queue one sentence
     * at a time; an end chunk finishes the item. Reports each sentence's text offset as it starts.
     */
    public void playStream(String id, java.util.concurrent.BlockingQueue<Chunk> q, int rate, float speed, int pauseMs) { playStream(id, q, rate, speed, 1f, pauseMs); }

    public void playStream(String id, java.util.concurrent.BlockingQueue<Chunk> q, int rate, float speed, float pitch, int pauseMs) { playStream(id, q, rate, speed, pitch, pauseMs, 0); }

    /**
     * preRollMs: how much speech to collect before starting, so a phone that generates more slowly
     * than real time pauses once at the start instead of between sentences.
     */
    public void playStream(String id, java.util.concurrent.BlockingQueue<Chunk> q, int rate, float speed, float pitch, int pauseMs, long preRollMs) {
        stopAll();
        final int myGen = gen;
        requestFocus();
        new Thread(() -> {
            AudioTrack t = null;
            try {
                int minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                int bufBytes = Math.max(minBuf * 4, 64 * 1024);
                t = new AudioTrack.Builder()
                        .setAudioAttributes(attrs)
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(rate)
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(bufBytes)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                try { t.setPlaybackParams(new PlaybackParams().setSpeed(speed).setPitch(pitch)); } catch (Exception ignored) { }
                current = t;
                t.play();
                final Fx fx = Fx.make(fxJson, rate);
                long frames = 0;
                boolean started = false, ended = false;
                // Pre-roll: hold back until enough speech is ready (or the passage is complete)
                java.util.ArrayList<Chunk> held = new java.util.ArrayList<>();
                long heldFrames = 0, needFrames = preRollMs * rate / 1000;
                while (myGen == gen && heldFrames < needFrames) {
                    Chunk c = q.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (c == null) continue;
                    if (c.end) { ended = true; break; }
                    held.add(c); heldFrames += c.pcm.length;
                }
                while (myGen == gen) {
                    Chunk c;
                    if (!held.isEmpty()) c = held.remove(0);
                    else if (ended) break;
                    else {
                        c = q.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                        if (c == null) continue;
                        if (c.end) break;
                    }
                    if (!started) { listener.onStart(id, -1); started = true; }
                    listener.onRange(id, c.offset);
                    int off = 0;
                    short[] data = fx != null ? fx.process(c.pcm, 0, c.pcm.length) : c.pcm;
                    while (off < data.length && myGen == gen) {
                        int n = t.write(data, off, Math.min(8192, data.length - off));
                        if (n < 0) throw new IllegalStateException("AudioTrack write " + n);
                        off += n;
                    }
                    frames += c.pcm.length;
                }
                if (myGen != gen) return;
                long pauseFrames = (long) rate * pauseMs / 1000;
                long total = frames + pauseFrames;
                short[] zeros = new short[4096];
                long padFrames = pauseFrames + bufBytes / 2, written = 0;
                long deadline = System.currentTimeMillis() + (long) (total * 1000.0 / rate / Math.max(speed, 0.25f)) + 2500;
                while (myGen == gen) {
                    long head = t.getPlaybackHeadPosition() & 0xffffffffL;
                    if (head >= total || System.currentTimeMillis() > deadline) break;
                    if (written < padFrames) {
                        short[] z = fx != null ? fx.process(zeros, 0, zeros.length) : zeros;   // lets echoes and rooms ring out
                        int n = t.write(z, 0, (int) Math.min(z.length, padFrames - written), AudioTrack.WRITE_NON_BLOCKING);
                        if (n < 0) break;
                        written += n;
                        if (n == 0) Thread.sleep(10);
                    } else Thread.sleep(10);
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
        }, "stream").start();
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
        if (renderer != null) renderer.cancel();
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

    /* ---------- Phone voices with Voice Tuner effects ----------
     * Each passage is rendered to audio a little ahead (by a separate voice-engine instance),
     * then played back to back through one AudioTrack with the effects applied. */
    private PhoneRenderer renderer;
    private PhoneRenderer renderer() { if (renderer == null) renderer = new PhoneRenderer(ctx, "play"); return renderer; }

    /* AI narrators (Supertonic): voices named "st:M1" or "st:M1=0.6,F2=0.4" */
    private SupertonicEngine st;
    void setSupertonic(SupertonicEngine e) { st = e; }
    PhoneRenderer.Pcm renderAny(PhoneRenderer phone, String text, float pitch, float rate, String voice) throws Exception {
        if (voice != null && voice.startsWith("st:")) {
            if (st == null || !st.installed()) throw new IllegalStateException("The AI voices aren't downloaded");
            // pitch without changing speed: speak at rate/pitch, then resample by pitch (raises/lowers pitch, restores speed)
            float p = Math.max(0.5f, Math.min(2f, pitch));
            short[] a = st.speak(text, voice.substring(3), Math.max(0.4f, Math.min(2.5f, rate / p)));
            int sr = st.sampleRate();
            return new PhoneRenderer.Pcm(Math.abs(p - 1f) < 0.005f ? a : PhoneRenderer.resample(a, Math.round(sr * p), sr), sr);
        }
        return phone.render(text, pitch, rate, voice);
    }
    PhoneRenderer.Renderer exportRouter() { PhoneRenderer ph = exportRenderer(); return (t, p, r, v) -> renderAny(ph, t, p, r, v); }
    private static final class RItem { String id, text, voice; float pitch, rate; int pauseMs; PhoneRenderer.Pcm pcm; String err; }
    private static final class Marker { final long frame; final String id; final char kind; final String msg; Marker(long f, String i, char k, String m) { frame = f; id = i; kind = k; msg = m; } }
    private static final class RSession {
        int gen; String fxJson;
        final java.util.concurrent.LinkedBlockingQueue<RItem> todo = new java.util.concurrent.LinkedBlockingQueue<>();
        final java.util.concurrent.LinkedBlockingQueue<RItem> ready = new java.util.concurrent.LinkedBlockingQueue<>();
    }
    private volatile RSession rs;

    public void queueRendered(String id, String text, float pitch, float rate, String voiceName, int pauseMs, boolean flush, String fxJson) {
        RItem it = new RItem();
        it.id = id; it.text = text; it.voice = voiceName; it.pitch = pitch; it.rate = rate; it.pauseMs = pauseMs;
        RSession s = rs;
        if (flush || s == null || s.gen != gen) {
            stopAll();
            requestFocus();
            s = new RSession(); s.gen = gen; s.fxJson = fxJson == null ? "" : fxJson;
            rs = s;
            startRenderThread(s);
            startPlayThread(s);
        }
        s.todo.add(it);
    }

    private void startRenderThread(RSession s) {
        Thread th = new Thread(() -> {
            try {
                while (s.gen == gen) {
                    RItem it = s.todo.poll(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (it == null) continue;
                    if (it.text != null && !it.text.trim().isEmpty()) {
                        try { it.pcm = renderAny(renderer(), it.text, it.pitch, it.rate, it.voice); }
                        catch (Exception e) { it.err = String.valueOf(e.getMessage()); }
                    }
                    if (s.gen != gen) break;
                    s.ready.add(it);
                }
            } catch (InterruptedException ignored) { }
        }, "render");
        th.start();
    }

    private void startPlayThread(RSession s) {
        Thread th = new Thread(() -> {
            AudioTrack t = null;
            java.util.concurrent.ConcurrentLinkedQueue<Marker> marks = new java.util.concurrent.ConcurrentLinkedQueue<>();
            try {
                int trackRate = 0, bufFrames = 0;
                Fx fx = null;
                long written = 0, padded = 0;
                while (s.gen == gen) {
                    RItem it = s.ready.poll(80, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (it == null) {
                        // nothing new yet: push the last audio all the way out so its "done" fires
                        if (t != null && padded < bufFrames) {
                            short[] z = new short[trackRate / 25];
                            if (fx != null) z = fx.process(z, 0, z.length);
                            int n = t.write(z, 0, z.length);
                            if (n > 0) { written += n; padded += n; }
                        }
                        continue;
                    }
                    if (t == null) {
                        trackRate = it.pcm != null ? it.pcm.rate : 24000;
                        int minBuf = AudioTrack.getMinBufferSize(trackRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
                        int bufBytes = Math.max(minBuf * 2, trackRate / 5 * 2);
                        bufFrames = bufBytes / 2;
                        t = new AudioTrack.Builder().setAudioAttributes(attrs)
                                .setAudioFormat(new AudioFormat.Builder().setSampleRate(trackRate)
                                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                                .setBufferSizeInBytes(bufBytes).setTransferMode(AudioTrack.MODE_STREAM).build();
                        current = t;
                        t.play();
                        fx = Fx.make(s.fxJson, trackRate);
                        startMonitor(s, t, marks);
                    }
                    if (it.err != null && !"cancelled".equals(it.err)) { marks.add(new Marker(written, it.id, 'E', it.err)); continue; }
                    if (it.err != null) continue;
                    marks.add(new Marker(written, it.id, 'S', null));
                    short[] data = it.pcm != null ? PhoneRenderer.resample(it.pcm.s, it.pcm.rate, trackRate) : new short[0];
                    short[] pause = new short[(int) ((long) trackRate * Math.max(0, it.pauseMs) / 1000)];
                    for (short[] part : new short[][]{data, pause}) {
                        if (part.length == 0) continue;
                        short[] out = fx != null ? fx.process(part, 0, part.length) : part;
                        int off = 0;
                        while (off < out.length && s.gen == gen) {
                            int n = t.write(out, off, Math.min(4096, out.length - off));
                            if (n < 0) throw new IllegalStateException("AudioTrack write " + n);
                            off += n;
                        }
                        written += out.length;
                    }
                    padded = 0;
                    marks.add(new Marker(written, it.id, 'D', null));
                }
            } catch (Throwable e) {
                if (s.gen == gen) { Marker m = marks.peek(); listener.onError(m != null ? m.id : "render", String.valueOf(e.getMessage())); }
            } finally {
                if (t != null) {
                    if (current == t) current = null;
                    try { t.pause(); t.flush(); } catch (Exception ignored) { }
                    try { t.release(); } catch (Exception ignored) { }
                }
            }
        }, "render-play");
        th.start();
    }

    // Fires start/done events when the speaker actually reaches each passage
    private void startMonitor(RSession s, AudioTrack t, java.util.concurrent.ConcurrentLinkedQueue<Marker> marks) {
        Thread th = new Thread(() -> {
            try {
                while (s.gen == gen) {
                    long head;
                    try { head = t.getPlaybackHeadPosition() & 0xffffffffL; } catch (Exception e) { break; }
                    Marker m;
                    while ((m = marks.peek()) != null && m.frame <= head && s.gen == gen) {
                        marks.poll();
                        if (m.kind == 'S') listener.onStart(m.id, -1);
                        else if (m.kind == 'D') listener.onDone(m.id);
                        else listener.onError(m.id, m.msg);
                    }
                    Thread.sleep(40);
                }
            } catch (InterruptedException ignored) { }
        }, "render-mon");
        th.start();
    }

    /** For saving audiobooks with phone voices: a renderer that never touches playback. */
    private PhoneRenderer exportRenderer;
    public synchronized PhoneRenderer exportRenderer() { if (exportRenderer == null) exportRenderer = new PhoneRenderer(ctx, "export"); return exportRenderer; }
}
