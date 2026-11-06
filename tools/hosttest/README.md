# Host-Tests (ohne Android)

Der Kern (`app/src/main/java/de/lanixx/chorstimme/core`) ist reines Java. Für Tests auf dem
Rechner wird `com.tom_roush.pdfbox` durch das Desktop-PDFBox (`org.apache.pdfbox`, 2.0.x)
ersetzt:

```sh
mkdir -p /tmp/ct/src /tmp/ct/out
for f in ../../app/src/main/java/de/lanixx/chorstimme/core/*.java; do
  sed 's/com\.tom_roush\.pdfbox/org.apache.pdfbox/g' "$f" > /tmp/ct/src/$(basename "$f")
done
javac -encoding UTF-8 -cp pdfbox-app-2.0.x.jar -d /tmp/ct/out /tmp/ct/src/*.java *.java

# PDF und ABC gegeneinander prüfen (gleiche Noten, gleiche Zeiten?)
java -cp /tmp/ct/out:pdfbox-app-2.0.x.jar Compare t5_rhythm.abc t5_rhythm.pdf
```

Für `EndToEnd` (MP3) die LAME-Bibliothek für den Host bauen:

```sh
gcc -shared -fPIC -O2 -w -DSTDC_HEADERS=1 -I$JAVA_HOME/include -I$JAVA_HOME/include/linux \
  -I../../app/src/main/cpp/lame ../../app/src/main/cpp/lame_jni.c ../../app/src/main/cpp/lame/*.c \
  -lm -o libchorlame.so
java -Djava.library.path=. -cp /tmp/ct/out:pdfbox-app-2.0.x.jar EndToEnd noten.pdf ausgabe
```

Die `.abc`-Dateien sind die Testfälle; die passenden PDFs entstehen, indem man sie mit abcjs
im Browser rendert und als PDF druckt.
