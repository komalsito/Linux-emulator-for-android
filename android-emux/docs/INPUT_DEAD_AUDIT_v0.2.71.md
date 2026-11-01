# v0.2.71 (vc171) — CAPTURE ARCHITECTURE AUDIT: our stack vs. the real projects that do this for a living

**Verdict up front:** the v0.2.70 field screenshots exonerated the input plane a
third time (`proof=ok touch=11 inj=22 fail=2` — injections counted, only the two
startup-recycle failures). The pane was `STALE … capage=15s`: **the frame source
keeps dying while input keeps working.** So this round did what was asked —
benchmarked EmuX against real, proven projects that put an Openbox/X desktop on a
remote screen — and the comparison indicts exactly one component: our capture
pipeline. v0.2.71 replaces it with the mechanism every one of those projects uses.

---

## 1. What the proven projects actually do

| project | how it captures the framebuffer | processes spawned per frame |
|---|---|---|
| **TigerVNC `x0vncserver`** | ONE persistent X connection. `DumbFrameBuffer` backend = `XGetImage(root, ZPixmap)`; `ShmFrameBuffer` = MIT-SHM when available | **0** |
| **x11vnc** | ONE persistent X connection. MIT-SHM fast path; documented fallback `-noxshm` = plain `XGetImage` grabs of the root window | **0** |
| **Xvnc/TigerVNC classic** | IS the X server; framebuffer lives in its own memory | **0** |
| **xrdp/xorgxrdp** | in-Xorg module reads the framebuffer in-process | **0** |
| **Android X servers (Termux:X11, bVNC-style setups)** | in-app protocol clients; pixels decoded inside the app | **0** |
| **EmuX ≤ v0.2.70** | per frame: `xwd \| xwdtopnm \| pnmtoplainpnm \| nc` — **4+ proot'd guest processes every single frame**, on a rootfs whose package manager "cannot run" and whose musl loader FAILED | **4+** |

Nobody ships the per-frame process pipeline. It is not a tuning problem — it is
the architectural outlier, and our own field evidence matches its failure mode
exactly:

* v0.2.69: `capage=22s` — helper alive but wedged (wedge guard fired);
* v0.2.70: `capage=15s`, no `cap.err` line — helper died inside the 21 s guard
  window, respawn cycle in progress;
* device probe earlier: `/tmp/emux_cap_display` epoch frozen for **2086 s** —
  the "frozen photograph" class (F/G) is the capture chain dying cyclically;
* same screenshots: input counters healthy. Input was never the remaining bug.

Every frame of the old pipeline re-enters proot's ptrace syscall translation
(4 processes × fork/exec/openall/socket), races the LMK, and re-opens the X
socket from scratch. Any one hiccup wedges the loop; on this rootfs they
compound. The x11vnc/x0vncserver model has none of those moving parts: one
socket, opened once, `GetImage` request → reply, pixels decoded in-app.

## 2. What v0.2.71 changes

**New primary frame source — native in-app capture (`X11Client.grabRootArgb`)**

* `OP_GET_IMAGE = 73`, ZPixmap, full root window, plane-mask all-planes;
  28-byte request, 32-byte reply header + `length*4` payload; depth-24
  TrueColor = 4 B/px BGRx, stride derived from the payload size, decoded to
  ARGB ints in Kotlin. This is byte-for-byte the x11vnc `-noxshm` fallback
  path (MIT-SHM stays unavailable to us: no guest cooperation, no shmget
  through proot — same reason x11vnc documents `-noxshm` for restricted hosts).
* Runs on a **dedicated** X11 connection (never the control plane's — a 3.6 MB
  reply must not queue behind input round-trips), unix-socket first, display
  number re-read on every reconnect so rotations/self-heals follow.
* ~3 fps foreground / ~1 fps background, adaptive to grab cost; frames go
  through the existing contract (`ensureBitmap` → `setPixels` under
  `frameLock` → `frameId++` → `frameCallback`), so DesktopView, the LIVE/STALE
  badge and CONNECTED promotion are untouched.
* Self-healing: 3 consecutive grab misses → close, reconnect with backoff
  (0.5 s → 8 s). The v0.2.69 wedge guard now also restarts THIS loop when
  capage climbs with native as the source.

**Legacy `emux-capture` demoted to fallback**

* The gate starts native capture the instant X is up (it needs no toolchain at
  all). `emux-capture` only launches if native produced **zero** frames during
  the whole gate window; a missing toolchain is no longer fatal when native is
  delivering.
* While native is fresh (<15 s since last frame), the stale guest publication
  file (`/tmp/emux_cap_display`) is ignored for capage, plane-agreement and
  wedge decisions — no false guard trips, no false split-brain recycles.

**Field-visible proof**

* Badge line gains `src=native` (or `src=native-retry` / `src=-`);
* status line: `capture: native XGetImage (N frames)`;
* startup note names geometry+depth: `native capture UP: XGetImage(ZPixmap) on
  :1 720x1280 depth24 — no guest binaries in the frame path`.

**Input plane: deliberately untouched.** XTEST control plane + FIFO agent
fallback shipped in v0.2.59–v0.2.70 and three field rounds confirmed it
injects. "Input detected but doesn't work" is what a frozen photograph looks
like from the user's chair: taps DO land on a live desktop the pane stopped
showing, so nothing visible responds. Fix the frame source and the desktop
moves when touched.

## 3. Expected field signatures on v0.2.71

Healthy: `LIVE ~3fps`, `proof=ok … src=native capage=0..1s`, pane shows the
cursor moving and windows opening on tap.
If capture ever breaks again: `src=native-retry` + `capage` climbing names the
new loop; `src=-` means we fell back to the guest pipeline (and cap.err still
paints the killer on STALE, v0.2.70).

Build: `versionCode 171 / versionName 0.2.71 / ENV_VERSION 72` (guest scripts
unchanged — the fix is 100 % app-side, which is the point). Gate 51/0/0,
signature `5e3ef254…a1fa` (in-place `adb install -r`).
