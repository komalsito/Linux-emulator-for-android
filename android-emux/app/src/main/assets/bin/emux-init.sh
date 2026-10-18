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
# v0.1.21 BUGFIX (desktop helpers died with "not found"): the old PATH rewrite
# REPLACED Android's PATH entirely, and every desktop helper session runs under
# a PTY wrapper resolved from /system/bin/script — which is looked up by the
# kernel execve BEFORE PATH matters for us, but busybox fallbacks and apk's
# helpers resolve through PATH at runtime. Keep Android dirs in PATH (prepend
# distro) instead of dropping them.
export PATH="$ROOT/usr/local/bin:$ROOT/usr/bin:$ROOT/bin:$ROOT/usr/local/sbin:$ROOT/usr/sbin:$ROOT/sbin:/system/bin:/system/xbin:$PATH"
# v0.1.21 BUGFIX (apk add failed on fresh rootfs → black desktop forever): musl
# binaries executed OUTSIDE a chroot need their own libc; without this every
# distro command (apk, xwd, Xvfb…) died with CANNOT LINK EXECUTABLE: library
# "libc.musl-aarch64.so.1" not found. The loader itself was launched with
# --library-path, but its CHILDREN inherit only the environment — so musl must
# be findable via LD_LIBRARY_PATH too.
export LD_LIBRARY_PATH="$ROOT/lib:$ROOT/usr/lib"
# apk/X do mktemp, PID files and sockets under /tmp — inside an app data dir
# there is no writable system /tmp visible to the distro unless we point TMPDIR
# (and create $ROOT/tmp) ourselves.
export TMPDIR="${TMPDIR:-$ROOT/tmp}"
mkdir -p "$ROOT/tmp" 2>/dev/null
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
# v0.1.20 BUGFIX (desktop never started): the script ALWAYS exec'd a plain
# login shell and ignored its command argument, so TerminalSession's desktop
# launches (`emux-x11 openbox`, `emux-capture 127.0.0.1:7001`) were silently
# replaced by an interactive shell — Xvfb never ran, port 6000 never opened,
# no frames ever arrived, pane stayed black forever. Now: when a command is
# passed, run it through the distro shell via `-c`; with no args, plain login
# shell as before.
# v0.1.20: callers pass the command as MULTIPLE argv slots (no shell quoting
# survives the Android sh + PTY-wrapper layers intact), so reassemble with "$@"
# rather than relying on a single pre-quoted $1.
CMD="$*"
[ -n "$CMD" ] || CMD="$1"
SHELL_BIN="$ROOT/bin/sh"
if [ -x "$SHELL_BIN" ]; then
  # Alpine ships NO bash in the minirootfs —FsLayout exported SHELL=/bin/bash,
  # which made every `$SHELL -c` / prompt lookup fail on fresh installs.
  [ -x "$ROOT/bin/bash" ] || export SHELL=/bin/sh
  cd "${EMUX_DISTRO_HOME:-$ROOT/home-emu}" 2>/dev/null \
    || { mkdir -p "$ROOT/home-emu" 2>/dev/null; cd "$ROOT/home-emu" 2>/dev/null; } \
    || cd "${HOME:-/}" 2>/dev/null || true
  LOADER_ARGS=""
  [ -n "$LOADER" ] && LOADER_ARGS="--library-path $LD_LIBRARY_PATH"
  if [ -n "$CMD" ]; then
    # v0.1.21 BUGFIX (desktop helpers "not found" inside distro shell): apk's
    # scripts and busybox fallbacks resolve through PATH at runtime; the old
    # PATH rewrite dropped /system/bin entirely for children of this launcher.
    # Also: emux-x11/emux-capture live in files/bin (first PATH entry from
    # FsLayout.sessionEnv) — but when we exec the DISTRO shell it rewrites
    # PATH again via /etc/profile.d; keep our bin dir pinned FIRST here so a
    # profile rewrite can't bury it.
    export EMUX_PINNED_BIN="$(dirname "$0"):$PATH"
    if [ -n "$LOADER" ]; then
      exec $LOADER $LOADER_ARGS "$SHELL_BIN" -l -c "export PATH=\"$ROOT/usr/local/bin:$ROOT/usr/bin:$ROOT/bin:/system/bin:/system/xbin:\$PATH\"; $CMD"
    fi
    exec "$SHELL_BIN" -l -c "$CMD"
  fi
  if [ -n "$LOADER" ]; then
    exec "$LOADER" --library-path "$LD_LIBRARY_PATH" "$SHELL_BIN" -l
  fi
  exec "$SHELL_BIN" -l
fi
exec /system/bin/sh "$@"
