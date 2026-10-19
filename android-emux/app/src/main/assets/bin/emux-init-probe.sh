#!/system/bin/sh
# emux-init-probe - v0.1.28 read-only environment self-test. No apostrophes.
# Prints one marker per subsystem so MainActivity can show exactly which layer
# is broken on-device without adb/logcat.
ROOT=${EMUX_ROOTFS:-$HOME/../linuxroot}
[ -d "$ROOT/etc" ] || ROOT=$HOME/linuxroot
if [ ! -d "$ROOT/etc" ]; then echo PROBE_NOROOTFS; exit 0; fi
NB=${EMUX_NATIVE_BRIDGE:-}
if [ -n "$NB" ] && [ -x "$NB/proot" ]; then
  LD_LIBRARY_PATH="$NB" "$NB/proot" --version >/dev/null 2>&1 && echo PROOT_OK || echo PROBE_PROOT_FAIL
else
  echo PROBE_PROOT_MISSING
fi
[ -f "$ROOT/usr/bin/script" ] && echo SCRIPT_OK || echo PROBE_SCRIPT_MISSING
[ -f "$ROOT/bin/bash" ] && echo BASH_OK || echo PROBE_BASH_MISSING
[ -f "$ROOT/usr/bin/Xvfb" ] && echo XVFB_OK || echo PROBE_XVFB_MISSING
LOADER=
for L in "$ROOT"/lib/ld-musl-*.so.1; do
  [ -f "$L" ] && LOADER=$L && break
done
if [ -z "$LOADER" ]; then echo PROBE_NOLOADER; exit 0; fi
"$LOADER" --library-path "$ROOT/lib:$ROOT/usr/lib" "$ROOT/bin/busybox" sh -c "echo LOADER_OK" 2>&1 || echo PROBE_LOADER_FAIL
"$LOADER" --library-path "$ROOT/lib:$ROOT/usr/lib" "$ROOT/sbin/apk" --version >/dev/null 2>&1 && echo APK_OK || echo PROBE_APK_FAIL
