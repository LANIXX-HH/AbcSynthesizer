package de.lanixx.chorstimme.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for the ABC notation subset used in choral scores: header fields, several voices
 * (V: lines and inline [V:...]), notes, rests, chords, ties, tuplets, broken rhythm,
 * accidentals with key signatures, simple repeats and lyrics (w:).
 * Only the first tune (X:) of a file is read.
 */
public final class AbcParser {

    private AbcParser() {}

    private static final class Tok {
        double start, dur;
        int[] pitches;      // null = rest
        boolean tie;
        boolean barAfter;
        String syllable;
        Tok(double start, double dur, int[] pitches) {
            this.start = start; this.dur = dur; this.pitches = pitches;
        }
    }

    private static final class VoiceState {
        String id, name;
        final List<Tok> toks = new ArrayList<>();
        double time;
        int[] keyAlter = new int[7];
        final Map<Integer, Integer> barAlter = new HashMap<>();
        int lyricLineStart = 0;      // first token of the current music line (for w:)
        int lyricCursor = -1;        // next token index for lyrics; -1 = start at lyricLineStart
        List<Tok> lastLineToks = new ArrayList<>();
        int octaveShift = 0;         // from octave= / clef=treble-8 in V: line
        boolean clef8;
        // repeats
        int repeatStart = 0;
        boolean inEnding1;
        int ending1Start = -1;
    }

    public static Score parse(String text) {
        Score score = new Score();
        String[] lines = text.replace("\r", "").split("\n");
        double unit = -1;            // L:
        String meter = "4/4";
        int[] globalKey = new int[7];
        Map<String, VoiceState> voices = new LinkedHashMap<>();
        VoiceState cur = null;
        boolean inTune = false, headerDone = false;
        int tunes = 0;

        for (String raw : lines) {
            String line = stripComment(raw);
            if (line.trim().isEmpty()) {
                if (headerDone && inTune) {
                    // blank line ends the tune
                    if (!voices.isEmpty()) break;
                }
                continue;
            }
            if (line.startsWith("%%")) continue;
            Matcher field = Pattern.compile("^([A-Za-z\\+]):\\s?(.*)$").matcher(line);
            if (field.matches() && !(headerDone && line.startsWith("|"))) {
                char f = field.group(1).charAt(0);
                String val = field.group(2).trim();
                switch (f) {
                    case 'X':
                        tunes++;
                        if (tunes > 1) return finish(score, voices);
                        inTune = true;
                        continue;
                    case 'T':
                        if (score.title.isEmpty()) score.title = val;
                        continue;
                    case 'L':
                        unit = fraction(val, 0.125) * 4;
                        continue;
                    case 'M':
                        meter = val;
                        continue;
                    case 'Q':
                        score.tempoBpm = tempo(val, score.tempoBpm);
                        continue;
                    case 'K': {
                        int[] k = key(val);
                        if (!headerDone) {
                            globalKey = k;
                            headerDone = true;
                            for (VoiceState v : voices.values()) v.keyAlter = k.clone();
                        } else if (cur != null) {
                            cur.keyAlter = k;
                        } else {
                            globalKey = k;
                        }
                        continue;
                    }
                    case 'V': {
                        VoiceState v = voice(voices, val, globalKey);
                        if (headerDone) cur = v;
                        continue;
                    }
                    case 'w':
                        if (cur != null) applyLyrics(cur, val);
                        continue;
                    case 'W':
                        continue;
                    default:
                        continue;      // C:, R:, N:, S:, ...
                }
            }
            if (!headerDone) continue;   // music before K: is ignored
            if (unit < 0) unit = defaultUnit(meter);
            if (cur == null) cur = voice(voices, "1", globalKey);
            cur = music(line, cur, voices, globalKey, unit, meter, score);
        }
        return finish(score, voices);
    }

    private static Score finish(Score score, Map<String, VoiceState> voices) {
        for (VoiceState v : voices.values()) {
            Score.Voice sv = new Score.Voice(v.name != null ? v.name : "Stimme " + v.id);
            Score.Note prev = null;
            Tok prevTok = null;
            for (Tok t : v.toks) {
                if (t.pitches == null) { prev = null; prevTok = null; continue; }
                if (prev != null && prevTok.tie && overlap(prevTok.pitches, t.pitches)
                        && Math.abs(prev.end() - t.start) < 1e-6) {
                    prev.duration += t.dur;
                    prevTok = t;
                    if (prev.syllable == null) prev.syllable = t.syllable;
                    continue;
                }
                Score.Note n = new Score.Note(t.start, t.dur, t.pitches);
                n.syllable = t.syllable;
                sv.notes.add(n);
                prev = n;
                prevTok = t;
            }
            if (!sv.notes.isEmpty()) score.voices.add(sv);
        }
        if (score.voices.isEmpty()) score.warnings.add("Keine Noten im ABC-Text gefunden.");
        score.assignVowels();
        return score;
    }

    private static boolean overlap(int[] a, int[] b) {
        for (int x : a) for (int y : b) if (x == y) return true;
        return false;
    }

    private static String stripComment(String s) {
        int i = s.indexOf('%');
        while (i > 0 && s.charAt(i - 1) == '\\') i = s.indexOf('%', i + 1);
        if (s.startsWith("%%")) return s;
        return i >= 0 ? s.substring(0, i) : s;
    }

    private static double defaultUnit(String meter) {
        double m = 1;
        if (meter.equals("C") || meter.equals("C|")) m = 1;
        else if (meter.equalsIgnoreCase("none")) m = 1;
        else m = fraction(meter, 1);
        return m < 0.75 ? 0.25 : 0.5;   // in beats (quarter = 1)
    }

    private static double fraction(String s, double def) {
        Matcher m = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)").matcher(s);
        if (m.find()) return Double.parseDouble(m.group(1)) / Double.parseDouble(m.group(2));
        return def;
    }

    private static double tempo(String val, double def) {
        Matcher m = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)\\s*=\\s*(\\d+)").matcher(val);
        if (m.find()) {
            double note = Double.parseDouble(m.group(1)) / Double.parseDouble(m.group(2));
            return Double.parseDouble(m.group(3)) * note * 4;   // in quarters per minute
        }
        m = Pattern.compile("(?:^|\\s)(\\d+)(?:\\s|$)").matcher(val);
        if (m.find()) return Double.parseDouble(m.group(1));
        return def;
    }

    private static VoiceState voice(Map<String, VoiceState> voices, String val, int[] key) {
        String id = val.split("\\s+")[0];
        VoiceState v = voices.get(id);
        if (v == null) {
            v = new VoiceState();
            v.id = id;
            v.keyAlter = key.clone();
            voices.put(id, v);
        }
        Matcher nm = Pattern.compile("(?:name|nm)\\s*=\\s*\"([^\"]*)\"").matcher(val);
        if (nm.find()) v.name = nm.group(1);
        else if (v.name == null) {
            Matcher nm2 = Pattern.compile("(?:name|nm)\\s*=\\s*(\\S+)").matcher(val);
            if (nm2.find()) v.name = nm2.group(1);
        }
        Matcher oc = Pattern.compile("octave\\s*=\\s*(-?\\d)").matcher(val);
        if (oc.find()) v.octaveShift = Integer.parseInt(oc.group(1));
        if (val.matches(".*clef\\s*=\\s*(treble|tenor)-8.*") && !v.clef8) {
            // tenor clef: written in treble clef, sung an octave lower
            v.octaveShift -= 1;
            v.clef8 = true;
        }
        return v;
    }

    // --------------------------------------------------------------- keys

    private static final String[] MAJOR_SHARPS = {"C", "G", "D", "A", "E", "B", "F#", "C#"};
    private static final String[] MAJOR_FLATS = {"C", "F", "Bb", "Eb", "Ab", "Db", "Gb", "Cb"};
    private static final int[] SHARP_ORDER = {3, 0, 4, 1, 5, 2, 6};   // F C G D A E B
    private static final int[] FLAT_ORDER = {6, 2, 5, 1, 4, 0, 3};    // B E A D G C F

    static int[] key(String val) {
        int[] alter = new int[7];
        String v = val.trim();
        if (v.isEmpty() || v.toLowerCase().startsWith("none")) return alter;
        Matcher m = Pattern.compile("^([A-Ga-g])([#b]?)\\s*([A-Za-z]*)").matcher(v);
        if (!m.find()) return alter;
        String tonic = m.group(1).toUpperCase() + m.group(2);
        String mode = m.group(3).toLowerCase();
        int shift = 0;   // steps on the circle of fifths relative to major
        if (mode.startsWith("m") && !mode.startsWith("maj") && !mode.startsWith("mix")) shift = -3;   // minor
        else if (mode.startsWith("aeo")) shift = -3;
        else if (mode.startsWith("dor")) shift = -2;
        else if (mode.startsWith("phr")) shift = -4;
        else if (mode.startsWith("lyd")) shift = 1;
        else if (mode.startsWith("mix")) shift = -1;
        else if (mode.startsWith("loc")) shift = -5;
        int fifths = fifthsOfMajor(tonic) + shift;
        if (fifths > 0) for (int i = 0; i < Math.min(7, fifths); i++) alter[SHARP_ORDER[i]] = 1;
        if (fifths < 0) for (int i = 0; i < Math.min(7, -fifths); i++) alter[FLAT_ORDER[i]] = -1;
        // explicit accidentals: K:D ^g _b =f
        Matcher acc = Pattern.compile("(\\^|_|=)([A-Ga-g])").matcher(v.substring(m.end()));
        while (acc.find()) {
            int letter = "CDEFGAB".indexOf(Character.toUpperCase(acc.group(2).charAt(0)));
            alter[letter] = acc.group(1).equals("^") ? 1 : acc.group(1).equals("_") ? -1 : 0;
        }
        return alter;
    }

    private static int fifthsOfMajor(String tonic) {
        for (int i = 0; i < MAJOR_SHARPS.length; i++) if (MAJOR_SHARPS[i].equals(tonic)) return i;
        for (int i = 0; i < MAJOR_FLATS.length; i++) if (MAJOR_FLATS[i].equals(tonic)) return -i;
        // minor tonics etc. are covered by the mode shift; unusual spellings:
        switch (tonic) {
            case "G#": return 8;
            case "D#": return 9;
            case "A#": return 10;
            case "Fb": return -8;
            default: return 0;
        }
    }

    // --------------------------------------------------------------- music

    private static VoiceState music(String line, VoiceState cur, Map<String, VoiceState> voices,
                                    int[] globalKey, double unit, String meter, Score score) {
        int i = 0, n = line.length();
        if (voices.values().iterator().next() == cur
                && (score.lineStarts.isEmpty() || cur.time > score.lineStarts.get(score.lineStarts.size() - 1) + 1e-6)) {
            score.lineStarts.add(cur.time);
        }
        cur.lyricLineStart = cur.toks.size();
        cur.lyricCursor = -1;
        double tupletFactor = 1;
        int tupletLeft = 0;
        List<Tok> pendingBroken = null;
        char broken = 0;
        int brokenCount = 0;
        Tok lastNote = null;

        while (i < n) {
            char c = line.charAt(i);
            // inline field [V:..] [K:..] [L:..]
            if (c == '[' && i + 2 < n && Character.isLetter(line.charAt(i + 1)) && line.charAt(i + 2) == ':') {
                int end = line.indexOf(']', i);
                if (end < 0) end = n;
                char f = line.charAt(i + 1);
                String val = line.substring(i + 3, end).trim();
                if (f == 'V') {
                    cur = voice(voices, val, globalKey);
                    cur.lyricLineStart = cur.toks.size();
                    cur.lyricCursor = -1;
                    lastNote = null;
                } else if (f == 'K') {
                    cur.keyAlter = key(val);
                } else if (f == 'Q') {
                    score.tempoBpm = tempo(val, score.tempoBpm);
                }
                i = end + 1;
                continue;
            }
            if (c == '"') {                       // chord symbol / annotation
                int end = line.indexOf('"', i + 1);
                i = end < 0 ? n : end + 1;
                continue;
            }
            if (c == '!' || c == '+') {           // decoration
                int end = line.indexOf(c, i + 1);
                i = end < 0 ? n : end + 1;
                continue;
            }
            if (c == '{') {                       // grace notes: skip
                int end = line.indexOf('}', i);
                i = end < 0 ? n : end + 1;
                continue;
            }
            if (c == '(' && i + 1 < n && Character.isDigit(line.charAt(i + 1))) {
                int p = line.charAt(i + 1) - '0';
                int q = p == 2 ? 3 : p == 3 ? 2 : p == 4 ? 3 : p == 6 ? 2 : p == 8 ? 3 : 2;
                int r = p;
                i += 2;
                // (p:q:r
                if (i < n && line.charAt(i) == ':') {
                    i++;
                    int s = i;
                    while (i < n && Character.isDigit(line.charAt(i))) i++;
                    if (i > s) q = Integer.parseInt(line.substring(s, i));
                    if (i < n && line.charAt(i) == ':') {
                        i++;
                        s = i;
                        while (i < n && Character.isDigit(line.charAt(i))) i++;
                        if (i > s) r = Integer.parseInt(line.substring(s, i));
                    }
                }
                tupletFactor = (double) q / p;
                tupletLeft = r;
                continue;
            }
            if (c == '|' || c == ':' || (c == '[' && i + 1 < n && (line.charAt(i + 1) == '|'
                    || Character.isDigit(line.charAt(i + 1))))) {
                // bar line, repeats, endings
                int s = i;
                while (i < n && "|:[]".indexOf(line.charAt(i)) >= 0) i++;
                String bar = line.substring(s, i);
                int ending = 0;
                if (i < n && Character.isDigit(line.charAt(i)) && (bar.endsWith("[") || bar.endsWith("|"))) {
                    ending = line.charAt(i) - '0';
                    while (i < n && (Character.isDigit(line.charAt(i)) || line.charAt(i) == ',' || line.charAt(i) == '-')) i++;
                }
                if (bar.isEmpty() && c == '[') { i++; continue; }
                cur.barAlter.clear();
                if (!cur.toks.isEmpty()) cur.toks.get(cur.toks.size() - 1).barAfter = true;
                if (voices.values().iterator().next() == cur && cur.time > 0
                        && (score.barTimes.isEmpty() || cur.time > score.barTimes.get(score.barTimes.size() - 1) + 1e-6)) {
                    score.barTimes.add(cur.time);
                }
                if (bar.startsWith(":")) repeatBack(cur);
                if (bar.endsWith(":")) cur.repeatStart = cur.toks.size();
                if (ending == 1) { cur.inEnding1 = true; cur.ending1Start = cur.toks.size(); }
                else if (ending == 2 && cur.ending1Start >= 0) { cur.inEnding1 = false; }
                continue;
            }
            if (c == '-' && lastNote != null) {   // tie
                lastNote.tie = true;
                i++;
                continue;
            }
            if (c == '>' || c == '<') {
                int cnt = 0;
                while (i < n && line.charAt(i) == c) { cnt++; i++; }
                if (lastNote != null) {
                    broken = c;
                    brokenCount = cnt;
                    pendingBroken = new ArrayList<>();
                    pendingBroken.add(lastNote);
                }
                continue;
            }
            if (c == 'y' || c == ' ' || c == '\t' || c == '`' || c == ')' || c == '(' || c == '.'
                    || c == '~' || c == 'H' || c == 'L' || c == 'M' || c == 'O' || c == 'P' || c == 'S'
                    || c == 'T' || c == 'u' || c == 'v' || c == '\\' || c == ']' || c == '&') {
                i++;
                continue;
            }

            // note, rest or chord
            int[] pitches = null;
            boolean isRest = false;
            int startI = i;
            double lenMul;
            if (c == '[') {                       // chord
                int end = line.indexOf(']', i);
                if (end < 0) end = n;
                List<Integer> ps = new ArrayList<>();
                int j = i + 1;
                double chordLen = -1;
                while (j < end) {
                    int[] r = new int[2];
                    double[] len = new double[1];
                    int nj = parseNote(line, j, end, cur, r, len);
                    if (nj <= j) { j++; continue; }
                    if (r[1] == 1) ps.add(r[0]);
                    if (chordLen < 0) chordLen = len[0];
                    j = nj;
                }
                i = end + 1;
                double[] outer = new double[1];
                i = parseLength(line, i, outer);
                lenMul = (chordLen < 0 ? 1 : chordLen) * outer[0];
                if (ps.isEmpty()) continue;
                pitches = new int[ps.size()];
                for (int k = 0; k < ps.size(); k++) pitches[k] = ps.get(k);
            } else if (c == 'z' || c == 'x' || c == 'Z' || c == 'X') {
                isRest = true;
                i++;
                double[] len = new double[1];
                i = parseLength(line, i, len);
                lenMul = len[0];
                if (c == 'Z' || c == 'X') {
                    double bar = fraction(meter, 1) * 4;
                    lenMul = lenMul * bar / unit;
                }
            } else if ("^_=ABCDEFGabcdefg".indexOf(c) >= 0) {
                int[] r = new int[2];
                double[] len = new double[1];
                int ni = parseNote(line, i, n, cur, r, len);
                if (ni <= i) { i++; continue; }
                i = ni;
                pitches = new int[]{r[0]};
                lenMul = len[0];
            } else {
                i++;
                continue;
            }
            if (i == startI) i++;
            double dur = lenMul * unit;
            if (tupletLeft > 0) {
                dur *= tupletFactor;
                tupletLeft--;
                if (tupletLeft == 0) tupletFactor = 1;
            }
            Tok t = new Tok(cur.time, dur, isRest ? null : pitches);
            // broken rhythm with the previous note
            if (pendingBroken != null) {
                Tok a = pendingBroken.get(0);
                double f = brokenCount == 1 ? 0.5 : brokenCount == 2 ? 0.75 : 0.875;
                double total = a.dur + t.dur;
                double half = total / 2;
                if (broken == '>') { a.dur = half * (1 + f); t.dur = half * (1 - f); }
                else { a.dur = half * (1 - f); t.dur = half * (1 + f); }
                t.start = a.start + a.dur;
                cur.time = t.start;
                pendingBroken = null;
            }
            cur.toks.add(t);
            cur.time = t.start + t.dur;
            if (!isRest) lastNote = t;
            else lastNote = null;
        }
        return cur;
    }

    /** Copies the tokens of a repeated section (|: ... :|) once more. */
    private static void repeatBack(VoiceState v) {
        int from = v.repeatStart;
        int to = v.inEnding1 && v.ending1Start >= 0 ? v.ending1Start : v.toks.size();
        List<Tok> copy = new ArrayList<>();
        double offset = v.time - (from < v.toks.size() ? v.toks.get(from).start : v.time);
        for (int k = from; k < to; k++) {
            Tok o = v.toks.get(k);
            Tok t = new Tok(o.start + offset, o.dur, o.pitches);
            t.tie = o.tie;
            t.syllable = o.syllable;
            copy.add(t);
        }
        // shift: copied section starts now
        double start = v.time;
        double base = copy.isEmpty() ? start : copy.get(0).start;
        for (Tok t : copy) t.start = t.start - base + start;
        v.toks.addAll(copy);
        if (!copy.isEmpty()) v.time = copy.get(copy.size() - 1).start + copy.get(copy.size() - 1).dur;
        v.inEnding1 = false;
        v.ending1Start = -1;
        v.repeatStart = v.toks.size();
    }

    /** Parses one note at i: accidentals, letter, octave marks, length. r = {midi, 1}. */
    private static int parseNote(String s, int i, int end, VoiceState v, int[] r, double[] len) {
        int n = Math.min(end, s.length());
        int alter = Integer.MIN_VALUE;
        while (i < n && "^_=".indexOf(s.charAt(i)) >= 0) {
            char a = s.charAt(i);
            int val = a == '^' ? 1 : a == '_' ? -1 : 0;
            alter = alter == Integer.MIN_VALUE || a == '=' ? val : alter + val;
            i++;
        }
        if (i >= n) return i;
        char c = s.charAt(i);
        int letter = "CDEFGAB".indexOf(Character.toUpperCase(c));
        if (letter < 0) return i;
        int octave = Character.isLowerCase(c) ? 5 : 4;
        i++;
        while (i < n && (s.charAt(i) == '\'' || s.charAt(i) == ',')) {
            octave += s.charAt(i) == '\'' ? 1 : -1;
            i++;
        }
        octave += v.octaveShift;
        int d = octave * 7 + letter;
        int acc;
        if (alter != Integer.MIN_VALUE) {
            acc = alter;
            v.barAlter.put(d, alter);
        } else if (v.barAlter.containsKey(d)) {
            acc = v.barAlter.get(d);
        } else {
            acc = v.keyAlter[letter];
        }
        int[] semi = {0, 2, 4, 5, 7, 9, 11};
        r[0] = 12 * (octave + 1) + semi[letter] + acc;
        r[1] = 1;
        return parseLength(s, i, len);
    }

    private static int parseLength(String s, int i, double[] out) {
        int n = s.length();
        int st = i;
        while (i < n && Character.isDigit(s.charAt(i))) i++;
        double num = i > st ? Double.parseDouble(s.substring(st, i)) : 1;
        double den = 1;
        if (i < n && s.charAt(i) == '/') {
            int slashes = 0;
            while (i < n && s.charAt(i) == '/') { slashes++; i++; }
            int ds = i;
            while (i < n && Character.isDigit(s.charAt(i))) i++;
            if (i > ds) den = Double.parseDouble(s.substring(ds, i));
            else den = Math.pow(2, slashes);
        }
        out[0] = num / den;
        return i;
    }

    // --------------------------------------------------------------- lyrics

    private static void applyLyrics(VoiceState v, String text) {
        List<Tok> notes = new ArrayList<>();
        for (int k = v.lyricLineStart; k < v.toks.size(); k++) {
            Tok t = v.toks.get(k);
            if (t.pitches == null) continue;
            notes.add(t);
        }
        // tied continuations don't take syllables
        List<Tok> slots = new ArrayList<>();
        Tok prev = null;
        for (Tok t : notes) {
            if (prev != null && prev.tie && overlap(prev.pitches, t.pitches)) { prev = t; continue; }
            slots.add(t);
            prev = t;
        }
        int idx = v.lyricCursor >= 0 ? v.lyricCursor : 0;
        List<String> parts = splitLyrics(text);
        for (String p : parts) {
            if (idx >= slots.size()) break;
            switch (p) {
                case "*":
                    idx++;
                    break;
                case "_":
                    idx++;   // melisma: syllable held over this note
                    break;
                case "-":
                    break;
                case "|":
                    // advance to the note after the next bar line
                    while (idx < slots.size() && !slots.get(idx > 0 ? idx - 1 : 0).barAfter) idx++;
                    break;
                default:
                    slots.get(idx).syllable = p.replace('~', ' ').replace("\\-", "-");
                    idx++;
                    break;
            }
        }
        v.lyricCursor = idx;
    }

    /** "Ky-ri-e e-lei-son" -> [Ky-, ri-, e, e-, lei-, son]; keeps _, *, | tokens. */
    static List<String> splitLyrics(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        int n = text.length();
        for (int i = 0; i < n; i++) {
            char c = text.charAt(i);
            if (c == '\\' && i + 1 < n && text.charAt(i + 1) == '-') {
                sb.append("\\-");
                i++;
                continue;
            }
            if (c == ' ' || c == '\t') {
                if (sb.length() > 0) { out.add(sb.toString()); sb.setLength(0); }
            } else if (c == '-') {
                if (sb.length() > 0) { sb.append('-'); out.add(sb.toString()); sb.setLength(0); }
                else if (!out.isEmpty() && out.get(out.size() - 1).endsWith("-")) out.add("*");   // "--" skips a note
            } else if (c == '_' || c == '*' || c == '|') {
                if (sb.length() > 0) { out.add(sb.toString()); sb.setLength(0); }
                out.add(String.valueOf(c));
            } else {
                sb.append(c);
            }
        }
        if (sb.length() > 0) out.add(sb.toString());
        return out;
    }
}
