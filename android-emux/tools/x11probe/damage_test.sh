#!/bin/sh
# damage_test.sh — Xvfb harness for damage_probe.py (v0.2.81).
# Proves the DAMAGE/XFIXES polling sequence the APK's partial capture speaks:
# idle root = zero rects, drawing client = the drawn rectangle, cleared after
# each read. No WM needed - damage is server-side, below any window manager.
set -u
D=90
T=$(mktemp -d /tmp/emux-dmg-test.XXXXXX) || exit 2
# wait after kill: PID 1 here never reaps, and a zombie openbox/Xvfb has already
# lied to pgrep-based assertions in ladder_test.sh once (v0.2.79).
trap 'kill $XV 2>/dev/null; wait $XV 2>/dev/null; rm -f /tmp/.X$D-lock /tmp/.X11-unix/X$D; rm -rf "$T"' EXIT INT TERM

for need in Xvfb xterm python3; do
    command -v "$need" >/dev/null 2>&1 || { echo "SKIP: $need not installed"; exit 0; }
done

rm -f /tmp/.X$D-lock /tmp/.X11-unix/X$D
Xvfb :$D -screen 0 1280x720x24 -listen tcp >"$T/xvfb.log" 2>&1 &
XV=$!
i=0
while [ ! -S /tmp/.X11-unix/X$D ] && [ "$i" -lt 50 ]; do sleep 0.1; i=$((i + 1)); done
[ -S /tmp/.X11-unix/X$D ] || { echo "Xvfb did not come up; log:"; tail -5 "$T/xvfb.log"; exit 2; }

echo "== damage probe on :$D =="
DISPLAY=:$D python3 "$(dirname "$0")/damage_probe.py"
RC=$?
exit $RC
