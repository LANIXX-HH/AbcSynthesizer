#!/bin/sh
# PDF regression: test PDFs against their ABC source, and the user's PDFs against a saved baseline.
CP=/home/claude/hosttest/out:/home/claude/tools/pdfbox-desktop.jar
for t in t1_milost t2_treble t3_satb t4_four t5_rhythm t6_long t7_repeat; do
  printf '%-10s ' $t
  java -cp $CP Compare /home/claude/rig/abc/$t.abc /home/claude/rig/pdf/$t.pdf 2>/dev/null | tail -n 1
done
mkdir -p /tmp/base
for f in /home/claude/in/doc.pdf /home/claude/in2/m.pdf /home/claude/in3/z.pdf /home/claude/in4/i.pdf /home/claude/in5/t.pdf; do
  n=$(basename $f .pdf)
  java -cp $CP ExtractTest $f > /tmp/base/$n.new 2>/dev/null
  if [ -f /tmp/base/$n.txt ]; then
    if cmp -s /tmp/base/$n.txt /tmp/base/$n.new; then echo "$n unchanged"; else echo "$n CHANGED"; fi
  else
    mv /tmp/base/$n.new /tmp/base/$n.txt; echo "$n baseline saved"
  fi
done
