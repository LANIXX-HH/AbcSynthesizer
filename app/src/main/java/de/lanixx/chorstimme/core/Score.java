package de.lanixx.chorstimme.core;

import java.util.ArrayList;
import java.util.List;

/** Recognised piece: voices with timed notes. Times are in quarter-note beats. */
public class Score {

    public static class Note {
        public double start;      // beats
        public double duration;   // beats
        public int[] pitches;     // MIDI numbers; a chord has several
        public String syllable;   // null if no new syllable on this note
        public char vowel;        // a, e, i, o, u – vowel sung on this note

        public Note(double start, double duration, int[] pitches) {
            this.start = start;
            this.duration = duration;
            this.pitches = pitches;
        }

        public double end() {
            return start + duration;
        }
    }

    public static class Voice {
        public String name;
        public final List<Note> notes = new ArrayList<>();

        public Voice(String name) {
            this.name = name;
        }

        public double end() {
            double e = 0;
            for (Note n : notes) e = Math.max(e, n.end());
            return e;
        }

        public String lyrics() {
            StringBuilder sb = new StringBuilder();
            for (Note n : notes) {
                if (n.syllable == null) continue;
                String s = n.syllable;
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') sb.append(' ');
                sb.append(s);
            }
            return sb.toString().replace("- ", "-").replace("-", "");
        }
    }

    public String title = "";
    public double tempoBpm = 100;   // quarter notes per minute
    public final List<Voice> voices = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();

    public double end() {
        double e = 0;
        for (Voice v : voices) e = Math.max(e, v.end());
        return e;
    }

    public int noteCount() {
        int c = 0;
        for (Voice v : voices) c += v.notes.size();
        return c;
    }

    /** Fill in the sung vowel for every note: from its syllable, else held from the previous note. */
    public void assignVowels() {
        // voices without lyrics take the vowel of the voice that has lyrics at the same time
        Voice lead = null;
        for (Voice v : voices) {
            for (Note n : v.notes) if (n.syllable != null) { lead = v; break; }
            if (lead != null) break;
        }
        for (Voice v : voices) {
            char cur = 'a';
            boolean has = false;
            for (Note n : v.notes) if (n.syllable != null) has = true;
            for (Note n : v.notes) {
                if (has) {
                    if (n.syllable != null) cur = Vowels.of(n.syllable, cur);
                } else if (lead != null) {
                    cur = vowelAt(lead, n.start, cur);
                }
                n.vowel = cur;
            }
        }
    }

    private static char vowelAt(Voice v, double t, char fallback) {
        char cur = fallback;
        for (Note n : v.notes) {
            if (n.start > t + 1e-6) break;
            if (n.syllable != null) cur = Vowels.of(n.syllable, cur);
        }
        return cur;
    }
}
