# v0.2.60 — desktop input UX, and the desktop that was *still* black

Two things happened this round.

1. The requested work: **scroll wheel, right-click / long-press, two-finger
   drag and on-screen key helpers** — every one of them verified at the wire
   level with `xev` watching what the X server actually delivered.
2. While building the test harness for (1), the pixel census said the desktop
   was **99.4 % pure black** — with Openbox running, the autostart executed,
   `tint2` up and `pcmanfm --desktop` alive. v0.2.59 had fixed *one* reason the
   desktop was black and there was a second, independent one underneath it.
   That is the more important half of this document.

As in v0.2.59, every claim below is a measurement on a live
`Xvfb 1.21` + `Openbox 3.6.1` + `pcmanfm` stack, reproducible with the
harnesses in [`tools/x11probe/`](../tools/x11probe/README.md).

---

## 1. Input UX (the requested task)

The gesture set is documented in the code at the head of the gesture section of
`DesktopView.kt`; the summary:

| gesture | what X receives |
|---|---|
| tap | button 1 press+release |
| drag | motion (window drag), button 1 semantics |
| **long-press** | **button 3 DOWN and *held*** — drag it for a right-drag, released where the finger lifts |
| **two-finger tap** | **button 2** (middle) — the only practical way to paste into an X terminal |
| **two-finger drag** | **X scroll wheel**: buttons 4/5 vertical, 6/7 horizontal, at fit-to-screen; viewport pan while zoomed (where panning is what you actually need) |
| pinch | zoom (unchanged) |
| double-tap | letterbox-fit ↔ fill-screen (unchanged) |
| **helper bar** (`keys` toggle) | Esc, Tab, Ctrl, Alt, Super, ▲, ▼, ⌫ |

Three design points worth recording:

**The long-press holds button 3 instead of tapping it.** v0.2.59 sent
press → 60 ms → release, which made a right-drag impossible (the button was
already up before the finger moved) and released the menu the instant it
appeared. Now the button stays down until the finger lifts, so "long-press,
then drag" selects a range in a terminal and "long-press, then let go" opens
the context menu. A second finger ending the gesture always releases it — no
stuck button 3.

**Wheel events are accumulated and quantised**, one click per
`SCROLL_STEP_PX` of finger travel, capped at 12 per call. Without that a slow
two-finger drag machine-guns the server with hundreds of button presses at
10 fps. Direction mapping is the X convention, measured not assumed: fingers
travelling *down* (`dy > 0`) scroll content down, which is button **5**.

**Sticky modifiers.** Tap `Ctrl` on the helper bar, then type `c` on the soft
keyboard and get Ctrl+C. The chips latch (`stickyCtrl/Alt/Meta`) and are
consumed by the next keysym, IME commit or hardware key; `clearSticky()` runs
after every consumption. `injectKeysymCombo()` deliberately has **no shift
parameter** — `X11Client.typeKeysymTap()` already applies the shift level read
out of the server's own keymap, so passing Shift too would double-apply it.
The `xev` witness shows exactly that: Ctrl+T arrives as
`state 0x5, keycode 28 (keysym 0x54, T)` — `0x5` = ControlMask|LockMask, an
uppercase `T` from the keymap, with no Shift press from the caller.

An overlay bug fell out of the same work: the helper bar was hit-tested on
both `ACTION_DOWN` and `ACTION_UP`, so one tap on a chip fired it twice.
`overlayDown` now consumes the tap once.

### Wire-level evidence — `gesture_e2e.sh` (10/10)

`GestureDriver.kt` drives the **real compiled `DesktopService` input path** on a
plain JVM; `xev` runs as the witness on the same display and records what the
server actually delivered. 10/10, `X errors: 0`:

```
[PASS] middle click = button 2, press+release (x2)
[PASS] wheel up = button 4, 3x press+release (x6)
[PASS] wheel down = button 5, 2x press+release (x4)
[PASS] wheel left = button 6 (x2)
[PASS] wheel right = button 7 (x2)
[PASS] right-drag = button 3, press+release (x2)
[PASS] Ctrl held while 'c' is typed (state 0x4 = ControlMask)
[PASS] Control_L itself was pressed and released
[PASS] Alt held while Tab is typed (state 0x8 = Mod1Mask)
[PASS] Ctrl+T uppercase via keymap shift level (state includes ControlMask)
```

Two traps this harness encodes (both cost a false failure first):

- `xev -event button,key` is **rejected** ("unrecognized event mask") and xev
  exits silently, which looks exactly like "the server delivered nothing".
  Correct form is one mask per occurrence: `-event button -event keyboard`.
- The witness must be confirmed up *before* the driver runs, or it records
  nothing and every assertion fails for the wrong reason.

---

## 2. The desktop was still black — three pcmanfm defects

### What the census said

`desktop_visible_ab.sh` Trial B — the *passing* v0.2.59 configuration:

```
openbox:            RUNNING
autostart executed: YES   (marker file written by the script itself)
tint2 panel:        RUNNING
pcmanfm --desktop:  RUNNING
root: 99.8 % PURE BLACK
```

Everything the previous round had fixed was working, and the pane was still
black. Isolating it took one experiment: launch `pcmanfm --desktop` alone on a
bare Xvfb and sample the root before and after. Before: whatever `xsetroot`
had painted. After: `pcmanfm`'s colour. **`pcmanfm --desktop` owns the root
window** — it paints its own pixmap and publishes `_XROOTPMAP_ID`, so
v0.2.59's `paint_root()` could never be seen while pcmanfm was healthy.
(The wedge watchdog keys on `_XROOTPMAP_ID` being *absent*; a pcmanfm that
paints black publishes it happily, so the watchdog never fired either.)

Three separate defects, any one of which alone produced a black desktop:

**Defect 1 — the rice was never written on a fresh install.**
`seed_desktop_config()` wrote `~/.config/pcmanfm/default/pcmanfm.conf` with
`2>/dev/null` but never `mkdir`'d the directory, and that directory does not
exist until pcmanfm has run once. On a first launch the write failed silently.

**Defect 2 — `wallpaper_mode=6` is not a value pcmanfm understands.**
The key takes a *string*. Measured, on a 720×1280 root with `wall.png` present:

| `wallpaper_mode` | root census | wallpaper painted? |
|---|---|---|
| `stretch` | 827 distinct | yes (distorted) |
| `fit` | 786 distinct, `#20344d` 65 % | yes (letterboxed) |
| `crop` | 671 distinct | **yes, full bleed** |
| `center` | 811 distinct, bg 37.7 % | yes |
| `tile` | 784 distinct | yes |
| `color` | 1 distinct = `desktop_bg` | no (by design) |
| **`6` (what shipped)** | **1 distinct = `desktop_bg`** | **no** |

So the shipped `wall.png` has **never once been displayed on any device**. The
only thing ever painted was `desktop_bg=#10141a` — RGB565 `(2,5,3)`, three to
five code values above black, which the frame encoder renders as a black pane.

**Defect 3 — the authoritative file is the per-screen one.**
Background, wallpaper and icon layout live in
`~/.config/pcmanfm/default/desktop-items-0.conf`, which **overrides** the
`[desktop]` section of `pcmanfm.conf`. pcmanfm creates that file on its first
run with `desktop_bg=#000000`. So even after defects 1 and 2 were fixed, any
install that had ever opened the desktop stayed **100 % pure black forever** —
measured, not inferred:

| pcmanfm state | rice in `pcmanfm.conf` | root |
|---|---|---|
| pristine `desktop-items-0.conf` (`#000000`) | none | `#000000` 100 % |
| pristine `desktop-items-0.conf` | **fixed rice, `crop` + wallpaper** | **`#000000` 100 %** |
| no items file | fixed rice | 671 distinct, wallpaper |
| no items file | none (vanilla pcmanfm) | `#000000` 100 % |

Row 2 is the one that matters: **a valid, correct rice cannot beat a
pre-existing items file.**

### The fix

In `seed_desktop_config()`:

- `mkdir -p ~/.config/pcmanfm/default` before writing anything.
- `wallpaper_mode=crop` and `desktop_bg=#20344d` — the same colour `paint_root`
  uses, so the two layers now agree and the "never a black void" contract holds
  on the layer the user is actually looking at.
- If `/usr/share/emux/wall.png` is not on disk, write `wallpaper_mode=color`
  with an **empty** `wallpaper=` rather than a path to a file that does not
  exist: solid `#20344d`, never black.
- Seed `desktop-items-0.conf` in full when it is absent.
- When it already exists, **repair it only if it is still pcmanfm's own
  untouched default** — gate: `desktop_bg=#000000` *and* no `wallpaper=/…`
  path. A user who chose a colour or a wallpaper is left exactly alone, and
  icon layout/sort order survive either way because only the background keys
  are rewritten. pcmanfm's default file carries **no `wallpaper=` key at all**,
  so the rewrite also appends any key that is absent — without that, a repaired
  install got `mode=crop` pointing at nothing and fell back to the solid
  colour. The repair is idempotent: once repaired, the gate no longer matches.
- `show_wm_menu=1` in both files, so a right-click on the desktop (now reachable
  as a long-press, §1) opens the Openbox menu.
- Rice stamp `Emux rice v0.2.51` → `Emux rice v0.2.60`, so existing installs
  get rewritten once.

### Evidence — `desktop_paint_test.sh` (20/20)

Extracts `seed_desktop_config` from the **shipped** `emux-x11` and, for the
"before" half, from the newest commit that still contains `wallpaper_mode=6`
(`8e80f81`, v0.2.59) — pinning the revision is deliberate, otherwise the
moment the fix is committed, `HEAD` *becomes* the fixed seed and the "before"
case starts failing for the wrong reason.

```
--- BEFORE v0.2.60: shipped seed over pcmanfm's pristine state
    distinct=333  top=#000000 (99.4 %)  PURE BLACK=99.4 %
PASS  reproduces the reported symptom: PURE BLACK desktop
--- AFTER: pristine black items repaired (wall.png present)
    distinct=1061 top=#1e3047 (2.3 %)   PURE BLACK=0.0 %
PASS  desktop is no longer black
PASS  wallpaper actually painted (>200 distinct colours)
--- AFTER: fresh HOME, wall.png MISSING
    distinct=394  top=#20344d (99.3 %)  PURE BLACK=0.0 %
PASS  fallback is the paint_root colour #20344d
--- AFTER: user chose desktop_bg=#334455
    distinct=396  top=#334455 (99.3 %)
PASS  user's own colour survived
```

`#1e3047` at 2.3 % with 1061 distinct colours *is* the wallpaper: the shipped
`wall.png` is a 1280×800 dark-navy gradient whose own dominant colours are
`#1e3047`/`#1d2e44`/`#1f324a` at ~2 % each (decoded and histogrammed from the
PNG in-asset, since no image tooling is installed here).

`desktop_visible_ab.sh` — same question, whole session:

```
A (bare openbox)      : autostart executed NO,  1 colour,   100 % black
B (openbox --startup) : autostart executed YES, 1236 colours, 0 % black
                        tint2 RUNNING, pcmanfm RUNNING      8/8 PASS
```

That harness also became device-faithful this round: it used to seed only the
autostart block, so its trial HOMEs had no pcmanfm configuration at all and
Trial B measured black for a reason unrelated to the autostart question it
exists to answer. It now runs the whole `seed_desktop_config` and installs the
wallpaper asset.

---

## 3. A user's own Openbox autostart is chained, not dropped

Found while writing `seed_autostart_test.sh` (23/23), which extracts the
autostart generation block from `emux-x11` **by marker, never by line number**
(line-number extraction breaks on any edit).

v0.2.59 backed a user's hand-written `~/.config/openbox/autostart` up to
`autostart.emux-bak` and then — because the write condition did not know about
the backup — **wrote nothing**. Result: no panel, no root colour, no icons, for
exactly the user who had customised Openbox once. That reproduces complaint (1)
of the original field report from a completely different cause.

The write condition gained `|| [ -n "$EMUX_USER_BAK" ]`, and the generated file
now ends with

```sh
# the user's own autostart, preserved and chained
[ -r '<abs>/autostart.emux-bak' ] && /bin/sh '<abs>/autostart.emux-bak' &
```

with the absolute path written at generation time so the generated file needs
no `$HOME` escaping. Older EmuX-stamped files are still rewritten in place;
user content is still never clobbered.

`ENV_VERSION` 68 → 69 (`core/FsLayout.kt`) because the guest script changed —
without the bump, existing installs keep running the old `emux-x11` and none of
§2 or §3 reaches them.

---

## 4. Scoreboard

| harness | result |
|---|---|
| `compile_all.sh` | 0 errors, 23 files, 263 classes |
| `gesture_e2e.sh` | 10/10 (xev witness, 0 X errors) |
| `desktop_paint_test.sh` | 20/20 |
| `desktop_visible_ab.sh` | 8/8 |
| `seed_autostart_test.sh` | 23/23 |
| `protocol_audit.sh` | PASS, 0 failures |
| `kotlin_xtest_e2e.sh` | PASS 3/3 |
| `agent_test.sh` | 4/4 |
| `sweep_test.sh` | victimKilled=yes tracerKilled=no lockCleanupReached=yes |

## 5. What this still needs from a real machine

Nothing here was packaged: the sandbox has 1 GB and `assembleDebug` gets
OOM-killed (exit 137) long before the APK exists. To ship v0.2.60 (vc160):

```sh
./gradlew :app:assembleDebug          # on a machine with >4 GB
```

then replace the root `emux-debug.apk` (still v0.2.58) in a follow-up commit.
`ENV_VERSION 69` handles re-seeding `emux-x11` onto existing installs; the
gesture work in §1 is host-side Kotlin and needs the new APK.
