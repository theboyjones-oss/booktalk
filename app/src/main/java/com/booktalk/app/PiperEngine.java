package com.booktalk.app;

import android.content.Context;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Accent voices: Piper (VITS) models run through sherpa-onnx. Each model is a one-off download
 * (about 60–80 MB) from the sherpa-onnx "tts-models" release. They share one copy of espeak-ng-data.
 * Voice ids look like "pp:vctk:79" (model : speaker number).
 */
final class PiperEngine {
    interface Progress { void on(String model, String stage, int pct, String msg); }

    static final String BASE = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-";
    private final Context ctx;
    private final File root, espeak;
    private final Set<String> installing = Collections.synchronizedSet(new HashSet<>());
    private final Object lock = new Object();
    private OfflineTts tts; private String loaded = "";

    PiperEngine(Context c) {
        ctx = c.getApplicationContext();
        root = new File(ctx.getFilesDir(), "piper");
        espeak = new File(root, "espeak-ng-data");
    }

    private static String safe(String m) { return m.replaceAll("[^A-Za-z0-9_\\-]", ""); }
    private File dir(String model) { return new File(root, safe(model)); }
    boolean installed(String model) { return new File(dir(model), "ok").exists() && new File(espeak, "ok").exists(); }
    boolean installing(String model) { return installing.contains(model); }

    /** file: e.g. "en_GB-vctk-medium" */
    void install(String model, String file, Progress p) {
        if (!installing.add(model)) return;
        new Thread(() -> {
            File d = dir(model), tmp = new File(ctx.getFilesDir(), "piper-" + safe(model) + ".tar.bz2");
            try {
                p.on(model, "download", 0, "");
                HttpURLConnection c = (HttpURLConnection) new URL(BASE + file + ".tar.bz2").openConnection();
                c.setInstanceFollowRedirects(true); c.setConnectTimeout(20000); c.setReadTimeout(60000);
                int code = c.getResponseCode();
                if (code >= 400) throw new IllegalStateException("Download failed (HTTP " + code + ")");
                long total = c.getContentLengthLong();
                try (InputStream in = new BufferedInputStream(c.getInputStream(), 1 << 16); OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[1 << 16]; long got = 0; int n, last = -1;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n); got += n;
                        int pct = total > 0 ? (int) (got * 100 / total) : -1;
                        if (pct != last) { last = pct; p.on(model, "download", pct, String.valueOf(got / 1048576)); }
                    }
                }
                p.on(model, "extract", 0, "");
                synchronized (lock) { if (loaded.startsWith(model + "|")) release(); }
                deleteTree(d); d.mkdirs();
                boolean needEspeak = !new File(espeak, "ok").exists();
                if (needEspeak) { deleteTree(espeak); espeak.mkdirs(); }
                try (TarArchiveInputStream tar = new TarArchiveInputStream(new BZip2CompressorInputStream(new BufferedInputStream(new FileInputStream(tmp), 1 << 16)))) {
                    TarArchiveEntry e; byte[] buf = new byte[1 << 16];
                    while ((e = tar.getNextTarEntry()) != null) {
                        String name = e.getName(); int slash = name.indexOf('/');
                        String rel = slash >= 0 ? name.substring(slash + 1) : name;
                        if (rel.isEmpty()) continue;
                        File f;
                        if (rel.startsWith("espeak-ng-data/")) { if (!needEspeak) continue; f = new File(root, rel); }
                        else if (rel.endsWith(".onnx")) f = new File(d, "model.onnx");
                        else if (rel.equals("tokens.txt") || rel.endsWith(".onnx.json") || rel.equals("MODEL_CARD") || rel.equals("LICENSE")) f = new File(d, rel.endsWith(".onnx.json") ? "model.onnx.json" : rel);
                        else continue;
                        if (!f.getCanonicalPath().startsWith(root.getCanonicalPath())) continue;
                        if (e.isDirectory()) { f.mkdirs(); continue; }
                        f.getParentFile().mkdirs();
                        try (OutputStream out = new FileOutputStream(f)) { int n; while ((n = tar.read(buf)) > 0) out.write(buf, 0, n); }
                    }
                }
                if (!new File(d, "model.onnx").exists() || !new File(d, "tokens.txt").exists()) throw new IllegalStateException("The voice download was incomplete");
                if (needEspeak) { if (!new File(espeak, "phontab").exists()) throw new IllegalStateException("The pronunciation data was missing"); new FileOutputStream(new File(espeak, "ok")).close(); }
                new FileOutputStream(new File(d, "ok")).close();
                p.on(model, "done", 100, "");
            } catch (Throwable t) {
                deleteTree(d);
                p.on(model, "error", 0, String.valueOf(t.getMessage()));
            } finally { tmp.delete(); installing.remove(model); }
        }, "piper-install").start();
    }

    void uninstall(String model) {
        synchronized (lock) { if (loaded.startsWith(model + "|")) release(); }
        deleteTree(dir(model));
    }

    private void release() { if (tts != null) { try { tts.release(); } catch (Throwable ignored) { } } tts = null; loaded = ""; }

    private OfflineTts model(String model) {
        int threads = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
        String key = model + "|" + threads;
        if (tts != null && key.equals(loaded)) return tts;
        release();
        File d = dir(model);
        OfflineTtsVitsModelConfig v = new OfflineTtsVitsModelConfig();
        v.setModel(new File(d, "model.onnx").getPath());
        v.setTokens(new File(d, "tokens.txt").getPath());
        v.setDataDir(espeak.getPath());
        OfflineTtsModelConfig mc = new OfflineTtsModelConfig();
        mc.setVits(v); mc.setNumThreads(threads); mc.setProvider("cpu");
        OfflineTtsConfig cfg = new OfflineTtsConfig();
        cfg.setModel(mc); cfg.setMaxNumSentences(1);
        tts = new OfflineTts(null, cfg); loaded = key;
        return tts;
    }

    /** Speak with voice "model:sid" at the given speed. Returns 16-bit PCM at {@link #lastRate}. */
    volatile int lastRate = 22050;
    short[] speak(String voice, String text, float speed) {
        String[] p = voice.split(":");
        String model = p[0]; int sid = p.length > 1 ? Integer.parseInt(p[1]) : 0;
        if (!installed(model)) throw new IllegalStateException("This accent voice isn't downloaded");
        synchronized (lock) {
            OfflineTts t = model(model);
            GeneratedAudio a = t.generate(text, Math.max(0, Math.min(sid, Math.max(0, t.numSpeakers() - 1))), Math.max(0.4f, Math.min(2.5f, speed)));
            lastRate = a.getSampleRate();
            float[] f = a.getSamples(); short[] s = new short[f.length];
            for (int i = 0; i < f.length; i++) { float x = Math.max(-1f, Math.min(1f, f[i])); s[i] = (short) (x * 32767); }
            return s;
        }
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
