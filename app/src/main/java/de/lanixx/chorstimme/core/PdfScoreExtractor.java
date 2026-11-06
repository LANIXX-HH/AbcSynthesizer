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

        // voice layout from the first system: from the labels ("Sopran", "Alt") if there are any,
        // otherwise detected from the stems (two voices on a staff = stems up and down)
        int sys0Staves = 0;
        for (Staff st : staves) if (st.system == 0) sys0Staves++;
        boolean anyLabels = false;
        for (Staff st : staves) if (st.system == 0 && !st.labels.isEmpty()) anyLabels = true;
        List<String> names = new ArrayList<>();
        int[] voicesPerStaff = new int[sys0Staves];
        Map<Staff, List<List<Event>>> firstRead = new HashMap<>();
        for (Staff st : staves) {
            if (st.system != 0) continue;
            st.glyphMode = glyphMode;
            List<TextItem> lab = mergeLabels(st.labels);
            int n;
            if (anyLabels) {
                n = Math.max(1, Math.min(2, lab.size()));
            } else {
                st.voiceCount = 0;
                List<List<Event>> evs = readStaff(st, score.warnings, 0);
                firstRead.put(st, evs);
                n = Math.max(1, Math.min(2, evs.size()));
            }
            voicesPerStaff[st.indexInSystem] = n;
            for (int v = 0; v < n; v++) names.add(v < lab.size() ? lab.get(v).text : null);
        }
        if (!anyLabels) {
            String[] satb = {"Sopran", "Alt", "Tenor", "Bass"};
            String[] sa = {"Sopran", "Alt"};
            String[] std = names.size() == 4 ? satb : names.size() == 2 ? sa : null;
            for (int v = 0; v < names.size(); v++) names.set(v, std != null ? std[v] : "Stimme " + (v + 1));
        }

        int[] offset = new int[sys0Staves];
        int total = 0;
        for (int i = 0; i < sys0Staves; i++) { offset[i] = total; total += voicesPerStaff[i]; }
        for (int v = 0; v < total; v++) {
            String n = names.get(v);
            score.voices.add(new Score.Voice(n != null ? n : "Stimme " + (v + 1)));
        }

        double t = 0;
        int sysCount = 0;
        for (Staff st : staves) sysCount = Math.max(sysCount, st.system + 1);
        List<List<Event>> carry = new ArrayList<>();   // last event per voice for ties over line ends
        for (int v = 0; v < total; v++) carry.add(new ArrayList<>());
        Event[] lastEvent = new Event[total];

        for (int sys = 0; sys < sysCount; sys++) {
            double sysEnd = t;
            List<double[]> lens = new ArrayList<>();
            for (Staff st : staves) {
                if (st.system != sys) continue;
                if (st.indexInSystem >= sys0Staves) {
                    score.warnings.add("Zeile " + (sys + 1) + ": mehr Notensysteme als in der ersten Zeile – ignoriert.");
                    continue;
                }
                st.voiceCount = voicesPerStaff[st.indexInSystem];
                st.glyphMode = glyphMode;
                List<List<Event>> evs = firstRead.containsKey(st) ? firstRead.get(st) : readStaff(st, score.warnings, sys);
                while (evs.size() < st.voiceCount) evs.add(new ArrayList<>());
                for (int v = 0; v < evs.size() && v < voicesPerStaff[st.indexInSystem]; v++) {
                    int gv = offset[st.indexInSystem] + v;
                    Score.Voice voice = score.voices.get(gv);
                    double vt = t;
                    List<Event> list = evs.get(v);
                    for (int i = 0; i < list.size(); i++) {
                        Event e = list.get(i);
                        // tie from the end of the previous line
                        if (i == 0 && lastEvent[gv] != null && lastEvent[gv].tieToNext && !e.rest
                                && samePitches(lastEvent[gv].midi, e.midi)) {
                            Score.Note prev = voice.notes.get(voice.notes.size() - 1);
                            prev.duration += e.dur;
                            vt += e.dur;
                            lastEvent[gv] = e;
                            continue;
                        }
                        if (!e.rest) {
                            Score.Note n = new Score.Note(vt, e.dur, e.midi);
                            n.syllable = e.syllable;
                            voice.notes.add(n);
                        }
                        vt += e.dur;
                        lastEvent[gv] = e.rest ? null : e;
                    }
                    lens.add(new double[]{gv, vt - t});
                    sysEnd = Math.max(sysEnd, vt);
                }
            }
            // consistency check: all voices of a line should last equally long
            double mx = 0, mn = Double.MAX_VALUE;
            for (double[] l : lens) { mx = Math.max(mx, l[1]); mn = Math.min(mn, l[1]); }
            if (lens.size() > 1 && mx - mn > 0.01) {
                StringBuilder sb = new StringBuilder("Zeile " + (sys + 1) + ": Stimmen unterschiedlich lang (");
                for (int i = 0; i < lens.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(score.voices.get((int) lens.get(i)[0]).name).append(' ').append(fmt(lens.get(i)[1]));
                }
                sb.append(" Schläge) – Erkennung prüfen.");
                score.warnings.add(sb.toString());
            }
            t = sysEnd;
        }
        // drop empty voices
        for (int v = score.voices.size() - 1; v >= 0; v--) {
            if (score.voices.get(v).notes.isEmpty()) score.voices.remove(v);
        }
        if (score.noteCount() == 0) score.warnings.add("Keine Noten erkannt.");
        score.assignVowels();
        return score;
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
                        && y < Math.min(nextTop - 0.3 * st.sp, st.bottom() + 12 * st.sp)) {
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
            if (s.curved && h >= 0.85 && h <= 1.25 && w >= 1.05 && w <= 1.55) {
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
            if (!s.curved && w > 0.6 && h >= 0.35) { beams.add(s); continue; }
            if (s.curved && w >= 0.3 && w <= 0.65 && h >= 0.3 && h <= 0.65) { dots.add(s); continue; }
            if (s.curved && h > 5.5 || s.curved && h > 2.5 && w > 2.2) {
                if (clef == null || s.x0 < clef.x0) clef = s;
                continue;
            }
            others.add(s);
        }

        identifyClef(st, clef, sh);
        if (st.glyphClef != 0) {
            switch (st.glyphClef) {
                case '?': st.clef = "bass"; st.bottomDiatonic = 18; break;
                case 'V': st.clef = "treble-8"; st.bottomDiatonic = 23; break;
                case 'B': st.clef = "alto"; st.bottomDiatonic = 24; break;
                default: st.clef = "treble"; st.bottomDiatonic = 30; break;
            }
            clef = null;
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

        // stems -> heads: an up stem leaves the right edge of the lowest chord note upwards,
        // a down stem leaves the left edge of the highest note downwards
        for (Head hd : heads) {
            if (hd.kind == 4) continue;
            Shape best = null;
            boolean bestUp = false;
            double bd = Double.MAX_VALUE;
            double cy = hd.s.cy();
            for (Shape s : stems) {
                double dUp = Math.abs(s.x1 - hd.s.x1);
                if (dUp < 0.2 * sp && cy <= s.y1 + 0.5 * sp && cy >= s.y0 + 0.5 * sp && dUp < bd) {
                    bd = dUp; best = s; bestUp = true;
                }
                double dDown = Math.abs(s.x0 - hd.s.x0);
                if (dDown < 0.2 * sp && cy >= s.y0 - 0.5 * sp && cy <= s.y1 - 0.5 * sp && dDown < bd) {
                    bd = dDown; best = s; bestUp = false;
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
        List<Shape> keyAcc = new ArrayList<>();
        for (Shape s : others) {
            double w = s.w() / sp, h = s.h() / sp;
            if (s.curved && s.subpaths >= 2 && h >= 2.2 && h <= 3.3 && w >= 0.55 && w <= 1.25) {
                if (s.x1 < firstX - 0.2 * sp) keyAcc.add(s);
                else accs.add(s);
                continue;
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
            if (s.curved && w >= 0.55 && w <= 1.25 && h >= 1.1 && h <= 1.7 && clef != null
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
            voices = both >= 2 ? 2 : 1;
        }

        for (Event e : events) {
            Head h0 = e.heads.get(0);
            e.voice = voices == 2 ? (h0.stemUp ? 0 : 1) : 0;
            double base = h0.kind == 2 ? 2 : 1;
            Integer fl = flagCount.get(h0.stem);
            int n = fl != null ? fl : countBeams(h0.stem, h0.stemUp, beams, sp);
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
        for (Event e : events) {
            if (e.voice == -2) e.voice = t0 <= t1 ? 0 : 1;
            if (e.dur < 0) e.dur = 4;   // whole-bar rest: assume 4 beats, fixed below
            if (e.voice == 0) t0 += e.dur; else t1 += e.dur;
        }

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

    private static int countBeams(Shape stem, boolean up, List<Shape> beams, double sp) {
        if (stem == null) return 0;
        double x = stem.cx();
        double tip = up ? stem.y0 : stem.y1;
        int n = 0;
        for (Shape b : beams) {
            for (double[] r : b.subs) {
                if (x < r[0] - 0.3 || x > r[2] + 0.3) continue;
                double lo = up ? tip - 0.6 * sp : tip - 2.6 * sp;
                double hi = up ? tip + 2.6 * sp : tip + 0.6 * sp;
                if (r[3] >= lo && r[1] <= hi) n++;
            }
        }
        return Math.min(n, 4);
    }

    private static int barIndex(List<Double> bars, double x) {
        int i = 0;
        for (double b : bars) { if (b < x) i++; else break; }
        return i;
    }

    private static void fixBarRests(List<List<Event>> perVoice, List<Double> bars) {
        // a whole-bar rest gets the length of the same bar in another voice
        for (List<Event> list : perVoice) {
            for (Event e : list) {
                if (!(e.rest && e.dur == 4)) continue;
                int b = barIndex(bars, e.cx);
                double best = 0;
                for (List<Event> other : perVoice) {
                    if (other == list) continue;
                    double sum = 0;
                    for (Event o : other) if (barIndex(bars, o.cx) == b) sum += o.dur;
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
        for (TextItem t : st.lyrics) {
            if (Math.abs(t.baseline - first) > 0.5 * t.fontSize) continue;
            if (t.text.matches("[-_]+")) continue;
            syl.addAll(splitSyllables(t));
        }
        syl.sort(Comparator.comparingDouble(t -> t.x0));
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
        while (i > 0 && j > 0) {
            if (move[i][j] == 1) {
                String s = syl.get(i - 1).text;
                if (!isBlank(s)) notes.get(j - 1).syllable = s;
                i--; j--;
            } else if (move[i][j] == 2) {
                j--;
            } else {
                i--;
            }
        }
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
