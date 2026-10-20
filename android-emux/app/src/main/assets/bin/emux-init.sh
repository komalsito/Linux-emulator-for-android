#!/system/bin/sh
# emux-init - session launcher for the Alpine rootfs (v0.1.28).
# IMPORTANT: no apostrophes anywhere in this file - TerminalSession passes the
# whole body through single-quoted shell strings (script(1) PTY wrapper).
#
# Launch priority:
#   1. proot (bundled in $EMUX_NATIVE_BRIDGE): full chroot-equivalent WITHOUT
#      root - absolute distro paths resolve, PT_INTERP is rewritten, xkbcomp
#      works for Xvfb. Same engine Termux proot-distro / AnLinux / UserLAnd use.
#   2. musl loader exec ($ROOT/lib/ld-musl-*.so.1 + bin/busybox): terminal
#      only - programs that exec absolute distro paths internally (X servers)
#      do NOT work this way.
#   3. plain Android sh - last resort; unlike every previous version it HONORS
#      $CMD instead of silently dropping it (v0.1.27 bug: the fallback ignored
#      the command entirely, so desktop sessions opened idle shells).
ROOT="${EMUX_ROOTFS:-${EMUX_ROOT:-$HOME/../linuxroot}}"
[ -d "$ROOT/etc" ] || ROOT="$HOME/linuxroot"
[ -d "$ROOT/etc" ] || ROOT="$(dirname "$HOME")/linuxroot"
export TMPDIR="${TMPDIR:-$ROOT/tmp}"
mkdir -p "$ROOT/tmp" 2>/dev/null
CMD="${EMUX_ARGV1:-$*}"
[ -n "$CMD" ] || CMD="$1"
NB="${EMUX_NATIVE_BRIDGE:-$(dirname "$HOME")/native-bridge}"
PROOT="$NB/proot"

# v0.1.22 (kept): Android toybox sh may print a bogus syntax-error line when
# handed multi-line code via -c; detect and silence it for THIS launcher stage.
if [ "$0" = sh ]; then
  PROBE=$(/system/bin/sh -c "$(printf true)" 2>&1 >/dev/null)
  case "$PROBE" in
    *unexpected*) exec 2>/dev/null ;;
  esac
fi

if [ ! -d "$ROOT/etc" ]; then
  echo "emux: rootfs missing at $ROOT - tap Install (or re-install) to bootstrap." >&2
  if [ -n "$CMD" ]; then exec /system/bin/sh -c "$CMD"; fi
  exec /system/bin/sh -l
fi

# Default session commands mean: start an interactive login shell.
IS_LOGIN=0
case "$CMD" in
  ""|"bash -l"|"/bin/sh -l"|"sh -l") IS_LOGIN=1 ;;
esac

# --- Path 1: proot (preferred) ----------------------------------------------
# NOTE: assignments prefixed to `exec` are NOT exported (POSIX) - set + export
# in separate statements (v0.1.23 lesson). LD_LIBRARY_PATH must point at the
# native-bridge dir so bionic finds libtalloc/libandroid-shmem (the bundled
# proot uses DT_RUNPATH, which LD_LIBRARY_PATH overrides).
if [ -x "$PROOT" ]; then
  HOME=/root
  LD_LIBRARY_PATH="$NB"
  # v0.1.35: proot reads PROOT_TMP_DIR (NOT TMPDIR) for its host-side scratch;
  # the compiled-in Termux default is uncreatable outside Termux ("can-t create
  # glue rootfs" -> execve EACCES on every guest binary). Guest programs get
  # TMPDIR=/tmp (real dir inside the rootfs); the host scratch stays outside.
  PROOT_TMP_DIR="$TMPDIR"
  mkdir -p "$PROOT_TMP_DIR" 2>/dev/null
  TMPDIR=/tmp
  # v0.1.36: the Termux proot build execs guests via a companion loader whose
  # compiled-in path (/data/data/com.termux/...) cannot exist here; proot
  # honors PROOT_LOADER/_32 overrides pointing at our shipped copies.
  PROOT_LOADER="$NB/loader"
  if [ -f "$NB/loader32" ]; then
    PROOT_LOADER_32="$NB/loader32"
    export PROOT_LOADER_32
  fi
  # guest-side SHELL: util-linux script(1) runs its -c payload via $SHELL; the
  # inherited host value (/system/bin/sh) does not exist inside the rootfs.
  SHELL=/bin/sh
  export HOME LD_LIBRARY_PATH PROOT_TMP_DIR TMPDIR PROOT_LOADER SHELL
  if [ "$IS_LOGIN" = 1 ]; then
    # v0.1.37 GUEST PTY: this ROM kills raw musl execs (seccomp SIGSYS) so the
    # host-side PTY wrappers can never probe OK; but proot+loader now work, and
    # bootstrap installed util-linux script(1) — so allocate the PTY INSIDE the
    # guest: script gives the login shell a real pts + TIOCSCTTY (echo, job
    # control, sane line discipline) relayed over our pipes. Fixes the raw-pipe
    # degraded mode artifacts (blind typing, buffered output, IME desync).
    if [ -f "$ROOT/usr/bin/script" ] || [ -f "$ROOT/bin/script" ]; then
      if [ -f "$ROOT/bin/bash" ]; then
        exec "$PROOT" -0 -k -r "$ROOT" -b /dev -b /proc -b /sys -w /root /usr/bin/script -qfc "/bin/bash -l" /dev/null
      fi
      exec "$PROOT" -0 -k -r "$ROOT" -b /dev -b /proc -b /sys -w /root /usr/bin/script -qfc "/bin/sh -l" /dev/null
    fi
    if [ -f "$ROOT/bin/bash" ]; then
      exec "$PROOT" -0 -k -r "$ROOT" -b /dev -b /proc -b /sys -w /root /bin/bash -l
    fi
    exec "$PROOT" -0 -k -r "$ROOT" -b /dev -b /proc -b /sys -w /root /bin/sh -l
  fi
  exec "$PROOT" -0 -k -r "$ROOT" -b /dev -b /proc -b /sys -w /root /bin/sh -l -c "$CMD"
fi

# --- Path 2: musl loader exec (no proot available) ---------------------------
# v0.1.28 FIXES: (a) the old gate tested [ -x "$ROOT/bin/sh" ] - but bin/sh is
# a symlink to /bin/busybox (ABSOLUTE), dangling on the Android host, so the
# whole distro branch was dead code on every device; gate on the real busybox
# file instead. (b) the loader search only covered aarch64 - now it globs every
# arch. (c) bash is preferred when the bootstrap extras installed it.
LOADER=""
for L in "$ROOT"/lib/ld-musl-*.so.1; do
  [ -f "$L" ] && { LOADER="$L"; break; }
done
if [ -n "$LOADER" ] && [ -f "$ROOT/bin/busybox" ]; then
  LP="$ROOT/lib:$ROOT/usr/lib"
  PATH="$ROOT/usr/local/bin:$ROOT/usr/bin:$ROOT/bin:$ROOT/usr/local/sbin:$ROOT/usr/sbin:$ROOT/sbin:/system/bin:/system/xbin:$PATH"
  export PATH
  if [ -f "$ROOT/bin/bash" ]; then
    DSHELL="$ROOT/bin/bash"
    BUSYBOX_MODE=0
  else
    DSHELL="$ROOT/bin/busybox"
    BUSYBOX_MODE=1
  fi
  if [ "$IS_LOGIN" = 1 ]; then
    if [ "$BUSYBOX_MODE" = 1 ]; then
      exec "$LOADER" --library-path "$LP" "$DSHELL" sh -l
    fi
    exec "$LOADER" --library-path "$LP" "$DSHELL" -l
  fi
  if [ "$BUSYBOX_MODE" = 1 ]; then
    exec "$LOADER" --library-path "$LP" "$DSHELL" sh -l -c "$CMD"
  fi
  exec "$LOADER" --library-path "$LP" "$DSHELL" -l -c "$CMD"
fi

# --- Path 3: plain Android shell (never drops CMD) ---------------------------
echo "emux: distro engine unavailable (no proot at $NB, no loader/busybox in $ROOT) - plain Android shell." >&2
if [ -n "$CMD" ]; then exec /system/bin/sh -c "$CMD"; fi
exec /system/bin/sh -l
