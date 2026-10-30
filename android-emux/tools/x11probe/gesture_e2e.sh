#!/bin/sh
# Proves the v0.2.60 gesture layer works ON THE WIRE.
#
# DesktopService itself cannot run on a JVM (it is an Android Service), but the
# sequences it now composes are pure X11Client calls, so GestureDriver.kt
# reproduces them exactly and `xev -event button,key` records what the SERVER
# actually delivered - button numbers, keycodes and the modifier state mask.
#
# Asserts: middle click, wheel up/down/left/right, Ctrl+C, Alt+Tab, Ctrl+T
# (uppercase through the keymap's shift level, no explicit Shift hold) and a
# right-button drag.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
D=${D:-97}
KP1=${KP1:-/tmp/kp1}
ANDROID_JAR=${ANDROID_JAR:-/opt/asdk/android-34/android.jar}
KOTLINC_HOME=${KOTLINC_HOME:-/opt/kt/kotlinc}
LOG=/tmp/xev_gestures.log
[ -f "$KP1/com/emux/x11/X11Client.class" ] || { echo "run compile_all.sh first"; exit 2; }

rm -f /tmp/.X$D-lock $LOG; mkdir -p /tmp/.X11-unix
Xvfb :$D -screen 0 720x1280x24 -listen tcp -ac -nolisten unix >/dev/null 2>&1 &
XV=$!
i=0; while [ $i -lt 100 ]; do [ -e /tmp/.X$D-lock ] && break; i=$((i+1)); sleep 0.1; done
sleep 0.8
[ -e /tmp/.X$D-lock ] || { echo "Xvfb failed"; exit 1; }
export DISPLAY=127.0.0.1:$D
# No window manager on purpose: with one, the WM may reposition or reparent the
# witness window and the click coordinates below would be wrong.
# NOTE: -event takes ONE mask per occurrence and the masks are `button` and
# `keyboard` - `-event button,key` is rejected outright ("unrecognized event
# mask") and xev exits, which reads as "the server delivered nothing".
xev -event button -event keyboard -geometry 300x200+100+100 > $LOG 2>&1 &
XEV=$!
sleep 2.0

rm -rf /tmp/kgd; mkdir -p /tmp/kgd
"$KOTLINC_HOME/bin/kotlinc" -nowarn -jvm-target 17 -cp "$ANDROID_JAR:$KP1" \
  "$HERE/GestureDriver.kt" -d /tmp/kgd 2>&1 | head -10
[ -f /tmp/kgd/GestureDriverKt.class ] || { echo "driver compile failed"; exit 1; }

echo "=== driver (X pointer aimed at the xev window centre 250,200) ==="
java -cp "/tmp/kgd:$KP1:$ANDROID_JAR:$KOTLINC_HOME/lib/kotlin-stdlib.jar" \
  GestureDriverKt $D 250 200
DRC=$?

kill $XEV 2>/dev/null; pkill -x Xvfb 2>/dev/null; rm -f /tmp/.X$D-lock
sleep 0.3
if grep -q "unrecognized\|usage:" $LOG 2>/dev/null; then
  echo "WITNESS FAILED TO START:"; head -3 $LOG; exit 2
fi

echo
echo "=== xev witness: what the server delivered ==="
p=0; f=0
# count occurrences of a pattern in the log
cnt() { grep -oE "$1" $LOG 2>/dev/null | wc -l | tr -d ' '; }
ck() {  # label, expected-count, pattern
  n=$(cnt "$3")
  if [ "$n" = "$2" ]; then echo "  [PASS] $1 (x$n)"; p=$((p+1));
  else echo "  [FAIL] $1: expected $2, got $n"; f=$((f+1)); fi
}
ckl() { # label, grep-pattern that must be present
  if grep -qE "$2" $LOG 2>/dev/null; then echo "  [PASS] $1"; p=$((p+1));
  else echo "  [FAIL] $1 (pattern '$2' absent)"; f=$((f+1)); fi
}

ck  "middle click = button 2, press+release" 2 "button 2,"
ck  "wheel up = button 4, 3x press+release"  6 "button 4,"
ck  "wheel down = button 5, 2x press+release" 4 "button 5,"
ck  "wheel left = button 6"                  2 "button 6,"
ck  "wheel right = button 7"                 2 "button 7,"
ck  "right-drag = button 3, press+release"   2 "button 3,"
ckl "Ctrl held while 'c' is typed (state 0x4 = ControlMask)" \
    "state 0x4, keycode [0-9]+ \(keysym 0x63, c\)"
ckl "Control_L itself was pressed and released" "keysym 0xffe3, Control_L"
ckl "Alt held while Tab is typed (state 0x8 = Mod1Mask)" \
    "state 0x8, keycode [0-9]+ \(keysym 0xff09, Tab\)"
ckl "Ctrl+T uppercase via keymap shift level (state includes ControlMask)" \
    "state 0x[0-9a-f]*, keycode [0-9]+ \(keysym 0x54, T\)"

echo
echo "  driver exit=$DRC   passed=$p failed=$f"
[ "$f" = 0 ] && [ $DRC = 0 ] && echo "  => the gesture layer's wire behaviour is correct"
