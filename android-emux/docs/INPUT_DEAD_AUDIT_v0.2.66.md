# v0.2.66 — INPUT-DEAD AUDIT: why the desktop shows but nothing is clickable

Full-project audit of `komalsito/Termux2` (EmuX) at `4a8131b` (v0.2.65), with
wire-level reproduction on a live X stack and a real Alpine 3.20 rootfs.
Companion patch: `INPUT_DEAD_FIX_v0.2.66.patch` (7 defects, 5 files).

**TL;DR — the input problem survived 10 versions because it was never one bug.
It is five independent defects stacked on top of each other, and every round
fixed one layer and then measured the wrong layer to prove it:**

| # | Defect | Where | Effect on the user |
|---|---|---|---|
| 1 | **Double-tap is eaten by the view toggle**, so a real double-click never reaches X | `DesktopView.onTouchEvent` ACTION_UP | Desktop icons, files and dialogs can *never* open — "nothing opens" |
| 2 | **The xdotool fallback is structurally unreachable**: its gate tests `connected`, not "usable" | `DesktopService.inputLine()` | When XTEST fails, input is 100 % dead instead of falling back — v0.2.65's headline fix is inert |
| 3 | **Alpine's desktop install aborts**: `apk add … wmctrl` on a package that does not exist on Alpine | `emux-x11` | On the *default* distro no openbox/tint2/pcmanfm/xterm/xdotool is ever installed |
| 4 | **xdotool is missing from the Alpine/Arch/Fedora package lists** | `emux-x11` | The fallback has no engine on 3 of 5 distros |
| 5 | **Portrait phones get a SQUARE 1280×1280 X screen** (`longSide / a` instead of `longSide * a`) | `DesktopService.desktopGeometry()` | Huge letterbox bars, ~1.6 M px/frame (part of "LIVE 0.5 fps"), mapping that never matched the pane |
| 6 | Green "INPUT: XTEST" badge is painted from a flag set at connect time and never revisited | `DesktopService.connectControlPlane` / proof | The badge lied exactly when input was dead (the v0.2.65 complaint) |
| 7 | The input proof can **false-pass** when the pointer sits near the far edge | `DesktopService.runInputProof` | A server that ignores FakeInput is certified "ok" |

The X11 wire encoding — the thing every previous round suspected — is **not**
broken. §2 proves it byte-for-byte against a live server.

---

## 1. What the project actually is (architecture map)

`android-emux` is a single-module Android app (Kotlin, ~13.3 k LOC) plus a
99 KB guest launcher script. Two surfaces:

- **Terminal tab** — `VtTerminalView` + `TerminalSession` (real PTY via
  util-linux `script(1)` exec'd through the musl loader) under bundled **proot**.
- **Desktop tab** — an X11 desktop *inside* the distro, with three independent
  pipelines that must agree on the same display number:

```
finger ─ DesktopView.onTouchEvent ─ mapToX(view→frame) ─ DesktopService.inject*()
                                         │
              ┌──────────────────────────┼───────────────────────────┐
              ▼ (XTEST)                                             ▼ (fallback)
        X11Client (raw X11     unix socket <rootfs>/tmp/.X11-unix/Xn   FIFO /tmp/emux_in
        protocol, 36-byte       or tcp 127.0.0.1:6000+n                 → guest xdotool agent
        FakeInput requests)                                            (one process/event)
              │                                                         │
              └────────────────────► Xvfb :n  ◄─────────────────────────┘
                                    openbox + tint2 + pcmanfm --desktop
                                            ▲
              emux-capture (xwd -root → xwdtopnm → pnmtoplainpnm → RGB565
              → TCP socket) streams frames back to DesktopView
```

Display number is negotiated three ways: the app picks a free `n`
(`DesktopService` line ~482), `emux-x11` may *walk away* to a higher `n`
(occupied-socket ladder) and publishes it in `/tmp/emux_display_num`, and two
watchers (control plane ~line 775, capture gate ~line 843) try to follow.

Input has two transports since v0.2.59: **XTEST FakeInput** over one persistent
socket (fast, real), and a **rootfs FIFO + guest `xdotool` agent** (slow,
fallback). v0.2.65 added an on-device *proof*: inject a motion, read the pointer
back with QueryPointer (opcode 38), compare; anything but `ok` sets
`xtestIgnored` and is supposed to hand input to the agent.

## 2. What I verified empirically (and what that rules out)

Built a reproduction rig in a Debian 12 sandbox: Xvfb 21.1 + Openbox 3.6.1 +
pcmanfm + tint2 + xterm, started with the **guest's exact command line**
(`Xvfb :n -screen 0 RES -listen tcp -ac -extension MIT-SHM`), plus a real
Alpine 3.20.3 minirootfs chroot. `tools/xprobe/xclient_probe.py` is a
byte-exact re-implementation of `X11Client`'s handshake, QueryExtension,
GetKeyboardMapping, `xtestFake` (36-byte body, rootX/rootY at request offsets
24/26) and the v0.2.65 proof.

| Experiment | Result | Consequence |
|---|---|---|
| Handshake + QueryExtension(XTEST) + GetVersion, guest flags | `present=1 major=131`, version 2.0 | server side is fine |
| `xtestFake` motion + button into a live `xev` window under Openbox | xev logged MotionNotify + ButtonPress + ButtonRelease, `synthetic NO`; pointer moved; **0 X errors** | **the wire encoding is correct; the 36-byte layout and offsets match python-xlib's `Pad(8)`/`Pad(8)`** |
| The v0.2.65 proof against this server | `ok`, 48 px delta honoured | proof works when the server is honest |
| Single injected click on a pcmanfm desktop icon | **nothing happens** | desktop items are not single-click by default |
| Injected double-click on the same icon | pcmanfm's **"Execute File" dialog appeared** (screenshot captured) | **double-click activates desktop items — and is exactly what the app cannot produce** |
| `apk add --no-cache xvfb … wmctrl` in the Alpine 3.20.3 rootfs | `ERROR: unable to select packages: wmctrl (no such package)` → **whole transaction aborts** | **Alpine never installs any desktop furniture**; per-package simulation: `wmctrl` NOT-FOUND, `xdotool` AVAILABLE |
| Shipped `emux-debug.apk` | dex contains `queryPointerRoot`, `emux-input-proof`, `proof=`, `agent=no-xdotool`; `assets/bin/emux-x11` byte-identical to source | **the APK is genuinely v0.2.65 — not another stale-APK round** |
| `-noreset` behaviour | Xvfb survives last-client disconnect either way | ruled out as a cause |

## 3. The defects, in detail

### 3.1 Double-tap made double-click impossible  *(the "nothing opens" cause)*

`DesktopView.onTouchEvent`, `ACTION_UP` (v0.2.37 → v0.2.65):

```kotlin
val isDoubleTap = !dragging && !panning && !longPressFired &&
    nowMs - lastTapUpMs < 300 && abs(...) < 40 && abs(...) < 40
if (isDoubleTap) {
    coverMode = !coverMode          // view toggle consumes the gesture
    ...
    return true                     // ← second tap NEVER reaches X
}
```

Everything on an X desktop activates on the *second* ButtonPress: pcmanfm
desktop icons, GTK file dialogs, file managers (proved in §2: one click =
nothing, two = dialog). So on a perfectly healthy transport the desktop
received one click per gesture forever — the user taps an icon, sees the
desktop *flip between fit and fill*, and nothing opens. It also silently
changed `coverMode` mid-session, which changes the scale used by `mapToX`,
shifting every subsequent tap.

**Fix:** deliver the second tap as a real click (double-tap = double-click);
move the fit↔fill toggle to a new `FIT` chip on the helper bar (the bar and its
hit-test are already generic over the chip list). README gesture table updated.
Additionally seed `single_click=1` in `~/.config/libfm/libfm.conf` (only when
the user has no setting yet) so a single tap also opens items — the friendlier
contract on a phone.

### 3.2 The v0.2.65 fallback is unreachable by construction

`DesktopService.inputLine()` (the only enqueue point for the FIFO agent):

```kotlin
val c = x11
if (c != null && c.connected) return false      // "XTEST owns input"
```

but `inputLine` is only ever called on the branch where `xtest() == null`, and
`xtest()` is

```kotlin
x11?.takeIf { it.connected && it.xtestOpcode > 127 && !xtestIgnored }
```

So on a server that ignores FakeInput (`xtestIgnored = true`, `connected =
true`) every one of the eleven `inputLine` call sites returns `false` and drops
the event. The doc's promise — *"a failed proof now costs the user the slow
transport instead of no transport"* — is false: it costs **no** transport.
This is why v0.2.65 changed nothing on the device.

**Fix:** gate on `xtest() != null` (i.e. "usable"), not on `connected`.

### 3.3 Alpine: one bad package name killed the whole desktop

`emux-x11` (Alpine branch, and Alpine is `DEFAULT_DISTRO`):

```sh
apk add --no-cache xvfb xkbcomp xkeyboard-config xwd netpbm openbox xterm \
    tint2 pcmanfm mousepad galculator wmctrl
```

`wmctrl` does not exist on Alpine 3.20 (verified in the real minirootfs), and
`apk add` aborts the entire transaction on one unknown name. Result on a fresh
Alpine install: **no X server, no WM, no panel, no icons, no xterm, no
xdotool** — a black pane, or nothing. `xdotool` *does* exist on Alpine and was
simply never listed. (Installs that got their furniture before v0.2.45 added
`wmctrl` keep working, because the warm-skip makes `NEEDREST=0` — which is why
this has looked intermittent instead of universal.)

**Fix:** corrected list (`-wmctrl +xdotool`) plus a per-package retry so one
unknown name can never abort the desktop again; `xdotool` added to the Arch
and Fedora lists as well. `ENV_VERSION` 70 → 71 so existing installs re-seed.

### 3.4 The badge that lies

`connectControlPlane()` sets `inputTransport = "xtest"` (and `inputAgentUp =
true`) at connect time and nothing revisits it when the proof later fails, so
the pane kept painting a green `INPUT: XTEST (control plane)` over a dead path
— precisely the v0.2.65 field complaint. **Fix:** on proof failure re-state the
transport from what will actually carry events (`agent` if the guest agent is
up, else `down`).

### 3.5 Portrait phones got a square desktop

`desktopGeometry()`:

```kotlin
val shortSide = ((longSide / a).coerceIn(360f, 1280f).toInt() / 2 * 2)
```

For a portrait pane `a = w/h < 1`, so `longSide / a > 1280` and clamps to
`longSide` itself → **every portrait phone got 1280×1280**. Consequences: the
"no letterbox bars" promise is false on every phone (the 1280×1280 frame is fit
into a ~0.46-aspect pane), capture encodes ~1.64 M px per frame instead of
~0.76 M (a large share of the field's `LIVE 0.5 fps`), and taps in the bars
clamp to the frame edge. **Fix:** `if (a >= 1f) longSide / a else longSide * a`.

### 3.6 The proof can certify a dead server

`runInputProof()` aims at `p0 + 48` clamped into the screen. When the pointer
already sits at/near the far edge, the clamp makes the target **equal** `p0`,
so `p1 == p0 == target` and a server that ignored the motion reports `ok`.
**Fix:** pick the direction with room (`+48` or `-48` per axis).

### 3.7 `injectKeycode` bypassed the gate

It called `x11?.fakeKey(...)` directly — the one entry point that kept
injecting into a server the proof had declared dead while every other path had
fallen back. **Fix:** route through `xtest()` with a loud drop note.

## 4. Why ten versions missed this

1. **Every harness lives on the build host.** `gesture_e2e.sh`,
   `kotlin_xtest_e2e.sh` etc. drive the compiled input path against a local
   Xvfb — the one environment where none of §3.1–§3.3 can occur (Debian has
   `wmctrl`; the harness calls `injectClick` twice by hand, producing a real
   double-click; the FIFO gate is never exercised).
2. **The diagnostics measure the layer just below the broken one.** The
   badge proves the socket; the proof proves the server; nothing proved that a
   *gesture* becomes a *double-click*, or that the fallback can be reached.
3. **The failure space was partitioned by counters that were all green.**
   `touch>0 inj>0 proof=ok` is consistent with §3.1: the transport is perfect
   and the desktop still opens nothing.
4. **Packaging masks guest bugs.** A fresh Alpine install can't show a desktop
   at all, so field reports from Alpine were read as "capture/visibility"
   problems for five versions.

## 5. What is still needed from the device (one screenshot)

Open the Desktop tab, tap an icon and type, then screenshot the top-left
corner. The third badge line now disambiguates everything:

- `proof=ok touch>0 inj>0` and still nothing opens → §3.1 (fixed here).
- `proof=ignored` / `proof=rejected:…` → the transport really is dead on this
  ROM; with §3.2 fixed the events now reach the guest agent instead of
  vanishing, and §3.3/§3.4 make sure the agent exists and the badge says so.
- `touch=0` while tapping → an overlay/pointer-events problem in the view
  hierarchy (nothing in this audit; would be a new finding).

## 6. Scope notes

- `ENV_VERSION` 70 → 71 (guest script changed: Alpine lists, libfm seed).
- No new dependency, no protocol change, no manifest change.
- Patch: `INPUT_DEAD_FIX_v0.2.66.patch` — 5 files, +94/−30.
- Repro tooling promoted into the repo: `tools/x11probe/xclient_probe.py`
  (byte-exact `X11Client` replica: handshake, QueryExtension, keymap,
  `xtestFake`, QueryPointer proof) and
  `tools/x11probe/desktop_click_test.sh`, which runs the whole §2 matrix as a
  regression test — guest-flag Xvfb + openbox + pcmanfm, xev as the delivery
  witness, and the single-vs-double click assertion. Currently **4/4 PASS** in
  a Debian 12 sandbox, so the double-click contract and the wire path are now
  tested instead of remembered. The Alpine package names need the same
  treatment in CI (a 4 MB minirootfs chroot is enough).
