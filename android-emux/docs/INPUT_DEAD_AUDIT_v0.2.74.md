# v0.2.74 (vc174) — half the wire, and the error code we threw away

## Field evidence (v0.2.73, 2026-11-01 20:43)

```
STALE 15s ... capage=17s nat=no-reply
proof=ok touch=9 inj=18 fail=0 ctrl=:1 cap=:1 capage=17s src=native agent=idle
terminal: the previous shell died with SIGSEGV 1 time(s) ... guest binaries
          do not match this device's ABI/loader
```

- `nat=no-reply` is the telemetry hole: an **X error packet** answered the
  band request and v0.2.73 recorded the fact but not the CODE. v0.2.74
  records it (`xerr=N` on the pane): BadMatch=8 / BadDrawable=9 /
  BadValue=2 / BadLength=16 each name a different next fix.
- The guest userland SIGSEGVs on this ROM (musl loader FAILED since round
  one) — guest processes are landmines; every byte of capture that can be
  moved out of the guest, must be.
- Input plane: sixth consecutive healthy round.

## v0.2.74 changes

1. **Depth 16 desktop** (`desktopGeometry()` now emits `WxHx16`; emux-x11
   passes RES straight to `Xvfb -screen 0`, no guest-script change,
   ENV_VERSION stays 72). Every capture payload halves: 1280x694 full
   screen = 1,776,640 B instead of 3,553,280 B; bands halve too. Wire
   verified against Xvfb 21.1.7 `-screen 0 1280x694x16`:
   `depth=16 payload=1776640 stride=2560`, 3-11 ms/grab local.
   `grabRegionArgb` decodes depth-16 ZPixmap = RGB565 LE directly (the
   format this app's frame path always spoke); depth 24/32 path retained.
2. **Error codes**: `X11Client.lastXErrCode` captured on every matched
   error packet; `nat=xerr=N` on the STALE pane when a grab is rejected.
3. **Pipe telemetry on the pane**: `pipe=NNNK b=NNr` (measured KB/s from
   the connect-time probe, current band height) joins `nat=` on the STALE
   line — the next field screenshot carries the numbers that size every
   future band, no adb, no terminal dump.

Expected: progressive top-down repaint at the pipe's real rate; if the
server rejects bands, `nat=xerr=N` names the rejection; if the pipe is
slower than any band, `pipe=` shows the ceiling.
Gate 51/0/0, signature 5e3ef254…a1fa, `adb install -r`.
