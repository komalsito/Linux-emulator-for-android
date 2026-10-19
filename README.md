# EmuX — Linux + X11 emulator for Android, in one app

EmuX runs a full Alpine Linux userland inside a single Android APK with:

- **Terminal tab** — custom VT100/xterm emulator view (`VtTerminalView`) with an extra-keys row (ESC, CTRL, ALT, arrows…), real-PTY sessions (util-linux `script(1)` from the distro, exec'd through the musl loader — verified pts + TIOCSCTTY job control), and a foreground `TerminalService` for power-efficient background execution (Termux-style).
- **X11 Desktop tab** — in-distro `Xvfb` + Openbox under **bundled proot** (chroot-equivalent without root), with the framebuffer streamed to the Android UI via a TCP capture socket (`emux-capture`: `xwd` → PPM → RGB565 → single persistent socket; fixed-size 8-byte stream header + 12-byte frame headers).
- **Root-aware bootstrap** — on first launch `BootstrapManager` downloads & extracts an Alpine minirootfs (~4 MB) into app-private storage, then runs the distro's own `apk` (through the musl loader, `--root` mode) to install `util-linux` (script/PTY), `bash`, and — unless disabled in Settings — the desktop stack (`xvfb`, `xkbcomp`, `openbox`, `xterm`, `xwd`, `xhost`, `netpbm`). If Magisk/root is detected, `RootManager` upgrades sessions to real chroot/Xorg mode.
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
    core/                BootstrapManager, RootManager, FsLayout
    terminal/            TerminalService, TerminalSession (TtyWrap PTY), VtTerminalView, ExtraKeysView
    x11/                 DesktopService, DesktopView, X11Client, UsbCaptureActivity
    api/                 EmuXExecuteService (external command API)
  app/src/main/assets/bin/     emux-init.sh, emux-x11, emux-capture, emux-init-probe.sh
  app/src/main/assets/proot/   bundled proot + libtalloc + libandroid-shmem per ABI
                               (Termux-built binaries; see proot/LICENSES.txt)
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

- v0.1.28 debug build — not yet release-signed or R8-minified.
- Terminal geometry: no TIOCSWINSZ from Java (no NDK); shells default to 80x24.
- Desktop requires the one-time apk component install (Settings → “Install
  desktop components”, on by default) and ~90 MB of disk.
- On-device runtime validated on hardware only; please report issues via
  GitHub Issues with `adb logcat | grep -i emux` (the in-app
  `environment self-test` banner also pinpoints the broken layer).
