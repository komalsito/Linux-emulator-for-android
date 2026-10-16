#!/system/bin/sh
# emux-init — unprivileged session launcher for the Alpine rootfs.
# No chroot needed: we exec the distro's musl loader with an explicit sysroot
# and set PATH/HOME so binaries resolve libraries correctly.
ROOT="${EMUX_ROOT:-$HOME/../linuxroot}"
export PATH="$ROOT/usr/local/bin:$ROOT/usr/bin:$ROOT/bin:$PATH"
export LD_LIBRARY_PATH="$ROOT/lib:$ROOT/usr/lib"
export SHELL=/bin/sh
[ -d "$ROOT/etc" ] || { echo "emux: rootfs missing at $ROOT"; exit 1; }
exec /system/bin/sh "$@"
