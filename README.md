# EmuX — Linux + X11 emulator for Android, in one app

EmuX runs a full Alpine Linux userland inside a single Android APK with:

- **Terminal tab** — custom VT100/xterm emulator view (`VtTerminalView`) with an extra-keys row (ESC, CTRL, ALT, arrows…), PTY-backed sessions, and a foreground `TerminalService` for power-efficient background execution (Termux-style).
- **X11 Desktop tab** — in-distro `Xvfb` + Openbox, with the framebuffer streamed to the Android UI via a TCP capture socket (`emux-capture`). The capture loop idles when nothing repaints, keeping battery drain minimal.
- **Root-aware bootstrap** — on first launch `BootstrapManager` downloads & extracts an Alpine minirootfs (~3 MB) into app-private storage. If Magisk/root is detected, `RootManager` upgrades the session to real chroot/Xorg mode; otherwise it stays in the unprivileged proot-free path (static busybox + patched ELF loader style).
- **Tasker/API integration** — `EmuXExecuteService` exposes a permission-guarded intent so other apps can run commands inside the Linux environment.

## Install / test

```bash
adb install -r emux-debug.apk
```

Debug-signed build; requires Android 7.0+ (minSdk 24). First launch bootstraps the rootfs automatically.

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
    terminal/            TerminalService, TerminalSession, VtTerminalView, ExtraKeysView
    x11/                 DesktopService, DesktopView, X11Client, UsbCaptureActivity
    api/                 EmuXExecuteService (external command API)
  app/src/main/assets/bin/   emux-init.sh, emux-x11, emux-capture
emux-debug.apk           Latest debug build (also attached to GitHub Releases)
```

## Status / caveats

- v0.1.0 debug build — not yet release-signed or R8-minified.
- On-device runtime (bootstrap download, X11 capture latency) validated only on hardware; please report crashes via Issues with `adb logcat | grep -i emux`.
