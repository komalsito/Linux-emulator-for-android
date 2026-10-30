#!/bin/sh
# What colour is the desktop, actually? A pixel census of the root window for
# every pcmanfm state a real install can be in.
#
# Written because v0.2.59's `paint_root` (xsetroot -solid '#20344d') looked
# like a guarantee and was not one: pcmanfm --desktop paints its OWN root
# pixmap over it, and three defects in the "rice" left that pixmap pure black
# on real devices -
#   1. the rice wrote ~/.config/pcmanfm/default/pcmanfm.conf without mkdir'ing
#      the directory, so on a fresh install the write failed silently;
#   2. `wallpaper_mode=6` is not a value pcmanfm parses (it wants a string:
#      none|color|stretch|fit|center|tile|crop), so `wallpaper=` was ignored
#      and only desktop_bg=#10141a ever showed;
#   3. the authoritative file is the per-screen desktop-items-0.conf, which
#      OVERRIDES [desktop] in pcmanfm.conf, and pcmanfm creates it on first run
#      with desktop_bg=#000000.
#
# The "before" half runs the seed extracted from the newest commit that still
# contains `wallpaper_mode=6`, so this harness keeps reproducing the original
# bug after the fix is committed (HEAD would otherwise become the fixed seed
# and case 1 would start failing for the wrong reason). Both halves extract
# seed_desktop_config from the real guest script - nothing here is a copy that
# can drift.
#
# Usage: sh desktop_paint_test.sh        (needs Xvfb, pcmanfm, xwd, python3)
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
EX="$HERE/../../app/src/main/assets/bin/emux-x11"
WALL_SRC="$HERE/../../app/src/main/assets/wallpaper/wall.png"
DISP=${DISP:-97}
PASS=0; FAIL=0; SKIP=0
export LANG=C.UTF-8 LC_ALL=C.UTF-8

[ -f "$EX" ] || { echo "FATAL: no emux-x11 at $EX"; exit 1; }
for t in pcmanfm Xvfb xwd xwdtopnm python3; do
  command -v $t >/dev/null 2>&1 || { echo "SKIP: $t not installed"; exit 0; }
done

extract_seed() {  # $1=file-or-'-'(stdin) $2=dest
  if [ "$1" = - ]; then
    awk '/^seed_desktop_config\(\) \{/{f=1} f{print} f&&/^\}/{exit}' > "$2"
  else
    awk '/^seed_desktop_config\(\) \{/{f=1} f{print} f&&/^\}/{exit}' "$1" > "$2"
  fi
  grep -q "^seed_desktop_config" "$2"
}

SEED_NEW=/tmp/dpt_seedfn.sh
extract_seed "$EX" "$SEED_NEW" || { echo "FATAL: cannot extract seed_desktop_config from $EX"; exit 1; }

# Newest revision whose emux-x11 still carries the broken rice.
SEED_OLD=""
TOP=$(git -C "$(dirname "$EX")" rev-parse --show-toplevel 2>/dev/null) || TOP=""
if [ -n "$TOP" ]; then
  ABS=$(cd "$(dirname "$EX")" && pwd)/$(basename "$EX")
  REL=${ABS#$TOP/}
  for r in $(git -C "$TOP" log --format=%H -n 40 -- "$REL" 2>/dev/null); do
    if git -C "$TOP" show "$r:$REL" 2>/dev/null | grep -q "wallpaper_mode=6"; then
      if git -C "$TOP" show "$r:$REL" 2>/dev/null | extract_seed - /tmp/dpt_seedfn_old.sh; then
        SEED_OLD=/tmp/dpt_seedfn_old.sh
        echo "  pre-fix seed from commit $(echo "$r" | cut -c1-7)"
      fi
      break
    fi
  done
fi

# pgrep -x matches ZOMBIES and this container's PID 1 does not reap, so count
# only processes whose /proc state is not Z.
alive() {
  n=0
  for p in $(pgrep -x "$1" 2>/dev/null); do
    st=$(sed 's/.*) //' /proc/$p/stat 2>/dev/null | cut -d' ' -f1)
    [ -n "$st" ] && [ "$st" != Z ] && n=$((n+1))
  done
  echo $n
}

# Emits "distinct top pct blackpct" for the root window.
census() {
  DISPLAY=127.0.0.1:$DISP xwd -root -silent 2>/dev/null | xwdtopnm 2>/dev/null | python3 -c "
import sys
from collections import Counter
d=sys.stdin.buffer.read()
i=d.index(b'255\n'); i=d.index(b'\n',i+1)+1
h=d[:i].decode().split(); w,ht=int(h[1]),int(h[2]); px=d[i:]
c=Counter()
for y in range(ht):
    row=px[y*w*3:(y+1)*w*3]
    for x in range(0,w*3,3*7): c[(row[x],row[x+1],row[x+2])]+=1
t=sum(c.values())
k,v=c.most_common(1)[0]
print('%d #%02x%02x%02x %.1f %.1f'%(len(c),k[0],k[1],k[2],100.0*v/t,100.0*c.get((0,0,0),0)/t))
"
}

check() {  # $1=description $2=1|0 $3=detail
  if [ "$2" = 1 ]; then PASS=$((PASS+1)); printf '  PASS  %s\n' "$1"
  else FAIL=$((FAIL+1)); printf '  FAIL  %s   (%s)\n' "$1" "$3"; fi
}
skip() { SKIP=$((SKIP+1)); printf '  SKIP  %s   (%s)\n' "$1" "$2"; }
not_black() { python3 -c "import sys;sys.exit(0 if float('$1')<1.0 else 1)" && echo 1 || echo 0; }

# PRISTINE = exactly what pcmanfm writes on its very first run, i.e. the state
# every install that opened the desktop before v0.2.60 is in. Note it carries
# NO wallpaper= key at all.
pristine_items() {
  printf '[*]\nwallpaper_mode=color\nwallpaper_common=1\ndesktop_bg=#000000\ndesktop_fg=#ffffff\ndesktop_shadow=#000000\ndesktop_font=Sans 12\nshow_wm_menu=0\nsort=mtime;ascending;\nshow_documents=0\nshow_trash=1\nshow_mounts=0\n' \
    > "$1/.config/pcmanfm/default/desktop-items-0.conf"
}

# run_case $1=label $2=setup-fn|none $3=wall(yes|no) $4=seed-file
run_case() {
  LABEL=$1; SETUP=$2; HAVEWALL=$3; SEED=$4
  H=$(mktemp -d /tmp/dptXXXX)
  mkdir -p "$H/.config"
  if [ "$HAVEWALL" = yes ]; then
    mkdir -p /usr/share/emux && cp "$WALL_SRC" /usr/share/emux/wall.png 2>/dev/null
  else
    rm -rf /usr/share/emux
  fi
  [ "$SETUP" != none ] && $SETUP "$H"
  # The seeding under test, run exactly as emux-x11 runs it.
  ( HOME="$H"; export HOME; . "$SEED"; seed_desktop_config ) >/tmp/dpt_seed.log 2>&1
  rm -f /tmp/.X$DISP-lock
  Xvfb :$DISP -screen 0 720x1280x24 -listen tcp -ac -nolisten unix >/dev/null 2>&1 &
  XP=$!
  i=0; while [ $i -lt 100 ]; do [ -e /tmp/.X$DISP-lock ] && break; i=$((i+1)); sleep 0.1; done
  sleep 0.6
  DISPLAY=127.0.0.1:$DISP xsetroot -solid '#20344d' >/dev/null 2>&1
  # A bare `DISPLAY=x` line sets a shell variable; without `export` pcmanfm
  # sees an empty display and dies with "Cannot open display".
  ( export HOME="$H" DISPLAY=127.0.0.1:$DISP \
      DBUS_SESSION_BUS_ADDRESS=disabled: GIO_USE_VOLUME_MONITOR=none
    exec pcmanfm --desktop >/tmp/dpt_pf.log 2>&1 ) &
  sleep 6
  set -- $(census)
  CASE_HOME=$H
  CASE_DISTINCT=$1; CASE_TOP=$2; CASE_TOPPCT=$3; CASE_BLACK=$4
  printf '  --- %s\n' "$LABEL"
  printf '      distinct=%s top=%s (%s%%) PURE BLACK=%s%%  pcmanfm_alive=%s\n' \
    "$CASE_DISTINCT" "$CASE_TOP" "$CASE_TOPPCT" "$CASE_BLACK" "$(alive pcmanfm)"
  [ "$(alive pcmanfm)" -gt 0 ] || printf '      pcmanfm log: %s\n' "$(head -2 /tmp/dpt_pf.log 2>/dev/null)"
}

teardown_case() {
  pkill -x pcmanfm 2>/dev/null; sleep 0.4
  [ -n "${XP:-}" ] && kill $XP 2>/dev/null
  wait $XP 2>/dev/null
  rm -f /tmp/.X$DISP-lock; sleep 0.3
}

setup_pristine() { mkdir -p "$1/.config/pcmanfm/default"; pristine_items "$1"; }
setup_user() {
  mkdir -p "$1/.config/pcmanfm/default"
  printf '[*]\nwallpaper_mode=color\nwallpaper_common=1\ndesktop_bg=#334455\ndesktop_fg=#ffffff\nshow_wm_menu=0\nsort=name;ascending;\n' \
    > "$1/.config/pcmanfm/default/desktop-items-0.conf"
}

pkill -x pcmanfm 2>/dev/null; pkill -x tint2 2>/dev/null; sleep 0.4
echo "=== desktop paint census (root window pixels) ==="
echo "  wallpaper asset: $([ -e "$WALL_SRC" ] && echo present || echo MISSING)"
echo

if [ -n "$SEED_OLD" ]; then
  run_case "BEFORE v0.2.60: shipped seed over pcmanfm's pristine state" setup_pristine yes "$SEED_OLD"
  check "reproduces the reported symptom: PURE BLACK desktop" \
    "$(python3 -c "import sys;sys.exit(0 if float('$CASE_BLACK')>99 else 1)" && echo 1 || echo 0)" \
    "black=$CASE_BLACK%"
  teardown_case
else
  skip "BEFORE v0.2.60 reproduction" "no git history with wallpaper_mode=6"
fi

run_case "AFTER: pristine black items repaired (wall.png present)" setup_pristine yes "$SEED_NEW"
check "desktop is no longer black" "$(not_black "$CASE_BLACK")" "black=$CASE_BLACK%"
check "wallpaper actually painted (>200 distinct colours)" \
  "$([ "$CASE_DISTINCT" -gt 200 ] && echo 1 || echo 0)" "distinct=$CASE_DISTINCT"
teardown_case

run_case "AFTER: fresh HOME, no pcmanfm config at all" none yes "$SEED_NEW"
check "desktop is no longer black" "$(not_black "$CASE_BLACK")" "black=$CASE_BLACK%"
check "wallpaper actually painted (>200 distinct colours)" \
  "$([ "$CASE_DISTINCT" -gt 200 ] && echo 1 || echo 0)" "distinct=$CASE_DISTINCT"
teardown_case

run_case "AFTER: fresh HOME, wall.png MISSING" none no "$SEED_NEW"
check "desktop is no longer black" "$(not_black "$CASE_BLACK")" "black=$CASE_BLACK%"
check "fallback is the paint_root colour #20344d" \
  "$([ "$CASE_TOP" = "#20344d" ] && echo 1 || echo 0)" "top=$CASE_TOP"
teardown_case

run_case "AFTER: pristine black items, wall.png MISSING" setup_pristine no "$SEED_NEW"
check "desktop is no longer black" "$(not_black "$CASE_BLACK")" "black=$CASE_BLACK%"
check "fallback is the paint_root colour #20344d" \
  "$([ "$CASE_TOP" = "#20344d" ] && echo 1 || echo 0)" "top=$CASE_TOP"
teardown_case

run_case "AFTER: user chose desktop_bg=#334455" setup_user yes "$SEED_NEW"
check "user's own colour survived" \
  "$([ "$CASE_TOP" = "#334455" ] && echo 1 || echo 0)" "top=$CASE_TOP"
teardown_case

run_case "AFTER: seeded twice (idempotent)" setup_pristine yes "$SEED_NEW"
FIRST=$CASE_TOP
( HOME="$CASE_HOME"; export HOME; . "$SEED_NEW"; seed_desktop_config ) >/dev/null 2>&1
pkill -x pcmanfm 2>/dev/null; sleep 0.4
( export HOME="$CASE_HOME" DISPLAY=127.0.0.1:$DISP \
    DBUS_SESSION_BUS_ADDRESS=disabled: GIO_USE_VOLUME_MONITOR=none
  exec pcmanfm --desktop >/tmp/dpt_pf.log 2>&1 ) &
sleep 5
set -- $(census)
check "second seed paints the same desktop" "$([ "$2" = "$FIRST" ] && echo 1 || echo 0)" "was $FIRST now $2"
check "still not black after the second seed" "$(not_black "$4")" "black=$4%"
teardown_case

# Static checks on the generated files (defect 1 and 2 need no X server).
H=$(mktemp -d /tmp/dptXXXX); mkdir -p "$H/.config"
( HOME="$H"; export HOME; . "$SEED_NEW"; seed_desktop_config ) >/dev/null 2>&1
PFI="$H/.config/pcmanfm/default/desktop-items-0.conf"
PFC="$H/.config/pcmanfm/default/pcmanfm.conf"
check "seed creates ~/.config/pcmanfm/default" "$([ -d "$H/.config/pcmanfm/default" ] && echo 1 || echo 0)" "dir missing"
check "seed writes pcmanfm.conf into a fresh HOME" "$([ -s "$PFC" ] && echo 1 || echo 0)" "file empty/absent"
check "seed writes desktop-items-0.conf into a fresh HOME" "$([ -s "$PFI" ] && echo 1 || echo 0)" "file empty/absent"
check "wallpaper_mode is a string pcmanfm parses (not 6)" \
  "$(grep -qE '^wallpaper_mode=(crop|fit|stretch|center|tile|color)$' "$PFC" 2>/dev/null && echo 1 || echo 0)" \
  "$(grep '^wallpaper_mode' "$PFC" 2>/dev/null)"
check "desktop_bg matches paint_root #20344d" \
  "$(grep -q '^desktop_bg=#20344d$' "$PFI" 2>/dev/null && echo 1 || echo 0)" \
  "$(grep '^desktop_bg' "$PFI" 2>/dev/null)"
rm -rf "$H"

# The repair must only fire on pcmanfm's untouched defaults, and must be
# idempotent, so assert the gate directly on file contents.
H=$(mktemp -d /tmp/dptXXXX); mkdir -p "$H/.config/pcmanfm/default"
pristine_items "$H"
( HOME="$H"; export HOME; . "$SEED_NEW"; seed_desktop_config ) >/dev/null 2>&1
PFI="$H/.config/pcmanfm/default/desktop-items-0.conf"
check "repaired file gained a wallpaper= key (pcmanfm's default has none)" \
  "$(grep -q '^wallpaper=/.' "$PFI" 2>/dev/null && echo 1 || echo 0)" "$(grep '^wallpaper' "$PFI" | tr '\n' ' ')"
check "repaired file kept the user's sort order" \
  "$(grep -q '^sort=mtime;ascending;$' "$PFI" 2>/dev/null && echo 1 || echo 0)" "$(grep '^sort' "$PFI" 2>/dev/null)"
check "no .emux-tmp left behind" \
  "$([ -z "$(ls "$H/.config/pcmanfm/default/" | grep 'emux-tmp')" ] && echo 1 || echo 0)" "tmp file remains"
rm -rf "$H"

echo
echo "passed=$PASS failed=$FAIL skipped=$SKIP"
[ "$FAIL" = 0 ]
