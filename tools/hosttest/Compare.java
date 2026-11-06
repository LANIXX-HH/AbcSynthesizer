import de.lanixx.chorstimme.core.*;
import java.io.FileInputStream;
import java.nio.file.*;
public class Compare {
  public static void main(String[] a) throws Exception {
    Score abc = AbcParser.parse(new String(Files.readAllBytes(Paths.get(a[0])), "UTF-8"));
    Score pdf = PdfScoreExtractor.extract(PdfReader.read(new FileInputStream(a[1])));
    System.out.println("ABC title="+abc.title+" tempo="+abc.tempoBpm+" voices="+abc.voices.size()+" | PDF title="+pdf.title+" tempo="+pdf.tempoBpm+" voices="+pdf.voices.size());
    for (String w: pdf.warnings) System.out.println("  PDF WARN "+w);
    for (String w: abc.warnings) System.out.println("  ABC WARN "+w);
    int diffs=0;
    for (int v=0; v<Math.max(abc.voices.size(),pdf.voices.size()); v++) {
      if (v>=abc.voices.size()||v>=pdf.voices.size()) { System.out.println("  voice count differs"); diffs++; break; }
      Score.Voice x=abc.voices.get(v), y=pdf.voices.get(v);
      int n=Math.max(x.notes.size(), y.notes.size());
      for (int i=0;i<n;i++){
        String sx = i<x.notes.size()? fmt(x.notes.get(i)) : "-";
        String sy = i<y.notes.size()? fmt(y.notes.get(i)) : "-";
        if (!sx.equals(sy)) { if (diffs<12) System.out.println("  "+x.name+" #"+i+" ABC "+sx+"  PDF "+sy); diffs++; }
      }
      System.out.println("  voice "+x.name+"/"+y.name+": abc "+x.notes.size()+" pdf "+y.notes.size()+"  lyrics abc='"+x.lyrics()+"' pdf='"+y.lyrics()+"'");
    }
    System.out.println(diffs==0? "  IDENTICAL (pitch+timing)" : "  DIFFS: "+diffs);
  }
  static String fmt(Score.Note n){ StringBuilder b=new StringBuilder(); b.append(String.format("%.3f+%.3f:",n.start,n.duration)); for(int p: n.pitches) b.append(p).append(','); return b.toString(); }
}
