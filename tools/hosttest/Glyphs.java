import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import java.io.File;
import java.util.*;
public class Glyphs {
  public static void main(String[] a) throws Exception {
    PDDocument d = PDDocument.load(new File(a[0]));
    Map<String,Integer> cnt = new TreeMap<>();
    Map<String,String> ex = new HashMap<>();
    new PDFTextStripper() {
      @Override protected void processTextPosition(TextPosition t) {
        String f = t.getFont().getName();
        if (f == null || !f.contains("Maestro")) return;
        String u = t.getUnicode(); int code = t.getCharacterCodes()[0];
        String k = u + " code=" + code;
        cnt.merge(k, 1, Integer::sum);
        ex.putIfAbsent(k, String.format("p%d x=%.1f y=%.1f w=%.1f fs=%.1f", getCurrentPageNo(), t.getXDirAdj(), t.getYDirAdj(), t.getWidthDirAdj(), t.getFontSizeInPt()));
      }
    }.getText(d);
    for (String k : cnt.keySet()) System.out.println(k + " n=" + cnt.get(k) + "  " + ex.get(k));
  }
}
