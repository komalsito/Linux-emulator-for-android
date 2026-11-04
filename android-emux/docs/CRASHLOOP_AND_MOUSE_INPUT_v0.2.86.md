# v0.2.86 — the crash that looped, the settings tier that could still kill, and the mouse path that kept the button

One field report (HUAWEI MRDI-W09, API 31, v0.2.85 / env 77), three defects:

> "the desktop is not interactable and there is no virtual mouse"
> "when I enter settings it crashes"
>
> ```
> java.lang.RuntimeException: Unable to resume activity {com.emux/com.emux.MainActivity}:
> kotlin.UninitializedPropertyAccessException: lateinit property b has not been initialized
>   at com.emux.MainActivity.onResume(MainActivity.kt:1106)
> ```

The pasted report is the SECOND crash. The first one - whatever it was - is the
trigger; the loop below is why the app never came back.

## 1. Any single crash became a permanent launch loop (MainActivity)

`onCreate` has two degraded branches that build their own code-only UI and
return WITHOUT running `doOnCreate`:

- the v0.2.62 prior-crash report view (`CrashReport.consumeIfFresh` found a
  report younger than 30 min), and
- the v0.1.3 startup-failure screen (`doOnCreate` threw).

Neither initializes `b` (the `ActivityMainBinding`). `onResume` dereferenced it
unconditionally - line 1106, `b.termContainer...requestFocus()`:

```
launch → priorCrash branch → report on screen → onResume → UninitializedPropertyAccessException
       → handler writes a NEW report → process dies
launch → priorCrash branch (the report it just wrote is fresh) → … forever
```

The escape hatch ("Try starting EmuX anyway") died on resume before it could be
read, let alone tapped. One settings crash = an unopenable app until the data is
cleared. The log tail in the field report shows exactly one lifecycle: service
start at 21:47:11.004, FATAL at 21:47:12.866 - the report screen never had a
chance.

**Fix:** every lifecycle entry that touches `b` now tolerates the degraded
branches - `onResume` returns after `super` when `!::b.isInitialized` (the
report screen needs nothing from the main UI), and `restartDesktopNow()` /
`updateDesktopStatus()` carry the same guard. Regression test:
`priorCrashReportDoesNotCrashLoopOnResume` writes a fresh `last_crash.txt`,
runs the full `setup()` lifecycle, and asserts the report screen AND its escape
hatch survive resume.

## 2. A settings tier could still kill the process at bind time (SettingsActivity)

The v0.2.82 post-mortem proved the mechanism: on this androidx version the
fragment's `onCreatePreferences` can run during `dispatchActivityCreated` from
`onStart` - AFTER `onCreate`'s guarded `commitNow()` returned - so a throw
while the preferences bind escapes every tier switch and kills the process.
v0.2.82 removed the one known landmine (the null `PreferenceManager` read);
the STRUCTURAL hole stayed: any future/device-specific throw in any tier is
"entering settings crashes" again, and (via §1) a dead app.

**Fix:** both fragments now catch `Throwable` inside `onCreatePreferences`
itself and degrade by posting to the decor view (outside the fragment
dispatch, where transactions are legal again):

```
Prefs (XML) ──throw──▶ post { replace(CodePrefs) } ──throw──▶ post { renderLastResort }
```

`renderLastResort` is the existing plain-widget screen, extracted verbatim
(including the v0.2.85 mouse rows) plus a "Retry settings" button. Opening
Settings can now never crash - the claim the three tiers always made.

## 3. The v0.2.85 mouse path: three seams, all in the new code

Audit of "what did the last changes do to desktop interaction": the finger
gesture machine is byte-for-byte the v0.2.84 logic (verified against the
diffs), the XTEST pump and `inject*` entry points are untouched, and the JVM
wire proof still passes 28/28 including `fakeScrollBurst`. The regressions
live exactly at the seams where the new DIRECT mouse path meets everything
else:

### 3a. A stuck button 1 — "the desktop is not interactable"

The mouse path presses X button 1 on `ACTION_DOWN` and holds it until
`ACTION_UP`. But the stream can stop belonging to the mouse path without an
UP/CANCEL ever reaching it:

- a finger joins mid-press → `pointerCount == 2` → the `onTouchEvent`
  intercept no longer matches → the finger state machine (which knows nothing
  of `mouseHeldButton`) owns the rest of the stream;
- a ROM reclassifies the lift between DOWN and UP (toolType/source flicker) →
  the UP goes down the finger path;
- the window loses focus or the view detaches mid-press.

In every case the button stayed DOWN in the server, and a button left down
swallows every later click - the exact v0.2.78/79 click-swallow signature,
reintroduced through a new seam. `releaseMouseButtons`' own comment claimed
"even a new DOWN goes through here"; the code never did.

**Fix:** `releaseMouseButtonsIfHeld()` - event-less release at the last known
X pointer position - is called from the finger path's DOWN, POINTER_DOWN, UP
and CANCEL, from `onWindowFocusChanged(false)` and `onDetachedFromWindow`, and
from the mouse DOWN itself. No-op when nothing is held, so the healthy paths
pay two int compares.

### 3b. The synthetic cursor hid forever — "there is no virtual mouse"

v0.2.85 hides the drawn cursor while `realMouseCursor` is true (no double
cursor under the system pointer - correct), but the flag only ever cleared on
a FINGER `ACTION_DOWN`. A disconnected mouse, an idle trackpad, or one stray
ROM hover event that classified as mouse left the pane permanently
cursor-less, breaking the v0.2.66 contract ("the pane must never be
cursor-less again") - and with a live frame that is exactly the field symptom.

**Fix:** the suppression now EXPIRES: `drawCursor` only skips while a real
mouse event arrived within `REAL_MOUSE_GRACE_MS` (2.5 s). Active mouse →
system cursor only (the v0.2.85 fix stays); silent mouse → the synthetic
cursor comes back. The mouse path also marks `cursorX/cursorY` as it injects
(it used to pass `markCursor = false`), so the reappearing cursor sits at the
REAL X pointer position, not a stale finger position.

### 3c. Touch hijack on desktop-mode ROMs + the double-press

`isMouseInput`'s fallback (`toolType UNKNOWN → trust SOURCE_MOUSE`) is what a
desktop-mode/PC-mode ROM feeds plain finger touches through. Those devices
lost the entire finger vocabulary (long-press right-click, two-finger scroll,
tap timing) and fed §3a's split-gesture stuck button. **Fix:** the
source-only fallback now additionally requires that the event's `InputDevice`
is not itself a touchscreen - a real mouse/trackpad never carries
`SOURCE_TOUCHSCREEN`, so the v0.2.85 feature is untouched.

Some trackpads report one physical click as BOTH `ACTION_DOWN` and
`ACTION_BUTTON_PRESS` - the old code pressed the X button twice, turning
single clicks into double clicks. DOWN now adopts an already-pressed
`mouseExtraButton` instead of re-pressing, BUTTON_PRESS skips the button the
DOWN path holds, and the release paths release a shared button exactly once.

## Verification

- `tools/build-apk/no-gradle-build.sh` (LOWMEM, committed keystore): compile
  clean, 536 classes all traceable, **`verify_apk.sh` 76/0** - the four new
  checks prove `degradeFromBind`, `renderLastResort`,
  `releaseMouseButtonsIfHeld` and `noteMouseActive` are in the shipped dex.
- **JVM wire proof 28/28** against a real depth-16 Xvfb (GetImage geometry,
  damage parking, `grabRegion565` + LUT, `fakeMotion`/`probeButtonDelivery`,
  `fakeScrollBurst` both axes) - the transport the fixes rely on, measured not
  assumed.
- New Robolectric regression test for the crash loop (§1); existing settings
  tests unchanged.
- Finger-path equivalence: on a device reporting `TOOL_TYPE_FINGER` (or
  `UNKNOWN` + `SOURCE_TOUCHSCREEN`), `isMouseInput` returns false exactly as
  in v0.2.84, `realMouseCursor` never sets, and every new release call is a
  no-op - the finger gesture machine is bit-for-bit the v0.2.84 behaviour.
- No guest-side changes; `ENV_VERSION` stays 77.

## Field acceptance targets

1. Enter Settings on the HUAWEI MRDI-W09: it opens (any tier), never kills
   the app; if a tier fails, the fallback screen shows with "Retry settings".
2. After ANY crash, the next launch shows the report screen and stays up;
   "Try starting EmuX anyway" returns to a working app.
3. Mouse/trackpad: click, right-click, drag, wheel scroll as in v0.2.85;
   after ~2.5 s of pointer silence the drawn cursor is back at the true X
   position; a finger touch anywhere instantly restores the finger cursor.
4. Touch the screen mid mouse-drag, or background the app mid-click, then
   click again: the desktop still responds (no swallowed clicks).
