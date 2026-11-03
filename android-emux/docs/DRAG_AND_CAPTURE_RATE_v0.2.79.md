# v0.2.79 — the drag that was never a drag, and a frame rate you can now see and change

Two field reports, one release:

> *"I can't drag items inside the desktop (I am able to click and it works)."*
> *"It feels very slow… the fps now shows at 0.5."*

The first was a gesture that never sent a button. The second was real, is
arithmetic, and is now both visible and adjustable.

---

## 1. The drag: motion events for a button that was never pressed

A drag in X is three things in order: `ButtonPress`, `MotionNotify` **with the
button's mask set in the event's `state` field**, `ButtonRelease`. The mask is
not something the sender chooses — the server fills it in from its own record of
which buttons are logically down. That is what distinguishes a drag from a
hover, and it is the part a motion-only gesture cannot fake.

What `DesktopView` actually sent for a one-finger drag:

```kotlin
// ACTION_MOVE, past the 18 px threshold
dragging = true
desktop?.injectMotion(x, y)          // MotionNotify only — no button in it
// ACTION_UP
desktop?.fakeButtonReleaseOnly(x, y) // ButtonRelease(1) … never pressed
```

`injectMotion()` is `xtestFake(FAKE_MOTION, 0, x, y)` — there is no button
anywhere in it. So the server saw a pointer wandering over the desktop, followed
by a release for a button that was not down. No toolkit can read that as a drag:
icons would not rubber-band, windows would not move by the titlebar, sliders
would not slide, text would not select.

**Why right-drag worked and left-drag did not.** Long-press really does press
first (`injectClick(x, y, button = 3, press = true)`) and holds it, so
long-press-then-move was a genuine button-3 drag. The left path had no
equivalent. The gesture table in the README has advertised "drag → move / window
drag" since v0.2.60; the code never did it.

### Measured

`tools/x11probe/drag_test.sh` brings up a real Xvfb + Openbox running the rc.xml
the launcher ships today, and `drag_probe.py` injects **both** sequences at a
window the WM frames like an application's. 7/7:

```
PHASE A - events the CLIENT window receives (icon/slider/selection drags)
  OLD  motion-only + unreferenced release: MotionNotify(0)x9
  NEW  press, motion, release:             MotionNotify(0) ButtonPress(1)
                                           MotionNotify(0)[b1]x8 ButtonRelease(1)

PHASE B - dragging the TITLEBAR (rc.xml binds Titlebar/Left/Drag -> Move)
  OLD  frame 479,237 -> 479,237  moved=(+0,+0)
  NEW  frame 479,237 -> 629,337  moved=(+150,+100)
```

`[b1]` is `state & Button1Mask`. Phase B is judged by where the window ends up,
not by events, and that is worth understanding: on a titlebar drag the press
lands on the **frame** window (Openbox's), and Openbox then takes an *active*
pointer grab for the interactive move — so the client legitimately sees nothing
at all. The window moving exactly the distance the finger travelled is the whole
proof. (This is also the case where `GrabControl(impervious)` would *break*
things: while impervious, Openbox's active grab stops seeing our motion, so the
window stops following the finger. See `CLICK_SWALLOW_v0.2.78.md` §5.)

### The gesture now

| gesture | what the desktop receives |
|---|---|
| drag | **ButtonPress(1) where the finger landed**, motion (≤30/s), **ButtonRelease(1) where it lifted** |
| double-tap-and-**hold** | the same, but the button goes down on the hold — grab first, then drag, without travelling 18 px |
| double-tap (quick) | two clicks, i.e. a real double-click — **unchanged** (v0.2.66; the hold is `DT_HOLD_MS`=170 ms, well under `LONG_PRESS_MS`=450 ms, so the two never race for one finger) |
| long-press + drag | right-drag — unchanged |
| tap | click — unchanged |

The press goes where the finger **landed**, not where it is when the threshold
trips, so the 18 px of slop is part of the drag rather than a jump.

`leftHeld` is the invariant that keeps this safe: it means *button 1 is down in
the server*, and every exit path releases it — finger lift, `ACTION_CANCEL`
(parent stole the touch, screen off, palm rejection), and a second finger
turning the gesture into a pan or pinch. A button left down wedges every later
click, which is the failure mode this class of bug produces on a touchscreen.
The release test is `leftHeld`, not `dragging`: a double-tap-and-hold that never
moved still has a real button down to let go of.

## 2. The frame rate: bytes per frame over a pipe we can measure but not widen

`0.5 fps` was not a bug in the badge — it was the truth. One refresh costs
`W × H × 2` bytes at depth 16, and the app measures the pipe's throughput when
it connects (`nativeKbPerS`, painted on the STALE pane since v0.2.74). So:

```
time per refresh ≈ (W × H × 2) / measured KB/s
```

A 576×1280 portrait screen is 1.47 MB per frame; on the field device's measured
720 KB/s that is ~2 s per refresh — 0.5 fps, exactly as reported. (The badge
shows a *measured* `ms/refresh` rather than this quotient because the grab also
decodes and copies, so on a healthy pipe the measured cost exceeds the wire
time. The equation explains the number; it does not replace it.) The pane sweeps
the screen in bands sized to ~1.2 s of measured pipe, so at that speed one band
*is* the whole screen and there is no progressive paint to soften it either.

Two things stood between that and a smoother feel:

1. **The bytes.** The X screen size *is* the capture size: `GetImage` returns
   the server's own pixels, so there is no way to send fewer bytes per frame
   without a smaller screen — and the screen cannot be resized mid-session.
   Hence a setting, applied when the desktop next starts.
2. **A cap nobody could reach anyway.** The native loop was hardcoded to
   `minFrameMs = 333` (3 fps) while the socket path it replaced used
   `MAX_FPS_FOREGROUND = 30`. The pipeline every modern device runs was capped
   ten times lower than the legacy one, invisibly at 1280 (where the pipe alone
   takes ~2 s) and as the *only* limit once the resolution drops.

### What changed

- **Settings → "Desktop capture quality (frame rate)"** — the long side of the X
  screen: **1280 (default, unchanged)**, 960 (~1.8× the rate), 720 (~3.2×),
  540 (~5.6×). The multipliers are the pixel-count ratio, since rate scales with
  1/(long side)². The default deliberately does not move: nobody's desktop gets
  softer without asking. Smaller screens also scale the short-side floors, so a
  lower setting keeps the pane's aspect instead of being coerced into a squarer
  one (the v0.2.66 letterbox bug, one level down).
- **Settings → "Restart desktop now"** — the X geometry is fixed at start, so
  this is the mechanism, not a convenience. `stop()` kills the guest Xvfb from a
  coroutine, so the restart waits 2 s before starting: starting immediately can
  race a server that is still up, and the launcher's "adopt, don't fight" rule
  (v0.2.13) would then adopt the **old** Xvfb at the **old** geometry and the
  setting would silently do nothing.
- **Native foreground cap 3 fps → 10 fps** (`MAX_FPS_NATIVE_FOREGROUND`). Not 30:
  a native refresh re-reads and re-decodes the *full* screen every frame
  (GetImage → RGB565 → IntArray → Bitmap), so its per-frame CPU cost is much
  higher than the socket path's diff decode. 10 fps is where the pipe stops
  being the limiter on a healthy device without cooking the phone. The grab
  itself remains the real pacer — when it exceeds the budget the loop yields
  only 30 ms.
- **The badge is now actionable.** `LIVE 0.5 fps` said "slow" but not why or
  what to do. It reads:

  ```
  LIVE 0.5 fps  576x1280  720KB/s  2000ms/refresh    <- what the field reported
  LIVE 2.8 fps  242x540   720KB/s  354ms/refresh     <- same pipe, 540 px setting
  ```

  Frame cost, measured pipe, resulting refresh cost — the three terms of the
  equation above, on the framebuffer, so it survives in a screenshot. When the
quality setting has been changed but not applied, both the LIVE badge and the
launcher's status line say so, instead of letting you watch an unchanged rate and
conclude the setting is broken.

### What this does not do

It cannot make the pipe faster. Under proot the server's writes are translated,
and the only lever on the app side is how many bytes it asks for. A device whose
pipe measures 900 KB/s gets ~0.5 fps at 1280 and ~3 fps at 540 — that trade is
the user's, which is why it is a setting with the live numbers next to it rather
than a guess baked into a default.

## 3. Two build gates that let a launch-crashing APK pass

This release also found two holes in the tooling, both of which produced a
*green* build.

### 3.1 A compile failure that printed nothing

Two builds died at step 7 with the driver's entire output being:

```
== 7/10 compile (single kotlinc pass -> javac R + binding) ==
EXIT=1
```

The cause is in the driver itself:

```sh
sh compile_classes.sh > "$OUT/compile.log" 2>&1
CRC=$?          # under `set -e` this line NEVER RUNS
```

A failing command aborts a `set -e` script on the spot, so the assignment — and
the `COMPILE FAILED` branch below it, the only thing that prints `kc.log` — were
dead code on exactly the path they exist for. The real error was sitting in
`$OUT/kc.log` the whole time:

```
OpenJDK 64-Bit Server VM warning: INFO: os::commit_memory(...) failed;
error='Not enough space' (errno=12)
```

**Not physical RAM.** The box has 1 GiB of RAM and ~780 MB of it free at the
time, no swap, and a cgroup limit of exactly `1073741824`. `-Xmx700m` is the
*heap*; a JVM's footprint is heap + metaspace + code cache + JIT/GC thread
stacks. Kotlinc over 12.6k lines wants most of 600 MB of heap before native
memory is even counted, and the single pass at `-Xmx700m` does not fit in what
the platform process leaves of 1 GiB — it was not unlucky, it was over budget by
construction. Measured by sampling `memory.usage_in_bytes` every 250 ms across
one compile step (57 s, 217 samples):

| | cgroup usage |
|---|---|
| idle baseline | 325,783,552 B |
| peak — LOWMEM two-pass, `-Xmx600m` with `-XX:+UseSerialGC -XX:TieredStopAtLevel=1 -XX:MaxMetaspaceSize=160m -XX:ReservedCodeCacheSize=48m` | **1,029,697,536 B** of the 1,073,741,824 B limit — 96%, ~42 MB of headroom |
| same step, single pass, `-Xmx700m` | does not complete: `os::commit_memory … errno=12` |

So the compile JVM costs ~700 MB all in — up to 600 MB of heap plus the ~100 MB
of native memory those caps hold it to — and a hundred further megabytes of heap
is the difference between finishing and dying on a 64 KB commit. The repo already
had the `LOWMEM` two-pass split written for exactly this box (v0.2.76) and the
build command was not setting it. Fixed: the driver now captures the status with
`if …; then CRC=0; else CRC=$?; fi` and prints `compile.log` **and** `kc.log`.

### 3.2 An APK with no `ActivityMainBinding`, signed and verified

That LOWMEM build then completed successfully — `515 classes, all traceable`,
`passed=52 failed=0`, signed, "safe to adb install -r". One line in its log was
the whole story:

```
…/genbind/com/emux/databinding/ActivityMainBinding.java:11: error: cannot find symbol
```

Line 11 is `import com.emux.R;`. In LOWMEM mode R is compiled by the *first*
javac into `$OUT/jclasses`, and the second javac — the one that compiles the
binding — was given `-cp "$LIBS:$OUT/kclasses"`, without `jclasses`. Kotlinc was
handed the binding as a *source*, and kotlinc resolves Java sources but writes no
`.class` for them, so nothing else produced it either. javac's status went down
the same `| grep | head` pipe the driver's own comment warns about, the merge
audit counted 515 classes that all traced to real sources (true — the binding
simply was not among them), and the APK shipped without the class
`MainActivity.onCreate` inflates through on its first line.

Measured, parsing each APK's dex `class_defs` table:

```
v0.2.78 (known good)   DEFINED Lcom/emux/databinding/ActivityMainBinding;   8136 classes defined
the LOWMEM build       MISSING Lcom/emux/databinding/ActivityMainBinding;   8141 classes defined
```

Why all 52 checks passed: they grep dex **bytes** for method names, and a class
that is *referenced but not defined* still has its descriptor in the string
table — `MainActivity` names the binding either way. Byte-grep cannot tell the
two apart, so the one failure mode that matters most was structurally invisible
to the gate.

Three fixes, all in this release:

1. `compile_classes.sh` — `jclasses` on the binding javac's classpath; javac's
   status checked in **both** branches instead of piped into grep; and the
   artifact asserted, not the exit code (`ActivityMainBinding.class` must exist
   before the merge), because "javac succeeded" and "javac wrote the class we
   asked for" are different claims and only the second reaches the dex.
2. `no-gradle-build.sh` — status captured under `set -e`; failures print
   `compile.log` and `kc.log`.
3. `verify_apk.sh` — a new check that reads the dex's own `class_defs` table
   (fixed header offsets `0x38`/`0x40`/`0x60`, 32-byte `class_def_item`,
   `zipfile` + `struct`, no new dependencies) and requires the load-bearing host
   classes to be **defined**: the binding, both activities, the service, the
   view. The gate is now 53 checks. It discriminates — the broken APK above
   scores 52/1 with the new check as the sole failure, while v0.2.78 passes it
   (scoring 51/2 against this tree only because it is vc178, not vc179).

The general lesson is the same one v0.2.62 recorded when it added the
dependency-version checks: a verifier must fail on the thing that actually
breaks the app. "Every class traces to a source" and "every byte-count matches"
were both true of an APK that could not start.

### 3.3 A test that failed because a dead window manager still had a process

The regression run for this release came back `ladder_test.sh: passed=45
failed=1` — *"stage 2 removed the grabber"* expected no `openbox` and found one.
It was not a product regression. `pgrep -x openbox` matches **zombies**, and PID
1 in this container is a node process that never reaps, so every Xvfb and openbox
any harness has ever killed is still in the process table: ten `[Xvfb] <defunct>`
from five hours earlier, three `[openbox] <defunct>` from the `drag_test.sh` run
minutes before. The trap had already been documented and solved elsewhere in this
directory — `desktop_paint_test.sh`, `desktop_visible_ab.sh` and `doctor_test.sh`
all define an `alive()` that counts only processes whose `/proc/<pid>/stat` state
is not `Z`. `ladder_test.sh` (v0.2.78) missed it, and the new `drag_test.sh`
initially repeated it.

Which way the check lies matters more than that it lied. The same `pgrep` made
*"stage 2 removed the grabber"* **fail** against a WM that was dead, and would
have made *"openbox came up"* **pass** against one that never started — the
second is worse, because the probe would then have run against an unmanaged
display and reported results for a configuration nobody was testing. Both
assertions now use `alive()`; `drag_test.sh` checks its own `$OB` pid's state, and
its trap `wait`s after `kill` so a harness stops leaving zombies for the next one.
Measured: 3 zombie openbox before `drag_test.sh`, 3 after; `ladder_test.sh` back
to 46/0.

## 4. Reproducing

```sh
sh tools/x11probe/drag_test.sh      # 7 checks, ~9 s: wire sequence + a window that moves
```

`drag_probe.py` reuses `button_probe.py`'s `Probe` — one source of truth for the
CreateWindow/MapWindow/QueryTree/GetGeometry layouts, so a fix to one wire
encoding cannot leave the other probe asserting the old one.

The full regression run for this release, all against the shipped sources:

| suite | result |
|---|---|
| `drag_test.sh` | 7/7 — press/motion-with-Button1Mask/release, and a titlebar drag that moves the window exactly +150,+100 |
| `ladder_test.sh` | 46/0 |
| `button_delivery_test.sh` | 8-case matrix unchanged: A/B/D/E/H `DELIVERED`, C/F/G `SWALLOWED` (G is the `GrabControl(impervious)` negative) |
| `verify_apk.sh` | 53/0 |

The build gate:

```sh
LOWMEM=1 HEAP="-J-Xmx600m -J-XX:+UseSerialGC -J-XX:TieredStopAtLevel=1 \
  -J-XX:MaxMetaspaceSize=160m -J-XX:ReservedCodeCacheSize=48m" \
  sh tools/build-apk/no-gradle-build.sh          # expect passed=53 failed=0
```
