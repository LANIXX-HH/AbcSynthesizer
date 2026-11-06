import de.lanixx.chorstimme.core.*;
import java.io.FileInputStream;
import java.nio.file.*;
/** args: input.pdf out.abc out.json – score notes as [[start,dur,pitch],...] per voice (chords split) */
public class NotesJson {
  public static void main(String[] a) throws Exception {
    Score s = PdfScoreExtractor.extract(PdfReader.read(new FileInputStream(a[0])));
    Files.write(Paths.get(a[1]), AbcWriter.write(s).getBytes("UTF-8"));
    StringBuilder b = new StringBuilder("[");
    for (int v = 0; v < s.voices.size(); v++) {
      if (v > 0) b.append(',');
      b.append('[');
      boolean first = true;
      for (Score.Note n : s.voices.get(v).notes) for (int p : n.pitches) {
        if (!first) b.append(','); first = false;
        b.append(String.format(java.util.Locale.ROOT, "[%.3f,%.3f,%d]", n.start, n.duration, p));
      }
      b.append(']');
    }
    Files.write(Paths.get(a[2]), b.append(']').toString().getBytes());
  }
}
