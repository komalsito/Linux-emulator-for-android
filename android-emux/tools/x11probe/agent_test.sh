#!/bin/sh
# Test the NEW guest input agent (extracted verbatim from emux-x11) against a
# real Xvfb + Openbox + xterm, feeding it the exact lines DesktopService sends.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
AGENT=${AGENT:-/tmp/agent_extracted.sh}
# Always test the agent AS IT IS EMBEDDED in assets/bin/emux-x11, never a stale copy.
python3 "$HERE/extract_agent.py" "$AGENT" || exit 1
D=91
export DISPLAY=127.0.0.1:$D LANG=C.UTF-8 LC_ALL=C.UTF-8
OUT=/tmp/agent_typed.txt
rm -f $OUT /tmp/emux_in /tmp/emux_display_num /tmp/emux_agent*.log /tmp/.X${D}-lock
mkdir -p /tmp/.X11-unix

Xvfb :$D -screen 0 720x1280x24 -listen tcp -ac -nolisten unix >/dev/null 2>&1 &
XV=$!
i=0; while [ $i -lt 100 ]; do [ -e /tmp/.X${D}-lock ] && break; i=$((i+1)); sleep 0.1; done
sleep 0.8
[ -e /tmp/.X${D}-lock ] || { echo "Xvfb failed"; exit 1; }
echo "$D" > /tmp/emux_display_num
openbox >/dev/null 2>&1 &
sleep 1.5
xterm -fa Monospace -fs 11 -geometry 60x16+60+80 -e sh -c \
  'while IFS= read -r l; do printf "%s\n" "$l" >> '"$OUT"'; done' >/dev/null 2>&1 &
sleep 2.5
W=$(xdotool search --name xterm | head -1)
echo "xterm window: ${W:-NONE}"
xdotool windowactivate --sync "$W" >/dev/null 2>&1
sleep 0.5

echo "--- starting the agent exactly as emux-x11 does ---"
sh "$AGENT" >/tmp/emux_agent.log 2>&1 </dev/null &
AG=$!
sleep 1.2
[ -p /tmp/emux_in ] && echo "  FIFO /tmp/emux_in created by the agent" || echo "  FIFO MISSING"

# The host side opens the FIFO write-only; the agent holds it read-write.
exec 4>/tmp/emux_in

echo "--- sending the v0.2.59 protocol ---"
printf 'M 240 260 0\n'   >&4; sleep 0.4
printf 'D 1 0 0\n'       >&4; sleep 0.2
printf 'U 1 0 0\n'       >&4; sleep 0.5
printf 'T hello agent\n' >&4; sleep 0.5
printf 'N Return\n'      >&4; sleep 0.6
printf 'T xdotool works\n' >&4; sleep 0.5
printf 'N Return\n'      >&4; sleep 0.6
# BackSpace handling: type, delete 4 chars, retype
printf 'T abcd\n'        >&4; sleep 0.4
printf 'N BackSpace BackSpace BackSpace BackSpace\n' >&4; sleep 0.4
printf 'T XY\n'          >&4; sleep 0.4
printf 'N Return\n'      >&4; sleep 0.7
# legacy keysym form, which the agent must now translate
printf 'K 65293\n'       >&4; sleep 0.5
printf 'T legacy\n'      >&4; sleep 0.4
printf 'K 65293\n'       >&4; sleep 0.8
exec 4>&-

echo
echo "=== xterm received (via its shell) ==="
sed 's/^/   | /' $OUT 2>/dev/null || echo "   MISSING - nothing arrived"
echo
echo "=== agent log ==="
sed 's/^/   /' /tmp/emux_agent.log | head -25
echo
echo "=== verdict ==="
pass=0; fail=0
chk() { if grep -qF "$1" $OUT 2>/dev/null; then echo "   [PASS] line '$1' typed correctly"; pass=$((pass+1));
        else echo "   [FAIL] line '$1' missing"; fail=$((fail+1)); fi; }
chk "hello agent"
chk "xdotool works"
chk "XY"   # 'abcd' + 4x BackSpace + 'XY' => 'XY'; asserting 'abXY' was the bug in this test
chk "legacy"
[ "$fail" = 0 ] && echo "   => the new T/N protocol types correctly" || echo "   => FAILURES: $fail"
echo "   passed=$pass failed=$fail"

kill $AG 2>/dev/null; pkill -x xterm 2>/dev/null; pkill -x openbox 2>/dev/null
kill $XV 2>/dev/null; rm -f /tmp/.X${D}-lock
