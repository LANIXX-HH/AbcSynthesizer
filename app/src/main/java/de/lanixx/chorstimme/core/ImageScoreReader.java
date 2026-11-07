package de.lanixx.chorstimme.core;

import de.lanixx.chorstimme.core.PdfReader.PageData;
import de.lanixx.chorstimme.core.PdfReader.Shape;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reads sheet music from a picture (scan, screenshot, straight photo). The picture is turned
 * into the same shapes a PDF drawing gives – staff lines, stems/bar lines, and the remaining ink
 * blobs (heads, flags, rests, accidentals, clefs, ...) – and then {@link PdfScoreExtractor} does
 * the rest. Lyrics are not read (no text recognition).
 *
 * Steps: grey -> black/white (local threshold) -> straighten a slight tilt -> find staff lines ->
 * erase staff and ledger lines -> take out thin vertical lines (stems touching a head, bar lines)
 * -> connected ink blobs -> shapes, scaled so that one staff space is about 5.5 units.
 */
public final class ImageScoreReader {

    private ImageScoreReader() {}

    /** A grey picture, 0 = black, 255 = white, row by row. */
    public static final class Gray {
        public final int w, h;
        public final byte[] px;

        public Gray(int w, int h, byte[] px) {
            this.w = w;
            this.h = h;
            this.px = px;
        }

        int at(int x, int y) {
            return px[y * w + x] & 0xFF;
        }
    }

    private static final double TARGET_SP = 5.5;
    /** Staff space in pixels below which the picture is enlarged, and the size it is enlarged to. */
    private static final double MIN_SP_PX = 12, WORK_SP_PX = 14.5;

    public static List<PageData> read(Gray g) {
        int w = g.w, h = g.h;
        boolean[] ink = binarize(g);

        // slight tilt (scanner, photo held almost straight)
        double angle = skew(ink, w, h);
        if (Math.abs(angle) > 0.05) {
            // turn the grey picture (keeps stems vertical too) and threshold again
            g = rotate(g, Math.toRadians(angle));
            ink = binarize(g);
        }

        List<int[]> lines = staffLines(ink, w, h);           // {y0, y1, x0, x1}
        double sp = staffSpace(lines);
        double sourceSp = sp;
        // small pictures (messenger apps shrink photos): heads only 8-9 pixels high are too
        // coarse for hollow/filled and stem tests. Enlarge so a staff space is about 14 pixels
        if (sp > 0 && sp < MIN_SP_PX) {
            g = scale(g, WORK_SP_PX / sp);
            w = g.w;
            h = g.h;
            ink = binarize(g);
            lines = staffLines(ink, w, h);
            sp = staffSpace(lines);
        }
        List<PageData> out = new ArrayList<>();
        PageData pd = new PageData();
        pd.sourceStaffSpacePx = sourceSp;
        out.add(pd);
        if (sp <= 0) return out;
        double k = TARGET_SP / sp;
        pd.width = w * k;
        pd.height = h * k;
        int lt = 1;
        for (int[] l : lines) lt = Math.max(lt, l[1] - l[0] + 1);
        lt = Math.min(lt, (int) Math.max(1, sp * 0.35));

        for (int[] l : lines) {
            Shape s = rect(l[2], l[0], l[3] + 1, l[1] + 1, k);
            s.curved = false;
            pd.shapes.add(s);
        }

        // erase staff lines and ledger lines where nothing crosses them
        for (int[] l : lines) eraseLine(ink, w, h, l[0], l[1], l[2], l[3], l[4]);
        List<int[]> ledgers = new ArrayList<>();
        eraseLedgers(ink, w, h, lines, sp, lt, ledgers);

        // big blobs at the start of a staff (clefs, braces) are kept whole
        int[] label = new int[w * h];
        List<int[]> comps = components(ink, w, h, label);    // {x0,y0,x1,y1,count}
        boolean[] protect = new boolean[comps.size() + 1];
        for (int c = 0; c < comps.size(); c++) {
            int[] b = comps.get(c);
            int bh = b[3] - b[1] + 1;
            for (int[] l : lines) {
                // only the staff the blob sits on (an indented staff above must not reach over)
                double ly = (l[0] + l[1]) / 2.0, cy = (b[1] + b[3]) / 2.0;
                if (b[0] < l[2] + 2 * sp && b[2] > l[2] - 3 * sp && bh > 2.5 * sp
                        && Math.abs(cy - ly) < 2.5 * sp) {
                    protect[c + 1] = true;
                }
            }
        }

        // thin vertical lines: stems (a head at one end) and bar lines (full staff height)
        List<int[]> clefBoxes = new ArrayList<>();
        for (int c = 0; c < comps.size(); c++) if (protect[c + 1]) clefBoxes.add(comps.get(c));
        List<int[]> verticals = verticalLines(ink, w, h, label, protect, lines, clefBoxes, sp);
        for (int[] v : verticals) {
            Shape s = rect(v[0], v[1], v[2] + 1, v[3] + 1, k);
            s.curved = false;
            pd.shapes.add(s);
            // erase the line; rows joined from the side belong to that symbol too. A short gap between two such
            // rows is the wall of a hollow head that the stem runs along: keep it as well
            int n = v[3] - v[1] + 1;
            boolean[] side = new boolean[n];
            for (int y = v[1]; y <= v[3]; y++) {
                side[y - v[1]] = v[0] > 0 && ink[y * w + v[0] - 1] || v[2] + 1 < w && ink[y * w + v[2] + 1];
            }
            int maxGap = (int) Math.round(0.6 * sp);
            for (int i = 0; i < n; ) {
                if (side[i]) { i++; continue; }
                int j = i;
                while (j < n && !side[j]) j++;
                boolean wall = false;
                if (i > 0 && j < n && j - i <= maxGap) {
                    int dir = ringBeside(ink, w, v, v[1] + (i + j) / 2, sp);
                    // the head closes on that same side just above and below the gap
                    // and nothing joins from the other side (beams do, at the stems they connect)
                    int xs = dir < 0 ? v[0] - 1 : v[2] + 1, xo = dir < 0 ? v[2] + 1 : v[0] - 1;
                    int ya = v[1] + i - 1, yb = v[1] + j;
                    wall = dir != 0 && xs >= 0 && xs < w && ink[ya * w + xs] && ink[yb * w + xs]
                            && (xo < 0 || xo >= w || !ink[ya * w + xo] && !ink[yb * w + xo])
                            && sideRun(ink, w, xs, ya, dir) < 1.6 * sp && sideRun(ink, w, xs, yb, dir) < 1.6 * sp;
                }
                if (!wall) {
                    for (int y = v[1] + i; y < v[1] + j; y++) for (int x = v[0]; x <= v[2]; x++) ink[y * w + x] = false;
                }
                i = j;
            }
        }

        // remaining blobs
        Arrays.fill(label, 0);
        comps = components(ink, w, h, label);
        boolean[] timeSig = timeSignatures(comps, lines, sp);
        boolean[] used = new boolean[comps.size()];
        mergeArcs(comps, label, w, sp, k, lines, ledgers, used, pd);
        for (int c = 0; c < comps.size(); c++) {
            int[] b = comps.get(c);
            if (used[c]) continue;
            if (timeSig[c]) {                       // two digits: upper and lower half of a staff
                Shape s = rect(b[0], b[1], b[2] + 1, b[3] + 1, k);
                s.curved = true;
                s.ink = (double) b[4] / ((b[2] - b[0] + 1) * (b[3] - b[1] + 1));
                pd.shapes.add(s);
                continue;
            }
            int bw = b[2] - b[0] + 1, bh = b[3] - b[1] + 1;
            if (b[4] < 3) continue;                                   // specks
            if (onStaffLine(b, lines)) continue;                      // rest of a staff line
            if (!nearStaff(b, lines, sp)) continue;                   // lyrics, titles, page decoration
            if (outsideWithoutLedger(b, lines, ledgers, verticals, sp)) continue;  // letters of lyrics, dynamics
            // pieces of thick stems left at a head's side (kept as its rim) can make it wider than
            // a head: then leave them out
            int[] full = b;
            if (bw > 1.55 * sp && bw < 2.3 * sp) b = withoutStemEdges(b, label, c + 1, w, verticals, sp);
            // stems on both sides: a chord of two voices (a flat has a stroke on one side only)
            boolean trimmed = b[0] != full[0] && b[2] != full[2];
            bw = b[2] - b[0] + 1;
            double fill = (double) b[4] / (bw * bh);
            boolean hole = hasHole(label, c + 1, b, w);
            // chords of two or three heads in seconds/thirds touch each other: split them
            if (fill > 0.4 && bw >= (trimmed ? 0.9 : 1.15) * sp && bw <= 2.3 * sp && bh > 1.6 * sp && bh < 3.4 * sp) {
                // rows that are only a stem stub or a bit of staff line do not belong to a head
                int tt = b[1], tb = b[3];
                while (tt < tb && rowCount(label, c + 1, b, tt, w) < 0.5 * sp) tt++;
                while (tb > tt && rowCount(label, c + 1, b, tb, w) < 0.5 * sp) tb--;
                int th = tb - tt + 1;
                // stacked heads are a third (one space) apart: one head plus one space per extra head
                int n = 1 + (int) Math.round((th - 0.95 * sp) / sp);
                // a narrow sign right after the clef is a flat of the key signature, not a chord
                boolean keySign = bw < 1.3 * sp && inKeyZone(b, clefBoxes, sp);
                List<Shape> parts = new ArrayList<>();
                boolean allHeads = n >= 2 && n <= 3 && !keySign;
                for (int i = 0; i < n && allHeads; i++) {
                    int y0 = tt + i * th / n, y1 = tt + (i + 1) * th / n - 1;
                    int[] part = {b[0], y0, b[2], y1, 0};
                    for (int y = y0; y <= y1; y++) for (int x = b[0]; x <= b[2]; x++) if (label[y * w + x] == c + 1) part[4]++;
                    Shape s = rect(b[0], y0, b[2] + 1, y1 + 1, k);
                    s.curved = true;
                    s.ink = (double) part[4] / ((b[2] - b[0] + 1) * (y1 - y0 + 1));
                    s.subpaths = hasHole(label, c + 1, part, w) || s.ink < 0.66 ? 2 : 1;
                    // every part must look like a head: well filled, or hollow and fairly inked
                    if (!(s.ink > 0.66 || s.ink > 0.38 && (s.subpaths == 2))) allHeads = false;
                    parts.add(s);
                }
                if (allHeads) {
                    pd.shapes.addAll(parts);
                    continue;
                }
            }
            Shape s = rect(b[0], b[1], b[2] + 1, b[3] + 1, k);
            s.ink = fill;
            s.upperInk = upperShare(label, c + 1, b, w);
            s.bowlY = wideRowsMiddle(label, c + 1, b, w) * k;
            s.subpaths = hole ? 2 : 1;
            // a hollow head sitting between two staff lines loses its top/bottom edge when the
            // lines are erased; its ink share (about half, a filled head about 3/4) tells it
            boolean headSize = bh >= 0.7 * sp && bh <= 1.35 * sp && bw >= 1.05 * sp && bw <= 2.3 * sp;
            if (headSize && fill > 0.35 && fill < 0.66) s.subpaths = 2;
            // whole-note sized blob without stems at its ends: a whole note (a beam joins stems)
            boolean wholeSize = bh >= 0.75 * sp && bh <= 1.4 * sp && bw >= 1.6 * sp && bw <= 2.4 * sp;
            if (wholeSize && stemsTouching(b, verticals, sp) < 2) s.subpaths = 2;
            double thick = (double) b[4] / bw;                        // mean ink per column
            if (fill > 0.88 && bw > 1.6 * bh) s.curved = false;          // whole/half rest bar
            else if (s.subpaths == 1 && bw > 1.5 * sp && (bw > 1.6 * bh || bw > 2.2 * sp)
                    && thick >= 0.35 * sp && bh < 3 * sp) {                  // beam(s); a head is rounder
                s.curved = false;
                s.subs.clear();
                s.subs.addAll(strips(label, c + 1, b, w, k));
            } else s.curved = true;
            pd.shapes.add(s);
        }
        return out;
    }

    private static Shape rect(double x0, double y0, double x1, double y1, double k) {
        Shape s = new Shape();
        s.x0 = x0 * k;
        s.y0 = y0 * k;
        s.x1 = x1 * k;
        s.y1 = y1 * k;
        s.fill = true;
        s.subpaths = 1;
        s.subs.add(new double[]{s.x0, s.y0, s.x1, s.y1});
        return s;
    }

    // ------------------------------------------------------------------ binarize

    /** Local mean threshold (Bradley): robust against uneven light. */
    static boolean[] binarize(Gray g) {
        int w = g.w, h = g.h;
        long[] integral = new long[(w + 1) * (h + 1)];
        for (int y = 0; y < h; y++) {
            long row = 0;
            for (int x = 0; x < w; x++) {
                row += g.at(x, y);
                integral[(y + 1) * (w + 1) + x + 1] = integral[y * (w + 1) + x + 1] + row;
            }
        }
        int r = Math.max(8, Math.max(w, h) / 32);
        boolean[] ink = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            int ya = Math.max(0, y - r), yb = Math.min(h, y + r + 1);
            for (int x = 0; x < w; x++) {
                int xa = Math.max(0, x - r), xb = Math.min(w, x + r + 1);
                long sum = integral[yb * (w + 1) + xb] - integral[ya * (w + 1) + xb]
                        - integral[yb * (w + 1) + xa] + integral[ya * (w + 1) + xa];
                double mean = (double) sum / ((xb - xa) * (yb - ya));
                int v = g.at(x, y);
                ink[y * w + x] = v < mean * 0.82 && v < 200;
            }
        }
        return ink;
    }

    // ------------------------------------------------------------------ tilt

    /** Angle (degrees) at which the rows line up best with the staff lines. */
    static double skew(boolean[] ink, int w, int h) {
        double coarse = skew(ink, w, h, -3, 3, 0.1);
        return skew(ink, w, h, coarse - 0.1, coarse + 0.1, 0.02);
    }

    static double skew(boolean[] ink, int w, int h, double from, double to, double stepA) {
        double best = 0, bestScore = -1;
        int step = Math.max(1, w / 900);
        for (double a = from; a <= to + 1e-6; a += stepA) {
            double t = Math.tan(Math.toRadians(a));
            int[] hist = new int[h];
            for (int y = 0; y < h; y += 1) {
                for (int x = 0; x < w; x += step) {
                    if (!ink[y * w + x]) continue;
                    int yy = (int) Math.round(y - x * t);
                    if (yy >= 0 && yy < h) hist[yy]++;
                }
            }
            double score = 0;
            for (int v : hist) score += (double) v * v;
            if (score > bestScore) { bestScore = score; best = a; }
        }
        return best;
    }

    /** Enlarges (bilinear). */
    static Gray scale(Gray g, double f) {
        int w = (int) Math.round(g.w * f), h = (int) Math.round(g.h * f);
        byte[] out = new byte[w * h];
        for (int y = 0; y < h; y++) {
            double sy = Math.min(g.h - 1.001, Math.max(0, (y + 0.5) / f - 0.5));
            int y0 = (int) sy;
            double fy = sy - y0;
            for (int x = 0; x < w; x++) {
                double sx = Math.min(g.w - 1.001, Math.max(0, (x + 0.5) / f - 0.5));
                int x0 = (int) sx;
                double fx = sx - x0;
                double top = g.at(x0, y0) * (1 - fx) + g.at(x0 + 1, y0) * fx;
                double bot = g.at(x0, y0 + 1) * (1 - fx) + g.at(x0 + 1, y0 + 1) * fx;
                out[y * w + x] = (byte) (int) Math.round(top * (1 - fy) + bot * fy);
            }
        }
        return new Gray(w, h, out);
    }

    /** Rotates so that rows running at the given angle become horizontal (bilinear, white edges). */
    static Gray rotate(Gray g, double a) {
        int w = g.w, h = g.h;
        double c = Math.cos(a), s = Math.sin(a), cx = w / 2.0, cy = h / 2.0;
        byte[] out = new byte[w * h];
        for (int y = 0; y < h; y++) {
            double v = y - cy;
            for (int x = 0; x < w; x++) {
                double u = x - cx;
                double sx = cx + u * c - v * s, sy = cy + u * s + v * c;
                int x0 = (int) Math.floor(sx), y0 = (int) Math.floor(sy);
                int val = 255;
                if (x0 >= 0 && y0 >= 0 && x0 + 1 < w && y0 + 1 < h) {
                    double fx = sx - x0, fy = sy - y0;
                    double top = g.at(x0, y0) * (1 - fx) + g.at(x0 + 1, y0) * fx;
                    double bot = g.at(x0, y0 + 1) * (1 - fx) + g.at(x0 + 1, y0 + 1) * fx;
                    val = (int) Math.round(top * (1 - fy) + bot * fy);
                }
                out[y * w + x] = (byte) val;
            }
        }
        return new Gray(w, h, out);
    }


    // ------------------------------------------------------------------ staff lines

    /** Rows that are mostly long horizontal ink runs, grouped into lines. */
    static List<int[]> staffLines(boolean[] ink, int w, int h) {
        int minRun = Math.max(20, w / 25);
        int[] cover = new int[h];
        int[] rx0 = new int[h], rx1 = new int[h];
        for (int y = 0; y < h; y++) {
            int run = 0, total = 0, a = w, b = -1;
            for (int x = 0; x <= w; x++) {
                boolean on = x < w && ink[y * w + x];
                if (on) run++;
                else {
                    if (run >= minRun) {
                        total += run;
                        a = Math.min(a, x - run);
                        b = Math.max(b, x - 1);
                    }
                    run = 0;
                }
            }
            cover[y] = total;
            rx0[y] = a;
            rx1[y] = b;
        }
        int maxCover = 0;
        for (int c : cover) maxCover = Math.max(maxCover, c);
        List<int[]> lines = new ArrayList<>();
        int y = 0;
        while (y < h) {
            if (cover[y] >= Math.max(minRun * 3, maxCover * 0.35)) {
                int y0 = y, x0 = rx0[y], x1 = rx1[y];
                while (y + 1 < h && cover[y + 1] >= Math.max(minRun * 3, maxCover * 0.35)) {
                    y++;
                    x0 = Math.min(x0, rx0[y]);
                    x1 = Math.max(x1, rx1[y]);
                }
                lines.add(new int[]{y0, y, x0, x1, lineThickness(ink, w, h, y0, y, x0, x1)});
            }
            y++;
        }
        return lines;
    }

    /** Median height of the ink run through a line. After straightening a tilted page the
     *  line wanders between neighbouring rows, so fewer rows are fully covered than it is thick. */
    static int lineThickness(boolean[] ink, int w, int h, int y0, int y1, int x0, int x1) {
        List<Integer> t = new ArrayList<>();
        for (int x = x0; x <= x1; x += 3) {
            int start = -1;
            for (int y = Math.max(0, y0 - 1); y <= Math.min(h - 1, y1 + 1); y++) {
                if (ink[y * w + x]) { start = y; break; }
            }
            if (start < 0) continue;
            int a = start, b = start;
            while (a - 1 >= 0 && ink[(a - 1) * w + x]) a--;
            while (b + 1 < h && ink[(b + 1) * w + x]) b++;
            t.add(b - a + 1);
        }
        if (t.isEmpty()) return y1 - y0 + 1;
        java.util.Collections.sort(t);
        return t.get(t.size() / 2);
    }

    /** Most common distance between neighbouring lines of a staff. */
    static double staffSpace(List<int[]> lines) {
        List<Double> gaps = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            double a = (lines.get(i - 1)[0] + lines.get(i - 1)[1]) / 2.0;
            double b = (lines.get(i)[0] + lines.get(i)[1]) / 2.0;
            gaps.add(b - a);
        }
        if (gaps.isEmpty()) return -1;
        List<Double> sorted = new ArrayList<>(gaps);
        java.util.Collections.sort(sorted);
        // the smallest gaps are the spaces inside staves; take the median of the lower half
        double med = sorted.get(Math.max(0, sorted.size() / 4));
        double sum = 0;
        int n = 0;
        for (double gp : gaps) if (Math.abs(gp - med) < 0.25 * med) { sum += gp; n++; }
        return n > 0 ? sum / n : med;
    }

    /** Clears a horizontal line where no symbol continues above or below it. */
    static void eraseLine(boolean[] ink, int w, int h, int y0, int y1, int x0, int x1, int thick) {
        // per column: the ink run through the line, which may wander a pixel up or down when the
        // page was tilted. Only a run inside that band is erased; anything reaching beyond it is
        // a symbol crossing or touching the line
        for (int x = Math.max(0, x0); x <= Math.min(w - 1, x1); x++) {
            int start = -1;
            for (int y = Math.max(0, y0 - 1); y <= Math.min(h - 1, y1 + 1); y++) {
                if (ink[y * w + x]) { start = y; break; }
            }
            if (start < 0) continue;
            int a = start, b = start;
            while (a - 1 >= 0 && ink[(a - 1) * w + x]) a--;
            while (b + 1 < h && ink[(b + 1) * w + x]) b++;
            if (a >= y0 - 1 && b <= y1 + 1 && b - a + 1 <= thick) for (int y = a; y <= b; y++) ink[y * w + x] = false;
        }
    }

    /** Short lines at whole spaces above/below a staff are ledger lines. */
    static void eraseLedgers(boolean[] ink, int w, int h, List<int[]> lines, double sp, int lt, List<int[]> ledgers) {
        for (int i = 0; i + 4 < lines.size(); i++) {
            int[] top = lines.get(i), bot = lines.get(i + 4);
            double span = (bot[0] + bot[1]) / 2.0 - (top[0] + top[1]) / 2.0;
            if (Math.abs(span - 4 * sp) > 0.6 * sp) continue;
            for (int kk = 1; kk <= 5; kk++) {
                double[] ys = {(top[0] + top[1]) / 2.0 - kk * sp, (bot[0] + bot[1]) / 2.0 + kk * sp};
                for (double yc : ys) {
                    for (int y = (int) (yc - lt); y <= (int) (yc + lt); y++) {
                        if (y < 1 || y >= h - 1) continue;
                        int run = 0;
                        for (int x = top[2]; x <= Math.min(w, top[3] + 1); x++) {
                            boolean on = x < w && ink[y * w + x];
                            if (on) run++;
                            else {
                                if (run >= 1.2 * sp && run <= 3.2 * sp) {
                                    int a = x - run;
                                    int y0 = y, y1 = y;
                                    while (y0 - 1 >= 0 && y - y0 < lt && ink[(y0 - 1) * w + a + run / 2]) y0--;
                                    while (y1 + 1 < h && y1 - y < lt && ink[(y1 + 1) * w + a + run / 2]) y1++;
                                    // a ledger is drawn for a head on it or beyond it; ink only on the
                                    // staff side (a letter of the lyrics sitting on its base stroke) is no ledger
                                    int dir = yc > (bot[0] + bot[1]) / 2.0 ? 1 : -1;     // away from the staff
                                    boolean inner = inkBand(ink, w, h, a + run / 4, x - 1 - run / 4, y - dir * (lt + 2), y - dir * (int) (0.5 * sp));
                                    boolean outer = inkBand(ink, w, h, a + run / 4, x - 1 - run / 4, y + dir * (lt + 2), y + dir * (int) (0.5 * sp));
                                    if (inner && !outer) { run = 0; continue; }
                                    // and it sticks out on both sides of that head; the stroke of a
                                    // letter ends where the letter's body ends
                                    int m = (int) Math.max(1, 0.12 * sp);
                                    int r0 = y - (int) (0.6 * sp), r1 = y + (int) (0.6 * sp);
                                    boolean endsFree = true;
                                    for (int yy = r0; yy <= r1 && endsFree; yy++) {
                                        if (yy >= y - lt - 1 && yy <= y + lt + 1) continue;      // the line itself
                                        if (inkBand(ink, w, h, a, a + m - 1, yy, yy) || inkBand(ink, w, h, x - m, x - 1, yy, yy)) endsFree = false;
                                    }
                                    if (!endsFree) { run = 0; continue; }
                                    eraseLine(ink, w, h, y0, y1, a, x - 1, y1 - y0 + 1);
                                    ledgers.add(new int[]{a, y0, x - 1, y1});
                                }
                                run = 0;
                            }
                        }
                    }
                }
            }
        }
    }

    /** Does the row run white and then hit ink again within a head's width, on either side of
     *  the line? Returns that side (-1 left, 1 right) or 0. Then the line is the wall of a hollow head (two beams are further apart). */
    static int ringBeside(boolean[] ink, int w, int[] v, int y, double sp) {
        int lim = (int) Math.round(1.25 * sp);
        for (int dir = -1; dir <= 1; dir += 2) {
            int x = dir < 0 ? v[0] - 1 : v[2] + 1;
            boolean white = false;
            for (int d = 0; d < lim && x >= 0 && x < w; d++, x += dir) {
                if (!ink[y * w + x]) white = true;
                else if (white) return dir;
            }
        }
        return 0;
    }

    /** Another long vertical stroke closer than a head's width on that side: the ink between is
     *  the bowl of a flat (key signatures put flats side by side), not a note head. */
    static boolean strokeBeside(boolean[] ink, int w, int h, int[] g, int end, int side, double sp) {
        int ya = Math.max(0, (int) (end - 2.5 * sp)), yb = Math.min(h - 1, (int) (end + 2.5 * sp));
        int from = side < 0 ? g[0] - 2 : g[2] + 2;
        for (int d = 0; d < 0.9 * sp; d++) {
            int x = from + side * d;
            if (x < 0 || x >= w) break;
            int run = 0, best = 0;
            for (int y = ya; y <= yb; y++) {
                run = ink[y * w + x] ? run + 1 : 0;
                best = Math.max(best, run);
            }
            if (best >= 2 * sp) return true;
        }
        return false;
    }

    /** Box of a component without the columns of a stem running along its left or right edge. */
    static int[] withoutStemEdges(int[] b, int[] label, int id, int w, List<int[]> verticals, double sp) {
        int x0 = b[0], x1 = b[2];
        for (int[] v : verticals) {
            if (v[3] < b[1] || v[1] > b[3]) continue;
            if (v[2] >= b[0] - 1 && v[2] < b[0] + 0.4 * sp) x0 = Math.max(x0, v[2] + 1);
            if (v[0] <= b[2] + 1 && v[0] > b[2] - 0.4 * sp) x1 = Math.min(x1, v[0] - 1);
        }
        if (x0 == b[0] && x1 == b[2] || x1 - x0 + 1 < 0.9 * sp) return b;
        int n = 0;
        for (int y = b[1]; y <= b[3]; y++) for (int x = x0; x <= x1; x++) if (label[y * w + x] == id) n++;
        return new int[]{x0, b[1], x1, b[3], n};
    }

    /** Pixels of component id in row y. */
    static int rowCount(int[] label, int id, int[] b, int y, int w) {
        int n = 0;
        for (int x = b[0]; x <= b[2]; x++) if (label[y * w + x] == id) n++;
        return n;
    }

    /** Mean y of the rows that are at least 60 % as wide as the widest row. */
    static double wideRowsMiddle(int[] label, int id, int[] b, int w) {
        int[] cnt = new int[b[3] - b[1] + 1];
        int max = 0;
        for (int y = b[1]; y <= b[3]; y++) {
            cnt[y - b[1]] = rowCount(label, id, b, y, w);
            max = Math.max(max, cnt[y - b[1]]);
        }
        double sum = 0;
        int n = 0;
        for (int i = 0; i < cnt.length; i++) if (cnt[i] >= 0.6 * max) { sum += b[1] + i + 0.5; n++; }
        return n == 0 ? (b[1] + b[3]) / 2.0 : sum / n;
    }

    /** Share of a component's pixels in the upper half of its box. */
    static double upperShare(int[] label, int id, int[] b, int w) {
        int mid = (b[1] + b[3]) / 2, up = 0, all = 0;
        for (int y = b[1]; y <= b[3]; y++) {
            for (int x = b[0]; x <= b[2]; x++) {
                if (label[y * w + x] != id) continue;
                all++;
                if (y < mid) up++;
            }
        }
        return all == 0 ? 0.5 : (double) up / all;
    }

    /** Lies entirely within a staff line (plus a pixel): a piece the line erasure left over. */
    static boolean onStaffLine(int[] b, List<int[]> lines) {
        for (int[] l : lines) {
            if (b[1] >= l[0] - 2 && b[3] <= l[1] + 2 && b[0] >= l[2] - 2 && b[2] <= l[3] + 2) return true;
        }
        return false;
    }

    /** Length of the ink run in row y starting at x and going in direction dir. */
    static int sideRun(boolean[] ink, int w, int x, int y, int dir) {
        int n = 0;
        while (x >= 0 && x < w && ink[y * w + x]) { n++; x += dir; }
        return n;
    }

    /** Any ink in columns xa..xb between rows ya and yb (either order)? */
    static boolean inkBand(boolean[] ink, int w, int h, int xa, int xb, int ya, int yb) {
        int y0 = Math.max(0, Math.min(ya, yb)), y1 = Math.min(h - 1, Math.max(ya, yb));
        for (int y = y0; y <= y1; y++) {
            for (int x = Math.max(0, xa); x <= Math.min(w - 1, xb); x++) if (ink[y * w + x]) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ vertical lines

    static List<int[]> verticalLines(boolean[] ink, int w, int h, int[] label, boolean[] protect,
                                     List<int[]> lines, List<int[]> clefBoxes, double sp) {
        int minLen = (int) Math.round(2.5 * sp);     // a flat's stem is shorter
        // vertical runs per column
        List<int[]> runs = new ArrayList<>();       // {x, y0, y1}
        for (int x = 0; x < w; x++) {
            int run = 0;
            for (int y = 0; y <= h; y++) {
                boolean on = y < h && ink[y * w + x] && !protect[label[y * w + x]];
                if (on) run++;
                else {
                    if (run >= minLen) runs.add(new int[]{x, y - run, y - 1});
                    run = 0;
                }
            }
        }
        // join runs of neighbouring columns into lines
        List<int[]> groups = new ArrayList<>();      // {x0, y0, x1, y1}
        List<int[]> open = new ArrayList<>();
        int curX = -1;
        for (int[] r : runs) {
            if (r[0] != curX) {
                List<int[]> still = new ArrayList<>();
                for (int[] g : open) if (g[2] >= r[0] - 1) still.add(g);
                open = still;
                curX = r[0];
            }
            int[] hit = null;
            for (int[] g : open) {
                if (g[2] == r[0] - 1 && r[1] <= g[3] && r[2] >= g[1]) { hit = g; break; }
            }
            if (hit != null) {
                hit[2] = r[0];
                hit[1] = Math.min(hit[1], r[1]);
                hit[3] = Math.max(hit[3], r[2]);
            } else {
                int[] g = {r[0], r[1], r[0], r[2]};
                groups.add(g);
                open.add(g);
            }
        }
        List<int[]> out = new ArrayList<>();
        for (int[] g : groups) {
            int gw = g[2] - g[0] + 1;
            if (gw > 0.8 * sp) continue;                                   // a filled blob, not a line
            boolean fullStaff = false;
            for (int i = 0; i + 4 < lines.size(); i++) {
                int[] top = lines.get(i), bot = lines.get(i + 4);
                if (g[1] <= top[1] + 0.3 * sp && g[3] >= bot[0] - 0.3 * sp) fullStaff = true;
            }
            if (fullStaff || (gw <= 0.35 * sp && headAtEnd(ink, w, h, g, sp, inKeyZone(g, clefBoxes, sp)))) out.add(g);
        }
        return out;
    }

    /** A note head next to one end of a vertical line (so it is a stem, not part of a sharp). */
    /** Just right of a clef, where the key signature stands. */
    static boolean inKeyZone(int[] g, List<int[]> clefBoxes, double sp) {
        double cy = (g[1] + g[3]) / 2.0;
        for (int[] b : clefBoxes) {
            if (Math.abs((b[1] + b[3]) / 2.0 - cy) < 4 * sp && g[0] > b[2] && g[0] < b[2] + 4.5 * sp) return true;
        }
        return false;
    }

    static boolean headAtEnd(boolean[] ink, int w, int h, int[] g, double sp, boolean keyZone) {
        // stem up: head left of the bottom end; stem down: head right of the top end
        // (a flat's stroke has its note on the right of the bottom end – not a stem)
        for (int end : new int[]{g[1], g[3]}) {
            int ya = (int) (end - 0.7 * sp), yb = (int) (end + 0.7 * sp);
            int side0 = end == g[3] ? -1 : 1;
            for (int side = side0; side == side0; side += 2) {
                int xa = side < 0 ? (int) (g[0] - 1.5 * sp) : g[2] + 1;
                int xb = side < 0 ? g[0] - 1 : (int) (g[2] + 1.5 * sp);
                int cnt = 0, joined = 0;
                for (int y = Math.max(0, ya); y <= Math.min(h - 1, yb); y++) {
                    for (int x = Math.max(0, xa); x <= Math.min(w - 1, xb); x++)
                        if (ink[y * w + x]) cnt++;
                    // the head touches its stem (a hollow one at least with its rim). A
                    // neighbouring sign, like the next flat of a key signature, stands apart
                    int xn = side < 0 ? g[0] - 1 : g[2] + 1;
                    if (xn >= 0 && xn < w && ink[y * w + xn]) joined++;
                }
                if (cnt >= 0.45 * sp * sp && joined >= 2 && !(keyZone && strokeBeside(ink, w, h, g, end, side, sp))) return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ blobs

    /** 8-connected components; label[] gets 1..n; returns {x0,y0,x1,y1,count} per component. */
    static List<int[]> components(boolean[] ink, int w, int h, int[] label) {
        List<int[]> out = new ArrayList<>();
        int[] stack = new int[Math.max(16, w * h / 4)];
        for (int i = 0; i < w * h; i++) {
            if (!ink[i] || label[i] != 0) continue;
            int id = out.size() + 1;
            int[] b = {i % w, i / w, i % w, i / w, 0};
            int sp = 0;
            stack[sp++] = i;
            label[i] = id;
            while (sp > 0) {
                int p = stack[--sp];
                int x = p % w, y = p / w;
                b[4]++;
                if (x < b[0]) b[0] = x;
                if (x > b[2]) b[2] = x;
                if (y < b[1]) b[1] = y;
                if (y > b[3]) b[3] = y;
                for (int dy = -1; dy <= 1; dy++) {
                    int yy = y + dy;
                    if (yy < 0 || yy >= h) continue;
                    for (int dx = -1; dx <= 1; dx++) {
                        int xx = x + dx;
                        if (xx < 0 || xx >= w) continue;
                        int q = yy * w + xx;
                        if (ink[q] && label[q] == 0) {
                            label[q] = id;
                            if (sp == stack.length) stack = Arrays.copyOf(stack, stack.length * 2);
                            stack[sp++] = q;
                        }
                    }
                }
            }
            out.add(b);
        }
        return out;
    }

    /**
     * Splits a beam blob into its strips (primary beam, secondary beams, stubs): ink runs of
     * neighbouring columns that overlap vertically form one strip.
     */
    static List<double[]> strips(int[] label, int id, int[] b, int w, double k) {
        List<int[]> open = new ArrayList<>(), all = new ArrayList<>();   // {x0, y0, x1, y1, lastY0, lastY1}
        for (int x = b[0]; x <= b[2]; x++) {
            List<int[]> runs = new ArrayList<>();
            int run = 0;
            for (int y = b[1]; y <= b[3] + 1; y++) {
                boolean on = y <= b[3] && label[y * w + x] == id;
                if (on) run++;
                else {
                    if (run > 0) runs.add(new int[]{y - run, y - 1});
                    run = 0;
                }
            }
            List<int[]> next = new ArrayList<>();
            for (int[] r : runs) {
                int[] hit = null;
                for (int[] st : open) if (r[0] <= st[5] + 1 && r[1] >= st[4] - 1) { hit = st; break; }
                if (hit == null) {
                    hit = new int[]{x, r[0], x, r[1], r[0], r[1]};
                    all.add(hit);
                }
                hit[2] = x;
                hit[1] = Math.min(hit[1], r[0]);
                hit[3] = Math.max(hit[3], r[1]);
                hit[4] = r[0];
                hit[5] = r[1];
                if (!next.contains(hit)) next.add(hit);
            }
            open = next;
        }
        List<double[]> out = new ArrayList<>();
        for (int[] st : all) {
            if (st[2] - st[0] < 2) continue;
            out.add(new double[]{st[0] * k, st[1] * k, (st[2] + 1) * k, (st[3] + 1) * k});
        }
        return out;
    }

    /** True if the blob encloses white area (hollow head, sharp, natural, flat bulb). */
    static boolean hasHole(int[] label, int id, int[] b, int w) {
        int bw = b[2] - b[0] + 1, bh = b[3] - b[1] + 1;
        if (bw < 4 || bh < 4 || bw * bh > 40000) return false;
        boolean[] seen = new boolean[bw * bh];
        int[] stack = new int[bw * bh];
        int sp = 0;
        for (int x = 0; x < bw; x++) {
            for (int y : new int[]{0, bh - 1}) {
                int i = y * bw + x;
                if (!seen[i] && label[(b[1] + y) * w + b[0] + x] != id) { seen[i] = true; stack[sp++] = i; }
            }
        }
        for (int y = 0; y < bh; y++) {
            for (int x : new int[]{0, bw - 1}) {
                int i = y * bw + x;
                if (!seen[i] && label[(b[1] + y) * w + b[0] + x] != id) { seen[i] = true; stack[sp++] = i; }
            }
        }
        while (sp > 0) {
            int i = stack[--sp];
            int x = i % bw, y = i / bw;
            int[][] nb = {{x + 1, y}, {x - 1, y}, {x, y + 1}, {x, y - 1}};
            for (int[] n : nb) {
                if (n[0] < 0 || n[1] < 0 || n[0] >= bw || n[1] >= bh) continue;
                int j = n[1] * bw + n[0];
                if (!seen[j] && label[(b[1] + n[1]) * w + b[0] + n[0]] != id) { seen[j] = true; stack[sp++] = j; }
            }
        }
        int enclosed = 0;
        for (int y = 0; y < bh; y++)
            for (int x = 0; x < bw; x++)
                if (!seen[y * bw + x] && label[(b[1] + y) * w + b[0] + x] != id) enclosed++;
        return enclosed >= Math.max(2, bw * bh / 60);
    }

    /**
     * A hollow head lying between two staff lines loses its top and bottom edge when the lines
     * are erased and falls apart into a left and a right arc. Two such arcs side by side at the
     * same height become one hollow head again.
     */
    static void mergeArcs(List<int[]> comps, int[] label, int w, double sp, double k,
                          List<int[]> lines, List<int[]> ledgers, boolean[] used, PageData pd) {
        for (int a = 0; a < comps.size(); a++) {
            int[] p = comps.get(a);
            if (used[a] || !isArc(p, sp)) continue;
            for (int c = 0; c < comps.size(); c++) {
                if (c == a || used[c]) continue;
                int[] q = comps.get(c);
                if (!isArc(q, sp) || q[0] <= p[2]) continue;
                double gap = q[0] - p[2];
                double cyP = (p[1] + p[3]) / 2.0, cyQ = (q[1] + q[3]) / 2.0;
                double width = q[2] - p[0] + 1;
                if (gap < 0.15 * sp || gap > 1.4 * sp || Math.abs(cyP - cyQ) > 0.3 * sp) continue;
                if (width < 1.05 * sp || width > 2.4 * sp) continue;
                int[] m = {p[0], Math.min(p[1], q[1]), q[2], Math.max(p[3], q[3]), p[4] + q[4]};
                if (!nearStaff(m, lines, sp)) continue;
                Shape s = rect(m[0], m[1], m[2] + 1, m[3] + 1, k);
                s.curved = true;
                s.subpaths = 2;
                s.ink = (double) m[4] / ((m[2] - m[0] + 1) * (m[3] - m[1] + 1));
                pd.shapes.add(s);
                used[a] = true;
                used[c] = true;
                break;
            }
        }
    }

    private static boolean isArc(int[] b, double sp) {
        int bw = b[2] - b[0] + 1, bh = b[3] - b[1] + 1;
        return bh >= 0.5 * sp && bh <= 1.3 * sp && bw <= 0.8 * sp && bw >= 0.12 * sp && b[4] >= 4;
    }

    /**
     * Time signature digits: two blobs at the same x, one filling the upper half of a staff
     * (top line to middle line), the other the lower half. Not to be split into note heads.
     */
    static boolean[] timeSignatures(List<int[]> comps, List<int[]> lines, double sp) {
        boolean[] ts = new boolean[comps.size()];
        for (int i = 0; i + 4 < lines.size(); i++) {
            double l0 = (lines.get(i)[0] + lines.get(i)[1]) / 2.0;
            double l2 = (lines.get(i + 2)[0] + lines.get(i + 2)[1]) / 2.0;
            double l4 = (lines.get(i + 4)[0] + lines.get(i + 4)[1]) / 2.0;
            if (Math.abs(l4 - l0 - 4 * sp) > 0.6 * sp) continue;
            for (int a = 0; a < comps.size(); a++) {
                int[] p = comps.get(a);
                if (Math.abs(p[1] - l0) > 0.35 * sp || Math.abs(p[3] - l2) > 0.35 * sp) continue;
                for (int c = 0; c < comps.size(); c++) {
                    int[] q = comps.get(c);
                    if (c == a || Math.abs(q[1] - l2) > 0.35 * sp || Math.abs(q[3] - l4) > 0.35 * sp) continue;
                    double ca = (p[0] + p[2]) / 2.0, cb = (q[0] + q[2]) / 2.0;
                    if (Math.abs(ca - cb) < 0.6 * sp) { ts[a] = true; ts[c] = true; }
                }
            }
        }
        return ts;
    }

    static int stemsTouching(int[] b, List<int[]> verticals, double sp) {
        int n = 0;
        for (int[] v : verticals) {
            double vx = (v[0] + v[2]) / 2.0;
            if (vx < b[0] - 0.3 * sp || vx > b[2] + 0.3 * sp) continue;
            if (v[1] <= b[3] + 0.5 * sp && v[3] >= b[1] - 0.5 * sp) n++;
        }
        return n;
    }


    /**
     * Small blobs clearly above or below a staff: a note there always has a ledger line, so
     * without one it is text (lyrics, dynamics, fingering) and is left out.
     */
    static boolean outsideWithoutLedger(int[] b, List<int[]> lines, List<int[]> ledgers, List<int[]> verticals, double sp) {
        int bh = b[3] - b[1] + 1;
        if (bh > 3 * sp) return false;
        // taller or wider than a head and joined to a stem: a flag or a beam
        int bw = b[2] - b[0] + 1;
        if ((bh > 1.6 * sp || bw > 2.2 * sp) && stemsTouching(b, verticals, sp) > 0) return false;
        // nearest staff
        double cy = (b[1] + b[3]) / 2.0, bestD = Double.MAX_VALUE, top = 0, bot = 0;
        for (int i = 0; i + 4 < lines.size(); i++) {
            double t = (lines.get(i)[0] + lines.get(i)[1]) / 2.0;
            double u = (lines.get(i + 4)[0] + lines.get(i + 4)[1]) / 2.0;
            if (Math.abs(u - t - 4 * sp) > 0.6 * sp) continue;
            double d = cy < t ? t - cy : cy > u ? cy - u : 0;
            if (d < bestD) { bestD = d; top = t; bot = u; }
        }
        if (bestD == Double.MAX_VALUE) return false;
        for (int[] l : lines) if (b[0] < l[2] + 5 * sp && b[2] < l[2] + 4 * sp && b[2] > l[2] - sp) return false;   // clef zone ("8")
        // a head without ledger line touches the outer staff line: its centre is half a space
        // beyond it. Letters of lyrics sit further away (often only a little)
        boolean below = b[1] > bot + 0.35 * sp || cy > bot + 0.8 * sp;
        boolean above = b[3] < top - 0.35 * sp || cy < top - 0.8 * sp;
        if (!below && !above) return false;
        for (int[] l : ledgers) {
            boolean xo = l[0] <= b[2] && l[2] >= b[0];
            double ly = (l[1] + l[3]) / 2.0;
            boolean yo = below ? ly > bot && ly < b[3] + 0.6 * sp : ly < top && ly > b[1] - 0.6 * sp;
            if (xo && yo) return false;
        }
        return true;
    }

    /** Ink close to a staff (notes may reach about 4 spaces beyond it). */
    static boolean nearStaff(int[] b, List<int[]> lines, double sp) {
        double cy = (b[1] + b[3]) / 2.0;
        for (int[] l : lines) {
            if (b[2] < l[2] - 0.3 * sp && Math.abs(cy - (l[0] + l[1]) / 2.0) < 6 * sp) continue;   // voice names left of the staff
            double ly = (l[0] + l[1]) / 2.0;
            if (Math.abs(cy - ly) < 3.2 * sp && b[2] >= l[2] - 4 * sp && b[0] <= l[3] + 2 * sp) return true;
            // tall symbols (clefs, stems with flags) may stick out further
            if (b[1] < ly + 0.5 * sp && b[3] > ly - 0.5 * sp) return true;
        }
        return false;
    }
}
