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
- **v0.2.21 / vc121** — sticky-bottom pane: the v0.2.3 scroll opened at the TOP of the
  history, so once the ring outgrew the screen every screenshot showed ancient lines.
  Now pinned to newest by default; drag up to read, back down to re-pin.
- **v0.2.22 / vc122** — auto diag dumps: the diagnostics bundle is mirrored into the
  Terminal tab at 60 s of frameless waiting, at the 120 s give-up, and on x11/capture
  post-mortems. No typing or scrolling needed to collect field evidence.
- **v0.2.23 / vc123** — THE signal 11, root-caused offline under qemu-user: the aarch64
  `emux-rgb565` `_start` did `ldp x0, x1, [sp]`, loading argv[0] (the first element)
  instead of &argv[0]; every argv[1..] access dereferenced the argument strings as a
  pointer table (fault address = ASCII `565\0-s\0` of its own cmdline). Only -s/-F
  modes touch argv, so the no-arg convert worked while the selftest and frame headers
  SIGSEGV'd on every arm64 device since day one - the native encoder was dead on
  arrival and all rounds fell back to slow awk/perl. Fixed to
  `ldr x0, [sp]; add x1, sp, #8`; all four ABIs rebuilt and qemu-verified byte-exact.
- **v0.2.24 / vc124** — per-frame EOF is normal: in per-frame nc mode every frame ends
  its connection; the v0.2.7 re-accept catch logged a note and slept 500 ms per EOF
  (~2 fps cap + flooded pane once the native encoder runs at 10 fps). EOF after >=1
  decoded frame now re-accepts immediately and silently.
