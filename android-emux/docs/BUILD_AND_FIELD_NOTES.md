- **v0.2.10 / vc110** — MIT-SHM disabled on Xvfb (`-extension MIT-SHM`: shm syscalls
  hang under proot); stage-wise grab with per-stage echoes.
- **v0.2.11 / vc111** — `timeout(1)` removed from the grab path (it failed silently
  under proot+shim); stages bounded by killer subshells + `wait`, real rc echoed.
- **v0.2.12 / vc112** — converter selftest (8-byte header) with loud drop + busybox-awk
  NUL-safe fallback (busybox installed on demand); size-mismatch and push-failure echoes.
- **v0.2.13 / vc113** — ADOPT don't fight: if anything answers on the display, launch
  zero Xvfbs and use it; guest sweep files-only; previous-attempt kill removed; DISPLAY
  exported in tcp form when no unix socket (this libX11 doesn't fall back).
- **v0.2.14 / vc114** — per-connection stream header (in per-frame nc mode the one-shot
  header made every re-accepted connection parse frame bytes as header → "bad capture
  magic" forever); visibility xterm at session start.
- **v0.2.15 / vc115** — perl encoder fallback (native→perl→busybox awk→awk); verbose
  native-converter probe (rc/bytes/stderr).
- **v0.2.16 / vc116** — `verify_x()`: adoption/gates require a real xwd handshake, not
  socket presence; rotate-and-launch if nothing verifies post-ladder.
- **v0.2.17 / vc117** — connect-based liveness sweep: wildcard `bind()` with
  SO_REUSEADDR succeeds OVER a loopback-specific listener (the ten-version-old reason
  "port6000: free" coexisted with a held 6000); post-kill sweep waits for silence.
- **v0.2.18 / vc118** — verify_x instrumentation (which leg, kernel tcp table rows, xwd
  rc; watcher first-up timestamp) + self-heal: if an adopted server vanished, rotate the
  display and launch Xvfb for real within the same session.
- **v0.2.19 / vc119** — THE META-BUG: guest helpers re-seeded only inside
  startupRepairAsync, which on the field ROM throws before the reseed (swallowed) — the
  device ran v0.2.16 scripts through v0.2.18, so every guest-script fix was dormant.
  Desktop start now re-seeds helpers/conv/shim straight from APK assets every time
  (pane note: "helpers re-seeded from APK assets (env N)").
- **v0.2.20 / vc120** — watcher re-reads `/tmp/emux_display_num` every tick (follows
  rotations/self-heal); self-heal completes the desktop: sets the X-up marker and
  relaunches WM + xterm against the new display (earlier launches died on the ghost).
