package de.lanixx.chorstimme.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Writes a {@link Score} as ABC notation (abcjs compatible): one V: per voice, bars and lines
 * as recognised, key signature estimated from the notes, lyrics as w: lines.
 */
public final class AbcWriter {

    private AbcWriter() {}

    private static final double UNIT = 0.5;            // L:1/8 = half a beat
    private static final double EPS = 1e-6;

    public static String write(Score score) {
        StringBuilder out = new StringBuilder();
        tupletLeft = 0;
        int fifths = estimateKey(score);
        List<Double> bars = cleanBars(score);
        double end = score.end();
        // metre: the most common bar length. Free rhythm (chant, many different bar lengths)
        // gets M:none; otherwise metre changes are written where a bar has another whole-beat length.
        List<Double> lens = new ArrayList<>();
        double prevB = 0;
        for (double b : bars) { lens.add(b - prevB); prevB = b; }
        lens.add(end - prevB);
        Map<Long, Integer> count = new HashMap<>();
        for (double l : lens) count.merge(Math.round(l * 8), 1, Integer::sum);
        long common = 0;
        int cmax = 0;
        for (Map.Entry<Long, Integer> e : count.entrySet()) if (e.getValue() > cmax) { cmax = e.getValue(); common = e.getKey(); }
        double meter = common / 8.0;
        boolean freeRhythm = lens.size() >= 3 && cmax < 0.6 * lens.size();
        if (!meterOk(meter)) freeRhythm = true;

        out.append("X:1\n");
        out.append("T:").append(score.title == null || score.title.isEmpty() ? "Chorstimme" : oneLine(score.title)).append('\n');
        out.append("M:").append(freeRhythm ? "none" : meterText(meter)).append('\n');
        out.append("L:1/8\n");
        out.append("Q:1/4=").append(Math.round(score.tempoBpm)).append('\n');
        int nv = score.voices.size();
        if (nv == 4) out.append("%%score (1 2) (3 4)\n");
        else if (nv == 2) out.append("%%score (1 2)\n");
        // voices sharing a staff (%%score groups) need the same clef
        String[] clefs = new String[nv];
        for (int v = 0; v < nv; v++) {
            List<Score.Voice> group = new ArrayList<>();
            int g0 = (nv == 4 || nv == 2) ? v - v % 2 : v;
            int g1 = (nv == 4 || nv == 2) ? g0 + 1 : v;
            for (int k = g0; k <= g1; k++) group.add(score.voices.get(k));
            clefs[v] = clefFor(group);
        }
        for (int v = 0; v < nv; v++) {
            Score.Voice voice = score.voices.get(v);
            out.append("V:").append(v + 1).append(" clef=").append(clefs[v])
                    .append(" name=\"").append(oneLine(voice.name).replace("\"", "'")).append("\"\n");
        }
        out.append("K:").append(keyName(fifths)).append('\n');

        // music lines: as printed, else every 4 bars
        List<Double> lineStarts = new ArrayList<>(score.lineStarts);
        if (lineStarts.size() < 2) {
            lineStarts.clear();
            lineStarts.add(0.0);
            for (int i = 3; i < bars.size(); i += 4) lineStarts.add(bars.get(i));
        }
        if (lineStarts.isEmpty() || lineStarts.get(0) > EPS) lineStarts.add(0, 0.0);

        Map<Score.Voice, Integer> cursor = new HashMap<>();
        double curMeter = meter;
        for (int li = 0; li < lineStarts.size(); li++) {
            double from = lineStarts.get(li);
            double to = li + 1 < lineStarts.size() ? lineStarts.get(li + 1) : end;
            if (to <= from + EPS) continue;
            // bar boundaries inside this line
            List<Double> cuts = new ArrayList<>();
            for (double b : bars) if (b > from + EPS && b < to - EPS) cuts.add(b);
            cuts.add(to);
            String meterChange = "";
            double prev = from;
            List<String> meters = new ArrayList<>();
            for (double c : cuts) {
                double len = c - prev;
                if (!freeRhythm && meterOk(len) && Math.abs(len - curMeter) > EPS) {
                    curMeter = len;
                    meters.add("[M:" + meterText(len) + "]");
                } else {
                    meters.add("");
                }
                prev = c;
            }
            // a metre change in the first bar of a line is written as an own M: line:
            // abcjs up to 6.4 crashes on an inline [M:] right after [V:] at a line start
            if (!meters.isEmpty() && !meters.get(0).isEmpty()) {
                out.append(meters.get(0), 1, meters.get(0).length() - 1).append('\n');
                meters.set(0, "");
            }
            for (int v = 0; v < nv; v++) {
                Score.Voice voice = score.voices.get(v);
                StringBuilder music = new StringBuilder();
                List<String> lyr = new ArrayList<>();
                music.append("[V:").append(v + 1).append("] ");
                tupletLeft = 0;
                double t = from;
                int[] state = keyState(fifths);
                Map<Integer, Integer> barAcc = new HashMap<>();
                for (int ci = 0; ci < cuts.size(); ci++) {
                    double barEnd = cuts.get(ci);
                    if (!meters.get(ci).isEmpty()) music.append(meters.get(ci)).append(' ');
                    barAcc.clear();
                    while (t < barEnd - EPS) {
                        Score.Note n = noteAt(voice, t, cursor);
                        if (n == null) {
                            // rest until the next note or the bar line
                            double next = nextStart(voice, t);
                            double r = Math.min(barEnd, next) - t;
                            appendLength(music, "z", r, false);
                            t += r;
                            continue;
                        }
                        double nEnd = Math.min(n.end(), barEnd);
                        boolean continued = n.start < t - EPS;          // tied over from the last bar
                        String pitch = pitches(n.pitches, fifths, barAcc);
                        boolean tieOut = n.end() > barEnd + EPS;
                        appendLength(music, pitch, nEnd - t, tieOut);
                        if (!continued) {
                            if (n.syllable != null) lyr.add(lyricToken(n.syllable));
                            else lyr.add(lyr.isEmpty() ? "*" : "_");
                        }
                        t = nEnd;
                    }
                    music.append(ci + 1 < cuts.size() ? " | " : (li + 1 < lineStarts.size() ? " |" : " |]"));
                }
                out.append(music.toString().replaceAll(" +", " ").trim()).append('\n');
                boolean any = false;
                for (String s : lyr) if (!s.equals("*") && !s.equals("_")) any = true;
                if (any) out.append("w: ").append(joinLyrics(lyr)).append('\n');
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------ timing helpers

    private static List<Double> cleanBars(Score score) {
        List<Double> b = new ArrayList<>();
        double end = score.end();
        List<Double> src = new ArrayList<>(score.barTimes);
        java.util.Collections.sort(src);
        for (double x : src) {
            if (x <= EPS || x >= end - EPS) continue;
            if (!b.isEmpty() && x - b.get(b.size() - 1) < EPS) continue;
            b.add(x);
        }
        return b;
    }

    private static Score.Note noteAt(Score.Voice v, double t, Map<Score.Voice, Integer> cursor) {
        for (Score.Note n : v.notes) {
            if (n.start <= t + EPS && n.end() > t + EPS) return n;
            if (n.start > t + EPS) break;
        }
        return null;
    }

    private static double nextStart(Score.Voice v, double t) {
        for (Score.Note n : v.notes) if (n.start > t + EPS) return n.start;
        return Double.MAX_VALUE;
    }

    private static int tupletLeft;

    /** Writes a note/rest of the given length, split into notatable values joined by ties. */
    private static void appendLength(StringBuilder sb, String sym, double beats, boolean tieAfter) {
        double units = beats / UNIT;
        // triplets: a length that is 2/3 of a plain value is written as (3 with the plain value
        if (tupletLeft == 0 && !single(units) && single(units * 1.5)) {
            sb.append("(3");
            tupletLeft = 3;
        }
        if (tupletLeft > 0) {
            tupletLeft--;
            sb.append(sym).append(lengthText(units * 1.5));
            if (tieAfter && !sym.startsWith("z")) sb.append('-');
            sb.append(' ');
            return;
        }
        List<Double> parts = decompose(units);
        for (int i = 0; i < parts.size(); i++) {
            sb.append(sym).append(lengthText(parts.get(i)));
            boolean tie = !sym.startsWith("z") && (i + 1 < parts.size() || tieAfter);
            if (tie) sb.append('-');
            sb.append(' ');
        }
    }

    private static boolean single(double units) {
        List<Double> d = decompose(units);
        return d.size() == 1 && Math.abs(d.get(0) - units) < 1e-4 && Math.abs(units * 64 - Math.rint(units * 64)) < 1e-3;
    }

    /** Splits a length (in 1/8 units) into plain and dotted values, largest first. */
    static List<Double> decompose(double units) {
        List<Double> allowed = new ArrayList<>();
        for (int k = -3; k <= 4; k++) {
            double p = Math.pow(2, k);
            allowed.add(p * 1.5);
            allowed.add(p);
        }
        allowed.sort((a, b) -> Double.compare(b, a));
        List<Double> out = new ArrayList<>();
        double rest = units;
        int guard = 0;
        while (rest > 1e-4 && guard++ < 16) {
            double pick = -1;
            for (double a : allowed) if (a <= rest + 1e-4) { pick = a; break; }
            if (pick < 0) {
                out.add(rest);         // odd remainder (tuplets): written as a fraction
                break;
            }
            out.add(pick);
            rest -= pick;
        }
        if (out.isEmpty()) out.add(units);
        return out;
    }

    static String lengthText(double u) {
        if (Math.abs(u - 1) < 1e-4) return "";
        if (Math.abs(u - Math.rint(u)) < 1e-4) return String.valueOf((int) Math.rint(u));
        for (int den : new int[]{2, 4, 8, 16, 3, 6, 12, 24}) {
            double num = u * den;
            if (Math.abs(num - Math.rint(num)) < 1e-3) {
                long nn = Math.round(num);
                return (nn == 1 ? "" : String.valueOf(nn)) + "/" + den;
            }
        }
        return String.format(Locale.ROOT, "%d/%d", Math.round(u * 48), 48);
    }

    /** a bar length that reads as a normal time signature (whole beats, 2..12) */
    private static boolean meterOk(double beats) {
        double r = Math.rint(beats);
        return Math.abs(beats - r) < EPS && r >= 2 && r <= 12;
    }

    private static String meterText(double beats) {
        if (beats <= EPS) return "none";
        double q = Math.rint(beats);
        if (Math.abs(beats - q) < EPS) return (int) q + "/4";
        double e = beats * 2;
        if (Math.abs(e - Math.rint(e)) < EPS) return (int) Math.rint(e) + "/8";
        return "none";
    }

    // ------------------------------------------------------------ pitch spelling

    private static final int[] NATURAL = {0, 2, 4, 5, 7, 9, 11};      // C D E F G A B
    private static final int[] SHARP_ORDER = {3, 0, 4, 1, 5, 2, 6};
    private static final int[] FLAT_ORDER = {6, 2, 5, 1, 4, 0, 3};
    private static final String[] MAJOR = {"Cb", "Gb", "Db", "Ab", "Eb", "Bb", "F", "C", "G", "D", "A", "E", "B", "F#", "C#"};

    private static String keyName(int fifths) {
        return MAJOR[fifths + 7];
    }

    /** alteration per letter in the key signature */
    private static int[] keyState(int fifths) {
        int[] a = new int[7];
        for (int i = 0; i < Math.abs(fifths); i++) a[fifths > 0 ? SHARP_ORDER[i] : FLAT_ORDER[i]] = fifths > 0 ? 1 : -1;
        return a;
    }

    /** Major key (-7..7 fifths) whose scale covers most of the sounding time. */
    static int estimateKey(Score score) {
        double[] pc = new double[12];
        for (Score.Voice v : score.voices) for (Score.Note n : v.notes) for (int p : n.pitches) pc[Math.floorMod(p, 12)] += n.duration;
        int best = 0;
        double bestScore = -1;
        for (int f = -6; f <= 6; f++) {
            int[] st = keyState(f);
            double s = 0;
            for (int l = 0; l < 7; l++) s += pc[Math.floorMod(NATURAL[l] + st[l], 12)];
            if (s > bestScore + 1e-6 || Math.abs(s - bestScore) < 1e-6 && Math.abs(f) < Math.abs(best)) {
                bestScore = s;
                best = f;
            }
        }
        return best;
    }

    /** letter (0..6) and alteration for a MIDI pitch in a key */
    static int[] spell(int midi, int fifths) {
        int pc = Math.floorMod(midi, 12);
        int[] st = keyState(fifths);
        for (int l = 0; l < 7; l++) if (Math.floorMod(NATURAL[l] + st[l], 12) == pc) return new int[]{l, st[l]};
        // chromatic note: spell by its degree above the tonic (b2, b3, #4, #5, b7 – the usual
        // alterations, incl. the raised 6th/7th of the relative minor)
        int tonic = Math.floorMod(7 * fifths, 12);
        int rel = Math.floorMod(pc - tonic, 12);
        boolean flat = rel == 1 || rel == 3 || rel == 10;
        for (int l = 0; l < 7; l++) {
            int scalePc = Math.floorMod(NATURAL[l] + st[l], 12);
            if (flat && Math.floorMod(scalePc - 1, 12) == pc) return new int[]{l, st[l] - 1};
            if (!flat && Math.floorMod(scalePc + 1, 12) == pc) return new int[]{l, st[l] + 1};
        }
        for (int l = 0; l < 7; l++) if (NATURAL[l] == pc) return new int[]{l, 0};
        for (int l = 0; l < 7; l++) if (Math.floorMod(NATURAL[l] + 1, 12) == pc) return new int[]{l, 1};
        return new int[]{0, 0};
    }

    private static String pitches(int[] ps, int fifths, Map<Integer, Integer> barAcc) {
        if (ps.length == 1) return pitch(ps[0], fifths, barAcc);
        StringBuilder sb = new StringBuilder("[");
        for (int p : ps) sb.append(pitch(p, fifths, barAcc));
        return sb.append(']').toString();
    }

    private static String pitch(int midi, int fifths, Map<Integer, Integer> barAcc) {
        int[] la = spell(midi, fifths);
        int letter = la[0], alter = la[1];
        int natural = midi - alter;
        int octave = Math.floorDiv(natural, 12) - 1;
        int d = octave * 7 + letter;
        int current = barAcc.containsKey(d) ? barAcc.get(d) : keyState(fifths)[letter];
        StringBuilder sb = new StringBuilder();
        if (alter != current) {
            sb.append(alter == 1 ? "^" : alter == -1 ? "_" : alter == 2 ? "^^" : alter == -2 ? "__" : "=");
            barAcc.put(d, alter);
        }
        char c = "CDEFGAB".charAt(letter);
        if (octave >= 5) {
            sb.append(Character.toLowerCase(c));
            for (int i = 5; i < octave; i++) sb.append('\'');
        } else {
            sb.append(c);
            for (int i = octave; i < 4; i++) sb.append(',');
        }
        return sb.toString();
    }

    private static String clefFor(List<Score.Voice> vs) {
        double sum = 0, n = 0;
        for (Score.Voice v : vs) for (Score.Note x : v.notes) for (int p : x.pitches) { sum += p * x.duration; n += x.duration; }
        double avg = n > 0 ? sum / n : 65;
        return avg < 57 ? "bass" : "treble";
    }

    // ------------------------------------------------------------ lyrics

    private static String lyricToken(String syl) {
        String s = syl.trim().replace(' ', '~');
        s = s.replace("_", "").replace("*", "").replace("|", "");
        if (s.isEmpty()) return "*";
        return s;
    }

    /** Syllables ending in '-' are joined to the next one without a space. */
    private static String joinLyrics(List<String> tokens) {
        StringBuilder sb = new StringBuilder();
        for (String t : tokens) {
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') sb.append(' ');
            sb.append(t);
        }
        return sb.toString();
    }

    private static String oneLine(String s) {
        return s.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
