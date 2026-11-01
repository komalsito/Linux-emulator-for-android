# v0.2.72 (vc172) — the GetImage wire bug: found by harness, not by hypothesis

## Field evidence (v0.2.71, HUAWEI M-Pencil Pro, 2026-11-01 01:15)

```
STALE 14s - no frames arriving (capture dead? respawning) capage=16s
INPUT: XTEST (control plane)
proof=ok touch=9 inj=18 fail=0 ctrl=:1 cap=:1 capage=16s src=native agent=idle
```

Input plane healthy for the **fourth** consecutive round (inj counting, fail=0).
`src=native` proves the new native loop CONNECTED — and `STALE` proves it never
delivered a single usable frame stream: the pane was a photograph painted by
whichever source ran last. So the failure was inside `grabRootArgb()` itself,
on every attempt, while 32-byte QueryPointer round-trips on the same server
succeeded. A per-request failure that spares small requests and kills big ones
usually means bandwidth; a failure that kills ALL grabs usually means the
server is rejecting the request.

## Proof, not guesswork: `tools/wiretest/xgetimage_wiretest.py`

A byte-exact Python replica of the Kotlin wire path (same setup request, same
reply walk, same GetImage body) run against a real **Xvfb 21.1.7** (same major
as the field server), geometry 1280x694x24 (the field geometry):

```
setup ok: root=0x50d 1280x694 depth=24 order=0 maxreq=65535 units screens=1 fmts=6
FULL GRAB FAILED: ('ERROR code=16 res=0x0', 0.000s)
```

**Error 16 = BadLength.** `xGetImageReq` is **24 bytes = 6 units total**, and
the ZPixmap **format lives in header byte 1** (the minor-opcode slot):

```
CARD8 opcode=73 | CARD8 format=2 (ZPixmap) | CARD16 length=6
CARD32 drawable | INT16 x | INT16 y | CARD16 width | CARD16 height
CARD32 plane-mask
```

v0.2.71 put `format` INSIDE a 24-byte body and sent 28 bytes / 7 units with
header format=0 → the server answered BadLength to **every grab, forever**.
That is the whole field symptom: connected capture plane, zero frames, healthy
control plane, frozen photograph, "input detected but nothing responds".

With the request corrected the harness passes end-to-end:

```
full grab 1280x694: depth=24 payload=3553280 bytes (expect 3553280) in 0.041s
derived stride=5120 (w*4=5120)
full grab #1..#5: 0.016s / 0.016s / 0.030s / 0.011s / 0.012s  ok
WIRETEST PASS
```

## What v0.2.72 changes

1. **`X11Client.grabRootArgb()`**: 20-byte body, format=2 in the minor byte,
   6-unit request. Wire-verified by the harness (kept in-repo at
   `tools/wiretest/xgetimage_wiretest.py` — run it against any Xvfb before
   doubting the capture path again).
2. **`X11Client.lastGrabError`**: the exact rejection (`pkt=0 err=16`,
   `no-reply(timeout/eof)`, `depth=…`, `short=…`) is recorded per failure.
3. **STALE pane names it**: `STALE …s … capage=…s nat=pkt=0 err=16` — the next
   field screenshot can never again say only "no frames arriving".
4. **Silence watchdog**: if NO source (native or guest) delivers a frame for
   25 s while the desktop is up, the guest `emux-capture` pipeline starts as
   fallback regardless of which path was supposed to be primary. The v0.2.71
   guards all keyed off the legacy session or its publication file; this one
   keys off pixels arriving.
5. Grab latency telemetry (`nativeGrabMs`) for the next round's sizing.

Input plane: untouched (four rounds of proof it injects correctly).
Build: vc172 / 0.2.72, ENV_VERSION 72 (app-side only). Gate 51/0/0, signature
`5e3ef254…a1fa` → `adb install -r` in place.
