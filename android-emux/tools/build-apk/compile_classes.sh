#!/bin/sh
# Compile the app's Kotlin sources + the generated R classes + the generated
# ViewBinding into .class files, without Gradle.
#
# ONE kotlinc pass. This used to be three phases with four hand-written stub files
# under $STUBS (ActivityMainBinding.kt, SubsetStubs.kt, SubsetStubs2.kt,
# BootstrapManagerStub.kt) to break a dependency cycle: pass 1 compiled x11 +
# terminal + core, which reference MainActivity and BootstrapManager, and pass 2
# compiled those. The stubs were never committed, so they lived only in one
# machine's /tmp — a fresh checkout could not run the pipeline at all, and the
# failure mode was silent: empty stub files still "compile", the errors show up as
# unresolved references, and a driver that does not check the exit status packages a
# dex with whole classes missing from it.
#
# The split was only ever needed because a single kotlinc over ~12.6k lines was
# believed to OOM on a 1 GB box. It does not: with -Xmx700m the single pass below
# completes in about a minute. kotlinc also resolves .java sources given on the
# command line without compiling them, so the generated R classes and the generated
# ActivityMainBinding.java can be fed straight in — which is what removes the cycle,
# since the binding references Kotlin views (DesktopView, ExtraKeysView) and the
# Kotlin code references the binding.
#
# javac then emits the .class files for those Java sources (kotlinc resolves them
# but does not compile them), and the two output trees are merged.
#
# There are no stubs any more, so nothing can leak a throwing placeholder body into
# the dex. audit_classes.py still runs, and now verifies a tree that could only have
# come from real sources.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=${EMUX_REPO:-$(cd "$HERE/../.." && pwd)}
WORK=${WORK:-/tmp/emux-nogradle}
APKB=$WORK/apkb; OUT=$WORK/out
AJ=${ANDROID_JAR:-/opt/asdk/android-34/android.jar}
KOTLINC=${KOTLINC:-/opt/kt/kotlinc/bin/kotlinc}
HEAP=${HEAP:--J-Xmx700m}
J=$REPO/app/src/main/java/com/emux
BINDING=$APKB/genbind/com/emux/databinding/ActivityMainBinding.java
LIBS="${LOWLIBS:-$AJ:$(ls $APKB/libs/*.jar | tr '\n' ':')}"
mkdir -p $OUT
rm -rf $OUT/kclasses $OUT/jclasses $OUT/merged
mkdir -p $OUT/kclasses $OUT/jclasses $OUT/merged

find $APKB/genR -name R.java > $OUT/r.list
[ -f "$BINDING" ] || { echo "   missing generated binding: $BINDING"; exit 1; }

if [ -n "${LOWMEM:-}" ]; then
  # v0.2.76 LOWMEM two-pass split for 1 GiB cgroup sandboxes: the single
  # kotlinc pass (kt + 60 R.java + binding) peaks above whatever the platform
  # leaves us and gets SIGKILLed; javac-first keeps each process small. The
  # generated binding imports only android/androidx/R, so javac needs no app
  # classes; kotlinc then resolves R/binding as CLASSES, not sources.
  echo "== 1a/3 javac FIRST (LOWMEM two-pass): R classes (binding needs app classes, comes later) =="
  javac -nowarn -source 17 -target 17 -cp "$LIBS" -d $OUT/jclasses \
    @$OUT/r.list 2>&1 | grep -vE '^Note|warning' | head -5
  echo "== 1b/3 kotlinc: app sources + binding source, R as classes on the classpath =="
  timeout 1500 $KOTLINC $HEAP -jvm-target 17 -cp "$LIBS:$OUT/jclasses" \
    $(find $J -name '*.kt') "$BINDING" -d $OUT/kclasses > $OUT/kc.log 2>&1
  RC=$?
else
echo "== 1/3 kotlinc: app sources + generated R + generated ViewBinding (single pass) =="
# shellcheck disable=SC2046  # word-splitting of the file lists is intended; no path has spaces
timeout 1500 $KOTLINC $HEAP -jvm-target 17 -cp "$LIBS" \
  $(find $J -name '*.kt') $(cat $OUT/r.list) "$BINDING" \
  -d $OUT/kclasses > $OUT/kc.log 2>&1
RC=$?
fi
# Count only real diagnostics. A bare 'error:' also matches source text echoed in a
# warning's context lines - e.g. the literal "detail error:" inside a Kotlin string -
# which made a clean build print errors=2.
E=$(grep -cE '\.(kt|java):[0-9]+:[0-9]+: error:' $OUT/kc.log)
echo "   exit=$RC errors=$E sources=$(find $J -name '*.kt' | wc -l)kt+$(wc -l < $OUT/r.list)R.java classes=$(find $OUT/kclasses -name '*.class' | wc -l) $([ $RC = 137 ] && echo '(OOM-KILLED: lower HEAP or restore the two-pass split)')"
[ "$E" != 0 ] && grep -E 'error:' $OUT/kc.log | head -15
# A non-zero exit with zero "error:" lines is still a failure (OOM, crash, timeout).
[ $RC != 0 ] && exit 1

if [ -z "${LOWMEM:-}" ]; then
echo "== 2/3 javac: the R classes + the real ActivityMainBinding =="
# kotlinc resolved these but emitted nothing for them; javac produces the .class
# files that actually go into the dex.
javac -nowarn -source 17 -target 17 -cp "$LIBS:$OUT/kclasses" -d $OUT/jclasses \
  @$OUT/r.list "$BINDING" 2>&1 | grep -vE '^Note|warning' | head -10
fi
if [ -n "${LOWMEM:-}" ]; then
  javac -nowarn -source 17 -target 17 -cp "$LIBS:$OUT/kclasses" -d $OUT/jclasses \
    "$BINDING" 2>&1 | grep -vE '^Note|warning' | head -5
fi
echo "   $(find $OUT/jclasses -name '*.class' | wc -l) classes from R sources + 1 binding"

echo "== 3/3 merge + audit =="
for d in kclasses jclasses; do
  (cd $OUT/$d && find . -name '*.class' -print0 | xargs -0 -I{} cp --parents {} $OUT/merged/)
done
echo "   $(find $OUT/merged -name '*.class' | wc -l) classes total"
EMUX_REPO=$REPO python3 "$HERE/audit_classes.py" $OUT/merged && echo "   audit: every class traces to a real source or a generated file"
