# Trackpad two-finger scroll selects instead of scrolling — v0.2.95 (vc195)

## Field report (v0.2.94, MatePad + trackpad, scroll options on)

Two-finger scroll on the trackpad does not scroll anywhere (Firefox,
xterm). Instead the pointer moves across the page and **selects
everything under it**, as if the user were drag-selecting with button 1
held down. The Settings scroll options are on (scroll speed,
natural scrolling) — they only tune a scroll that arrives; they cannot
create one.

The screenshot that came with the report is itself evidence for the
second half of the story: `INPUT: buttons SWALLOWED (motion ok)
btn=DEAD … proof=motion-only`. Motion reaches the server, button
delivery is what the proof cannot verify in that session — see
"Note on btn=DEAD" below. The selection, however, proves a button 1
*is* reaching Firefox at least some of the time: a selection needs a
ButtonPress plus motion. So the gesture is arriving, and arriving
wrong.

## Root causes (both in `DesktopView`, both regressions of v0.2.85/86)

### 1. A button-less mouse DOWN pressed X button 1 — the selection

`xButtonForBits()` defaulted "no button bits set" to X button 1, and
`onMouseTouchEvent(ACTION_DOWN)` pressed whatever it returned,
unconditionally.

A trackpad finger touching the pad reports `ACTION_DOWN` with
`buttonState == 0`: that is *contact*, not a click. (A physical mouse
cannot produce a button-less DOWN — a mouse DOWN exists because a
button was pressed. A trackpad can, and does, on every touch-down.)
So the gesture the user actually made was delivered to X as:

```
ButtonPress 1  →  Motion… (button 1 held)  →  ButtonRelease 1
```

i.e. a drag-select across the page. The second finger's
`ACTION_POINTER_DOWN` does release the button (the finger path's
`releaseMouseButtonsIfHeld()`), but only after the first finger has
already moved — and on this ROM the scroll itself never arrived as a
scroll event at all (cause 2), so there was nothing to take over from
the drag.

This is the v0.2.86 seam in reverse: v0.2.86 fixed a button *stuck*
down across a path switch; this one *presses* a button that was never
pressed.

### 2. The v0.2.86 touchscreen veto dropped the wheel event — the "not scrolling"

`isWheelInput()` routed `ACTION_SCROLL` through `isMouseInput()`,
which since v0.2.86 returns false when the originating device also
carries `SOURCE_TOUCHSCREEN` — the guard that stops Huawei-class
PC-mode ROMs' re-tagged finger touches (SOURCE_MOUSE, toolType
UNKNOWN) being treated as mouse input.

That guard is correct for *touch* streams and wrong for *wheel*
events: an `ACTION_SCROLL` carries no fingers (pointerCount 0 on
most ROMs) and no button state, and a touchscreen two-finger drag
never arrives as `ACTION_SCROLL` — it arrives as a touch stream.
So no finger gesture can be misrouted by accepting wheel events on
their source alone, while a trackpad whose `InputDevice` reports
`SOURCE_TOUCHSCREEN` (the MatePad keyboard-cover trackpad does) had
every scroll event rejected: `isMouseInput` false, source not
`SOURCE_TOUCHPAD` either, `onGenericMotionEvent` returned false, and
the scroll vanished with no trace.

Timeline: v0.2.85 added the wheel path (and its field device scrolled);
v0.2.86 added the veto for the "desktop not interactable" report on
the same Huawei ROM family — and silently broke the wheel path on
exactly those devices.

## What v0.2.95 does (DesktopView only, ENV_VERSION stays 81)

1. **`xButtonForBits(0) == 0`.** "No button bits" now means "no
   button". `onMouseTouchEvent(ACTION_DOWN)` presses nothing for it
   and records `mouseContactDown` instead; the gesture's MOVE becomes
   hover motion (throttled, ≤30/s, same as drag motion) — motion with
   no button held can never select or drag, and without it the pointer
   would freeze at the touch-down point for the whole gesture.
   A real click is unaffected: a physical mouse DOWN always carries
   its button, and a trackpad tap-to-click arrives as
   `ACTION_BUTTON_PRESS` (already handled, with the v0.2.86
   DOWN-vs-BUTTON_PRESS dedup) or as a DOWN with the button set.
   The `ACTION_BUTTON_PRESS` handler also guards `b == 0` now.
   `mouseContactDown` is cleared by both release paths
   (`releaseMouseButtons`, `releaseMouseButtonsIfHeld`), so a
   path-switch, focus loss or detach cannot leave it set.
2. **`isWheelInput()` is source-only**: `SOURCE_MOUSE` or
   `SOURCE_TOUCHPAD` is a wheel event, regardless of the device's
   touchscreen bit, toolType or pointerCount. The v0.2.86 veto still
   applies exactly where it was designed to — touch classification in
   `isMouseInput()`, used by the DOWN/UP/hover paths.
3. **A refused scroll is noted once per session** (source, toolType,
   device name in the diagnostics history) instead of vanishing
   silently — the pre-fix failure mode was "does not scroll anywhere"
   with no evidence about which event, if any, arrived.

Untouched on purpose: the finger two-finger drag → scroll path
(`ACTION_POINTER_DOWN` → pan/scroll), the XTEST/FIFO scroll
injection (`injectScrollPair` / `fakeScrollBurst`, proven 28/28 by
the JVM wire harness in v0.2.85), the Settings options (speed and
natural scrolling still only tune an arriving scroll), and the
18 px drag arming on the finger path.

## Note on `btn=DEAD` in the report screenshot

`proof=motion-only` / `btn=DEAD` means the button *probe* did not see
its own ButtonPress come back. That verdict is not, by itself, proof
that wheel buttons 4/5 cannot reach Firefox: the probe maps a 72×72
window at (64,64) and clicks its centre, and it only checks the
pointer is geometrically inside that rectangle — it does not check
the probe window is the topmost window there. With a maximised
Firefox open, the click can land on Firefox instead and the probe
reports "swallowed" while real clicks work. The selection in the
field report is consistent with that reading (button 1 demonstrably
reaches Firefox).

If scrolling still fails after this fix, the next check is the probe
detail in the diagnostics history (`emux-x11 doctor` on the desktop):
a `motion=N` count with no ButtonPress *while the probe window is
visible on top* is a real grab; a failure with a maximised window
covering (64,64) is the stacking false positive, and the probe
should be fixed to raise its window (or to move it) rather than the
scroll path being blamed again.

## Verification

- `kotlinc` compile of the app sources (no-Gradle pipeline,
  `tools/build-apk/compile_classes.sh`): DesktopView edits only;
  see the PR checks.
- Field acceptance (MatePad trackpad, v0.2.95):
  - two-finger scroll in Firefox scrolls the page; nothing gets
    selected; the pointer follows the gesture.
  - two-finger scroll in xterm (`seq 1 300`) scrolls the buffer.
  - physical mouse click / drag / right-click unchanged (button 1
    drag still selects text, right-click still opens menus).
  - tap-to-click on the trackpad still clicks (BUTTON_PRESS path).
  - on-screen two-finger drag still scrolls (finger path untouched).
  - if a scroll is still refused, the diagnostics history carries
    the one-shot `trackpad scroll ignored: source=…` line naming
    the event that arrived.
