package com.booktalk.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;

/**
 * Joins a chapter's generated passages (24 kHz mono PCM from the audio cache) into one
 * AAC .m4a file and saves it to Music/BookTalk/&lt;book&gt;/ so any player can use it.
 */
public class AudiobookExporter {

    static final int RATE = 24000;

    /** items: [{"k": cacheKey|null, "gap": ms}]. Returns a human-readable location. */
    public static String export(Context ctx, AudioCache cache, String book, String name, JSONArray items) throws Exception { return export(ctx, cache, book, name, items, 1f); }

    public static String export(Context ctx, AudioCache cache, String book, String name, JSONArray items, float pitch) throws Exception {
        File tmp = new File(ctx.getCacheDir(), "export-" + System.nanoTime() + ".m4a");
        try {
            encode(cache, items, tmp, pitch);
            String folder = "BookTalk/" + clean(book);
            String file = clean(name) + ".m4a";
            if (Build.VERSION.SDK_INT >= 29) {
                ContentResolver r = ctx.getContentResolver();
                ContentValues v = new ContentValues();
                v.put(MediaStore.Audio.Media.DISPLAY_NAME, file);
                v.put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4");
                v.put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/" + folder);
                v.put(MediaStore.Audio.Media.TITLE, name);
                v.put(MediaStore.Audio.Media.IS_PENDING, 1);
                Uri uri = r.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
                if (uri == null) throw new IllegalStateException("Couldn't create the file in Music");
                try (OutputStream os = r.openOutputStream(uri); InputStream in = new FileInputStream(tmp)) {
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
                v.clear();
                v.put(MediaStore.Audio.Media.IS_PENDING, 0);
                r.update(uri, v, null, null);
                return "Music/" + folder;
            } else {
                File dir = new File(ctx.getExternalFilesDir(Environment.DIRECTORY_MUSIC), folder);
                dir.mkdirs();
                File out = new File(dir, file);
                if (!tmp.renameTo(out)) {
                    try (InputStream in = new FileInputStream(tmp); OutputStream os = new java.io.FileOutputStream(out)) {
                        byte[] buf = new byte[1 << 16];
                        int n;
                        while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                    }
                }
                return dir.getPath();
            }
        } finally {
            tmp.delete();
        }
    }

    static String clean(String s) {
        String t = s == null ? "" : s.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (t.length() > 80) t = t.substring(0, 80).trim();
        return t.isEmpty() ? "Untitled" : t;
    }

    /** Streams all PCM pieces through an AAC encoder into an MPEG-4 file. */
    /** Linear resample: playing faster/slower raises/lowers pitch (the mood's pitch, baked into the file). */
    static byte[] resample(byte[] pcm, float factor) {
        if (pcm == null || Math.abs(factor - 1f) < 0.005f) return pcm;
        int n = pcm.length / 2, m = (int) (n / factor);
        byte[] out = new byte[m * 2];
        for (int i = 0; i < m; i++) {
            float x = i * factor; int a = (int) x; float f = x - a;
            int b = Math.min(a + 1, n - 1); a = Math.min(a, n - 1);
            short sa = (short) ((pcm[a * 2] & 0xff) | (pcm[a * 2 + 1] << 8));
            short sb = (short) ((pcm[b * 2] & 0xff) | (pcm[b * 2 + 1] << 8));
            short v = (short) Math.round(sa + (sb - sa) * f);
            out[i * 2] = (byte) (v & 0xff); out[i * 2 + 1] = (byte) ((v >> 8) & 0xff);
        }
        return out;
    }

    private static void encode(AudioCache cache, JSONArray items, File out, float pitch) throws Exception {
        MediaFormat fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1);
        fmt.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        fmt.setInteger(MediaFormat.KEY_BIT_RATE, 64000);
        fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
        MediaCodec enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
        enc.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        enc.start();
        MediaMuxer mux = new MediaMuxer(out.getPath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        int track = -1;
        boolean muxing = false, inputDone = false, outputDone = false;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        int item = 0;            // which item we're reading
        byte[] cur = null;       // current PCM block
        int pos = 0;
        long framesIn = 0;

        try {
            while (!outputDone) {
                if (!inputDone) {
                    int ix = enc.dequeueInputBuffer(10000);
                    if (ix >= 0) {
                        ByteBuffer ib = enc.getInputBuffer(ix);
                        ib.clear();
                        int cap = Math.min(ib.remaining(), 8192) & ~1;
                        int filled = 0;
                        while (filled < cap) {
                            if (cur == null || pos >= cur.length) {
                                if (item >= items.length()) break;
                                JSONObject it = items.getJSONObject(item++);
                                String k = it.optString("k", "");
                                byte[] pcm = k.isEmpty() ? null : resample(cache.get(k), pitch);
                                int gap = it.optInt("gap", 0);
                                int silence = (int) ((long) RATE * gap / 1000) * 2;
                                int len = (pcm != null ? pcm.length : 0) + silence;
                                cur = new byte[len];
                                if (pcm != null) System.arraycopy(pcm, 0, cur, 0, pcm.length);
                                pos = 0;
                                continue;
                            }
                            int n = Math.min(cap - filled, cur.length - pos);
                            ib.put(cur, pos, n);
                            pos += n;
                            filled += n;
                        }
                        long pts = framesIn * 1_000_000L / RATE;
                        if (filled == 0) {
                            enc.queueInputBuffer(ix, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            enc.queueInputBuffer(ix, 0, filled, pts, 0);
                            framesIn += filled / 2;
                        }
                    }
                }
                int ox = enc.dequeueOutputBuffer(info, 10000);
                if (ox == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = mux.addTrack(enc.getOutputFormat());
                    mux.start();
                    muxing = true;
                } else if (ox >= 0) {
                    ByteBuffer ob = enc.getOutputBuffer(ox);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && muxing) {
                        ob.position(info.offset);
                        ob.limit(info.offset + info.size);
                        mux.writeSampleData(track, ob, info);
                    }
                    enc.releaseOutputBuffer(ox, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
                }
            }
        } finally {
            try { enc.stop(); } catch (Exception ignored) { }
            enc.release();
            try { if (muxing) mux.stop(); } catch (Exception ignored) { }
            mux.release();
        }
    }
}
