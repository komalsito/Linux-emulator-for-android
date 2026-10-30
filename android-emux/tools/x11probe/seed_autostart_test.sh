#!/bin/sh
# Tests the Openbox autostart that emux-x11 SEEDS, by extracting the generation
# block straight out of the script (marker-based, so it survives edits) and
# running it against four filesystem states:
#
#   1. fresh install          -> the file must be created and be valid shell
#   2. run 4x                 -> must stay idempotent (no duplicate panels;
#                                the old gate grepped v0.2.45 while writing
#                                v0.2.54, so it appended a copy every run)
#   3. user-written autostart -> must be backed up to autostart.emux-bak, never
#                                silently clobbered
#   4. older EmuX stamp       -> rewritten in place, no duplicate, no backup
#
# Leaves /tmp/ob1/.config/openbox/autostart behind, which is what
# desktop_visible_ab.sh feeds to `openbox --startup`.
set -u
HERE=$(cd "$(dirname "$0")" && pwd)
X11=${X11:-$HERE/../../app/src/main/assets/bin/emux-x11}
BLK=/tmp/emux_seed_block.sh

[ -f "$X11" ] || { echo "cannot find emux-x11 at $X11"; exit 2; }

awk '
  /OBX="\$HOME\/\.config\/openbox"/ { f=1 }
  f { print }
  f && /chmod \+x "\$OBX\/autostart"/ { g=1 }
  g && /^  fi$/ { exit }
' "$X11" > $BLK

LINES=$(wc -l < $BLK | tr -d ' ')
[ "$LINES" -gt 8 ] || { echo "extraction failed: only $LINES lines (has emux-x11 changed shape?)"; exit 1; }
sh -n $BLK || { echo "extracted block is not valid shell"; exit 1; }
echo "extracted the autostart generation block from emux-x11 ($LINES lines); sh -n OK"

STAMP=$(grep -oE 'EMUX_AUTOSTART_STAMP="[^"]+"' $BLK | head -1 | cut -d'"' -f2)
echo "stamp in use: '$STAMP'"
[ -n "$STAMP" ] || { echo "no stamp found"; exit 1; }

run_gen() { ( export HOME="$1" OBX="$1/.config/openbox"; sh $BLK ) >/dev/null 2>&1; }
p=0; f=0
ck() { if eval "$2"; then echo "  [PASS] $1"; p=$((p+1)); else echo "  [FAIL] $1"; f=$((f+1)); fi; }

echo
echo "=== 1. fresh install ==="
rm -rf /tmp/ob1; mkdir -p /tmp/ob1/.config/openbox
run_gen /tmp/ob1
A=/tmp/ob1/.config/openbox/autostart
ck "autostart created"                "[ -s $A ]"
ck "carries the current stamp"        "grep -qF '$STAMP' $A"
ck "generated file is valid shell"    "sh -n $A"
ck "paints the root (xsetroot)"       "grep -q 'xsetroot -solid' $A"
ck "starts the panel (tint2)"         "grep -q 'tint2' $A"
ck "picom is OPT-IN, not automatic"   "grep -q 'emux_want_picom' $A"
ck "pcmanfm starved of DBUS/GIO"      "grep -q 'DBUS_SESSION_BUS_ADDRESS=disabled:' $A"
ck "every launcher ends with &"       "! grep -qE '^[a-z].*[^&[:space:]]\$' $A || grep -q 'fi' $A"

echo
echo "=== 2. idempotency over 4 runs ==="
run_gen /tmp/ob1; run_gen /tmp/ob1; run_gen /tmp/ob1
ck "exactly one tint2 launch"         "[ \$(grep -c 'tint2' $A) -eq 1 ]"
ck "exactly one pcmanfm --desktop"    "[ \$(grep -c 'pcmanfm --desktop' $A) -eq 1 ]"
ck "exactly one stamp line"           "[ \$(grep -cF '$STAMP' $A) -eq 1 ]"
ck "still valid shell"                "sh -n $A"

echo
echo "=== 3. a user's own autostart is backed up AND chained ==="
rm -rf /tmp/ob2 /tmp/chain_ran; mkdir -p /tmp/ob2/.config/openbox
printf 'touch /tmp/chain_ran\n' > /tmp/ob2/.config/openbox/autostart
run_gen /tmp/ob2
B=/tmp/ob2/.config/openbox
ck "backup file created"              "[ -f $B/autostart.emux-bak ]"
ck "backup holds the user's content"  "grep -q 'chain_ran' $B/autostart.emux-bak"
ck "EmuX furniture still installed"   "grep -qF '$STAMP' $B/autostart"
ck "user autostart is chained"        "grep -q 'autostart.emux-bak' $B/autostart"
ck "generated file is valid shell"    "sh -n $B/autostart"
# Execute it for real: the chain must run the user's own script.
HOME=/tmp/ob2 sh $B/autostart >/dev/null 2>&1
sleep 0.6
ck "running it executes the user's script" "[ -f /tmp/chain_ran ]"
ck "and it is idempotent on a second run"  "run_gen /tmp/ob2; [ \$(grep -c 'emux-bak' $B/autostart) -eq 1 ]"

echo "=== 4. older EmuX stamp is rewritten in place ==="
rm -rf /tmp/ob3; mkdir -p /tmp/ob3/.config/openbox
printf '# EmuX desktop v0.2.54\ntint2 >/dev/null 2>&1 &\npcmanfm --desktop >/dev/null 2>&1 &\n' \
  > /tmp/ob3/.config/openbox/autostart
run_gen /tmp/ob3
C=/tmp/ob3/.config/openbox
ck "no backup (it was ours)"          "[ ! -f $C/autostart.emux-bak ]"
ck "old stamp gone"                   "! grep -q 'v0.2.54' $C/autostart"
ck "exactly one tint2 launch"         "[ \$(grep -c 'tint2' $C/autostart) -eq 1 ]"
ck "current stamp present"            "grep -qF '$STAMP' $C/autostart"

echo
echo "  passed=$p failed=$f"
[ "$f" = 0 ] && echo "  => the seeded autostart is correct, idempotent and non-destructive"
[ "$f" = 0 ]
