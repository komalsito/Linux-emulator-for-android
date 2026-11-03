#!/bin/sh
# v0.2.78 - regression tests for the click-swallow fix and the stray-Xvfb fix.
#
# Three suites, all running the REAL functions out of assets/bin/emux-x11 (they
# are extracted by marker, not copied, so a change to the launcher cannot leave
# the test passing against stale logic):
#
#   rc.xml    - the stamp/backup/audit rules, including "upgrade our own old
#               file in place" vs "back up a foreign one"
#   ladder    - LIVE: break a real Openbox session the way v0.2.51..77 did,
#               publish the app's verdict, and measure with button_probe.py
#               that stage 1 and stage 2 each actually restore ButtonPress
#   xvfb      - retire_own_xvfb kills our own attempt that is NOT serving and
#               never touches one that IS (v0.2.13 "adopt, don't fight")
#
# Needs: Xvfb, openbox, python3 (ladder suite). Missing tools skip, not fail.
# Usage: sh tools/x11probe/ladder_test.sh          (from android-emux/)
set -u
cd "$(dirname "$0")/../.." || exit 2
BIN=app/src/main/assets/bin/emux-x11
PROBE=tools/x11probe/button_probe.py
FIXTURE=tools/x11probe/rc_v0.2.77.xml
DISTRO=/etc/xdg/openbox/rc.xml
T=$(mktemp -d /tmp/emux-ladder-test.XXXXXX) || exit 2
pass=0; fail=0; skip=0
ck() { # ck <label> <want> <got>
  if [ "$2" = "$3" ]; then pass=$((pass + 1)); echo "  ok    $1"
  else fail=$((fail + 1)); echo "  FAIL  $1"; echo "          want [$2]"; echo "          got  [$3]"; fi
}
sk() { skip=$((skip + 1)); echo "  skip  $1"; }

# ---------------------------------------------------------------- extraction
extract() { # extract <start-marker> <end-marker> <outfile> [dedent]
  s=$(grep -n "^$1" "$BIN" | head -1 | cut -d: -f1)
  e=$(grep -n "^$2" "$BIN" | head -1 | cut -d: -f1)
  [ -n "$s" ] && [ -n "$e" ] && [ "$e" -gt "$s" ] || { echo "extract: markers not found ($1)"; exit 2; }
  if [ "${4:-0}" = 1 ]; then sed -n "${s},$((e - 1))p" "$BIN" | sed 's/^  //' > "$3"
  else sed -n "${s},$((e - 1))p" "$BIN" > "$3"; fi
  sh -n "$3" || { echo "extract: $3 does not parse"; exit 2; }
}
extract 'EMUX_RCXML_STAMP=' '# v0\.2\.59 FIELD FIX' "$T/rc.sh"
extract '  XVFB_PID=""; XVFB_PID_DN=""' '  XTRY=0' "$T/xvfb.sh" 1
. "$T/rc.sh"
. "$T/xvfb.sh"
running() { pgrep -x "$1" >/dev/null 2>&1; }   # launcher-provided in the real script
# v0.2.79: the ASSERTIONS below must not use `running`/pgrep. pgrep -x matches
# ZOMBIES, and PID 1 in this container is a node process that never reaps, so
# every Xvfb/openbox an earlier harness killed stays visible forever — which made
# "stage 2 removed the grabber" FAIL against a WM that was in fact dead, and could
# equally make "openbox came up" PASS against one that never started. Same helper
# and same reasoning as desktop_paint_test.sh / desktop_visible_ab.sh /
# doctor_test.sh; `running()` above stays as it is because it mirrors the
# launcher's own code, which is what this harness is testing.
alive() {
  n=0
  for p in $(pgrep -x "$1" 2>/dev/null); do
    st=$(sed 's/.*) //' /proc/$p/stat 2>/dev/null | cut -d' ' -f1)
    [ -n "$st" ] && [ "$st" != Z ] && n=$((n+1))
  done
  echo $n
}
sorted() { tr ' ' '\n' | grep . | sort | tr '\n' ' ' | sed 's/ $//'; }

have() { command -v "$1" >/dev/null 2>&1; }

# ============================================================== 1. rc.xml ==
echo
echo "== suite 1: rc.xml stamp / backup / audit =="
rhome() { rm -rf "$T/H"; mkdir -p "$T/H/.config/openbox"; HOME=$T/H; export HOME; }

rhome; rm -rf "$T/H/.config"; write_rc_xml >/dev/null 2>&1
O="$HOME/.config/openbox/rc.xml"
ck "fresh device: file written" "yes" "$([ -e "$O" ] && echo yes || echo no)"
ck "fresh device: stamp present" "yes" "$(grep -qF "$EMUX_RCXML_STAMP" "$O" && echo yes || echo no)"
ck "fresh device: no grab contexts" "" "$(rc_xml_grab_contexts "$O")"
ck "fresh device: no Frame context at all" "no" "$(grep -q 'name="Frame"' "$O" && echo yes || echo no)"
ck "fresh device: followMouse replaces it" "yes" "$(grep -q '<followMouse>yes' "$O" && echo yes || echo no)"
ck "fresh device: no stray backup/tmp" "no no" \
   "$([ -e "$O.emux-bak" ] && echo yes || echo no) $([ -e "$O.emux-tmp" ] && echo yes || echo no)"
ck "effective file is ours" "$O" "$(rc_xml_effective)"

M1=$(stat -c %Y "$O" 2>/dev/null || stat -f %m "$O"); sleep 1.1
write_rc_xml >/dev/null 2>&1
M2=$(stat -c %Y "$O" 2>/dev/null || stat -f %m "$O")
ck "second run is a no-op (idempotent)" "$M1" "$M2"

if [ -e "$FIXTURE" ]; then
  rhome; cp "$FIXTURE" "$O"
  ck "v0.2.77 fixture audits as Frame" "Frame" "$(rc_xml_grab_contexts "$O")"
  write_rc_xml >/dev/null 2>&1
  ck "v0.2.77 -> v0.2.78: now clean" "" "$(rc_xml_grab_contexts "$O")"
  ck "our own old file: rewritten in place, NOT backed up" "no" \
     "$([ -e "$O.emux-bak" ] && echo yes || echo no)"
else
  sk "v0.2.77 fixture missing ($FIXTURE)"
fi

if [ -r "$DISTRO" ]; then
  rhome; cp "$DISTRO" "$O"
  ck "distro default audits as Client+Desktop+Frame" "Client Desktop Frame" \
     "$(rc_xml_grab_contexts "$O" | sorted)"
  write_rc_xml 2>"$T/err"
  ck "foreign file: now clean" "" "$(rc_xml_grab_contexts "$O")"
  ck "foreign file: backed up byte-identical" "yes" \
     "$(cmp -s "$DISTRO" "$O.emux-bak" && echo yes || echo no)"
  ck "foreign file: user told" "yes" "$(grep -q 'replaced a foreign rc.xml' "$T/err" && echo yes || echo no)"
  echo "<!-- MY BACKUP -->" > "$O.emux-bak"
  cp "$DISTRO" "$O"; write_rc_xml >/dev/null 2>&1
  ck "a user's backup is never clobbered" "<!-- MY BACKUP -->" "$(cat "$O.emux-bak")"
else
  sk "no distro rc.xml to test against ($DISTRO)"
fi

# A FILE where a directory must be. Not `: >` - `:` is a POSIX *special*
# builtin, so a redirection error on it exits the shell outright and the rest
# of the suite silently never runs.
rhome; rmdir "$HOME/.config/openbox"; printf '' > "$HOME/.config/openbox"
write_rc_xml >/dev/null 2>"$T/err2"; RC=$?
ck "unwritable: returns failure" "1" "$RC"
ck "unwritable: says CANNOT WRITE" "yes" "$(grep -q 'CANNOT WRITE' "$T/err2" && echo yes || echo no)"
ck "unwritable: names the consequence" "yes" "$(grep -q 'distro default' "$T/err2" && echo yes || echo no)"
ck "missing file: audit does not crash" "" "$(rc_xml_grab_contexts /nonexistent/rc.xml)"

# ============================================================== 2. ladder ==
echo
echo "== suite 2: escalation ladder, live Openbox + wire-level probe =="
D=88; P=6088
if have Xvfb && have openbox && have python3 && [ -e "$FIXTURE" ] && [ -e "$PROBE" ]; then
  HOME=$T/H; export HOME
  rm -rf "$T/H"; mkdir -p "$T/H/.config/openbox"; cp "$FIXTURE" "$T/H/.config/openbox/rc.xml"
  rm -f /tmp/emux_btn_verdict /tmp/emux_btn_action /tmp/emux_btn_stages \
        /tmp/emux_wm_mode /tmp/emux_wm_fellback /tmp/emux_rcxml_grabs
  Xvfb :$D -screen 0 1280x720x24 -listen tcp >"$T/xvfb.log" 2>&1 & XV=$!
  i=0; while [ ! -S /tmp/.X11-unix/X$D ] && [ "$i" -lt 40 ]; do sleep 0.1; i=$((i + 1)); done
  DISPLAY=:$D openbox >"$T/ob.log" 2>&1 & sleep 1.2
  ck "openbox came up" "yes" "$([ "$(alive openbox)" -gt 0 ] && echo yes || echo no)"

  verdict() { DISPLAY=:$D python3 "$PROBE" $P 2>&1 | grep -oE 'DELIVERED|SWALLOWED|CANNOT-RUN' | head -1; }
  ck "baseline: broken config swallows the click" "SWALLOWED" "$(verdict)"

  cat > "$T/watch.sh" <<INNER
#!/bin/sh
HOME=$T/H; export HOME; DISPLAY=:$D; export DISPLAY
running() { pgrep -x "\$1" >/dev/null 2>&1; }
. "$T/rc.sh"
btn_watch
INNER
  sh "$T/watch.sh" >"$T/watch.log" 2>&1 & WP=$!
  sleep 0.5

  echo "$(date +%s) dead btn=0 motion=1" > /tmp/emux_btn_verdict
  i=0; while [ ! -s /tmp/emux_btn_action ] && [ "$i" -lt 40 ]; do sleep 0.5; i=$((i + 1)); done
  ck "stage 1 acted" "stage1" "$(grep -o 'stage1' /tmp/emux_btn_action | head -1)"
  ck "stage 1 consumed the verdict" "no" "$([ -e /tmp/emux_btn_verdict ] && echo yes || echo no)"
  ck "stage 1 stamped rc.xml in force" "yes" \
     "$(grep -qF "$EMUX_RCXML_STAMP" "$T/H/.config/openbox/rc.xml" && echo yes || echo no)"
  ck "stage 1 published grabs=none" "none" "$(cat /tmp/emux_rcxml_grabs 2>/dev/null)"
  sleep 1.0
  ck "STAGE 1 RESTORED THE CLICK (live session, no restart)" "DELIVERED" "$(verdict)"

  cp "$FIXTURE" "$T/H/.config/openbox/rc.xml"; DISPLAY=:$D openbox --reconfigure 2>/dev/null; sleep 1.0
  ck "re-broken to prove stage 2 is a different action" "SWALLOWED" "$(verdict)"

  echo "$(date +%s) dead btn=0 motion=1" > /tmp/emux_btn_verdict
  i=0; while ! grep -q 'stage2' /tmp/emux_btn_action 2>/dev/null && [ "$i" -lt 60 ]; do sleep 0.5; i=$((i + 1)); done
  ck "stage 2 acted" "stage2" "$(grep -o 'stage2' /tmp/emux_btn_action | head -1)"
  ck "stage 2 removed the grabber" "no" "$([ "$(alive openbox)" -gt 0 ] && echo yes || echo no)"
  sleep 1.0
  ck "STAGE 2 RESTORED THE CLICK" "DELIVERED" "$(verdict)"
  ck "wm_mode published" "$(cat /tmp/emux_wm_mode 2>/dev/null)" "$(cat /tmp/emux_wm_mode 2>/dev/null)"

  echo "$(date +%s) dead btn=0 motion=1" > /tmp/emux_btn_verdict; sleep 3
  ck "WM switch is once per session" "yes" \
     "$(grep -q 'already applied this session' "$T/watch.log" && echo yes || echo no)"

  kill $WP 2>/dev/null; pkill -x openbox 2>/dev/null; pkill -x twm 2>/dev/null
  kill $XV 2>/dev/null; rm -f /tmp/.X$D-lock /tmp/.X11-unix/X$D
else
  sk "ladder suite needs Xvfb + openbox + python3 + $FIXTURE"
fi

# ============================================================== 3. xvfb ====
echo
echo "== suite 3: retire_own_xvfb (the stray-on-:0 report) =="
MARK=/tmp/emux_sess_marker
XVFB_PID=""; XVFB_PID_DN=""
retire_own_xvfb 2>"$T/x0.log"
ck "no previous attempt: silent no-op" "0" "$?"
ck "no previous attempt: said nothing" "0" "$(wc -l < "$T/x0.log" | tr -d ' ')"

sleep 0.1 & DEAD=$!; kill -9 $DEAD 2>/dev/null; wait $DEAD 2>/dev/null
XVFB_PID=$DEAD; XVFB_PID_DN=89
retire_own_xvfb 2>/dev/null
ck "a pid that already died is just forgotten" "" "$XVFB_PID"

if have Xvfb; then
  printf '' > $MARK; sleep 0.2
  Xvfb :89 -screen 0 320x200x24 -listen tcp >"$T/x89.log" 2>&1 & P1=$!
  i=0; while [ ! -S /tmp/.X11-unix/X89 ] && [ "$i" -lt 40 ]; do sleep 0.1; i=$((i + 1)); done
  ck "serving server: socket is fresh" "yes" \
     "$([ /tmp/.X11-unix/X89 -nt $MARK ] && echo yes || echo no)"
  XVFB_PID=$P1; XVFB_PID_DN=89
  retire_own_xvfb 2>"$T/x1.log"
  ck "SERVING server is kept (adopt, don't fight)" "yes" "$(kill -0 $P1 2>/dev/null && echo yes || echo no)"
  ck "serving server: says why" "yes" "$(grep -q 'fresh unix socket' "$T/x1.log" && echo yes || echo no)"
  ck "serving server: pid released" "" "$XVFB_PID"
  kill -9 $P1 2>/dev/null; rm -f /tmp/.X89-lock /tmp/.X11-unix/X89

  printf '' > $MARK; sleep 0.2
  Xvfb :91 -screen 0 320x200x24 >"$T/x91.log" 2>&1 & P3=$!   # no tcp listener
  i=0; while [ ! -S /tmp/.X11-unix/X91 ] && [ "$i" -lt 40 ]; do sleep 0.1; i=$((i + 1)); done
  XVFB_PID=$P3; XVFB_PID_DN=91
  retire_own_xvfb 2>/dev/null
  ck "socket rule alone protects a server (no nc needed)" "yes" \
     "$(kill -0 $P3 2>/dev/null && echo yes || echo no)"
  touch -d '1970-01-02' /tmp/.X11-unix/X91 2>/dev/null
  XVFB_PID=$P3; XVFB_PID_DN=91
  retire_own_xvfb 2>/dev/null
  ck "a STALE socket does not protect a stray" "no" "$(kill -0 $P3 2>/dev/null && echo yes || echo no)"
  kill -9 $P3 2>/dev/null; rm -f /tmp/.X91-lock /tmp/.X11-unix/X91
else
  sk "Xvfb not installed"
fi

rm -f /tmp/.X11-unix/X90 /tmp/.X90-lock 2>/dev/null
sleep 300 & P2=$!                       # alive, but serving nothing on :90
XVFB_PID=$P2; XVFB_PID_DN=90
retire_own_xvfb 2>"$T/x2.log"
ck "ALIVE-BUT-NOT-SERVING attempt is retired (the stray)" "no" \
   "$(kill -0 $P2 2>/dev/null && echo yes || echo no)"
ck "stray: says why" "yes" "$(grep -q 'retiring our own unverified Xvfb' "$T/x2.log" && echo yes || echo no)"
ck "stray: names the display it was on" "yes" "$(grep -q ':90' "$T/x2.log" && echo yes || echo no)"
ck "stray: pid released" "" "$XVFB_PID"
kill -9 $P2 2>/dev/null

rm -f /tmp/emux_btn_verdict /tmp/emux_btn_action /tmp/emux_btn_stages \
      /tmp/emux_wm_mode /tmp/emux_wm_fellback /tmp/emux_rcxml_grabs 2>/dev/null
rm -rf "$T"
echo
echo "passed=$pass failed=$fail skipped=$skip"
[ "$fail" -eq 0 ]
