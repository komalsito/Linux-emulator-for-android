# v0.2.81 — damage-driven capture: ship the pixels that changed, not the screen

> *"everything on the desktop is still laggy and slow"*

After v0.2.79 (pacing, quality) and v0.2.80 (honest fps, adaptive bands, input
re-proof, verified geometry) one structural fact remained: **the capture loop
polled the whole screen**. Every visual response — a click, a drag, a menu
opening — waited behind a full sweep of GetImage bands, most of whose pixels
were identical to the frame before. On a proot-thin pipe that is seconds of
unchanged pixels per interaction. No pacing fix can help; the architecture is
the bug.

The fix is the x11vnc model: ask the X server **what changed** and ship only
that. An idle desktop now costs almost nothing and answers in one round trip;
a dragged window costs its own rectangle instead of the screen.

## Two dead designs, measured

1. **CARD16 QueryVersion.** The first encoding sent `CARD16 major + CARD16
   minor` (4 bytes) to DAMAGE/XFIXES QueryVersion. Both extensions take
   **CARD32 + CARD32**; the server answers `BadLength(16)`. Caught by
   `damage_probe.py` before it shipped — the probe exists precisely because
   every hand-rolled request in this project has eventually been wrong in a way
   only a real server notices.
2. **XFIXES region polling.** The second design polled damage without events:
   `DamageSubtract(repair=None, parts=region)` then `XFixesFetchRegion(region)`.
   On this Xvfb **every** XFIXES region request is rejected — CreateRegion,
   Subtract-with-parts, FetchRegion all error (BadRequest/BadLength, badvalue
   constant across minors and sizes), while DAMAGE itself works. The
   region-polling design died on the sandbox, which is what the sandbox is for.

A third, quieter bug: `sync()` in the Python client *returns* the last error
instead of raising, and a noreply request's error arrives under its own
sequence — so "CreateRegion ok / DamageCreate ok" prints were lies for one
whole debugging round. The probe now reads the error list after **every**
step, and `initDamageTracking()` ends with a checked Sync so a rejected Create
cannot masquerade as damage-enabled.

## What ships: DamageNotify events

`DAMAGE Create` on the **root** window with level `RawRectangles` (damage to
inferior windows counts — client windows, WM frame moves, panel clocks), then
the capture loop drains `xDamageNotify` events off the connection it already
owns. No second socket, no event loop, no XFIXES. Layout, measured byte by byte
on Xvfb and asserted by the probe:

```
0 type(=first_event)  1 level  2 sequence  4 time  8 drawable
12 damage  16 geometry x,y INT16 + w,h CARD16  24 area (same shape)
```

Per tick (~33 ms): drain events → union the rectangles → one `GetImage` of the
union → paint it at its offset → sleep 10 ms. Rules that keep it sane:

- **empty drain = nothing changed** → sleep, ship nothing (an idle desktop
  stops paying the pipe at all);
- union **> 60% of the screen** (video, full redraws) → the existing banded
  sweep handles that frame, it is what it is for;
- union taller than half the screen → same, sweep;
- **every 150 ticks (~5 s) a full sweep anyway** — the healer, for anything
  DAMAGE cannot see (server-side sprite draws, a lost event, our own
  bookkeeping);
- any partial-grab failure falls through to the sweep with the v0.2.80
  adaptive-band backoff, so damage mode can never be worse than before.

The badge says which architecture is live: `mode=damage` or `mode=sweep`. On a
damage-driven session `ms/refresh` is the cost of the last *update*, and fps is
updates per second — the number that matches what your finger feels.

## Evidence

`tools/x11probe/damage_test.sh` (Xvfb harness, no WM needed — damage is
server-side, below any window manager): **7/7**.

```
  ok    DAMAGE QueryVersion accepts CARD32+CARD32
  ok    DamageCreate on root accepted
  ok    idle root reports NO damage                      [0 rect(s)]
  ok    a drawing client produces damage notifies        [26 rect(s)]
  ok    notify geometry covers the drawn window          [union 80,60-326,196]
  ok    a quiet desktop reports NO damage                [0 rect(s)]
  ok    a closing window also reports damage             [1 rect(s)]
```

The union `80,60-326,196` is exactly the `xterm -geometry 40x10+80+60` that was
drawn — the rectangles are real geometry, not guesses. Regression:
`drag_test.sh` 7/7, `ladder_test.sh` 46/0, `verify_apk.sh` 55/0 (the two new
dex symbols are now part of the gate). No guest changes; `ENV_VERSION` stays 75.
