#!/system/bin/sh
# emux-init-probe - v0.1.30 read-only environment self-test. No apostrophes.
# Prints one marker per subsystem so MainActivity can show exactly which layer
# is broken on-device without adb/logcat.
ROOT=${EMUX_ROOTFS:-$HOME/../linuxroot}
# v0.1.49 BUGFIX: scan files/distros/* BEFORE the legacy fallback overwrites
# ROOT (see emux-init.sh); otherwise the probe never sees co-installed distros.
if [ ! -d "$ROOT/etc" ]; then
  for CAND in "$(dirname "$HOME")/distros"/*; do
    [ -d "$CAND/etc" ] && { ROOT="$CAND"; break; }
  done
fi
[ -d "$ROOT/etc" ] || ROOT=$HOME/linuxroot
if [ ! -d "$ROOT/etc" ]; then echo PROBE_NOROOTFS; exit 0; fi
echo "ROOTFS=$ROOT"

# SELinux domain of THIS process: untrusted_app_27 = exec of app-data allowed
# (targetSdk<=28); plain untrusted_app = W^X blocked (targetSdk>=29 in effect).
D=$(cat /proc/self/attr/current 2>/dev/null | tr -d "\000")
[ -n "$D" ] && echo "DOMAIN=$D"

NB=${EMUX_NATIVE_BRIDGE:-}
if [ -n "$NB" ] && [ -x "$NB/proot" ]; then
  LD_LIBRARY_PATH="$NB" "$NB/proot" --version >/dev/null 2>&1 && echo PROOT_OK || echo PROBE_PROOT_FAIL
  # ptrace probe: the smallest real proot run (fails with EPERM on ROMs that
  # block app ptrace, e.g. some HarmonyOS/OEM hardening)
  # v0.1.44: the micro-run must carry the same env as real proot runs
  # (loader + writable tmp), else it reports a false PROOT_PTRACE_DENIED.
  mkdir -p "$ROOT/tmp" 2>/dev/null
  LD_LIBRARY_PATH="$NB" PROOT_TMP_DIR="$ROOT/tmp" PROOT_LOADER="$NB/loader" \
    "$NB/proot" -0 -r "$ROOT" -b /dev /bin/true >/dev/null 2>&1 \
    && echo PROOT_PTRACE_OK || echo PROOT_PTRACE_DENIED
else
  echo PROBE_PROOT_MISSING
fi
[ -f "$ROOT/usr/bin/script" ] || [ -f "$ROOT/bin/script" ] && echo SCRIPT_OK || echo PROBE_SCRIPT_MISSING
{ [ -f "$ROOT/bin/bash" ] || [ -f "$ROOT/usr/bin/bash" ]; } && echo BASH_OK || echo PROBE_BASH_MISSING
[ -f "$ROOT/usr/bin/Xvfb" ] && echo XVFB_OK || echo PROBE_XVFB_MISSING
# v0.1.47: musl (Alpine) AND glibc (Debian/Ubuntu) loaders, incl. multiarch dirs.
LOADER=
for L in "$ROOT"/lib/ld-musl-*.so.1 "$ROOT"/lib/ld-linux-*.so.* "$ROOT"/lib/*-linux-gnu*/ld-linux-*.so.* "$ROOT"/lib/*-linux-gnu*/ld-*.so.*; do
  [ -f "$L" ] && LOADER=$L && break
done
if [ -z "$LOADER" ]; then echo PROBE_NOLOADER; exit 0; fi
# host exec permission probe: running the loader with no args prints usage
# (exit nonzero) when EXEC is allowed; a denial prints permission denied.
EXOUT=$("$LOADER" 2>&1)
case "$EXOUT" in
  *[Pp]ermission*) echo HOSTEXEC_DENIED ;;
  *) echo HOSTEXEC_OK ;;
esac
LP="$ROOT/lib:$ROOT/usr/lib"
for MD in "$ROOT"/lib/*-linux-gnu* "$ROOT"/usr/lib/*-linux-gnu*; do
  [ -d "$MD" ] && LP="$LP:$MD"
done
PBIN="$ROOT/bin/busybox"
[ -f "$PBIN" ] || PBIN="$ROOT/bin/bash"
[ -f "$PBIN" ] || PBIN="$ROOT/usr/bin/bash"
[ -f "$PBIN" ] || PBIN="$ROOT/bin/dash"
case "$PBIN" in *busybox) "$LOADER" --library-path "$LP" "$PBIN" sh -c "echo LOADER_OK" 2>&1 || echo PROBE_LOADER_FAIL ;; *) "$LOADER" --library-path "$LP" "$PBIN" -c "echo LOADER_OK" 2>&1 || echo PROBE_LOADER_FAIL ;; esac
# v0.1.47: package-manager probe per distro family (apk on Alpine, apt otherwise).
if [ -f "$ROOT/sbin/apk" ]; then
  "$LOADER" --library-path "$LP" "$ROOT/sbin/apk" --version >/dev/null 2>&1 && echo APK_OK || echo PROBE_APK_FAIL
elif [ -f "$ROOT/usr/bin/apt-get" ] || [ -f "$ROOT/bin/apt-get" ]; then
  APT="$ROOT/usr/bin/apt-get"
  [ -f "$APT" ] || APT="$ROOT/bin/apt-get"
  "$LOADER" --library-path "$LP" "$APT" --version >/dev/null 2>&1 && echo APT_OK || echo PROBE_APT_FAIL
else
  echo PROBE_NOPM
fi
