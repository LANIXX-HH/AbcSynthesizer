import de.lanixx.chorstimme.core.*;
import java.io.FileInputStream;
import java.nio.file.*;
/** PDF/ABC -> Score -> ABC text -> Score again; compare notes. args: input [out.abc] */
public class RoundTrip {
  public static void main(String[] a) throws Exception {
    Score s = a[0].endsWith(".pdf") ? PdfScoreExtractor.extract(PdfReader.read(new FileInputStream(a[0])))
        : AbcParser.parse(new String(Files.readAllBytes(Paths.get(a[0])), "UTF-8"));
    String abc = AbcWriter.write(s);
    if (a.length > 1) Files.write(Paths.get(a[1]), abc.getBytes("UTF-8"));
    Score r = AbcParser.parse(abc);
    int diffs = 0, lyrDiff = 0;
    for (int v = 0; v < s.voices.size(); v++) {
      Score.Voice x = s.voices.get(v), y = v < r.voices.size() ? r.voices.get(v) : null;
      if (y == null) { System.out.println("voice missing " + x.name); diffs++; continue; }
      int n = Math.max(x.notes.size(), y.notes.size());
      for (int i = 0; i < n; i++) {
        String sx = i < x.notes.size() ? fmt(x.notes.get(i)) : "-", sy = i < y.notes.size() ? fmt(y.notes.get(i)) : "-";
        if (!sx.equals(sy)) { if (diffs < 6) System.out.println("  " + x.name + " #" + i + " orig " + sx + " abc " + sy); diffs++; }
      }
      if (!x.lyrics().equals(y.lyrics())) { lyrDiff++; if (lyrDiff < 3) System.out.println("  LYR orig '" + x.lyrics() + "'\n      abc  '" + y.lyrics() + "'"); }
    }
    System.out.println((diffs == 0 ? "NOTES IDENTICAL" : "NOTE DIFFS " + diffs) + (lyrDiff == 0 ? ", LYRICS IDENTICAL" : ", LYRICS DIFF in " + lyrDiff + " voices") + "  (" + abc.split("\n").length + " ABC lines, warnings " + r.warnings + ")");
  }
  static String fmt(Score.Note n) { StringBuilder b = new StringBuilder(String.format("%.3f+%.3f:", n.start, n.duration)); int[] p = n.pitches.clone(); java.util.Arrays.sort(p); for (int x : p) b.append(x).append(','); return b.toString(); }
}
