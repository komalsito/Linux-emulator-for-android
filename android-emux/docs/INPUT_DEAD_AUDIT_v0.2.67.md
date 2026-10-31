# v0.2.67 — INPUT-DEAD AUDIT, ROUND 2: the two failure classes a connect-time proof cannot see

Audit of `komalsito/Termux2` (EmuX) at `a6803af` (v0.2.66, vc166) against a NEW
field capture taken ON v0.2.66 (two screenshots, `Screenshot_20261031_*`), i.e.
against a build that already contains every fix of the v0.2.66 input-dead
audit. The desktop still shows, and still nothing opens. This document names
the two remaining failure classes, proves each reachable from the shipped
code, and explains why ten versions of green diagnostics could not see them.
Companion change set: branch `fix/input-dead-v0.2.67` (vc167, ENV_VERSION 72).

**TL;DR — v0.2.66 proved the input path ONCE, at connect time, on ONE plane.
The field failure lives in what happens AFTER that proof, BETWEEN the two
planes:**

| # | Defect | Where | Effect on the user |
|---|---|---|---|
| S | **The capture plane can silently stream a DIFFERENT X server than the one the control plane injects into.** `emux-capture`'s preflight probe list ended with `:0` / `127.0.0.1:0`; ONE transient failure on the session display at capture start pinned the stream to `:0` for the whole session — and `:0` is exactly the display a zombie Xvfb from a previous app life answers on forever. `recheck_display` compared the marker with the `$DN` *variable*, never with the `$DISPLAY` actually in use, so the split never healed. | `emux-capture` preflight + `recheck_display` | The pane films the zombie's old desktop (icons, dead panel, zero damage → steady `LIVE 0.5 fps`) while every tap lands on the unseen session server: `proof=ok`, `touch>0`, `inj>0`, nothing opens — forever |
| D | **A control-plane connection that dies AFTER the proof keeps owning input forever.** `X11Client.connected` only flips in `connect()`/`close()`; the inject path never reads the socket, so an Xvfb restart on the SAME display number (LMK kill on a non-rooted device → xvfb watchdog relaunch), a proot socket drop or a server-side disconnect leaves a corpse: every `fakeButton` throws inside `runCatching`, the note lands in the history ring that frames paint over, the badge stays green on the connect-time proof, and the FIFO fallback cannot engage because `xtest()` still returns the corpse. | `DesktopService.xtest()/inject*`, `X11Client.connected` | Live desktop (capture opens a fresh xwd connection per frame and recovers transparently), green badge, zero response to any touch |
| H | **The one badge line that could have disambiguated this in the field was painted UNDER the gesture legend** (both at `sp(40)`), so in every screenshot it overprinted into garbage. | `DesktopView.drawPane/drawGestureHint` | Ten versions of field screenshots whose most important line was unreadable |

The v0.2.66 fixes (double-tap→double-click, fallback gate, Alpine package
list, portrait geometry, proof clamp, badge re-statement) are all REAL and all
present in the device build — the dex of the shipped `emux-debug.apk` was
verified to contain `drawCursor`, `onHoverEvent`, `CHIP_FILL`, `agent=idle`
and manifest `versionName 0.2.66`. They fixed the legs they measured. The
failure moved to the joints between legs.

---

## 1. The evidence (read off the v0.2.66 field screenshots)

Isolating the green HUD pixels (the proof line is overprinted by the grey
gesture legend — defect H) yields the third badge line verbatim:

```
LIVE 0.5 fps
INPUT: XTEST (control plane)
proof=ok touch=18 inj=36 agent=idle
```

plus, from the Terminal tab of the same device: `root: not available`,
`musl loader/busybox: FAILED`, `package manager: cannot run`,
`Xvfb (desktop): OK`.

Each leg of that line, and what it actually proves:

| Leg | Value | Proves | Does NOT prove |
|---|---|---|---|
| `touch=18` | 18 ACTION_DOWNs reached `DesktopView` | the view hierarchy delivers fingers | anything beyond the view |
| `inj=36` | 36 `inject*` calls (18 taps × press+release) | the app leg wrote 36 flushed FakeInput requests **into the control-plane socket** | that the socket's far end is the server being streamed, or is alive at all |
| `proof=ok` | at CONNECT time the server behind that socket honoured a FakeInput motion (QueryPointer round-trip) | the wire encoding and that one server, once | anything after the proof; any other server |
| `agent=idle` | agent marker present, xdotool present, no agent clicks | the fallback exists | that it was ever needed (it was not: `xtest()` owned input) |
| `LIVE 0.5 fps` | frames arrive at the idle-heartbeat rate | capture is connected to SOME living server | that it is the server input goes to |
| desktop pixels | icons present, **no tint2 panel**, solid `#20344d`-family wallpaper, no damage ever (fps never spikes despite 18 taps) | a real, furnished-but-partial desktop, completely untouched by the taps | which session it belongs to |

`inj=36` with zero damage on the streamed desktop is the contradiction that
kills every "the transport is fine" reading: 36 button events that a visible
pcmanfm desktop received would select, rubber-band, focus or open SOMETHING,
and each of those is damage, and damage moves the fps badge. Therefore the
events are not arriving at the streamed server. Either they arrive at another
server (S) or at a dead socket (D).

Corroborating pixels for S: the streamed desktop has the v0.2.45+ seeded icons
but NO panel — the signature of an OLD session's desktop whose tint2 died
(the wedge/LMK/watchdog history in `emux-x11`) while its Xvfb + pcmanfm
survived, i.e. precisely a zombie the project's own comments call unkillable
on this ROM ("the field ROM hides other processes' cmdlines, so the host
pid-scan kill has silently no-oped for five versions", emux-x11 v0.2.6).
Corroborating pixels for D: `root: not available` + the v0.2.47 LMK note
("protecting the Terminal (oom_score_adj -300) was DENIED … under memory
pressure Android can still reap the terminal's proot") — on this device
desktop processes, Xvfb included, are killable mid-session, and the xvfb
watchdog restarts Xvfb **on the same display number**, which is invisible to
the v0.2.52 rotation watcher (it only reacts to a NUMBER change).

## 2. Defect S, from the shipped code

`emux-capture` (v0.2.66, preflight):

```sh
for d in ":$DN" "127.0.0.1:$DN" ":0" "127.0.0.1:0"; do
  if DISPLAY="$d" "$XWD" -root -silent >/dev/null 2>>/tmp/emux_cap.err; then
    export DISPLAY="$d"; break
  fi
done
```

and the rotation follow:

```sh
recheck_display() {
  ND=$(tr -dc '0-9' < /tmp/emux_display_num 2>/dev/null)
  if [ -n "$ND" ] && [ "$ND" != "$DN" ]; then   # <-- $DN, the VARIABLE
    ...
```

The sequence on the field device:

1. Session start deletes `X0..X4` socket files and sweeps guests; the sweep
   no-ops on this ROM (hidden cmdlines), so the previous life's Xvfb keeps
   `:0` (unix inode unlinked but tcp 6000 still accepting — the v0.2.17
   "CONNECT, not bind" note documents exactly this zombie behaviour).
2. The app picks a free `n>0`; `emux-x11` brings the session server up on
   `:n` and publishes `/tmp/emux_display_num = n`.
3. The capture gate sees the session socket and starts `emux-capture … n`.
   At that instant the session server is mid-restart (LMK/watchdog) or its
   unix socket is not yet translated by proot: probes `:n` and `127.0.0.1:n`
   both fail ONCE … and the fourth probe, `127.0.0.1:0`, SUCCEEDS — the
   zombie answers tcp forever.
4. `DISPLAY` is now `:0` for the whole session. `recheck_display` compares
   the marker (`n`) with `$DN` (`n`) — equal — and never touches `DISPLAY`.
   The stream films the zombie: old icons, dead panel, idle damage rate.
5. The control plane connects to `:n` (marker), proofs `ok` on `:n`, injects
   36 events into `:n`. The user's fingers tap the zombie's icons. Nothing
   opens. Every counter is green.

Nothing in v0.2.66 cross-checks the two planes, so nothing can notice. The
same split is reachable without a zombie: any marker race where the control
plane adopts a number the capture never followed (unix-vs-tcp fallbacks,
`firstOrNull` socket scan in the capture gate) produces it symmetrically.

## 3. Defect D, from the shipped code

`X11Client`:

```kotlin
@Volatile var connected = false; private set   // set true in connect()
fun close() { connected = false; … }           // the ONLY other write
```

`DesktopService`:

```kotlin
private fun xtest(): X11Client? =
    x11?.takeIf { it.connected && it.xtestOpcode > 127 && !xtestIgnored }

fun injectClick(…) {
    …
    runCatching { c.fakeButton(…); surfaceXtestErrors(c) }
        .onFailure { noteInputDropped("XTEST click failed: $it") }  // history ring: invisible under frames
```

A write to a socket whose peer died throws; `runCatching` converts that into
a note nobody can see once frames flow; `connected` stays `true`, so
`xtest()` keeps returning the corpse, so `inputLine()`'s gate keeps returning
false, so the guest agent — the transport whose whole purpose is to back a
dead control plane — never receives a line. Meanwhile `emux-capture`, which
spawns a fresh `xwd` per frame, reconnects to the restarted server on the
SAME display number and keeps streaming a perfectly live desktop. Badge:
green, `proof=ok` (from before the restart), `inj=` climbing on writes that
vanish. This is "input dead" with every diagnostic green, produced by a
single LMK kill on a device without root — the exact device profile in the
screenshots (`root: not available`).

## 4. Why ten versions missed this (again)

1. **Every proof is a connect-time proof.** v0.2.59 proved the handshake,
   v0.2.65 proved the server, v0.2.66 proved the gesture. The field failure
   begins AFTER all three, so all three stay green while it happens.
2. **The planes are reconciled by eventually-consistent files, with
   per-plane fallbacks.** Marker, socket scan, tcp/unix fallbacks and (until
   now) a hardcoded `:0` last resort each pick a display independently;
   agreement is a hope, not an invariant.
3. **Failure notes go to the surface frames paint over.** `noteInfo` feeds
   the history ring; once `State.CONNECTED` the bitmap covers it. The ONE
   line painted on the bitmap (the proof line) was itself overprinted by the
   gesture legend (defect H), so even the designed-for-screenshot evidence
   was unreadable in every screenshot taken since v0.2.65.
4. **The harnesses live on the build host**, where zombies do not survive
   sessions and the LMK does not kill Xvfb: `desktop_click_test.sh` proves
   the wire and the double-click contract against a local Xvfb that never
   dies mid-test and is the only server in existence.

## 5. The v0.2.67 change set (branch `fix/input-dead-v0.2.67`)

Guest (`emux-capture`):
1. **The `:0` last resort is gone.** The preflight probes ONLY spellings of
   the session display, waiting up to 20 s; if the session display never
   opens, capture EXITS LOUDLY (the app respawns it) instead of silently
   filming another desktop. A capture that cannot reach its own server is a
   visible failure; a capture filming a zombie is an invisible one.
2. `recheck_display` compares the marker with **`${DISPLAY##*:}`**, the
   display actually in use, and only follows the marker if the new display
   actually opens (never trade a living stream for a stale marker).
3. Every loop publishes **`/tmp/emux_cap_display` = "<display> <epoch>"** —
   the capture plane's statement of what the user sees.

App (`DesktopService`):
4. **Plane agreement invariant**: a 5 s watcher reads
   `/tmp/emux_cap_display`; if fresh and different from the control plane's
   display, the control plane RECYCLES onto the capture's display (input
   follows the pixels, by definition). `connectControlPlane` likewise prefers
   a fresh capture publication over the marker when (re)connecting.
5. **Continuous proof**: the same watcher round-trips a QueryPointer on the
   control plane; two consecutive misses (or a throwing write) recycle the
   connection. Reads stay stream-safe (`readLock` + sequence-matched
   `requestReply` already serialize concurrent readers).
6. **Dead-socket recycling**: two consecutive failed injection writes →
   `recycleControlPlane()`: close the corpse, set `inputProof=pending`, hand
   input to the guest agent IMMEDIATELY (`xtest()` goes null so the
   `inputLine` gate opens), reconnect + re-proof in the background. `inj=`
   can no longer climb on writes that vanish.
7. Badge honesty, round 3: the proof line gains
   `fail=<write failures> ctrl=:<control display> cap=:<capture display>`,
   so the next field screenshot names the class without adb; the gesture
   legend moves to `sp(52)/sp(62)` (it was painting ON the proof line at
   `sp(40)`, defect H) and its text matches the v0.2.66 contract
   (`double-tap=2xclick`, `FIT=fill`).
8. `injectChar` no longer falls through to a gate that drops: a failed
   `typeChar` on a live XTEST is noted (keymap miss) or recycles (write
   threw) instead of vanishing.

Guest (`emux-x11`):
9. The input agent is now `start_agent()` with a pidfile
   (`/tmp/emux_agent_pid`); the governor relaunches it when the LMK reaps it,
   so the fallback transport exists exactly when defect D needs it.

Packaging: `ENV_VERSION` 71→72 (re-seeds `emux-x11`/`emux-capture` onto
existing installs without reinstall), versionCode 167 / versionName 0.2.67.

## 6. Prove-the-fix matrix (run on the build host AND on device)

Host regression (extend `tools/x11probe/desktop_click_test.sh`):
- S1 split-brain: start zombie Xvfb `:77` with furniture + session Xvfb `:78`;
  make `:78` refuse xwd for 3 s at capture start; assert emux-capture EXITS
  (v0.2.66: streams `:77`) and that with the app-side watcher simulated the
  control plane ends on `:78`.
- D1 dead socket: connect the Kotlin input path to Xvfb `:78`, kill and
  restart `:78` on the same number, inject; assert `injectFail>0`, transport
  flips to `agent`, and a reconnect re-proofs `ok` within 35 s.

On device, ONE screenshot of the top-left corner now disambiguates everything
without adb:
- `fail>0` or `proof=conn-dead/keepalive…` → class D fired and recycled;
- `ctrl=:a cap=:b` with a≠b persisting → class S still alive somewhere (it
  cannot persist: both planes converge within one 5 s tick);
- `proof=ok touch>0 inj>0 fail=0 ctrl=:n cap=:n` and STILL nothing opens →
  the remaining fault is inside the streamed desktop itself (window/client
  layer), a new finding, and the badge will have said so.

On device, right now, on the v0.2.66 build (no install needed) — the class
can be confirmed from the Terminal tab:

```sh
echo "--- planes"; cat /tmp/emux_display_num; ls /tmp/.X11-unix/ 2>/dev/null
grep -a "after xwd preflight" /tmp/emux_desktop_diag.log | tail -3
grep -aE "control plane UP|display rotated|click failed|input dropped" \
     /tmp/emux_desktop_diag.log | tail -10
tail -20 /tmp/emux_agent.log 2>/dev/null
```

Class S signature: `emux-capture: display=:0 after xwd preflight` (or any
number ≠ `/tmp/emux_display_num`), and/or two sockets in `/tmp/.X11-unix`.
Class D signature: `XTEST click failed: …SocketException…` /
`XTEST click failed: …EPIPE…` lines in the diag log beside a live stream.

## 7. Scope notes

- No protocol change, no new dependency, no manifest/permission change.
- Wire encoding untouched (v0.2.66 §2 measured it byte-exact; re-verified
  here against `xclient_probe.py`).
- v0.2.66's seven fixes are kept verbatim; this round adds the joints:
  post-connect liveness, cross-plane agreement, and a badge that cannot be
  overprinted or outrun by a restart.
