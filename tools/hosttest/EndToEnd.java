import de.lanixx.chorstimme.core.*;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

/** args: input(.pdf|.abc) outBase */
public class EndToEnd {
    public static void main(String[] a) throws Exception {
        long t0 = System.currentTimeMillis();
        Score s = a[0].endsWith(".pdf")
                ? PdfScoreExtractor.extract(PdfReader.read(new FileInputStream(a[0])))
                : AbcParser.parse(new String(Files.readAllBytes(Paths.get(a[0])), "UTF-8"));
        long t1 = System.currentTimeMillis();
        ChoirSynth.Options o = new ChoirSynth.Options();
        short[] pcm = ChoirSynth.render(s, o, null);
        long t2 = System.currentTimeMillis();
        byte[] mp3 = Mp3Encoder.encodeStereo(pcm, ChoirSynth.SAMPLE_RATE, 160, null);
        long t3 = System.currentTimeMillis();
        try (FileOutputStream f = new FileOutputStream(a[1] + ".mp3")) { f.write(mp3); }
        try (FileOutputStream f = new FileOutputStream(a[1] + ".mid")) { f.write(MidiWriter.write(s, 1.0, null)); }
        System.out.printf("parse %d ms, synth %d ms, mp3 %d ms, %.1f s audio, %d KB mp3, warnings %s%n",
                t1 - t0, t2 - t1, t3 - t2, pcm.length / 2.0 / ChoirSynth.SAMPLE_RATE, mp3.length / 1024, s.warnings);
    }
}
