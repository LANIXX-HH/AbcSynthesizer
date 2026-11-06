import de.lanixx.chorstimme.core.*;
import java.io.FileInputStream;
/** compare scores from two PDFs (original vs. abcjs rendering of the exported ABC) */
public class Circle {
  public static void main(String[] a) throws Exception {
    Score s = PdfScoreExtractor.extract(PdfReader.read(new FileInputStream(a[0])));
    Score r = PdfScoreExtractor.extract(PdfReader.read(new FileInputStream(a[1])));
    int diffs = 0, total = 0;
    for (int v = 0; v < s.voices.size(); v++) {
      Score.Voice x = s.voices.get(v), y = v < r.voices.size() ? r.voices.get(v) : null;
      if (y == null) { System.out.println("voice missing"); diffs++; continue; }
      int n = Math.max(x.notes.size(), y.notes.size()); total += n;
      for (int i = 0; i < n; i++) {
        String sx = i < x.notes.size() ? RoundTrip.fmt(x.notes.get(i)) : "-", sy = i < y.notes.size() ? RoundTrip.fmt(y.notes.get(i)) : "-";
        if (!sx.equals(sy)) { if (diffs < 4) System.out.println("  " + x.name + " #" + i + " orig " + sx + " abcjs " + sy); diffs++; }
      }
    }
    System.out.println((diffs == 0 ? "IDENTICAL" : "DIFFS " + diffs) + " of " + total + " notes; warnings " + r.warnings);
  }
}
