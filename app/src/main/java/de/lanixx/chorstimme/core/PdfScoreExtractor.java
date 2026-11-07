package de.lanixx.chorstimme.core;

import de.lanixx.chorstimme.core.PdfReader.PageData;
import de.lanixx.chorstimme.core.PdfReader.Shape;
import de.lanixx.chorstimme.core.PdfReader.TextItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rebuilds the notes from the vector drawing of a sheet-music PDF engraved with abcjs
 * (printed from a browser). All glyph sizes are judged relative to the staff-line spacing,
 * so the print scale does not matter.
 */
public final class PdfScoreExtractor {

    private PdfScoreExtractor() {}

    static final String REPEAT_WARNING =
            "Wiederholungszeichen erkannt – Wiederholungen werden aus der PDF nicht ausgeschrieben (ABC-Text kann das).";

    // ------------------------------------------------------------ data classes

    static final class Staff {
        int page;
        double[] lines = new double[5];   // y of the 5 lines, top to bottom
        double x0, x1, sp;
        int bottomDiatonic = 30;          // diatonic index of the bottom line (E4 = 30)
        String clef = "treble";
        final Map<Integer, Integer> keySig = new HashMap<>();   // letter (0=C..6=B) -> alter
        final List<Shape> shapes = new ArrayList<>();
        final List<TextItem> labels = new ArrayList<>();
        final List<TextItem> lyrics = new ArrayList<>();
        final List<TextItem> numbers = new ArrayList<>();
        int system;                       // index of the system (line of music)
        int indexInSystem;
        int voiceCount = 1;
        boolean glyphMode;               // notes come from a music font (Finale & Co.)
        char glyphClef;
        final List<double[]> meters = new ArrayList<>();   // {x, beats per bar} from time signatures
        List<Shape> pageShapes = new ArrayList<>();       // all shapes of the page (for bar lines)
        List<Double> barXs = new ArrayList<>();

        double top() { return lines[0]; }
        double bottom() { return lines[4]; }
        double mid() { return lines[2]; }
    }

    static final class Head {
        Shape s;
        int kind;          // 1 = filled, 2 = half (hollow), 4 = whole
        int pos;           // staff position, 0 = bottom line, 1 = first space ...
        int alter = Integer.MIN_VALUE;   // explicit accidental, MIN_VALUE = none
        boolean dotted;
        Shape stem;
        boolean stemUp;
        int voice = -1;
        double x() { return s.x0; }
    }

    static final class Event {
        final List<Head> heads = new ArrayList<>();
        boolean rest;
        double x, cx, cy;
        double dur;            // beats
        int voice;
        boolean tieToNext;
        String syllable;
        boolean placeholder;   // lyric placeholder (melisma) assigned
        int[] midi;
        int bar;               // bar index on the staff (bar lines left of it)
        boolean barRest;       // whole-bar rest: length taken from the other voices / metre
        boolean recite;        // carries several words (recitation): held as a breve
        double anchor;         // x of the time column this event starts in
        int col;               // index of that column
        double start;          // beats from the start of the line
    }

    // ------------------------------------------------------------ entry point

    public static Score extract(List<PageData> pages) {
        Score score = new Score();
        boolean glyphMode = false;
        for (PageData pd : pages) {
            if (!pd.glyphs.isEmpty()) glyphMode = true;
            glyphsToShapes(pd);
        }
        mergeSplitStaves(pages);
        List<Staff> staves = new ArrayList<>();
        for (int p = 0; p < pages.size(); p++) staves.addAll(findStaves(pages.get(p), p));
        if (staves.isEmpty()) {
            score.warnings.add("Keine Notenlinien gefunden – ist das eine abcjs-Noten-PDF?");
            return score;
        }
        readHeader(pages.get(0), staves.get(0), score);
        for (int p = 0; p < pages.size(); p++) assignToStaves(pages.get(p), p, staves);
        groupSystems(pages, staves);

        // voice layout: from the labels ("Sopran", "Alt") if the first system has any, otherwise
        // detected from the stems over the whole piece (two voices = stems up and down)
        int sys0Staves = 0;
        for (Staff st : staves) if (st.system == 0) sys0Staves++;
        boolean anyLabels = false;
        for (Staff st : staves) if (st.system == 0 && !st.labels.isEmpty()) anyLabels = true;
        int[] voicesPerStaff = new int[sys0Staves];
        List<String> labelNames = new ArrayList<>();
        Map<Staff, List<List<Event>>> read = new HashMap<>();
        if (anyLabels) {
            for (Staff st : staves) {
                if (st.system != 0) continue;
                List<TextItem> lab = mergeLabels(st.labels);
                voicesPerStaff[st.indexInSystem] = Math.max(1, Math.min(2, lab.size()));
            }
        } else {
            for (Staff st : staves) {
                if (st.indexInSystem >= sys0Staves) continue;
                st.glyphMode = glyphMode;
                st.voiceCount = 0;
                List<List<Event>> evs = readStaff(st, new ArrayList<>(), st.system);
                read.put(st, evs);
                voicesPerStaff[st.indexInSystem] = Math.max(voicesPerStaff[st.indexInSystem],
                        Math.min(2, evs.size()));
            }
        }
        for (Staff st : staves) {
            if (st.system != 0) continue;
            List<TextItem> lab = mergeLabels(st.labels);
            for (int v = 0; v < voicesPerStaff[st.indexInSystem]; v++) {
                labelNames.add(v < lab.size() ? lab.get(v).text : null);
            }
        }
        int total = labelNames.size();
        if (!anyLabels) {
            String[] std = total == 4 ? new String[]{"Sopran", "Alt", "Tenor", "Bass"}
                    : total == 2 ? new String[]{"Sopran", "Alt"} : null;
            for (int v = 0; v < total; v++) labelNames.set(v, std != null ? std[v] : "Stimme " + (v + 1));
        }
        int[] offset = new int[sys0Staves];
        int acc = 0;
        for (int i = 0; i < sys0Staves; i++) { offset[i] = acc; acc += voicesPerStaff[i]; }
        for (int v = 0; v < total; v++) {
            String n = labelNames.get(v);
            score.voices.add(new Score.Voice(n != null ? n : "Stimme " + (v + 1)));
        }

        double t = 0;
        int sysCount = 0;
        for (Staff st : staves) sysCount = Math.max(sysCount, st.system + 1);
        Event[] lastEvent = new Event[total];
        List<Integer> conflictLines = new ArrayList<>();
        double[] meterOfStaff = new double[sys0Staves];   // beats per bar, carried over lines
        boolean warnedExtra = false;

        for (int sys = 0; sys < sysCount; sys++) {
            // 1) read every staff of the line with the final voice count
            List<Staff> line = new ArrayList<>();
            Map<Staff, List<List<Event>>> evMap = new HashMap<>();
            for (Staff st : staves) {
                if (st.system != sys) continue;
                if (st.indexInSystem >= sys0Staves) {
                    if (!warnedExtra) score.warnings.add("Zeile " + (sys + 1) + ": mehr Notensysteme als in der ersten Zeile – ignoriert.");
                    warnedExtra = true;
                    continue;
                }
                int want = voicesPerStaff[st.indexInSystem];
                List<List<Event>> evs = read.get(st);
                if (evs == null || evs.size() != want) {
                    st.voiceCount = want;
                    st.glyphMode = glyphMode;
                    evs = readStaff(st, score.warnings, sys);
                } else if (st.system > 0 || anyLabels) {
                    // keep auto-read result
                }
                while (evs.size() < want) evs.add(new ArrayList<>());
                line.add(st);
                evMap.put(st, evs);
            }
            // 2) whole-bar rests still open: length of that bar in the other voices of the line,
            //    else from the time signature
            for (Staff st : line) {
                int idx = st.indexInSystem;
                for (List<Event> list : evMap.get(st)) {
                    for (Event e : list) {
                        if (!(e.rest && e.barRest && e.dur == 0)) continue;
                        double best = 0;
                        for (Staff o : line) {
                            for (List<Event> ol : evMap.get(o)) {
                                if (ol == list) continue;
                                double sum = 0;
                                boolean onlyBarRests = true;
                                for (Event x : ol) {
                                    if (x.bar != e.bar) continue;
                                    sum += x.dur;
                                    if (!x.barRest) onlyBarRests = false;
                                }
                                if (!onlyBarRests) best = Math.max(best, sum);
                            }
                        }
                        if (best <= 0) {
                            double m = meterOfStaff[idx];
                            for (double[] mt : st.meters) if (mt[0] < e.cx) m = mt[1];
                            best = m > 0 ? m : 4;
                        }
                        e.dur = best;
                    }
                }
                if (!st.meters.isEmpty()) meterOfStaff[idx] = st.meters.get(st.meters.size() - 1)[1];
            }
            // 3) timeline: aligned on the horizontal note positions of all voices
            List<int[]> voiceOf = new ArrayList<>();          // {global voice}
            List<List<Event>> lists = new ArrayList<>();
            List<Staff> staffOf = new ArrayList<>();
            for (Staff st : line) {
                List<List<Event>> evs = evMap.get(st);
                for (int v = 0; v < voicesPerStaff[st.indexInSystem]; v++) {
                    voiceOf.add(new int[]{offset[st.indexInSystem] + v});
                    lists.add(evs.get(v));
                    staffOf.add(st);
                }
            }
            LineTiming lt = alignLine(lists, staffOf);
            if (lt.conflicts > 0) conflictLines.add(sys + 1);
            for (int k = 0; k < lists.size(); k++) {
                int gv = voiceOf.get(k)[0];
                Score.Voice voice = score.voices.get(gv);
                List<Event> list = lists.get(k);
                for (int i = 0; i < list.size(); i++) {
                    Event e = list.get(i);
                    double start = t + e.start;
                    // tie from the end of the previous line
                    if (i == 0 && lastEvent[gv] != null && lastEvent[gv].tieToNext && !e.rest
                            && samePitches(lastEvent[gv].midi, e.midi) && !voice.notes.isEmpty()) {
                        Score.Note prev = voice.notes.get(voice.notes.size() - 1);
                        if (Math.abs(prev.end() - start) < 1e-6) {
                            prev.duration += e.dur;
                            lastEvent[gv] = e;
                            continue;
                        }
                    }
                    if (!e.rest) {
                        Score.Note n = new Score.Note(start, e.dur, e.midi);
                        n.syllable = e.syllable;
                        voice.notes.add(n);
                    }
                    lastEvent[gv] = e.rest ? null : e;
                }
            }
            score.lineStarts.add(t);
            for (double bt : lt.bars) score.barTimes.add(t + bt);
            t += lt.length;
            score.barTimes.add(t);          // a printed line ends with a bar line
        }
        if (!conflictLines.isEmpty()) {
            score.warnings.add("Zeile " + joinInts(conflictLines) + ": einzelne Notenwerte passten nicht zusammen und "
                    + "wurden an den anderen Stimmen ausgerichtet (Takt bleibt synchron). Beim Anhören prüfen.");
        }
        // drop empty voices
        for (int v = score.voices.size() - 1; v >= 0; v--) {
            if (score.voices.get(v).notes.isEmpty()) score.voices.remove(v);
        }
        if (score.noteCount() == 0) score.warnings.add("Keine Noten erkannt.");
        score.assignVowels();
        return score;
    }

    static final double BREVE = 8;     // beats

    static final class LineTiming {
        double length;
        int conflicts;
        final List<Double> bars = new ArrayList<>();   // bar line times inside the line
    }

    /**
     * Gives every event of one line its start time and length.
     *
     * Notes sounding together are printed one above the other, in every engraving. So the time of
     * an event comes from its horizontal position: all voices are sorted into common columns, and
     * each column gets the time most voices agree on (previous column + note value). An event
     * lasts until the next event of its voice. A misread note value or a lost note therefore stays
     * a local error instead of shifting the rest of its voice.
     */
    static LineTiming alignLine(List<List<Event>> lists, List<Staff> staffOf) {
        LineTiming lt = new LineTiming();
        double sp = 5;
        for (Staff st : staffOf) sp = st.sp;

        // anchors: where a note starts horizontally (head centre on the normal side of the stem)
        List<Event> anchored = new ArrayList<>();
        for (int k = 0; k < lists.size(); k++) {
            Staff st = staffOf.get(k);
            for (Event e : lists.get(k)) {
                if (e.barRest) continue;
                e.anchor = anchorOf(e, st.sp);
                anchored.add(e);
            }
        }
        anchored.sort(Comparator.comparingDouble(e -> e.anchor));
        Map<Event, Integer> listOf = new java.util.IdentityHashMap<>();
        for (int k = 0; k < lists.size(); k++) for (Event e : lists.get(k)) listOf.put(e, k);
        // tight columns first; then neighbouring columns are joined when no voice has a note in
        // both – that is a head pushed aside (second between two voices), not a later moment
        List<List<Event>> groups = new ArrayList<>();
        for (Event e : anchored) {
            List<Event> g = groups.isEmpty() ? null : groups.get(groups.size() - 1);
            if (g != null && e.anchor - g.get(0).anchor <= 0.6 * sp) g.add(e);
            else {
                g = new ArrayList<>();
                g.add(e);
                groups.add(g);
            }
        }
        for (int j = groups.size() - 1; j > 0; j--) {
            List<Event> a = groups.get(j - 1), b = groups.get(j);
            double gap = b.get(0).anchor - a.get(a.size() - 1).anchor;
            if (gap > 1.8 * sp) continue;
            boolean shared = false;
            for (Event x : a) for (Event y : b) if (listOf.get(x).equals(listOf.get(y))) shared = true;
            if (!shared) {
                a.addAll(b);
                groups.remove(j);
            }
        }
        List<Double> cols = new ArrayList<>();
        for (List<Event> g : groups) {
            double m = Double.MAX_VALUE;
            for (Event e : g) m = Math.min(m, e.anchor);
            cols.add(m);
            for (Event e : g) e.anchor = m;
        }

        // whole-bar rests start at the first column of their bar
        for (int k = 0; k < lists.size(); k++) {
            Staff st = staffOf.get(k);
            for (Event e : lists.get(k)) {
                if (!e.barRest) continue;
                double barStart = Double.NEGATIVE_INFINITY, barEnd = Double.MAX_VALUE;
                for (double bx : st.barXs) {
                    if (bx < e.cx) barStart = bx;
                    else { barEnd = bx; break; }
                }
                double a = Double.NaN;
                for (double c : cols) if (c > barStart && c < barEnd) { a = c; break; }
                if (Double.isNaN(a)) {
                    a = barStart > Double.NEGATIVE_INFINITY ? barStart + 0.5 * sp : e.cx;
                    cols.add(a);
                    cols.sort(Double::compare);
                }
                e.anchor = a;
            }
        }
        int C = cols.size();
        for (List<Event> list : lists) {
            for (Event e : list) e.col = nearest(cols, e.anchor);
            list.sort((x, y) -> x.col != y.col ? Integer.compare(x.col, y.col) : Double.compare(x.cx, y.cx));
            // two events of one voice in one column: same moment, keep them as one chord
            for (int i = list.size() - 1; i > 0; i--) {
                Event a = list.get(i - 1), b = list.get(i);
                if (a.col != b.col) continue;
                if (a.rest && !b.rest) { list.remove(i - 1); continue; }
                if (!a.rest && !b.rest && a.midi != null && b.midi != null) {
                    int[] m = java.util.Arrays.copyOf(a.midi, a.midi.length + b.midi.length);
                    System.arraycopy(b.midi, 0, m, a.midi.length, b.midi.length);
                    a.midi = m;
                    if (a.syllable == null) a.syllable = b.syllable;
                }
                a.dur = Math.max(a.dur, b.dur);
                list.remove(i);
            }
        }
        if (C == 0) return lt;

        // recitation: a column where a note carries several words is held as a breve (8 beats)
        // in all voices
        boolean[] recite = new boolean[C];
        for (List<Event> list : lists) for (Event e : list) if (e.recite) recite[e.col] = true;
        for (List<Event> list : lists) for (Event e : list) if (recite[e.col] && e.dur < BREVE) e.dur = BREVE;

        // column times: vote of all voices (time of the previous event + its note value)
        double[] time = new double[C];
        time[0] = 0;
        for (int j = 1; j < C; j++) {
            Map<Long, Integer> votes = new HashMap<>();
            for (List<Event> list : lists) {
                for (int i = 1; i < list.size(); i++) {
                    Event prev = list.get(i - 1), cur = list.get(i);
                    if (cur.col != j || prev.col >= j) continue;
                    long key = Math.round((time[prev.col] + prev.dur) * 96);
                    votes.merge(key, 1, Integer::sum);
                }
            }
            double best = Double.NaN;
            int bestVotes = 0, all = 0;
            for (Map.Entry<Long, Integer> v : votes.entrySet()) {
                all += v.getValue();
                double val = v.getKey() / 96.0;
                if (val <= time[j - 1] + 1e-6) continue;
                if (v.getValue() > bestVotes || v.getValue() == bestVotes && val < best) {
                    bestVotes = v.getValue();
                    best = val;
                }
            }
            if (Double.isNaN(best)) {
                // nobody arrives here directly: shortest event of the previous column decides
                double d = Double.MAX_VALUE;
                for (List<Event> list : lists) for (Event e : list) if (e.col == j - 1 && e.dur > 0) d = Math.min(d, e.dur);
                best = time[j - 1] + (d == Double.MAX_VALUE ? 1 : d);
            }
            time[j] = best;
        }
        // end of the line: vote of the last events
        Map<Long, Integer> endVotes = new HashMap<>();
        for (List<Event> list : lists) {
            if (list.isEmpty()) continue;
            Event last = list.get(list.size() - 1);
            endVotes.merge(Math.round((time[last.col] + last.dur) * 96), 1, Integer::sum);
        }
        double end = time[C - 1];
        int ev = 0;
        for (Map.Entry<Long, Integer> v : endVotes.entrySet()) {
            double val = v.getKey() / 96.0;
            if (val > time[C - 1] && (v.getValue() > ev || v.getValue() == ev && val > end)) { ev = v.getValue(); end = val; }
        }
        if (end <= time[C - 1]) end = time[C - 1] + 1;
        // start and length of every event
        for (List<Event> list : lists) {
            for (int i = 0; i < list.size(); i++) {
                Event e = list.get(i);
                e.start = time[e.col];
                double next = i + 1 < list.size() ? time[list.get(i + 1).col] : end;
                double len = next - e.start;
                // a note whose read value had to change is a recognition conflict
                if (Math.abs(len - e.dur) > 1e-3 && !e.barRest && i + 1 < list.size()) lt.conflicts++;
                e.dur = len > 1e-6 ? len : Math.max(e.dur, 0.25);
            }
        }
        lt.length = end;
        // bar lines of the first staff -> times (start of the first column after the line)
        if (!staffOf.isEmpty()) {
            for (double bx : staffOf.get(0).barXs) {
                double bt = end;
                for (int j = 0; j < C; j++) if (cols.get(j) > bx) { bt = time[j]; break; }
                if (bt > 1e-6 && bt < end - 1e-6 && (lt.bars.isEmpty() || bt > lt.bars.get(lt.bars.size() - 1) + 1e-6)) lt.bars.add(bt);
            }
        }
        return lt;
    }

    private static double anchorOf(Event e, double sp) {
        if (!e.rest && !e.heads.isEmpty()) {
            Head h0 = e.heads.get(0);
            if (h0.stem != null) {
                // normal head position next to the stem, whichever side a second pushed it to
                return h0.stemUp ? h0.stem.cx() - 0.65 * sp : h0.stem.cx() + 0.65 * sp;
            }
            double m = Double.MAX_VALUE;
            for (Head h : e.heads) m = Math.min(m, h.s.cx());
            return m;
        }
        return e.cx;
    }

    private static int nearest(List<Double> cols, double x) {
        int best = 0;
        for (int i = 1; i < cols.size(); i++) if (Math.abs(cols.get(i) - x) < Math.abs(cols.get(best) - x)) best = i;
        return best;
    }

    private static String joinInts(List<Integer> l) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) sb.append(i == l.size() - 1 ? " und " : ", ");
            sb.append(l.get(i));
        }
        return sb.toString();
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((int) d) : String.valueOf(Math.round(d * 100) / 100.0);
    }

    private static boolean samePitches(int[] a, int[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) return false;
        for (int x : a) for (int y : b) if (x == y) return true;
        return false;
    }

    // ------------------------------------------------------------ staves

    static List<Staff> findStaves(PageData pd, int page) {
        List<Shape> lines = lineCandidates(pd);
        List<Staff> out = new ArrayList<>();
        boolean[] used = new boolean[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            if (used[i]) continue;
            Shape a = lines.get(i);
            List<Integer> group = new ArrayList<>();
            group.add(i);
            double gap = -1;
            for (int j = i + 1; j < lines.size() && group.size() < 5; j++) {
                if (used[j]) continue;
                Shape b = lines.get(j);
                if (Math.abs(b.x0 - a.x0) > 3 || Math.abs(b.x1 - a.x1) > 3) continue;
                Shape prev = lines.get(group.get(group.size() - 1));
                double g = b.cy() - prev.cy();
                if (gap < 0) {
                    if (g < 2 || g > 20) break;
                    gap = g;
                    group.add(j);
                } else if (Math.abs(g - gap) < 0.15 * gap) {
                    group.add(j);
                } else if (g > gap * 1.15) {
                    break;
                }
            }
            if (group.size() == 5) {
                Staff st = new Staff();
                st.page = page;
                for (int k = 0; k < 5; k++) {
                    st.lines[k] = lines.get(group.get(k)).cy();
                    used[group.get(k)] = true;
                }
                st.x0 = a.x0;
                st.x1 = a.x1;
                st.sp = (st.lines[4] - st.lines[0]) / 4;
                out.add(st);
            }
        }
        out.sort(Comparator.comparingDouble(s -> s.lines[0]));
        return out;
    }

    private static List<Shape> lineCandidates(PageData pd) {
        List<Shape> cand = new ArrayList<>();
        for (Shape s : pd.shapes) {
            if (s.h() < 1.6 && s.w() > 60 && s.w() > 20 * s.h()) cand.add(s);
        }
        cand.sort(Comparator.comparingDouble(Shape::cy));
        List<Shape> lines = new ArrayList<>();
        for (Shape s : cand) {
            Shape last = lines.isEmpty() ? null : lines.get(lines.size() - 1);
            if (last != null && Math.abs(last.cy() - s.cy()) < 0.6 && Math.abs(last.x0 - s.x0) < 3) continue;
            lines.add(s);
        }
        return lines;
    }

    /**
     * Browsers sometimes cut one staff across a page break (some lines at the bottom of one
     * page, the rest at the top of the next). Moves the upper part onto the next page so the
     * staff is whole again.
     */
    static void mergeSplitStaves(List<PageData> pages) {
        for (int p = 0; p + 1 < pages.size(); p++) {
            PageData a = pages.get(p), b = pages.get(p + 1);
            List<Staff> fa = findStaves(a, p), fb = findStaves(b, p + 1);
            double lastBottom = fa.isEmpty() ? -1 : fa.get(fa.size() - 1).bottom();
            double firstTop = fb.isEmpty() ? Double.MAX_VALUE : fb.get(0).top();
            List<Shape> la = new ArrayList<>(), lb = new ArrayList<>();
            for (Shape s : lineCandidates(a)) if (s.cy() > lastBottom + 1) la.add(s);
            for (Shape s : lineCandidates(b)) if (s.cy() < firstTop - 1) lb.add(s);
            if (la.isEmpty() || lb.isEmpty() || la.size() + lb.size() != 5) continue;
            if (Math.abs(la.get(0).x0 - lb.get(0).x0) > 3 || Math.abs(la.get(0).x1 - lb.get(0).x1) > 3) continue;
            double gap;
            if (lb.size() >= 2) gap = lb.get(1).cy() - lb.get(0).cy();
            else if (la.size() >= 2) gap = la.get(1).cy() - la.get(0).cy();
            else if (!fb.isEmpty()) gap = fb.get(0).sp;
            else continue;
            if (gap < 2 || gap > 20) continue;
            double offset = (lb.get(0).cy() - gap * la.size()) - la.get(0).cy();
            double thr = la.get(0).cy() - Math.min(6 * gap, lastBottom > 0 ? (la.get(0).cy() - lastBottom) / 2 : 6 * gap);
            List<Shape> move = new ArrayList<>();
            for (Shape s : a.shapes) if (s.cy() > thr) move.add(s);
            a.shapes.removeAll(move);
            List<Shape> existing = new ArrayList<>(b.shapes);
            for (Shape s : move) {
                s.y0 += offset; s.y1 += offset;
                for (double[] r : s.subs) { r[1] += offset; r[3] += offset; }
                // glyphs lying on the cut were drawn on both pages
                boolean dup = false;
                for (Shape e : existing) {
                    if (Math.abs(e.x0 - s.x0) < 0.5 && Math.abs(e.y0 - s.y0) < 0.5
                            && Math.abs(e.w() - s.w()) < 0.5 && Math.abs(e.h() - s.h()) < 0.5) { dup = true; break; }
                }
                if (!dup) b.shapes.add(s);
            }
            List<TextItem> moveT = new ArrayList<>();
            for (TextItem t : a.texts) if (t.baseline > thr) moveT.add(t);
            a.texts.removeAll(moveT);
            List<TextItem> existingT = new ArrayList<>(b.texts);
            for (TextItem t : moveT) {
                t.baseline += offset;
                boolean dup = false;
                for (TextItem e : existingT) {
                    if (e.text.equals(t.text) && Math.abs(e.x0 - t.x0) < 0.5 && Math.abs(e.baseline - t.baseline) < 0.5) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) b.texts.add(t);
            }
        }
    }

    static void assignToStaves(PageData pd, int page, List<Staff> all) {
        List<Staff> staves = new ArrayList<>();
        for (Staff s : all) if (s.page == page) staves.add(s);
        if (staves.isEmpty()) return;
        for (Staff st : staves) st.pageShapes = pd.shapes;
        for (Shape s : pd.shapes) {
            Staff best = null;
            double bd = Double.MAX_VALUE;
            for (Staff st : staves) {
                double d = s.cy() < st.top() ? st.top() - s.cy() : s.cy() > st.bottom() ? s.cy() - st.bottom() : 0;
                d /= st.sp;
                if (d < bd) { bd = d; best = st; }
            }
            if (best != null && bd < 6 && s.cx() > best.x0 - 2 * best.sp && s.cx() < best.x1 + 2 * best.sp) {
                best.shapes.add(s);
            }
        }
        // lines with ©, links or mail addresses are page footers, not lyrics
        java.util.Set<Long> footer = new java.util.HashSet<>();
        for (TextItem t : pd.texts) {
            if (t.text.contains("©") || t.text.contains("http") || t.text.contains("@") || t.text.contains("www.")) {
                footer.add(Math.round(t.baseline));
            }
        }
        for (int i = 0; i < staves.size(); i++) {
            Staff st = staves.get(i);
            double nextTop = i + 1 < staves.size() ? staves.get(i + 1).top() : Double.MAX_VALUE;
            for (TextItem t : pd.texts) {
                double y = t.baseline;
                if (t.x1 < st.x0 + st.sp && y > st.top() - st.sp && y < st.bottom() + 2.5 * st.sp) {
                    if (!isBlank(t.text)) st.labels.add(t);
                } else if (t.text.matches("[0-9]")) {
                    continue;   // tuplet numbers: handled below
                } else if (t.x0 > st.x0 - 2 * st.sp && y > st.bottom() + 0.5 * st.sp
                        && y < Math.min(nextTop - 2.5 * st.sp, st.bottom() + 12 * st.sp)
                        && !DIRECTION.matcher(t.text).matches()
                        && !footer.contains(Math.round(t.baseline)) && !footer.contains(Math.round(t.baseline) - 1)
                        && !footer.contains(Math.round(t.baseline) + 1)) {
                    st.lyrics.add(t);
                }
            }
        }
        // tuplet digits go to the nearest staff
        for (TextItem t : pd.texts) {
            if (!t.text.matches("[0-9]")) continue;
            Staff best = null;
            double bd = Double.MAX_VALUE;
            double y = t.baseline - t.fontSize * 0.35;
            for (Staff st : staves) {
                if (t.x1 < st.x0 + st.sp) continue;     // part of a label
                double d = y < st.top() ? st.top() - y : y > st.bottom() ? y - st.bottom() : 0;
                if (d < bd) { bd = d; best = st; }
            }
            if (best != null && bd < 6 * best.sp) best.numbers.add(t);
        }
    }

    /** Systems: staves joined by a vertical line at their left edge belong together. */
    static void groupSystems(List<PageData> pages, List<Staff> staves) {
        int sys = -1;
        Staff prev = null;
        int idx = 0;
        for (Staff st : staves) {
            boolean joined = false;
            if (prev != null && prev.page == st.page) {
                for (Shape s : pages.get(st.page).shapes) {
                    if (s.w() < 1.5 && Math.abs(s.x0 - st.x0) < 2.5 && s.y0 <= prev.bottom() + 0.5
                            && s.y1 >= st.top() - 0.5) {
                        joined = true;
                        break;
                    }
                }
            }
            if (joined) {
                idx++;
            } else {
                sys++;
                idx = 0;
            }
            st.system = sys;
            st.indexInSystem = idx;
            prev = st;
        }
    }

    private static boolean isBlank(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c) && !Character.isSpaceChar(c)) return false;
        }
        return true;
    }

    /** Music-font characters become shapes centred on their baseline (= staff position). */
    static void glyphsToShapes(PageData pd) {
        for (PdfReader.Glyph g : pd.glyphs) {
            double half = Math.max(g.size, 4) * 0.125;     // half a staff space
            Shape s = new Shape();
            s.glyph = g.c;
            s.fill = true;
            s.curved = true;
            s.subpaths = 1;
            s.x0 = g.x0;
            s.x1 = Math.max(g.x1, g.x0 + 0.5);
            s.y0 = g.baseline - half;
            s.y1 = g.baseline + half;
            pd.shapes.add(s);
        }
        pd.glyphs.clear();
    }

    /** Reading order: lines by baseline (1 pt tolerance), left to right inside a line. */
    private static void sortByLine(List<TextItem> items) {
        items.sort(Comparator.comparingDouble(t -> t.baseline));
        Map<TextItem, Integer> lineOf = new java.util.IdentityHashMap<>();
        int line = -1;
        double y = Double.NEGATIVE_INFINITY;
        for (TextItem t : items) {
            if (t.baseline - y > 1) { line++; y = t.baseline; }
            lineOf.put(t, line);
        }
        items.sort((a, b) -> {
            int c = Integer.compare(lineOf.get(a), lineOf.get(b));
            return c != 0 ? c : Double.compare(a.x0, b.x0);
        });
    }

    private static List<TextItem> mergeLabels(List<TextItem> labels) {
        // words on the same baseline form one label ("Sopran 1")
        List<TextItem> sorted = new ArrayList<>(labels);
        sortByLine(sorted);
        List<TextItem> out = new ArrayList<>();
        for (TextItem t : sorted) {
            TextItem last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && Math.abs(last.baseline - t.baseline) < 1) {
                last.text = last.text + " " + t.text;
                last.x1 = t.x1;
            } else {
                TextItem c = new TextItem();
                c.text = t.text; c.x0 = t.x0; c.x1 = t.x1; c.baseline = t.baseline; c.fontSize = t.fontSize;
                out.add(c);
            }
        }
        return out;
    }

    // ------------------------------------------------------------ header

    /** performance directions printed near the staves – not lyrics */
    private static final Pattern DIRECTION = Pattern.compile(
            "(?i)(rit|ritard|rall|accel|cresc|decresc|dim|tempo|poco|meno|piu|più|molto|sub|ten|"
                    + "p|pp|ppp|mp|mf|f|ff|fff|sf|sfz|fp)\\.?");

    private static final Pattern TEMPO = Pattern.compile("=\\s*(\\d{2,3})");

    static void readHeader(PageData pd, Staff first, Score score) {
        // title: biggest font above the first staff
        double maxFs = 0;
        for (TextItem t : pd.texts) {
            if (t.baseline < first.top() - 2 * first.sp) maxFs = Math.max(maxFs, t.fontSize);
        }
        List<TextItem> titleWords = new ArrayList<>();
        for (TextItem t : pd.texts) {
            if (t.baseline < first.top() - 2 * first.sp && Math.abs(t.fontSize - maxFs) < 0.5) titleWords.add(t);
        }
        if (!titleWords.isEmpty()) {
            double base = titleWords.get(0).baseline;
            StringBuilder sb = new StringBuilder();
            titleWords.sort(Comparator.comparingDouble(t -> t.x0));
            for (TextItem t : titleWords) {
                if (Math.abs(t.baseline - base) > 1) continue;
                if (sb.length() > 0) sb.append(' ');
                sb.append(t.text);
            }
            score.title = sb.toString();
        }
        // tempo "= 120" above the first staff
        List<TextItem> above = new ArrayList<>();
        for (TextItem t : pd.texts) {
            if (t.baseline < first.top() && t.baseline > first.top() - 12 * first.sp) above.add(t);
        }
        sortByLine(above);
        StringBuilder line = new StringBuilder();
        for (TextItem t : above) line.append(t.text).append(' ');
        Matcher m = TEMPO.matcher(line);
        if (m.find()) {
            double bpm = Double.parseDouble(m.group(1));
            if (bpm >= 20 && bpm <= 300) score.tempoBpm = bpm;
        }
    }

    // ------------------------------------------------------------ one staff

    static List<List<Event>> readStaff(Staff st, List<String> warn, int sys) {
        double sp = st.sp;
        List<Shape> sh = st.shapes;
        List<Head> heads = new ArrayList<>();
        List<Shape> stems = new ArrayList<>(), flags = new ArrayList<>(), beams = new ArrayList<>();
        List<Shape> dots = new ArrayList<>(), accs = new ArrayList<>(), curves = new ArrayList<>();
        List<Shape> rectRests = new ArrayList<>(), others = new ArrayList<>();
        List<Double> bars = new ArrayList<>();
        List<Shape> barCand = new ArrayList<>();
        Shape clef = null;

        List<Shape> glyphAccs = new ArrayList<>();
        List<Shape> meterDigits = new ArrayList<>();
        Map<Shape, Integer> glyphFlags = new HashMap<>();
        Map<Shape, Integer> restBeats = new HashMap<>();
        List<Shape> restGlyphs = new ArrayList<>();
        for (Shape s : sh) {
            if (s.glyph != 0) {
                switch (s.glyph) {
                    case 'œ': case '˙': case 'w': {
                        Head hd = new Head();
                        hd.s = s;
                        hd.kind = s.glyph == 'œ' ? 1 : s.glyph == '˙' ? 2 : 4;
                        heads.add(hd);
                        break;
                    }
                    case '&': case '?': case 'B': case 'V':
                        if (st.glyphClef == 0) st.glyphClef = s.glyph;
                        break;
                    case '#': case 'b': case 'n':
                        glyphAccs.add(s);
                        break;
                    case '.': case 'k':
                        dots.add(s);
                        break;
                    case 'j': case 'J':
                        glyphFlags.put(s, 1);
                        break;
                    case 'r': case 'R':
                        glyphFlags.put(s, 2);
                        break;
                    case 'Œ': restGlyphs.add(s); restBeats.put(s, 100); break;
                    case '‰': restGlyphs.add(s); restBeats.put(s, 50); break;
                    case '≈': restGlyphs.add(s); restBeats.put(s, 25); break;
                    case 'Ó': restGlyphs.add(s); restBeats.put(s, 2); break;
                    case '∑': restGlyphs.add(s); restBeats.put(s, -1); break;
                    case 'c': st.meters.add(new double[]{s.x0, 4}); break;      // common time
                    case 'C': st.meters.add(new double[]{s.x0, 4}); break;      // cut time 2/2
                    case '1': case '2': case '3': case '4': case '5': case '6': case '7': case '8': case '9':
                        meterDigits.add(s);
                        break;
                    default:
                        break;
                }
                continue;
            }
            boolean lineLike = s.stroke && (s.w() < 0.3 * sp || s.h() < 0.15 * sp);
            if (!s.fill && !lineLike) continue;
            double w = s.w() / sp, h = s.h() / sp;
            if (h < 0.15 && w > 15) continue;                       // staff line
            if (w < 0.6 && h > 3.7 && s.y0 <= st.top() + 0.3 * sp && s.y1 >= st.bottom() - 0.3 * sp) {
                barCand.add(s);                                      // bar line or long stem
                continue;
            }
            if (w < 0.3 && h > 1.5) { stems.add(s); continue; }
            if (h < 0.15) continue;                                  // ledger line
            // bitmap glyphs: a filled head is mostly ink; accents, boxes etc. are not
            boolean headInk = s.ink < 0 || s.subpaths >= 2 || s.ink > 0.5;
            if (s.curved && headInk && h >= 0.85 && h <= 1.25 && w >= 1.05 && w <= 1.55) {
                Head hd = new Head();
                hd.s = s;
                hd.kind = s.subpaths >= 2 ? 2 : 1;
                heads.add(hd);
                continue;
            }
            if (s.curved && h >= 0.85 && h <= 1.25 && w >= 1.7 && w <= 2.3 && s.subpaths >= 2) {
                Head hd = new Head();
                hd.s = s;
                hd.kind = 4;
                heads.add(hd);
                continue;
            }
            if (!s.curved && w >= 1.2 && w <= 1.8 && h >= 0.45 && h <= 0.8) { rectRests.add(s); continue; }
            if (!s.curved && w > 0.6 && h >= 0.35) {
                beams.add(s);
                // Finale draws ties as polygons, not curves: also a tie candidate
                if (h < 2.2 && w > 1.2 && s.fill && !s.stroke) curves.add(s);
                continue;
            }
            if (s.curved && w >= 0.3 && w <= 0.65 && h >= 0.3 && h <= 0.65) { dots.add(s); continue; }
            if (s.curved && h > 5.5 || s.curved && h > 2.5 && w > 2.2) {
                if (clef == null || s.x0 < clef.x0) clef = s;
                continue;
            }
            others.add(s);
        }

        identifyClef(st, clef, sh);
        for (Shape a : meterDigits) {
            for (Shape b : meterDigits) {
                if (a == b || Math.abs(a.x0 - b.x0) > 0.6 * sp || a.cy() >= b.cy()) continue;
                double num = a.glyph - '0', den = b.glyph - '0';
                st.meters.add(new double[]{a.x0, num * 4 / den});
            }
        }
        st.meters.sort(Comparator.comparingDouble(m -> m[0]));
        if (st.glyphClef != 0) {
            switch (st.glyphClef) {
                case '?': st.clef = "bass"; st.bottomDiatonic = 18; break;
                case 'V': st.clef = "treble-8"; st.bottomDiatonic = 23; break;
                case 'B': st.clef = "alto"; st.bottomDiatonic = 24; break;
                default: st.clef = "treble"; st.bottomDiatonic = 30; break;
            }
            clef = null;
        }

        // bar lines drawn through several staves belong to the shape list of only one of them
        for (Shape b : st.pageShapes) {
            if (barCand.contains(b) || b.glyph != 0) continue;
            if (b.w() < 0.6 * sp && b.h() > 3.7 * sp && b.y0 <= st.top() + 0.3 * sp
                    && b.y1 >= st.bottom() - 0.3 * sp && b.cx() > st.x0 + 0.5 * sp && b.cx() < st.x1 + sp
                    && (b.fill || b.stroke)) {
                barCand.add(b);
            }
        }
        // a full-height vertical touching a note head is a stem, otherwise a bar line
        for (Shape b : barCand) {
            boolean stem = false;
            if (b.w() < 0.3 * sp) {
                for (Head hd : heads) {
                    if (hd.kind == 4) continue;
                    boolean edge = Math.abs(b.x1 - hd.s.x1) < 0.2 * sp || Math.abs(b.x0 - hd.s.x0) < 0.2 * sp;
                    boolean end = Math.abs(b.y1 - hd.s.cy()) < 0.7 * sp || Math.abs(b.y0 - hd.s.cy()) < 0.7 * sp;
                    if (edge && end) { stem = true; break; }
                }
            }
            if (stem) stems.add(b);
            else bars.add(b.cx());
        }

        // stems -> heads. Normal: right edge on an up stem, left edge on a down stem.
        // In chords with seconds a head sits on the other side: right of an up stem,
        // left of a down stem. The head must lie on the stem's note end.
        for (Head hd : heads) {
            if (hd.kind == 4) continue;
            Shape best = null;
            boolean bestUp = false;
            double bd = Double.MAX_VALUE;
            double cy = hd.s.cy();
            for (Shape s : stems) {
                // the stem reaches well beyond the head on its free end (>= 1.5 spaces)
                boolean upY = cy >= s.y0 + 1.5 * sp && cy <= s.y1 + 0.6 * sp;
                boolean downY = cy >= s.y0 - 0.6 * sp && cy <= s.y1 - 1.5 * sp;
                double[] cand = {
                        upY ? Math.abs(s.x1 - hd.s.x1) : 1e9,                    // up, normal
                        downY ? Math.abs(s.x0 - hd.s.x0) : 1e9,                  // down, normal
                        upY ? Math.abs(hd.s.x0 - s.x1) + 0.1 * sp : 1e9,         // up, head right of stem
                        downY ? Math.abs(hd.s.x1 - s.x0) + 0.1 * sp : 1e9};      // down, head left of stem
                for (int k = 0; k < 4; k++) {
                    if (cand[k] < 0.3 * sp && cand[k] < bd) {
                        bd = cand[k];
                        best = s;
                        bestUp = k == 0 || k == 2;
                    }
                }
            }
            if (best != null) {
                hd.stem = best;
                hd.stemUp = bestUp;
            }
        }

        // two equal heads at one place (unison of two voices): one per stem direction
        for (int i = 0; i < heads.size(); i++) {
            for (int j = i + 1; j < heads.size(); j++) {
                Head a = heads.get(i), b = heads.get(j);
                if (a.stem == null || a.stem != b.stem) continue;
                if (Math.abs(a.s.x0 - b.s.x0) > 0.3 * sp || Math.abs(a.s.cy() - b.s.cy()) > 0.3 * sp) continue;
                for (Shape stm : stems) {
                    if (stm == a.stem) continue;
                    double cy = b.s.cy();
                    boolean up = Math.abs(stm.x1 - b.s.x1) < 0.2 * sp && cy <= stm.y1 + 0.5 * sp && cy >= stm.y0 + 0.5 * sp;
                    boolean down = Math.abs(stm.x0 - b.s.x0) < 0.2 * sp && cy >= stm.y0 - 0.5 * sp && cy <= stm.y1 - 0.5 * sp;
                    if (up || down) { b.stem = stm; b.stemUp = up; break; }
                }
            }
        }

        // flags (attached to a stem tip)
        Map<Shape, Integer> flagCount = new HashMap<>();
        for (Map.Entry<Shape, Integer> fe : glyphFlags.entrySet()) {
            Shape f = fe.getKey();
            Shape best = null;
            double bd = Double.MAX_VALUE;
            for (Shape stm : stems) {
                double d = Math.abs(stm.cx() - f.x0) + Math.min(Math.abs(stm.y0 - f.cy()), Math.abs(stm.y1 - f.cy()));
                if (Math.abs(stm.cx() - f.x0) < 0.6 * sp && d < bd) { bd = d; best = stm; }
            }
            if (best != null) flagCount.put(best, fe.getValue());
        }
        List<Shape> rest = new ArrayList<>();
        for (Shape s : others) {
            double w = s.w() / sp, h = s.h() / sp;
            Shape stem = null;
            if (s.curved && w >= 0.65 && w <= 1.3 && h >= 2.2 && h <= 3.5) {
                for (Shape stm : stems) {
                    if (Math.abs(s.x0 - stm.x0) < 0.4 * sp
                            && (Math.abs(s.y0 - stm.y0) < 0.5 * sp || Math.abs(s.y1 - stm.y1) < 0.5 * sp)) {
                        stem = stm;
                        break;
                    }
                }
            }
            if (stem != null) {
                flagCount.put(stem, Math.max(1, Math.min(3, s.subpaths)));
            } else {
                rest.add(s);
            }
        }
        others = rest;

        // staff positions
        for (Head hd : heads) hd.pos = (int) Math.round((st.bottom() - hd.s.cy()) / (sp / 2));

        // first musical x (after clef / key / time)
        double firstX = Double.MAX_VALUE;
        for (Head hd : heads) firstX = Math.min(firstX, hd.s.x0);
        for (Shape r : rectRests) firstX = Math.min(firstX, r.x0);

        // accidentals and quarter / eighth rests among the remaining shapes
        restGlyphs.addAll(rectRests);
        for (Shape r : rectRests) {
            // whole rest hangs from a line, half rest sits on a line
            boolean hangs = false;
            for (double ly : st.lines) if (Math.abs(r.y0 - ly) < 0.3 * sp) hangs = true;
            restBeats.put(r, hangs ? -1 : 2);   // -1: whole-bar rest, resolved later
        }
        // time signatures drawn as paths (abcjs): two digits stacked in the upper and lower half
        // of the staff at the same x – not rests, also in the middle of a line (metre change)
        java.util.Set<Shape> timeSig = new java.util.HashSet<>();
        for (Shape a : others) {
            if (!a.curved || a.glyph != 0) continue;
            double ha = a.h() / sp;
            if (ha < 1.6 || ha > 2.2) continue;
            for (Shape b : others) {
                if (b == a || !b.curved || b.glyph != 0) continue;
                double hb = b.h() / sp;
                if (hb < 1.6 || hb > 2.2 || Math.abs(a.cx() - b.cx()) > 0.5 * sp) continue;
                boolean aTop = Math.abs(a.cy() - (st.lines[0] + st.lines[2]) / 2) < 0.4 * sp
                        && Math.abs(b.cy() - (st.lines[2] + st.lines[4]) / 2) < 0.4 * sp;
                if (aTop) {
                    timeSig.add(a);
                    timeSig.add(b);
                }
            }
        }
        others.removeAll(timeSig);
        List<Shape> keyAcc = new ArrayList<>();
        for (Shape s : others) {
            double w = s.w() / sp, h = s.h() / sp;
            boolean accSize = s.curved && h >= 2.1 && h <= 3.3 && w >= 0.55 && w <= 1.25;
            if (accSize && s.subpaths >= 2) {
                if (s.x1 < firstX - 0.2 * sp) keyAcc.add(s);
                else accs.add(s);
                continue;
            }
            // bitmap accidentals have no "hole" flag: take them by position (key signature zone,
            // or right in front of a head on the same staff position)
            if (accSize && s.ink >= 0) {
                if (s.x1 < firstX - 0.2 * sp) { keyAcc.add(s); continue; }
                boolean beforeHead = false;
                for (Head hd : heads) {
                    double dx = hd.s.x0 - s.x1;
                    if (dx < -0.2 * sp || dx > 2.0 * sp) continue;
                    int type = accType(s, sp);
                    if (accPos(s, type, st) == hd.pos) beforeHead = true;
                }
                if (beforeHead) { accs.add(s); continue; }
            }
            if (s.curved && w >= 0.85 && w <= 1.5 && h >= 2.4 && h <= 3.3 && s.x0 > firstX - sp) {
                // quarter rest (narrow) or 16th rest (wider, two hooks)
                restGlyphs.add(s);
                restBeats.put(s, w >= 1.2 ? 25 : 100);     // hundredths of a beat
                continue;
            }
            if (s.curved && w >= 0.8 && w <= 1.3 && h >= 1.4 && h <= 2.2 && s.x0 > firstX - sp) {
                restGlyphs.add(s);
                restBeats.put(s, 50);
                continue;
            }
            if (s.curved && h < 2.2 && w > 1.2) { curves.add(s); continue; }
            if (s.curved && w >= 0.55 && w <= 1.25 && h >= 0.9 && h <= 1.7 && clef != null
                    && s.cx() > clef.x0 && s.cx() < clef.x1 && s.y0 > clef.y1 - 0.5 * sp) {
                // small "8" under a treble clef: octave lower
                if (st.clef.equals("treble")) { st.clef = "treble-8"; st.bottomDiatonic -= 7; }
            }
        }
        // rect rests in the first column belong to the music too
        for (Shape r : rectRests) restBeats.putIfAbsent(r, 2);

        for (Shape a : glyphAccs) {
            int type = a.glyph == '#' ? 1 : a.glyph == 'b' ? -1 : 2;
            int pos = (int) Math.round((st.bottom() - a.cy()) / (sp / 2));
            if (a.x1 < firstX - 0.2 * sp) {
                if (type != 2) st.keySig.put(Math.floorMod(st.bottomDiatonic + pos, 7), type);
                continue;
            }
            Head best = null;
            double bd = Double.MAX_VALUE;
            for (Head hd : heads) {
                double dx = hd.s.x0 - a.x1;
                if (dx < -0.2 * sp || dx > 2.5 * sp || hd.pos != pos) continue;
                if (dx < bd) { bd = dx; best = hd; }
            }
            if (best != null) best.alter = type;
        }

        // key signature
        for (Shape a : keyAcc) {
            int type = accType(a, sp);
            int pos = accPos(a, type, st);
            int letter = Math.floorMod(st.bottomDiatonic + pos, 7);
            if (type != 0) st.keySig.put(letter, type);
        }

        // accidentals in front of heads
        for (Shape a : accs) {
            int type = accType(a, sp);
            Head best = null;
            double bd = Double.MAX_VALUE;
            for (Head hd : heads) {
                double dx = hd.s.x0 - a.x1;
                if (dx < -0.2 * sp || dx > 2.5 * sp) continue;
                int pos = accPos(a, type, st);
                if (Math.abs(pos - hd.pos) > 0) continue;
                if (dx < bd) { bd = dx; best = hd; }
            }
            if (best != null) best.alter = type;
        }

        // dots
        for (Shape d : dots) {
            Head best = null;
            double bd = Double.MAX_VALUE;
            for (Head hd : heads) {
                double dx = d.x0 - hd.s.x1;
                if (dx < -0.1 * sp || dx > 1.6 * sp || Math.abs(d.cy() - hd.s.cy()) > 0.8 * sp) continue;
                if (dx < bd) { bd = dx; best = hd; }
            }
            if (best != null) { best.dotted = true; continue; }
            // two dots in the middle spaces next to a bar line: repeat sign
            boolean inMiddle = Math.abs(d.cy() - (st.lines[1] + st.lines[2]) / 2) < 0.3 * sp
                    || Math.abs(d.cy() - (st.lines[2] + st.lines[3]) / 2) < 0.3 * sp;
            if (inMiddle) {
                for (Shape b : barCand) {
                    if (Math.abs(d.cx() - b.cx()) < 1.6 * sp) {
                        if (!warn.contains(REPEAT_WARNING)) warn.add(REPEAT_WARNING);
                        break;
                    }
                }
            }
            for (Shape r : restGlyphs) {
                double dx = d.x0 - r.x1;
                if (dx >= -0.1 * sp && dx < 1.6 * sp && d.cy() > r.y0 - sp && d.cy() < r.y1 + sp) {
                    restBeats.put(r, (int) Math.round(restBeats.get(r) * 1.5));
                    break;
                }
            }
        }

        // ---- group heads into events (chords)
        List<Event> events = new ArrayList<>();
        Map<Shape, Event> byStem = new HashMap<>();
        List<Head> stemless = new ArrayList<>();
        for (Head hd : heads) {
            if (hd.stem != null) {
                Event e = byStem.get(hd.stem);
                if (e == null) {
                    e = new Event();
                    byStem.put(hd.stem, e);
                    events.add(e);
                }
                e.heads.add(hd);
            } else {
                stemless.add(hd);
            }
        }
        stemless.sort(Comparator.comparingDouble(h -> h.s.cx()));

        // decide voice count on this staff
        int voices = st.voiceCount;
        if (voices == 0) {
            int both = 0;
            for (Event a : events) for (Event b : events) {
                if (a != b && a.heads.get(0).stemUp && !b.heads.get(0).stemUp
                        && Math.abs(a.heads.get(0).s.cx() - b.heads.get(0).s.cx()) < 0.6 * sp) both++;
            }
            // a single voice turns its stems by pitch; two voices keep voice 1 up and voice 2 down,
            // so an up stem on a high note (or a down stem on a low one) means two voices
            int contrary = 0;
            for (Event a : events) {
                Head h0 = a.heads.get(0);
                int top = Integer.MIN_VALUE, low = Integer.MAX_VALUE;
                for (Head h : a.heads) { top = Math.max(top, h.pos); low = Math.min(low, h.pos); }
                if (h0.stemUp && low > 5) contrary++;
                if (!h0.stemUp && top < 3) contrary++;
            }
            voices = both >= 1 || contrary >= 2 ? 2 : 1;
        }

        for (Event e : events) {
            Head h0 = e.heads.get(0);
            e.voice = voices == 2 ? (h0.stemUp ? 0 : 1) : 0;
            double base = h0.kind == 2 ? 2 : 1;
            Integer fl = flagCount.get(h0.stem);
            int n = fl != null ? fl : countBeams(h0.stem, h0.stemUp, beams, stems, sp);
            if (h0.kind == 1 && n > 0) base = Math.pow(0.5, n);
            boolean dotted = false;
            for (Head hd : e.heads) dotted |= hd.dotted;
            e.dur = dotted ? base * 1.5 : base;
        }
        // stemless heads: cluster by x
        List<List<Head>> clusters = new ArrayList<>();
        for (Head hd : stemless) {
            List<Head> last = clusters.isEmpty() ? null : clusters.get(clusters.size() - 1);
            if (last != null && Math.abs(last.get(0).s.cx() - hd.s.cx()) < 0.6 * sp) last.add(hd);
            else {
                List<Head> c = new ArrayList<>();
                c.add(hd);
                clusters.add(c);
            }
        }
        List<List<Head>> pending = new ArrayList<>();   // single stemless heads in 2-voice staves
        for (List<Head> c : clusters) {
            c.sort((a, b) -> Integer.compare(b.pos, a.pos));     // top first
            if (voices == 1) {
                events.add(stemlessEvent(c, 0));
            } else if (c.size() >= 2) {
                List<Head> top = new ArrayList<>(), low = new ArrayList<>();
                for (int i = 0; i < c.size(); i++) (i < (c.size() + 1) / 2 ? top : low).add(c.get(i));
                events.add(stemlessEvent(top, 0));
                events.add(stemlessEvent(low, 1));
            } else {
                // other voice present at the same x?
                Head hd = c.get(0);
                int v = -1;
                for (Event e : events) {
                    if (e.heads.isEmpty() || Math.abs(e.heads.get(0).s.cx() - hd.s.cx()) > 0.6 * sp) continue;
                    int other = e.voice;
                    int topPos = Integer.MIN_VALUE;
                    for (Head x : e.heads) topPos = Math.max(topPos, x.pos);
                    v = hd.pos > topPos ? 0 : 1;
                    if (v == other) v = 1 - other;
                    break;
                }
                if (v >= 0) events.add(stemlessEvent(c, v));
                else pending.add(c);
            }
        }

        // rests
        for (Shape r : restGlyphs) {
            Integer b = restBeats.get(r);
            if (b == null) continue;
            Event e = new Event();
            e.rest = true;
            e.x = r.x0;
            e.cx = r.cx();
            e.cy = r.cy();
            // -1 = whole-bar rest; 2/3 = (dotted) half rest in beats; else hundredths of a beat
            e.dur = b == -1 ? -1 : (b <= 4 ? b : b / 100.0);
            e.voice = voices == 2 ? (r.cy() < st.mid() - 0.3 * sp ? 0 : r.cy() > st.mid() + 0.3 * sp ? 1 : -2) : 0;
            events.add(e);
        }
        for (Event e : events) {
            if (!e.rest) {
                Head h0 = e.heads.get(0);
                e.x = h0.s.x0;
                e.cx = h0.s.cx();
                e.cy = h0.s.cy();
            }
        }

        // pending single stemless heads and centred rests: give to the voice that is behind in time
        List<Event> v0 = new ArrayList<>(), v1 = new ArrayList<>();
        for (List<Head> c : pending) {
            Event e = stemlessEvent(c, -2);
            e.x = c.get(0).s.x0; e.cx = c.get(0).s.cx(); e.cy = c.get(0).s.cy();
            events.add(e);
        }
        events.sort(Comparator.comparingDouble(e -> e.cx));
        double t0 = 0, t1 = 0;
        List<Event> twins = new ArrayList<>();
        for (Event e : events) {
            if (e.dur < 0) { e.barRest = true; e.dur = 0; }    // resolved later
            if (e.voice == -2) {
                if (e.barRest && voices == 2) {
                    // one bar rest in the middle of a two-voice staff: both voices rest
                    e.voice = 0;
                    Event t = new Event();
                    t.rest = true; t.barRest = true; t.voice = 1;
                    t.x = e.x; t.cx = e.cx; t.cy = e.cy;
                    twins.add(t);
                } else {
                    e.voice = t0 <= t1 ? 0 : 1;
                }
            }
            if (e.voice == 0) t0 += e.dur; else t1 += e.dur;
        }
        events.addAll(twins);
        events.sort(Comparator.comparingDouble(e -> e.cx));

        // tuplets: digit near the notes
        for (TextItem num : st.numbers) {
            if (num.x0 < st.x0 + 4 * sp) continue;     // bar number at the start of a line
            int n = Integer.parseInt(num.text);
            double factor = n == 3 ? 2.0 / 3 : n == 2 ? 1.5 : n == 4 ? 0.75 : n == 6 ? 2.0 / 3 : n == 5 ? 0.8 : 1;
            if (factor == 1) continue;
            double cx = (num.x0 + num.x1) / 2;
            Event near = null;
            for (Event e : events) if (!e.rest && (near == null || Math.abs(e.cx - cx) < Math.abs(near.cx - cx))) near = e;
            if (near == null) continue;
            List<Event> vv = new ArrayList<>();
            for (Event e : events) if (e.voice == near.voice) vv.add(e);
            int bestI = -1;
            double bd = Double.MAX_VALUE;
            for (int i = 0; i + n <= vv.size(); i++) {
                double c = (vv.get(i).cx + vv.get(i + n - 1).cx) / 2;
                if (Math.abs(c - cx) < bd) { bd = Math.abs(c - cx); bestI = i; }
            }
            if (bestI >= 0 && bd < 4 * sp) for (int i = bestI; i < bestI + n; i++) vv.get(i).dur *= factor;
        }

        // whole-bar rests: length = longest other voice in that bar
        List<List<Event>> perVoice = new ArrayList<>();
        int nv = Math.max(1, voices);
        for (int v = 0; v < nv; v++) perVoice.add(new ArrayList<>());
        for (Event e : events) perVoice.get(Math.min(e.voice, nv - 1)).add(e);
        bars.sort(Double::compare);
        // lines in front of the first note/rest (system bracket, line start) don't divide bars
        double firstEvent = Double.MAX_VALUE;
        for (Event e : events) firstEvent = Math.min(firstEvent, e.x);
        for (int i = bars.size() - 1; i >= 0; i--) {
            if (bars.get(i) < firstEvent - 0.3 * sp) bars.remove(i);
        }
        for (int i = bars.size() - 1; i > 0; i--) {
            if (bars.get(i) - bars.get(i - 1) < 1.2 * sp) bars.remove(i);   // double / final bar lines
        }
        st.barXs = bars;
        for (Event e : events) e.bar = barIndex(bars, e.cx);

        // pitches with accidentals (bar-scoped)
        for (List<Event> list : perVoice) {
            Map<Integer, Integer> barAcc = new HashMap<>();
            int bar = -1;
            for (Event e : list) {
                int b = barIndex(bars, e.cx);
                if (b != bar) { barAcc.clear(); bar = b; }
                if (e.rest) continue;
                e.midi = new int[e.heads.size()];
                for (int i = 0; i < e.heads.size(); i++) {
                    Head hd = e.heads.get(i);
                    int d = st.bottomDiatonic + hd.pos;
                    int alter;
                    if (hd.alter != Integer.MIN_VALUE) {
                        alter = hd.alter == 2 ? 0 : hd.alter;
                        barAcc.put(d, alter);
                    } else if (barAcc.containsKey(d)) {
                        alter = barAcc.get(d);
                    } else {
                        Integer k = st.keySig.get(Math.floorMod(d, 7));
                        alter = k == null ? 0 : k;
                    }
                    e.midi[i] = diatonicToMidi(d) + alter;
                }
            }
        }

        fixBarRests(perVoice, bars);
        if (perVoice.size() == 2) shareRests(perVoice, sp);

        // ties
        for (Shape c : curves) {
            for (List<Event> list : perVoice) {
                for (int i = 0; i < list.size(); i++) {
                    Event e = list.get(i);
                    if (e.rest) continue;
                    boolean startsHere = c.x0 >= e.cx - 0.6 * sp && c.x0 <= e.cx + 1.6 * sp
                            && Math.abs(c.cy() - e.cy) < 2.2 * sp;
                    if (!startsHere) continue;
                    Event nx = null;
                    for (int j = i + 1; j < list.size(); j++) { nx = list.get(j); break; }
                    if (nx == null) {
                        if (c.x1 > st.x1 - 3 * sp) e.tieToNext = true;    // tie into next line
                    } else if (!nx.rest && samePitches(e.midi, nx.midi) && c.x1 >= nx.x - 1.2 * sp
                            && c.x1 <= nx.cx + 1.2 * sp) {
                        e.tieToNext = true;
                    }
                }
            }
        }
        for (List<Event> list : perVoice) {
            for (int i = 0; i + 1 < list.size(); i++) {
                Event e = list.get(i), nx = list.get(i + 1);
                if (e.tieToNext && !nx.rest && samePitches(e.midi, nx.midi)) {
                    e.dur += nx.dur;
                    e.tieToNext = nx.tieToNext;
                    if (e.syllable == null) e.syllable = nx.syllable;
                    list.remove(i + 1);
                    i--;
                }
            }
        }

        assignLyrics(st, perVoice.get(0), sp);
        return perVoice;
    }

    private static Event stemlessEvent(List<Head> c, int voice) {
        Event e = new Event();
        e.heads.addAll(c);
        e.voice = voice;
        int kind = c.get(0).kind;
        boolean dotted = false;
        for (Head hd : c) dotted |= hd.dotted;
        double base = kind == 4 ? 4 : kind == 2 ? 2 : 1;
        e.dur = dotted ? base * 1.5 : base;
        return e;
    }

    private static int countBeams(Shape stem, boolean up, List<Shape> beams, List<Shape> stems, double sp) {
        if (stem == null) return 0;
        double x = stem.cx();
        double tip = up ? stem.y0 : stem.y1;
        int n = 0;
        for (Shape b : beams) {
            for (double[] r : b.subs) {
                if (x < r[0] - 0.3 || x > r[2] + 0.3) continue;
                if (r[2] - r[0] > 1.6 * sp && stemsUnder(r, stems) < 2) continue;   // hairpin, not a beam
                double lo = up ? tip - 0.6 * sp : tip - 2.6 * sp;
                double hi = up ? tip + 2.6 * sp : tip + 0.6 * sp;
                if (r[3] >= lo && r[1] <= hi) n++;
            }
        }
        return Math.min(n, 4);
    }

    private static int stemsUnder(double[] r, List<Shape> stems) {
        int n = 0;
        for (Shape s : stems) {
            boolean inX = s.cx() >= r[0] - 0.5 && s.cx() <= r[2] + 0.5;
            boolean touches = s.y0 <= r[3] + 1 && s.y1 >= r[1] - 1;
            if (inX && touches) n++;
        }
        return n;
    }

    private static int barIndex(List<Double> bars, double x) {
        int i = 0;
        for (double b : bars) { if (b < x) i++; else break; }
        return i;
    }

    /**
     * Engravers often print one rest for both voices of a staff. If a voice is shorter in a bar
     * and the other voice has a rest where this voice has nothing, the rest counts for both.
     */
    private static void shareRests(List<List<Event>> pv, double sp) {
        for (int v = 0; v < 2; v++) {
            List<Event> me = pv.get(v), other = pv.get(1 - v);
            java.util.Set<Integer> barsSeen = new java.util.TreeSet<>();
            for (Event e : other) barsSeen.add(e.bar);
            for (int b : barsSeen) {
                double mine = 0, theirs = 0;
                for (Event e : me) if (e.bar == b) mine += e.dur;
                for (Event e : other) if (e.bar == b) theirs += e.dur;
                for (Event r : other) {
                    if (mine >= theirs - 1e-6) break;
                    if (r.bar != b || !r.rest || r.barRest) continue;
                    boolean occupied = false;
                    for (Event e : me) if (Math.abs(e.cx - r.cx) < 0.6 * sp) occupied = true;
                    if (occupied || mine + r.dur > theirs + 1e-6) continue;
                    Event c = new Event();
                    c.rest = true; c.x = r.x; c.cx = r.cx; c.cy = r.cy; c.dur = r.dur; c.bar = b; c.voice = v;
                    me.add(c);
                    mine += r.dur;
                }
            }
            me.sort(Comparator.comparingDouble(e -> e.cx));
        }
    }

    private static void fixBarRests(List<List<Event>> perVoice, List<Double> bars) {
        // a whole-bar rest gets the length of the same bar in another voice
        for (List<Event> list : perVoice) {
            for (Event e : list) {
                if (!(e.rest && e.barRest && e.dur == 0)) continue;
                int b = e.bar;
                double best = 0;
                for (List<Event> other : perVoice) {
                    if (other == list) continue;
                    double sum = 0;
                    for (Event o : other) if (o.bar == b) sum += o.dur;
                    best = Math.max(best, sum);
                }
                if (best > 0) e.dur = best;
            }
        }
    }

    /** -1 flat, 1 sharp, 2 natural */
    private static int accType(Shape a, double sp) {
        double w = a.w() / sp, h = a.h() / sp;
        if (w < 0.8) return 2;
        if (h < 2.7) return -1;
        return 1;
    }

    private static int accPos(Shape a, int type, Staff st) {
        double y = type == -1 ? a.y1 - 0.62 * st.sp : a.cy();
        return (int) Math.round((st.bottom() - y) / (st.sp / 2));
    }

    private static void identifyClef(Staff st, Shape clef, List<Shape> all) {
        double sp = st.sp;
        if (clef == null) {
            st.clef = "treble";
            st.bottomDiatonic = 30;
            return;
        }
        double h = clef.h() / sp;
        if (h > 5.5) {
            st.clef = "treble";
            st.bottomDiatonic = 30;              // E4
        } else if (h < 3.5) {
            st.clef = "bass";
            st.bottomDiatonic = 18;              // G2
        } else if (Math.abs(clef.cy() - st.lines[1]) < 0.5 * sp) {
            st.clef = "tenor";
            st.bottomDiatonic = 22;              // D3
        } else {
            st.clef = "alto";
            st.bottomDiatonic = 24;              // F3
        }
    }

    static int diatonicToMidi(int d) {
        int[] semi = {0, 2, 4, 5, 7, 9, 11};
        int oct = Math.floorDiv(d, 7);
        return 12 * (oct + 1) + semi[Math.floorMod(d, 7)];
    }

    // ------------------------------------------------------------ lyrics

    static void assignLyrics(Staff st, List<Event> voice, double sp) {
        if (st.lyrics.isEmpty()) return;
        // first lyric line only (closest to the staff)
        double first = Double.MAX_VALUE;
        for (TextItem t : st.lyrics) first = Math.min(first, t.baseline);
        List<TextItem> syl = new ArrayList<>();
        List<TextItem> dashes = new ArrayList<>();
        for (TextItem t : st.lyrics) {
            if (Math.abs(t.baseline - first) > 0.5 * t.fontSize) continue;
            if (t.text.matches("[-_]+")) { dashes.add(t); continue; }
            syl.addAll(splitSyllables(t));
        }
        syl.sort(Comparator.comparingDouble(t -> t.x0));
        // a dash printed on its own between two syllables joins them into one word
        for (TextItem d : dashes) {
            if (!d.text.startsWith("-")) continue;
            TextItem left = null;
            for (TextItem t : syl) if (t.x1 <= d.x0 + 0.5) left = t;
            if (left != null && !left.text.endsWith("-") && !isBlank(left.text)) left.text = left.text + "-";
        }
        List<Event> notes = new ArrayList<>();
        for (Event e : voice) if (!e.rest) notes.add(e);
        int S = syl.size(), N = notes.size();
        if (S == 0 || N == 0) return;
        // monotone alignment, minimum total distance (text centre ≈ left edge of head)
        double INF = 1e18;
        // move: 1 = syllable on note, 2 = note without syllable, 3 = syllable dropped
        double drop = 4 * sp;
        double[][] dp = new double[S + 1][N + 1];
        int[][] move = new int[S + 1][N + 1];
        for (double[] r : dp) java.util.Arrays.fill(r, INF);
        dp[0][0] = 0;
        for (int j = 1; j <= N; j++) { dp[0][j] = 0; move[0][j] = 2; }
        for (int i = 1; i <= S; i++) { dp[i][0] = i * drop; move[i][0] = 3; }
        for (int i = 1; i <= S; i++) {
            TextItem t = syl.get(i - 1);
            double c = (t.x0 + t.x1) / 2;
            for (int j = 1; j <= N; j++) {
                Event ne = notes.get(j - 1);
                double use = dp[i - 1][j - 1] + Math.abs(c - (st.glyphMode ? ne.cx : ne.x));
                double skipNote = dp[i][j - 1];
                double skipSyl = dp[i - 1][j] + drop;
                if (use <= skipNote && use <= skipSyl) { dp[i][j] = use; move[i][j] = 1; }
                else if (skipNote <= skipSyl) { dp[i][j] = skipNote; move[i][j] = 2; }
                else { dp[i][j] = skipSyl; move[i][j] = 3; }
            }
        }
        int i = S, j = N;
        List<TextItem> dropped = new ArrayList<>();
        while (i > 0 && j > 0) {
            if (move[i][j] == 1) {
                String s = syl.get(i - 1).text;
                if (!isBlank(s)) notes.get(j - 1).syllable = s;
                i--; j--;
            } else if (move[i][j] == 2) {
                j--;
            } else {
                dropped.add(syl.get(i - 1));
                i--;
            }
        }
        while (i > 0) dropped.add(syl.get(--i));
        recitation(notes, dropped, sp);
    }

    /**
     * More text than notes: syllables printed in the gap after a note are all sung on that note
     * (recitation tone). They are joined to its syllable – words by spaces (written as ~ in ABC),
     * parts of one word directly – and the note is marked; it is held as a breve.
     */
    private static void recitation(List<Event> notes, List<TextItem> dropped, double sp) {
        dropped.sort(Comparator.comparingDouble(t -> t.x0));
        Map<Event, List<TextItem>> extra = new java.util.LinkedHashMap<>();
        for (TextItem t : dropped) {
            if (isBlank(t.text)) continue;
            double c = (t.x0 + t.x1) / 2;
            Event prev = null, next = null;
            for (Event e : notes) {
                if (e.cx < c - 0.3 * sp) prev = e;
                else { next = e; break; }
            }
            // the reciting tone is the note without stem (breve, whole or black head) next to
            // the text; the text can stand before it (Obikhod) or after it
            Event target;
            if (next != null && stemless(next)) target = next;
            else if (prev != null) target = prev;
            else target = next;
            if (target == null) continue;
            extra.computeIfAbsent(target, k -> new ArrayList<>()).add(t);
        }
        for (Map.Entry<Event, List<TextItem>> en : extra.entrySet()) {
            Event e = en.getKey();
            // a recitation tone: a note without stem, or one carrying at least two extra
            // syllables. A single leftover syllable elsewhere is a reading slip.
            if (!stemless(e) && en.getValue().size() < 2) continue;
            List<TextItem> parts = new ArrayList<>(en.getValue());
            if (e.syllable != null) {
                TextItem own = new TextItem();
                own.text = e.syllable;
                own.x0 = e.cx - 0.1;
                own.x1 = e.cx + 0.1;
                parts.add(own);
            }
            parts.sort(Comparator.comparingDouble(t -> (t.x0 + t.x1) / 2));
            StringBuilder sb = new StringBuilder();
            for (TextItem part : parts) {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') sb.setLength(sb.length() - 1);
                else if (sb.length() > 0) sb.append(' ');
                sb.append(part.text);
            }
            e.syllable = sb.toString();
            e.recite = true;
        }
    }

    private static boolean stemless(Event e) {
        return !e.rest && !e.heads.isEmpty() && e.heads.get(0).stem == null;
    }

    private static List<TextItem> splitSyllables(TextItem t) {
        List<TextItem> out = new ArrayList<>();
        String s = t.text;
        int n = s.length();
        int start = 0;
        for (int k = 0; k < n; k++) {
            boolean cut = s.charAt(k) == '-' && k + 1 < n && s.charAt(k + 1) != '-';
            if (cut || k == n - 1) {
                int end = k + 1;
                TextItem p = new TextItem();
                p.text = s.substring(start, end);
                p.x0 = t.x0 + (t.x1 - t.x0) * start / n;
                p.x1 = t.x0 + (t.x1 - t.x0) * end / n;
                p.baseline = t.baseline;
                p.fontSize = t.fontSize;
                out.add(p);
                start = end;
            }
        }
        return out;
    }
}
