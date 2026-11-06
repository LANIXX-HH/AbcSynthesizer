# Chorstimme

Android-App: Noten-PDF (aus abcjs gedruckt) oder ABC-Notation laden → Übungsaufnahme als
MP3 (synthetischer Chor, singt die Vokale des Textes) und MIDI-Datei. Alles läuft offline auf
dem Handy.

## Funktionen

- **PDF öffnen** – liest die Vektorgrafik der PDF (Notenköpfe, Hälse, Fähnchen, Balken,
  Punkte, Pausen, Vorzeichen, Haltebögen, Triolen, Schlüssel, Liedtext) und baut daraus die Noten.
  Mehrere Stimmen pro System (Sopran/Alt auf einem System) und mehrere Systeme pro Zeile
  (SATB) werden unterstützt.
- **ABC-Datei / ABC-Text** – direkter Import der ABC-Notation (Stimmen, Vorzeichen, Bögen,
  Triolen, punktierte Rhythmen, Wiederholungen mit 1./2. Klammer, `w:`-Text).
- **Tempo** 50–150 %, **Stimme hervorheben** („Alt betont“, „Nur Alt“), **Einzähler**.
- **Abspielen** in der App, **MP3 speichern**, **MIDI speichern** (Chor-Klang, Text als
  Lyrics-Events – z. B. in MuseScore lesbar), **ABC speichern** (ABC-Notation mit Stimmen,
  Takten, Tonart, Liedtext; direkt in abcjs/ABC-Editoren nutzbar). MIDI und ABC stehen sofort
  nach dem Einlesen bereit.
- Prüfung: Sind die Stimmen einer Zeile unterschiedlich lang, zeigt die App einen Hinweis.

## Grenzen

- PDFs: Notensatz von **abcjs** (Druck aus dem Browser), **Finale** mit Notenschrift
  „Maestro“ (z. B. Noten von azbyka.ru) und ältere PDFs, deren Notenzeichen als kleine
  Bitmaps (Type3-Schriften, Acrobat Distiller) gespeichert sind. Gescannte Noten oder PDFs aus Sibelius/MuseScore/LilyPond
  werden nicht erkannt.
- Ohne Stimmnamen in der PDF werden die Stimmen aus den Notenhälsen erkannt und
  Sopran/Alt/Tenor/Bass genannt (bei 4 Stimmen).
- Wiederholungszeichen in PDFs werden erkannt, aber nicht ausgeschrieben (Hinweis erscheint).
  Mit ABC-Text werden Wiederholungen ausgeschrieben.
- Kein echter Gesang mit Worten: Der Chor singt die Vokale (a, e, i, o, u) der Silben.

## APK bauen

**Release (empfohlen)** – zwei Wege:

- GitHub → *Actions → APK bauen → Run workflow*, Version eintragen (z. B. `1.0.0`). Der
  Workflow baut die APK und legt das Release `v1.0.0` mit der APK an.
- Oder auf GitHub ein Release veröffentlichen (*Releases → Draft a new release*, Tag z. B.
  `v1.0.1`). Der Workflow baut die APK und hängt sie an dieses Release.

Ohne Release (normaler Push) liegt die APK nur als Artifact unter *Actions → Lauf → Artifacts*.

Die APK ist mit einem festen Schlüssel aus dem Repository signiert (`app/chorstimme.keystore`): direkt installierbar, neue Versionen lassen sich darüber installieren. Für den Play Store einen eigenen, geheimen Schlüssel verwenden.

**Android Studio:** Projektordner öffnen, „Run“. NDK und CMake installiert Android Studio bei
Bedarf selbst.

## Aufbau

| Pfad | Inhalt |
|---|---|
| `app/src/main/java/.../MainActivity.java` | Oberfläche |
| `core/PdfReader.java` | PDF-Vektorpfade und Text auslesen (PdfBox-Android) |
| `core/PdfScoreExtractor.java` | Notenerkennung aus den Vektorformen (relativ zum Linienabstand) |
| `core/AbcParser.java` | ABC-Notation |
| `core/ChoirSynth.java` | Chorklang: additive Formant-Synthese, Vibrato, Hall |
| `core/MidiWriter.java` | MIDI-Datei |
| `core/AbcWriter.java` | ABC-Export (Tonart geschätzt, Takte/Zeilen wie erkannt, Triolen, w:-Text) |
| `core/Mp3Encoder.java` + `cpp/` | MP3 über LAME (NDK/JNI) |
| `tools/hosttest/` | Tests auf dem Rechner (Desktop-Java) |

## Lizenzen

- LAME 3.99.5 (`app/src/main/cpp/lame`) – LGPL 2, siehe `COPYING` dort.
- PdfBox-Android – Apache License 2.0.
