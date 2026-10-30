#!/bin/sh
# Regression harness for `emux-x11 doctor` - the read-only field report.
#
# Three states a device can actually be in, each asserted from the report text:
#   1. healthy desktop  -> "not black", every furniture process with its OWN pid
#   2. the black-desktop bug (pcmanfm's pristine desktop-items-0.conf, which
#      overrides the rice) -> "PURE BLACK" + the items-file problem named
#   3. X not running at all -> "NOTHING ANSWERS", and still exit 0
#
# Plus the two invariants that are easy to break and expensive to notice:
#   - doctor must be READ-ONLY: the session markers it reports must survive it
#     (an earlier version of the script cleared /tmp/emux_wm_launched at startup,
#     which would have made the report lie about a healthy session), and it must
#     not leave its grab file behind;
#   - it must not print shell errors. Two real bugs are guarded here: grepping
#     the whole cmdline made the harness's OWN shell match Xvfb, openbox, tint2
#     and pcmanfm simultaneously (one pid for all four), and splitting "R G B;R
#     G B" with a `for` loop yields one number per iteration, which dash reports
#     as "arithmetic expression: expecting primary".
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
EX="$HERE/../../app/src/main/assets/bin/emux-x11"
WALL_SRC="$HERE/../../app/src/main/assets/wallpaper/wall.png"
PASS=0; FAIL=0
export LANG=C.UTF-8 LC_ALL=C.UTF-8

[ -f "$EX" ] || { echo "FATAL: no emux-x11 at $EX"; exit 1; }
for t in Xvfb openbox xwd xwdtopnm xwininfo; do
  command -v $t >/dev/null 2>&1 || { echo "SKIP: $t not installed"; exit 0; }
done

SEEDFN=/tmp/drt_seedfn.sh
awk '/^seed_desktop_config\(\) \{/{f=1} f{print} f&&/^\}/{exit}' "$EX" > $SEEDFN
grep -q "^seed_desktop_config" $SEEDFN || { echo "FATAL: cannot extract seed_desktop_config"; exit 1; }

check() {
  if [ "$2" = 1 ]; then PASS=$((PASS+1)); printf '  PASS  %s\n' "$1"
  else FAIL=$((FAIL+1)); printf '  FAIL  %s   (%s)\n' "$1" "$3"; fi
}
alive() {
  n=0
  for p in $(pgrep -x "$1" 2>/dev/null); do
    st=$(sed 's/.*) //' /proc/$p/stat 2>/dev/null | cut -d' ' -f1)
    [ -n "$st" ] && [ "$st" != Z ] && n=$((n+1))
  done
  echo $n
}

# start_stack $1=HOME $2=display $3=blackbug(yes|no)
start_stack() {
  H=$1; D=$2
  pkill -x tint2 2>/dev/null; pkill -x pcmanfm 2>/dev/null; pkill -x openbox 2>/dev/null; sleep 0.4
  rm -rf "$H"; mkdir -p "$H"
  if [ -e "$WALL_SRC" ]; then mkdir -p /usr/share/emux && cp "$WALL_SRC" /usr/share/emux/wall.png; fi
  ( HOME="$H"; export HOME; . $SEEDFN; seed_desktop_config ) >/dev/null 2>&1
  if [ "$3" = yes ] && command -v pcmanfm >/dev/null 2>&1; then
    # Exactly what pcmanfm writes on its own first run, i.e. the state every
    # install that opened the desktop before v0.2.60 is in. It overrides the
    # rice, so the desktop is black even though pcmanfm.conf is correct.
    mkdir -p "$H/.config/pcmanfm/default"
    printf '[*]\nwallpaper_mode=color\nwallpaper_common=1\ndesktop_bg=#000000\ndesktop_fg=#ffffff\nshow_wm_menu=0\nsort=mtime;ascending;\n' \
      > "$H/.config/pcmanfm/default/desktop-items-0.conf"
  fi
  rm -f /tmp/.X$D-lock
  Xvfb :$D -screen 0 720x1280x24 -listen tcp -ac -nolisten unix >/tmp/drt_xvfb.log 2>&1 &
  XPID=$!
  i=0; while [ $i -lt 100 ]; do [ -e /tmp/.X$D-lock ] && break; i=$((i+1)); sleep 0.1; done
  sleep 0.7
  echo $D > /tmp/emux_display_num
  export DISPLAY=127.0.0.1:$D HOME="$H"
  rm -f /tmp/emux_x_up /tmp/emux_wm_launched /tmp/emux_root_painted /tmp/emux_doctor_root.ppm
  touch /tmp/emux_x_up /tmp/emux_wm_launched /tmp/emux_root_painted
  nohup openbox --sm-disable --startup "/bin/sh $H/.config/openbox/autostart" >/tmp/drt_wm.log 2>&1 </dev/null &
  sleep 8
}
stop_stack() {
  pkill -x tint2 2>/dev/null; pkill -x pcmanfm 2>/dev/null; pkill -x openbox 2>/dev/null
  [ -n "${XPID:-}" ] && kill $XPID 2>/dev/null
  wait $XPID 2>/dev/null
  rm -f /tmp/.X96-lock /tmp/.X95-lock; sleep 0.5
}
# pid reported for a section, or "-"
pid_of() { echo "$1" | sed -n "s/^  $2: *[^ ]* (pid \([0-9]*\)).*/\1/p" | head -1; }

echo "=== emux-x11 doctor ==="

# ---------------------------------------------------------------- state 1
start_stack /tmp/drt_ok 96 no
OUT=$(sh "$EX" doctor 2>/tmp/drt_err.txt); RC=$?
echo "--- state 1: healthy desktop (report follows)"
echo "$OUT" | sed -n '1,22p' | sed 's/^/    /'
printf '    ... verdict: %s\n' "$(echo "$OUT" | grep -A1 '  verdict:' | tail -1)"
check "exit 0" "$([ $RC = 0 ] && echo 1 || echo 0)" "rc=$RC"
check "no shell errors on stderr" \
  "$([ ! -s /tmp/drt_err.txt ] && echo 1 || echo 0)" "$(head -2 /tmp/drt_err.txt)"
check "reports the display as answering" \
  "$(echo "$OUT" | grep -q 'answering' && echo 1 || echo 0)" "no 'answering' line"
check "root reported as not black" \
  "$(echo "$OUT" | grep -q 'not black' && echo 1 || echo 0)" "$(echo "$OUT" | grep 'verdict:' | head -1)"
check "verdict section says OK" \
  "$(echo "$OUT" | sed -n '/== verdict ==/,$p' | grep -q '^  OK:' && echo 1 || echo 0)" \
  "$(echo "$OUT" | sed -n '/== verdict ==/,$p' | head -3 | tr '\n' ' ')"
PX=$(pid_of "$OUT" Xvfb); PO=$(pid_of "$OUT" openbox); PT=$(pid_of "$OUT" "tint2 panel"); PP=$(pid_of "$OUT" pcmanfm)
check "Xvfb pid reported" "$([ -n "$PX" ] && [ "$PX" != "-" ] && echo 1 || echo 0)" "PX='$PX'"
check "each process has its OWN pid (not one match for all)" \
  "$([ -n "$PO" ] && [ -n "$PT" ] && [ "$PO" != "$PX" ] && { [ -z "$PT" ] || [ "$PT" != "$PO" ]; } && echo 1 || echo 0)" \
  "Xvfb=$PX openbox=$PO tint2=$PT pcmanfm=$PP"
check "pcmanfm really is live (independent of the report)" \
  "$([ "$(alive pcmanfm)" -gt 0 ] && echo 1 || echo 0)" "alive=$(alive pcmanfm)"
check "reports the seeded rice stamp" \
  "$(echo "$OUT" | grep -q 'Emux rice v0.2' && echo 1 || echo 0)" "stamp line missing"
check "READ-ONLY: session markers survived the run" \
  "$([ -e /tmp/emux_wm_launched ] && [ -e /tmp/emux_x_up ] && echo 1 || echo 0)" "markers cleared"
check "READ-ONLY: no grab file left behind" \
  "$([ ! -e /tmp/emux_doctor_root.ppm ] && echo 1 || echo 0)" "/tmp/emux_doctor_root.ppm remains"
stop_stack

# ---------------------------------------------------------------- state 2
if command -v pcmanfm >/dev/null 2>&1; then
  start_stack /tmp/drt_bad 95 yes
  OUT=$(sh "$EX" doctor 2>/tmp/drt_err.txt); RC=$?
  echo "--- state 2: pcmanfm's pristine black desktop-items-0.conf"
  echo "$OUT" | sed -n '/== desktop ==/,/== input ==/p' | sed 's/^/    /'
  check "exit 0" "$([ $RC = 0 ] && echo 1 || echo 0)" "rc=$RC"
  check "no shell errors on stderr" \
    "$([ ! -s /tmp/drt_err.txt ] && echo 1 || echo 0)" "$(head -2 /tmp/drt_err.txt)"
  check "root reported as PURE BLACK" \
    "$(echo "$OUT" | grep -q 'PURE BLACK' && echo 1 || echo 0)" "$(echo "$OUT" | grep 'root colour' )"
  check "names desktop-items-0.conf as the cause" \
    "$(echo "$OUT" | grep -q 'desktop-items-0.conf is still' && echo 1 || echo 0)" "problem not named"
  check "verdict section lists problems" \
    "$(echo "$OUT" | sed -n '/== verdict ==/,$p' | grep -q 'problem(s) found' && echo 1 || echo 0)" "no problem list"
  stop_stack
else
  echo "--- state 2 SKIPPED (pcmanfm not installed)"
fi

# ---------------------------------------------------------------- state 3
pkill -x tint2 2>/dev/null; pkill -x pcmanfm 2>/dev/null; pkill -x openbox 2>/dev/null; sleep 0.4
rm -f /tmp/.X94-lock /tmp/emux_x_up /tmp/emux_wm_launched
echo 94 > /tmp/emux_display_num
OUT=$(HOME=/tmp/drt_ok sh "$EX" doctor 2>/tmp/drt_err.txt); RC=$?
echo "--- state 3: X server not running at all"
echo "$OUT" | sed -n '/== X server ==/,/== desktop ==/p' | sed 's/^/    /'
check "exit 0 even with no X server" "$([ $RC = 0 ] && echo 1 || echo 0)" "rc=$RC"
check "no shell errors on stderr" \
  "$([ ! -s /tmp/drt_err.txt ] && echo 1 || echo 0)" "$(head -2 /tmp/drt_err.txt)"
check "says nothing answers on the display" \
  "$(echo "$OUT" | grep -q 'NOTHING ANSWERS' && echo 1 || echo 0)" "no diagnosis"
check "reports X server as a problem" \
  "$(echo "$OUT" | sed -n '/== verdict ==/,$p' | grep -q 'X server is not answering' && echo 1 || echo 0)" \
  "$(echo "$OUT" | sed -n '/== verdict ==/,$p' | head -3 | tr '\n' ' ')"
rm -f /tmp/.X94-lock

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" = 0 ]
