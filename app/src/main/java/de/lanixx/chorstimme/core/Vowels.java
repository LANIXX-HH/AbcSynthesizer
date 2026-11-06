package de.lanixx.chorstimme.core;

/** Maps a syllable (Cyrillic, Church Slavonic, Latin, German) to one of five sung vowels. */
public final class Vowels {
    private Vowels() {}

    public static char of(String syllable, char fallback) {
        String s = syllable.toLowerCase();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // Latin "ou"/Slavonic "оу" -> u
            if ((c == 'о' || c == 'o') && i + 1 < s.length()
                    && (s.charAt(i + 1) == 'у' || s.charAt(i + 1) == 'u')) return 'u';
            switch (c) {
                case 'а': case 'я': case 'a': case 'ä': case 'à': case 'á':
                    return 'a';
                case 'о': case 'ё': case 'ѡ': case 'o': case 'ö': case 'ò': case 'ó':
                    return 'o';
                case 'у': case 'ю': case 'ꙋ': case 'u': case 'ü': case 'ù': case 'ú':
                    return 'u';
                case 'и': case 'ы': case 'і': case 'ї': case 'ѵ': case 'i': case 'y': case 'í':
                    return 'i';
                case 'е': case 'э': case 'є': case 'ѣ': case 'e': case 'é': case 'è':
                    return 'e';
                default:
                    break;
            }
        }
        return fallback;
    }
}
