package com.booktalk.app;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;

/** Generated speech kept on disk so the same passage is never paid for twice. */
public class AudioCache {
    private static final long MAX_BYTES = 400L * 1024 * 1024;
    private final File dir;

    public AudioCache(Context ctx) {
        dir = new File(ctx.getCacheDir(), "tts");
        if (!dir.exists()) dir.mkdirs();
    }

    private File file(String key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] h = md.digest(key.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return new File(dir, sb + ".pcm");
        } catch (Exception e) {
            return new File(dir, Integer.toHexString(key.hashCode()) + ".pcm");
        }
    }

    public boolean has(String key) { return file(key).length() > 0; }

    public void put(String key, byte[] pcm) {
        File f = file(key), tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream os = new FileOutputStream(tmp)) {
            os.write(pcm);
        } catch (Exception e) { tmp.delete(); return; }
        tmp.renameTo(f);
        trim();
    }

    public byte[] get(String key) {
        File f = file(key);
        if (f.length() <= 0) return null;
        byte[] b = new byte[(int) f.length()];
        try (FileInputStream in = new FileInputStream(f)) {
            int off = 0, n;
            while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
            f.setLastModified(System.currentTimeMillis());
            return b;
        } catch (Exception e) { return null; }
    }

    public long size() {
        long t = 0;
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) t += f.length();
        return t;
    }

    public void clear() {
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
    }

    private synchronized void trim() {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        long total = 0;
        for (File f : fs) total += f.length();
        if (total <= MAX_BYTES) return;
        Arrays.sort(fs, Comparator.comparingLong(File::lastModified));
        for (File f : fs) {
            if (total <= MAX_BYTES * 0.8) break;
            total -= f.length();
            f.delete();
        }
    }
}
