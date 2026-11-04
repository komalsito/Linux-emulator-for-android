#!/bin/sh
# verify_apk.sh - prove an APK was built from THIS source tree before installing it.
#
# Why this exists: the repo commits a prebuilt `emux-debug.apk` at the root, and
# it goes stale silently - it was still v0.2.58 for two releases while the source
# moved to v0.2.61, so `adb install -r emux-debug.apk` looked like it worked and
# installed none of the fixes. A stale APK is worse than no APK, because the
# field report that comes back ("still black") describes code that was never on
# the device.
#
# Every check compares the APK against the working tree, so it also catches a
# build made from an uncommitted/edited tree. Read-only: it never modifies the
# APK or the repo.
#
# Usage:
#   sh tools/verify_apk.sh                     # checks ../../emux-debug.apk
#   sh tools/verify_apk.sh path/to/app.apk
#   ANDROID_HOME=/path/to/sdk sh tools/verify_apk.sh
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
PROJ=$(cd "$HERE/.." && pwd)             # android-emux/  (this script is in tools/, not tools/x11probe/)
REPO=$(cd "$PROJ/.." && pwd)             # repo root
APK=${1:-$REPO/emux-debug.apk}
PASS=0; FAIL=0; SKIP=0
export LANG=C.UTF-8 LC_ALL=C.UTF-8

# ---- locate build-tools (aapt2/apksigner) ---------------------------------
BT=""
for c in "${ANDROID_HOME:-/nonexistent}/build-tools" "${ANDROID_SDK_ROOT:-/nonexistent}/build-tools" \
         "$HOME/Android/Sdk/build-tools" "$HOME/Library/Android/sdk/build-tools" \
         /opt/bt /usr/lib/android-sdk/build-tools; do
  [ -d "$c" ] || continue
  # newest version dir that has both tools
  for d in $(ls -1 "$c" 2>/dev/null | sort -Vr); do
    if [ -x "$c/$d/aapt2" ]; then BT="$c/$d"; break; fi
  done
  [ -n "$BT" ] && break
done
if [ -z "$BT" ] && [ -d /opt/bt ]; then
  for d in /opt/bt/*/; do [ -x "$d/aapt2" ] && { BT=${d%/}; break; }; done
fi
# local.properties may name the SDK
if [ -z "$BT" ] && [ -f "$PROJ/local.properties" ]; then
  SDK=$(sed -n 's/^sdk\.dir=//p' "$PROJ/local.properties" | head -1)
  [ -n "$SDK" ] && for d in $(ls -1 "$SDK/build-tools" 2>/dev/null | sort -Vr); do
    [ -x "$SDK/build-tools/$d/aapt2" ] && { BT="$SDK/build-tools/$d"; break; }
  done
fi

ck() {  # $1=desc $2=1|0 $3=detail
  if [ "$2" = 1 ]; then PASS=$((PASS+1)); printf '  PASS  %s\n' "$1"
  else FAIL=$((FAIL+1)); printf '  FAIL  %s\n          %s\n' "$1" "$3"; fi
}
sk() { SKIP=$((SKIP+1)); printf '  SKIP  %s   (%s)\n' "$1" "$2"; }

echo "=== verify_apk.sh ==="
echo "  apk:    $APK"
echo "  source: $PROJ"
if [ ! -f "$APK" ]; then
  echo; echo "  FAIL  no APK at that path"
  echo; echo "passed=0 failed=1 skipped=0"; exit 1
fi
echo "  size:   $(wc -c < "$APK") bytes"
echo "  build-tools: ${BT:-NOT FOUND (badging/signature checks will be skipped)}"

# ---- what the source tree says --------------------------------------------
SRC_VC=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' "$PROJ/app/build.gradle.kts" | head -1)
SRC_VN=$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' "$PROJ/app/build.gradle.kts" | head -1)
SRC_MIN=$(sed -n 's/.*minSdk = \([0-9]*\).*/\1/p' "$PROJ/app/build.gradle.kts" | head -1)
SRC_TGT=$(sed -n 's/.*targetSdk = \([0-9]*\).*/\1/p' "$PROJ/app/build.gradle.kts" | head -1)
SRC_ENV=$(sed -n 's/.*ENV_VERSION = \([0-9]*\).*/\1/p' "$PROJ/app/src/main/java/com/emux/core/FsLayout.kt" | head -1)
SRC_RICE=$(sed -n 's/.*EMUX_RICE_STAMP="\([^"]*\)".*/\1/p' "$PROJ/app/src/main/assets/bin/emux-x11" | head -1)
SRC_AS=$(sed -n 's/.*EMUX_AUTOSTART_STAMP="\([^"]*\)".*/\1/p' "$PROJ/app/src/main/assets/bin/emux-x11" | head -1)
echo
echo "  source says: versionCode=$SRC_VC versionName=$SRC_VN minSdk=$SRC_MIN targetSdk=$SRC_TGT"
echo "               ENV_VERSION=$SRC_ENV rice='$SRC_RICE' autostart='$SRC_AS'"

# ---- 1. identity / SDK levels ---------------------------------------------
echo
echo "--- identity"
if [ -n "$BT" ]; then
  BADGING=$("$BT/aapt2" dump badging "$APK" 2>/dev/null)
  APK_PKG=$(echo "$BADGING" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1)
  APK_VC=$(echo "$BADGING"  | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" | head -1)
  APK_VN=$(echo "$BADGING"  | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)
  APK_MIN=$(echo "$BADGING" | sed -n "s/^sdkVersion:'\([^']*\)'.*/\1/p" | head -1)
  APK_TGT=$(echo "$BADGING" | sed -n "s/^targetSdkVersion:'\([^']*\)'.*/\1/p" | head -1)
  APK_LAUNCH=$(echo "$BADGING" | sed -n "s/^launchable-activity: name='\([^']*\)'.*/\1/p" | head -1)
  ck "package is com.emux" "$([ "$APK_PKG" = com.emux ] && echo 1 || echo 0)" "found '$APK_PKG'"
  ck "versionCode matches source ($SRC_VC)" "$([ "$APK_VC" = "$SRC_VC" ] && echo 1 || echo 0)" \
     "APK has versionCode=$APK_VC - THIS APK IS STALE, rebuild it"
  ck "versionName matches source ($SRC_VN)" "$([ "$APK_VN" = "$SRC_VN" ] && echo 1 || echo 0)" \
     "APK has versionName=$APK_VN"
  ck "minSdkVersion matches source ($SRC_MIN)" "$([ "$APK_MIN" = "$SRC_MIN" ] && echo 1 || echo 0)" "found '$APK_MIN'"
  ck "targetSdkVersion matches source ($SRC_TGT)" "$([ "$APK_TGT" = "$SRC_TGT" ] && echo 1 || echo 0)" "found '$APK_TGT'"
  ck "launchable activity is com.emux.MainActivity" \
     "$([ "$APK_LAUNCH" = com.emux.MainActivity ] && echo 1 || echo 0)" "found '$APK_LAUNCH'"
else
  sk "badging checks" "no build-tools with aapt2"
fi

# ---- 2. signature == the committed keystore -------------------------------
echo
echo "--- signature (must match debug-keystore/emux-debug.jks or installs fail"
echo "    with INSTALL_FAILED_UPDATE_INCOMPATIBLE and wipe the rootfs)"
KS="$PROJ/debug-keystore/emux-debug.jks"
if [ -n "$BT" ] && [ -f "$KS" ] && command -v keytool >/dev/null 2>&1; then
  APK_CERT=$("$BT/apksigner" verify --print-certs "$APK" 2>/dev/null | \
             sed -n 's/.*SHA-256 digest: \([0-9a-f:]*\).*/\1/p' | head -1)
  KS_CERT=$(keytool -list -v -keystore "$KS" -storepass emuxdebug 2>/dev/null | \
            sed -n 's/.*SHA256: \([0-9A-F:]*\).*/\1/p' | head -1)
  # apksigner prints the digest bare, keytool prints it colon-separated - normalise
  # both before comparing (a naive compare fails on two identical digests).
  APK_CERT_N=$(echo "$APK_CERT" | tr -d ':' | tr 'a-f' 'A-F')
  KS_CERT_N=$(echo "$KS_CERT" | tr -d ':' | tr 'a-f' 'A-F')
  if [ -n "$APK_CERT_N" ] && [ -n "$KS_CERT_N" ]; then
    ck "signed with the committed keystore" \
       "$([ "$APK_CERT_N" = "$KS_CERT_N" ] && echo 1 || echo 0)" \
       "apk=$APK_CERT keystore=$KS_CERT"
  else
    sk "signature comparison" "could not read one of the certificates"
  fi
  "$BT/apksigner" verify "$APK" >/dev/null 2>&1
  ck "apksigner accepts the APK" "$([ $? = 0 ] && echo 1 || echo 0)" "signature invalid/missing"
else
  sk "signature checks" "needs build-tools, keytool and $KS"
fi

# ---- 3+4. guest assets and dex contents, via python3 ----------------------
# These two sections used to shell out to `unzip -p` and `strings`. Neither is
# guaranteed on a lean machine, and when they were absent the asset checks silently
# SKIPped while the dex checks reported three FALSE FAILS ("the APK predates
# v0.2.60") for an APK that was in fact current. A verifier that cannot run must not
# look like a verifier that found a stale APK. python3 is already a hard dependency
# of the build pipeline, so both sections now use zipfile and nothing else.
#
# The guest script is where every desktop fix lives, and it is only re-seeded onto a
# device when ENV_VERSION rises - so a stale assets/bin/emux-x11 means the fix cannot
# reach an existing install even if the dex is new.
echo
echo "--- guest assets (where the desktop fixes actually live)"
TMPD=$(mktemp -d /tmp/vapkXXXX)
python3 - "$APK" "$PROJ/app/src/main/assets" "$SRC_RICE" "$SRC_AS" > "$TMPD/a" <<'PYEOF'
import os,re,sys,zipfile
apk,aroot,rice,autostart=sys.argv[1:5]
z=zipfile.ZipFile(apk); names=set(z.namelist())
srcs=[]
for r,_,fs in os.walk(aroot):
    for f in fs: srcs.append(os.path.relpath(os.path.join(r,f),aroot))
def out(ok,desc,detail=""): print(("1|" if ok else "0|")+desc+"|"+detail)
for rel in sorted(srcs):
    n='assets/'+rel
    if n not in names: out(False,f"assets/{rel} present in the APK","missing"); continue
    same = z.read(n)==open(os.path.join(aroot,rel),'rb').read()
    out(same,f"assets/{rel} identical to source",
        "" if same else "differs - the APK was built from a different tree (or is stale)")
g=z.read('assets/bin/emux-x11').decode('utf-8','replace') if 'assets/bin/emux-x11' in names else ''
def found(pat):
    m=re.search(pat,g); return m.group(0) if m else 'none'
out(rice in g, f"APK's emux-x11 carries the current rice stamp ({rice})", found(r'Emux rice v[0-9.]+'))
out(autostart in g, f"APK's emux-x11 carries the current autostart stamp ({autostart})", found(r'EmuX desktop v[0-9.]+'))
out('desktop-items-0.conf' in g, "APK's emux-x11 has the pcmanfm desktop-items repair",
    "no desktop-items-0.conf handling - pre-v0.2.60 guest script")
out(bool(re.search(r'(?m)^doctor\(\)',g)), "APK's emux-x11 has the doctor subcommand",
    "no doctor() - pre-v0.2.61")
# v0.2.84: the zero-process framebuffer mailbox. The guest script must launch
# Xvfb with -fbdir (detected at runtime, but the CODE has to be in the asset),
# or the app's fbdir reader never sees a file and the fallback keeps spawning
# the xwd|xwdtopnm|pnmtoplainpnm text chain per frame.
out('-fbdir /tmp/emux_fbdir' in g and 'FBDIR_CHECKED' in g,
    "APK's emux-x11 launches Xvfb with -fbdir (v0.2.84 zero-process framebuffer mailbox)",
    "no -fbdir handling - pre-v0.2.84 guest script")
# v0.2.85: the per-file rice opt-out. Without the marker helper in the asset,
# a user's own tint2rc/autostart/pcmanfm config keeps getting regenerated on
# every start no matter what they put in ~/.config/emux/.
out('emux_keep()' in g and 'keep-tint2rc' in g and 'keep-autostart' in g and 'keep-pcmanfm' in g,
    "APK's emux-x11 has the per-file rice opt-out markers (v0.2.85 keep-tint2rc/keep-autostart/keep-pcmanfm)",
    "no emux_keep markers - pre-v0.2.85 guest script")
PYEOF

# ---- 4. dex carries the current host-side code ----------------------------
# ENV_VERSION is a Kotlin `const val`, so it is INLINED into the dex as a bare
# int and cannot be grepped. Method names can: these three exist only in the
# v0.2.60/v0.2.61 sources, so their presence proves the dex is current.
echo
echo "--- dex (host-side Kotlin)"
python3 - "$APK" > "$TMPD/d" <<'PYEOF'
import struct,sys,zipfile
z=zipfile.ZipFile(sys.argv[1])
dex=sorted(n for n in z.namelist() if n.startswith('classes') and n.endswith('.dex'))
blobs=[z.read(n) for n in dex]
def out(ok,desc,detail=""): print(("1|" if ok else "0|")+desc+"|"+detail)
for sym in ('injectScroll','injectKeysymCombo','maybeShowIntro','initDamageTracking','drainDamageRects',
            # v0.2.83: the GetImage BadLength fix + event parking must be IN the
            # shipped dex. pendingEvents/requestReplyFull/damageRectOf are private
            # but D8 (no minification) keeps the names; hasQueuedEvent is public.
            'pendingEvents','requestReplyFull','damageRectOf','hasQueuedEvent','postXtest',
            # v0.2.84: raw-565 grabs + the band-diff painter + the fbdir mailbox
            # reader must all be in the shipped dex.
            'grabRegion565','diffUpload','paintRawFrame','xwdHeaderOf','fbdirAvailable',
            # v0.2.85: mouse/trackpad - the batched wheel burst (client), the
            # two-axis service entry, the direct mouse gesture path and its
            # source check, plus the natural-scroll pref key string.
            'fakeScrollBurst','injectScrollPair','onMouseTouchEvent','isMouseInput',
            'mouse_natural_scroll'):
    hit=any(sym.encode() in b for b in blobs)
    out(hit,f"dex contains {sym}",
        "" if hit else "not found in any classes*.dex - the APK predates v0.2.60/v0.2.61")
out(len(dex)>=2,"APK is multidex as expected","" if len(dex)>=2 else f"found {len(dex)} dex file(s)")

# ---- 4b. classes the app cannot start without are DEFINED, not just named ----
# v0.2.79: a LOWMEM build shipped an APK with ActivityMainBinding absent from the
# dex (javac could not resolve com.emux.R, wrote nothing, and its status was lost
# down a pipe). All 52 checks passed, because the byte-greps above cannot tell a
# class the dex DEFINES from one it merely REFERENCES: MainActivity names the
# binding either way, so its descriptor sits in the string table regardless. The
# fix is to read the dex's own class_defs table - header offsets are fixed by the
# format, and this needs nothing but zipfile and struct.
def defined_classes(b):
    str_size,str_off = struct.unpack_from('<II',b,0x38)
    type_size,type_off = struct.unpack_from('<II',b,0x40)
    cd_size,cd_off = struct.unpack_from('<II',b,0x60)
    strs = struct.unpack_from('<%dI'%str_size,b,str_off)
    types = struct.unpack_from('<%dI'%type_size,b,type_off)
    def desc(i):
        p = strs[i]
        while b[p] & 0x80: p += 1        # skip the uleb128 length
        p += 1
        return b[p:b.index(b'\0',p)].decode('utf-8','replace')
    r = set()
    for k in range(cd_size):             # class_def_item is 32 bytes, class_idx first
        (cidx,) = struct.unpack_from('<I',b,cd_off + k*32)
        r.add(desc(types[cidx]))
    return r
try:
    defs = set()
    for b in blobs: defs |= defined_classes(b)
    need = ['Lcom/emux/databinding/ActivityMainBinding;',  # MainActivity.onCreate inflates through it
            'Lcom/emux/MainActivity;',
            'Lcom/emux/SettingsActivity;',
            'Lcom/emux/x11/DesktopService;',
            'Lcom/emux/x11/DesktopView;']
    missing = [n for n in need if n not in defs]
    out(not missing,"dex DEFINES the load-bearing host classes (binding + activities + service + view)",
        "" if not missing else
        "absent from class_defs: "+", ".join(missing)+" - the APK would NoClassDefFoundError on launch")
except Exception as e:
    out(False,"dex class_defs readable","%s: %s"%(type(e).__name__,e))
PYEOF

for f in "$TMPD/a" "$TMPD/d"; do
  while IFS='|' read -r ok desc detail; do
    [ -z "$ok" ] && continue
    ck "$desc" "$ok" "$detail"
  done < "$f"
done

# ---- 5. dependency versions match deps.lock -------------------------------
# v0.2.62: added because a shipped APK crashed on every launch and all 50 checks
# above passed. The cause class was dependency drift: the no-Gradle build resolved
# androidx.sqlite 2.4.0 / tracing 1.2.0 / localbroadcastmanager 1.1.0 where AGP
# resolves 2.3.0 / 1.0.0 / 1.0.0. Class NAMES were identical, so a class-set
# comparison cannot see it - only method signatures differ, and those are invisible
# without running the code. Every AAR ships META-INF/<group>_<artifact>.version
# naming its own version, so the packaged markers can be diffed against the lock
# that produced them. This is the only check here that tests the DEPENDENCIES
# rather than the app's own code and assets.
echo
echo "--- dependency versions (APK markers vs deps.lock)"
LOCK="$HERE/build-apk/deps.lock"
if [ ! -f "$LOCK" ]; then
  sk "dependency version markers" "no deps.lock at $LOCK"
elif ! command -v python3 >/dev/null 2>&1; then
  sk "dependency version markers" "python3 not installed"
else
  python3 - "$APK" "$LOCK" > "$TMPD/depvers" <<'PYEOF'
import re,sys,zipfile
apk,lock=sys.argv[1],sys.argv[2]
pins={}
# A KMP artifact publishes under its base name but the .version marker it ships is
# named for the BASE module, not the -android/-jvm variant: deps.lock pins
# lifecycle-runtime-android and kotlinx-coroutines-core-jvm, while the APK carries
# androidx.lifecycle_lifecycle-runtime.version and kotlinx_coroutines_core.version.
# Index both spellings so those do not mis-match a shorter artifact name (which is
# how 'core' 1.13.1 got blamed for coroutines-core 1.8.1).
for ln in open(lock):
    ln=ln.split('#')[0].strip()
    if not ln or ':' not in ln: continue
    coord=ln.split('@')[0]
    parts=coord.split(':')
    if len(parts)<3: continue
    art=parts[1].replace('_','-').lower(); ver=parts[2]
    pins[art]=ver
    for suf in ('-android','-jvm'):
        if art.endswith(suf): pins.setdefault(art[:-len(suf)], ver)
z=zipfile.ZipFile(apk)
markers={n.split('/')[-1][:-len('.version')]:z.read(n).decode('utf-8','replace').strip()
         for n in z.namelist() if n.startswith('META-INF/') and n.endswith('.version')}
VER=re.compile(r'^\d+(\.\d+)*([.\-][A-Za-z0-9.\-]+)?$')
# AGP *generates* a few .version markers that no published artifact carries, so a
# Gradle-built APK contains them and a from-source build cannot without
# fabricating a file. lifecycle-runtime-ktx 2.8.6 is a KMP anchor whose jar holds
# only MANIFEST.MF + kotlin-project-structure-metadata.json - the marker for it is
# written by AGP's writeVersionFile task, not shipped. Counted, not failed.
GENERATED_ONLY={'androidx.lifecycle_lifecycle-runtime-ktx'}
matched=drift=opaque=generated=0
for name,ver in sorted(markers.items()):
    if name in GENERATED_ONLY: generated+=1; continue
    norm=name.replace('_','-').lower()
    # longest artifact name that is a separator-delimited suffix of the marker
    cands=[a for a in pins if norm==a or norm.endswith('-'+a)]
    if not cands:
        print(f"0|no deps.lock entry for marker {name} (version {ver})|unpinned dependency packaged into the APK")
        drift+=1; continue
    art=max(cands,key=len)
    if not VER.match(ver):
        opaque+=1; continue        # AGP writes a Gradle up-to-date message for some artifacts
    matched+=1
    if pins[art]!=ver:
        print(f"0|{art}: APK carries {ver}, deps.lock pins {pins[art]}|version drift - a wrong dependency was packaged")
        drift+=1
if not drift:
    extra=[]
    if opaque: extra.append(f"{opaque} opaque")
    if generated: extra.append(f"{generated} AGP-generated")
    note=(" ("+", ".join(extra)+")") if extra else ""
    print(f"1|{matched} dependency version marker(s) match deps.lock{note}, {len(markers)} in APK|")
if drift:
    print(f"0|{drift} dependency version marker(s) do NOT match deps.lock|see lines above")
PYEOF
  while IFS='|' read -r ok desc detail; do
    [ -z "$ok" ] && continue
    ck "$desc" "$ok" "$detail"
  done < "$TMPD/depvers"
fi

rm -rf "$TMPD"
echo
echo "=== summary ==="
echo "  source ENV_VERSION=$SRC_ENV (a device only re-seeds the guest scripts when"
echo "  this rises, so it must be > the installed build's for fixes to land)"
echo "passed=$PASS failed=$FAIL skipped=$SKIP"
[ "$FAIL" = 0 ] && echo "  => this APK matches the working tree; safe to adb install -r" \
                || echo "  => DO NOT SHIP THIS APK: it does not match the working tree"
[ "$FAIL" = 0 ]
