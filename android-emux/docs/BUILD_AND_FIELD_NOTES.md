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
- **v0.2.59 / vc159** — THE DESKTOP-WAS-NEVER-INTERACTIVE round. Three independent
  root causes, each measured on a live Xvfb 1.21 + Openbox 3.6.1 stack rather than
  inferred; see `DESKTOP_FIX_v0.2.59.md` for the evidence and the probes.
  1. **XTEST was dead from wire-protocol bugs in `X11Client`, not from the ROM.**
     QueryExtension was sent as opcode 15 (that is QueryTree; the real opcode is 98),
     the setup reply was parsed big-endian on a little-endian server, the screen walk
     used the wrong offsets, `GetProperty` omitted its window field, and
     XTestFakeInput used a 24-byte body (the server only accepts a 36-byte request:
     type/detail/time/rootX/rootY/root/deviceid). Rewritten; keysym→keycode now comes
     from the server's own `GetKeyboardMapping`, so shift level is always correct.
     Two MORE bugs of the same class were then caught by the new
     `tools/x11probe/protocol_audit.sh` harness running the compiled client
     against a live server: `internAtom()` and `getProperty()` wrote their BOOL
     (`only-if-exists`, `delete-after`) into the request BODY instead of the
     header's second byte, inflating each request by 4 bytes, so the server
     answered BadLength for EVERY atom - even `WM_NAME` - and
     `isWindowManagerRunning()` reported false forever while Openbox ran.
     `XErrorEvent.describe()` now names the error and request (`BadLength on
     InternAtom`) and reads majorCode as the single byte at offset 10 that it
     is; that diagnostic is what surfaced the bug.
  2. **The FIFO fallback typed nothing because keysyms were passed as keycodes.**
     The host sent `K <keysym-int>` and the agent ran `xdotool keydown <int>`, but
     xdotool treats a bare number as a KEYCODE: measured, `xdotool keydown 97`
     injects keycode 97 while keysym 97 ('a') is keycode 38. Protocol is now
     `T <text>` (xdotool type) / `N <name>` (xdotool key), payload kept verbatim
     (the old `read -r t a b c` truncated any text containing a space), legacy `K`
     translated to names. Multi-key `N` must stay UNQUOTED (xdotool takes one key
     name per argument; one quoted string exits 2).
  3. **"signal 11" was the app killing its own ptrace tracer.** `killDesktopGuests()`
     built `/bin/sh -c '<payload>'` with the literal patterns `Xvfb|Xorg|emux-capture|xwd`
     inside the payload, and proot's OWN argv contains that payload. proot binds the
     host `/proc`, so the sweep matched the tracer and `kill -9`'d it; a tracee whose
     tracer dies reports exactly "proot warning: signal 11". `[ $p != $$ ]` guarded the
     shell, not its proot parent. Patterns now travel in the environment
     (`EMUX_SW1..4`, never in any argv) and the shell skips itself plus its parent
     parsed from `/proc/self/stat`. A short list degrades to an unmatchable sentinel,
     never to `*""*` (which matches every process).
  Also: bare `openbox` does NOT run autostart (openbox.org/help/Autostart) so the
  seeded panel/colours never executed — `start_wm()` now uses the documented
  `openbox --sm-disable --startup "/bin/sh ~/.config/openbox/autostart"`; A/B pixel
  census goes from 100% pure black (1 colour) to 97.7% `#20344d` (102 colours).
  `paint_root()` guarantees a non-black root even if pcmanfm wedges. picom is opt-in
  (`touch /tmp/emux_want_picom`) — a compositor buys nothing on a 10 fps `xwd` stream
  and is a large LMK target. The v0.2.47 LMK bias is corrected: negative
  `oom_score_adj` needs CAP_SYS_RESOURCE so the terminal "-300" was ALWAYS silently
  rejected while the desktop "+100" succeeded, and `cmd.contains("proot")` skipped
  every real hog (Xvfb/Openbox/tint2/pcmanfm are tracees whose cmdline has no
  "proot"). Now verified-by-read-back and reported honestly on the pane.
  Input no longer blocks the UI thread: FIFO writes moved to a daemon thread with
  motion coalescing and FIFO-inode reopen (the agent rm+mkfifo's each session, so a
  cached stream wrote into an unlinked inode and every event vanished). IME commits
  are one `injectText` call, not one xdotool process per character.
  ENV_VERSION 67→68 so the corrected `emux-x11` is actually re-seeded onto devices.
  Full module type-checks clean (23/23 files, 261 classes, 0 errors) against the real
  API 34 `android.jar` + AndroidX 1.13/1.7/2.8 jars via `probe/compile_all.sh`; the
  APK itself was NOT packaged here (1 GB sandbox OOM-kills the Gradle daemon).
- **v0.2.60 / vc160** — THE DESKTOP-WAS-STILL-BLACK round, plus the requested
  input UX. Evidence and measurements in `INPUT_AND_PAINT_v0.2.60.md`; every
  number below comes from a live Xvfb + Openbox + pcmanfm stack, not from
  reading code.
  1. **Input UX (the task):** long-press now HOLDS button 3 (drag it for a
     right-drag, released where the finger lifts) instead of the v0.2.59
     press+60ms+release, which made right-drags impossible and dropped the
     context menu the instant it appeared; two-finger tap = button 2 (the only
     practical paste in an X terminal); two-finger drag = the X scroll wheel
     (buttons 4/5 vertical, 6/7 horizontal) at fit-to-screen and viewport pan
     while zoomed, accumulated and quantised one click per SCROLL_STEP_PX
     (cap 12/call) so a slow drag does not machine-gun the server at 10 fps;
     helper bar (toggle `keys`) with Esc/Tab/Ctrl/Alt/Super/arrows/BackSpace and
     STICKY modifiers — tap Ctrl, type `c`, get Ctrl+C — consumed by the next
     keysym/IME commit/hardware key. `injectKeysymCombo()` has no shift
     parameter on purpose: `typeKeysymTap()` applies the shift level from the
     server's own keymap, so passing Shift too would double-apply it (witnessed:
     Ctrl+T arrives as `state 0x5, keycode 28 (keysym 0x54, T)`). The helper bar
     was hit-tested on both DOWN and UP, so one tap fired twice; `overlayDown`
     now consumes it once. Proven at the wire by `gesture_e2e.sh` + `xev`
     (10/10, 0 X errors).
  2. **The desktop was still 99.4% pure black on device.** v0.2.59 fixed one
     reason and there was a second underneath it: `pcmanfm --desktop` OWNS the
     root window (paints its own pixmap, publishes `_XROOTPMAP_ID`), so
     `paint_root()`'s xsetroot could never be seen and the wedge watchdog —
     which keys on that atom being ABSENT — never fired. Three defects in the
     pcmanfm "rice", each alone sufficient: (a) it wrote pcmanfm.conf with
     `2>/dev/null` but never mkdir'd `~/.config/pcmanfm/default`, which does not
     exist until pcmanfm has run once, so on a fresh install the write failed
     silently; (b) `wallpaper_mode=6` is not a value pcmanfm parses — the key
     takes a STRING (none|color|stretch|fit|center|tile|crop) — so `wallpaper=`
     was ignored and only `desktop_bg=#10141a` ever painted, i.e. RGB565 (2,5,3),
     a black pane: **the shipped wall.png has never once been displayed on any
     device**; (c) the authoritative file is the per-screen
     `desktop-items-0.conf`, which OVERRIDES `[desktop]` in pcmanfm.conf, and
     pcmanfm creates it on first run with `desktop_bg=#000000` — measured, a
     valid fixed rice still leaves that install 100% black. Now: mkdir first,
     `wallpaper_mode=crop` (or `color` + empty `wallpaper=` when wall.png is
     missing), `desktop_bg=#20344d` matching paint_root, items file seeded in
     full when absent and otherwise REPAIRED only when it is still pcmanfm's
     untouched default (`desktop_bg=#000000` AND no `wallpaper=/…`) — a user's
     own colour/wallpaper and icon layout are never touched. The repair appends
     absent keys (pcmanfm's default file has NO `wallpaper=` key at all, so
     rewriting alone left `crop` pointing at nothing) and is idempotent.
     `show_wm_menu=1` in both files so a long-press on the desktop opens the
     Openbox menu. Rice stamp v0.2.51 → v0.2.60. `desktop_paint_test.sh` 20/20,
     including a "before" case that runs the seed extracted from commit 8e80f81
     and reproduces 99.4% pure black.
  3. **A user's own Openbox autostart was backed up and then dropped.** v0.2.59
     copied it to `autostart.emux-bak` but the write condition did not know
     about the backup, so nothing was written: no panel, no root colour, no
     icons — the original "I can't see the desktop" report from a completely
     different cause, hitting exactly the user who had customised Openbox once.
     Condition gained `|| [ -n "$EMUX_USER_BAK" ]` and the generated file now
     CHAINS the backup (absolute path written at generation time, so the
     generated file needs no `$HOME` escaping). `seed_autostart_test.sh` 23/23,
     extracting the block by MARKER — line-number extraction breaks on any edit.
  ENV_VERSION 68→69 so §2 and §3 actually reach existing installs. Type-checks
  clean (23/23 files, 263 classes, 0 errors); APK still NOT packaged here (1 GB
  sandbox OOM-kills the Gradle daemon) — needs `./gradlew :app:assembleDebug`
  on a real machine, and the root `emux-debug.apk` is still v0.2.58.
