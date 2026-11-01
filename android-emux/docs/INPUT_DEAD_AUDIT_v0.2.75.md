# v0.2.75 (vc175) — pixels by file: the filesystem mailbox

## Field evidence (v0.2.74, 2026-11-01 21:04)

```
STALE 11s ... capage=13s nat=xerr=16 pipe=-1K b=8r
proof=ok touch=11 inj=32 fail=0 ctrl=:1 cap=:1 capage=13s src=native agent=idle
```

Round four of native socket capture, death number four: **BadLength on
8-row bands** from a listener that answers QueryPointer fine, on bytes my
harness proves correct against Xvfb 21.1.7. The device's :1 socket has now
rejected, timed out, or wedged every bulk-read strategy sent to it.
Meanwhile the shared rootfs filesystem has carried every marker file
(`emux_x_up`, `emux_cap_display`, `emux_display_num`, cap.err tails) across
all twelve rounds without one failure. Input plane: seventh healthy round.

## The transport change

Bulk pixels move to the one proven transport:

- guest `emux-fbmail <display>` (new asset, ENV_VERSION 73 re-seeds it):
  loop { `xwd -root` -> temp file -> `rename(2)` } — xwd talks to Xvfb over
  the guest-LOCAL unix socket (the only X data path this device ever
  liked), publishes `/tmp/emux_cap_display` in the format the watcher
  already parses, logs to `/tmp/emux_fbmail.err`, never exits.
- host: poll `tmp/emux_fb.xwd` mtime, read the file DIRECTLY (the read path
  never enters proot), parse the XWD header, paint. rename(2) makes torn
  reads impossible.
- XWD parser validated against a real `xwd -root` dump before shipping:
  header big-endian (header_size 107), ncolors@76, pixels at
  header_size+ncolors*12, endian detected by plausibility, 16/24/32 bpp and
  LSB/MSB image orders all handled.

## Ladder and forensics

- Order is now native GetImage -> fbmail -> (watchdog) legacy pipeline.
  Native switches to fbmail immediately when its probe or grabs are
  rejected (`xerr`), at the gate when native produced nothing, and the
  watchdog prefers fbmail over the legacy chain; a dead fbmail session is
  respawned by the watchdog.
- `X11Client.lastXErr` now carries code + major/minor opcode + resource id
  (`nat=xerr=16:maj=73:min=0:res=0x...`), so desync vs. genuine rejection
  is distinguishable from a screenshot.
- `src=` on the proof line reads `fbmail` when the mailbox is painting.

Expected field signature: `src=fbmail`, a repainting pane at the guest's
xwd loop rate (~1-3 fps), capage following the mailbox publication.
Gate 52/0/0, signature 5e3ef254…a1fa, `adb install -r`.
