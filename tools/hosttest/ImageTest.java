import de.lanixx.chorstimme.core.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.*;
import java.util.*;
/** args: truth(.abc|.pdf) page1.png [page2.png ...] */
public class ImageTest {
  public static void main(String[] a) throws Exception {
    Score truth = a[0].endsWith(".abc") ? AbcParser.parse(new String(Files.readAllBytes(Paths.get(a[0])), "UTF-8"))
        : PdfScoreExtractor.extract(PdfReader.read(new java.io.FileInputStream(a[0])));
    List<PdfReader.PageData> pages = new ArrayList<>();
    long t0 = System.currentTimeMillis();
    for (int i = 1; i < a.length; i++) {
      BufferedImage im = ImageIO.read(new File(a[i]));
      int w = im.getWidth(), h = im.getHeight(); byte[] px = new byte[w * h];
      for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) { if (im.getType() == BufferedImage.TYPE_BYTE_GRAY) { px[y * w + x] = (byte) im.getRaster().getSample(x, y, 0); continue; } int rgb = im.getRGB(x, y); int g = ((rgb >> 16 & 255) * 3 + (rgb >> 8 & 255) * 6 + (rgb & 255)) / 10; px[y * w + x] = (byte) g; }
      pages.addAll(ImageScoreReader.read(new ImageScoreReader.Gray(w, h, px)));
    }
    Score s = PdfScoreExtractor.extract(pages);
    long t1 = System.currentTimeMillis();
    int total = 0, okPitch = 0, okBoth = 0;
    StringBuilder vinfo = new StringBuilder();
    for (int v = 0; v < truth.voices.size(); v++) {
      Score.Voice x = truth.voices.get(v); Score.Voice y = v < s.voices.size() ? s.voices.get(v) : null;
      vinfo.append(x.notes.size()).append('/').append(y == null ? 0 : y.notes.size()).append(' ');
      if (y == null) { total += x.notes.size(); continue; }
      // align by pitch sequence (LCS), then compare durations of matched notes
      List<Score.Note> X = x.notes, Y = y.notes; int n1 = X.size(), n2 = Y.size();
      int[][] L = new int[n1 + 1][n2 + 1];
      for (int i = n1 - 1; i >= 0; i--) for (int j = n2 - 1; j >= 0; j--)
        L[i][j] = Arrays.equals(sorted(X.get(i).pitches), sorted(Y.get(j).pitches)) ? L[i+1][j+1] + 1 : Math.max(L[i+1][j], L[i][j+1]);
      int i = 0, j = 0; total += n1;
      while (i < n1 && j < n2) {
        if (Arrays.equals(sorted(X.get(i).pitches), sorted(Y.get(j).pitches))) { okPitch++; if (Math.abs(X.get(i).duration - Y.get(j).duration) < 1e-3) okBoth++; else if (System.getenv("DUR") != null) System.out.println("  dur " + x.name + " #" + i + " " + X.get(i).duration + " vs " + Y.get(j).duration); i++; j++; }
        else if (L[i+1][j] >= L[i][j+1]) { if (System.getenv("DUR") != null) System.out.println("  missing " + x.name + " #" + i + " " + ExtractTest.nm(X.get(i).pitches)); i++; } else { if (System.getenv("DUR") != null) System.out.println("  extra " + x.name + " " + ExtractTest.nm(Y.get(j).pitches)); j++; }
      }
    }
    System.out.printf("notes truth/img per voice: %s| pitch-seq match %d/%d (%.0f%%), +duration %d (%.0f%%) | %d ms | warnings %s%n",
        vinfo, okPitch, total, 100.0 * okPitch / Math.max(1, total), okBoth, 100.0 * okBoth / Math.max(1, total), t1 - t0, s.warnings);
  }
  static int[] sorted(int[] p) { int[] c = p.clone(); Arrays.sort(c); return c; }
}
