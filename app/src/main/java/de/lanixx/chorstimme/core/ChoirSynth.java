package de.lanixx.chorstimme.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Renders a {@link Score} as a "choir" singing vowels: every voice is three slightly detuned
 * singers, each an additive (formant-shaped) wavetable oscillator with vibrato, followed by a
 * Freeverb-style hall. No samples, no speech synthesis.
 */
public final class ChoirSynth {

    public static final int SAMPLE_RATE = 44100;

    public static class Options {
        public double tempoFactor = 1.0;       // 1.0 = tempo of the score
        public double[] voiceGain;             // per voice, null = all 1.0
        public boolean countIn = true;         // one bar of clicks before the music
        public int singersPerVoice = 3;
        public double reverb = 0.28;           // wet amount
    }

    public interface Progress {
        void update(int percent);
    }

    /** Interleaved stereo, 16 bit. */
    public static short[] render(Score score, Options opt, Progress progress) {
        double spb = 60.0 / (score.tempoBpm * opt.tempoFactor);     // seconds per beat
        double lead = opt.countIn ? 4 * spb : 0.3;
        double total = lead + score.end() * spb + 3.0;
        int n = (int) (total * SAMPLE_RATE);
        float[] left = new float[n], right = new float[n];
        Random rng = new Random(7);

        int nv = score.voices.size();
        int jobs = Math.max(1, nv * opt.singersPerVoice);
        int done = 0;
        for (int v = 0; v < nv; v++) {
            double gain = opt.voiceGain != null && v < opt.voiceGain.length ? opt.voiceGain[v] : 1.0;
            if (gain <= 0.001) { done += opt.singersPerVoice; continue; }
            double pan = nv == 1 ? 0 : -0.5 + (double) v / (nv - 1);     // spread voices L..R
            for (int s = 0; s < opt.singersPerVoice; s++) {
                double detune = (s - (opt.singersPerVoice - 1) / 2.0) * 7 + (rng.nextDouble() - 0.5) * 4;
                double p = Math.max(-1, Math.min(1, pan * 0.7 + (rng.nextDouble() - 0.5) * 0.3));
                float gl = (float) (gain * Math.sqrt((1 - p) / 2));
                float gr = (float) (gain * Math.sqrt((1 + p) / 2));
                singer(score.voices.get(v).notes, spb, lead, detune, rng, left, right, gl, gr);
                done++;
                if (progress != null) progress.update(done * 80 / jobs);
            }
        }
        if (opt.countIn) clicks(left, right, spb);
        reverb(left, right, opt.reverb);
        if (progress != null) progress.update(95);

        float peak = 1e-6f;
        for (int i = 0; i < n; i++) peak = Math.max(peak, Math.max(Math.abs(left[i]), Math.abs(right[i])));
        float k = 0.89f * 32767f / peak;
        short[] out = new short[2 * n];
        for (int i = 0; i < n; i++) {
            out[2 * i] = (short) Math.round(left[i] * k);
            out[2 * i + 1] = (short) Math.round(right[i] * k);
        }
        if (progress != null) progress.update(100);
        return out;
    }

    // ------------------------------------------------------------------ voice

    private static final int TABLE = 2048;

    private static final double[][] FORMANTS = {   // F, bandwidth; vowels a e i o u
            {750, 90, 1200, 110, 2700, 160, 3400, 200},
            {450, 80, 1800, 120, 2650, 160, 3400, 200},
            {300, 70, 2200, 120, 2900, 160, 3500, 200},
            {480, 80, 850, 100, 2600, 150, 3300, 200},
            {340, 70, 750, 100, 2500, 150, 3300, 200},
    };
    private static final double[] FORMANT_GAIN = {1.0, 0.6, 0.25, 0.15};

    private static final Map<Long, float[]> TABLES = new HashMap<>();

    private static int vowelIndex(char v) {
        switch (v) {
            case 'e': return 1;
            case 'i': return 2;
            case 'o': return 3;
            case 'u': return 4;
            default: return 0;
        }
    }

    /** One period of a sung vowel at frequency f0 (harmonics shaped by formants). */
    private static synchronized float[] table(char vowel, double f0) {
        int vi = vowelIndex(vowel);
        long key = vi * 100000L + Math.round(f0 * 4);
        float[] t = TABLES.get(key);
        if (t != null) return t;
        double[] fm = FORMANTS[vi];
        int harmonics = (int) Math.min(60, Math.floor(5000 / f0));
        double[] amp = new double[harmonics + 1];
        for (int h = 1; h <= harmonics; h++) {
            double f = h * f0, g = 0;
            for (int j = 0; j < 4; j++) {
                double fc = fm[2 * j], bw = fm[2 * j + 1];
                double x = (f - fc) / (bw / 2);
                g += FORMANT_GAIN[j] / (1 + x * x);
            }
            amp[h] = g / Math.pow(h, 0.6) + 0.02 / h;
        }
        t = new float[TABLE + 1];
        double sumSq = 0;
        for (int i = 0; i < TABLE; i++) {
            double ph = 2 * Math.PI * i / TABLE, s = 0;
            for (int h = 1; h <= harmonics; h++) s += amp[h] * Math.sin(h * ph);
            t[i] = (float) s;
            sumSq += s * s;
        }
        float norm = (float) (0.3 / Math.sqrt(sumSq / TABLE + 1e-12));
        for (int i = 0; i < TABLE; i++) t[i] *= norm;
        t[TABLE] = t[0];
        if (TABLES.size() > 4000) TABLES.clear();
        TABLES.put(key, t);
        return t;
    }

    private static double hz(int midi, double cents) {
        return 440.0 * Math.pow(2, (midi - 69 + cents / 100.0) / 12.0);
    }

    private static void singer(List<Score.Note> notes, double spb, double lead, double detune, Random rng,
                               float[] left, float[] right, float gl, float gr) {
        int sr = SAMPLE_RATE;
        double vibPhase = rng.nextDouble() * 2 * Math.PI;
        double vibRate = 5.0 + rng.nextDouble() * 0.6;
        double jitter = 0, jitterTarget = 0;
        double[] phase = new double[8];
        for (int k = 0; k < phase.length; k++) phase[k] = rng.nextDouble();
        int[] prevMidi = null;
        double prevEnd = -1;

        for (int idx = 0; idx < notes.size(); idx++) {
            Score.Note note = notes.get(idx);
            Score.Note next = idx + 1 < notes.size() ? notes.get(idx + 1) : null;
            int a = (int) ((lead + note.start * spb) * sr);
            int b = (int) ((lead + note.end() * spb) * sr);
            if (b <= a) continue;
            b = Math.min(b, left.length);
            boolean newSyl = note.syllable != null || prevEnd < note.start - 1e-6 || prevMidi == null;
            boolean legatoIn = !newSyl && prevMidi != null;
            boolean nextNew = next == null || next.syllable != null || next.start > note.end() + 1e-6;
            int att = (int) ((newSyl ? 0.07 : 0.02) * sr);
            int rel = (int) ((nextNew ? 0.06 : 0.02) * sr);
            float attFrom = newSyl ? 0f : 0.75f;
            float relTo = nextNew ? 0.25f : 0.8f;
            int glide = legatoIn ? (int) (0.035 * sr) : 0;

            int voices = Math.min(note.pitches.length, phase.length);
            float[][] tabs = new float[voices][];
            double[] f = new double[voices], fPrev = new double[voices];
            for (int k = 0; k < voices; k++) {
                f[k] = hz(note.pitches[k], detune);
                fPrev[k] = prevMidi != null && k < prevMidi.length ? hz(prevMidi[k], detune) : f[k];
                tabs[k] = table(note.vowel == 0 ? 'a' : note.vowel, f[k]);
            }
            float chordGain = 1f / (float) Math.sqrt(voices);
            int len = b - a;
            for (int i = 0; i < len; i++) {
                double t = (double) (a + i) / sr;
                // envelope
                float env = 1f;
                if (i < att) env = attFrom + (1 - attFrom) * i / att;
                if (i > len - rel) env *= 1 - (1 - relTo) * (i - (len - rel)) / (float) rel;
                // vibrato fades in after 0.25 s of a note
                double noteT = (double) i / sr;
                double vibDepth = 0.0045 * Math.min(1, Math.max(0, (noteT - 0.25) / 0.4));
                if (!newSyl) vibDepth = 0.0045;
                double vib = 1 + vibDepth * Math.sin(2 * Math.PI * vibRate * t + vibPhase);
                if ((i & 255) == 0) jitterTarget = (rng.nextDouble() - 0.5) * 0.004;
                jitter += (jitterTarget - jitter) * 0.002;
                float s = 0;
                for (int k = 0; k < voices; k++) {
                    double fk = i < glide ? fPrev[k] + (f[k] - fPrev[k]) * i / glide : f[k];
                    phase[k] += fk * vib * (1 + jitter) / sr;
                    phase[k] -= Math.floor(phase[k]);
                    double pos = phase[k] * TABLE;
                    int p0 = (int) pos;
                    float fr = (float) (pos - p0);
                    float[] tb = tabs[k];
                    s += tb[p0] + (tb[p0 + 1] - tb[p0]) * fr;
                }
                s *= env * chordGain;
                // a little breath noise
                s += (float) ((rng.nextDouble() - 0.5) * 0.004 * env);
                int o = a + i;
                left[o] += s * gl;
                right[o] += s * gr;
            }
            prevMidi = note.pitches;
            prevEnd = note.end();
        }
    }

    private static void clicks(float[] left, float[] right, double spb) {
        int sr = SAMPLE_RATE;
        for (int beat = 0; beat < 4; beat++) {
            int a = (int) (beat * spb * sr);
            double freq = beat == 0 ? 1500 : 1000;
            int len = (int) (0.03 * sr);
            for (int i = 0; i < len && a + i < left.length; i++) {
                float s = (float) (0.25 * Math.sin(2 * Math.PI * freq * i / sr) * Math.exp(-i / (0.006 * sr)));
                left[a + i] += s;
                right[a + i] += s;
            }
        }
    }

    // ------------------------------------------------------------------ reverb

    private static final int[] COMB = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
    private static final int[] ALLPASS = {556, 441, 341, 225};
    private static final int SPREAD = 23;

    private static void reverb(float[] left, float[] right, double wet) {
        // two passes over a streaming reverb (no extra full-length buffers): first measure, then mix
        float dryPeak = 1e-6f, wetPeak = 1e-6f;
        Freeverb fl = new Freeverb(0), fr = new Freeverb(SPREAD);
        for (int i = 0; i < left.length; i++) {
            float in = (left[i] + right[i]) * 0.015f;
            dryPeak = Math.max(dryPeak, Math.max(Math.abs(left[i]), Math.abs(right[i])));
            wetPeak = Math.max(wetPeak, Math.max(Math.abs(fl.process(in)), Math.abs(fr.process(in))));
        }
        float wk = (float) (wet * dryPeak / wetPeak * 1.6);
        fl = new Freeverb(0);
        fr = new Freeverb(SPREAD);
        for (int i = 0; i < left.length; i++) {
            float in = (left[i] + right[i]) * 0.015f;
            float wl = fl.process(in), wr = fr.process(in);
            left[i] = left[i] * 0.8f + wl * wk;
            right[i] = right[i] * 0.8f + wr * wk;
        }
    }

    /** Schroeder/Moorer reverb as in Freeverb: 8 damped combs, 4 allpasses. */
    private static final class Freeverb {
        private final float[][] comb = new float[COMB.length][];
        private final int[] combIdx = new int[COMB.length];
        private final float[] combStore = new float[COMB.length];
        private final float[][] ap = new float[ALLPASS.length][];
        private final int[] apIdx = new int[ALLPASS.length];
        private static final float FEEDBACK = 0.84f, DAMP = 0.25f;

        Freeverb(int spread) {
            for (int c = 0; c < COMB.length; c++) comb[c] = new float[COMB[c] + spread];
            for (int c = 0; c < ALLPASS.length; c++) ap[c] = new float[ALLPASS[c] + spread];
        }

        float process(float in) {
            float acc = 0;
            for (int c = 0; c < comb.length; c++) {
                float[] buf = comb[c];
                float y = buf[combIdx[c]];
                combStore[c] = y * (1 - DAMP) + combStore[c] * DAMP;
                buf[combIdx[c]] = in + combStore[c] * FEEDBACK;
                if (++combIdx[c] >= buf.length) combIdx[c] = 0;
                acc += y;
            }
            for (int c = 0; c < ap.length; c++) {
                float[] buf = ap[c];
                float bo = buf[apIdx[c]];
                buf[apIdx[c]] = acc + bo * 0.5f;
                acc = bo - acc;
                if (++apIdx[c] >= buf.length) apIdx[c] = 0;
            }
            return acc;
        }
    }
}
