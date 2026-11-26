package com.booktalk.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;

/**
 * BookTalk's fast AI narrators: Supertonic 3 (Supertone, open weights, OpenRAIL-M) running on the phone
 * with ONNX Runtime. Ten preset voices, plus blends of them. Much faster than real time, so it keeps up
 * with reading when passages are made a few ahead.
 */
final class SupertonicEngine {
    interface Progress { void at(String stage, int pct, String msg); }

    static final String[] VOICES = {"M1", "M2", "M3", "M4", "M5", "F1", "F2", "F3", "F4", "F5"};
    private static final String[] PARTS = {"duration_predictor.onnx", "text_encoder.onnx", "vector_estimator.onnx", "vocoder.onnx"};

    private final Context ctx;
    private final File dir;
    private final SharedPreferences prefs;
    private volatile boolean installing = false;

    private OrtEnvironment env;
    private OrtSession dp, enc, est, voc;
    private long[] indexer;
    private int sampleRate, baseChunk, compress, ldim;
    private final Map<String, float[][]> styles = new HashMap<>();   // name → {ttl, dp}
    private long[] ttlShape, dpShape;
    private int loadedThreads = 0;

    SupertonicEngine(Context ctx) {
        this.ctx = ctx.getApplicationContext();
        this.dir = new File(this.ctx.getFilesDir(), "supertonic");
        this.prefs = this.ctx.getSharedPreferences("supertonic", Context.MODE_PRIVATE);
    }

    boolean installed() {
        for (String p : PARTS) if (!new File(dir, p).isFile()) return false;
        return new File(dir, "tts.json").isFile() && new File(dir, "unicode_indexer.json").isFile()
                && new File(dir, "voice_styles/M1.json").isFile();
    }
    boolean installing() { return installing; }
    int sampleRate() { return sampleRate; }

    /* ---------------- install ---------------- */

    /** files: [{"url":..., "path":"vocoder.onnx", "size": bytes}] */
    void install(JSONArray files, Progress prog) {
        if (installing) return;
        installing = true;
        new Thread(() -> {
            try {
                dir.mkdirs();
                long total = 0, done = 0;
                for (int i = 0; i < files.length(); i++) total += Math.max(1, files.getJSONObject(i).optLong("size", 1));
                for (int i = 0; i < files.length(); i++) {
                    JSONObject f = files.getJSONObject(i);
                    File out = new File(dir, f.getString("path"));
                    long size = f.optLong("size", 0);
                    if (out.isFile() && out.length() > 0) { done += Math.max(1, size); continue; }   // already have it (files are only renamed into place once complete)
                    out.getParentFile().mkdirs();
                    File tmp = new File(out.getPath() + ".part");
                    final long base = done, tot = total;
                    final String label = f.getString("path");
                    download(f.getString("url"), tmp, n -> prog.at("download", (int) Math.min(99, (base + n) * 100 / tot), label));
                    if (!tmp.renameTo(out)) throw new IllegalStateException("Couldn't save " + f.getString("path"));
                    done += Math.max(1, size > 0 ? size : out.length());
                }
                prog.at("check", 99, "Checking the voices…");
                close();
                load(threads());
                prog.at("tune", 99, "Measuring your phone's speed…");
                tune();
                prog.at("done", 100, "Ready");
            } catch (Throwable e) {
                prog.at("error", 0, String.valueOf(e.getMessage()));
            } finally {
                installing = false;
            }
        }, "st-install").start();
    }

    interface Count { void n(long bytes); }

    private static void download(String url, File out, Count cb) throws Exception {
        HttpURLConnection c = null;
        for (int hop = 0; hop < 6; hop++) {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(20000);
            c.setReadTimeout(60000);
            c.setRequestProperty("User-Agent", "BookTalk (Android)");
            int code = c.getResponseCode();
            if (code >= 300 && code < 400) { url = new URL(new URL(url), c.getHeaderField("Location")).toString(); c.disconnect(); continue; }
            if (code != 200) throw new IllegalStateException("Download failed (HTTP " + code + ")");
            break;
        }
        try (InputStream in = c.getInputStream(); FileOutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            long n = 0, last = 0; int r;
            while ((r = in.read(buf)) > 0) {
                os.write(buf, 0, r); n += r;
                if (n - last > (1 << 20)) { last = n; cb.n(n); }
            }
        } finally { c.disconnect(); }
    }

    void uninstall() {
        close();
        deleteAll(dir);
    }

    private static void deleteAll(File f) {
        File[] k = f.listFiles();
        if (k != null) for (File x : k) deleteAll(x);
        f.delete();
    }

    /* ---------------- load ---------------- */

    int threads() { return prefs.getInt("threads", Math.min(4, Runtime.getRuntime().availableProcessors())); }
    float speedX() { return prefs.getFloat("speedX", 0f); }   // how many times faster than real time (0 = not measured)

    private synchronized void ensureLoaded() throws Exception {
        if (dp != null && loadedThreads == threads()) return;
        close();
        load(threads());
    }

    private synchronized void load(int threads) throws Exception {
        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setIntraOpNumThreads(threads);
        o.setInterOpNumThreads(1);
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        dp = env.createSession(new File(dir, PARTS[0]).getPath(), o);
        enc = env.createSession(new File(dir, PARTS[1]).getPath(), o);
        est = env.createSession(new File(dir, PARTS[2]).getPath(), o);
        voc = env.createSession(new File(dir, PARTS[3]).getPath(), o);
        JSONObject cfg = new JSONObject(read(new File(dir, "tts.json")));
        sampleRate = cfg.getJSONObject("ae").getInt("sample_rate");
        baseChunk = cfg.getJSONObject("ae").getInt("base_chunk_size");
        compress = cfg.getJSONObject("ttl").getInt("chunk_compress_factor");
        ldim = cfg.getJSONObject("ttl").getInt("latent_dim");
        if (indexer == null) {
            JSONArray a = new JSONArray(read(new File(dir, "unicode_indexer.json")));
            indexer = new long[a.length()];
            for (int i = 0; i < indexer.length; i++) indexer[i] = a.getLong(i);
        }
        loadedThreads = threads;
    }

    synchronized void close() {
        for (OrtSession s : new OrtSession[]{dp, enc, est, voc}) if (s != null) try { s.close(); } catch (Exception ignored) { }
        dp = enc = est = voc = null;
    }

    private static String read(File f) throws Exception {
        byte[] b = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) { int off = 0, n; while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n; }
        return new String(b, StandardCharsets.UTF_8);
    }

    /** One preset voice: {ttl (flattened), dp (flattened)}. */
    private float[][] preset(String name) throws Exception {
        float[][] s = styles.get(name);
        if (s != null) return s;
        JSONObject j = new JSONObject(read(new File(dir, "voice_styles/" + name + ".json")));
        JSONObject t = j.getJSONObject("style_ttl"), d = j.getJSONObject("style_dp");
        ttlShape = dims(t.getJSONArray("dims")); dpShape = dims(d.getJSONArray("dims"));
        s = new float[][]{flat(t.getJSONArray("data")), flat(d.getJSONArray("data"))};
        styles.put(name, s);
        return s;
    }

    private static long[] dims(JSONArray a) throws Exception { long[] r = new long[a.length()]; for (int i = 0; i < r.length; i++) r[i] = a.getLong(i); r[0] = 1; return r; }

    private static float[] flat(JSONArray a) throws Exception {
        ArrayList<Float> out = new ArrayList<>();
        flatInto(a, out);
        float[] r = new float[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }
    private static void flatInto(JSONArray a, ArrayList<Float> out) throws Exception {
        for (int i = 0; i < a.length(); i++) {
            Object o = a.get(i);
            if (o instanceof JSONArray) flatInto((JSONArray) o, out); else out.add((float) a.getDouble(i));
        }
    }

    /** "M1", or a blend "M1=0.6,F2=0.4" (weights are normalised). */
    private float[][] style(String spec) throws Exception {
        if (spec == null || spec.isEmpty()) spec = "M1";
        if (!spec.contains("=")) return preset(spec.trim());
        float[] ttl = null, dpv = null; float total = 0;
        for (String part : spec.split(",")) {
            String[] kv = part.split("=");
            if (kv.length != 2) continue;
            float w = Float.parseFloat(kv[1].trim());
            if (w <= 0) continue;
            float[][] p = preset(kv[0].trim());
            if (ttl == null) { ttl = new float[p[0].length]; dpv = new float[p[1].length]; }
            for (int i = 0; i < ttl.length; i++) ttl[i] += p[0][i] * w;
            for (int i = 0; i < dpv.length; i++) dpv[i] += p[1][i] * w;
            total += w;
        }
        if (ttl == null) return preset("M1");
        for (int i = 0; i < ttl.length; i++) ttl[i] /= total;
        for (int i = 0; i < dpv.length; i++) dpv[i] /= total;
        return new float[][]{ttl, dpv};
    }

    /* ---------------- speak ---------------- */

    int steps() { return prefs.getInt("steps", 5); }
    void setQuality(int steps) { prefs.edit().putInt("steps", Math.max(2, Math.min(12, steps))).apply(); }

    /** Make speech for one passage. speed: 1 = normal. Returns 16-bit samples at sampleRate(). */
    synchronized short[] speak(String text, String voice, float speed) throws Exception {
        ensureLoaded();
        float[][] st = style(voice);
        List<String> chunks = chunk(text.trim(), 300);
        ArrayList<short[]> parts = new ArrayList<>();
        int total = 0;
        for (String c : chunks) {
            if (c.trim().isEmpty()) continue;
            short[] p = infer(c, st, steps(), speed);
            parts.add(p); total += p.length;
            if (chunks.size() > 1) { short[] gap = new short[sampleRate / 6]; parts.add(gap); total += gap.length; }
        }
        short[] out = new short[total];
        int off = 0;
        for (short[] p : parts) { System.arraycopy(p, 0, out, off, p.length); off += p.length; }
        return out;
    }

    private short[] infer(String text, float[][] st, int totalStep, float speed) throws Exception {
        int[] cps = prep(text).codePoints().toArray();
        long[] ids = new long[cps.length];
        for (int i = 0; i < cps.length; i++) ids[i] = cps[i] < indexer.length ? indexer[cps[i]] : 0;
        float[] tmask = new float[cps.length];
        java.util.Arrays.fill(tmask, 1f);
        long[] tShape = {1, ids.length}, mShape = {1, 1, ids.length};
        try (OnnxTensor tIds = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), tShape);
             OnnxTensor tMask = OnnxTensor.createTensor(env, FloatBuffer.wrap(tmask), mShape);
             OnnxTensor sTtl = OnnxTensor.createTensor(env, FloatBuffer.wrap(st[0]), ttlShape);
             OnnxTensor sDp = OnnxTensor.createTensor(env, FloatBuffer.wrap(st[1]), dpShape)) {
            Map<String, OnnxTensor> in = new HashMap<>();
            in.put("text_ids", tIds); in.put("style_dp", sDp); in.put("text_mask", tMask);
            float dur;
            try (OrtSession.Result r = dp.run(in)) {
                Object v = r.get(0).getValue();
                dur = v instanceof float[][] ? ((float[][]) v)[0][0] : ((float[]) v)[0];
            }
            dur /= Math.max(0.3f, speed);
            in.clear();
            in.put("text_ids", tIds); in.put("style_ttl", sTtl); in.put("text_mask", tMask);
            try (OrtSession.Result er = enc.run(in)) {
                OnnxTensor emb = (OnnxTensor) er.get(0);
                long wavLen = (long) (dur * sampleRate);
                int chunk = baseChunk * compress, latLen = (int) ((wavLen + chunk - 1) / chunk), latDim = ldim * compress;
                float[] xt = new float[latDim * latLen];
                Random rng = new Random();
                for (int i = 0; i < xt.length; i++) xt[i] = (float) rng.nextGaussian();
                float[] lmask = new float[latLen];
                java.util.Arrays.fill(lmask, 1f);
                long[] xShape = {1, latDim, latLen}, lShape = {1, 1, latLen};
                try (OnnxTensor tStep = OnnxTensor.createTensor(env, new float[]{totalStep});
                     OnnxTensor lm = OnnxTensor.createTensor(env, FloatBuffer.wrap(lmask), lShape)) {
                    for (int step = 0; step < totalStep; step++) {
                        try (OnnxTensor cur = OnnxTensor.createTensor(env, new float[]{step});
                             OnnxTensor x = OnnxTensor.createTensor(env, FloatBuffer.wrap(xt), xShape)) {
                            in.clear();
                            in.put("noisy_latent", x); in.put("text_emb", emb); in.put("style_ttl", sTtl);
                            in.put("latent_mask", lm); in.put("text_mask", tMask);
                            in.put("current_step", cur); in.put("total_step", tStep);
                            try (OrtSession.Result r = est.run(in)) {
                                FloatBuffer fb = ((OnnxTensor) r.get(0)).getFloatBuffer();
                                fb.get(xt, 0, Math.min(xt.length, fb.remaining()));
                            }
                        }
                    }
                    try (OnnxTensor x = OnnxTensor.createTensor(env, FloatBuffer.wrap(xt), xShape)) {
                        in.clear(); in.put("latent", x);
                        try (OrtSession.Result r = voc.run(in)) {
                            FloatBuffer fb = ((OnnxTensor) r.get(0)).getFloatBuffer();
                            int n = (int) Math.min(fb.remaining(), wavLen);
                            short[] out = new short[n];
                            for (int i = 0; i < n; i++) {
                                float s = fb.get(i);
                                out[i] = (short) Math.max(-32767, Math.min(32767, Math.round(s * 32767f)));
                            }
                            return out;
                        }
                    }
                }
            }
        }
    }

    /** The same clean-up Supertonic's own code does before speaking. */
    private static String prep(String text) {
        text = Normalizer.normalize(text, Normalizer.Form.NFKD);
        StringBuilder sb = new StringBuilder();
        text.codePoints().forEach(cp -> {
            boolean emoji = (cp >= 0x1F300 && cp <= 0x1FAFF) || (cp >= 0x2600 && cp <= 0x27BF) || (cp >= 0x1F1E6 && cp <= 0x1F1FF);
            if (!emoji) sb.appendCodePoint(cp);
        });
        text = sb.toString()
                .replace("–", "-").replace("‑", "-").replace("—", "-").replace("_", " ")
                .replace("“", "\"").replace("”", "\"").replace("‘", "'").replace("’", "'")
                .replace("´", "'").replace("`", "'").replace("[", " ").replace("]", " ").replace("|", " ")
                .replace("/", " ").replace("#", " ").replace("→", " ").replace("←", " ")
                .replaceAll("[♥☆♡©\\\\]", "")
                .replace("@", " at ").replace("e.g.,", "for example, ").replace("i.e.,", "that is, ")
                .replace(" ,", ",").replace(" .", ".").replace(" !", "!").replace(" ?", "?").replace(" ;", ";").replace(" :", ":").replace(" '", "'");
        while (text.contains("\"\"")) text = text.replace("\"\"", "\"");
        while (text.contains("''")) text = text.replace("''", "'");
        text = text.replaceAll("\\s+", " ").trim();
        if (!text.matches("(?s).*[.!?;:,'\"\\u201C\\u201D\\u2018\\u2019)\\]}…]$")) text += ".";
        return "<en>" + text + "</en>";
    }

    /** Long passages are made in pieces of at most maxLen characters (split at sentences, then commas, then words). */
    private static List<String> chunk(String text, int maxLen) {
        ArrayList<String> out = new ArrayList<>();
        if (text.length() <= maxLen) { out.add(text); return out; }
        StringBuilder cur = new StringBuilder();
        for (String piece : text.split("(?<=[.!?…])\\s+|(?<=,)\\s+")) {
            if (piece.length() > maxLen) {
                for (String w : piece.split("\\s+")) {
                    if (cur.length() + w.length() + 1 > maxLen && cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
                    if (cur.length() > 0) cur.append(' ');
                    cur.append(w);
                }
                continue;
            }
            if (cur.length() + piece.length() + 1 > maxLen && cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            if (cur.length() > 0) cur.append(' ');
            cur.append(piece);
        }
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    /* ---------------- speed check ---------------- */

    /** Try a few thread counts once and keep the fastest. Records how many times faster than real time it runs. */
    synchronized void tune() {
        String t = "The lighthouse stood alone at the edge of the world, and every night its lamp swept the dark water.";
        int cores = Runtime.getRuntime().availableProcessors();
        int best = threads(); double bestX = 0;
        for (int th : new int[]{2, 4, Math.min(6, cores)}) {
            if (th > cores) continue;
            try {
                close(); load(th);
                speak("Hello.", "M1", 1f);   // warm up
                long t0 = System.nanoTime();
                short[] a = speak(t, "M1", 1f);
                double secs = (System.nanoTime() - t0) / 1e9, audio = a.length / (double) sampleRate;
                double x = audio / Math.max(0.01, secs);
                if (x > bestX) { bestX = x; best = th; }
            } catch (Throwable ignored) { }
        }
        prefs.edit().putInt("threads", best).putFloat("speedX", (float) bestX).apply();
        close();
    }
}
