#!/bin/sh
# button_delivery_test.sh - reproduce the v0.2.78 click-swallow bug and prove the fix.
#
# THE QUESTION: an injected click measures "ok" against a motion-only proof, yet
# nothing on the desktop opens. Which window-manager configuration actually
# delivers a ButtonPress to an application window?
#
# THE EXPERIMENT (this is the field test, scripted): for each configuration,
# start Xvfb, start an application window, optionally start Openbox with a chosen
# rc.xml, aim the pointer INSIDE that window, inject a left click through XTEST,
# and check whether the ButtonPress was ever delivered.
#
# Two instruments, because they answer two different questions:
#   xev    - the field's own instrument (xdotool mousemove + click, xev reports).
#   probe  - tools/x11probe/button_probe.py, the wire-level twin of
#            X11Client.probeButtonDelivery(): it creates and aims its OWN window
#            and can also send XTEST GrabControl(impervious), so it validates the
#            byte layouts the Kotlin probe uses and the ladder's stage 2.
#
# Configurations (see the printed matrix):
#   A  no window manager at all              control: what "killing Openbox" does
#   B  Openbox + the DISTRO DEFAULT rc.xml   /etc/xdg/openbox/rc.xml
#   C  Openbox + EmuX rc.xml <= v0.2.77      binds Left/Press in the FRAME context
#   D  Openbox + EmuX rc.xml v0.2.78         no Frame/Client/Desktop bindings
#   E  C with the Frame context stripped     isolates which binding is fatal
#   F  C, measured by the in-app probe       the probe sees the same swallow
#   G  C + XTEST GrabControl(impervious)     ladder stage 2 rescues a bad config
#   H  D, measured by the in-app probe       probe passes on the fixed config
#
# Usage:  sh tools/x11probe/button_delivery_test.sh [display-number]
# Needs:  Xvfb, openbox, xdotool, xev + xwininfo (x11-utils), python3. ~40 s.
# Read-only against the repo; writes only under $TMPDIR.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
PROJ=$(cd "$HERE/../.." && pwd)
DN=${1:-77}
DISPLAY=":$DN"
XP=$((6000 + DN))
WORK=${TMPDIR:-/tmp}/emux-bttest.$$
mkdir -p "$WORK"
PIDS=""
HN=0

for b in Xvfb openbox xdotool xev xwininfo python3; do
  command -v "$b" >/dev/null 2>&1 || { echo "MISSING: $b (install xvfb openbox xdotool x11-utils)"; exit 2; }
done

stop_x() {
  for p in $PIDS; do kill -9 "$p" 2>/dev/null; done
  PIDS=""
  # Kill any Xvfb serving OUR display by matching argv[0] only - never the whole
  # cmdline, and never $$ (this script's own argv contains ":$DN"; two earlier
  # revisions of this harness killed themselves that way).
  for q in $(ls /proc 2>/dev/null); do
    case "$q" in ''|*[!0-9]*) continue ;; esac
    [ "$q" = "$$" ] && continue
    [ -r "/proc/$q/cmdline" ] || continue
    a=$(tr '\0' '\n' < "/proc/$q/cmdline" 2>/dev/null | head -1)
    case "$a" in
      */Xvfb|Xvfb) tr '\0' ' ' < "/proc/$q/cmdline" 2>/dev/null | grep -q " $DISPLAY " && kill -9 "$q" 2>/dev/null ;;
    esac
  done
  # A SIGKILLed Xvfb leaves its socket AND lock behind - exactly the debris
  # emux-x11's kill_stale_x exists for. Sweep it, or the next case's server exits
  # with "Server is already active for display N" and there is no window to find.
  i=0
  while [ "$i" -lt 30 ]; do
    rm -f "/tmp/.X11-unix/X$DN" "/tmp/.X$DN-lock" 2>/dev/null
    [ -e "/tmp/.X11-unix/X$DN" ] || break
    sleep 0.1; i=$((i + 1))
  done
}
trap 'stop_x; rm -rf "$WORK"' EXIT INT TERM

start_x() { # $1 = rc.xml to install, or "" for no window manager
  HN=$((HN + 1)); H="$WORK/home-$HN"; mkdir -p "$H/.config/openbox"
  [ -n "$1" ] && cp "$1" "$H/.config/openbox/rc.xml"
  # -listen tcp is explicit: Debian builds Xvfb without a tcp listener by
  # default, and button_probe.py connects to 127.0.0.1:$XP.
  Xvfb "$DISPLAY" -screen 0 1024x768x24 -ac -listen tcp >"$WORK/xvfb.log" 2>&1 &
  PIDS="$PIDS $!"
  # Wait for a HANDSHAKE, not for a socket file: a stale X$DN proves nothing
  # (emux-x11 learned that in v0.2.16/v0.2.28, and so must this harness).
  i=0
  while ! DISPLAY="$DISPLAY" xwininfo -root >/dev/null 2>&1 && [ "$i" -lt 80 ]; do sleep 0.25; i=$((i+1)); done
  DISPLAY="$DISPLAY" xwininfo -root >/dev/null 2>&1 || {
    echo "   ($1) Xvfb never answered: $(tail -1 "$WORK/xvfb.log" 2>/dev/null)"; return 1; }
  if [ -n "$1" ]; then
    HOME="$H" DISPLAY="$DISPLAY" openbox --sm-disable >"$WORK/wm.log" 2>&1 &
    PIDS="$PIDS $!"
  fi
  sleep 1.5
  return 0
}

# --- the two rc.xml files under test -------------------------------------
# D: the config assets/bin/emux-x11 writes today (extracted from the heredoc).
# Match the heredoc OPENER, not the path it writes to: the variable name inside
# write_rc_xml() has already changed once (RC -> RCF) and a matcher keyed to it
# silently extracts nothing.
awk '/<<.RCXML.$/{f=1;next} /^RCXML$/{f=0} f' \
  "$PROJ/app/src/main/assets/bin/emux-x11" > "$WORK/rc_new.xml"
[ -s "$WORK/rc_new.xml" ] || { echo "could not extract the current rc.xml from emux-x11"; exit 2; }
# C: what it shipped v0.2.51..v0.2.77 - a checked-in FIXTURE, not git history, so
# this test still runs after the fix is merged, squashed or shallow-cloned.
cp "$HERE/rc_v0.2.77.xml" "$WORK/rc_old.xml" 2>/dev/null
[ -s "$WORK/rc_old.xml" ] || { echo "missing fixture $HERE/rc_v0.2.77.xml"; exit 2; }
# E: C with the Frame context removed - the control that names the culprit.
sed '/<context name="Frame">/,/<\/context>/d' "$WORK/rc_old.xml" > "$WORK/rc_noframe.xml"

echo "== button delivery test on $DISPLAY (work dir $WORK) =="
echo "   openbox $(openbox --version 2>&1 | head -1 | sed 's/.*version //')"
echo

case_xev() { # $1=label $2=config-note $3=rcfile-or-empty
  stop_x
  start_x "$3" || { printf '%-3s %-9s %s\n' "$1" "NO-X" "server did not start"; return; }
  HOME="$WORK" DISPLAY="$DISPLAY" xev > "$WORK/xev.log" 2>&1 &
  PIDS="$PIDS $!"
  sleep 2
  W=$(DISPLAY="$DISPLAY" xdotool search --name "Event Tester" 2>/dev/null | head -1)
  if [ -z "$W" ]; then printf '%-3s %-9s %s\n' "$1" "NO-WIN" "xev's window not found"; return; fi
  # xev reports button events for its INNER window (the small box it draws), so
  # that is what must be aimed at - the honest target: a real client area inside
  # a window the WM has just framed.
  INNER=$(DISPLAY="$DISPLAY" xwininfo -id "$W" -children 2>/dev/null | sed -n 's/^ *\(0x[0-9a-fA-F]*\) .*/\1/p' | head -1)
  T=${INNER:-$W}
  eval "$(DISPLAY="$DISPLAY" xwininfo -id "$T" 2>/dev/null | sed -n \
    's/^[ ]*Absolute upper-left X: *\([0-9-]*\)/WX=\1/p;s/^[ ]*Absolute upper-left Y: *\([0-9-]*\)/WY=\1/p;s/^[ ]*Width: *\([0-9]*\)/WW=\1/p;s/^[ ]*Height: *\([0-9]*\)/WH=\1/p')"
  CX=$(( ${WX:-0} + ${WW:-100} / 2 )); CY=$(( ${WY:-0} + ${WH:-100} / 2 ))
  : > "$WORK/xev.log"          # ignore mapping noise; we want the CLICK
  DISPLAY="$DISPLAY" xdotool mousemove "$CX" "$CY" 2>/dev/null
  sleep 0.4
  DISPLAY="$DISPLAY" xdotool click 1 2>/dev/null
  sleep 1.0
  MOT=$(grep -c MotionNotify "$WORK/xev.log" 2>/dev/null); MOT=${MOT:-0}
  BTN=$(grep -c ButtonPress "$WORK/xev.log" 2>/dev/null); BTN=${BTN:-0}
  PTR=$(DISPLAY="$DISPLAY" xdotool getmouselocation 2>/dev/null | sed -n 's/.*x:\([0-9]*\) y:\([0-9]*\).*/\1,\2/p')
  if [ "${BTN:-0}" -gt 0 ]; then
    printf '%-3s %-9s DELIVERED  ButtonPress=%s MotionNotify=%s ptr=%s aim=%s,%s  [%s]\n' \
      "$1" "OK" "$BTN" "$MOT" "$PTR" "$CX" "$CY" "$2"
  else
    printf '%-3s %-9s SWALLOWED  ButtonPress=0 MotionNotify=%s ptr=%s aim=%s,%s  [%s]\n' \
      "$1" "DEAD" "$MOT" "$PTR" "$CX" "$CY" "$2"
  fi
}

case_probe() { # $1=label $2=config-note $3=rcfile-or-empty $4=extra flags
  stop_x
  start_x "$3" || { printf '%-3s %-9s %s\n' "$1" "NO-X" "server did not start"; return; }
  OUT=$(DISPLAY="$DISPLAY" python3 "$HERE/button_probe.py" "$XP" $4 2>&1)
  RC=$?
  case "$OUT" in
    DELIVERED*) printf '%-3s %-9s %s  [%s]\n' "$1" "OK" "$OUT" "$2" ;;
    SWALLOWED*) printf '%-3s %-9s %s  [%s]\n' "$1" "DEAD" "$OUT" "$2" ;;
    *)          printf '%-3s %-9s probe rc=%s: %s  [%s]\n' "$1" "ERR" "$RC" "$OUT" "$2" ;;
  esac
}

case_xev   A "no window manager"                ""
case_xev   B "distro default rc.xml"            /etc/xdg/openbox/rc.xml
case_xev   C "EmuX rc.xml v0.2.51..v0.2.77"     "$WORK/rc_old.xml"
case_xev   D "EmuX rc.xml v0.2.78"              "$WORK/rc_new.xml"
case_xev   E "C minus the Frame context"        "$WORK/rc_noframe.xml"
case_probe F "C, in-app probe"                  "$WORK/rc_old.xml"      ""
case_probe G "C + GrabControl(impervious)"      "$WORK/rc_old.xml"      "--impervious --pointer-root"
case_probe H "D, in-app probe"                  "$WORK/rc_new.xml"      ""
stop_x

echo
echo "How to read this:"
echo "  * SWALLOWED with MotionNotify>0 is THE field signature - the pointer moved,"
echo "    so XTEST works, and a passive button grab ate the press. Motion and keys"
echo "    are untouched, which is why 'the desktop is visible but I cannot interact"
echo "    with it' survived a motion-only proof for ~25 releases."
echo "  * C vs E names the culprit: a mousebind in the FRAME context makes Openbox"
echo "    XGrabButton() the frame window, which is the PARENT of the client window,"
echo "    and xorg checks passive grabs on every window from the root down"
echo "    (dix/events.c CheckDeviceGrabs) with owner_events=False. Openbox only"
echo "    replays events it grabbed for a CLIENT context (mouse.c: replay_pointer_"
echo "    needed is set for CLIENT_CONTEXT alone), so a Frame grab never gives it"
echo "    back. That is why the distro default (B), which binds Frame only with"
echo "    modifiers, still delivers."
echo "  * G is a NEGATIVE result, and it is the reason the ladder does not contain"
echo "    that step: XTEST GrabControl(impervious) is ACCEPTED by the server (no"
echo "    error, sync ok) and the click is STILL swallowed. Impervious does not"
echo "    rescue a passive Frame grab, so the ladder escalates to what case A proves"
echo "    works - removing the grabber. The primitive stays in X11Client."
echo "    xtestGrabControl() and button_probe.py --impervious as a documented"
echo "    diagnostic: it is the right tool for an ACTIVE grab, not for this."
echo "  * F/H prove the in-app probe (X11Client.probeButtonDelivery) agrees with xev."
