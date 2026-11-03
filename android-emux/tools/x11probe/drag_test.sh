#!/bin/sh
# v0.2.79 - does a left-drag actually DRAG?
#
# Field report: "I can't drag items inside the desktop (I am able to click and
# it works)". The transport was fine - the gesture never sent a ButtonPress, so
# the server saw motion events with no button down and then a release for a
# button nothing had pressed.
#
# This harness brings up a real Xvfb + Openbox running the rc.xml that
# assets/bin/emux-x11 ships today, then runs drag_probe.py, which injects BOTH
# the old sequence (motion, motion, release) and the new one (press, motion,
# release) at a framed window's titlebar and measures whether the window moves.
#
# Needs: Xvfb, openbox, python3. Missing tools skip, not fail.
# Usage: sh tools/x11probe/drag_test.sh        (from android-emux/)
set -u
cd "$(dirname "$0")/../.." || exit 2
BIN=app/src/main/assets/bin/emux-x11
D=89
P=6089
T=$(mktemp -d /tmp/emux-drag-test.XXXXXX) || exit 2
# `wait` after `kill` matters: PID 1 in this container is a node process that
# never reaps, so a killed-but-unwaited child stays in the process table as a
# zombie forever - and `pgrep -x openbox` in ladder_test.sh then reported "stage 2
# removed the grabber" as a FAIL against a WM that was long dead. A harness must
# clean up after itself in a shared sandbox.
trap 'kill $OB $XV 2>/dev/null; wait $OB $XV 2>/dev/null; rm -f /tmp/.X$D-lock /tmp/.X11-unix/X$D; rm -rf "$T"' EXIT INT TERM

for need in Xvfb openbox python3; do
    command -v "$need" >/dev/null 2>&1 || { echo "SKIP: $need not installed"; exit 0; }
done

# The rc.xml the launcher writes today, extracted from its heredoc by the
# OPENER (not by the variable name it writes to, which has already changed once).
mkdir -p "$T/home/.config/openbox"
awk '/<<.RCXML.$/{f=1;next} /^RCXML$/{f=0} f' "$BIN" > "$T/home/.config/openbox/rc.xml"
[ -s "$T/home/.config/openbox/rc.xml" ] || { echo "could not extract rc.xml from $BIN"; exit 2; }

rm -f /tmp/.X$D-lock /tmp/.X11-unix/X$D
Xvfb :$D -screen 0 1280x720x24 -listen tcp >"$T/xvfb.log" 2>&1 &
XV=$!
i=0
while [ ! -S /tmp/.X11-unix/X$D ] && [ "$i" -lt 50 ]; do sleep 0.1; i=$((i + 1)); done
[ -S /tmp/.X11-unix/X$D ] || { echo "Xvfb did not come up; log:"; tail -5 "$T/xvfb.log"; exit 2; }

HOME="$T/home" DISPLAY=:$D openbox >"$T/openbox.log" 2>&1 &
OB=$!
sleep 1.5
# Check OUR openbox by pid and /proc state, not `pgrep -x openbox`: pgrep also
# matches zombies left behind by earlier harnesses, so a dead WM would look alive
# and the probe would run against an unmanaged display.
proc_state() { sed 's/.*) //' /proc/$1/stat 2>/dev/null | cut -d' ' -f1; }
OBST=$(proc_state "$OB")
if [ -z "$OBST" ] || [ "$OBST" = Z ]; then
    echo "openbox did not stay up (state '${OBST:-gone}'); log:"; tail -5 "$T/openbox.log"; exit 2
fi

echo "display :$D   openbox pid $OB   rc.xml: $(awk '/<context/ {n++} END {print n" contexts"}' "$T/home/.config/openbox/rc.xml")"
echo "titlebar binding in force: $(grep -A2 'name="Titlebar"' "$T/home/.config/openbox/rc.xml" | grep -o 'action="Drag".*' | head -1)"
echo
DISPLAY=:$D python3 tools/x11probe/drag_probe.py $P "$@"
rc=$?
echo
case $rc in
  0) echo "PASS: a client receives ButtonPress + MotionNotify(Button1Mask) + ButtonRelease,"
     echo "      and dragging the titlebar MOVES the window. The old motion-only"
     echo "      sequence does neither - which is the reported bug, reproduced." ;;
  1) echo "FAIL: at least one expectation did not hold (see the checks above)." ;;
  *) echo "COULD NOT RUN (exit $rc) - the probe needs a window manager to frame its window." ;;
esac
exit $rc
