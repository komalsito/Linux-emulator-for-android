#!/bin/sh
# Compile the app's 23 Kotlin files + the generated R classes + the generated
# ViewBinding into .class files, without Gradle.
#
# Gradle's assembleDebug needs ~2 GB and is OOM-killed on a 1 GB machine, and a
# single kotlinc over all 23 files (11.5k lines) is killed too. So this compiles
# in two passes, exactly like tools/x11probe/compile_all.sh does for type-checking,
# with one difference: the outputs here are REAL classes that go into the dex.
#
# Three phases, because of a dependency cycle:
#   1. javac  the generated R classes        (no dependencies on anything)
#   2. kotlinc the app sources               (against R + a ViewBinding *stub*)
#   3. javac  the real ActivityMainBinding   (against the Kotlin classes from 2)
# Phase 3 has to come last: the binding references com.emux.x11.DesktopView and
# com.emux.terminal.ExtraKeysView, which are Kotlin. The stub from phase 2 is then
# deleted so it cannot leak into the dex - the audit at the end fails the build if
# any class is not traceable to a real source or a generated file.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=${EMUX_REPO:-$(cd "$HERE/../.." && pwd)}
WORK=${WORK:-/tmp/emux-nogradle}
APKB=$WORK/apkb; OUT=$WORK/out
AJ=${ANDROID_JAR:-/opt/asdk/android-34/android.jar}
KOTLINC=${KOTLINC:-/opt/kt/kotlinc/bin/kotlinc}
STUBS=${STUBS:-/tmp/stubs}
HEAP=${HEAP:--J-Xmx700m}
J=$REPO/app/src/main/java/com/emux
LIBS="$AJ:$(ls $APKB/libs/*.jar | tr '\n' ':')"
mkdir -p $OUT
rm -rf $OUT/rclasses $OUT/kp1 $OUT/kp2 $OUT/jclasses $OUT/merged
mkdir -p $OUT/rclasses $OUT/kp1 $OUT/kp2 $OUT/jclasses $OUT/merged

echo "== 1/5 javac: generated R classes =="
find $APKB/genR -name R.java > $OUT/r.list
javac -nowarn -source 17 -target 17 -d $OUT/rclasses @$OUT/r.list 2>&1 | grep -vE '^Note|warning' | head -5
echo "   $(find $OUT/rclasses -name '*.class' | wc -l) classes from $(wc -l < $OUT/r.list) sources"

P1="$J/x11/DesktopService.kt $J/x11/DesktopView.kt $J/x11/X11Client.kt $J/x11/UsbCaptureActivity.kt \
 $J/EmuXLog.kt $J/core/FsLayout.kt $J/core/RootManager.kt $J/core/DistroCatalog.kt \
 $J/terminal/TerminalSession.kt $J/terminal/TerminalService.kt $J/terminal/ExtraKeysView.kt \
 $STUBS/ActivityMainBinding.kt $STUBS/SubsetStubs.kt $STUBS/SubsetStubs2.kt $STUBS/BootstrapManagerStub.kt"
P2="$J/MainActivity.kt $J/SettingsActivity.kt $J/EmuXApplication.kt \
 $J/api/EmuXExecuteService.kt $J/terminal/VtTerminalView.kt \
 $J/core/BootstrapManager.kt $J/core/BootstrapScheduling.kt $J/core/BootstrapWorker.kt \
 $J/core/BootstrapRunner.kt $J/core/BootstrapReporter.kt $J/core/BootstrapDiagnostics.kt \
 $J/core/EmuXBootstrapApp.kt $STUBS/ActivityMainBinding.kt"

echo "== 2/5 kotlinc pass 1 (x11 + terminal + core) =="
timeout 1500 $KOTLINC $HEAP -jvm-target 17 -cp "$LIBS$OUT/rclasses" $P1 -d $OUT/kp1 > $OUT/kc1.log 2>&1
RC=$?; E=$(grep -cE '\.kt:[0-9]+:[0-9]+: error:' $OUT/kc1.log)
echo "   exit=$RC errors=$E classes=$(find $OUT/kp1 -name '*.class'|wc -l) $([ $RC = 137 ] && echo '(OOM-KILLED: raise HEAP or split further)')"
[ "$E" != 0 ] && grep -E '\.kt:[0-9]+:[0-9]+: error:' $OUT/kc1.log | head -10
[ $RC != 0 ] && exit 1
# pass 2 compiles the REAL sources for these stubs, so the stub classes must go or
# Kotlin reports the pairs as redeclarations.
rm -f $OUT/kp1/com/emux/MainActivity*.class $OUT/kp1/com/emux/core/BootstrapManager*.class \
      $OUT/kp1/com/emux/core/BootstrapProgress*.class

echo "== 3/5 kotlinc pass 2 (rest of module) =="
timeout 1500 $KOTLINC $HEAP -jvm-target 17 -cp "$LIBS$OUT/rclasses:$OUT/kp1" $P2 -d $OUT/kp2 > $OUT/kc2.log 2>&1
RC=$?; E=$(grep -cE '\.kt:[0-9]+:[0-9]+: error:' $OUT/kc2.log)
echo "   exit=$RC errors=$E classes=$(find $OUT/kp2 -name '*.class'|wc -l) $([ $RC = 137 ] && echo '(OOM-KILLED)')"
[ "$E" != 0 ] && grep -E '\.kt:[0-9]+:[0-9]+: error:' $OUT/kc2.log | head -10
[ $RC != 0 ] && exit 1
rm -rf $OUT/kp2/com/emux/databinding          # the stub binding; phase 3 replaces it

echo "== 4/5 javac: the real ActivityMainBinding =="
javac -nowarn -source 17 -target 17 -cp "$LIBS$OUT/rclasses:$OUT/kp1:$OUT/kp2" \
  -d $OUT/jclasses $APKB/genbind/com/emux/databinding/ActivityMainBinding.java 2>&1 | grep -vE '^Note|warning' | head
echo "   $(find $OUT/jclasses -name '*.class' | wc -l) class(es)"

echo "== 5/5 merge + stub-leak audit =="
for d in rclasses kp1 kp2 jclasses; do
  (cd $OUT/$d && find . -name '*.class' -print0 | xargs -0 -I{} cp --parents {} $OUT/merged/)
done
echo "   $(find $OUT/merged -name '*.class' | wc -l) classes total"
EMUX_REPO=$REPO python3 "$HERE/audit_classes.py" $OUT/merged && echo "   audit: every class traces to a real source or a generated file"
