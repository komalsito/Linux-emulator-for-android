#!/bin/sh
# A/B proof for the field report "I can't see the desktop".
#
#   A = v0.2.58 behaviour: bare `openbox`
#   B = v0.2.59 behaviour: `openbox --sm-disable --startup "/bin/sh <autostart>"`
#
# Per openbox.org/help/Autostart: "When you run the openbox command on its own,
# the autostart scripts will not run." This measures that claim and its visual
# consequence instead of trusting it.
#
# v0.2.60: each trial HOME is now seeded with the WHOLE seed_desktop_config
# (extracted from the shipped emux-x11), not just the autostart block, and the
# wallpaper asset is installed. That matters because the trial HOMEs used to
# have no pcmanfm configuration at all, so pcmanfm painted its own default
# black over the root and trial B measured black for a reason that has nothing
# to do with the autostart question under test. See desktop_paint_test.sh for
# the pcmanfm side of that story.
#
# Three independent pieces of evidence per trial:
#   1. a marker file the autostart itself touches  (did it execute at all?)
#   2. the process list, after killing stale furniture first (no false positives
#      from a tint2 left over on some other display by an earlier probe)
#   3. a pixel census of the ROOT WINDOW via xwd (what the user actually sees)
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
export LANG=C.UTF-8 LC_ALL=C.UTF-8
EX="$HERE/../../app/src/main/assets/bin/emux-x11"
WALL_SRC="$HERE/../../app/src/main/assets/wallpaper/wall.png"
PASS=0; FAIL=0

# The autostart under test is the one emux-x11 SEEDS, so validate the generator
# first - otherwise this harness silently tests a stale file from an earlier run.
sh "$HERE/seed_autostart_test.sh" >/tmp/seed_out.txt 2>&1 || {
  echo "seed_autostart_test.sh failed:"; tail -20 /tmp/seed_out.txt; exit 1; }
grep -qE "passed=[0-9]+ failed=0" /tmp/seed_out.txt || {
  echo "seed_autostart_test.sh reported failures:"; tail -20 /tmp/seed_out.txt; exit 1; }
[ -f /tmp/ob1/.config/openbox/autostart ] || { echo "seed harness produced no autostart"; exit 1; }

# The full seeder, extracted by marker (never by line number) from the shipped
# guest script, so the trial HOMEs match a real device.
SEEDFN=/tmp/ab_seedfn.sh
awk '/^seed_desktop_config\(\) \{/{f=1} f{print} f&&/^\}/{exit}' "$EX" > $SEEDFN
grep -q "^seed_desktop_config" $SEEDFN || { echo "cannot extract seed_desktop_config"; exit 1; }
if [ -e "$WALL_SRC" ]; then
  mkdir -p /usr/share/emux && cp "$WALL_SRC" /usr/share/emux/wall.png 2>/dev/null
  echo "wallpaper asset installed at /usr/share/emux/wall.png"
else
  echo "NOTE: wallpaper asset missing at $WALL_SRC (trials will use the solid fallback)"
fi

# pgrep -x MATCHES ZOMBIES, and this container's PID 1 does not reap, so a
# tint2 killed by an earlier probe still shows up as a "running" panel. Count
# only processes whose /proc state is not Z. (Same trap as probe/sweep_test.sh.)
alive() {
  n=0
  for p in $(pgrep -x "$1" 2>/dev/null); do
    st=$(sed 's/.*) //' /proc/$p/stat 2>/dev/null | cut -d' ' -f1)
    [ -n "$st" ] && [ "$st" != Z ] && n=$((n+1))
  done
  echo $n
}

# Emits "distinct blackpct" for the root window, sampling every 7th pixel.
census() {
  DISPLAY=$1 xwd -root -silent 2>/dev/null | xwdtopnm 2>/dev/null | python3 -c "
import sys
from collections import Counter
d=sys.stdin.buffer.read()
i=d.index(b'255\n'); i=d.index(b'\n',i+1)+1
hdr=d[:i].decode().split(); w,h=int(hdr[1]),int(hdr[2]); px=d[i:]
c=Counter()
for y in range(h):
    row=px[y*w*3:(y+1)*w*3]
    for x in range(0,w*3,3*7): c[(row[x],row[x+1],row[x+2])]+=1
tot=sum(c.values())
sys.stderr.write('      root is %dx%d, %d distinct colours\n'%(w,h,len(c)))
for col,cnt in c.most_common(4):
    sys.stderr.write('        #%02x%02x%02x  %5.1f%%\n'%(col[0],col[1],col[2],100.0*cnt/tot))
print('%d %.1f'%(len(c),100.0*c.get((0,0,0),0)/tot))
"
}

check() {
  if [ "$2" = 1 ]; then PASS=$((PASS+1)); printf '  PASS  %s\n' "$1"
  else FAIL=$((FAIL+1)); printf '  FAIL  %s   (%s)\n' "$1" "$3"; fi
}

trial() {  # $1=label $2=display $3=bare|startup
  L=$1; D=$2; MODE=$3
  export DISPLAY=127.0.0.1:$D
  # Kill furniture from any earlier trial/probe so pgrep cannot lie.
  pkill -x tint2 2>/dev/null; pkill -x pcmanfm 2>/dev/null; pkill -x openbox 2>/dev/null
  sleep 0.5
  rm -f /tmp/.X$D-lock /tmp/autostart_ran_$D; mkdir -p /tmp/.X11-unix
  Xvfb :$D -screen 0 720x1280x24 -listen tcp -ac -nolisten unix >/dev/null 2>&1 &
  XV=$!
  i=0; while [ $i -lt 100 ]; do [ -e /tmp/.X$D-lock ] && break; i=$((i+1)); sleep 0.1; done
  sleep 0.7

  export HOME=/tmp/hb_$D
  rm -rf $HOME; mkdir -p $HOME/.config/openbox
  # Seed exactly what emux-x11 seeds on a device: openbox rc/menu/theme, tint2,
  # the pcmanfm rice, and the autostart under test.
  ( HOME=$HOME; export HOME; . $SEEDFN; seed_desktop_config ) >/dev/null 2>&1
  # Prepend a marker line: proof of execution that does not depend on ps output.
  { echo "touch /tmp/autostart_ran_$D"; cat "$HOME/.config/openbox/autostart"; } > /tmp/ab_as_$D
  cp /tmp/ab_as_$D "$HOME/.config/openbox/autostart"
  sh -n $HOME/.config/openbox/autostart || { echo "  autostart is not valid shell"; }

  echo "=================== $L ==================="
  if [ "$MODE" = bare ]; then
    echo "  cmd: openbox"
    nohup openbox >/tmp/wm_$D.log 2>&1 </dev/null &
  else
    echo "  cmd: openbox --sm-disable --startup \"/bin/sh \$HOME/.config/openbox/autostart\""
    nohup openbox --sm-disable --startup "/bin/sh $HOME/.config/openbox/autostart" >/tmp/wm_$D.log 2>&1 </dev/null &
  fi
  sleep 8
  OBX_ALIVE=$(alive openbox); TINT_ALIVE=$(alive tint2); PF_ALIVE=$(alive pcmanfm)
  [ "$OBX_ALIVE" -gt 0 ] && echo "  openbox:            RUNNING" || echo "  openbox:            DEAD"
  if [ -e /tmp/autostart_ran_$D ]; then
    echo "  autostart executed: YES  (marker file written by the script itself)"
    RAN=1
  else
    echo "  autostart executed: NO   (marker file never appeared)"
    RAN=0
  fi
  [ "$TINT_ALIVE" -gt 0 ] && echo "  tint2 panel:        RUNNING" || echo "  tint2 panel:        absent (no live process)"
  [ "$PF_ALIVE"  -gt 0 ] && echo "  pcmanfm --desktop:  RUNNING" || echo "  pcmanfm --desktop:  absent (no live process)"
  echo "  what the user sees:"
  set -- $(census 127.0.0.1:$D)
  DIST=$1; BLACK=$2
  echo "      PURE BLACK: $BLACK% of sampled pixels ($DIST distinct)"
  echo
  TRIAL_RAN=$RAN; TRIAL_TINT=$TINT_ALIVE; TRIAL_DIST=$DIST; TRIAL_BLACK=$BLACK

  pkill -x tint2 2>/dev/null; pkill -x pcmanfm 2>/dev/null; pkill -x openbox 2>/dev/null
  kill $XV 2>/dev/null; sleep 0.5; rm -f /tmp/.X$D-lock
}

trial "A - v0.2.58 as shipped: bare openbox" 92 bare
check "A: openbox itself is running" "$([ "$OBX_ALIVE" -gt 0 ] && echo 1 || echo 0)" "openbox dead"
check "A: autostart NEVER executes (the bug)" "$([ "$TRIAL_RAN" = 0 ] && echo 1 || echo 0)" "marker appeared"
check "A: root is pure black (nothing painted it)" \
  "$(python3 -c "import sys;sys.exit(0 if float('$TRIAL_BLACK')>99 else 1)" && echo 1 || echo 0)" "black=$TRIAL_BLACK%"
echo

trial "B - v0.2.59+ fix: openbox --startup autostart" 93 startup
check "B: openbox is running" "$([ "$OBX_ALIVE" -gt 0 ] && echo 1 || echo 0)" "openbox dead"
check "B: autostart executed" "$([ "$TRIAL_RAN" = 1 ] && echo 1 || echo 0)" "no marker"
check "B: tint2 panel started" "$([ "$TRIAL_TINT" -gt 0 ] && echo 1 || echo 0)" "panel absent"
check "B: root is NOT black" \
  "$(python3 -c "import sys;sys.exit(0 if float('$TRIAL_BLACK')<1 else 1)" && echo 1 || echo 0)" "black=$TRIAL_BLACK%"
check "B: root has real content (>50 distinct colours)" \
  "$([ "$TRIAL_DIST" -gt 50 ] && echo 1 || echo 0)" "distinct=$TRIAL_DIST"
echo

echo "=================== CONCLUSION ==================="
echo "  A reproduces the reported symptom: Openbox runs, but autostart never"
echo "  executes, so nothing paints the root and the pane is 100% black."
echo "  B runs the documented session path: autostart executes, the root is"
echo "  painted and the panel renders."
echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" = 0 ]
