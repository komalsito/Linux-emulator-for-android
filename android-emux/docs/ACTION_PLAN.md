# EmuX — Handoff & Action Plan (state at v0.1.46)

Read this first in a new session. It condenses ~30 debug rounds against a real
field device (Huawei-family tablet `MRDI-W09`, Android 12/API 31, SELinux
`untrusted_app_27`, **no root**) plus sandbox-verified fixes.

## 0. Token hygiene (do this first)
1. GitHub → Settings → Developer settings → Personal access tokens → **revoke**
   the old PAT (it was pasted into a chat; treat it as compromised).
2. Create a new PAT (scope: `repo`).
3. Update the local remote WITHOUT embedding it in files:
   `git remote set-url origin https://github.com/komalsito/Termux2.git`
   and authenticate via `git config credential.helper store` or per-command
   `https://x-access-token:<PAT>@github.com/...` (never commit it).

## 1. What EmuX is
One APK that runs an Alpine Linux userland + X11 desktop on unrooted Android:
- `core/BootstrapManager.kt`: downloads Alpine minirootfs, extracts, seeds
  configs, stages proot "native bridge", installs distro components via apk.
- `terminal/`: in-house VT100 renderer (`VtTerminalView`), session process
  management (`TerminalSession` + `TtyWrap`), foreground `TerminalService`.
- `x11/`: `DesktopService` (frame socket reader), `DesktopView`, in-distro
  `emux-x11` (Xvfb+WM) and `emux-capture` (xwd→PPM→RGB565→TCP :7001).
- Helper scripts in `app/src/main/assets/bin/` (re-seeded on ENV_VERSION bump).

## 2. Verified-working on the field device (v0.1.45 screenshots)
- Bootstrap: rootfs download/extract, apk inside proot (util-linux, bash,
  desktop stack), marker stamping, cheap re-stamp on upgrades.
- Terminal: real PTY via guest `script(1)` inside proot (`pty=true`), colored
  prompt, echo, command execution, output persists across app pause/resume
  (v0.1.45 pump fix), in-place PTY upgrade for early-born sessions.
- Self-test markers all OK: proot, PTY wrapper, bash, Xvfb, apk, PROOT_PTRACE_OK.

## 3. OPEN ISSUES (next actions)
1. **Desktop frames**: pane shows "waiting for X frames…". Since v0.1.45 the
   pane ALSO paints the live x11/capture session log tail while waiting — get a
   desktop screenshot on v0.1.46 and read the tail (Xvfb log / apk / nc errors).
   Suspects in order: Xvfb shm (retry with -noshmem exists), xwd/netpbm/nc
   availability in guest, capture→host TCP :7001, openbox crash.
2. **First-launch race**: session #1 can start before helper seeding finishes
   ("rootfs launcher missing" line); mitigated by
   `upgradeToGuestPtyIfAvailable()` on bootstrap success — verify it leaves no
   stale Android-sh process behind.
3. **OEM IME quirks**: keep-alive hooks exist (window focus, banner updates);
   watch for regressions on other keyboards.
4. Cosmetics: bootstrap pane hides on success (v0.1.40+); leftover `[emux]`
   warning lines for host-wrapper absence are expected on this ROM (guest PTY
   is the real engine) — consider silencing when `ptyWrapped==true`.

## 4. Device-specific landmines (MRDI-W09 / this ROM class)
- **Raw musl exec dies with SIGSYS (exit 159)**: ROM seccomp kills non-proot
  musl binaries. NEVER rely on host-side loader exec for guest binaries;
  proot+loader is the only engine. (`musl loader/busybox: FAILED` in self-test
  is EXPECTED here.)
- **proot is the Termux build**: compiled-in loader path
  `/data/data/com.termux/files/usr/libexec/proot/loader[32]` and tmp
  `/data/data/com.termux/files/usr/tmp` are unreachable outside Termux →
  ALWAYS export `PROOT_LOADER`, `PROOT_LOADER_32`, `PROOT_TMP_DIR`
  (host-writable dir) for every proot invocation, host-side and in scripts.
- **proot `-k` TAKES AN ARGUMENT** (`--kernel-release`): `-0 -k -r ROOT` makes
  `-k` eat `-r` and ROOT becomes the command → "is not a regular file". Fixed
  in v0.1.44; never reintroduce.
- DNS: seed guest resolv.conf from `ConnectivityManager` live DNS
  (`net.dns*` properties are dead since API 26); IP-literal multi-mirror
  repositories are the DNS-less fallback for apk.

## 5. Architecture invariants (why things are the way they are)
- `targetSdk=28` on purpose: Android 10+ W^X denies exec/mmap-exec of app-data
  for SDK 29+; 28 keeps `untrusted_app_27` exec permission (same model as
  Termux, termux-app#1072). Not Play-distributable by design.
- No NDK: PTY comes from guest util-linux `script(1)` (real pts + TIOCSCTTY)
  launched inside proot by `emux-init.sh`; winsize sync is a file channel
  (`<rootfs>/tmp/emuxsize` → guest `emux-winsize` watcher → `stty -F`).
  Async-command caveat: background jobs of non-interactive shells get
  stdin=/dev/null (POSIX) — pass the tty explicitly (`$(tty)` arg) + /proc
  field-7 fallback.
- Renderer repaints ALL rows every frame (v0.1.43): the old damage-only scheme
  cleared the full canvas but repainted only dirty rows → every feed wiped all
  previous text ("text disappears" history). Do not "optimize" this back
  without implementing true clipped partial invalidation.
- Scrollback pump must live OUTSIDE repeatOnLifecycle (v0.1.45): it was
  cancelled on pause and never re-armed on resume → black screen after
  returning to the app.

## 6. Sandbox build recipe (1 GB RAM box; full AGP assembleDebug OOMs)
```bash
apt-get install -y openjdk-17-jdk-headless wget unzip zip
# SDK
cmdline-tools → sdkmanager "platforms;android-34" "build-tools;34.0.0"
# Kotlin 1.9.24 CLI (matches project), R8 8.5.27 jar (AGP-matching dexer)
wget github JetBrains/kotlin v1.9.24 zip; wget dl.google.com .../r8/8.5.27/r8-8.5.27.jar
# Classpaths (configuration-only Gradle run, memory-capped):
GRADLE_OPTS="-Xmx96m" ./gradlew -I cp.init.gradle :app:printCp :app:printRuntimeCp \
  :app:processDebugResources :app:dataBindingGenBaseClassesDebug :app:mergeDebugJavaResource \
  --no-daemon --max-workers=1 -Dorg.gradle.jvmargs="-Xmx512m -XX:MaxMetaspaceSize=256m"
# (cp.init.gradle registers tasks resolving debugCompileClasspath/debugRuntimeClasspath)
# Extract EVERY runtime AAR's classes.jar AND libs/*.jar (emoji2 ships
# libs/repackaged.jar — missing it = 52 missing classes).
# Compile: kotlinc -jvm-target 17 -cp <compile cp + R.jar> <all .kt> <ActivityMainBinding.java>
#          javac the binding against kotlinc output.
# Dex: java -cp r8.jar com.android.tools.r8.D8 --min-api 24 --lib android.jar \
#        --output dex/ app-kclasses.jar app-jclasses.jar <repackaged.jar> <ALL runtime jars>
# Package: resources-debug.ap_ + classes*.dex + assets/ + merged java resources
#          (META-INF/services!, kotlin/*.kotlin_builtins, *.version, DebugProbesKt.bin)
#          → zipalign -f -p 4 → apksigner sign (debug keystore).
```
Pitfalls checklist (each caused a real crash/bug once):
- Missing `META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory`
  → Dispatchers.Main init crash at onCreate (v0.1.31 lesson).
- Dexing the COMPILE classpath → 622 missing runtime classes → startup
  NoClassDefFoundError (v0.1.32 lesson). Use debugRuntimeClasspath.
- D8 rejects raw class directories here → jar them first.
- Keystore lives outside the repo: if the sandbox resets, the signature
  changes → devices need ONE `adb uninstall com.emux` afterwards. Keep the
  keystore in the repo (e.g. `debug-keystore/` with a README) if this recurs.

## 7. Termux cross-check (approach validation)
- targetSdk 28 / W^X exemption: identical to Termux's long-standing choice.
- proot + loader + libtalloc from Termux packages: same binaries proot-distro
  uses; we additionally override PROOT_LOADER/PROOT_TMP_DIR (Termux paths are
  compiled in; proot-distro runs inside Termux so it never hits this).
- PTY: Termux uses JNI `forkpty` in its native termux.c; EmuX (no NDK) uses
  guest util-linux `script(1)` under proot — functionally equivalent
  (pts + TIOCSCTTY), validated on-device.
- Bootstrap: Termux ships per-arch bootstrap zips; EmuX uses Alpine minirootfs
  + the distro's own apk under proot (= proot-distro's model).
- References: termux/termux-app issue #1072 (W^X), proot-me/proot docs,
  termux/proot-distro README.

## 8. Version log (condensed)
v0.1.31 live-DNS seeding + multi-mirror IP repos + untruncated banners ·
v0.1.32 META-INF services in APK · v0.1.33 runtime-classpath dex ·
v0.1.34/35 PROOT_TMP_DIR · v0.1.36 PROOT_LOADER shipped · v0.1.37 guest PTY +
desktop diag · v0.1.38/39 winsize channel (+async-stdin fix, -noshmem retry) ·
v0.1.40 pane hide + stream dbg · v0.1.41/42 attach marker, tails ·
v0.1.43 RENDER FIX (full repaint) · v0.1.44 proot `-k` removal ·
v0.1.45 pump re-arm on resume + upgrade hook + pane live tails ·
v0.1.46 sudo+apt extras (best effort).

## 9. Field-report protocol (keep using it)
Every failure banner embeds: android/model/targetSdk/selinux, exec(loader),
exec(proot), proot-ptrace, dns(java), resolv.conf, dns-tcp, repo-tcp, and the
apk output tails; terminal prints `[emux] dbg stream/tail/view` lines and the
environment self-test; desktop pane paints x11/capture log tails. ONE screenshot
per symptom is usually sufficient — read these sections first, guess second.
