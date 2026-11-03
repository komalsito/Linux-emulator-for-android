# v0.2.80 — the terminal that staircased, and the desktop that repainted in fragments

Two field reports, both with screenshots, both legible once you know what the
diagnostics mean:

> *"the capture only goes up to 1 fps and the desktop still is very sloppy and
> slow, it doesnt work very well still."*
>
> *"the terminal sometimes looks like the picture instead of being tidy at the
> left corner as it should be."*

The screenshots carried four independent defects. This release fixes all four.

---

## 1. The terminal: a flag that lied about the wire

The screenshot's self-test staircases — every line begins where the previous one
ended:

```
                [emux] --- environment self-test ---
                                 [emux]   proot (unrooted distro engine): OK
                                                 [emux]   PTY wrapper (…): OK
```

That is the signature of **bare LF bytes**: with no tty driver in the path,
nothing rewrites `\n` into `\r\n`, so a newline moves down a row and leaves the
column where it was. ONLCR emulation for raw-pipe sessions has existed since
v0.1.55, keyed on one flag: `view.onlcr = !s.ptyWrapped`.

The flag is an *intent*, not a measurement. `TerminalSession` sets

```kotlin
ptyWrapped = TtyWrap.apply(pb) || guestPty      // guestPty = "proot bin exists"
```

before the guest `script(1)` wrapper is known to be *alive*. On this ROM the same
screenshot reports `musl loader/busybox: FAILED`, and proot logs `signal 11` —
the wrapper segfaulted, the shell re-exec'd on raw pipes, the flag stayed true,
the emulation stayed off, and the screen staircased. The same flag also gates
local echo, so typing was invisible until the shell answered.

### The fix: measure the wire

A real PTY with ONLCR can **never** put a bare `0x0A` on the wire — the line
discipline rewrites every LF to CR LF, including LF bytes inside OSC/DCS
strings. So `VtTerminalView` now counts bare LFs (a `0x0A` not preceded by
`0x0D`), in every parser state. Three of them are proof that there is no line
discipline, whichever flag claims otherwise, and on that proof:

- the renderer sets `onlcr = true` immediately — mid-stream self-heal, no
  restart, no attach callback needed;
- `session.noteNoLineDiscipline()` clears `ptyWrapped`/`guestPty` with the reason
  `bare-lf: no line discipline on the wire`, which restores local echo too;
- the SIGSEGV path clears the same flags at detection time when the guest
  wrapper is what died, instead of waiting for the next attach.

Three, not one: a single stray LF could be a malformed escape; three on a
session that claims to have a tty is a wire without a line discipline.

The four identical `WARNING: no PTY wrapper found on this ROM` lines in the
screenshot were one feed site re-run by state churn — now printed once per
session.

## 2. The desktop: "1.0 fps" that was not fps

The badge in the screenshot reads:

```
LIVE 1.0 fps  1280x694  -1KB/s  -1ms/refresh      capage=2s
```

Read it against the code and it is a contradiction that names the bug.
`nativeGrabMs` is set only when a **full sweep** of bands completes;
`nativeKbPerS` only by the connect-time probe (or never, if the probe fails).
Both `-1` means neither had ever happened. Yet the badge said LIVE 1.0 fps —
because fps counted **band paints**, i.e. partial repaints. The pane was being
repainted in fragments, forever: that is exactly "very sloppy and slow".

Why no sweep completed: one band that cannot cross the pipe inside its 8 s
timeout aborts the whole sweep (`ok = false`), the sweep restarts from y=0, and
on a pipe this thin it aborts again — an 8-row band at 1280 wide is 20 KB, and
even that was missing while the desktop stack was still settling under proot.

### The fix: keep the scan moving, and say what it is doing

- **Adaptive bands.** A missed band halves the band size (floor 4 rows) so the
  sweep keeps advancing instead of restarting; after a clean sweep the band
  regrows towards ~1.2 s of measured pipe, so halving is not a one-way ratchet.
- **KB/s from reality.** An EWMA over the bands that actually land fills
  `nativeKbPerS` when the probe could not, so a live session can never show
  `-1KB/s` again.
- **fps means refreshes.** The badge now counts completed sweeps per second
  (2 s window over `nativeFrames`), and when a sweep is in flight or stuck it
  appends where and why:

  ```
  LIVE 0.0 fps  1280x694  21KB/s  -1ms/refresh  sweep 344/694 miss=12 no-reply
  ```

  The next field screenshot ends the archaeology instead of starting it.

## 3. Input: a one-shot verdict at the worst possible moment

The same screenshot: `INPUT: guest xdotool agent (slow fallback)` and
`proof=ignored`. `runInputProof` ran **once per control-plane connection** —
while the desktop stack is still settling. One raced `QueryPointer` then
condemned the whole session to the guest agent, which execs a process per event:
every tap and every drag motion pays a guest round trip. That alone can make a
desktop feel broken while every diagnostic claims health.

Now a failed proof schedules backoff re-proofs (10/20/40/60/60/60 s). The moment
the server honours `FakeInput` the transport is promoted back to XTEST, the
agent is retired, and the v0.2.78 button leg runs its normal escalation. A
server that still ignores XTEST after four minutes says so in the log once.

## 4. The quality change that never landed

The badge still read `1280x694` — the default — on a device whose owner had been
told the setting exists. The X screen size is fixed when Xvfb starts, so a
quality change lands only via a restart; and if the old server is still dying
when the new one starts, the launcher's adopt-don't-fight rule (v0.2.13) adopts
the **old** server at the **old** geometry and the setting silently does
nothing. The v0.2.79 restart waits 2 s; on a slow ROM that is not always enough.

Now the native loop **verifies** the live server's geometry against the requested
long side. On mismatch — with the preference unchanged, so it is not the user
mid-choice — it restarts the desktop once and logs
`GEOMETRY MISMATCH: live server WxH but the requested long side is N`, capped at
two fixes per process lifetime so a server that keeps coming up wrong cannot
restart-loop.

And the choice itself is no longer a guess: the capture loop persists the pipe it
measured (`capture_kbps`, `capture_geom`, every 25 refreshes), and Settings turns
it into a per-option prediction on this device's own numbers:

```
Your pipe measured 720 KB/s: 1280px≈0.5fps 960px≈0.9fps 720px≈1.6fps 540px≈2.8fps
```

## 5. Reproducing, and what did not change

- No guest-side changes; `ENV_VERSION` stays 75, so devices do not re-seed.
- Release gate unchanged: `verify_apk.sh` 53/0; `drag_test.sh` 7/7;
  `ladder_test.sh` 46/0. The terminal fix is renderer-level and is verified by
  reading the wire, which is the point: it cannot regress into trusting a flag
  again without the bare-LF detector noticing.
- Housekeeping note: mid-release the build sandbox was re-provisioned and lost
  the whole toolchain (JDK, kotlinc, platform-34, build-tools, r8, stubs) and
  most of `/tmp/emux-nogradle`. Re-provisioned from the same pinned sources:
  OpenJDK 17.0.21, kotlin-compiler-1.9.24, platform-34-ext7_r03,
  build-tools r34, r8 8.5.35, stubs regenerated by `gen_stubs.py`. The build
  command and its `LOWMEM=1 HEAP=…` bounds are unchanged — see
  `DRAG_AND_CAPTURE_RATE_v0.2.79.md` §3.1 for why they are not optional.
