import de.lanixx.chorstimme.core.*;
import java.io.FileInputStream;
public class Lines { public static void main(String[] a) throws Exception {
  for (String f : a) { Score s = PdfScoreExtractor.extract(PdfReader.read(new FileInputStream(f)));
    StringBuilder b = new StringBuilder(); double prev = 0; for (double x : s.lineStarts) { if (x > 0) b.append(String.format("%.2f ", x - prev)); prev = x; } b.append(String.format("%.2f", s.end() - prev));
    System.out.println(b); } } }
