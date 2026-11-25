package com.booktalk.app;

import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Renders text with the phone's own voices into audio BookTalk can process itself
 * (so the Voice Tuner's sound effects work, and books can be saved as audio files).
 * Has its own TextToSpeech instance so it never interrupts live playback.
 */
final class PhoneRenderer {
    static final class Pcm {
        final short[] s; final int rate;
        Pcm(short[] s, int rate) { this.s = s; this.rate = rate; }
    }

    private final TextToSpeech tts;
    private final File dir;
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile boolean ok = false;
    private final ConcurrentHashMap<String, CountDownLatch> waits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> errors = new ConcurrentHashMap<>();
    private int counter = 0;
    private String lastVoice = null;

    PhoneRenderer(Context ctx, String tag) {
        dir = new File(ctx.getCacheDir(), "render-" + tag);
        dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        tts = new TextToSpeech(ctx.getApplicationContext(), st -> { ok = st == TextToSpeech.SUCCESS; ready.countDown(); });
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) { }
            @Override public void onDone(String id) { finish(id, null); }
            @Override public void onError(String id) { finish(id, "voice error"); }
            @Override public void onError(String id, int code) { finish(id, "voice error " + code); }
            @Override public void onStop(String id, boolean interrupted) { finish(id, "cancelled"); }
        });
    }

    private void finish(String id, String err) {
        if (err != null) errors.put(id, err);
        CountDownLatch l = waits.get(id);
        if (l != null) l.countDown();
    }

    /** Render one passage (blocking). Throws if cancelled or the voice fails. */
    synchronized Pcm render(String text, float pitch, float rate, String voice) throws Exception {
        if (!ready.await(15, TimeUnit.SECONDS) || !ok) throw new IllegalStateException("Phone voice isn't ready");
        tts.setPitch(pitch);
        tts.setSpeechRate(rate);
        applyVoice(voice);
        String uid = "r" + (counter++);
        File f = new File(dir, uid + ".wav");
        CountDownLatch l = new CountDownLatch(1);
        waits.put(uid, l);
        try {
            int r = tts.synthesizeToFile(text, new Bundle(), f, uid);
            if (r != TextToSpeech.SUCCESS) throw new IllegalStateException("Phone voice couldn't start");
            if (!l.await(90, TimeUnit.SECONDS)) throw new IllegalStateException("Phone voice timed out");
            String err = errors.remove(uid);
            if (err != null) throw new IllegalStateException(err);
            return readWav(f);
        } finally {
            waits.remove(uid);
            f.delete();
        }
    }

    /** Abandon whatever is being rendered right now. */
    void cancel() {
        try { tts.stop(); } catch (Exception ignored) { }
        for (String id : waits.keySet()) finish(id, "cancelled");
    }

    void shutdown() { try { tts.shutdown(); } catch (Exception ignored) { } }

    private void applyVoice(String want) {
        if (want == null) want = "";
        if (want.equals(lastVoice)) return;
        boolean set = false;
        if (!want.isEmpty()) {
            try {
                Set<Voice> vs = tts.getVoices();
                if (vs != null) for (Voice v : vs) if (v.getName().equals(want)) { tts.setVoice(v); set = true; break; }
            } catch (Exception ignored) { }
        }
        if (!set) {
            try { Voice d = tts.getDefaultVoice(); if (d != null) tts.setVoice(d); else tts.setLanguage(Locale.UK); } catch (Exception ignored) { }
        }
        lastVoice = want;
    }

    /** 16-bit mono (or stereo, mixed down) WAV → samples. */
    static Pcm readWav(File f) throws IOException {
        byte[] b = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
        }
        if (b.length < 12 || b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F') throw new IOException("Not a WAV file");
        int rate = 24000, channels = 1, bits = 16, p = 12, dataOff = -1, dataLen = 0;
        while (p + 8 <= b.length) {
            String id = new String(b, p, 4, "US-ASCII");
            int len = le32(b, p + 4);
            if (id.equals("fmt ")) { channels = le16(b, p + 10); rate = le32(b, p + 12); bits = le16(b, p + 22); }
            else if (id.equals("data")) { dataOff = p + 8; dataLen = (len <= 0 || dataOff + len > b.length) ? b.length - dataOff : len; break; }
            p += 8 + Math.max(0, len) + (len & 1);
        }
        if (dataOff < 0) return new Pcm(new short[0], rate);
        if (bits != 16) throw new IOException("Unsupported WAV (" + bits + " bit)");
        int frames = dataLen / 2 / Math.max(1, channels);
        short[] s = new short[frames];
        for (int i = 0; i < frames; i++) {
            int sum = 0;
            for (int c = 0; c < channels; c++) { int q = dataOff + (i * channels + c) * 2; sum += (short) ((b[q] & 0xff) | (b[q + 1] << 8)); }
            s[i] = (short) (sum / channels);
        }
        return new Pcm(s, rate);
    }

    static short[] resample(short[] in, int from, int to) {
        if (from == to || in.length == 0) return in;
        int n = (int) ((long) in.length * to / from);
        short[] out = new short[n];
        double step = (double) from / to;
        for (int i = 0; i < n; i++) {
            double x = i * step; int a = (int) x; int c = Math.min(a + 1, in.length - 1); double fr = x - a;
            out[i] = (short) Math.round(in[a] + (in[c] - in[a]) * fr);
        }
        return out;
    }

    private static int le16(byte[] b, int p) { return (b[p] & 0xff) | ((b[p + 1] & 0xff) << 8); }
    private static int le32(byte[] b, int p) { return (b[p] & 0xff) | ((b[p + 1] & 0xff) << 8) | ((b[p + 2] & 0xff) << 16) | ((b[p + 3] & 0xff) << 24); }
}
