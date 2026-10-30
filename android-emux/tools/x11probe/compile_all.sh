#!/bin/sh
# Full-module Kotlin type-check WITHOUT Gradle.
#
# Why this exists: a Gradle `assembleDebug` needs ~2 GB. On a 1 GB machine the
# daemon is OOM-killed mid-build ("DaemonDisappearedException") and a single
# kotlinc over all 23 files (11.5k lines) is killed too (exit 137). Chunking
# works, because Kotlin can compile against previously-produced .class files.
#
# This is a genuine TYPE-CHECK: it runs against the real API 34 android.jar and
# the real AndroidX jars, not approximations of them. It is NOT a resource /
# dex / package build - it cannot produce an installable APK.
#
# Setup (once):
#   sh fetch_libs.py                       -> /opt/libs/*.jar
#   python3 tools/x11probe/gen_stubs.py    -> /tmp/stubs/{R,ActivityMainBinding}.kt
#   (run gen_stubs.py from the android-emux directory)
#
# Overrides: ANDROID_JAR, LIBS_DIR, KOTLINC, HEAP.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
PROJ=$(cd "$HERE/../.." && pwd)                 # android-emux/
J=$PROJ/app/src/main/java/com/emux

ANDROID_JAR=${ANDROID_JAR:-/opt/asdk/android-34/android.jar}
LIBS_DIR=${LIBS_DIR:-/opt/libs}
KOTLINC=${KOTLINC:-$(command -v kotlinc 2>/dev/null || echo /opt/kt/kotlinc/bin/kotlinc)}
HEAP=${HEAP:--J-Xmx760m}
STUBS=${STUBS:-/tmp/stubs}

[ -f "$ANDROID_JAR" ] || { echo "missing android.jar: $ANDROID_JAR (set ANDROID_JAR)"; exit 2; }
[ -d "$LIBS_DIR" ]    || { echo "missing libs dir: $LIBS_DIR (run fetch_libs.py)"; exit 2; }
for s in R.kt ActivityMainBinding.kt SubsetStubs.kt SubsetStubs2.kt BootstrapManagerStub.kt; do
  [ -f "$STUBS/$s" ] || { echo "missing stub $STUBS/$s (run gen_stubs.py; see README.md)"; exit 2; }
done

LIBS="$ANDROID_JAR:$(ls $LIBS_DIR/*.jar | tr '\n' ':')"

# Pass 1: the desktop/X11 subsystem and everything it directly needs.
# MainActivity / BootstrapManager / BootstrapProgress are STUBBED here.
P1="$J/x11/DesktopService.kt $J/x11/DesktopView.kt $J/x11/X11Client.kt $J/x11/UsbCaptureActivity.kt \
 $J/EmuXLog.kt $J/core/FsLayout.kt $J/core/RootManager.kt $J/core/DistroCatalog.kt \
 $J/terminal/TerminalSession.kt $J/terminal/TerminalService.kt $J/terminal/ExtraKeysView.kt \
 $STUBS/R.kt $STUBS/ActivityMainBinding.kt $STUBS/SubsetStubs.kt \
 $STUBS/SubsetStubs2.kt $STUBS/BootstrapManagerStub.kt"

# Pass 2: the REAL sources for pass 1's stubs, plus the rest of the module.
P2="$J/MainActivity.kt $J/SettingsActivity.kt $J/EmuXApplication.kt \
 $J/api/EmuXExecuteService.kt $J/terminal/VtTerminalView.kt \
 $J/core/BootstrapManager.kt $J/core/BootstrapScheduling.kt $J/core/BootstrapWorker.kt \
 $J/core/BootstrapRunner.kt $J/core/BootstrapReporter.kt $J/core/BootstrapDiagnostics.kt \
 $J/core/EmuXBootstrapApp.kt $STUBS/R.kt $STUBS/ActivityMainBinding.kt"

run_pass() {  # $1=label $2=outdir $3=extra-classpath, rest=sources
  L=$1; O=$2; X=$3; shift 3
  rm -rf "$O"; mkdir -p "$O"
  timeout 1500 "$KOTLINC" $HEAP -jvm-target 17 -cp "$LIBS$X" "$@" -d "$O" > "/tmp/kc_$L.log" 2>&1
  RC=$?
  E=$(grep -cE '\.kt:[0-9]+:[0-9]+: error:' "/tmp/kc_$L.log")
  C=$(find "$O" -name '*.class' | wc -l)
  echo "pass $L: exit=$RC  errors=$E  classes=$C   $([ "$RC" = 137 ] && echo '(OOM-KILLED: lower the file set or raise HEAP)')"
  [ "$E" != 0 ] && grep -E '\.kt:[0-9]+:[0-9]+: error:' "/tmp/kc_$L.log" | head -20
  return $RC
}

echo "===== PASS 1 (x11 + terminal + core) ====="
run_pass p1 /tmp/kp1 /tmp/kout $P1 || { echo "PASS 1 FAILED"; exit 1; }

# Pass 2 compiles real sources for pass 1's stubs, so those .class files must go
# or Kotlin reports the pairs as redeclarations.
rm -f /tmp/kp1/com/emux/MainActivity*.class
rm -f /tmp/kp1/com/emux/core/BootstrapManager*.class
rm -f /tmp/kp1/com/emux/core/BootstrapProgress*.class

echo "===== PASS 2 (rest of module) ====="
run_pass p2 /tmp/kp2 /tmp/kout:/tmp/kp1 $P2 || { echo "PASS 2 FAILED"; exit 1; }

N1=$(find /tmp/kp1 -name '*.class' | wc -l); N2=$(find /tmp/kp2 -name '*.class' | wc -l)
echo
echo "===== RESULT ====="
echo "  0 errors across $(( $(find $J -name '*.kt' | wc -l) )) Kotlin files, $((N1 + N2)) classes"
