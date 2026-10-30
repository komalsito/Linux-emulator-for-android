#!/bin/sh
# Prove the killDesktopGuests() sweep bug and the fix.
#
# Real layout: the app runs
#     proot -0 -r ROOT ... /bin/sh -c "<payload>"
# proot's OWN argv therefore contains the payload text. proot binds the host
# /proc, so the payload's loop over /proc/*/cmdline sees proot itself.
#
# OLD payload had the literal strings *Xvfb*|*Xorg*|*emux-capture*|*xwd* in it,
# so proot's cmdline matched and the sweep did `kill -9 <proot>` - killing the
# ptrace tracer mid-sweep (tracee reports "proot warning: signal 11") and
# never reaching the X lock cleanup.
#
# NEW payload takes the patterns from the ENVIRONMENT, which never appears in
# any cmdline, and skips itself and its parent.
set -u
BIN=/tmp/sweepbin
rm -rf $BIN; mkdir -p $BIN
cp /bin/sleep $BIN/Xvfb            # a victim whose cmdline contains "Xvfb"

victim() { $BIN/Xvfb 120 >/dev/null 2>&1 & echo $!; }

OLD='for f in /proc/[0-9]*/cmdline; do c=$(tr "\0" " " < $f 2>/dev/null); case $c in *Xvfb*|*Xorg*|*emux-capture*|*xwd*) p=${f#/proc/}; p=${p%/cmdline}; [ $p != $$ ] && kill -9 $p 2>/dev/null;; esac; done; echo SWEEP_RAN_TO_COMPLETION'

NEW='PP=$(sed "s/.*) //" /proc/self/stat 2>/dev/null | cut -d" " -f2); for f in /proc/[0-9]*/cmdline; do p=${f#/proc/}; p=${p%/cmdline}; [ "$p" = "$$" ] && continue; [ -n "$PP" ] && [ "$p" = "$PP" ] && continue; c=$(tr "\0" " " < $f 2>/dev/null); case $c in *"$EMUX_SW1"*|*"$EMUX_SW2"*|*"$EMUX_SW3"*|*"$EMUX_SW4"*) kill -9 $p 2>/dev/null;; esac; done; echo SWEEP_RAN_TO_COMPLETION'

# `wrapper` mimics proot: a process whose argv contains the whole payload.
run_case() {
  LABEL="$1"; PAYLOAD="$2"; shift 2
  V=$(victim)
  sleep 0.3
  # wrapper's argv contains the payload, exactly like proot's does
  if [ "$LABEL" = "OLD" ]; then
    /bin/sh -c "/bin/sh -c \"\$1\"; echo WRAPPER_SURVIVED" _ "$PAYLOAD" > /tmp/sweep_out.txt 2>&1 &
  else
    EMUX_SW1=Xvfb EMUX_SW2=Xorg EMUX_SW3=emux-capture EMUX_SW4=/xwd \
      /bin/sh -c "/bin/sh -c \"\$1\"; echo WRAPPER_SURVIVED" _ "$PAYLOAD" > /tmp/sweep_out.txt 2>&1 &
  fi
  W=$!
  sleep 2.0
  echo "===== $LABEL payload ====="
  # NOTE: kill -0 SUCCEEDS on a zombie, and in a container PID 1 may not reap,
  # so read the process state instead of using kill -0.
  VST=$(awk '{print $3}' /proc/$V/stat 2>/dev/null || echo gone)
  case "$VST" in R|S|D|T) VOK=1;; *) VOK=0;; esac
  echo "  victim (fake Xvfb):   state=$VST  $([ $VOK = 0 ] && echo '<- killed (correct)' || echo '<- STILL RUNNING')"
  if grep -q WRAPPER_SURVIVED /tmp/sweep_out.txt; then WOK=0; echo "  wrapper (the tracer): SURVIVED (correct)"; else WOK=1; echo "  wrapper (the tracer): KILLED  <-- the bug: it killed its own ptrace tracer"; fi
  echo "  sweep output:"; sed 's/^/    /' /tmp/sweep_out.txt
  grep -q SWEEP_RAN_TO_COMPLETION /tmp/sweep_out.txt && echo "    -> sweep COMPLETED" || echo "    -> sweep ABORTED before the X-lock cleanup"
  kill -9 $V $W 2>/dev/null
  echo "  VERDICT: victimKilled=$([ $VOK = 0 ] && echo yes || echo NO)  tracerKilled=$([ $WOK = 1 ] && echo YES || echo no)  lockCleanupReached=$(grep -q SWEEP_RAN_TO_COMPLETION /tmp/sweep_out.txt && echo yes || echo NO)"
  echo
}

run_case OLD "$OLD"
run_case NEW "$NEW"
rm -rf $BIN
