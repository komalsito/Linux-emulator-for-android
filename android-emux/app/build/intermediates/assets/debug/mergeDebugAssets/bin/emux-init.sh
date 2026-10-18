#!/system/bin/sh
# emux-init - unprivileged session launcher for the Alpine rootfs.
# NOTE: no apostrophes allowed anywhere in this file - TerminalSession may pass
# the whole body through a single-quoted shell string (script(1) PTY wrapper).
ROOT="${EMUX_ROOTFS:-${EMUX_ROOT:-$HOME/../linuxroot}}"
[ -d "$ROOT/etc" ] || ROOT="$HOME/linuxroot"
[ -d "$ROOT/etc" ] || ROOT="$(dirname "$HOME")/linuxroot"
export PATH="$ROOT/usr/local/bin:$ROOT/usr/bin:$ROOT/bin:$ROOT/usr/local/sbin:$ROOT/usr/sbin:$ROOT/sbin:/system/bin:/system/xbin:$PATH"
export LD_LIBRARY_PATH="$ROOT/lib:$ROOT/usr/lib"
export TMPDIR="${TMPDIR:-$ROOT/tmp}"
mkdir -p "$ROOT/tmp" 2>/dev/null
export SHELL=/bin/sh
if [ ! -d "$ROOT/etc" ]; then
  echo "emux: rootfs missing at $ROOT - tap Install (or re-install) to bootstrap." >&2
  exec /system/bin/sh -l
fi
# v0.1.22 BUGFIX: Android toybox sh prints a bogus "sh: syntax error:
# unexpected" line on stderr when handed multi-line code via -c (our exact
# launch form since the noexec-mount fix). It landed as the FIRST line of every
# v0.1.22: Android toybox sh writes a bogus "sh: syntax error: unexpected" to
# stderr when handed multi-line code via -c (our exact launch form since the
# noexec fix), and it appears as the FIRST line of every session, making a
# healthy terminal look broken. Detect our own launch mode ($0 is literally
# "sh" only for `sh -c`), reproduce the probe, and if this sh really emits the
# bogus line, silence fd2 for the rest of THIS launcher stage (the distro shell
# we exec below gets its own honest stderr back via the loader/exec chain).
if [ "$0" = sh ]; then
  PROBE=$(/system/bin/sh -c "$(printf true)" 2>&1 >/dev/null)
  case "$PROBE" in
    *unexpected*) exec 2>/dev/null ;;
  esac
fi
# The command arrives via EMUX_ARGV1 (set by TerminalSession inline, because
# app-data is mounted noexec so a real argv passthrough is impossible) or as
# positional params on legacy paths.
CMD="${EMUX_ARGV1:-$*}"
[ -n "$CMD" ] || CMD="$1"
SHELL_BIN="$ROOT/bin/sh"
if [ -x "$SHELL_BIN" ]; then
  [ -x "$ROOT/bin/bash" ] || export SHELL=/bin/sh
  cd "${EMUX_DISTRO_HOME:-$ROOT/home-emu}" 2>/dev/null \
    || { mkdir -p "$ROOT/home-emu" 2>/dev/null; cd "$ROOT/home-emu" 2>/dev/null; } \
    || cd "${HOME:-/}" 2>/dev/null || true
  LOADER=""
  for L in "$ROOT/lib/ld-musl-aarch64.so.1" "$ROOT/lib/ld-linux-aarch64.so.1"; do
    [ -x "$L" ] && { LOADER="$L"; break; }
  done
  PINNED="$PATH"
  [ -n "$EMUX_BIN" ] && PINNED="$EMUX_BIN:$PINNED"
  if [ -n "$CMD" ]; then
    if [ -n "$LOADER" ]; then
      exec "$LOADER" --library-path "$LD_LIBRARY_PATH" "$SHELL_BIN" -l -c "export PATH=\"$PINNED\"; $CMD"
    fi
    exec "$SHELL_BIN" -l -c "export PATH=\"$PINNED\"; $CMD"
  fi
  if [ -n "$LOADER" ]; then
    exec "$LOADER" --library-path "$LD_LIBRARY_PATH" "$SHELL_BIN" -l
  fi
  exec "$SHELL_BIN" -l
fi
exec /system/bin/sh -l
