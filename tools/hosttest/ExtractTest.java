import de.lanixx.chorstimme.core.*;
import java.io.FileInputStream;
public class ExtractTest {
  static String name(int m){ String[] n={"C","C#","D","Eb","E","F","F#","G","G#","A","Bb","B"}; return n[m%12]+(m/12-1); }
  public static void main(String[] a) throws Exception {
    Score s = PdfScoreExtractor.extract(PdfReader.read(new FileInputStream(a[0])));
    System.out.println("TITLE "+s.title+" tempo "+s.tempoBpm+" end "+s.end());
    for (String w: s.warnings) System.out.println("WARN "+w);
    for (Score.Voice v: s.voices) {
      StringBuilder sb=new StringBuilder();
      for (Score.Note n: v.notes){ sb.append(String.format("%s%s/%s", n.syllable!=null?"["+n.syllable+"]":"", nm(n.pitches), trim(n.duration))).append(' ');}
      System.out.println("VOICE "+v.name+" ("+v.notes.size()+"): "+sb);
    }
  }
  static String nm(int[] p){ StringBuilder b=new StringBuilder(); for(int i=0;i<p.length;i++){ if(i>0)b.append('+'); b.append(name(p[i]));} return b.toString(); }
  static String trim(double d){ return d==Math.rint(d)?String.valueOf((int)d):String.valueOf(d); }
}
