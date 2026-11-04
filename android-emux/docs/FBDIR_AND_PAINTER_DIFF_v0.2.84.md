# v0.2.84 — fbdir, the band-diff painter, and a badge that tells the truth

Field directive (verbatim, five asks):

> 1. Send only changed regions … prioritise the area around the pointer
>    during a drag.
> 2. Show real capture rate and measured throughput on screen — the
>    `LIVE x fps` figure must not drop when nothing changes.
> 3. Fallback path: let Xvfb mmap its screen into a file (`-fbdir`) the app
>    reads directly, replacing the per-frame xwd/xwdtopnm/pnmtoplainpnm text
>    chain under proot.
> 4. App-side: compare each horizontal band with the previous frame, convert
>    and upload only changed bands, skip the frame entirely if nothing
>    changed.
> 5. Use the 16-bit pixel format directly so no per-pixel conversion is
>    needed.

v0.2.83 fixed *correctness* of the native path (GetImage BadLength, dropped
DamageNotifies, drag-stall architecture). This release removes the remaining
*waste*: whole-screen work when nothing changed, per-pixel conversion of
bytes that were already in the bitmap's own format, a fallback path that
spent three guest processes per frame on text conversion, and a badge whose
fps figure punished the pipeline for succeeding.

---

## 1. The zero-process framebuffer mailbox: Xvfb `-fbdir`

### What it is

Xvfb's `-fbdir DIR` option makes the server keep its framebuffer in
`DIR/Xvfb_screen0` — a file in **XWD format** (the same format `xwd -root`
writes), memory-mapped by the server. Since the guest rootfs is a shared
filesystem the host app can already read, the fallback capture path becomes:

```
before (per frame):  Xvfb → xwd → xwdtopnm → pnmtoplainpnm → text push → app parse
after  (per frame):  Xvfb → (its own mmap) → app read() → band-diff → bitmap
```

**Zero guest processes per frame.** No proot round trips, no sockets, no
text encoding. The three-process chain only ever existed because nothing
exposed the framebuffer as bytes; `-fbdir` exposes exactly that.

### File format, measured byte-for-byte against a real Xvfb

`Xvfb_screen0` for a 576×320×16 screen is 369,568 bytes:

```
160 B   XWD header (BIG-endian fields), file_version 7, format ZPixmap(2),
        depth 16, w 576, h 320, byte_order LSBFirst(0), bpp 16,
        bytes_per_line 1152 = w*2, ncolors 64 (at offset 76)
64×12 B colormap
w*h*2 B pixels, RGB565 little-endian, row-contiguous
```

The app already parsed `xwd -root` dumps; v0.2.84 factors that header logic
into `xwdHeaderOf()` and the SAME parser reads both. Header endianness is
still detected by plausibility (header_size ∈ 96..4096, w/h ∈ 1..8192),
never assumed — this server writes a big-endian header around
little-endian pixels, exactly like the xwd chain did.

### The freshness trap: mtime is a liar

The obvious optimisation — `stat()` the file and re-read only when mtime
moves — is **wrong**, and the end-to-end proof caught it red-handed:
painting a rectangle changed the file's pixels (verified byte-exact) while
**mtime stood perfectly still** (server writes through its shared mapping;
the inode timestamp never moves). A stat-gated reader would show a frozen
desktop forever. So the mail reader re-reads on **every tick** while fbdir
is the live source and lets the byte-diff decide: an unchanged desktop
costs one page-cache read plus a memcmp and paints NOTHING.

### Lifecycle guards

* `emux-x11` probes `"$XVFB" -help` for `-fbdir` **once** per boot
  (`FBDIR_CHECKED`/`FBDIR_OPT`), so an unsupported Xvfb silently keeps the
  xwd chain; the token rides on every launch site, including the
  post-death `-nolisten unix` restart.
* Stale files would paint a photograph of the last session: `emux-x11`
  `rm -f /tmp/emux_fbdir/Xvfb_screen*` before every launch, and the app
  deletes the file again at session start.
* `fbdirAvailable()` gates the legacy helpers: while the screen file
  exists, `ensureMailCapture`/`ensureFbMail` are no-ops — the xwd chain is
  strictly worse and must not even start.
* fbdir is read only while `!nativeFresh`: co-reading with a working
  native damage path could paint an older copy over a newer damage frame.

`ENV_VERSION` 75 → 76 re-seeds the guest helpers.

---

## 2. The band-diff painter: convert and upload only what changed

Asks 4 and 5 are one mechanism. The bitmap is `RGB_565`; the wire and the
fbdir file are RGB565 little-endian. v0.2.83 still ran every grabbed byte
through per-pixel component math before `setPixels`. v0.2.84 keeps a raw
byte mirror of the bitmap (`prevRaw`, w*h*2) and compares **bytes**:

```
diffUpload(fx, fy, w, rows, raw, off0, bmpW, bmpH):
    for each 32-row slice (RAW_BAND):
        memcmp slice vs mirror        ← most slices match: skipped, 0 work
        on first differing byte:
            decode slice via RGB565_TO_ARGB[v] table lookup (no bit math)
            setPixels just that slice
            copy slice into the mirror
    return number of slices uploaded   (0 ⇒ frame skipped ENTIRELY)
```

* **No conversion for unchanged pixels** — a byte compare decides first.
* **No bit math even for changed pixels** — `X11Client.RGB565_TO_ARGB` is a
  65536-entry lookup table, built once, proven exact for all 65536 codes
  against the component-math decoder (harness check below).
* **Whole frames vanish**: `diffUpload` returning 0 means the grab was
  byte-identical to what is on screen; the frame counter, the healer and
  the invalidations are NOT touched, and `diffSkips` counts the event.
* Every source feeds the same differ: native damage chunks, native sweep
  bands, fbdir raw frames (`paintRawFrame`), all via `grabRegion565` —
  a new `X11Client` grab that returns raw RGB565 LE bytes (arraycopy fast
  path when stride == w*2; repack for strided/24/32 servers) with the same
  error contract as `grabRegionArgb`.
* Paths that paint `IntArray`s without maintaining the mirror
  (`paintMail`, legacy `writeDirtyRegion`) call `invalidatePrevRaw()`, so
  the next diff repaints once, fully — never trusts a stale mirror.

---

## 3. Pointer-priority damage during a drag

Ask 1's second half. The damage loop already sent only changed regions;
v0.2.84 orders them. While a button is down (`ptrDown`, refreshed by
`notePointer()` from every inject — click, motion, release, scroll — and
valid for 1.5 s), pending `dirtyBands` are sorted by distance to the
pointer's row: the band under the finger paints FIRST, the rest keep
top-down order. On a budget-limited tick, the region the user is watching
is never the one left pending. This is the last piece of the drag fix:
v0.2.83 made drags incremental; this makes them incremental *from the
grip point outward*.

---

## 4. A badge that tells the truth: IDLE vs LIVE vs cap

Ask 2. The old badge derived `LIVE x fps` from painted frames, so a
damage-driven pipeline that correctly paints NOTHING on a quiet desktop
showed `LIVE 0.0 fps` — indistinguishable from a dead one. v0.2.84
separates two clocks:

* **proof** (`nativeProofAtMs` / `lastSourceProofAtMs`): a grab or mailbox
  read *succeeded*. The pipeline is alive. `nativeFresh` and the watchdog
  are keyed on this, not on paint — an idle desktop is fresh by design.
* **paint** (`lastAnyFrameAtMs`): pixels actually changed on screen.

The badge head now reads:

```
LIVE 12.3 fps   … updates flowing (painted-frame rate)
IDLE 7s - nothing changing   … proof is fresh, paint is quiet: SUCCESS state
STALE …   … only when there is no source proof either — actually dead
```

and always carries the pipe's real capacity: `cap~Nfps`, computed from
measured `nativeKbPerS` over full-frame bytes (`kb*1024 / (w*h*2)`) — the
capture rate the hardware can sustain, which by construction does not
collapse to zero when the desktop is quiet. While IDLE the view
self-invalidates once a second so the readout stays true without frames
arriving to drive it.

The full-screen healer is now adaptive: a proof-clean sweep that uploaded
nothing means the screen really is static, so the periodic healer backs off
(150 ticks → doubling → max 4×) and resets to 150 on any upload; the first
clean sweep after connect always promotes (firstClean guard). Info line
gains `diff-skips=N healer=Nt`.

---

## Verification

**JVM wire harness — 26/26** against a real Xvfb on :5, the APK's own
`X11Client.kt` (new-API checks by reflection, so the harness compiles
against any client revision). New in v0.2.84:

```
ok  grabRegion565 returns the painted rect as raw RGB565 LE, stride-free
      [80x60, every 16-bit unit = 0x7e0]
ok  the LUT is exact for all 65536 codes and agrees with grabRegionArgb
      [lut[0x7e0]=0xff00ff00]
```

Cost of one update, re-measured this release (no proot):

```
full screen 576x1280   1474560 B   med 3.91 ms  →  255.9 fps
typical window 480x360  345600 B   med 1.16 ms  →  864.4 fps
drag sliver 576x40       46080 B   med 0.28 ms  → 3595.5 fps
small damage 120x90      21600 B   med 0.11 ms  → 8923.0 fps
```

**fbdir end-to-end proof — 20/20** (`tools/x11probe/fbdir/fbdir_e2e_proof.py`, raw-socket X11
painter vs a real `Xvfb :8 -fbdir`): header parses through the exact
`xwdHeaderOf` plausibility rules (BE header / LSBFirst pixels, v7 ZPixmap,
depth/bpp 16, bpl = w*2, pixels at 160 + 64*12); a PolyFillRectangle'd
green 0x07E0 rect reads back byte-exact through the file; a second rect
repainted red 0xF800 via ChangeGC reads back byte-exact with the first
rect surviving; pixels outside the rects untouched; **mtime unmoved across
both paints while content changed** (the stat-gate trap, proven); mtime
also stands still while idle. Two forensic notes from building the proof:
the connection setup's screen list follows the FORMAT list
(`numFormats` at setup byte 21 — reading the first FORMAT record
`{depth=1,bpp=1,pad=32}` as the root window yields the plausible-looking
0x200101), and a duplicate CreateGC answers BadIDChoice whose error packet
arrives where the sync reply is expected — the proof now surfaces error
packets by code instead of silently misreading them.

Reproduce: start `Xvfb :8 -screen 0 576x320x16 -listen tcp -ac -extension
MIT-SHM -fbdir /tmp/fbtest2`, then `python3
tools/x11probe/fbdir/fbdir_e2e_proof.py` (expects the file at
`/tmp/fbtest2/Xvfb_screen0` and TCP 6008).

**Compile audit**: single kotlinc pass, 25 kt + 60 R.java + binding →
534 classes, every one traceable to a real source or generated file.

One trap worth recording for future releases: kotlinc 1.9.24 at
`-jvm-target 17` miscompiles an inlined `IntArray(65536) { v -> … }`
factory inside `<clinit>` — it emits a loop that reads local slot 2 before
any store, and the StackMapTable doesn't declare it. The JVM harness never
saw it (it compiles at the default target), but d8 rejected the class with
an internal error and — because the driver kept the previous dex — the
first build packaged v0.2.83 dex under a v0.2.84 shell. Stage 10 caught it:
exactly the five new dex symbols failed. The LUT is now built by
`buildRgb565Lut()`, a plain function with a plain while-loop, whose
`<clinit>` is `invokestatic` + `putstatic` and which every tool in the
chain accepts. Lesson: a d8 "internal error" is a build failure even when
dex files exist on disk — they are the LAST build's.

**verify_apk.sh**: 66 checks — dex must contain `grabRegion565`,
`diffUpload`, `paintRawFrame`, `xwdHeaderOf`, `fbdirAvailable`; the APK's
`emux-x11` asset must contain `-fbdir /tmp/emux_fbdir` and `FBDIR_CHECKED`.

## Files touched

* `app/src/main/java/com/emux/x11/X11Client.kt` — `grabRegion565`,
  `RGB565_TO_ARGB` (@JvmField).
* `app/src/main/java/com/emux/x11/DesktopService.kt` — diffUpload/prevRaw
  band differ, paintRawFrame, xwdHeaderOf extraction, fbdir mail reader +
  gates, proof-vs-paint clocks, nativeCapacityFps, adaptive healer,
  pointer-priority band sort, notePointer wiring, lifecycle resets.
* `app/src/main/java/com/emux/x11/DesktopView.kt` — IDLE/LIVE/cap~ badge,
  proof-aware STALE, idle self-invalidate.
* `app/src/main/assets/bin/emux-x11` — one-time `-fbdir` detection, stale
  screen cleanup, token on both launch sites.
* `app/src/main/java/com/emux/core/FsLayout.kt` — ENV_VERSION 76.
* `app/build.gradle.kts` — 184 / 0.2.84.
* `tools/verify_apk.sh`, `tools/x11probe/jvmwire/JvmWireProof.kt` — the
  new checks above.
