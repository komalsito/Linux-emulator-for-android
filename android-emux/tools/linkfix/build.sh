#!/bin/sh
# Build libemux-linkfix.so for every guest ABI EmuX supports and drop the
# results into app/src/main/assets/linkfix/<abi>/.
# Freestanding (-nostdlib): one binary per arch serves glibc AND musl guests.
set -e
cd "$(dirname "$0")"
OUT=../../app/src/main/assets/linkfix
CFLAGS="-shared -fPIC -nostdlib -O2 -Wall -Wextra -fno-stack-protector -fno-ident"
build() { # $1 = cc, $2 = abi dir
  mkdir -p "$OUT/$2"
  $1 $CFLAGS -o "$OUT/$2/libemux-linkfix.so" libemux-linkfix.c
  echo "built $OUT/$2/libemux-linkfix.so"
}
build "${CC_AARCH64:-aarch64-linux-gnu-gcc}"     arm64-v8a
build "${CC_ARM:-arm-linux-gnueabihf-gcc}"       armeabi-v7a
build "${CC_X86_64:-gcc}"                        x86_64
build "${CC_X86:-i686-linux-gnu-gcc}"            x86
for f in "$OUT"/*/libemux-linkfix.so; do
  echo "== $f"; readelf -h "$f" | grep -E "Class|Machine|Type"; readelf -d "$f" | grep -E "NEEDED|SONAME" || true
done
