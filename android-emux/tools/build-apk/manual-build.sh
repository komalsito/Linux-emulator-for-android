#!/bin/bash
# manual-build.sh - build emux-debug.apk on a small (1 GB RAM) box.
#
# WHY: a full `./gradlew assembleDebug` OOMs at :app:compileDebugKotlin on
# 1 GB machines (Kotlin daemon + gradle JVM). This is the pipeline that
# actually produced the shipped APKs (see ACTION_PLAN §6 for the prose
# recipe and the pitfalls checklist; §24 for the v0.1.78 round).
#
# Prereqs (Debian bookworm):
#   apt-get install openjdk-17-jdk-headless wget unzip zip xz-utils
#   # cmdline-tools -> /opt/android-sdk, then:
#   sdkmanager --sdk_root=/opt/android-sdk "platforms;android-34" "build-tools;34.0.0"
#   # kotlin compiler 1.9.24 -> /opt/bt/kotlinc, r8 8.5.27 -> /opt/bt/r8.jar
#
# Steps 1-2 (gradle warm-up) must run BEFORE this script: it prints the
# compile/runtime classpaths and produces the resource/binding artifacts
# this script consumes:
#   JAVA_HOME=... GRADLE_USER_HOME=... GRADLE_OPTS="-Xmx96m" ./gradlew \
#     -I cp.init.gradle :app:printCp :app:printRuntimeCp \
#     :app:processDebugResources :app:dataBindingGenBaseClassesDebug \
#     --no-daemon --max-workers=1 \
#     -Dorg.gradle.jvmargs="-Xmx512m -XX:MaxMetaspaceSize=256m"   # log to a file!
#
# Usage: LOG=/path/to/gradle-warm.log sh tools/build-apk/manual-build.sh [outfile]
set -e
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}
export PATH=$JAVA_HOME/bin:$PATH
A=$(cd "$(dirname "$0")/../.." && pwd)
OUT=${2:-$A/../emux-debug.apk}
B=${BUILD_TMP:-/tmp/emux-apkbuild}
LOG=${LOG:-/tmp/gradle-warm.log}
BT=/opt/android-sdk/build-tools/34.0.0
rm -rf "$B"; mkdir -p "$B/aarx" "$B/dex" "$B/in"

# stage the gradle outputs we consume (copy FIRST: some sandbox filesystems
# are flaky about long-lived reads out of app/build/)
cp "$A/app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug/processDebugResources/R.jar" "$B/in/R.jar"
cp "$A/app/build/generated/data_binding_base_class_source_out/debug/out/com/emux/databinding/ActivityMainBinding.java" "$B/in/ActivityMainBinding.java"
cp "$A/app/build/intermediates/processed_res/debug/processDebugResources/out/resources-debug.ap_" "$B/in/resources-debug.ap_"
cp "$A/debug-keystore/emux-debug.jks" "$B/in/emux-debug.jks"
[ -f "$B/in/R.jar" ] || { echo "gradle artifacts missing - run the warm-up first"; exit 1; }

CP=$(grep -a '^CP_START:' "$LOG" | head -1 | sed 's/^CP_START://; s/:CP_END$//')
RCP=$(grep -a '^RCP_START:' "$LOG" | head -1 | sed 's/^RCP_START://; s/:RCP_END$//')
[ -n "$CP" ] && [ -n "$RCP" ] || { echo "classpath markers not in $LOG"; exit 1; }
tr ':' '\n' <<< "$CP" > "$B/cp.txt"
tr ':' '\n' <<< "$RCP" > "$B/rcp.txt"

# 4) AARs are RAW on both classpaths - extract classes.jar + libs/*.jar
for j in $(cat "$B/cp.txt" "$B/rcp.txt" | sort -u); do
  case "$j" in *.aar)
    n=$(basename "$j" .aar); d="$B/aarx/$n"
    [ -f "$d/.done" ] || { mkdir -p "$d"; (cd "$d" && unzip -q "$j") && touch "$d/.done"; }
    ;;
  esac
done
mkjars() { # $1 list -> jar list (some AARs ship NO classes.jar - guard it)
  for j in $(cat "$1" | sort -u); do
    case "$j" in
      *.aar) n=$(basename "$j" .aar)
             [ -f "$B/aarx/$n/classes.jar" ] && echo "$B/aarx/$n/classes.jar"
             { find "$B/aarx/$n/libs" -name '*.jar' 2>/dev/null || true; } ;;
      *.jar) echo "$j" ;;
    esac
  done
}
mkjars "$B/cp.txt" > "$B/cp-jars.txt"
mkjars "$B/rcp.txt" > "$B/rcp-jars.txt"

ANDROID_JAR=/opt/android-sdk/platforms/android-34/android.jar
R_JAR=$B/in/R.jar; BINDING=$B/in/ActivityMainBinding.java
KCPCP=$( { cat "$B/cp-jars.txt"; echo "$ANDROID_JAR"; echo "$R_JAR"; } | paste -sd:)

# 5) Kotlin (fits 1 GB: kotlinc OUTSIDE the gradle JVM)
cd "$A"
find app/src/main/java -name '*.kt' | sort > "$B/kt.txt"
JAVA_OPTS="-Xmx620m -XX:MaxMetaspaceSize=224m" /opt/bt/kotlinc/bin/kotlinc \
  -jvm-target 17 -cp "$KCPCP" -d "$B/kclasses" $(cat "$B/kt.txt") "$BINDING" \
  > "$B/kotlinc.log" 2>&1 || { echo KOTLINC FAIL; grep -m 12 'error:' "$B/kotlinc.log"; exit 1; }
KCOUNT=$(find "$B/kclasses" -name '*.class' | wc -l)
[ "$KCOUNT" -gt 100 ] || { echo "suspiciously few classes: $KCOUNT"; exit 1; }

# 6) javac the generated binding against kclasses (COLON classpath!)
mkdir -p "$B/jclasses"
javac -nowarn -cp "$KCPCP:$B/kclasses" -d "$B/jclasses" "$BINDING" > "$B/javac.log" 2>&1 \
  || { echo JAVAC FAIL; tail -20 "$B/javac.log"; exit 1; }
( cd "$B/kclasses" && jar cf "$B/app-kclasses.jar" . )
( cd "$B/jclasses" && jar cf "$B/app-jclasses.jar" . )

# 7) D8 over app classes + R + the RUNTIME classpath jars
java -Xmx512m -cp /opt/bt/r8.jar com.android.tools.r8.D8 --min-api 24 \
  --lib "$ANDROID_JAR" --output "$B/dex" \
  "$B/app-kclasses.jar" "$B/app-jclasses.jar" "$R_JAR" $(cat "$B/rcp-jars.txt") \
  > "$B/d8.log" 2>&1 || { echo D8 FAIL; tail -30 "$B/d8.log"; exit 1; }

# 8) package: processed res + dex + assets from the tree + merged java res
#    (kotlin builtins / META-INF services / *.version). If the dependency
#    set changed since the last shipped APK, regenerate the java-res set
#    instead of copying it from OLDAPK.
OLDAPK=${OLDAPK:-$A/../emux-debug.apk}
python3 - "$B" "$A" "$OLDAPK" <<'PY'
import sys, os, zipfile
B, A, OLD = sys.argv[1], sys.argv[2], sys.argv[3]
zold = zipfile.ZipFile(OLD)
zout = zipfile.ZipFile(os.path.join(B, 'app-unsigned.apk'), 'w')
for i in zipfile.ZipFile(os.path.join(B, 'in/resources-debug.ap_')).infolist():
    ct = zipfile.ZIP_STORED if i.filename == 'resources.arsc' else i.compress_type
    zout.writestr(zipfile.ZipInfo(i.filename, date_time=i.date_time),
                  zipfile.ZipFile(os.path.join(B, 'in/resources-debug.ap_')).read(i.filename), compress_type=ct)
for n in sorted(os.listdir(os.path.join(B, 'dex'))):
    if n.endswith('.dex'):
        zout.write(os.path.join(B, 'dex', n), n, compress_type=zipfile.ZIP_DEFLATED)
assets = os.path.join(A, 'app/src/main/assets')
for root, _, files in os.walk(assets):
    for f in sorted(files):
        p = os.path.join(root, f)
        zout.write(p, 'assets/' + os.path.relpath(p, assets).replace(os.sep, '/'),
                   compress_type=zipfile.ZIP_DEFLATED)
for i in zold.infolist():
    n = i.filename
    if n.startswith('kotlin/') or n == 'DebugProbesKt.bin' or n.startswith('META-INF/services/') \
       or (n.startswith('META-INF/') and n.endswith('.version')):
        zout.writestr(zipfile.ZipInfo(n, date_time=i.date_time), zold.read(n), compress_type=i.compress_type)
zout.close()
print('packaged', os.path.getsize(os.path.join(B, 'app-unsigned.apk')), 'bytes')
PY

# 9) align + sign with the COMMITTED keystore, then verify
$BT/zipalign -f -p 4 "$B/app-unsigned.apk" "$B/app-aligned.apk"
$BT/apksigner sign --ks "$B/in/emux-debug.jks" --ks-pass pass:emuxdebug \
  --ks-key-alias emux --key-pass pass:emuxdebug --out "$B/emux-debug.apk" "$B/app-aligned.apk"
$BT/apksigner verify "$B/emux-debug.apk"
$BT/aapt2 dump badging "$B/emux-debug.apk" 2>/dev/null | grep -E '^package:' || true
cp "$B/emux-debug.apk" "$OUT"
echo "wrote $OUT"
