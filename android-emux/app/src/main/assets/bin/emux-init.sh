#!/system/bin/sh
# emux-init — unprivileged session launcher for the Alpine rootfs.
# v0.1.17: ROOT resolution hardened. Previously EMUX_ROOTFS was never exported
# by FsLayout.sessionEnv(), so the fallback $HOME/../linuxroot expanded to a
# non-existent path and every session silently dropped to plain Android sh
# (blank-looking terminal, no distro tools). We now prefer EMUX_ROOTFS/EMUX_ROOT
# and fall back through several guesses, failing LOUDLY with a notice instead of
# quietly exec'ing an empty shell.
ROOT="${EMUX_ROOTFS:-${EMUX_ROOT:-$HOME/../linuxroot}}"
[ -d "$ROOT/etc" ] || ROOT="$HOME/linuxroot"
[ -d "$ROOT/etc" ] || ROOT="$(dirname "$HOME")/linuxroot"
export PATH="$ROOT/usr/local/bin:$ROOT/usr/bin:$ROOT/bin:$ROOT/usr/local/sbin:$ROOT/usr/sbin:$ROOT/sbin:$PATH"
export LD_LIBRARY_PATH="$ROOT/lib:$ROOT/usr/lib"
export SHELL=/bin/sh
if [ ! -d "$ROOT/etc" ]; then
  echo "emux: rootfs missing at $ROOT — tap Install (or re-install) to bootstrap." >&2
  exec /system/bin/sh -l
fi
# The args arrive as "/bin/sh -l": run the DISTRO shell via its loader. musl
# binaries need their own ldso when invoked outside chroot; the minirootfs
# loader path is stable across Alpine versions.
LOADER=""
for L in "$ROOT/lib/ld-musl-aarch64.so.1" "$ROOT/lib/ld-linux-aarch64.so.1"; do
  [ -x "$L" ] && { LOADER="$L"; break; }
done
SHELL_BIN="$ROOT/bin/sh"
if [ -x "$SHELL_BIN" ]; then
  cd "${HOME:-/}" 2>/dev/null || true
  if [ -n "$LOADER" ]; then
    exec "$LOADER" --library-path "$LD_LIBRARY_PATH" "$SHELL_BIN" -l
  fi
  exec "$SHELL_BIN" -l
fi
exec /system/bin/sh "$@"
