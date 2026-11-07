#!/bin/sh
# Runs ImageTest on all rasterized test pieces and prints one summary line each.
CP=/home/claude/hosttest/out:/home/claude/tools/pdfbox-desktop.jar
R=/home/claude/rig/abc
I=/tmp/img
run() {
  name=$1; shift
  printf '%-4s ' "$name"
  java -cp $CP ImageTest "$@" 2>/dev/null | tail -n 1
}
run t1 $R/t1_milost.abc $I/t1_milost-1.png
run t2 $R/t2_treble.abc $I/t2_treble-1.png
run t3 $R/t3_satb.abc $I/t3_satb-1.png
run t4 $R/t4_four.abc $I/t4_four-1.png
run t5 $R/t5_rhythm.abc $I/t5_rhythm-1.png
run doc /home/claude/in/doc.pdf $I/doc-1.png $I/doc-2.png
run m /home/claude/in2/m.pdf $I/m-1.png $I/m-2.png $I/m-3.png
run z /home/claude/in3/z.pdf $I/z-1.png $I/z-2.png $I/z-3.png
run i /home/claude/in4/i.pdf $I/i-1.png $I/i-2.png $I/i-3.png $I/i-4.png
run t /home/claude/in5/t.pdf $I/t-1.png $I/t-2.png
