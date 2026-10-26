# EmuX — build recipe & field-fix log (v0.2.0 → v0.2.6)

Repo: `komalsito/Termux2` (private) · project dir: `android-emux/` · head at time of writing: `ea6f073` (v0.2.6, vc106)

## Rebuild recipe

Toolchain: JDK 17 (AGP 8.5.2 / Kotlin 1.9.24 / Gradle 8.7 wrapper), Android SDK
`platforms;android-34` + `build-tools;34.0.0`. `local.properties` is TRACKED and pins
`sdk.dir=/opt/android-sdk` — install the SDK there or overwrite locally (don't commit).

```bash
cd android-emux
./gradlew assembleDebug --console=plain
# -> app/build/outputs/apk/debug/app-debug.apk
```

Signing: the repo's committed `debug-keystore/emux-debug.jks` (alias `emux`,
store/key pass `emuxdebug`). Cert SHA-256
`5E:3E:F2:54:92:EC:7E:64:80:2F:D3:E5:A4:0B:EC:A0:4F:1F:86:AE:04:72:CB:E4:4A:0A:FE:50:AC:A9:A1:FA`
— identical across all releases, so `adb install -r` never wipes the rootfs.
If a build's cert differs: one `adb uninstall com.emux` is required (wipes distro).

### Low-RAM machines (~1 GiB, no swap)

Plain `assembleDebug` gets natively OOM-killed inside `compileDebugKotlin`
(measured peak ~987 MB of a 1 GiB cgroup). These flags fit:

```bash
export MALLOC_ARENA_MAX=2
./gradlew assembleDebug --no-daemon --no-watch-fs --console=plain \
  -Dorg.gradle.jvmargs="-Xmx384m -XX:MaxMetaspaceSize=256m -XX:CompressedClassSpaceSize=64m -XX:ReservedCodeCacheSize=48m -XX:MaxDirectMemorySize=32m -Xss512k -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Dfile.encoding=UTF-8" \
  -Dorg.gradle.workers.max=1 \
  -Pandroid.aapt2.maxWorkerCount=1 \
  -Pkotlin.compiler.execution.strategy=in-process
```

### Verify before shipping

```bash
$BT/aapt2 dump badging app-debug.apk | grep '^package'      # versionCode/Name
$BT/apksigner verify --print-certs app-debug.apk           # must match cert above
```

## Field-fix log (why each version exists)

- **v0.2.0 / vc100** — repo did not compile: `Sequence.takeLast` does not exist
  (`DesktopView.kt:105`, landed in `dff2aff`); the single Actions run ended
  `conclusion=failure`, which is why the committed APK was stale. Fixed
  `lineSequence()` → `lines()`. Workflow deleted in the same commit.
- **v0.2.1 / vc101** — desktop hang: `runApkProcess` read the guest pipe until EOF
  (orphans hold pipes forever) and `withAptLock` was an unbounded `lock()`, so a
  zombie holder wedged every later apt run incl. `desktopPreRepair`. Now: output to
  a cache file, bounded waits (`destroyForcibly` on timeout), `tryLock` with fallback,
  live `progressNote` lines in the pane.
- **v0.2.2 / vc102** — `desktopPreRepair` gated on Xvfb+id only; the capture toolchain
  (xwd/xwdtopnm/pnmtoplainpnm/nc) + WM stayed missing and the in-guest cascade that
  installed them was SIGHUP-killed by respawns mid-configure (half-configured
  journals). Now the FULL `spec.desktopPkgs` stack installs host-side, plus a re-heal
  if an install is interrupted.
- **v0.2.3 / vc103** — pane scroll: frameless drag scrolls the full history (was a
  fixed 16-line window; touches went into a non-existent X).
- **v0.2.4 / vc104** — `xwd: unable to open display ':0'` (cap.err): the tcp X
  handshake is the broken path on the field ROM; Xvfb attempt 1 now keeps the unix
  socket, and emux-capture preflights DISPLAY spellings with a throwaway grab.
  Terminal: Ctrl+V paste; degraded-mode backspace can no longer erase the prompt.
- **v0.2.5 / vc105** — scroll range collapsed when the pane filled: errHistory
  rotated at 24 notes → cap 400. First display-rotation attempt (pick a free :N).
- **v0.2.6 / vc106** — zombies win: on this ROM the host pid-scan kill no-ops
  (cmdlines hidden) and guest sweeps can't signal foreign proot tracees, so stale
  Xvfb processes hold unix/abstract/tcp sockets forever ("server already running",
  all ladder attempts rc=1, capture rc=137 in respawn churn, xwd grabbing from the
  zombie). Strategy now: WALK AWAY — rotate DN+1 (≤3) when a display's sockets are
  occupied, publish the winner in `/tmp/emux_display_num`; capture and the Android
  control plane read that file; stale `emux_x_up` / `emux_display_num` / `X0..X4`
  socket files deleted at session start.

## Diagnostics cheat-sheet (device side)

| Question | Command (Terminal tab) |
|---|---|
| Full desktop story | `tail -60 /tmp/emux_desktop_diag.log` |
| Why capture died | `tail -5 /tmp/emux_cap.err` |
| Xvfb attempt results | `cat /tmp/emux_attempts` |
| Which display won | `cat /tmp/emux_display_num` |
| Xvfb's own fatal line | `tail -3 /tmp/xvfb.log` (`.1`, `.2` = older attempts) |
| dpkg state | `tail -6 /var/log/dpkg.log` |
| Host-side log | `adb pull /data/data/com.emux/files/logs/desktop.log` |

Pane behaviour: frameless = drag to scroll history (400 notes); green = healthy
per `DiagColors`; with frames = tap/drag/long-press-right/double-tap-zoom/2-finger-pan.

## CI

None (workflow deleted in v0.2.0 commit; Actions budget exhausted). The current APK
lives at `emux-debug.apk` on `main` and in GitHub Releases `v0.1.x` (stale assets).
