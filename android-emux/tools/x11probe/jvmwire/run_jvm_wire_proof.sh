#!/bin/sh
# run_jvm_wire_proof.sh - compile and run the APK's OWN X11Client.kt against a
# REAL X server on a desktop JVM, and print a pass/fail verdict per wire fact.
#
# Why: tools/wiretest/xgetimage_wiretest.py and tools/x11probe/*_probe.py each
# RE-IMPLEMENT the request they claim to verify. v0.2.71..v0.2.82 shipped a
# grabRegionArgb() whose xGetImageReq was 24 bytes / 6 units where the protocol
# says 20 / 5 - every grab answered BadLength(16), native capture never
# produced a frame on any device, and the Python wiretest passed all the while
# because it packs the request itself. See
# docs/GETIMAGE_BADLENGTH_AND_EVENT_DROPS_v0.2.83.md.
#
# This runner compiles the real class (plus two JVM stand-ins for
# android.net.LocalSocket / LocalSocketAddress, which the APK build gets from
# android.jar and the platform) and exercises it end to end.
#
# Prereqs: JDK 17+, kotlinc 1.9.x, a running X server whose screen is depth 16
# (the app's capture depth). Example:
#
#   Xvfb :5 -screen 0 576x1280x16 -listen tcp -ac -extension MIT-SHM &
#   DISPLAY=:5 sh tools/x11probe/jvmwire/run_jvm_wire_proof.sh
#
# Set EMUX_WIRE_PROOT=1 to run the whole proof a second time against an Xvfb
# that is itself inside proot, which is how the app really meets it on a
# device. The verdicts must be identical; only the timings should differ.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=${EMUX_REPO:-$(cd "$HERE/../../.." && pwd)}
KOTLINC=${KOTLINC:-/opt/kotlinc/bin/kotlinc}
JAVA=${JAVA:-java}
JAVAC=${JAVAC:-javac}
OUT=${OUT:-/tmp/emux-jvmwire}
KSTDLIB=${KSTDLIB:-$(dirname "$(readlink -f "$KOTLINC")")/../lib/kotlin-stdlib.jar}

mkdir -p "$OUT/shim" "$OUT/classes"

echo "== 1/3 javac: JVM stand-ins for android.net.LocalSocket{,Address} =="
find "$HERE/shim" -name '*.java' > "$OUT/shim.list"
$JAVAC -nowarn -encoding UTF-8 -source 17 -target 17 -d "$OUT/shim" @"$OUT/shim.list"
echo "   $(find "$OUT/shim" -name '*.class' | wc -l) shim classes"

echo "== 2/3 kotlinc: the APK's X11Client.kt + the proof =="
# X11Client.kt is compiled from app/src/main verbatim - no copy, no fork. If
# the app's copy stops compiling, this stops too. EMUX_X11CLIENT_SRC exists so
# the proof can be pointed at an older revision, which is how the v0.2.82
# BadLength was demonstrated rather than asserted:
#   git show HEAD~1:.../X11Client.kt > /tmp/old.kt
#   EMUX_X11CLIENT_SRC=/tmp/old.kt sh run_jvm_wire_proof.sh
CLIENT_SRC=${EMUX_X11CLIENT_SRC:-$REPO/app/src/main/java/com/emux/x11/X11Client.kt}
echo "   client under test: $CLIENT_SRC"
$KOTLINC -nowarn -jvm-target 17 -cp "$OUT/shim" \
  "$CLIENT_SRC" \
  "$HERE/JvmWireProof.kt" \
  -d "$OUT/classes"
echo "   $(find "$OUT/classes" -name '*.class' | wc -l) classes"

echo "== 3/3 run against ${DISPLAY:-:5} =="
$JAVA -cp "$OUT/shim:$OUT/classes:$KSTDLIB" com.emux.x11.jvmwire.JvmWireProofKt "$@"
