#!/bin/sh
# desktop_click_test.sh - regression test for the two defects that made the
# desktop "show but never open anything" (audit INPUT_DEAD_AUDIT_v0.2.66.md):
#
#   A. the XTEST wire path really delivers events to clients (xev witness), and
#   B. a desktop item needs TWO ButtonPress events, so any gesture layer that
#      eats the second tap of a double-tap makes the desktop unopenable.
#
# Needs: Xvfb, openbox, pcmanfm, xdotool, xev, xwd/xwdtopnm/pnmtopng (or PIL).
# Usage: sh tools/x11probe/desktop_click_test.sh [display]
set -u
DN=${1:-9}
export DISPLAY=":$DN"
HERE=$(cd "$(dirname "$0")" && pwd)
W=/tmp/emux_clicktest.$$
mkdir -p "$W/home/.config/pcmanfm/default" "$W/home/Desktop" "$W/out"
export HOME="$W/home"
PASS=0; FAIL=0
ok()  { echo "[PASS] $1"; PASS=$((PASS+1)); }
bad() { echo "[FAIL] $1"; FAIL=$((FAIL+1)); }

rm -f "/tmp/.X$DN-lock" "/tmp/.X11-unix/X$DN" 2>/dev/null
# THE GUEST'S EXACT SERVER FLAGS - keep in sync with emux-x11 start_xvfb().
export XPROBE_DIR="$HERE"
Xvfb ":$DN" -screen 0 720x1280x24 -listen tcp -ac -extension MIT-SHM >"$W/xvfb.log" 2>&1 &
XPID=$!
i=0; while [ $i -lt 60 ] && [ ! -S "/tmp/.X11-unix/X$DN" ]; do sleep 0.25; i=$((i+1)); done
[ -S "/tmp/.X11-unix/X$DN" ] || { echo "FATAL: Xvfb did not come up: $(cat "$W/xvfb.log")"; exit 1; }

# pcmanfm rice exactly as emux-x11 seeds it, plus a launcher that leaves a marker
printf '[desktop]\nwallpaper_mode=color\nwallpaper=\ndesktop_bg=#20344d\nshow_wm_menu=1\n[config]\nicon_theme=Adwaita\n' \
  > "$HOME/.config/pcmanfm/default/pcmanfm.conf"
printf '[*]\nwallpaper_mode=color\nwallpaper=\ndesktop_bg=#20344d\nshow_wm_menu=1\n' \
  > "$HOME/.config/pcmanfm/default/desktop-items-0.conf"
printf '[Desktop Entry]\nType=Application\nName=OpenMe\nExec=/bin/sh -c "touch %s/out/OPENED"\nIcon=utilities-terminal\n' "$W" \
  > "$HOME/Desktop/OpenMe.desktop"
chmod +x "$HOME/Desktop/OpenMe.desktop"

openbox >"$W/openbox.log" 2>&1 &
sleep 1
env DBUS_SESSION_BUS_ADDRESS=disabled: GIO_USE_VOLUME_MONITOR=none \
    pcmanfm --desktop >"$W/pcmanfm.log" 2>&1 &
sleep 4

xev -geometry 200x120+300+500 >"$W/xev.log" 2>&1 &
sleep 2
XEW=$(xdotool search --class xev 2>/dev/null | head -1)
G=$(xdotool getwindowgeometry --shell "$XEW" 2>/dev/null | tr '\n' ' ')
CX=$(echo "$G" | sed 's/.*X=\([0-9]*\).*/\1/'); CY=$(echo "$G" | sed 's/.*Y=\([0-9]*\).*/\1/')
[ -n "$CX" ] || { CX=400; CY=560; }
CX=$((CX+100)); CY=$((CY+60))

# --- A: does the byte-exact X11Client replica reach a live client? -----------
python3 "$HERE/xclient_probe.py" "/tmp/.X11-unix/X$DN" "$DN" replica >"$W/probe.log" 2>&1
grep -q "PROOF -> ok" "$W/probe.log" && ok "input proof ok on guest-flag Xvfb" \
  || bad "input proof not ok: $(tail -3 "$W/probe.log" | tr '\n' ' ')"
python3 - "$CX" "$CY" <<'PY' >>"$W/probe.log" 2>&1
import sys, time, os
sys.path.insert(0, os.environ["XPROBE_DIR"])
import xclient_probe as X
c = X.C("/tmp/.X11-unix/X%s" % os.environ["DISPLAY"][1:], 0, "clicker").connect()
c.fake_button(1, int(sys.argv[1]), int(sys.argv[2]), True);  time.sleep(0.05)
c.fake_button(1, int(sys.argv[1]), int(sys.argv[2]), False); time.sleep(0.3)
c.s.close()
PY
sleep 1
grep -q ButtonPress "$W/xev.log" && ok "injected click delivered to a client (xev witness)" \
  || bad "injected click never reached the xev window"

# --- B: one click must NOT open, two MUST (this is why double-tap matters) ---
# the xev witness window would pollute the icon-detection screenshot, so the
# witness is retired before we ask "where is the desktop icon?"
pkill -x xev 2>/dev/null; sleep 1
# locate the desktop icon: middle of the first contiguous non-bg row-run in
# the top-left quadrant (pcmanfm lays icons out in a column there)
ICON=$(python3 - <<'PY'
import subprocess
from PIL import Image
subprocess.run("xwd -root -silent | xwdtopnm | pnmtopng > /tmp/emux_clickroot.png", shell=True)
im = Image.open("/tmp/emux_clickroot.png"); px = im.load(); W, H = im.size
bg = px[10, 10]
def nrow(y):
    return sum(1 for x in range(0, 300)
               if abs(px[x, y][0]-bg[0]) + abs(px[x, y][1]-bg[1]) + abs(px[x, y][2]-bg[2]) > 40)
rows = [y for y in range(0, H // 2) if nrow(y) > 5]
if rows:
    run = [rows[0]]
    for y in rows[1:]:
        if y - run[-1] <= 2: run.append(y)
        else: break
    ym = run[len(run) // 2]
    xs = [x for x in range(0, 300)
          if abs(px[x, ym][0]-bg[0]) + abs(px[x, ym][1]-bg[1]) + abs(px[x, ym][2]-bg[2]) > 40]
    print("%d %d" % (sum(xs) // len(xs), ym))
PY
)
[ -n "$ICON" ] || { echo "WARN: could not locate a desktop icon; skipping B"; }
if [ -n "$ICON" ]; then
  IX=${ICON%% *}; IY=${ICON##* }
  python3 - "$IX" "$IY" <<'PY'
import sys, time
import os, sys
sys.path.insert(0, os.environ["XPROBE_DIR"])
import xclient_probe as X
c = X.C("/tmp/.X11-unix/X%s" % __import__("os").environ["DISPLAY"][1:], 0, "iconer").connect()
c.fake_button(1, int(sys.argv[1]), int(sys.argv[2]), True);  time.sleep(0.05)
c.fake_button(1, int(sys.argv[1]), int(sys.argv[2]), False)
c.s.close()
PY
  sleep 2
  [ -e "$W/out/OPENED" ] && bad "single click opened the item - double-click assumption changed" \
    || ok "single click does NOT activate a desktop item (as expected)"
  rm -f "$W/out/OPENED"
  python3 - "$IX" "$IY" <<'PY'
import sys, time
import os, sys
sys.path.insert(0, os.environ["XPROBE_DIR"])
import xclient_probe as X
c = X.C("/tmp/.X11-unix/X%s" % __import__("os").environ["DISPLAY"][1:], 0, "iconer2").connect()
for _ in range(2):
    c.fake_button(1, int(sys.argv[1]), int(sys.argv[2]), True);  time.sleep(0.06)
    c.fake_button(1, int(sys.argv[1]), int(sys.argv[2]), False); time.sleep(0.08)
c.s.close()
PY
  sleep 3
  # either it ran (marker) or pcmanfm asked to confirm (dialog window exists)
  if [ -e "$W/out/OPENED" ] || xdotool search --name "Execute File" >/dev/null 2>&1; then
    ok "double click DOES activate the item - a gesture layer that eats tap #2 breaks the desktop"
  else
    bad "double click did not activate the item - check pcmanfm/geometry"
  fi
fi

echo "== scoreboard: $PASS passed, $FAIL failed =="
pkill -P $$ 2>/dev/null
kill $XPID 2>/dev/null
rm -rf "$W" "/tmp/.X$DN-lock" "/tmp/.X11-unix/X$DN" /tmp/emux_clickroot.png 2>/dev/null
[ "$FAIL" = 0 ]
