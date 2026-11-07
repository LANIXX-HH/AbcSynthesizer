package de.lanixx.chorstimme.core;

import com.tom_roush.pdfbox.contentstream.PDContentStream;
import com.tom_roush.pdfbox.contentstream.operator.Operator;
import com.tom_roush.pdfbox.cos.COSBase;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.cos.COSNumber;
import com.tom_roush.pdfbox.pdfparser.PDFStreamParser;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDResources;
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle;
import com.tom_roush.pdfbox.pdmodel.graphics.PDXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDInlineImage;
import com.tom_roush.pdfbox.pdmodel.font.PDFont;
import com.tom_roush.pdfbox.pdmodel.font.PDType3CharProc;
import com.tom_roush.pdfbox.pdmodel.font.PDType3Font;
import com.tom_roush.pdfbox.cos.COSArray;
import com.tom_roush.pdfbox.cos.COSDictionary;
import com.tom_roush.pdfbox.cos.COSString;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

/**
 * Reads the vector drawing of every page: each painted path becomes a {@link Shape}
 * (bounding box in points, origin top-left), each word of text a {@link TextItem}.
 * Only uses PDFBox's COS/parser classes, so the same code runs with PDFBox (desktop)
 * and PdfBox-Android.
 */
public final class PdfReader {

    public static class Shape {
        public double x0, y0, x1, y1;   // top-left origin, points
        public boolean fill, stroke, curved;
        public int subpaths;
        public double lineWidth;
        /** music-font character this shape stands for (0 = drawn path) */
        public char glyph;
        /** for bitmaps: share of inked pixels (-1 = vector path, unknown) */
        public double ink = -1;
        /** Bitmaps: share of the ink in the upper half (a flat has little, a sharp about half). */
        public double upperInk = -1;
        /** Bitmaps: middle of the rows where the sign is at its widest (a flat's bowl), -1 = unknown. */
        public double bowlY = -1;
        /** bounding box per subpath: {x0, y0, x1, y1}, top-left origin */
        public final List<double[]> subs = new ArrayList<>();

        public double w() { return x1 - x0; }
        public double h() { return y1 - y0; }
        public double cx() { return (x0 + x1) / 2; }
        public double cy() { return (y0 + y1) / 2; }

        @Override
        public String toString() {
            return String.format("[%.1f,%.1f %.1fx%.1f %s%s sp=%d%s]", x0, y0, w(), h(),
                    fill ? "F" : "", stroke ? "S" : "", subpaths, curved ? " c" : "");
        }
    }

    public static class TextItem {
        public String text;
        public double x0, x1, baseline, fontSize;

        @Override
        public String toString() {
            return String.format("'%s'@%.1f..%.1f,%.1f(%.1f)", text, x0, x1, baseline, fontSize);
        }
    }

    /** One character of a music font (Finale Maestro, Sonata, Petrucci, ...): note heads, clefs... */
    public static class Glyph {
        public char c;
        public double x0, x1, baseline, size;

        @Override
        public String toString() {
            return String.format("<%s %.1f..%.1f,%.1f>", c, x0, x1, baseline);
        }
    }

    public static class PageData {
        public double width, height;
        public final List<Shape> shapes = new ArrayList<>();
        public final List<TextItem> texts = new ArrayList<>();
        public final List<Glyph> glyphs = new ArrayList<>();
    }

    private static final java.util.regex.Pattern MUSIC_FONT = java.util.regex.Pattern.compile(
            "(?i).*(maestro|sonata|petrucci|opus|jazz|engraver|broadway|tamburo|november|bravura|leland|emmentaler|feta).*");

    private PdfReader() {}

    /**
     * Type3 fonts without a Unicode table are drawings (music symbols as small pictures, e.g.
     * from old notation programs). Type3 fonts with a Unicode table are ordinary text (Chrome
     * writes lyrics like that) and stay text.
     */
    static boolean isSymbolType3(PDFont f) {
        return f instanceof PDType3Font
                && !f.getCOSObject().containsKey(COSName.TO_UNICODE);
    }

    /**
     * Old PDFs often have Cyrillic fonts without a Unicode table; the text then comes out as
     * Latin-1 letters (Ìèðî instead of Миро). Mostly À..ÿ means Windows-1251 bytes.
     */
    static String fixCyrillic(String s) {
        int hi = 0, letters = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) letters++;
            if (c >= 0xC0 && c <= 0xFF) hi++;
            if (c > 0xFF) return s;               // already real Unicode
        }
        if (letters == 0 || hi * 2 < letters) return s;
        byte[] b = new byte[s.length()];
        for (int i = 0; i < s.length(); i++) b[i] = (byte) s.charAt(i);
        try {
            return new String(b, "windows-1251");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;
        }
    }

    public static List<PageData> read(InputStream in) throws IOException {
        List<PageData> pages = new ArrayList<>();
        try (PDDocument doc = PDDocument.load(in)) {
            int n = doc.getNumberOfPages();
            for (int i = 0; i < n; i++) {
                PDPage page = doc.getPage(i);
                PDRectangle box = page.getCropBox();
                PageData pd = new PageData();
                pd.width = box.getWidth();
                pd.height = box.getHeight();
                new PathCollector(pd, box).run(page, page.getResources(), Matrix.identity(), 0);
                pages.add(pd);
            }
            Stripper st = new Stripper(pages);
            st.setSortByPosition(false);   // we sort ourselves (PDFBox's sorter can throw too)
            st.getText(doc);
            for (PageData pd : pages) st.finish(pd);
        }
        return pages;
    }

    // ------------------------------------------------------------------ vectors

    /** 2D affine matrix [a b c d e f] as in PDF. */
    static final class Matrix {
        final double a, b, c, d, e, f;

        Matrix(double a, double b, double c, double d, double e, double f) {
            this.a = a; this.b = b; this.c = c; this.d = d; this.e = e; this.f = f;
        }

        static Matrix identity() { return new Matrix(1, 0, 0, 1, 0, 0); }

        /** this applied first, then m (PDF: cm concatenates as m × ctm). */
        Matrix then(Matrix m) {
            return new Matrix(
                    a * m.a + b * m.c, a * m.b + b * m.d,
                    c * m.a + d * m.c, c * m.b + d * m.d,
                    e * m.a + f * m.c + m.e, e * m.b + f * m.d + m.f);
        }

        double tx(double x, double y) { return a * x + c * y + e; }
        double ty(double x, double y) { return b * x + d * y + f; }
        double scale() { return Math.sqrt(Math.abs(a * d - b * c)); }
    }

    private static final class PathCollector {
        private final PageData pd;
        private final PDRectangle box;

        PathCollector(PageData pd, PDRectangle box) {
            this.pd = pd;
            this.box = box;
        }

        void run(PDContentStream cs, PDResources res, Matrix base, int depth) throws IOException {
            if (depth > 8) return;
            PDFStreamParser parser = new PDFStreamParser(cs);
            parser.parse();
            List<Object> tokens = parser.getTokens();

            Deque<Matrix> stack = new ArrayDeque<>();
            Deque<Double> lwStack = new ArrayDeque<>();
            Matrix ctm = base;
            double lineWidth = 1;
            List<Double> ops = new ArrayList<>();
            List<COSBase> rawOps = new ArrayList<>();

            // text state (only Type3 fonts are drawn here: their glyphs are small drawings/bitmaps)
            Matrix tm = Matrix.identity(), tlm = Matrix.identity();
            PDFont font = null;
            double fontSize = 0, charSpacing = 0, wordSpacing = 0, hScale = 1, leading = 0, rise = 0;

            // current path
            double[] bb = null;
            List<double[]> subBoxes = new ArrayList<>();
            int sub = 0;
            boolean curved = false;
            double curX = 0, curY = 0;

            for (Object t : tokens) {
                if (!(t instanceof Operator)) {
                    if (t instanceof COSNumber) ops.add((double) ((COSNumber) t).floatValue());
                    if (t instanceof COSBase) rawOps.add((COSBase) t);
                    continue;
                }
                String op = ((Operator) t).getName();
                double[] o = new double[ops.size()];
                for (int i = 0; i < o.length; i++) o[i] = ops.get(i);
                switch (op) {
                    case "q":
                        stack.push(ctm);
                        lwStack.push(lineWidth);
                        break;
                    case "Q":
                        if (!stack.isEmpty()) ctm = stack.pop();
                        if (!lwStack.isEmpty()) lineWidth = lwStack.pop();
                        break;
                    case "cm":
                        if (o.length == 6) ctm = new Matrix(o[0], o[1], o[2], o[3], o[4], o[5]).then(ctm);
                        break;
                    case "w":
                        if (o.length == 1) lineWidth = o[0];
                        break;
                    case "m":
                        if (o.length == 2) {
                            bb = add(bb, ctm, o[0], o[1]);
                            subBoxes.add(add(null, ctm, o[0], o[1]));
                            sub++;
                            curX = o[0]; curY = o[1];
                        }
                        break;
                    case "l":
                        if (o.length == 2) {
                            bb = add(bb, ctm, o[0], o[1]);
                            addSub(subBoxes, ctm, o[0], o[1]);
                            curX = o[0]; curY = o[1];
                        }
                        break;
                    case "c":
                        if (o.length == 6) {
                            for (int i = 0; i < 6; i += 2) {
                                bb = add(bb, ctm, o[i], o[i + 1]);
                                addSub(subBoxes, ctm, o[i], o[i + 1]);
                            }
                            curX = o[4]; curY = o[5];
                            curved = true;
                        }
                        break;
                    case "v":
                    case "y":
                        if (o.length == 4) {
                            bb = add(bb, ctm, o[0], o[1]);
                            bb = add(bb, ctm, o[2], o[3]);
                            addSub(subBoxes, ctm, o[0], o[1]);
                            addSub(subBoxes, ctm, o[2], o[3]);
                            curX = o[2]; curY = o[3];
                            curved = true;
                        }
                        break;
                    case "re":
                        if (o.length == 4) {
                            bb = add(bb, ctm, o[0], o[1]);
                            bb = add(bb, ctm, o[0] + o[2], o[1] + o[3]);
                            double[] rb = add(null, ctm, o[0], o[1]);
                            rb = add(rb, ctm, o[0] + o[2], o[1] + o[3]);
                            rb = add(rb, ctm, o[0] + o[2], o[1]);
                            rb = add(rb, ctm, o[0], o[1] + o[3]);
                            subBoxes.add(rb);
                            sub++;
                        }
                        break;
                    case "h":
                        break;
                    case "f": case "F": case "f*":
                    case "B": case "B*": case "b": case "b*":
                    case "S": case "s":
                        if (bb != null) {
                            Shape s = new Shape();
                            s.x0 = bb[0] - box.getLowerLeftX();
                            s.x1 = bb[2] - box.getLowerLeftX();
                            s.y0 = box.getUpperRightY() - bb[3];
                            s.y1 = box.getUpperRightY() - bb[1];
                            s.fill = !op.startsWith("S") && !op.equals("s");
                            s.stroke = !op.startsWith("f") && !op.equals("F");
                            s.subpaths = sub;
                            s.curved = curved;
                            s.lineWidth = lineWidth * ctm.scale();
                            for (double[] r : subBoxes) {
                                s.subs.add(new double[]{r[0] - box.getLowerLeftX(), box.getUpperRightY() - r[3],
                                        r[2] - box.getLowerLeftX(), box.getUpperRightY() - r[1]});
                            }
                            if (s.stroke && !s.fill) {   // grow by half the pen width
                                double hw = s.lineWidth / 2;
                                s.x0 -= hw; s.x1 += hw; s.y0 -= hw; s.y1 += hw;
                            }
                            // Chrome paints many glyphs twice (fill, then hairline stroke): keep one
                            Shape prev = pd.shapes.isEmpty() ? null : pd.shapes.get(pd.shapes.size() - 1);
                            boolean twin = s.stroke && !s.fill && prev != null && prev.fill
                                    && Math.abs(prev.cx() - s.cx()) < 0.6 && Math.abs(prev.cy() - s.cy()) < 0.6
                                    && Math.abs(prev.w() - s.w()) < s.lineWidth + 0.6;
                            if (!twin) pd.shapes.add(s);
                        }
                        bb = null; sub = 0; curved = false; subBoxes.clear();
                        break;
                    case "n":
                        bb = null; sub = 0; curved = false; subBoxes.clear();
                        break;
                    case "Do":
                        if (!rawOps.isEmpty() && rawOps.get(rawOps.size() - 1) instanceof COSName && res != null) {
                            PDXObject x = res.getXObject((COSName) rawOps.get(rawOps.size() - 1));
                            if (x instanceof PDFormXObject) {
                                PDFormXObject form = (PDFormXObject) x;
                                com.tom_roush.pdfbox.util.Matrix fm = form.getMatrix();
                                Matrix m = new Matrix(fm.getScaleX(), fm.getShearY(), fm.getShearX(),
                                        fm.getScaleY(), fm.getTranslateX(), fm.getTranslateY());
                                PDResources fr = form.getResources() != null ? form.getResources() : res;
                                run(form, fr, m.then(ctm), depth + 1);
                            } else if (x instanceof PDImageXObject) {
                                PDImageXObject img = (PDImageXObject) x;
                                try {
                                    boolean bilevel = img.isStencil() || img.getBitsPerComponent() == 1;
                                    addImage(img.getWidth(), img.getHeight(), bilevel,
                                            bilevel ? img.createInputStream() : null, ctm);
                                } catch (Exception e) {
                                    addImage(1, 1, false, null, ctm);
                                }
                            }
                        }
                        break;
                    case "BI": {
                        Operator bi = (Operator) t;
                        try {
                            PDInlineImage img = new PDInlineImage(bi.getImageParameters(), bi.getImageData(), res);
                            addImage(img.getWidth(), img.getHeight(), img.isStencil() || img.getBitsPerComponent() == 1,
                                    img.createInputStream(), ctm);
                        } catch (Exception ignored) {
                            addImage(1, 1, false, null, ctm);
                        }
                        break;
                    }
                    case "BT":
                        tm = Matrix.identity();
                        tlm = Matrix.identity();
                        break;
                    case "Tf":
                        if (!rawOps.isEmpty() && rawOps.get(0) instanceof COSName && o.length >= 1 && res != null) {
                            try {
                                font = res.getFont((COSName) rawOps.get(0));
                            } catch (Exception e) {
                                font = null;
                            }
                            fontSize = o[o.length - 1];
                        }
                        break;
                    case "Tc": if (o.length == 1) charSpacing = o[0]; break;
                    case "Tw": if (o.length == 1) wordSpacing = o[0]; break;
                    case "Tz": if (o.length == 1) hScale = o[0] / 100; break;
                    case "TL": if (o.length == 1) leading = o[0]; break;
                    case "Ts": if (o.length == 1) rise = o[0]; break;
                    case "Tm":
                        if (o.length == 6) {
                            tlm = new Matrix(o[0], o[1], o[2], o[3], o[4], o[5]);
                            tm = tlm;
                        }
                        break;
                    case "Td":
                    case "TD":
                        if (o.length == 2) {
                            if (op.equals("TD")) leading = -o[1];
                            tlm = new Matrix(1, 0, 0, 1, o[0], o[1]).then(tlm);
                            tm = tlm;
                        }
                        break;
                    case "T*":
                        tlm = new Matrix(1, 0, 0, 1, 0, -leading).then(tlm);
                        tm = tlm;
                        break;
                    case "Tj":
                    case "'":
                    case "\"":
                    case "TJ": {
                        if (op.equals("'") || op.equals("\"")) {
                            if (op.equals("\"") && o.length >= 2) { wordSpacing = o[0]; charSpacing = o[1]; }
                            tlm = new Matrix(1, 0, 0, 1, 0, -leading).then(tlm);
                            tm = tlm;
                        }
                        if (!isSymbolType3(font)) break;     // real text: read by the text stripper
                        List<Object> parts = new ArrayList<>();
                        for (COSBase b : rawOps) {
                            if (b instanceof COSString) parts.add(b);
                            else if (b instanceof COSArray) for (COSBase x : (COSArray) b) parts.add(x);
                        }
                        for (Object part : parts) {
                            if (part instanceof COSNumber) {
                                double adj = -((COSNumber) part).floatValue() / 1000.0 * fontSize * hScale;
                                tm = new Matrix(1, 0, 0, 1, adj, 0).then(tm);
                            } else if (part instanceof COSString) {
                                tm = showType3((PDType3Font) font, ((COSString) part).getBytes(), tm, ctm,
                                        fontSize, charSpacing, wordSpacing, hScale, rise, depth);
                            }
                        }
                        break;
                    }
                    default:
                        break;
                }
                ops.clear();
                rawOps.clear();
            }
        }

        /** Draws the glyphs of a Type3 string (each glyph is a small content stream). */
        private Matrix showType3(PDType3Font font, byte[] bytes, Matrix tm, Matrix ctm, double fontSize,
                                 double charSpacing, double wordSpacing, double hScale, double rise, int depth) throws IOException {
            com.tom_roush.pdfbox.util.Matrix fm = font.getFontMatrix();
            Matrix fontMatrix = new Matrix(fm.getScaleX(), fm.getShearY(), fm.getShearX(), fm.getScaleY(),
                    fm.getTranslateX(), fm.getTranslateY());
            java.io.InputStream in = new java.io.ByteArrayInputStream(bytes);
            PDResources fr = font.getResources();
            while (in.available() > 0) {
                int code = font.readCode(in);
                Matrix trm = new Matrix(fontSize * hScale, 0, 0, fontSize, 0, rise).then(tm);
                PDType3CharProc proc = font.getCharProc(code);
                if (proc != null) run(proc, fr != null ? fr : new PDResources(), fontMatrix.then(trm).then(ctm), depth + 1);
                double w = font.getDisplacement(code).getX();
                double tx = (w * fontSize + charSpacing + (code == 32 ? wordSpacing : 0)) * hScale;
                tm = new Matrix(1, 0, 0, 1, tx, 0).then(tm);
            }
            return tm;
        }

        /**
         * An image is drawn into the unit square of the CTM. Bitmaps of 1 bit (glyphs and rules
         * of older PDF printers) become shapes like drawn paths; a white centre marks a hollow
         * glyph (half/whole note head, sharp, natural) like a path with a hole.
         */
        private void addImage(int w, int h, boolean bilevel, java.io.InputStream data, Matrix ctm) {
            double[] b = add(null, ctm, 0, 0);
            add(b, ctm, 1, 0);
            add(b, ctm, 0, 1);
            add(b, ctm, 1, 1);
            Shape s = new Shape();
            s.x0 = b[0] - box.getLowerLeftX();
            s.x1 = b[2] - box.getLowerLeftX();
            s.y0 = box.getUpperRightY() - b[3];
            s.y1 = box.getUpperRightY() - b[1];
            s.fill = true;
            s.subpaths = 1;
            s.curved = w > 2 && h > 2;               // a real glyph, not a 1x1 rule
            if (bilevel && data != null && w > 4 && h > 4) {
                try {
                    int stride = (w + 7) / 8;
                    byte[] bits = new byte[stride * h];
                    int read = 0, n;
                    while (read < bits.length && (n = data.read(bits, read, bits.length - read)) > 0) read += n;
                    // image masks paint where the sample is 0
                    int ink = 0;
                    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) if (bit(bits, stride, x, y) == 0) ink++;
                    s.ink = (double) ink / (w * h);
                    // hollow glyph (like a path with a hole): white centre, ink left+right and above+below
                    int cx = w / 2, cy = h / 2;
                    boolean centreWhite = true;
                    for (int y = cy - h / 10; y <= cy + h / 10; y++)
                        for (int x = cx - w / 10; x <= cx + w / 10; x++)
                            if (x >= 0 && y >= 0 && x < w && y < h && bit(bits, stride, x, y) == 0) centreWhite = false;
                    boolean left = false, right = false, up = false, down = false;
                    for (int x = 0; x < cx; x++) if (bit(bits, stride, x, cy) == 0) left = true;
                    for (int x = cx + 1; x < w; x++) if (bit(bits, stride, x, cy) == 0) right = true;
                    for (int y = 0; y < cy; y++) if (bit(bits, stride, cx, y) == 0) up = true;
                    for (int y = cy + 1; y < h; y++) if (bit(bits, stride, cx, y) == 0) down = true;
                    if (centreWhite && left && right && up && down) s.subpaths = 2;
                } catch (Exception ignored) {
                    // keep as filled glyph
                }
            }
            if (s.w() > 0 || s.h() > 0) pd.shapes.add(s);
        }

        private static int bit(byte[] bits, int stride, int x, int y) {
            return (bits[y * stride + (x >> 3)] >> (7 - (x & 7))) & 1;
        }

        private static void addSub(List<double[]> subs, Matrix m, double x, double y) {
            if (subs.isEmpty()) subs.add(add(null, m, x, y));
            else add(subs.get(subs.size() - 1), m, x, y);
        }

        private static double[] add(double[] bb, Matrix m, double x, double y) {
            double px = m.tx(x, y), py = m.ty(x, y);
            if (bb == null) return new double[]{px, py, px, py};
            bb[0] = Math.min(bb[0], px);
            bb[1] = Math.min(bb[1], py);
            bb[2] = Math.max(bb[2], px);
            bb[3] = Math.max(bb[3], py);
            return bb;
        }
    }

    // ------------------------------------------------------------------ text

    private static final class Stripper extends PDFTextStripper {
        private final List<PageData> pages;
        private final List<List<TextPosition>> chars = new ArrayList<>();
        private final java.util.Set<String> seen = new java.util.HashSet<>();

        Stripper(List<PageData> pages) throws IOException {
            super();
            this.pages = pages;
            for (int i = 0; i < pages.size(); i++) chars.add(new ArrayList<>());
        }

        @Override
        protected void processTextPosition(TextPosition text) {
            int p = getCurrentPageNo() - 1;
            if (isSymbolType3(text.getFont())) return;     // drawn music symbols, see PathCollector
            String font = text.getFont() != null ? text.getFont().getName() : null;
            if (font != null && MUSIC_FONT.matcher(font).matches() && p >= 0 && p < pages.size()) {
                String u = text.getUnicode();
                if (u != null && !u.isEmpty()) {
                    Glyph g = new Glyph();
                    g.c = u.charAt(0);
                    g.x0 = text.getXDirAdj();
                    g.x1 = g.x0 + text.getWidthDirAdj();
                    g.baseline = text.getYDirAdj();
                    g.size = text.getFontSizeInPt();
                    pages.get(p).glyphs.add(g);      // no de-duplication: unisons are two equal heads
                }
                return;
            }
            // some PDFs (Chrome) draw every glyph twice: fill and outline
            String key = p + "|" + text.getUnicode() + "|" + Math.round(text.getXDirAdj() * 5)
                    + "|" + Math.round(text.getYDirAdj() * 5);
            if (!seen.add(key)) return;
            if (p >= 0 && p < chars.size()) chars.get(p).add(text);
        }

        void finish(PageData pd) {
            List<TextPosition> list = new ArrayList<>(chars.get(pages.indexOf(pd)));
            // group into lines by baseline, then words by gaps/spaces.
            // (A comparator with a tolerance is not transitive and makes TimSort throw
            // "Comparison method violates its general contract", so lines are numbered first.)
            Collections.sort(list, (a, b) -> Double.compare(a.getYDirAdj(), b.getYDirAdj()));
            java.util.Map<TextPosition, Integer> lineOf = new java.util.IdentityHashMap<>();
            int line = -1;
            double lineY = Double.NEGATIVE_INFINITY;
            for (TextPosition tp : list) {
                double fa = Math.max(tp.getFontSizeInPt(), 1);
                if (tp.getYDirAdj() - lineY > 0.3 * fa) {
                    line++;
                    lineY = tp.getYDirAdj();
                }
                lineOf.put(tp, line);
            }
            Collections.sort(list, (a, b) -> {
                int c = Integer.compare(lineOf.get(a), lineOf.get(b));
                return c != 0 ? c : Double.compare(a.getXDirAdj(), b.getXDirAdj());
            });
            TextItem cur = null;
            StringBuilder sb = new StringBuilder();
            for (TextPosition tp : list) {
                String u = tp.getUnicode();
                if (u == null) continue;
                double fs = Math.max(tp.getFontSizeInPt(), tp.getHeightDir());
                if (fs <= 0) fs = 8;
                double x0 = tp.getXDirAdj(), x1 = x0 + tp.getWidthDirAdj(), y = tp.getYDirAdj();
                boolean space = u.trim().isEmpty();
                // abcjs writes every syllable as its own text run: inside a run the glyphs
                // touch exactly, between runs there is a visible gap
                boolean newWord = cur == null || space
                        || Math.abs(y - cur.baseline) > 0.3 * fs
                        || x0 - cur.x1 > Math.max(0.5, 0.03 * fs)
                        || x0 < cur.x0 - 0.5;
                if (newWord && cur != null) {
                    cur.text = sb.toString();
                    if (!cur.text.isEmpty()) pd.texts.add(cur);
                    cur = null;
                }
                if (space) continue;
                if (cur == null) {
                    cur = new TextItem();
                    cur.x0 = x0;
                    cur.baseline = y;
                    cur.fontSize = fs;
                    sb.setLength(0);
                }
                sb.append(u);
                cur.x1 = Math.max(cur.x1, x1);
            }
            if (cur != null) {
                cur.text = sb.toString();
                if (!cur.text.isEmpty()) pd.texts.add(cur);
            }
            for (TextItem t : pd.texts) t.text = fixCyrillic(t.text);
        }
    }
}
