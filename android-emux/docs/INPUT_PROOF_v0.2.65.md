# v0.2.65 — the desktop paints but nothing is clickable: making the input path prove itself on-device

## The report

v0.2.64 closed the launch crash and the `signal 11` terminal deaths ("everyThing
seems to work like the other working version now"). The desktop tab streams a
real Openbox/pcmanfm desktop with icons and a cursor. But:

> "the priority is that input doesnt work, i cant click/ touch anything yet and
> probably keyboard doesnt work either"

The screenshot that came with it is the key artefact. Read its top-left badges:

```
LIVE 0.5 fps
INPUT: XTEST (control plane)      <- green
```

**Green.** The app was asserting, on screen, that its input path was live, at the
same moment as that path demonstrably delivered nothing. Any diagnosis that
trusts that badge is wrong by construction.

## Why this was undiagnosable before, by design of the protocol

Input has two transports (v0.2.59):

1. **XTEST over the control plane** — one persistent X socket, `FakeInput`
   requests, ~0.02 ms per event. The real path.
2. **Rootfs FIFO + guest `xdotool` agent** — one proot-spawned process per event;
   the coarse fallback.

`FakeInput` is a **sendNoReply** request. A server that *honours* an injected
motion and a server that *silently discards* it produce byte-identical behaviour
on the client: nothing comes back. The only asynchronous signal is an X error
packet, and a server that ignores an event sends none. So "the wire encoding is
correct" and "the server is dropping everything" are **indistinguishable from the
client side** — and with no adb, they were indistinguishable from anywhere.

Every leg was individually plausible, which is what made this stick:

| Leg | Evidence it is fine |
|---|---|
| Touch reaches the view | `DesktopView.onTouchEvent` handles `ACTION_DOWN`, returns true; overlay hit-test only consumes the helper-bar strip and the toggle chip |
| Coordinate transform | `mapToX` is a plain fit-to-screen affine, clamped into the captured frame (= the X screen) |
| Control plane connects | handshake, `QueryExtension`, XTEST `GetVersion`, keymap and geometry all succeeded — that is what painted the green badge |
| FakeInput encoding | the 36-byte body with rootX/rootY at offsets 24/26 was **measured against a live Xvfb 1.21 + Openbox 3.6.1** in v0.2.59: an injected click focused an xterm, injected keystrokes ran a shell command |

…and that last verification happened **on the build host**, not on a device. The
one environment difference that matters — a guest X server running under proot on
an ARM ROM, reached through a unix socket in app storage — had never been asked to
honour a single injected event.

## The fix: close the loop with QueryPointer

`X11Client.queryPointerRoot()` (core opcode 38) reads back where the *server*
believes the pointer is. That turns injection from fire-and-forget into something
testable:

1. read pointer `P0`;
2. inject `FakeInput MotionNotify` at `P0 + 48 px`, clamped inside the server
   geometry so server-side clamping can neither fake nor mask the delta;
3. wait 250 ms, read pointer `P1`;
4. `P1 ≈ target` → **proof=ok**. Otherwise → **proof=ignored**, or
   **proof=rejected:<X error>** when the server did answer with an error.

On anything but `ok` the service sets `xtestIgnored`, which makes `xtest()` return
null — **input automatically falls back to the guest `xdotool` agent**, which
injects from *inside* the guest and therefore does not depend on our wire encoding
at all. A failed proof now costs the user the slow transport instead of no
transport.

The proof runs once per control-plane connection, on its own daemon thread
(`emux-input-proof`) — never on the FIFO writer's single thread (that would stall
real input behind it) and never on the UI thread.

## Four counters, one screenshot, no adb

The pane now paints a third badge line on the framebuffer itself, so it survives
in any screenshot:

```
proof=ok touch=12 inj=12 agent=clicked
```

| Field | Measures |
|---|---|
| `touch=` | `ACTION_DOWN` events that reached `DesktopView.onTouchEvent` — the **finger→view** leg |
| `inj=` | injection calls `DesktopService` actually made, across all seven entry points — the **view→service** leg |
| `proof=` | the **server's** verdict, from the QueryPointer round-trip — the **service→X** leg |
| `agent=` | the guest agent's own forensic markers (`emux_agent_launched` / `emux_agent_noxtool` / `emux_agent_firstclick`) — the **fallback** leg |

That single line partitions the failure space completely:

| Reading | Meaning | Where the next fix goes |
|---|---|---|
| `touch=0` while tapping | touches never reach the view | view hierarchy / an intercepting overlay |
| `touch>0 inj=0` | view got them, service never injected | gesture/overlay logic in `DesktopView` |
| `inj>0 proof=ignored` | events reach a server that discards them | now auto-routed to the guest agent; if `agent=no-xdotool`/`never-started`, the note names it |
| `inj>0 proof=rejected:E` | server answered X error E | the error text names the request field at fault |
| `proof=ok` and still dead | pointer moves; nothing reacts | window-manager/focus layer, not the transport |

## Two silent-death paths closed while in here

- **The FIFO fallback could park forever.** `open(2)` on a FIFO blocks until a
  reader appears. If the guest agent never started, or started and found no
  `xdotool` (its read loop then exits), the writer thread blocked on the open with
  no error anywhere, while the pane kept advertising a transport. The writer now
  checks the agent's own markers *before* touching the FIFO and reports precisely
  which one is missing.
- **`noteInputDropped` noted once per session.** The first, often transient reason
  masked every later, more specific one — and the specific one is the one that
  names the remedy. It now re-notes whenever the reason changes.

## Scope notes

- **`ENV_VERSION` stays 70.** Kotlin-only again; no guest helper changed, so no
  device re-seed is warranted.
- The proof injects a **motion**, not a button press: moving the pointer is
  observable, harmless, and needs no focused window — a click proof would depend
  on whatever happens to own the focus at that moment.
- `injectCount` is incremented at the seven public injection entry points
  (`injectClick`, `injectMotion`, `fakeButtonReleaseOnly`, `injectKeycode`,
  `injectKeysymTap`, `injectScroll`, `injectKeysymCombo`), so drags, wheels and
  key chords all show up, not just taps.

## Verification

| Check | Result |
|---|---|
| kotlinc single pass | `exit=0 errors=0`, 25 kt sources → 254 classes |
| Class audit | 504 classes, all traceable |
| `verify_apk.sh` | **51 passed / 0 failed / 0 skipped** |
| Version stamp | `com.emux` versionCode **165**, versionName **0.2.65** |
| Signature | committed keystore, SHA-256 `5e3ef254…a1fa` |
| New code in dex | `queryPointerRoot`, `input proof`, `proof=`, `touch=`, `agent=never-started`, `agent=no-xdotool`, `emux-input-proof` — all FOUND |
| APK | `emux-debug.apk`, 6,856,541 B, sha256 `71c8232d…e5e245` |

Toolchain note: `/opt` (build-tools 34, platform-34, kotlinc 1.9.24, r8 8.5.35,
the 77-artifact Maven set) does not survive between sessions; it was re-fetched
from `dl.google.com` and `deps.lock` for this build. Stage 1 of
`no-gradle-build.sh` repopulated `/opt/apkb` from scratch.

## What to send back

Install v0.2.65, open the Desktop tab, tap a few icons and type a few keys, then
screenshot the top-left corner. The third badge line plus the `INPUT:` line above
it identify the broken leg unambiguously — and if the proof failed, input should
already be working through the guest agent, just more slowly.
