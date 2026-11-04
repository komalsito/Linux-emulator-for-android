# Mouse & trackpad support — v0.2.85 (vc185)

## Field report (v0.2.84, Galaxy Tab S9 with M-Pencil and a trackpad case)

- The mouse cursor moves but **clicks feel wrong** and there is visible lag between
  the system cursor and the one drawn on screen.
- **Trackpad scrolling does not work anywhere** — xterm (`seq 1 300`), Firefox, files.
- The M-Pencil works normally; fingers must keep working exactly as before.

## Root causes

`DesktopView` had three gaps:

1. **No `onGenericMotionEvent` at all.** A wheel or two-finger trackpad scroll
   arrives as `ACTION_SCROLL` carrying `AXIS_VSCROLL`/`AXIS_HSCROLL` — none of the
   existing handlers is ever called for it, so every scroll gesture was dropped.
   `buttonState` was never read either.
2. **`onHoverEvent` never injected.** It only moved the locally drawn cursor —
   the X11 pointer stayed behind, so hover highlights in pcmanfm/tint2 never
   fired and two cursors were visible (system + synthetic, drifting apart).
3. **Trackpad clicks went through the finger gesture state machine** — tap delay,
   long-press-as-right-click, edge slop — instead of `ACTION_BUTTON_PRESS/RELEASE`.

## What v0.2.85 does

### Scroll: `onGenericMotionEvent` → one batched burst per gesture

- Fires only for mouse/trackpad sources (`SOURCE_MOUSE | SOURCE_TOUCHPAD`), and
  only with `pointerCount == 0` — a finger on the screen can never trigger it.
- Sign convention: `AXIS_VSCROLL` + → button 4 (up), − → button 5 (down);
  `AXIS_HSCROLL` + → button 7 (right), − → button 6 (left).
- Per-axis **fractional remainder accumulators**: trackpads emit fractions of a
  notch per event; clicks are produced only on whole notches (clamp ±64 so a
  paused gesture cannot bank a huge burst).
- Scale from Settings (`mouse_scroll_scale`, default "Normal — 3 lines/notch",
  clamp 0.1–16) × natural-scrolling toggle (`mouse_natural_scroll`, default off,
  same axis convention as Android).
- `DesktopService.injectScrollPair(x, y, vClicks, hClicks)` posts **one** XTEST
  task; `X11Client.fakeScrollBurst` wraps pointer motion + all press/release
  pairs in `beginBatch()/endBatch()` — **one socket write per scroll gesture**
  (the v0.2.80 batching lesson), max 24 clicks per axis per gesture.
- Works identically in FIFO fallback mode (`M` + `D`/`U` lines).

### Hover: inject, coalesced, one visible cursor

- `HOVER_ENTER` + every `HOVER_MOVE` now `injectMotion` through the existing
  coalescing `pendingMotion` slot — at most one inject per posted Runnable, min
  33 ms apart (~30/s), **with a trailing flush**: the final hover position always
  lands even after events stop, which is what hover highlights need.
- While a real mouse pointer is attached the system draws its own cursor at the
  exact on-screen position, so `drawCursor` skips the synthetic one (no double
  cursor, no lag between them). A finger `ACTION_DOWN` clears the flag and the
  synthetic cursor returns.
- `onDetachedFromWindow` now also removes the pending hover flush.

### Buttons: direct press/release, real drags, right-click without long-press

- `onTouchEvent` routes **pointer 0 only, mouse toolType only** to
  `onMouseTouchEvent`: `DOWN` → press, `UP` → release (all held buttons),
  `MOVE` → hover inject (drag), plus `ACTION_BUTTON_PRESS/RELEASE` for buttons
  that change mid-gesture (a trackpad sends its clicks exactly this way). Held
  buttons are tracked so a press/release is never sent twice.
- Mapping: PRIMARY→1, TERTIARY (middle)→2, SECONDARY (right)→3, BACK→8,
  FORWARD→9. Right-click is a direct button-3 press/release — no long-press
  timer involved; middle-click pastes in xterm; left press-and-move is a real
  drag (text selection; titlebar `Left Drag → Move` is bound in the seeded
  rc.xml, so windows move).
- Every exit path (`UP`/`CANCEL`) releases all held buttons — the v0.2.79
  stuck-button lesson.
- Overlay chips still work with a mouse: hits are tested before injection,
  `requestFocus()` + `SHOW_IMPLICIT` show the soft keyboard on click.
- `isMouseInput`: toolType first — `STYLUS`/`ERASER`/`FINGER` are **never** a
  mouse (M-Pencil keeps the finger path, as specified); `SOURCE_MOUSE` is only a
  fallback when toolType is unknown.

### Finger path: untouched

The only change inside finger handling is `realMouseCursor = false` on
`ACTION_DOWN` (restore the synthetic cursor). Long-press→right-click, drag
scrolling, edge slop, tap delays — all exactly as in v0.2.84.

## Bonus: per-file rice opt-out (`emux_keep`)

`emux-x11` now checks `~/.config/emux/keep-<name>` marker files before
regenerating:

| Marker | Keeps yours |
|---|---|
| `keep-tint2rc` | `~/.config/tint2/tint2rc` (incl. the pre-rice rewrite) |
| `keep-autostart` | `~/.config/openbox/autostart` (runs as-is; no backup, no chain) |
| `keep-pcmanfm` | `~/.config/pcmanfm/default/pcmanfm.conf` + `desktop-items-0.conf` + the `libfm` dir block |

Deliberately **not** opt-out-able: `rc.xml` (the v0.2.78 click-swallow fix must
survive — a foreign rc.xml is still replaced, kept as `rc.xml.emux-bak`), the
EmuX `.desktop` launchers, Welcome.txt and the theme (EmuX-owned files).
`ENV_VERSION` 76→77 re-seeds once with the new script; afterwards the markers
decide.

## Verification

- **JVM wire harness** (`tools/x11probe/jvmwire/run_jvm_wire_proof.sh`) grew
  section 7b: a Painter-side listening window under the target point receives
  `fakeScrollBurst` (reflected, so the harness still compiles against older
  clients) and counts events on the wire — **2× button-4 press+release and 1×
  button-7 press+release, with QueryPointer confirming the aim** — proving the
  whole scroll path (batch → motion → wheel buttons) end-to-end. 28 checks.
- `verify_apk.sh`: new dex symbols (`fakeScrollBurst`, `injectScrollPair`,
  `onMouseTouchEvent`, `isMouseInput`, `mouse_natural_scroll`) + keep-marker
  asset check. 73 checks, 0 failed.
- Field acceptance targets (from the report): two-finger scroll in xterm and
  Firefox; hover highlights in pcmanfm + tint2; right-click → Openbox desktop
  menu (rc.xml `Root` context); middle-click paste in xterm; click-drag selects
  text and moves windows.
