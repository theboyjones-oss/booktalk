package com.booktalk.app;

import org.json.JSONObject;

/**
 * Voice Tuner sound studio: tone controls, rooms and special effects, applied to the narrator's
 * audio as it plays (and baked into saved audiobooks). State carries across chunks so a passage
 * is processed as one continuous sound.
 */
final class Fx {
    private final int rate;
    private Biquad[] eq = new Biquad[0];
    private float drive = 0f, gain = 1f;
    private float ringHz = 0f; private double ringPh = 0;
    private float wobbleHz = 0f, wobbleDepth = 0f; private double wobblePh = 0;
    private float[] echo; private int echoPos; private float echoFb, echoMix;
    private Reverb rev; private float revMix;

    private Fx(int rate) { this.rate = rate; }

    /** Returns null when the settings change nothing (so normal playback is untouched). */
    static Fx make(String json, int rate) {
        if (json == null || json.length() < 3) return null;
        try {
            JSONObject o = new JSONObject(json);
            float warmth = (float) o.optDouble("warmth", 0) / 100f;
            float clarity = (float) o.optDouble("clarity", 0) / 100f;
            float room = (float) o.optDouble("room", 0) / 100f;
            String effect = o.optString("effect", "none");
            if (Math.abs(warmth) < 0.01f && Math.abs(clarity) < 0.01f && room < 0.01f && "none".equals(effect)) return null;
            Fx f = new Fx(rate);
            java.util.ArrayList<Biquad> list = new java.util.ArrayList<>();
            switch (effect) {
                case "radio":
                    list.add(Biquad.highpass(rate, 320, 0.8)); list.add(Biquad.highpass(rate, 320, 0.8));
                    list.add(Biquad.lowpass(rate, 3400, 0.8)); list.add(Biquad.lowpass(rate, 3400, 0.8));
                    list.add(Biquad.peaking(rate, 1600, 1.0, 5));
                    f.drive = 2.2f; f.gain = 0.7f; break;
                case "phone":
                    list.add(Biquad.highpass(rate, 480, 0.9)); list.add(Biquad.highpass(rate, 480, 0.9));
                    list.add(Biquad.lowpass(rate, 2900, 0.9)); list.add(Biquad.lowpass(rate, 2900, 0.9));
                    f.drive = 1.4f; break;
                case "mega":
                    list.add(Biquad.highpass(rate, 650, 0.9)); list.add(Biquad.highpass(rate, 650, 0.9));
                    list.add(Biquad.lowpass(rate, 4800, 0.8));
                    list.add(Biquad.peaking(rate, 2000, 1.2, 9));
                    f.drive = 5f; f.gain = 0.5f; break;
                case "underwater":
                    list.add(Biquad.lowpass(rate, 650, 0.9)); list.add(Biquad.lowpass(rate, 650, 0.9));
                    f.wobbleHz = 4.5f; f.wobbleDepth = 0.35f; f.gain = 1.6f; break;
                case "robot":
                    f.ringHz = 42f; list.add(Biquad.peaking(rate, 1200, 1.0, 4)); f.gain = 1.05f; break;
                case "echo":
                    f.initEcho(0.30f, 0.42f, 0.45f); break;
                case "cave":
                    f.initEcho(0.45f, 0.30f, 0.30f); room = Math.max(room, 0.9f); f.gain = 0.6f; break;
                case "giant":
                    list.add(Biquad.lowshelf(rate, 200, 6)); f.initEcho(0.09f, 0.25f, 0.25f); room = Math.max(room, 0.4f); break;
            }
            if (Math.abs(warmth) >= 0.01f) list.add(Biquad.lowshelf(rate, 220, 8 * warmth));
            if (Math.abs(clarity) >= 0.01f) list.add(Biquad.highshelf(rate, 3500, 8 * clarity));
            f.eq = list.toArray(new Biquad[0]);
            if (room >= 0.01f) { f.rev = new Reverb(rate, 0.70f + 0.27f * room); f.revMix = 0.15f + 0.4f * room; }
            return f;
        } catch (Throwable e) { return null; }
    }

    private void initEcho(float secs, float fb, float mix) {
        echo = new float[Math.max(1, (int) (rate * secs))]; echoFb = fb; echoMix = mix;
    }

    /** Process len samples from in[off..] into a new array (in is left alone: it may be cached audio). */
    short[] process(short[] in, int off, int len) {
        short[] out = new short[len];
        for (int i = 0; i < len; i++) out[i] = sample(in[off + i] / 32768f);
        return out;
    }

    private short sample(float x) {
        for (Biquad b : eq) x = b.run(x);
        if (drive > 0) x = (float) (Math.tanh(x * drive) / Math.tanh(drive)) * 0.9f;
        if (ringHz > 0) { x = x * (0.25f + 0.75f * (float) Math.sin(ringPh)); ringPh += 2 * Math.PI * ringHz / rate; if (ringPh > 2 * Math.PI) ringPh -= 2 * Math.PI; }
        if (wobbleHz > 0) { x *= 1f - wobbleDepth * (0.5f + 0.5f * (float) Math.sin(wobblePh)); wobblePh += 2 * Math.PI * wobbleHz / rate; if (wobblePh > 2 * Math.PI) wobblePh -= 2 * Math.PI; }
        if (echo != null) { float d = echo[echoPos]; echo[echoPos] = x + d * echoFb; echoPos = (echoPos + 1) % echo.length; x = x + d * echoMix; }
        if (rev != null) x = x * (1 - revMix * 0.5f) + rev.run(x) * revMix;
        x *= gain;
        if (x > 1f) x = 1f; else if (x < -1f) x = -1f;
        return (short) (x * 32767);
    }

    /** RBJ cookbook biquad filters. */
    static final class Biquad {
        final float b0, b1, b2, a1, a2; float z1, z2;
        Biquad(double b0, double b1, double b2, double a0, double a1, double a2) {
            this.b0 = (float) (b0 / a0); this.b1 = (float) (b1 / a0); this.b2 = (float) (b2 / a0); this.a1 = (float) (a1 / a0); this.a2 = (float) (a2 / a0);
        }
        float run(float x) { float y = b0 * x + z1; z1 = b1 * x - a1 * y + z2; z2 = b2 * x - a2 * y; return y; }
        static Biquad lowpass(int fs, double f, double q) { double w = 2 * Math.PI * f / fs, c = Math.cos(w), al = Math.sin(w) / (2 * q); return new Biquad((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al); }
        static Biquad highpass(int fs, double f, double q) { double w = 2 * Math.PI * f / fs, c = Math.cos(w), al = Math.sin(w) / (2 * q); return new Biquad((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al); }
        static Biquad peaking(int fs, double f, double q, double db) { double A = Math.pow(10, db / 40), w = 2 * Math.PI * f / fs, c = Math.cos(w), al = Math.sin(w) / (2 * q); return new Biquad(1 + al * A, -2 * c, 1 - al * A, 1 + al / A, -2 * c, 1 - al / A); }
        static Biquad lowshelf(int fs, double f, double db) {
            double A = Math.pow(10, db / 40), w = 2 * Math.PI * f / fs, c = Math.cos(w), s = Math.sin(w), al = s / 2 * Math.sqrt(2), r = 2 * Math.sqrt(A) * al;
            return new Biquad(A * ((A + 1) - (A - 1) * c + r), 2 * A * ((A - 1) - (A + 1) * c), A * ((A + 1) - (A - 1) * c - r), (A + 1) + (A - 1) * c + r, -2 * ((A - 1) + (A + 1) * c), (A + 1) + (A - 1) * c - r);
        }
        static Biquad highshelf(int fs, double f, double db) {
            double A = Math.pow(10, db / 40), w = 2 * Math.PI * f / fs, c = Math.cos(w), s = Math.sin(w), al = s / 2 * Math.sqrt(2), r = 2 * Math.sqrt(A) * al;
            return new Biquad(A * ((A + 1) + (A - 1) * c + r), -2 * A * ((A - 1) + (A + 1) * c), A * ((A + 1) + (A - 1) * c - r), (A + 1) - (A - 1) * c + r, 2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - r);
        }
    }

    /** Small Freeverb-style room. */
    static final class Reverb {
        final float[][] comb; final int[] cp; final float[] cs; final float fb, damp = 0.25f;
        final float[][] ap; final int[] apos;
        Reverb(int rate, float fb) {
            this.fb = fb;
            int[] cl = {1116, 1188, 1277, 1356, 1422, 1491};
            int[] al = {556, 441, 341};
            double k = rate / 44100.0;
            comb = new float[cl.length][]; cp = new int[cl.length]; cs = new float[cl.length];
            for (int i = 0; i < cl.length; i++) comb[i] = new float[Math.max(1, (int) (cl[i] * k))];
            ap = new float[al.length][]; apos = new int[al.length];
            for (int i = 0; i < al.length; i++) ap[i] = new float[Math.max(1, (int) (al[i] * k))];
        }
        float run(float x) {
            float in = x * 0.09f, out = 0;
            for (int i = 0; i < comb.length; i++) {
                float y = comb[i][cp[i]];
                cs[i] = y * (1 - damp) + cs[i] * damp;
                comb[i][cp[i]] = in + cs[i] * fb;
                cp[i] = (cp[i] + 1) % comb[i].length;
                out += y;
            }
            for (int i = 0; i < ap.length; i++) {
                float b = ap[i][apos[i]];
                ap[i][apos[i]] = out + b * 0.5f;
                apos[i] = (apos[i] + 1) % ap[i].length;
                out = b - out;
            }
            return out;
        }
    }
}
