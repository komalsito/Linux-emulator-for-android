# EmuX — Linux + X11 emulator for Android, in one app

EmuX runs a full Linux userland inside a single Android APK — **your choice of
Alpine (default), Debian 12, Ubuntu 24.04, Arch Linux ARM or Fedora** — with
the X11 desktop built into the same app (no companion app like Termux:X11):

- **Terminal tab** — custom VT100/xterm emulator view (`VtTerminalView`) with an extra-keys row (ESC, CTRL, ALT, arrows…), real-PTY sessions (util-linux `script(1)` from the distro, exec'd through the musl loader — verified pts + TIOCSCTTY job control), and a foreground `TerminalService` for power-efficient background execution (Termux-style).
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
./gradlew assembleDebug
# output: app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+ and the Android SDK (platform 34).

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

- v0.1.59 debug build — not yet release-signed or R8-minified.
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
