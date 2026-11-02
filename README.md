# EmuX — Linux + X11 emulator for Android, in one app

EmuX runs a full Linux userland inside a single Android APK — **your choice of
Alpine (default), Debian 12, Ubuntu 24.04, Arch Linux ARM or Fedora** — with
the X11 desktop built into the same app (no companion app like Termux:X11):

- **Terminal tab** — custom VT100/xterm emulator view (`VtTerminalView`) with an extra-keys row (ESC, CTRL, ALT, arrows…), real-PTY sessions (util-linux `script(1)` from the distro, exec'd through the musl loader — verified pts + TIOCSCTTY job control), a foreground `TerminalService` for power-efficient background execution (Termux-style), **copy/paste** (long-press + drag selects across scrollback, release copies to the clipboard; long-press without moving pastes), and an in-app **physical-keyboard layout** (Settings → *Terminal physical keyboard layout*, or the `es⇄` extra-key / Alt+Shift) with dead-key accent composition for Spanish ISO keyboards.
- **X11 Desktop tab** — in-distro `Xvfb` + Openbox under **bundled proot** (chroot-equivalent without root), with the framebuffer streamed to the Android UI via a TCP capture socket (`emux-capture`: `xwd` → PPM → RGB565 → single persistent socket; fixed-size 8-byte stream header + 12-byte frame headers).
- **Multi-distro, root-aware bootstrap** — on first launch `BootstrapManager` downloads & extracts the selected distro rootfs into app-private storage (`files/distros/<id>/`), then runs the distro's OWN package manager inside the bundled proot — `apk` (Alpine), `apt` (Debian/Ubuntu), `pacman` (Arch), `dnf` (Fedora) — to install `util-linux` (script/PTY), `bash`, `sudo`, and — unless disabled in Settings — the desktop stack (Xvfb, xkbcomp, Openbox, xterm, xwd/xhost, netpbm, netcat). Distro images are the SHA256-pinned proot-distro release tarballs (Debian/Ubuntu/Arch/Fedora) plus Alpine minirootfs from the official mirrors; Ubuntu additionally falls back to Canonical cloud-images. **Installed distros coexist on disk** — switching in Settings → "Linux distribution" activates one; a not-yet-installed selection shows an explicit Install prompt (nothing downloads without your tap). If Magisk/root is detected, `RootManager` upgrades sessions to real chroot/Xorg mode.
- **Tasker/API integration** — `EmuXExecuteService` exposes a permission-guarded intent so other apps can run commands inside the Linux environment.

## Why targetSdk 28 (important)

Android 10+ denies `execve()`/`mmap(PROT_EXEC)` of any file in app-private
storage for apps targeting SDK 29+ (W^X; SELinux `untrusted_app` domain — see
[Android 10 behavior changes](https://developer.android.com/about/versions/10/behavior-changes-10)
and [termux-app#1072](https://github.com/termux/termux-app/issues/1072)).
A Linux-distro app therefore targets SDK 28 exactly like Termux does
(`untrusted_app_27` keeps exec-of-app-data). Trade-off: not distributable via
Google Play — EmuX ships as a sideloaded APK, same as Termux on F-Droid.

## Install / test

```bash
adb install -r emux-debug.apk
```

Debug-signed build; requires Android 7.0+ (minSdk 24). First launch bootstraps
the rootfs automatically (banner + Install button; runs once — the completion
marker `.env_version` plus a fixed rootfs-completeness probe make re-launches
instant, and app updates re-seed helpers/components WITHOUT re-downloading).

**Upgrading from any pre-0.1.47 build requires ONE `adb uninstall com.emux`**
(older APKs were signed with ephemeral per-machine debug keys). Since v0.1.47
the debug keystore is committed in `android-emux/debug-keystore/`, so the
signature is stable across builds forever and `adb install -r` works again.

## Using the X11 desktop

Everything below works with one or two fingers; no hardware keyboard is needed.
The first time you open the desktop, a card listing these gestures fades in over
the session and the on-screen key bar opens itself — any touch dismisses the
card, and your choice about the bar is remembered after that.

| gesture | what the desktop receives |
|---|---|
| tap | left click |
| drag | move / window drag |
| **long-press** | **right click, held** — keep holding and drag for a right-drag (range select in a terminal); release for the context menu |
| **two-finger tap** | **middle click** — how you paste into an X terminal |
| **two-finger drag** | **scroll wheel** (up/down and left/right); pans the viewport instead once you are zoomed in |
| pinch | zoom the viewport |
| double-tap | **double click** — opens desktop icons, files and dialogs (v0.2.66: it used to be eaten by the view toggle, which is why nothing on the desktop could ever be opened) |
| `FIT` chip (`keys` bar) | toggle letterbox-fit ↔ fill-screen |
| `keys` button (top-right) | on-screen **Esc, Tab, Ctrl, Alt, Super, ▲, ▼, ⌫** |

`Ctrl`, `Alt` and `Super` are **sticky**: tap `Ctrl`, then type `c` on the soft
keyboard and the desktop receives Ctrl+C. The chip lights up while armed and
clears itself after the next key, so a modifier can never get stuck down.

## If the desktop is black

Run this inside the Linux terminal (Terminal tab, or any shell in the distro):

```bash
emux-x11 doctor
```

It is **read-only** — it changes nothing and is safe against a live session —
and prints one screen covering the X server, the window manager and panel, what
colour the root window actually is (five sampled points, not a guess), the
`pcmanfm` background configuration, whether input can be injected at all, and
whether frames are being produced, then names the problem it found:

```
== desktop ==
  openbox:      RUNNING (pid 19617)
  tint2 panel:  RUNNING (pid 19627)
  pcmanfm:      RUNNING (pid 19628)
  root colour:  #000000 #000000 #000000 #000000 #000000
  verdict:      PURE BLACK at all 5 sample points
== verdict ==
  - desktop-items-0.conf is still pcmanfm's untouched default
    (desktop_bg=#000000). It OVERRIDES pcmanfm.conf, so the desktop is
    black no matter what the rice says.
```

That distinction matters because "black" is the same pixels for six different
failures — X never came up, the WM died, the autostart never ran, `pcmanfm`
painted its own black root pixmap, capture died, or frames never reached the
app. Paste the doctor output into an issue and the cause is already identified.
The desktop pane also runs a shorter version of this check ~6 s after launch, so
a black root is reported without you asking.

## If the desktop is visible but taps do nothing (v0.2.78)

The input badge at the bottom of the desktop pane now reports **buttons**, not
just the transport:

```
INPUT: XTEST (control plane)  btn=pervious wm=openbox     <- healthy, green
INPUT: buttons SWALLOWED (motion ok)  btn=DEAD wm=openbox <- red, and it means it
```

`btn=` is a measurement, not a guess: the app creates a small window on the
desktop, aims the pointer at it, injects a real `ButtonPress` through XTEST, and
reads its **own** connection for the press that should come back — the `xev`
experiment, in-app. `proof=ok` now requires both legs; a session where motion
arrives but buttons do not reads `proof=motion-only`, which is what every build
from v0.2.51 to v0.2.77 called `proof=ok`.

The cause was our own `~/.config/openbox/rc.xml`: a `<context name="Frame">`
mousebind makes Openbox `XGrabButton()` the **frame window** — the parent of
every application window — and xorg checks passive grabs on every window from
the root down to the one under the pointer, so the press was delivered to
Openbox and never replayed. Motion and keys use different masks and were never
affected. Desktop icons still worked (a desktop-type client is not framed),
which is why this looked like "some things are clickable" for so long. Full
mechanism, source citations and the measured 8-case matrix:
`android-emux/docs/CLICK_SWALLOW_v0.2.78.md`.

If it ever happens again the app escalates by itself: the guest rewrites the
stamped `rc.xml` and sends `openbox --reconfigure` (no restart, windows kept),
and if buttons are *still* swallowed it switches window manager — or runs with
none, which still shows every window and still delivers every click.
`emux-x11 doctor` has a **`clicks (button delivery)`** section naming which
`rc.xml` Openbox is actually reading, its stamp, its grab contexts, the app's
verdict and what the ladder did about it:

```
== clicks (button delivery) ==
  WM mode:      openbox  RUNNING (pid 19617)
  WM fallback:  not needed
  rc.xml read:  /data/data/com.emux/files/rootfs/home/komalsito/.config/openbox/rc.xml
  rc.xml stamp: EmuX rc v0.2.78 (current)
  grab contexts: none
  app verdict:  1762147200 ok ButtonPress b=1 arrived at 640,360 on 0x600001
                (framed=yes, motion=2, release=1)
  ladder action: -
```

`grab contexts: Frame`, `Client` or `Desktop` in that output is the bug, named.

## Distros & package management (v0.1.47)

| Distro | Version | Download | Package manager | Notes |
|---|---|---|---|---|
| **Alpine** (default) | 3.20.3 minirootfs | ~4 MB | `apk` | fastest install; `sudo`+`apt` preinstalled for familiarity |
| **Debian** | 12 bookworm | ~40 MB | `apt` | full-fledged glibc distro |
| **Ubuntu** | 24.04 LTS noble | ~61 MB | `apt` | biggest software selection; falls back to Canonical cloud-images |
| **Arch Linux ARM** | rolling | ~138 MB | `pacman` | ALARM repos preconfigured |
| **Fedora** | 40 | ~30 MB | `dnf` | bleeding-edge developer distro |

All distros install into `files/distros/<id>/` and **coexist**: pick the active
one under Settings → *Linux distribution*. Selecting a distro that is not on
disk shows an *Install & switch* prompt — other distros are never touched, and
switching back is instant (no re-download). Inside proot you are fake-root:
`sudo` works out of the box on every distro. Images are the proot-ready
SHA256-pinned tarballs from [termux/proot-distro releases](https://github.com/termux/proot-distro/releases)
(Debian/Ubuntu/Arch/Fedora) — pinned in `core/DistroCatalog.kt`; a full custom
`.tar.gz`/`.tar.xz` rootfs URL can be set via Settings → *Mirror / custom
rootfs URL* (which also remains the Alpine mirror-base override).

See `android-emux/docs/ACTION_PLAN.md` for the full handoff/action-plan
document (architecture invariants, device landmines, sandbox build recipe,
open issues).

## Build from source

```bash
cd android-emux
./gradlew :app:assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk

sh tools/verify_apk.sh app/build/outputs/apk/debug/app-debug.apk   # must be all PASS
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+, the Android SDK (platform 34) and ~4 GB of free RAM.

**No 4 GB to spare?** `sh android-emux/tools/build-apk/no-gradle-build.sh` builds and
signs the same APK with **no Gradle at all** — about 4 minutes on a 1 GB machine —
then runs the verifier before writing `emux-debug.apk`. It generates the three things
Gradle would have supplied (the per-library R classes, the ViewBinding, and the
linked resources), and is documented in
[`android-emux/tools/build-apk/README.md`](android-emux/tools/build-apk/README.md).
`manual-build.sh` alongside it is the hybrid path (a small-heap Gradle warm-up, then
the same steps) and stays the reference wherever Gradle can run.

See [`android-emux/docs/BUILDING_THE_APK.md`](android-emux/docs/BUILDING_THE_APK.md)
for the full recipe and what each `verify_apk.sh` failure means.

**Verify before you install.** The root `emux-debug.apk` goes stale silently — it
once sat at v0.2.58 while the source reached v0.2.61, so *three* releases of desktop
fixes were committed and pushed but installable by nobody. It is now v0.2.62 and
matches the working tree (`verify_apk.sh`: 51 PASS / 0 FAIL). `sh tools/verify_apk.sh`
compares an APK against the working tree (identity, signature, every guest asset
byte-for-byte, whether the dex carries the current code, and whether every packaged
dependency version matches `deps.lock`) and exits non-zero on any mismatch. Signing
must stay on the committed `debug-keystore/emux-debug.jks`: a different signature
means `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and the only way past it is an uninstall,
which **deletes the downloaded rootfs**.

**If it crashes on launch, it now tells you why.** From v0.2.62 a process-wide
handler (`com.emux.CrashReport`) writes the full stack trace to
`Android/data/com.emux/files/last_crash.txt` — readable with a file manager, no adb
or root — and the next launch shows it on screen with a copy button. That exists
because a v0.2.61 install crash-looped on a device with no adb, and the cause could
not be determined: see
[`android-emux/docs/CRASH_ON_LAUNCH_v0.2.62.md`](android-emux/docs/CRASH_ON_LAUNCH_v0.2.62.md)
for what was ruled out (packaging — the hand-built APK proved structurally
equivalent to an AGP build) and what was fixed instead.

## Repository layout

```
android-emux/            Gradle project (Kotlin, single module)
  app/src/main/java/com/emux/
    core/                BootstrapManager, DistroCatalog, RootManager, FsLayout
    terminal/            TerminalService, TerminalSession (TtyWrap PTY), VtTerminalView, ExtraKeysView
    x11/                 DesktopService, DesktopView, X11Client, UsbCaptureActivity
    api/                 EmuXExecuteService (external command API)
  app/src/main/assets/bin/     emux-init.sh, emux-x11, emux-capture, emux-init-probe.sh, emux-winsize
  app/src/main/assets/proot/   bundled proot + libtalloc + libandroid-shmem per ABI
                               (Termux-built binaries; see proot/LICENSES.txt)
  debug-keystore/        committed debug signing key (stable sideload upgrades)
emux-debug.apk           Latest debug build (also attached to GitHub Releases)
```

## Session architecture (v0.1.28)

```
VtTerminalView (IME/keys) ─ TerminalSession.write()
   └─ ProcessBuilder
        └─ util-linux script(1) from the rootfs, via musl loader   ← real PTY + ctty
             └─ /system/bin/sh -c "<emux-init.sh body>"            ← noexec-proof launcher
                  └─ proot -0 -r files/linuxroot -b /dev -b /proc -b /sys
                       └─ /bin/bash -l   (or: /bin/sh -l -c "$CMD")
```

proot (bundled, bionic, DT_RUNPATH → `LD_LIBRARY_PATH` redirect) makes every
absolute distro path resolve — including `/usr/bin/xkbcomp`, which Xvfb execs
at startup and which cannot be redirected by env or PATH (verified: it is a
compile-time path; Termux patches xorg-server for this reason). Fallbacks:
musl-loader exec (terminal only), then plain Android sh (CMD honored).

## Status / caveats

- v0.1.62 debug build — not yet release-signed or R8-minified.
  v0.1.62: DesktopService is a process-wide singleton (the reopen EADDRINUSE
  root cause), the X server starts as soon as its CORE packages land (WM +
  capture tools install in the background), and Xvfb auto-restarts once with
  -noshmem if it dies mid-session.
  v0.1.61: settle-pass for first-pass proot postinst failures (libpaper1/
  statx case), and the control-plane retry loop + capture respawn that
  v0.1.59 announced but never actually shipped.
  v0.1.60 fixes the two real desktop dependency bugs (sandbox-reproduced on
  the pinned image): wrong apt package name (xkbcomp -> x11-xkb-utils, which
  aborted the whole desktop install) and the missing xwdtopnm conversion step
  in the frame capture chain (0-byte frames -> 'waiting for X frames').
  v0.1.59: control plane keeps retrying until X is up (no more permanent
  "input disabled"), capture auto-respawns until first frame, longer
  guest-side waits for slow first-run installs.
  v0.1.58: desktop no longer races the background desktop-stack install
  (waits for foreign apt/dpkg, heals between retries); v0.1.57 applied
  Install immediately to live sessions and added the capture-toolchain
  cascade + silent-capture-death watcher.
  v0.1.56: off-UI-thread VT parsing (fixes apt-list ANR), helper re-seed on
  upgraded devices, dpkg pre-heal before the desktop cascade.
  v0.1.55: guest-PTY upgrade on seccomp-strict ROMs (real PTY instead of raw
  pipes), UTF-8 rendering + ONLCR emulation, startup gap-healing of missing
  base/desktop packages, desktop error history in pane + files/logs/desktop.log.
  v0.1.54 adds terminal scrollback (drag / double-tap / pgup-pgdn) and fixes
  the desktop restart leaks (orphaned Xvfb/capture + EADDRINUSE on reopen).
  v0.1.53 heals broken dpkg/apt state (interrupted journal + proot link(2)
  kernel bugs, termux/proot#377) at app start and via Settings →
  "Repair package manager", and fixes the host-side `$(tty)` launcher noise.
- Terminal geometry: no TIOCSWINSZ from Java (no NDK); shells default to 80x24.
- Desktop requires the one-time distro component install (Settings → “Install
  desktop components”, on by default) and ~90 MB of disk on Alpine (~400 MB on
  Ubuntu with the desktop stack).
- On-device runtime validated on hardware only; please report issues via
  GitHub Issues with `adb logcat | grep -i emux` (the in-app
  `environment self-test` banner also pinpoints the broken layer).
