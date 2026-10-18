#!/system/bin/sh
# emux-init-probe - v0.1.24 read-only environment self-test. No apostrophes.
ROOT=${EMUX_ROOTFS:-$HOME/../linuxroot}
[ -d "$ROOT/etc" ] || ROOT=$HOME/linuxroot
if [ ! -d "$ROOT/etc" ]; then echo PROBE_NOROOTFS; exit 0; fi
LOADER=
for L in "$ROOT/lib/ld-musl-aarch64.so.1" "$ROOT/lib/ld-linux-aarch64.so.1"; do
  [ -x "$L" ] && LOADER=$L && break
done
SHELL_BIN=$ROOT/bin/sh
[ -x "$SHELL_BIN" ] || SHELL_BIN=$ROOT/usr/bin/sh
if [ -z "$LOADER" ]; then echo PROBE_NOLOADER; exit 0; fi
"$LOADER" --library-path "$ROOT/lib:$ROOT/usr/lib" "$SHELL_BIN" -c "echo LOADER_OK" 2>&1 || echo PROBE_LOADER_FAIL
"$LOADER" --library-path "$ROOT/lib:$ROOT/usr/lib" "$ROOT/sbin/apk" --version 2>&1 | head -1 >/dev/null && echo APK_OK || echo PROBE_APK_FAIL
