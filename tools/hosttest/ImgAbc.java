import de.lanixx.chorstimme.core.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
/** args: image -> ABC on stdout (what the app produces from a picture) */
public class ImgAbc {
  public static void main(String[] a) throws Exception {
    BufferedImage im = ImageIO.read(new File(a[0]));
    int w = im.getWidth(), h = im.getHeight(); byte[] px = new byte[w * h];
    for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) { if (im.getType() == BufferedImage.TYPE_BYTE_GRAY) { px[y * w + x] = (byte) im.getRaster().getSample(x, y, 0); continue; } int rgb = im.getRGB(x, y); px[y * w + x] = (byte) (((rgb >> 16 & 255) * 3 + (rgb >> 8 & 255) * 6 + (rgb & 255)) / 10); }
    Score sc = PdfScoreExtractor.extract(ImageScoreReader.read(new ImageScoreReader.Gray(w, h, px)));
    sc.title = a.length > 1 ? a[1] : "Bild";
    System.out.print(AbcWriter.write(sc));
    for (String s : sc.warnings) System.err.println("WARN " + s);
  }
}
