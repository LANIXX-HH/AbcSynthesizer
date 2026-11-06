import de.lanixx.chorstimme.core.PdfReader;

import java.io.FileInputStream;
import java.util.List;

public class Dump {
    public static void main(String[] a) throws Exception {
        List<PdfReader.PageData> pages = PdfReader.read(new FileInputStream(a[0]));
        int p = 0;
        for (PdfReader.PageData pd : pages) {
            System.out.printf("PAGE %d %.0fx%.0f shapes=%d texts=%d%n", p++, pd.width, pd.height,
                    pd.shapes.size(), pd.texts.size());
            if (a.length > 1 && a[1].equals("all")) {
                for (PdfReader.Shape s : pd.shapes) System.out.println("  " + s);
                for (PdfReader.TextItem t : pd.texts) System.out.println("  " + t);
            }
        }
    }
}
