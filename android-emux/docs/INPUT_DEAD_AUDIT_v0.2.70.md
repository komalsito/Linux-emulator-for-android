# v0.2.68–v0.2.70 ADDENDUM — the stream was the last liar

Round-2 audit (INPUT_DEAD_AUDIT_v0.2.67.md) fixed the input path's joints
(plane agreement, dead-socket recycling, continuous proof). The v0.2.67 field
badge then read `proof=ok touch=25 inj=48 fail=2 ctrl=:1 cap=:1 agent=idle` —
planes agreed, writes succeeded, proof passed — and the desktop STILL showed
zero damage for 25 taps. A live pcmanfm desktop cannot ignore 48 button events
silently. A photograph can.

## F — the frozen photograph (v0.2.68)

`emux-capture` died mid-session and two code facts made its death invisible:

1. the respawn watcher gated on `frameId == 0` — a capture death AFTER the
   first frame was never respawned and never reported;
2. the LIVE badge computes `frameTimes.size / 2f`, and `frameTimes` is only
   trimmed when a NEW frame arrives — so the badge froze at its last value
   over the last bitmap, forever.

Device proof (user terminal, v0.2.67): `/tmp/emux_cap_display` epoch was
**2086 s** older than `date +%s` — the helper had been dead for half an hour
while the pane claimed `LIVE 0.5 fps`. Every tap was landing on the real,
living desktop behind that last frame — opening windows the user could not
see. Ten versions of "input dead" ended here: the input path was healthy; the
*eyes* were dead and the UI lied about it.

Fixes: respawn on ANY capture death (budget 40, rc + cap.err tail noted);
badge paints `STALE Ns … capage=Ns` in red and ticks once >8 s pass without a
frame; proof line gains `capage=` (seconds since emux-capture last published
`/tmp/emux_cap_display`, which it rewrites at the top of every loop).

## G — the wedged capture (v0.2.69)

A capture can be ALIVE but stuck (`xwd` hung inside `grab_frame`); its session
never reports DEAD, so the respawn watcher never fires. Since the publication
happens at the top of every loop, >20 s without one proves the loop is not
looping: the wedge guard stops the session (respawn watcher recreates it) and
notes the tail of `/tmp/emux_cap.err`.

## H — the stream was the designated LMK sacrifice (v0.2.70)

Why did capture die repeatedly on this device? The app's own LMK bias ladder:

```kotlin
cmd.contains("emux-capture") || cmd.contains("/xwd") -> "950"   // max killable
```

written to `/proc/<pid>/oom_score_adj` for every desktop guest, "so the
desktop stack is the preferred sacrifice" while the terminal's `-300`
protection needs root and is DENIED on every unrooted device. On exactly the
devices in the field (top bar: `root: not available`) the ladder bought
nothing and guaranteed the first memory-pressure event kills the capture
helper — respawn → live → pressure → dead → STALE, every session.

v0.2.70 policy: capture/xwd are never bias-adjusted; the positive ladder is
applied ONLY when the terminal protection actually succeeded (root); without
root nothing is written and the pane says so. Additionally the STALE pane now
paints the capture's own `cap.err` tail, so the tool killing the stream names
itself in the next screenshot.

## Field-verification matrix after v0.2.70

| badge after 1 min on Desktop | meaning |
|---|---|
| `LIVE … capage=<small>` + taps open things | closed |
| `STALE … capage=…` + `cap.err: …` | capture still dying/wedging; the cap.err tail names the tool (next fix targets it, e.g. drop netpbm and parse XWD in-app) |
| `LIVE … capage=<small>`, taps inert | photograph ruled out; run the xwininfo/xdotool window-swallow probe |

APKs for every version are kept in `releases/` (user request); root
`emux-debug.apk` is always the newest. All verified 51/0/0 by
`tools/verify_apk.sh` and signed with the committed keystore.
