package de.lanixx.chorstimme.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Standard MIDI file, type 1: one track per voice, choir sound, lyrics as meta events. */
public final class MidiWriter {

    private MidiWriter() {}

    private static final int PPQ = 480;

    public static byte[] write(Score score, double tempoFactor, double[] voiceGain) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int tracks = score.voices.size() + 1;
        out.write('M'); out.write('T'); out.write('h'); out.write('d');
        int32(out, 6);
        int16(out, 1);
        int16(out, tracks);
        int16(out, PPQ);

        // conductor track: tempo + title
        ByteArrayOutputStream t0 = new ByteArrayOutputStream();
        meta(t0, 0, 0x03, score.title.getBytes(StandardCharsets.UTF_8));
        int usPerQuarter = (int) Math.round(60_000_000.0 / (score.tempoBpm * tempoFactor));
        meta(t0, 0, 0x51, new byte[]{(byte) (usPerQuarter >> 16), (byte) (usPerQuarter >> 8), (byte) usPerQuarter});
        meta(t0, 0, 0x2F, new byte[0]);
        chunk(out, t0.toByteArray());

        for (int v = 0; v < score.voices.size(); v++) {
            Score.Voice voice = score.voices.get(v);
            int ch = v % 16 == 9 ? 10 : v % 16;   // skip drum channel
            double g = voiceGain != null && v < voiceGain.length ? voiceGain[v] : 1;
            int vel = (int) Math.max(1, Math.min(127, Math.round(80 * Math.min(1.5, g))));
            ByteArrayOutputStream tr = new ByteArrayOutputStream();
            meta(tr, 0, 0x03, voice.name.getBytes(StandardCharsets.UTF_8));
            varLen(tr, 0);
            tr.write(0xC0 | ch);
            tr.write(52);                       // Choir Aahs

            List<long[]> ev = new ArrayList<>();       // tick, order(0=off,1=lyric,2=on), pitch, idx
            List<String> lyr = new ArrayList<>();
            for (Score.Note n : voice.notes) {
                long on = Math.round(n.start * PPQ);
                long off = Math.max(on + 1, Math.round(n.end() * PPQ) - 4);
                if (n.syllable != null) {
                    lyr.add(n.syllable);
                    ev.add(new long[]{on, 1, 0, lyr.size() - 1});
                }
                for (int p : n.pitches) {
                    ev.add(new long[]{on, 2, p, 0});
                    ev.add(new long[]{off, 0, p, 0});
                }
            }
            ev.sort((a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));
            long last = 0;
            for (long[] e : ev) {
                long delta = e[0] - last;
                last = e[0];
                if (e[1] == 1) {
                    meta(tr, delta, 0x05, lyr.get((int) e[3]).getBytes(StandardCharsets.UTF_8));
                } else {
                    varLen(tr, delta);
                    tr.write((e[1] == 2 ? 0x90 : 0x80) | ch);
                    tr.write((int) e[2] & 0x7F);
                    tr.write(e[1] == 2 ? vel : 0);
                }
            }
            meta(tr, 0, 0x2F, new byte[0]);
            chunk(out, tr.toByteArray());
        }
        return out.toByteArray();
    }

    private static void meta(ByteArrayOutputStream o, long delta, int type, byte[] data) {
        varLen(o, delta);
        o.write(0xFF);
        o.write(type);
        varLen(o, data.length);
        o.write(data, 0, data.length);
    }

    private static void chunk(ByteArrayOutputStream out, byte[] data) {
        out.write('M'); out.write('T'); out.write('r'); out.write('k');
        int32(out, data.length);
        out.write(data, 0, data.length);
    }

    private static void varLen(ByteArrayOutputStream o, long v) {
        long buf = v & 0x7F;
        while ((v >>= 7) > 0) {
            buf <<= 8;
            buf |= ((v & 0x7F) | 0x80);
        }
        while (true) {
            o.write((int) (buf & 0xFF));
            if ((buf & 0x80) != 0) buf >>= 8;
            else break;
        }
    }

    private static void int32(ByteArrayOutputStream o, int v) {
        o.write(v >>> 24); o.write(v >>> 16); o.write(v >>> 8); o.write(v);
    }

    private static void int16(ByteArrayOutputStream o, int v) {
        o.write(v >>> 8); o.write(v);
    }
}
