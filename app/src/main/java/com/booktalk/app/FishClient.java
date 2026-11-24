package com.booktalk.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Minimal Fish Audio REST client (runs off the main thread). */
public class FishClient {

    public static class Result {
        public int status;
        public byte[] body;
        public String text() { return body == null ? "" : new String(body, StandardCharsets.UTF_8); }
        public boolean ok() { return status >= 200 && status < 300; }
    }

    private static final String BASE = "https://api.fish.audio";

    /** POST /model (multipart) to create a private instant voice clone. */
    public static Result createVoice(String key, String title, byte[] wav) throws Exception {
        String boundary = "----BookTalk" + System.nanoTime();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        field(b, boundary, "type", "tts");
        field(b, boundary, "title", title);
        field(b, boundary, "train_mode", "fast");
        field(b, boundary, "visibility", "private");
        field(b, boundary, "enhance_audio_quality", "true");
        b.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"voices\"; filename=\"voice.wav\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        b.write(wav);
        b.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return send("POST", BASE + "/model", key, "multipart/form-data; boundary=" + boundary, null, b.toByteArray(), 120000);
    }

    /** POST /v1/tts with a JSON body; returns raw audio bytes. */
    public static Result tts(String key, String model, String json) throws Exception {
        return send("POST", BASE + "/v1/tts", key, "application/json", model, json.getBytes(StandardCharsets.UTF_8), 180000);
    }

    public static Result deleteVoice(String key, String id) throws Exception {
        return send("DELETE", BASE + "/model/" + id, key, null, null, null, 30000);
    }

    private static void field(ByteArrayOutputStream b, String boundary, String name, String value) throws Exception {
        b.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    private static Result send(String method, String url, String key, String contentType, String model, byte[] body, int timeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(20000);
            c.setReadTimeout(timeout);
            c.setRequestProperty("Authorization", "Bearer " + key);
            if (model != null && !model.isEmpty()) c.setRequestProperty("model", model);
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", contentType);
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream os = c.getOutputStream()) { os.write(body); }
            }
            Result r = new Result();
            r.status = c.getResponseCode();
            InputStream in = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
            }
            r.body = out.toByteArray();
            return r;
        } finally {
            c.disconnect();
        }
    }
}
