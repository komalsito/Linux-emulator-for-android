#!/bin/sh
# End-to-end proof that the REAL compiled Kotlin X11Client injects input.
#
# Stands up Xvfb + Openbox + an xterm whose shell logs every line it receives,
# then runs KotlinXtestDriver against the .class files compile_all.sh produced.
# If the driver's clicks and keystrokes show up in the xterm's log, the XTEST
# path in the shipped Kotlin code works - measured, not argued.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
D=${D:-94}
export DISPLAY=127.0.0.1:$D LANG=C.UTF-8 LC_ALL=C.UTF-8
OUT=/tmp/kxtest_typed.txt
KP1=${KP1:-/tmp/kp1}
ANDROID_JAR=${ANDROID_JAR:-/opt/asdk/android-34/android.jar}
KOTLINC_HOME=${KOTLINC_HOME:-/opt/kt/kotlinc}

[ -f "$KP1/com/emux/x11/X11Client.class" ] || {
  echo "X11Client.class not found in $KP1 - run compile_all.sh first"; exit 2; }

rm -f $OUT /tmp/.X$D-lock; mkdir -p /tmp/.X11-unix
Xvfb :$D -screen 0 720x1280x24 -listen tcp -ac -nolisten unix >/dev/null 2>&1 &
XV=$!
i=0; while [ $i -lt 100 ]; do [ -e /tmp/.X$D-lock ] && break; i=$((i+1)); sleep 0.1; done
sleep 0.8
[ -e /tmp/.X$D-lock ] || { echo "Xvfb failed to start"; exit 1; }
openbox >/dev/null 2>&1 &
sleep 1.5
# An xterm whose shell appends every line it reads to $OUT: typed text that
# arrives AND is followed by a working Return shows up as a line in the file.
xterm -fa Monospace -fs 11 -geometry 60x16+60+80 -e sh -c \
  'while IFS= read -r l; do printf "%s\n" "$l" >> '"$OUT"'; done' >/dev/null 2>&1 &
sleep 2.5

echo "=== compiling the driver against the real X11Client.class ==="
rm -rf /tmp/kdrv; mkdir -p /tmp/kdrv
"$KOTLINC_HOME/bin/kotlinc" -nowarn -jvm-target 17 \
  -cp "$ANDROID_JAR:$KP1" "$HERE/KotlinXtestDriver.kt" -d /tmp/kdrv 2>&1 | head -20
[ -f /tmp/kdrv/KotlinXtestDriverKt.class ] || { echo "driver compile failed"; exit 1; }
echo "  ok"

echo
echo "=== running the driver (real Kotlin X11Client -> live Xvfb :$D) ==="
java -cp "/tmp/kdrv:$KP1:$ANDROID_JAR:$KOTLINC_HOME/lib/kotlin-stdlib.jar" \
  KotlinXtestDriverKt "$D" "kotlin XTEST works"
RC=$?

echo
echo "=== what the xterm's shell actually received ==="
sed 's/^/   | /' $OUT 2>/dev/null || echo "   NOTHING - input never arrived"

echo
echo "=== verdict ==="
p=0; f=0
ck() { if grep -qF "$1" $OUT 2>/dev/null; then echo "   [PASS] '$1'"; p=$((p+1));
       else echo "   [FAIL] '$1' missing"; f=$((f+1)); fi; }
ck "kotlin XTEST works"
ck "XY"
ck "MiXeD CaSe 123 !@#"
echo "   passed=$p failed=$f (driver exit=$RC)"

pkill -x xterm 2>/dev/null; pkill -x openbox 2>/dev/null
kill $XV 2>/dev/null; rm -f /tmp/.X$D-lock
[ "$f" = 0 ] && [ $RC = 0 ]
