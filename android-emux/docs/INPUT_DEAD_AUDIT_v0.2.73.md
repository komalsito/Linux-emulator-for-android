# v0.2.73 (vc173) — banded GetImage: sizing requests to the measured pipe

## Field evidence (v0.2.72, 2026-11-01 20:10)

```
STALE 32s - no frames arriving (capture dead? respawning) capage=34s nat=no-reply(timeout/eof)
proof=ok touch=10 inj=20 fail=0 ctrl=:1 cap=:1 capage=34s src=native agent=idle
cap.err: xwdtopnm: writing PPM file | xwdtopnm: WARNING: Producing maxval 65535 output...
```

Three facts, each decisive:

1. `nat=no-reply(timeout/eof)` — the v0.2.72 wire fix **worked** (no more
   BadLength); now the full-screen 3.5 MB reply simply **never finishes
   inside 6 s** on this device. The proot-translated write path is slow,
   not broken: small requests (QueryPointer, handshake) round-trip fine.
2. `cap.err` proves the v0.2.72 silence watchdog did its job — the guest
   pipeline started — and the guest pipeline then wedged in `xwdtopnm`
   exactly as in every earlier round. Two independent capture paths, both
   defeated by **bulk transfer**, not by protocol or input.
3. `proof=ok touch=10 inj=20 fail=0` — input plane healthy, fifth round.

## What real projects do about slow pipes

x11vnc/TigerVNC never request one giant image when the transport is weak:
they request **regions** (and with DAMAGE, only damaged regions). A region
request that fits the pipe always completes; a full-screen request that
doesn't, stalls forever. v0.2.73 completes that design in-app.

## v0.2.73 changes

- `X11Client.grabRegionArgb(x, y, w, h, timeout, reuse)` — region GetImage
  with buffer reuse; `grabRootArgb()` kept as a wrapper.
- `X11Client.lastReadErr` — `timeout` vs `eof` distinguished (v0.2.72
  conflated them; the field answer was timeout).
- `DesktopService` native loop, per connection:
  1. **Probe**: one 16-row band with a 10 s budget → measured KB/s, painted
     into the notes (`native probe on :1: 1280x16 in NNms -> ~NNNKB/s ->
     band NN rows of 694`).
  2. **Banded refresh**: sequential bands sized to ~1.2 s of measured pipe
     (floor 8 rows), each band painted into the frame bitmap and invalidated
     **the moment it lands** — the pane goes live progressively (top-down
     sweep) instead of never.
  3. Per-refresh telemetry every 25 refreshes (ms/refresh, band rows, KB/s).
- Silence watchdog, wedge guard, legacy fallback: unchanged.

Expected field signature: probe note in history, then a progressively
repainting pane at a rate the pipe allows (even ~0.2-1 fps is a LIVE badge
and a desktop that responds to taps), `src=native`, `capage≈0-2s`.
If bands themselves time out, `nat=timeout` with the band size in the miss
note names the next wall precisely.

Build vc173 / 0.2.73, ENV_VERSION 72, gate 51/0/0, signature 5e3ef254…a1fa.
