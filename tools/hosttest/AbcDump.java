import de.lanixx.chorstimme.core.*;
import java.nio.file.*;
public class AbcDump { public static void main(String[] a) throws Exception {
  Score s = AbcParser.parse(new String(Files.readAllBytes(Paths.get(a[0])), "UTF-8"));
  for (Score.Voice v : s.voices) { StringBuilder b = new StringBuilder(v.name + ": ");
    for (Score.Note n : v.notes) b.append(n.pitches[0]).append('@').append(n.start).append('/').append(n.duration).append(n.syllable != null ? "[" + n.syllable + "]" : "").append(' ');
    System.out.println(b); } System.out.println(s.warnings); } }
