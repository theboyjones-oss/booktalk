package com.booktalk.app;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Gemini text-to-speech, done natively so multi-minute clips never pass through JavaScript. */
public class GeminiClient {

    public static class Result {
        public int status;
        public byte[] pcm;
        public int rate = 24000;
        public String error = "";
    }

    public static Result tts(String key, String model, String json) throws Exception {
        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + URLEncoder.encode(model, "UTF-8") + ":generateContent");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        Result r = new Result();
        try {
            byte[] body = json.getBytes(StandardCharsets.UTF_8);
            c.setRequestMethod("POST");
            c.setConnectTimeout(20000);
            c.setReadTimeout(240000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("x-goog-api-key", key);
            c.setFixedLengthStreamingMode(body.length);
            try (OutputStream os = c.getOutputStream()) { os.write(body); }
            r.status = c.getResponseCode();
            InputStream in = r.status >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (in != null) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
            }
            String text = out.toString("UTF-8");
            if (r.status >= 400) {
                r.error = text.length() > 4000 ? text.substring(0, 4000) : text;
                return r;
            }
            // Pull out the base64 audio without parsing the whole (large) JSON document
            Matcher mm = Pattern.compile("\"mimeType\"\\s*:\\s*\"[^\"]*rate=(\\d+)").matcher(text);
            if (mm.find()) r.rate = Integer.parseInt(mm.group(1));
            int k = text.indexOf("\"data\"");
            if (k < 0) {
                Matcher fr = Pattern.compile("\"finishReason\"\\s*:\\s*\"(\\w+)\"").matcher(text);
                r.status = 0;
                r.error = "Gemini returned no audio" + (fr.find() ? " (" + fr.group(1) + ")" : "");
                return r;
            }
            int q1 = text.indexOf('"', text.indexOf(':', k) + 1);
            int q2 = text.indexOf('"', q1 + 1);
            r.pcm = Base64.decode(text.substring(q1 + 1, q2).replace("\\/", "/"), Base64.DEFAULT);
            return r;
        } finally {
            c.disconnect();
        }
    }
}
