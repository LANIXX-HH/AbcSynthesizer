import de.lanixx.chorstimme.core.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
public class ImgDump {
  public static void main(String[] a) throws Exception {
    BufferedImage im = ImageIO.read(new File(a[0]));
    int w = im.getWidth(), h = im.getHeight(); byte[] px = new byte[w * h];
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) { if (im.getType() == BufferedImage.TYPE_BYTE_GRAY) { px[y * w + x] = (byte) im.getRaster().getSample(x, y, 0); continue; } int rgb = im.getRGB(x, y); px[y * w + x] = (byte) (((rgb >> 16 & 255) * 3 + (rgb >> 8 & 255) * 6 + (rgb & 255)) / 10); }
    List<PdfReader.PageData> pages = ImageScoreReader.read(new ImageScoreReader.Gray(w, h, px));
    double ymax = a.length > 1 ? Double.parseDouble(a[1]) : 1e9;
    for (PdfReader.Shape s : pages.get(0).shapes) if (s.y0 < ymax) System.out.println(s + String.format(" ink=%.2f up=%.2f w/sp=%.2f h/sp=%.2f", s.ink, s.upperInk, s.w()/5.5, s.h()/5.5));
    if (System.getenv("XDBG") != null) PdfScoreExtractor.debug = System.out;
    Score sc = PdfScoreExtractor.extract(pages);
    for (Score.Voice v : sc.voices) { StringBuilder b = new StringBuilder(v.name + ": "); for (Score.Note n : v.notes) b.append(ExtractTest.nm(n.pitches)).append('/').append(n.duration).append('@').append(n.start).append(' '); System.out.println(b); }
  }
}
