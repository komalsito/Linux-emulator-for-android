#!/bin/sh
# Build emux-rgb565 for every guest ABI EmuX supports (freestanding static
# executables, same ABI matrix as the linkfix shim).
set -e
cd "$(dirname "$0")"
OUT=../../app/src/main/assets/capture
CFLAGS="-static -no-pie -nostdlib -O2 -Wall -Wextra -fno-stack-protector -fno-ident"
build() { # $1 = cc, $2 = abi dir
  mkdir -p "$OUT/$2"
  $1 $CFLAGS -o "$OUT/$2/emux-rgb565" emux-rgb565.c -lgcc
  echo "built $OUT/$2/emux-rgb565"
}
build "${CC_AARCH64:-aarch64-linux-gnu-gcc}" arm64-v8a
build "${CC_ARM:-arm-linux-gnueabihf-gcc}"     armeabi-v7a
build "${CC_X86_64:-gcc}"                      x86_64
build "${CC_X86:-i686-linux-gnu-gcc}"          x86
for f in "$OUT"/*/emux-rgb565; do
  echo "== $f"; readelf -h "$f" | grep -E "Class|Machine|Type"; readelf -d "$f" 2>/dev/null | grep NEEDED || echo "no-NEEDED(ok)"
done
