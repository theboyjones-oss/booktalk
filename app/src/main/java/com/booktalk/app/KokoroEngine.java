package com.booktalk.app;

import android.content.Context;
import android.os.Build;

import com.k2fsa.sherpa.onnx.GeneratedAudio;
import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Kokoro-82M (via sherpa-onnx) running on the phone. One background worker generates speech
 * sentence by sentence; the passage being listened to is streamed straight to the speaker,
 * upcoming passages are prepared ahead and saved in the audio cache.
 */
public class KokoroEngine {

    public interface Events {
        void install(String stage, int pct, String msg);
        void prepared(String reqId, boolean ok, String msg);
        void busy(int pending);
    }

    static final String URL_INT8 = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2";
    static final int RATE = 24000;

    private final Context ctx;
    private final File dir;
    private final AudioCache cache;
    private final Speaker speaker;
    private final Events events;

    private OfflineTts tts;
    private String loadedLexicon = "";
    private volatile boolean installing = false;

    private static class Job {
        String key, text, reqId; int sid;
        volatile boolean aborted;
        final List<Speaker.Chunk> done = new ArrayList<>();
        BlockingQueue<Speaker.Chunk> sink;       // set when someone is listening live
        boolean finished;
    }

    private final LinkedBlockingDeque<Job> queue = new LinkedBlockingDeque<>();
    private volatile Job running;
    private final Object lock = new Object();

    public KokoroEngine(Context ctx, AudioCache cache, Speaker speaker, Events events) {
        this.ctx = ctx.getApplicationContext();
        this.dir = new File(this.ctx.getFilesDir(), "kokoro");
        this.cache = cache;
        this.speaker = speaker;
        this.events = events;
        Thread w = new Thread(this::workLoop, "kokoro");
        w.setPriority(Thread.NORM_PRIORITY - 1);
        w.setDaemon(true);
        w.start();
    }

    public static boolean supported() {
        for (String abi : Build.SUPPORTED_ABIS) if ("arm64-v8a".equals(abi)) return true;
        return false;
    }

    public boolean installed() { return new File(dir, "ok").exists(); }
    public boolean isInstalling() { return installing; }

    /* ---------------- One-time download ---------------- */

    public void install() {
        if (installing) return;
        installing = true;
        new Thread(() -> {
            File tmp = new File(ctx.getFilesDir(), "kokoro.tar.bz2");
            try {
                events.install("download", 0, "");
                HttpURLConnection c = (HttpURLConnection) new URL(URL_INT8).openConnection();
                c.setInstanceFollowRedirects(true);
                c.setConnectTimeout(20000);
                c.setReadTimeout(60000);
                int code = c.getResponseCode();
                if (code >= 400) throw new IllegalStateException("Download failed (HTTP " + code + ")");
                long total = c.getContentLengthLong();
                try (InputStream in = new BufferedInputStream(c.getInputStream(), 1 << 16);
                     OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[1 << 16];
                    long got = 0; int n, lastPct = -1;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        got += n;
                        int pct = total > 0 ? (int) (got * 100 / total) : -1;
                        if (pct != lastPct) { lastPct = pct; events.install("download", pct, String.valueOf(got / 1048576)); }
                    }
                }
                events.install("extract", 0, "");
                deleteTree(dir);
                dir.mkdirs();
                long size = tmp.length(), read;
                try (FileInputStream fin = new FileInputStream(tmp);
                     CountingStream cin = new CountingStream(new BufferedInputStream(fin, 1 << 16));
                     TarArchiveInputStream tar = new TarArchiveInputStream(new BZip2CompressorInputStream(cin))) {
                    TarArchiveEntry e;
                    int lastPct = -1;
                    byte[] buf = new byte[1 << 16];
                    while ((e = tar.getNextTarEntry()) != null) {
                        String name = e.getName();
                        int slash = name.indexOf('/');
                        String rel = slash >= 0 ? name.substring(slash + 1) : name;
                        if (rel.isEmpty() || !wanted(rel)) continue;
                        File f = new File(dir, rel);
                        if (!f.getCanonicalPath().startsWith(dir.getCanonicalPath())) continue;
                        if (e.isDirectory()) { f.mkdirs(); continue; }
                        f.getParentFile().mkdirs();
                        try (OutputStream out = new FileOutputStream(f)) {
                            int n;
                            while ((n = tar.read(buf)) > 0) out.write(buf, 0, n);
                        }
                        int pct = (int) (cin.count * 100 / Math.max(1, size));
                        if (pct != lastPct) { lastPct = pct; events.install("extract", pct, ""); }
                    }
                }
                if (!new File(dir, "model.int8.onnx").exists() || !new File(dir, "voices.bin").exists())
                    throw new IllegalStateException("The voice pack was incomplete");
                new FileOutputStream(new File(dir, "ok")).close();
                events.install("done", 100, "");
            } catch (Throwable t) {
                deleteTree(dir);
                events.install("error", 0, String.valueOf(t.getMessage()));
            } finally {
                tmp.delete();
                installing = false;
            }
        }, "kokoro-install").start();
    }

    private static boolean wanted(String rel) {
        return rel.equals("model.int8.onnx") || rel.equals("voices.bin") || rel.equals("tokens.txt")
                || rel.equals("lexicon-us-en.txt") || rel.equals("lexicon-gb-en.txt")
                || rel.startsWith("espeak-ng-data/") || rel.equals("LICENSE");
    }

    public void uninstall() {
        cancelAll();
        synchronized (lock) {
            if (tts != null) { try { tts.release(); } catch (Throwable ignored) { } tts = null; loadedLexicon = ""; }
        }
        deleteTree(dir);
    }

    private static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private static class CountingStream extends java.io.FilterInputStream {
        long count;
        CountingStream(InputStream in) { super(in); }
        @Override public int read() throws java.io.IOException { int b = super.read(); if (b >= 0) count++; return b; }
        @Override public int read(byte[] b, int o, int l) throws java.io.IOException { int n = super.read(b, o, l); if (n > 0) count += n; return n; }
    }

    /* ---------------- Model ---------------- */

    /** British voices (ids 20-27) read with the British lexicon. */
    private OfflineTts model(int sid) {
        String lex = (sid >= 20 && sid <= 27 && new File(dir, "lexicon-gb-en.txt").exists()) ? "lexicon-gb-en.txt" : "lexicon-us-en.txt";
        synchronized (lock) {
            if (tts != null && lex.equals(loadedLexicon)) return tts;
            if (tts != null) { try { tts.release(); } catch (Throwable ignored) { } tts = null; }
            OfflineTtsKokoroModelConfig k = new OfflineTtsKokoroModelConfig();
            k.setModel(new File(dir, "model.int8.onnx").getPath());
            k.setVoices(new File(dir, "voices.bin").getPath());
            k.setTokens(new File(dir, "tokens.txt").getPath());
            k.setDataDir(new File(dir, "espeak-ng-data").getPath());
            k.setLexicon(new File(dir, lex).getPath());
            k.setLang("en");
            OfflineTtsModelConfig mc = new OfflineTtsModelConfig();
            mc.setKokoro(k);
            mc.setNumThreads(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors())));
            mc.setProvider("cpu");
            OfflineTtsConfig cfg = new OfflineTtsConfig();
            cfg.setModel(mc);
            cfg.setMaxNumSentences(1);
            tts = new OfflineTts(null, cfg);
            loadedLexicon = lex;
            return tts;
        }
    }

    /* ---------------- Jobs ---------------- */

    /** Listen now: stream the passage (or play it straight from the cache). */
    public void play(String id, String key, String text, int sid, float speed, int pauseMs) {
        byte[] saved = cache.get(key);
        if (saved != null) { speaker.playPcmBytes(id, saved, null, RATE, speed, pauseMs); return; }
        BlockingQueue<Speaker.Chunk> sink = new LinkedBlockingQueue<>();
        synchronized (this) {
            Job r = running;
            if (r != null && key.equals(r.key) && !r.aborted) {
                // Already being prepared: replay what's done and follow along
                synchronized (r) { for (Speaker.Chunk c : r.done) sink.add(c); if (r.finished) sink.add(new Speaker.Chunk(null, 0, true)); else r.sink = sink; }
            } else {
                if (r != null) r.aborted = true;
                Job mine = null;
                for (Job j : queue) if (key.equals(j.key)) { mine = j; break; }
                queue.clear();
                if (mine == null) { mine = new Job(); mine.key = key; mine.text = text; mine.sid = sid; }
                mine.sink = sink;
                queue.addFirst(mine);
            }
        }
        speaker.playStream(id, sink, RATE, speed, pauseMs);
        events.busy(queue.size());
    }

    /** Prepare a passage ahead of time; reqId may be null. */
    public void prepare(String reqId, String key, String text, int sid) {
        if (cache.has(key)) { if (reqId != null) events.prepared(reqId, true, ""); return; }
        synchronized (this) {
            Job r = running;
            if (r != null && key.equals(r.key) && !r.aborted) return;
            for (Job j : queue) if (key.equals(j.key)) return;
            Job j = new Job(); j.key = key; j.text = text; j.sid = sid; j.reqId = reqId;
            queue.addLast(j);
        }
        events.busy(queue.size());
    }

    public void cancelAll() {
        synchronized (this) {
            queue.clear();
            Job r = running;
            if (r != null && r.sink == null) r.aborted = true;
        }
        events.busy(0);
    }

    public int pending() { return queue.size() + (running != null ? 1 : 0); }

    private void workLoop() {
        while (true) {
            Job j;
            try { j = queue.takeFirst(); } catch (InterruptedException e) { return; }
            synchronized (this) { running = j; }
            boolean ok = false; String err = "";
            try {
                if (!installed()) throw new IllegalStateException("Kokoro voices aren't downloaded");
                OfflineTts t = model(j.sid);
                ByteArrayOutputStream all = new ByteArrayOutputStream();
                for (int[] s : sentences(j.text)) {
                    if (j.aborted) break;
                    String piece = j.text.substring(s[0], s[1]);
                    GeneratedAudio a = t.generate(piece, j.sid, 1.0f);
                    float[] f = a.getSamples();
                    short[] pcm = new short[f.length];
                    byte[] bytes = new byte[f.length * 2];
                    for (int i = 0; i < f.length; i++) {
                        float v = Math.max(-1f, Math.min(1f, f[i]));
                        short sv = (short) (v * 32767);
                        pcm[i] = sv;
                        bytes[i * 2] = (byte) (sv & 0xff);
                        bytes[i * 2 + 1] = (byte) ((sv >> 8) & 0xff);
                    }
                    all.write(bytes, 0, bytes.length);
                    Speaker.Chunk c = new Speaker.Chunk(pcm, s[0], false);
                    synchronized (j) { j.done.add(c); if (j.sink != null) j.sink.add(c); }
                }
                if (!j.aborted) { cache.put(j.key, all.toByteArray()); ok = true; }
                else err = "cancelled";
            } catch (Throwable e) {
                err = String.valueOf(e.getMessage());
            } finally {
                synchronized (j) { j.finished = true; if (j.sink != null) j.sink.add(new Speaker.Chunk(null, 0, true)); }
                synchronized (this) { if (running == j) running = null; }
                if (j.reqId != null) events.prepared(j.reqId, ok, err);
                events.busy(queue.size());
            }
        }
    }

    /** Split into sentences (start, end offsets), merging very short ones. */
    static List<int[]> sentences(String t) {
        List<int[]> raw = new ArrayList<>();
        int n = t.length(), s = 0;
        for (int i = 0; i < n; i++) {
            char c = t.charAt(i);
            boolean brk = false;
            int end = i + 1;
            if (c == '\n') brk = true;
            else if (c == '.' || c == '!' || c == '?' || c == '…' || c == ';') {
                int j = i + 1;
                while (j < n && "\"'”’)]".indexOf(t.charAt(j)) >= 0) j++;
                boolean abbr = false;
                if (c == '.') {
                    int w = i; while (w > 0 && Character.isLetter(t.charAt(w - 1))) w--;
                    String word = t.substring(w, i).toLowerCase();
                    abbr = Arrays.asList("mr", "mrs", "ms", "dr", "st", "jr", "sr", "vs", "prof", "mt", "rev", "gen", "capt", "lt", "col", "no", "etc").contains(word)
                            || (word.length() == 1 && Character.isUpperCase(t.charAt(i - 1)));
                }
                if (!abbr && (j >= n || Character.isWhitespace(t.charAt(j)))) { brk = true; end = j; i = j - 1; }
            }
            if (brk || i == n - 1) {
                if (!brk) end = n;
                int a = s; while (a < end && Character.isWhitespace(t.charAt(a))) a++;
                int b = end; while (b > a && Character.isWhitespace(t.charAt(b - 1))) b--;
                if (b > a) raw.add(new int[]{a, b});
                s = end;
            }
        }
        List<int[]> out = new ArrayList<>();
        for (int[] r : raw) {
            int[] last = out.isEmpty() ? null : out.get(out.size() - 1);
            boolean lastNewline = last != null && t.substring(last[1], r[0]).indexOf('\n') >= 0;
            if (last != null && !lastNewline && (last[1] - last[0] < 25 || r[1] - r[0] < 12) && r[1] - last[0] < 300) last[1] = r[1];
            else out.add(r);
        }
        // Very long sentences: split at commas so speech starts sooner
        List<int[]> fin = new ArrayList<>();
        for (int[] r : out) {
            int a = r[0];
            while (r[1] - a > 320) {
                int cut = t.lastIndexOf(", ", a + 300);
                if (cut <= a + 60) cut = t.lastIndexOf(' ', a + 300);
                if (cut <= a) break;
                fin.add(new int[]{a, cut + 1});
                a = cut + 1; while (a < r[1] && t.charAt(a) == ' ') a++;
            }
            fin.add(new int[]{a, r[1]});
        }
        return fin;
    }
}
