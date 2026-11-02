# v0.2.76 (vc176) — read the mailbox that was already there

## Field evidence (v0.2.75, user-run guest commands, 2026-11-02 03:01)

```
STALE 36s ... nat=xerr=16:maj=73:min=0:r pipe=-1K b=8r
proof=ok touch=16 inj=32 fail=0 ... src=native
# user, inside the guest:
-rw------- 1 root root 1776640 2026-11-02 03:00:56 /tmp/emux_frame.rgb
Mon Nov  2 03:01:13 UTC 2026
DISPLAY=127.0.0.1:1 xdotool getmouselocation  ->  x:640 y:347 window:2097754
```

Three facts:

1. **maj=73** — the rejected request IS GetImage; the device's :1 listener
   refuses it in every shape (full, banded, depth 24, depth 16). Socket
   capture is dead on this ROM, permanently, in all four variants tried.
2. **/tmp/emux_frame.rgb**: 1,776,640 bytes = 1280x694x2 of raw RGB565,
   rewritten by the ALREADY-RUNNING emux-capture loop every iteration,
   BEFORE its nc push — the push is what wedges. The mailbox existed since
   long before this saga; nothing on the app side ever read it.
   17 s fresh beside capage=38s: the guest loop lives, the socket dies.
3. `xdotool getmouselocation` moves with the user's touches: input proven
   end-to-end inside the guest, eighth round. The photograph was always
   the only problem.

## v0.2.76

- **Unified mailbox reader** from session start: polls
  `tmp/emux_frame.rgb` (headerless raw RGB565; size == session geometry is
  the header; mtime-stable read guards the in-place write) and
  `tmp/emux_fb.xwd` (atomic XWD drops). Reads happen outside proot.
- **emux-capture `mail` mode** (3rd arg): the proven xwd+convert chain
  MINUS the nc push — grab, convert, write file, publish, loop. No push,
  no wedge, no respawn churn. Ladder: native -> mail-mode capture ->
  fbmail -> (never needed) plain push mode.
- ENV_VERSION 74 re-seeds emux-capture.
- Badge: `src=mail` / `src=fbmail` when a mailbox is painting.

Build note: sandbox cgroup (1 GiB, guardian kills at ~82%) no longer fits
the single-pass kotlinc; compile_classes.sh gained a LOWMEM two-pass mode
and the driver a PREBUILT_MERGED hook + cache-purge (posix_fadvise
DONTNEED) before compile; the round's APK was produced with the direct
K2JVMCompiler invocation (-no-stdlib, purged cache, 380m heap).
Gate 52/0/0, signature 5e3ef254…a1fa, `adb install -r`.

## Addendum — v0.2.77 (vc177): the pane wall over a living desktop

Field v0.2.76: `mailbox capture UP (mail): painting 1280x694` at 09:55:28,
then at 09:55:29 the full post-mortem diag bundle flooded the pane
("desktop starts briefly and then gives this output"). The post-mortem
itself said `tcp6000=true` — the server was ALIVE; what exited non-zero was
the warm-start LAUNCHER a second after frames began flowing. The xSession
watcher's fall-through treated any post-frame launcher death as terminal
(setError + diag dump).

v0.2.77 judges by the server, not the launcher's rc: post-frame death +
server reachable (tcp probe / unix socket) = "daemons detached, session
continues"; post-frame death + server gone = bounded whole-X respawn
(3x) instead of an instant ERROR wall. The diag bundle now only appears
when respawns are exhausted.
