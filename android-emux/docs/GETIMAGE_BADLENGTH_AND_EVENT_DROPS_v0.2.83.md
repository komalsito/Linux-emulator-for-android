# v0.2.83 — the GetImage BadLength, the dropped DamageNotifies, and the drag that teleports

Field report (verbatim):

> desktop is very slow — slow to open, slow interactions, and **during drag
> there is no animation: it waits for input to stop and then the window
> appears at the destination** instead of following the finger.

Every previous round tuned around this symptom (bands, pacing, quality
ladder, mailbox fallbacks). This round found the actual causes — three of
them, stacked — and the first two are wire-protocol bugs that made the
native capture path fail **100 % of the time on every device since
v0.2.71**, which is why the app always ended up on the slow guest
pipelines the field kept reporting on.

## Root cause #1 — GetImage sent 24 bytes where the protocol defines 20

`xGetImageReq` is **5 units**:

```
 1   reqType = 73 (GetImage)
 1   format  (2 = ZPixmap)   ← the "minor" byte
 2   length  = 5
 4   drawable
 2   x   2 y   2 w   2 h
 4   plane-mask
───
20 bytes
```

`grabRegionArgb()` built the body with `ByteBuffer.allocate(20)` and passed
the whole array as the body — 4 bytes of body past plane-mask — so
`buildRequest()` computed `ceil((4+20)/4) = 6` units and sent a **24-byte**
request. Xvfb validates GetImage's length exactly: **`BadLength (16)`,
major 73, on every single grab**. Proven twice:

1. Raw Python on the wire (no app code involved): the 20-byte form gets a
   REPLY; the same request with 4 pad bytes gets `BadLength`.
2. `tools/x11probe/jvmwire` (new, see below) runs both forms through the
   real `X11Client` as check #0, on every harness run.

Consequence chain: native capture never produced a frame → the app fell
through to the proot guest pipelines (`emux-capture` / `emux-fbmail`:
per-frame `xwd` full-screen dumps through proot) → **"720 KB/s pipe"** —
the number eleven rounds of telemetry reported as the device's ceiling —
was measured from the broken fallback path, not from X11. The real
GetImage pipe on the same hardware class measures **~1.8 GB/s without
proot translation, ~875 MB/s through it** (`tools/x11probe/xbench.py`): one full
576×1280×16 screen in 0.8–1.6 ms, not 2 s.

**Fix** (`X11Client.grabRegionArgb`): 16-byte body (`allocate(16)`:
drawable+x/y/w/h+plane-mask), format byte 1 → ZPixmap(2) in the header,
length 5. The reply tail now lands in a caller-supplied buffer (`tailBuf`)
instead of allocating 2×1.47 MB per full-screen grab, and the RGB565→ARGB
conversion uses a 64 K-entry LUT instead of per-pixel math.

## Root cause #2 — `requestReply()` threw away every event it wasn't the reply to

The capture connection subscribes to DAMAGE (v0.2.81). While a GetImage
reply is in flight, the server keeps pushing `DamageNotify` packets for
whatever the desktop drew meanwhile. The old read loop's last branch was
literally *"events and errors for other requests: keep looping"* — each of
them was read off the wire and **discarded**. A grab that takes 400 ms
blindfolds the damage stream for 400 ms; a dragged window damages
continuously, so most of its notifies landed inside a grab and were lost.
The pane then sat on stale pixels until the ~5 s healer sweep redrew
everything **at once** — the literal "waits for input to stop, then the
window appears at the destination".

**Fix**: non-matching events are parked in a bounded queue
(`pendingEvents`, 8192 cap, oldest-dropped with `eventsDropped`
telemetry) and served in arrival order by `drainDamageRects()` /
`readEvent()`. `drainDamageRects()` also stopped burning its full wait
window per call: it blocks until the *first* rect (idle path parks on a
socket read — zero polling) and then coalesces for only 10 ms, instead of
adding a fixed 33 ms of latency to every update.

## Root cause #3 — the drag-stall architecture around a (previously dead) damage path

With the wire fixed, three design faults still turned drags into teleports:

1. **Union-rectangle damage** (v0.2.82): all rects since the last tick were
   merged into ONE rect; >60 % of the screen or >h/2 tall → **full banded
   sweep**. A drag is exactly that case (window at the new position +
   exposure at the old = half the screen), so every drag frame paid a full
   refresh. *Fix: persistent dirty **bands*** — horizontal strips merged
   only with strips they nearly touch (24-row margin), persistent across
   ticks, each painted from its own small grab (≤256-row chunks, ≤6 bands,
   120 ms budget per tick; the rest stays pending). Nothing in the damage
   path can trigger a full sweep any more; the sweep is only the healer
   (~5 s), the first paint, and the two-strikes fallback for a degrading
   pipe.
2. **Input injection on the UI thread**: `onTouchEvent` called
   `fakeMotion()` directly. Normally a 36-byte write — but the socket ends
   at an Xvfb under proot, and when the server falls behind (exactly
   during a drag) the send buffer fills and `write(2)` **blocks the UI
   thread**: Android stops delivering MotionEvents, and when the block
   clears, a burst of stale motions floods the server at once. *Fix: the
   **XTEST pump*** — a dedicated max-priority thread; `inject*` is now an
   O(1) enqueue; consecutive pending motions collapse into the newest one
   (only the finger's latest position is worth injecting); buttons/keys/
   text keep strict FIFO order so a press never overtakes the motion that
   aims it; tasks re-resolve the transport at run time and the queue is
   dropped on recycle/stop/rotation.
3. **Co-running capture pipelines**: `ensureFbMail`/`ensureMailCapture`
   could start (and the fbmail respawn watcher restart) guest `xwd`
   pipelines **beside a healthy native stream** — per-frame full-screen
   proot dumps stealing CPU from the grabs, and 2-second-old mailbox
   photographs overwriting fresh damage frames on the same bitmap (the
   invisible "sloppy"). *Fix*: both `ensure*` are gated on `nativeFresh`;
   the native loop **retires** the mailboxes level-triggered once
   `nativeFrames ≥ 8`; both respawn watchers refuse to restart guest
   pipelines while native is fresh. The silence watchdog still brings them
   back within ~25 s if native ever goes quiet.

Also: the opening full sweep is **capped at 8 s** when DAMAGE is live
(before: tens of seconds on a slow pipe with every touch queued behind it
— "slow to open"); a capped sweep never regrows bands and never clears the
pending set. The LIVE badge now carries **`src=`** (native/mail/fbmail/
native-retry) so a co-running fallback can never hide again.

## The proof harness: `tools/x11probe/jvmwire`

`X11Client.kt` compiles **unmodified** on a plain JVM against the tiny
LocalSocket/Color shims in `jvmwire/shim/`, so the whole wire layer is now
testable off-device against a real Xvfb — no phone, no adb, no field round
to find a protocol bug:

```sh
DISPLAY=:5 KOTLINC=/opt/kotlinc/bin/kotlinc \
  sh tools/x11probe/jvmwire/run_jvm_wire_proof.sh          # EMUX_WIRE_TRACE=1 for packets
```

It runs the app's own `connect()`, then 24 checks: handshake/geometry/
depth/XTEST/keymap; full-screen and region GetImage with **pixel-exact**
verification against rectangles an independent raw-socket painter drew
(PolyFillRectangle — the harness's ground truth, see its kdoc for why not
PutImage); boundary-straddling regions; DAMAGE create → notify geometry →
damaged-rect grab; the parked-event queue (a notify arriving mid-request
must survive and be served by `drainDamageRects`); `fakeMotion` verified
through QueryPointer; `probeButtonDelivery` end to end; and a per-size
cost table. Check #0 is the wire contrast above — the exact request
v0.2.71–v0.2.82 sent must fail with BadLength.

Run against **the old client** (`EMUX_X11CLIENT_SRC=$(git show
HEAD~:…X11Client.kt)`): **16 passed / 6 failed** — every pixel check dies
with `xerr=16:maj=73` and the queue checks with the events gone. Against
**this release**: **24 passed / 0 failed**.

Measured costs (this box, no proot, 576×1280×16):

| grab | bytes | median | ⇒ |
|---|---|---|---|
| full screen | 1 474 560 | 3.7 ms | ~270 fps |
| typical window 480×360 | 345 600 | 1.1 ms | ~900 fps |
| drag sliver 576×40 | 46 080 | 0.21 ms | ~4 800 fps |
| small damage 120×90 | 21 600 | 0.12 ms | ~8 300 fps |

A drag update is a sliver, not a screen: that table is the whole
difference between "follows the finger" and "teleports".

## Files

- `app/src/main/java/com/emux/x11/X11Client.kt` — GetImage 16-byte body +
  `tailBuf` + RGB565 LUT; `pendingEvents` parking (`hasQueuedEvent()`,
  `eventsQueued`/`eventsDropped`); `drainDamageRects` block-then-coalesce;
  `MAX_RECTS_PER_DRAIN`.
- `app/src/main/java/com/emux/x11/DesktopService.kt` — dirty-band damage
  loop, 8 s first-sweep cap, XTEST pump, mail-pipeline gating +
  `retireMailPipelines()`.
- `app/src/main/java/com/emux/x11/DesktopView.kt` — badge `src=`.
- `tools/x11probe/jvmwire/` — harness + shims + runner (new).
- `tools/verify_apk.sh` — dex must carry `pendingEvents`,
  `requestReplyFull`, `damageRectOf`, `hasQueuedEvent`, `postXtest`
  (60 checks now).
- `tools/x11probe/xbench.py` — GetImage throughput benchmark (new).

No guest-script changes: `ENV_VERSION` stays 75.
