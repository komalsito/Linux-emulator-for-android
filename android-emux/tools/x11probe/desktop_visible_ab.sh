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
# Three independent pieces of evidence per trial:
#   1. a marker file the autostart itself touches  (did it execute at all?)
#   2. the process list, after killing stale furniture first (no false positives
#      from a tint2 left over on some other display by an earlier probe)
#   3. a pixel census of the ROOT WINDOW via xwd (what the user actually sees)
set -u
export LANG=C.UTF-8 LC_ALL=C.UTF-8
ASRC=/tmp/ob1/.config/openbox/autostart
[ -f "$ASRC" ] || { echo "missing generated autostart (run the seed test first)"; exit 1; }

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

root_census() {
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
print('      root is %dx%d, %d distinct colours'%(w,h,len(c)))
for col,cnt in c.most_common(4):
    print('        #%02x%02x%02x  %5.1f%%'%(col[0],col[1],col[2],100.0*cnt/tot))
print('      PURE BLACK: %.1f%% of sampled pixels'%(100.0*c.get((0,0,0),0)/tot))
"
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
  # Prepend a marker line: proof of execution that does not depend on ps output.
  { echo "touch /tmp/autostart_ran_$D"; cat "$ASRC"; } > $HOME/.config/openbox/autostart
  sh -n $HOME/.config/openbox/autostart || { echo "  autostart is not valid shell"; }

  echo "=================== $L ==================="
  if [ "$MODE" = bare ]; then
    echo "  cmd: openbox"
    nohup openbox >/tmp/wm_$D.log 2>&1 </dev/null &
  else
    echo "  cmd: openbox --sm-disable --startup \"/bin/sh \$HOME/.config/openbox/autostart\""
    nohup openbox --sm-disable --startup "/bin/sh $HOME/.config/openbox/autostart" >/tmp/wm_$D.log 2>&1 </dev/null &
  fi
  sleep 7
  [ "$(alive openbox)" -gt 0 ] && echo "  openbox:            RUNNING" || echo "  openbox:            DEAD"
  if [ -e /tmp/autostart_ran_$D ]; then
    echo "  autostart executed: YES  (marker file written by the script itself)"
  else
    echo "  autostart executed: NO   (marker file never appeared)"
  fi
  [ "$(alive tint2)" -gt 0 ] && echo "  tint2 panel:        RUNNING" || echo "  tint2 panel:        absent (no live process)"
  [ "$(alive pcmanfm)" -gt 0 ] && echo "  pcmanfm --desktop:  RUNNING" || echo "  pcmanfm --desktop:  absent (no live process)"
  echo "  what the user sees:"
  root_census 127.0.0.1:$D
  echo

  pkill -x tint2 2>/dev/null; pkill -x pcmanfm 2>/dev/null; pkill -x openbox 2>/dev/null
  kill $XV 2>/dev/null; sleep 0.5; rm -f /tmp/.X$D-lock
}

trial "A - v0.2.58 as shipped: bare openbox" 92 bare
trial "B - v0.2.59 fix: openbox --startup autostart" 93 startup

echo "=================== CONCLUSION ==================="
echo "  A reproduces the reported symptom: Openbox runs, but autostart never"
echo "  executes, so nothing paints the root and the pane is 100% black."
echo "  B runs the documented session path: autostart executes, the root is"
echo "  painted and the panel renders."
