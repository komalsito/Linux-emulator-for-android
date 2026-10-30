#!/bin/sh
# no-gradle-build.sh - build a signed emux-debug.apk with NO Gradle at all.
#
# Why this exists next to manual-build.sh: manual-build.sh is a hybrid. It runs a
# small-heap Gradle warm-up first and consumes three Gradle outputs - R.jar (the
# per-library R classes), ActivityMainBinding.java, and resources-debug.ap_ (the
# merged manifest + linked resources). Those three are exactly the parts that need
# Gradle's resource merger and R generator. This script replaces all three:
#
#   R.jar                  -> gen_r.py + gen_app_r.py   (validated field-for-field
#                               against a reference APK's own R classes: 179/179)
#   ActivityMainBinding    -> gen_binding.py            (generated from the layout)
#   resources-debug.ap_    -> mk_manifest.py + aapt2 compile/link
#
# Everything after that (kotlinc, javac, d8, zipalign, apksigner) is the same work
# manual-build.sh does, and fits in 1 GB.
#
# Prereqs: JDK 17, kotlinc, android.jar (platform 34), build-tools 34 (aapt2, d8,
# zipalign, apksigner), an r8 jar >= 8.5 (see the d8 note in README.md), python3.
#
# Env: WORK (scratch, default /tmp/emux-nogradle), ANDROID_JAR, BUILD_TOOLS,
#      R8_JAR, KOTLINC, STUBS, EMUX_REPO, SKIP_FETCH=1 to reuse $WORK.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
REPO=${EMUX_REPO:-$(cd "$HERE/../.." && pwd)}
WORK=${WORK:-/tmp/emux-nogradle}
APKB=$WORK/apkb; OUT=$WORK/out
export WORK EMUX_REPO=$REPO
ANDROID_JAR=${ANDROID_JAR:-/opt/asdk/android-34/android.jar}
BUILD_TOOLS=${BUILD_TOOLS:-/opt/bt/android-14}
R8_JAR=${R8_JAR:-/opt/r8-8.5.35.jar}
STUBS=${STUBS:-/tmp/stubs}
MANIFEST_SRC=${MANIFEST_SRC:-$HERE/merged-manifest.xml}
KS=$REPO/debug-keystore/emux-debug.jks
OUTAPK=${1:-$(dirname "$REPO")/emux-debug.apk}
mkdir -p "$APKB/flat" "$OUT/dex"

VC=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' "$REPO/app/build.gradle.kts" | head -1)
VN=$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' "$REPO/app/build.gradle.kts" | head -1)
echo "== EmuX no-Gradle build: v$VN (vc$VC) =="

if [ "${SKIP_FETCH:-0}" != 1 ]; then
  echo "== 1/9 fetch deps.lock =="; python3 "$HERE/fetch_deps.py" | tail -2
else echo "== 1/9 fetch skipped (SKIP_FETCH=1) =="; fi

echo "== 2/9 merged manifest =="
# The checked-in merged-manifest.xml carries the LIBRARY components (WorkManager's
# services/receivers, the androidx.startup provider, profileinstaller, room, window's
# uses-library) that only Gradle's merger knows about. Its version attributes are
# overwritten from app/build.gradle.kts, so a version bump needs no re-derivation.
GRADLE_KTS=$REPO/app/build.gradle.kts python3 - "$MANIFEST_SRC" "$VC" "$VN" > "$WORK/manifest.xml" <<'PY'
import re,sys
s=open(sys.argv[1]).read()
s=re.sub(r'android:versionCode="\d+"','android:versionCode="%s"'%sys.argv[2],s,count=1)
s=re.sub(r'android:versionName="[^"]+"','android:versionName="%s"'%sys.argv[3],s,count=1)
sys.stdout.write(s)
PY
grep -o 'android:version[A-Za-z]*="[^"]*"' "$WORK/manifest.xml" | sed 's/^/   /'

echo "== 3/9 aapt2 compile (app res + every library res) =="
$BUILD_TOOLS/aapt2 compile --dir "$REPO/app/src/main/res" -o "$APKB/flat/APP.zip"
for d in "$APKB"/res/*/; do
  n=$(basename "$d"); [ -f "$APKB/flat/$n.zip" ] || $BUILD_TOOLS/aapt2 compile --dir "$d" -o "$APKB/flat/$n.zip" >/dev/null
done
echo "   $(ls "$APKB"/flat/*.zip | wc -l) flat archives"

echo "== 4/9 aapt2 link =="
LIBFLATS=$(ls "$APKB"/flat/*.zip | grep -v '/APP.zip$' | tr '\n' ' ')
# --version-code/--version-name are NOT passed: aapt2 does not override values that
# are already in the manifest, so passing them silently does nothing. Step 2 sets them.
$BUILD_TOOLS/aapt2 link -I "$ANDROID_JAR" --manifest "$WORK/manifest.xml" \
  --java "$APKB/gen" --output-text-symbols "$APKB/R.txt" \
  -A "$REPO/app/src/main/assets" --min-sdk-version 24 --target-sdk-version 28 \
  --auto-add-overlay -o "$APKB/base.apk" $LIBFLATS "$APKB/flat/APP.zip"
echo "   base.apk: $(stat -c%s "$APKB/base.apk") bytes, $(wc -l < "$APKB/R.txt") symbols"

echo "== 5/9 generate R classes (per-library, non-transitive) =="
python3 "$HERE/gen_r.py" | tail -3
python3 "$HERE/gen_app_r.py" "$REPO"
python3 "$HERE/gen_binding.py" "$REPO" "$REPO/app/src/main/res/layout/activity_main.xml" \
  "$APKB/genbind/com/emux/databinding" ActivityMainBinding

echo "== 6/9 compile (javac R -> kotlinc app -> javac binding) =="
ANDROID_JAR=$ANDROID_JAR STUBS=$STUBS KOTLINC=${KOTLINC:-/opt/kt/kotlinc/bin/kotlinc} \
  sh "$HERE/compile_classes.sh" | grep -E '^==|classes|error|audit'

echo "== 7/9 d8 =="
( cd "$OUT/merged" && python3 -c "
import zipfile,os
z=zipfile.ZipFile('$OUT/app-classes.jar','w',zipfile.ZIP_DEFLATED)
for r,_,fs in os.walk('.'):
    for f in fs: p=os.path.join(r,f); z.write(p,os.path.relpath(p,'.'))
z.close()" )
# build-tools' own d8 (8.2.2) NPEs on appcompat 1.7.0's ActionBarDrawerToggle$1 - a
# class with no SourceFile attribute. AGP never hits this because it uses the D8
# bundled in the plugin, not build-tools'. Hence R8_JAR >= 8.5 (reference APK: AGP 8.5.2).
java -Xmx700m -cp "$R8_JAR" com.android.tools.r8.D8 --lib "$ANDROID_JAR" --min-api 24 \
  --output "$OUT/dex" $(ls "$APKB"/libs/*.jar | tr '\n' ' ') "$OUT/app-classes.jar" 2>&1 \
  | grep -viE 'invalid locals|Attempt to define local|outdated Java toolchain|^\s*$|^Info' | head -5
echo "   $(ls "$OUT"/dex/*.dex | wc -l) dex files, $(du -sb "$OUT/dex" | cut -f1) bytes"

echo "== 8/9 package + align + sign (committed keystore) =="
python3 "$HERE/package_apk.py" --base "$APKB/base.apk" --dex-dir "$OUT/dex" \
  --libs "$APKB/libs" --out "$OUT/unsigned.apk"
$BUILD_TOOLS/zipalign -p -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"
$BUILD_TOOLS/zipalign -c -p 4 "$OUT/aligned.apk"
$BUILD_TOOLS/apksigner sign --ks "$KS" --ks-key-alias emux \
  --ks-pass pass:emuxdebug --key-pass pass:emuxdebug --out "$OUT/emux-debug.apk" "$OUT/aligned.apk"
$BUILD_TOOLS/apksigner verify --print-certs "$OUT/emux-debug.apk" | head -2 | sed 's/^/   /'

echo "== 9/9 verify against the working tree =="
cp "$OUT/emux-debug.apk" "$OUTAPK"
( cd "$REPO/tools" && ANDROID_HOME=$(dirname "$BUILD_TOOLS") sh verify_apk.sh "$OUTAPK" | tail -4 )
echo "wrote $OUTAPK"
