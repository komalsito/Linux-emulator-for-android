#!/bin/sh
# run.sh - regression-test the REAL desktop cascade scripts in the jail.
#
#   sh tools/jailtest/run.sh [x11-script] [repair-script]
#
# Defaults to the scripts in this tree. Scenarios (field state = ghost xvfb /
# x11 stack in the dpkg db, see mkjail.sh):
#   NEW    : run the given emux-x11 -> MUST purge the ghosts, install the
#            stack, launch the stub Xvfb (X0 socket + xvfb.log) and exit 0.
#   REPAIR : ghost coreutils (marker deleted) -> emux-apt-repair MUST purge
#            the db entry, reinstall the base packages and refresh
#            /tmp/emux_selfcheck (LINK_OK on a healthy fs).
#   OLD    : if git history is available, the v0.1.77 emux-x11 (bd48cb8) is
#            run against the same state and MUST fail with "no X server
#            available" - proving the harness reproduces the field pane.
# Exit code 0 = all scenarios behaved as expected.
T="${JAILTEST_TMP:-/tmp/emux-jailtest}"
FR=$T/jail
HERE=$(cd "$(dirname "$0")" && pwd)
X11=${1:-$HERE/../../app/src/main/assets/bin/emux-x11}
REPAIR=${2:-$HERE/../../app/src/main/assets/bin/emux-apt-repair}
FAIL=0
expect() { # $1 label, $2 0/1 wanted, $3 0/1 actual
  if [ "$2" = "$3" ]; then echo "PASS $1"; else echo "FAIL $1"; FAIL=1; fi
}
runjail() { # $1 script $2 jailpath
  cp "$1" "$FR$2" 2>/dev/null || { echo "cannot stage $1"; FAIL=1; return 1; }
  chmod +x "$FR$2"
  rm -f "$FR/actions.log"
  timeout 150 chroot "$FR" /bin/sh -c "PATH=/usr/local/bin:/usr/bin:/bin; export PATH; exec $2 openbox"
}

echo "=== NEW: $(basename "$X11") against ghost-xvfb db ==="
sh "$HERE/mkjail.sh" || exit 1
runjail "$X11" /usr/local/bin/emux-x11 > "$T/new.log" 2>&1
rc=$?
[ -e "$FR/tmp/.X11-unix/X0" ]; expect "NEW: X0 socket created" 0 $?
[ -e "$FR/tmp/xvfb.log" ]; expect "NEW: xvfb.log written" 0 $?
[ "$rc" = "0" ]; expect "NEW: clean exit (rc=0, was: no X server available)" 0 $?
grep -q "ghost package xvfb" "$T/new.log"; expect "NEW: ghost xvfb purged" 0 $?
grep -q "apt-stub: installed xvfb" "$FR/actions.log" 2>/dev/null; expect "NEW: xvfb really installed" 0 $?
grep -v -- "-version" "$FR/tmp/xvfb-args" 2>/dev/null | head -1 | grep -q -- "-nolisten unix"; expect "NEW: first attempt is tcp-only (-nolisten unix)" 0 $?
if [ -e "$FR/tmp/xvfb-args" ] && grep "noshmem" "$FR/tmp/xvfb-args" | grep -v -- "-version" | grep -q .; then
  expect "NEW: no LAUNCH passes -noshmem (noble Xvfb rejects it)" 1 0
else
  expect "NEW: no LAUNCH passes -noshmem (noble Xvfb rejects it)" 0 0
fi

echo "=== SLOW: first start takes 20 s (must wait, not kill+flag-switch) ==="
sh "$HERE/mkjail.sh" || exit 1
touch "$FR/tmp/xvfb-slow"
runjail "$X11" /usr/local/bin/emux-x11 > "$T/slow.log" 2>&1
rc=$?
[ -e "$FR/tmp/.X11-unix/X0" ]; expect "SLOW: X0 created despite 20 s start" 0 $?
[ "$rc" = "0" ]; expect "SLOW: clean exit" 0 $?
grep -q "alive but X0 not up in 45 s\|X0 never appeared" "$T/slow.log" && \
  expect "SLOW: no blind kill/flag-switch message" 1 0 || \
  expect "SLOW: no blind kill/flag-switch message" 0 0

echo "=== STALE: leftover X0 socket + lock from a SIGKILLed server ==="
sh "$HERE/mkjail.sh" || exit 1
mkdir -p "$FR/tmp/.X11-unix"
: > "$FR/tmp/.X11-unix/X0"; : > "$FR/tmp/.X0-lock"
runjail "$X11" /usr/local/bin/emux-x11 > "$T/stale.log" 2>&1
rc=$?
[ "$rc" = "0" ]; expect "STALE: clean exit despite stale socket" 0 $?
[ -e "$FR/tmp/.X11-unix/X0" ]; expect "STALE: X0 recreated" 0 $?
if [ -e "$FR/tmp/xvfb-args" ] && grep -q "saw-stale-socket" "$FR/tmp/xvfb-args"; then
  expect "STALE: sweep dropped socket before launch" 1 0
else
  expect "STALE: sweep dropped socket before launch" 0 0
fi

echo "=== ORPHAN: live foreign Xvfb holder (pid visible in /proc) ==="
sh "$HERE/mkjail.sh" || exit 1
printf '#!/bin/sh\n/usr/bin/sleep 999\n' > "$FR/usr/local/bin/Xvfb"
chmod +x "$FR/usr/local/bin/Xvfb"
# wrapper stays alive so the holder is reaped properly when killed
( chroot "$FR" /bin/sh -c '/usr/local/bin/Xvfb :0 & echo $! > /tmp/holder.pid; wait' ) >/dev/null 2>&1 &
WRAP=$!
i=0; while [ ! -f "$FR/tmp/holder.pid" ] && [ "$i" -lt 20 ]; do sleep 0.1; i=$((i+1)); done
HP=$(cat "$FR/tmp/holder.pid")
mkdir -p "$FR/proc/$HP"
printf 'Xvfb\000:0\000-screen\000' > "$FR/proc/$HP/cmdline"
kill -0 "$HP" 2>/dev/null; expect "ORPHAN: holder alive before run" 0 $?
runjail "$X11" /usr/local/bin/emux-x11 > "$T/orphan.log" 2>&1
rc=$?
[ "$rc" = "0" ]; expect "ORPHAN: clean exit despite live holder" 0 $?
kill -0 "$HP" 2>/dev/null; expect "ORPHAN: sweep killed the holder" 1 $?
if [ -e "$FR/tmp/xvfb-args" ] && grep -q "saw-holder" "$FR/tmp/xvfb-args"; then
  expect "ORPHAN: Xvfb never launched against live holder" 1 0
else
  expect "ORPHAN: Xvfb never launched against live holder" 0 0
fi
kill "$WRAP" 2>/dev/null; wait "$WRAP" 2>/dev/null

echo "=== REPAIR: $(basename "$REPAIR") against ghost coreutils ==="
sh "$HERE/mkjail.sh" || exit 1
rm -f "$FR/usr/bin/id"
runjail "$REPAIR" /usr/local/bin/emux-apt-repair > "$T/repair.log" 2>&1
grep -q "ghost package coreutils" "$T/repair.log"; expect "REPAIR: ghost coreutils purged" 0 $?
[ -e "$FR/usr/bin/id" ]; expect "REPAIR: coreutils marker restored" 0 $?
grep -q "LINK_OK" "$FR/tmp/emux_selfcheck" 2>/dev/null; expect "REPAIR: selfcheck LINK_OK" 0 $?

echo "=== OLD: v0.1.77 emux-x11 must STILL fail on the ghost db ==="
if git -C "$HERE" rev-parse -q --verify bd48cb8 >/dev/null 2>&1; then
  sh "$HERE/mkjail.sh" || exit 1
  git -C "$HERE" show bd48cb8:android-emux/app/src/main/assets/bin/emux-x11 > "$T/x11-old.sh"
  runjail "$T/x11-old.sh" /usr/local/bin/emux-x11 > "$T/old.log" 2>&1
  rc=$?
  [ ! -e "$FR/tmp/.X11-unix/X0" ]; expect "OLD: no X0 (field failure reproduced)" 0 $?
  [ "$rc" != "0" ]; expect "OLD: non-zero exit" 0 $?
  grep -q "no X server available" "$T/old.log"; expect "OLD: 'no X server available'" 0 $?
else
  echo "SKIP (git history unavailable)"
fi

[ "$FAIL" = "0" ] && echo "ALL SCENARIOS PASSED" || echo "REGRESSIONS DETECTED"
exit $FAIL
