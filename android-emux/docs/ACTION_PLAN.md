# EmuX — Handoff & Action Plan (state at v0.1.47)

Read this first in a new session. It condenses ~30 debug rounds against a real
field device (Huawei-family tablet `MRDI-W09`, Android 12/API 31, SELinux
`untrusted_app_27`, **no root**) plus sandbox-verified fixes.

## 0. Token hygiene (do this first)
1. GitHub → Settings → Developer settings → Personal access tokens → **revoke**
   the old PAT (a PAT was again pasted into chat during the v0.1.47 session —
   treat ANY chat-pasted token as compromised and rotate it).
2. Create a new PAT (scope: `repo`).
3. Update the local remote WITHOUT embedding it in files:
   `git remote set-url origin https://github.com/komalsito/Termux2.git`
   and authenticate via `git config credential.helper store` or per-command
   `https://x-access-token:<PAT>@github.com/...` (never commit it).

## 1. What EmuX is
One APK that runs a Linux userland + built-in X11 desktop on unrooted Android.
Since v0.1.47 it is MULTI-DISTRO: Alpine (default) + Debian 12 + Ubuntu 24.04
+ Arch Linux ARM + Fedora 40, installable side-by-side (see §1.5).
- `core/BootstrapManager.kt`: downloads the selected distro rootfs, extracts
  (gz/xz), seeds configs, stages proot "native bridge", installs components
  via the distro package manager (apk/apt/pacman/dnf) inside proot.
- `core/DistroCatalog.kt`: the distro table — pinned image URLs + SHA256s
  (proot-distro release tarballs; Ubuntu also Canonical cloud-images),
  per-distro package lists, apt mirror tables, switch-confirmation pref.
- `terminal/`: in-house VT100 renderer (`VtTerminalView`), session process
  management (`TerminalSession` + `TtyWrap`), foreground `TerminalService`.
- `x11/`: `DesktopService` (frame socket reader), `DesktopView`, in-distro
  `emux-x11` (Xvfb+WM) and `emux-capture` (xwd→PPM→RGB565→TCP :7001).
- Helper scripts in `app/src/main/assets/bin/` (re-seeded on ENV_VERSION bump).

## 1.5 Multi-distro architecture (v0.1.47) — invariants
- Layout: `files/distros/<id>/` per distro; `FsLayout.linuxRoot(ctx)` resolves
  the ACTIVE id (Settings pref "distro") — every session/desktop/bootstrap
  call site goes through it, so switching distros needs zero call-site edits.
  The legacy `files/linuxroot` tree is MIGRATED (cheap rename) to
  `distros/alpine` on first access; if the rename ever fails, linuxRoot()
  falls back to the legacy path while alpine is active.
- Marker: `.env_version` content is "<ENV_VERSION>:<distroId>" (e.g. "21:alpine");
  `isBootstrapped` = marker names the ACTIVE distro + its etc/ exists. A bare
  number (≤ v0.1.46) counts as alpine but is stale (ENV bumped 20→21) → lands
  in the cheap re-stamp path (no re-download). ENV_VERSION=21.
- SWITCH SEMANTICS: distros coexist; installing one never deletes another.
  Switching active distro with the target not on disk shows an explicit
  "Install & switch" pane. Guards (keep all three):
   1. `EmuXApplication` auto-schedules bootstrap ONLY when NO distro is
      installed (never auto-downloads a switch);
   2. BootstrapManager refuses a fresh download while another distro is
      installed unless `DistroCatalog.switchConfirmed(ctx)` (pref
      "distro_switch_ok_for" == active id; stamped by the Install button);
   3. MainActivity restarts sessions whose `launchedRootfs` != new active
      rootfs after success (in-place restart, same scrollback) and stops a
      running desktop.
- Downloads: per-distro ordered candidates; SHA256 verified when pinned
  (all proot-distro images are; Ubuntu cloud-image fallback is magic-bytes
  only). `.tar.xz` extraction ladder: host `xz|unxz|tar -J` → pure-Java
  `org.tukaani:xz` (new dependency, 0BSD). The Java tar parser now copies
  type-'1' HARDLINKS from earlier entries (Debian/Ubuntu images contain them;
  Alpine minirootfs barely does — the old empty-file behavior would corrupt
  merged-/usr trees).
- Component installs: `installDistroExtras` dispatches on `PkgManager` —
  APK (unchanged v0.1.29-46 ladder), APT (`installAptExtras`: policy-rc.d
  exit 101 + APT::Sandbox::User=root, on-disk success probes, TUNA/AliYun/USTC
  deb822 rotation, IP-literal last resort), PACMAN/DNF (`installGenericPm*`:
  image-shipped repos; pacman `-Sy --noconfirm --needed`, dnf `-y
  --skip-broken install || -y install`). pd images preinstall bash+util-linux,
  so `shellOk()` passes OFFLINE — apt/pacman/dnf run only for real gaps.
- Completeness probes per family (`FsLayout.hasCompleteRootfsAt`): alpine =
  busybox/sh + alpine-release + musl loader (lstat, never follow); apt =
  bash/dash + dpkg + etc/debian_version; pacman = bash + usr/bin/pacman +
  pacman.conf; dnf = bash + usr/bin/dnf + etc/fedora-release. `installedDistroIds`
  trusts the DIRECTORY NAME first (Debian/Ubuntu trees pass each other's shape
  probe — sniffing alone mislabels ubuntu/ as debian).
- Guest scripts are pm-aware (`command -v apt-get/pacman/dnf` cascades in
  emux-x11/emux-capture auto-install; emux-init.sh Path-2 globs glibc loaders
  + multiarch lib dirs — still best-effort: proot stays the engine).
- Catalog refresh procedure: pull TARBALL_URL/TARBALL_SHA256 from
  proot-distro `vX.Y.Z/distro-plugins/<distro>.sh` (raw.githubusercontent),
  HEAD-verify, update DistroCatalog. Never transcribe hashes by hand.
- Adding a distro = one DistroSpec + a hasCompleteRootfsAt probe + (new PM
  family only) a runPmProot payload + guest-script cascade branch + UI arrays.

## 2. Verified-working on the field device (v0.1.45 screenshots)
- Bootstrap: rootfs download/extract, apk inside proot (util-linux, bash,
  desktop stack), marker stamping, cheap re-stamp on upgrades.
- Terminal: real PTY via guest `script(1)` inside proot (`pty=true`), colored
  prompt, echo, command execution, output persists across app pause/resume
  (v0.1.45 pump fix), in-place PTY upgrade for early-born sessions.
- Self-test markers all OK: proot, PTY wrapper, bash, Xvfb, apk, PROOT_PTRACE_OK.

## 3. OPEN ISSUES (next actions)
0. **Field-verify the new installers (v0.1.47/48)**: extraction is now
   sandbox-proven for all five images (v0.1.48), but apt (Debian/Ubuntu),
   pacman (Arch), dnf (Fedora) runs under proot are NOT yet device-proven; the failure banners embed the apt/pacman/dnf output tails +
   device probe. Watch: dnf metadata refresh time on slow links, pacman
   keyring/CheckSpace noise, Ubuntu noble armhf (pd image only — Canonical
   cloud-images dropped armhf), NO Ubuntu image for 32-bit x86 (error is
   explicit). Debian/Ubuntu/Arch/Fedora desktop components install was never
   field-tested either (package names verified against distro repos only).
1. **Desktop frames**: pane shows "waiting for X frames…". v0.1.52 removed
   the two proven blockers below; if frames STILL never arrive on the field
   tablet with v0.1.52+, read the pane's live x11/capture tails (Xvfb shm,
   capture→host TCP) as before.
   a) EADDRINUSE (field-verified v0.1.51): openFrameListener() set
      SO_REUSEADDR AFTER the binding constructor — a TIME_WAIT socket from
      the previous desktop visit made every re-bind fail. Now: unbound
      ServerSocket → reuseAddress → bind, plus 8×400 ms retry.
   b) stale guest Xvfb: a leftover Xvfb from an earlier session holds :0 and
      tcp/6000; emux-x11 now proc-scans and kills "Xvfb :0"/"Xorg :0" and
      removes /tmp/.X11-unix/X0 + lock before starting. Since v0.1.45 the
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

## 6. Sandbox build recipe (1 GB RAM box) — RE-VERIFIED END-TO-END at v0.1.47
Full `./gradlew assembleDebug` OOMs at :app:compileDebugKotlin (Kotlin daemon +
gradle JVM together exceed ~1 GB). The manual pipeline below built the shipped
v0.1.47 APK from a FRESH sandbox in ~25 min total (most of it downloads):
```bash
# 0) tooling (sandbox starts bare: no java/wget/zip/xz)
apt-get update && apt-get install -y openjdk-17-jdk-headless wget unzip zip xz-utils

# 1) Android SDK (local.properties already points at /opt/android-sdk)
wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
unzip → /opt/android-sdk/cmdline-tools/latest
yes | sdkmanager --sdk_root=/opt/android-sdk --licenses
sdkmanager --sdk_root=/opt/android-sdk "platforms;android-34" "build-tools;34.0.0" "platform-tools"

# 2) compilers
wget github.com/JetBrains/kotlin/releases/download/v1.9.24/kotlin-compiler-1.9.24.zip → /opt/bt/kotlinc
wget dl.google.com/android/maven2/com/android/tools/r8/8.5.27/r8-8.5.27.jar → /opt/bt/r8.jar

# 3) warm gradle + print classpaths + run resource tasks (memory-capped, NO compile)
#    (cp.init.gradle in repo root registers :app:printCp / :app:printRuntimeCp)
JAVA_HOME=… GRADLE_USER_HOME=/opt/gradle-home GRADLE_OPTS="-Xmx96m" \
  ./gradlew -I cp.init.gradle :app:printCp :app:printRuntimeCp \
  :app:processDebugResources :app:dataBindingGenBaseClassesDebug \
  --no-daemon --max-workers=1 -Dorg.gradle.jvmargs="-Xmx512m -XX:MaxMetaspaceSize=256m"
#    extract: grep -o "CP_START:.*:CP_END" | sed → cp.txt ; same RCP_START → rcp.txt

# 4) AARs are RAW on both classpaths — extract classes.jar + libs/*.jar from
#    EVERY .aar entry (emoji2 libs/repackaged.jar lesson still applies)

# 5) Kotlin: kotlinc -jvm-target 17 -cp "<cp jars + extracted AAR jars + android.jar + R.jar>"
#    -d kclasses $(find app/src/main/java -name '*.kt') + ActivityMainBinding.java (for resolution)
JAVA_OPTS="-Xmx620m -XX:MaxMetaspaceSize=224m"   # fits the 1 GB box

# 6) javac the generated binding against kclasses (COLON-joined cp!)

# 7) Dex: java -Xmx512m -cp r8.jar com.android.tools.r8.D8 --min-api 24 \
#      --lib android.jar --output dex/ app-kclasses.jar app-jclasses.jar R.jar \
#      <ALL runtime jars: extracted-AAR classes+libs + every plain jar of debugRuntimeClasspath>

# 8) Package: unzip resources-debug.ap_ → + classes*.dex + assets/ (copy from
#    app/src/main/assets — includes bin/ AND proot/) + merged_java_res base.jar
#    (META-INF/services!, kotlin builtins, *.version, DebugProbesKt.bin; skip its MANIFEST)
#    → zip -r (resources.arsc MUST be STORED: zip -0 it separately)
#    → zipalign -f -p 4 → apksigner sign --ks debug-keystore/emux-debug.jks
#      --ks-pass pass:emuxdebug --ks-key-alias emux
# 9) Verify: aapt2 dump badging (versionCode/Name), apksigner verify, python
#    zipfile asset/arsc parity vs previous APK, grep dex for new class names.
```
Pitfalls checklist (each caused a real crash/bug once):
- Missing `META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory`
  → Dispatchers.Main init crash at onCreate (v0.1.31 lesson).
- Dexing the COMPILE classpath → 622 missing runtime classes → startup
  NoClassDefFoundError (v0.1.32 lesson). Use debugRuntimeClasspath.
- D8 rejects raw class directories here → jar them first.
- KOTLIN BLOCK COMMENTS NEST: `files/distros/*` inside a KDoc opens a nested
  comment that swallows the rest of the file ("unclosed comment" + phantom
  "missing }" errors pointing elsewhere). Never write `/*` inside comments.
- javac/kotlinc -cp is COLON-separated; a space-joined jar list silently
  resolves nothing ("cannot find symbol androidx.annotation.NonNull").
- `grep CP_START` also matches `RCP_START` — extract the two marker lines with
  distinct patterns or the classpath is garbage (v0.1.47 lesson).
- `cmd | head` swallows the exit status → `set -e` does NOT stop the pipeline
  on javac failure; the empty jar then sails into D8 (check class counts!).
- resources.arsc must end up STORED (zip -0) like AGP packages it.
- v0.1.48 FIELD BUG (Ubuntu install died before helper scripts; empty
  self-test + "rootfs launcher missing"): THREE parser gaps, each verified
  against system-tar ground truth for ALL FIVE images in the sandbox:
   1. proot-distro tarballs wrap the tree in ONE top-level dir
      (ubuntu-noble-aarch64/ …) → dest/etc never appeared → post-extract
      check() aborted. Fix: unwrapSingleTopDir() after extraction.
   2. pd images open with PAX extended headers ('x'); the old unknown-type
      branch skipped payload WITHOUT the 512-byte padding → stream desync at
      entry #1 → silent EMPTY extraction. Fix: every branch consumes
      payload+pad; 'x'/'g'/'L'/'K' handled; PAX path/linkpath/size honored.
   3. Alpine hardlink linknames are ABSOLUTE (/bin/busybox); File(dest,"/abs")
      ignores dest → forward+absolute hardlinks vanished (busybox applets).
      Fix: normalize linknames rootfs-relative + defer unresolved hardlinks
      to a post-pass.
  Verification method (repeat before shipping extractor changes): compile the
  extractor verbatim into a JVM harness (needs an EmuXLog stub + xz jar),
  extract each catalog image, diff name/type sets vs `tar -tvf` (device nodes
  excluded, wrapper prefix stripped), then run the FsLayout probe paths.
- v0.1.49 FIELD BUG #2 (same screenshot): emux-init.sh / emux-init-probe.sh
  distro-fallback scan ran AFTER the legacy `ROOT=$HOME/linuxroot` assignment
  had overwritten ROOT, so it scanned files/* instead of files/distros/* —
  with a pending switch the terminal never fell back to a co-installed distro
  and every message named the dead legacy path ("rootfs missing at
  …/files/linuxroot", PROBE_NOROOTFS) even though a distro WAS on disk.
  Fix: scan "$(dirname "$HOME")/distros"/* FIRST, before legacy fallbacks.
- v0.1.51 FIELD BUG #3 (banner "v0.1.50 · … incomplete rootfs (tar: exec xz…)"):
  the end-of-extraction completeness check inside extractTarXz/extractTarGz
  demanded dest/etc IMMEDIATELY — but wrapped images only get etc/ after the
  CALLER's unwrapSingleTopDir(), so a perfect extraction was rejected on
  device (ROMs without an xz binary = the pure-Java path = always wrapped).
  The v0.1.48 sandbox harness missed it because it called parser+unwrap
  directly, never the real sequence. Fix: wrapper-aware rootfsLooksExtracted()
  inside the extractors; harness v6 now runs extractArchive(allowHostTools=
  false) → unwrap → strict etc gate → probes for ALL FIVE images (all green).
  Lesson: harnesses must execute the CALLER sequence, not just the leaf fn.
- v0.1.50: (a) bootstrap failure banners now embed the RUNNING app version
  ("v0.1.50 · RuntimeException: …") — the 0.1.48/49 field round was actually a
  device still executing 0.1.47 (banner string provenance + the process-scoped
  BootstrapReporter LiveData prove it), so ALWAYS read the version prefix
  before diagnosing; the top bar also shows v<name> · root status.
  (b) LIVE distro switching: SharedPreferences listener in MainActivity —
  selecting an installed distro re-stamps + restarts bound sessions + hides
  the pane immediately; selecting an uninstalled one shows the Install pane
  at once. (Listener MUST stay a field: SharedPreferences keeps listeners by
  weak reference.)
- Keystore: NOW COMMITTED (`android-emux/debug-keystore/emux-debug.jks`,
  pass `emuxdebug`, alias `emux`) — signatures are stable across sandboxes;
  no more uninstall churn after the one-time pre-0.1.47 migration.

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
v0.1.53 dpkg/apt self-repair field round: link()->linkat() LD_PRELOAD shim
(assets/linkfix, termux/proot#377 class of kernel bugs), emux-apt-repair
helper + app-start/Settings repair pass, guest-side $(tty) expansion fix in
emux-init.sh, coreutils self-heal extras, ENV_VERSION 22 ·
v0.1.31 live-DNS seeding + multi-mirror IP repos + untruncated banners ·
v0.1.32 META-INF services in APK · v0.1.33 runtime-classpath dex ·
v0.1.34/35 PROOT_TMP_DIR · v0.1.36 PROOT_LOADER shipped · v0.1.37 guest PTY +
desktop diag · v0.1.38/39 winsize channel (+async-stdin fix, -noshmem retry) ·
v0.1.40 pane hide + stream dbg · v0.1.41/42 attach marker, tails ·
v0.1.43 RENDER FIX (full repaint) · v0.1.44 proot `-k` removal ·
v0.1.45 pump re-arm on resume + upgrade hook + pane live tails ·
v0.1.46 sudo+apt extras (best effort) ·
v0.1.47 MULTI-DISTRO (Debian 12 / Ubuntu 24.04 / Arch ARM / Fedora 40 via
pinned proot-distro images; coexisting files/distros/<id>, explicit switch
guard, apt/pacman/dnf install ladders, tar.xz + hardlink-aware extraction,
SHA256 verification, committed debug keystore, ENV_VERSION 21) ·
v0.1.48 extraction correctness: wrapper-dir unwrap + PAX/GNU-long-header
handling + absolute/forward hardlink resolution (field bug: v0.1.47 Ubuntu
install aborted post-extract); all five images verified byte-faithful vs
system tar in sandbox · v0.1.49 launcher/probe distro-fallback scan order
(scan files/distros before legacy linuxroot fallbacks) · v0.1.50 version-
tagged failure banners + live distro switching (pref listener) · v0.1.51
wrapper-aware post-extract completeness check (the last field blocker for
pd-image installs; re-verified end-to-end for all five images) · v0.1.52
desktop EADDRINUSE fix (SO_REUSEADDR pre-bind + retry; stale-Xvfb kill in
emux-x11) + apt self-heal (`dpkg --configure -a` before every apt run —
field: a killed mid-install apt run left dpkg journal dirty, "apt tells
me to use dpkg").

## 9. Field-report protocol (keep using it)
Every failure banner embeds: android/model/targetSdk/selinux, exec(loader),
exec(proot), proot-ptrace, dns(java), resolv.conf, dns-tcp, repo-tcp, and the
apk output tails; terminal prints `[emux] dbg stream/tail/view` lines and the
environment self-test; desktop pane paints x11/capture log tails. ONE screenshot
per symptom is usually sufficient — read these sections first, guess second.

## 7. v0.1.53 field round — Ubuntu 24.04 arm64: broken dpkg = black desktop

Field screenshots (v0.1.52 build, Ubuntu noble, tuna mirror) showed four
symptoms that are ONE causal chain:

1. `/system/bin/sh: $ROOT/usr/bin/tty: No such file or directory` at every
   session start — `emux-init.sh` expanded `$(tty)` on the ANDROID host
   (double-quoted payload); the host session PATH prepends the rootfs dirs,
   so the host exec'd the guest ARM ELF (execve ENOENT = missing interp) and
   the winsize watcher got an empty tty arg. Fixed: `\$(tty)` → guest dash
   expands it inside proot (its stdin IS the pts), plus an existence guard.
2. `bash: groups: command not found` + missing `script(1)` (the "no PTY
   wrapper" banner) — coreutils/util-linux half-installed: a killed dpkg
   transaction (or killed extraction) removed files nothing reinstalled.
   Fixed: `coreutils` joins Debian/Ubuntu extraPkgs; emux-apt-repair
   reinstalls coreutils/util-linux/bash.
3. `E: dpkg was interrupted…` then
   `dpkg: error: error creating new backup file '/var/lib/dpkg/status-old':
   Permission denied` — two causes: (a) dirty journal (var/lib/dpkg/updates/*)
   from the killed run; (b) dpkg's db backup is unlink+**link(2)**, and under
   the bundled proot several Android kernels answer the legacy link() with a
   bogus errno (EEXIST on 3.4 per termux/proot#377, EACCES here) while
   **linkat(2)** translates fine → EVERY apt/dpkg run dies forever.
   Fixed: `tools/linkfix/` builds a freestanding LD_PRELOAD shim
   (link→linkat, raw syscalls, no DT_NEEDED → glibc AND musl guests) for all
   four ABIs; BootstrapManager installs it to
   `<rootfs>/usr/local/lib/emux/libemux-linkfix.so` + `/etc/ld.so.preload`;
   repair drops a stale status-old and quarantines an unrecoverable journal
   (`updates.emux-broken/`) before retrying `dpkg --configure -a`.
4. Desktop pane `waiting for X frames… / X server exited (code 1)` with apt
   `Hit:` lines — emux-x11's apt self-install of Xvfb died on (3). Fixed by
   calling emux-apt-repair in the cascade; the startup repair pass below
   heals already-broken devices without a reinstall.

New runtime hygiene: `BootstrapManager.startupRepairAsync()` — once per
process start (daemon thread; Settings → "Repair package manager" forces it)
it no-ops unless the journal is dirty / shim / stamp missing, then runs the
in-guest repair inside proot. Bootstrap never re-runs on a stamped rootfs,
so without this pass a broken dpkg survived forever (the field state).
ENV_VERSION 21→22 re-seeds helpers (emux-apt-repair) on upgrade.

## 10. v0.1.54 field round — terminal scrollback + desktop lifecycle

1. TERMINAL COULD NOT SCROLL (user report): the renderer kept only the live
   grid; rows scrolled off the top were destroyed. Now VtTerminalView keeps
   a 3000-row scrollback (plain-shell mode only; alt-screen TUIs exempt),
   renders any window of history+grid, and adds gestures: one-finger
   vertical drag = scroll, double-tap = jump to live, ExtraKeys pgup/pgdn =
   page up/down (handled locally, no bytes to the guest). Scrollbar thumb on
   the right edge + a hint line while off the live edge; cursor hidden while
   reading history; offset tracks new output so the page never jumps.
2. DESKTOP "exited (code 1)" THEN EADDRINUSE ON REOPEN (user report):
   chain of leaks - (a) Process.destroy() killed only the wrapper shell; the
   proot tracer+guests are DETACHED by the kernel on tracer death, so Xvfb /
   emux-capture survived session stop AND app death holding tcp/6000, the
   :7001 capture connection and /tmp/.X11-unix/X0; (b) the accepted :7001
   socket was never closed on stop (coroutine cancel does not unblock a
   parked read) and a live same-tuple socket defeats SO_REUSEADDR -> the
   next bind died EADDRINUSE; (c) a failed desktop start leaked listener +
   sessions; (d) MainActivity only started the desktop from IDLE, so one
   failed attempt (e.g. apt lock raced with the v0.1.53 startup repair)
   left the pane on a stale error forever.
   Fixes: TerminalSession.destroyTree() (/proc walk, SIGKILL descendants),
   DesktopService.killDesktopGuests() one-shot proot pass (Xvfb/Xorg/
   emux-capture/xwd + X locks) on start/stop/reset, activeSock handle
   closed from stop/reset/failure paths, full reset in the start-failure
   catch, ERROR state retries on tab reselect, emux-x11 stale-kill extended
   to the capture family + apt install retries (4x5s) for dpkg-lock races,
   BootstrapManager aptLock serializes in-process proot apt runs.

## 11. v0.1.55 field round — pty=false staircase, mojibake, missing base pkgs, rotating desktop errors

Field screenshot (v0.1.54, Ubuntu): `attached #1 pty=false` + staircased
banners + `â` mojibake + self-test `tr: No such file` + `Xvfb: missing`.
Root causes & fixes:

1. RAW-PIPE STAIRCASE + NO ECHO: the guest-PTY upgrade never fired on
   seccomp-strict ROMs - MainActivity only upgraded when
   TtyWrap.available() (HOST-side loader exec, which these ROMs kill with
   SIGSYS). guestPty (script inside proot) was never considered post-start.
   Fix: explicit guest-PTY upgrade path (canGuestPty() ->
   upgradeToGuestPtyIfAvailable()), and the renderer emulates ONLCR
   (LF also CR) while ptyWrapped==false so pipe mode no longer staircases.
   Attach banner now prints WHY pty is missing (proot/script/cmd).
2. MOJIBAKE: VtTerminalView fed bytes straight into the VT parser (no UTF-8
   decoding) - every multibyte glyph rendered as latin-1 garbage. Fix:
   proper UTF-8 decoder in feed() with U+FFFD fallback.
3. SELF-TEST NOISE: emux-init-probe.sh ran bare `tr` - resolved through the
   rootfs-first host PATH to the GUEST ELF (execve ENOENT), same class as
   the v0.1.53 $(tty) bug. Pinned to /system/bin/tr.
4. BASE/DESKTOP PACKAGES STILL MISSING (tr, script, Xvfb): those installs
   only ever ran inside bootstrap, which never re-runs on a stamped rootfs;
   the v0.1.53 repair payload healed dpkg but installed nothing. Fix:
   startupRepairAsync now probes host-side for util-linux/coreutils/bash/
   Xvfb gaps and installs exactly the missing sets via runAptProot (respect
   the desktop_components pref) - heals the field device on next launch.
5. ROTATING DESKTOP ERRORS UNCAPTURABLE: pane overwrote errorText on every
   transition. Fix: DesktopService.setError() keeps a 5-entry timestamped
   ring shown in the pane AND appended to files/logs/desktop.log.

## 12. v0.1.56 field round — apt-list ANR, stale in-rootfs helpers

1. ANR ("app not responding") during `apt list`: feed() parsed every chunk
   and invalidated ON THE UI THREAD; a multi-thousand-line burst saturated
   the main looper (parse + full repaint per chunk) until input dispatch
   timed out. Fix: feed() enqueues; a dedicated parser thread mutates the
   model under a lock (burst drained per lock hold) and the UI repaints at a
   30 fps coalesced cap. All UI-thread model mutators (resize, reset,
   scroll) now take the same lock.
2. Desktop still "E: dpkg was interrupted / unable to locate packages":
   helper scripts were re-seeded ONLY inside bootstrap passes, which never
   run on a stamped rootfs - updated devices kept executing their OLD
   in-rootfs emux-x11 (pre-repair, pre-retry) forever. Fix:
   reseedHelpersIfNeeded() at app start (marker ENV stamp < ENV_VERSION ->
   stageHelperAssets + installHelperScripts + re-stamp), and
   DesktopService.startDesktop() now runs BootstrapManager.desktopPreRepair()
   (journal heal + shim) BEFORE launching the X session cascade.

## 13. v0.1.57 field round — post-Install apply, "waiting for X frames" with Xvfb running

1. "After Install I must close and reopen the app": sessions born before
   bootstrap finishes stay on raw pipes with the pre-install env; the
   session-list collector never re-fires on a finished bootstrap (the list
   does not change), so nothing upgraded them until the next process start.
   The Bootstrapped/pct==100 branch now upgrades every live session to the
   guest PTY in place and refocuses the IME.
2. Desktop stuck at "waiting for X frames" with Xvfb actually running: the
   emux-x11 cascade installed ONLY the X server + WM; the capture pipeline
   needs xwd (x11-apps), pnmtoplainpnm (netpbm) and nc (netcat-openbsd). On
   devices whose bootstrap desktop install never ran, emux-capture found no
   tools and its own fallback apt run died on the dpkg state (bare
   configure -a). Cascade list now includes the capture toolchain;
   emux-capture fallbacks run emux-apt-repair first; the startup gap probe
   treats missing capture tools as a desktop gap; and a dead capture
   session now paints its exit code + log tail on the pane (it used to be
   silent - only the X session had a death watcher).
3. WM chooser clarification (summary text): Openbox ships with the desktop
   components; Xfce4/LXDE/i3 must be apt-installed inside the guest first;
   every choice falls back openbox -> twm/icewm -> xterm, so frames flow
   regardless of WM availability.

## 13. v0.1.58 field round — the apt-collision desktop failure

Pane (v0.1.57): "E: dpkg was interrupted, you must manually run
'dpkg --configure -a' ... | emux-x11: apt install failed (lock or mirror?) -
retry", then "desktop did not start in 25s", on EVERY WM choice.

Root cause: apt's "dpkg was interrupted" check fires whenever
/var/lib/dpkg/updates/ is non-empty - which is ALSO true while another dpkg
transaction is legitimately mid-flight. The v0.1.55 app-start gap install of
the desktop stack (xvfb+netpbm+netcat+..., minutes on slow links) was still
running when the user opened the Desktop tab; desktopPreRepair saw a
"dirty" (in-use) journal, and the emux-x11 cascade's 4x5s retries raced the
multi-minute install and always lost -> no Xvfb -> no frames.

Fixes:
- aptLock is now a ReentrantLock; desktopPreRepair tryLock(90s) so the
  desktop start waits for the in-process gap install instead of racing it.
- emux-x11 cascade: apt_busy() proc scan - WAIT (12x10s) while a foreign
  apt/dpkg runs; heal_dpkg() (status-old drop + journal quarantine) between
  retries only when nothing is running; emux-apt-repair guarded by apt_busy.
- emux-capture: same foreign-apt wait (30x5s) before its fallback installs.
- Settings WM summary now explains Openbox is the preinstalled session and
  Xfce4/LXDE/i3 need their packages installed inside the distro first.
ENV_VERSION 26, versionCode 58.

## 14. v0.1.59 field round — "input disabled: X server did not come up within 25s"

Verified in sandbox first: Xvfb 21.1.x (same generation as Ubuntu noble)
DOES honor `-listen tcp` (TCP connect to 6000+display OK), so the option was
not the culprit - TIMING was: when the desktop cascade has to install Xvfb
first (minutes on slow links), the control plane's single 25 s attempt plus
single 30 s background retry both expire while apt is still running, and
touch input then stays dead for the whole session even after X comes up.
emux-capture's 30 s wait for /tmp/.X11-unix/X0 expired the same way, leaving
the pane at "waiting for X frames" with a dead capture.

Fixes:
- connectControlPlane(timeout) + an endless 10 s-cadence background retry
  until connected or desktop stop; transient pane wording
  ("touch input: X TCP not up yet - retrying in background"), cleared on
  success.
- capture respawn watcher: while frameId==0 and <3 respawns, a dead
  emux-capture is restarted automatically.
- emux-capture X0 wait 30 s -> 180 s; emux-x11 foreign-apt wait 12 -> 30
  rounds (5 min) for very slow first-run installs.
ENV_VERSION 27, versionCode 59.

## 15. v0.1.60 field round — the two REAL desktop dependency bugs (sandbox-reproduced)

Reproduced end-to-end in the sandbox with the PINNED Ubuntu noble image
(sha-verified) under proot, running the exact cascade list:

1. `E: Unable to locate package xkbcomp` - on Debian/Ubuntu the package is
   **x11-xkb-utils**; apt aborts the WHOLE transaction on one bad name, so
   NOTHING of the desktop stack ever installed on apt-family devices
   (field: "not finding dependencies", "server did not come up", rotating
   pane errors). The bad name was in BOTH emux-x11's cascade and
   DistroCatalog's debian/ubuntu desktopPkgs since v0.1.47. Fixed lists:
   xvfb x11-xkb-utils xkb-data xfonts-base x11-xserver-utils x11-apps netpbm
   netcat-openbsd openbox xterm (xkb-data/xfonts-base = Xvfb keymap+fonts;
   verified Xvfb then starts, X0 + tcp/6000 up).
2. emux-capture piped **XWD into pnmtoplainpnm** - netpbm's converter for
   XWD is `xwdtopnm`; without it grab_frame yielded 0 bytes
   ("pamtopnm: bad magic number"), nc never connected and the pane waited
   for frames forever even with a healthy X server. Fixed chain
   xwd | xwdtopnm | pnmtoplainpnm, verified end-to-end: host listener
   received the EMUX stream header (720x1280) + full RGB565 frames.
   Capture deps added to the apk/pacman/dnf cascade lists too.
ENV_VERSION 28, versionCode 60.

## 16. v0.1.61 field round — first-pass postinst failure under proot + the v0.1.59 DesktopService edits that never shipped

1. SANDBOX-REPRODUCED (fresh extract of the pinned noble image, exact gap
   payload): the FIRST apt pass dies at "Setting up libpaper1" - its postinst
   does `echo $RET > /etc/papersize.dpkg-inst` then ucf stats it, and under
   proot the statx translation reports the brand-new file as missing
   ("stat: cannot statx '/etc/papersize.dpkg-inst': No such file"), postinst
   exits 1, and the chain libgs10/libspectre1/libimlib2/libobrender/openbox
   stays unpacked (apt exit 100). A plain retry of `dpkg --configure -a`
   configures everything cleanly (verified: 0 stuck packages). The field
   "illegal number" lines are dash errors from the same class of first-pass
   maintainer-script failures under proot. Fix: a settle loop
   (configure -a, up to 2 rounds) appended to runAptProot's payload,
   emux-apt-repair and the emux-x11 cascade.
2. AUDIT FINDING: the v0.1.59 DesktopService edits (control-plane retry
   loop, capture respawn) were NEVER APPLIED - that edit script died on a
   Python syntax error before writing, and the follow-up only covered
   emux-x11/versions. v0.1.59/60 therefore still had the single 25 s +
   single 30 s control-plane attempts: exactly the persistent "server did
   not come up within 25s". Now actually applied: connectControlPlane()
   with a 60 s first attempt + endless 10 s-cadence background retry,
   capture respawn (<=3) while frameId==0. Shipped-artifact grep markers
   are now part of the release checklist (see commit).
ENV_VERSION 29, versionCode 61.

## 17. v0.1.62 field round — reopen-EADDRINUSE root cause + faster X startup

1. EADDRINUSE on close/reopen (FINAL root cause): DesktopService was built
   PER ACTIVITY while the process outlives the activity (foreground terminal
   service) - the old instance's frame listener (:7001), reader thread and
   sessions leaked, so the reopened activity's fresh instance could never
   bind. Now a process-wide singleton (DesktopService.get()); verified in
   the shipped dex ($Companion type + MainActivity call site).
2. "touch input: X TCP not up" after a pile of dpkg/apt output: the cascade
   gated Xvfb behind ONE multi-package transaction (openbox chain included
   the libpaper1-first-pass postinst mire, minutes long). Split: CORE
   (xvfb x11-xkb-utils xkb-data xfonts-base) installs first and X starts as
   soon as it lands; REST (WM + capture toolchain) installs in the
   background and starts the WM when it arrives (marker file prevents a
   double WM). Sandbox-verified end-to-end on the pinned noble image:
   X0 + host tcp/6000 + openbox running.
3. Xvfb mid-session death (shm SIGBUS class): restart once with -noshmem
   instead of ending the session and orphaning the pane.
4. Sandbox re-checks this round: noble Xvfb -listen tcp DOES open tcp/6000
   and the host reaches it (the 21.1.x removal fear was unfounded).
ENV_VERSION 30, versionCode 62.

## 18. v0.1.63 field round — shim delivery belt + device selfcheck

Field v0.1.62 still showed "status-old: Permission denied", i.e. the
link()->linkat() shim was not reaching dpkg on that device even with
/etc/ld.so.preload written. Since remote guessing has burned three rounds,
this release (a) stops relying on the preload file alone - every proot
apt/dpkg run now also sets LD_PRELOAD explicitly (runAptProot +
runRepairProot), (b) normalizes permissions host-side (shim dir/file,
ld.so.preload, var/lib/dpkg + status/status-old/available owner-write), and
(c) ships a GUEST SELFCHECK (APT_SELFCHECK_PAYLOAD, written to
<rootfs>/tmp/emux_selfcheck and echoed to the repair stderr) reporting
SHIM_LOADED/SHIM_NOT_LOADED (via /proc/self/maps), a live link(2) test
(LINK_OK/LINK_FAIL+errno), and owner/mode of the dpkg db dir. DesktopService
paints the condensed selfcheck into the pane error history + desktop.log at
every desktop start, so the next field screenshot identifies the broken leg
in one shot instead of three rounds of inference.
ENV_VERSION 31, versionCode 63.

## 19. v0.1.65 field round — no red flash, diagnostics while waiting, app-side X core install

- Removed the v0.1.25 debugFlashRed() paint on Desktop-tab selection (user
  request: the red flash on every tab open looked broken).
- The waiting pane (CONNECTED but frameId==0) now shows the diagnostic
  HISTORY instead of only the newest transient line, and the frameless
  watcher snapshots xvfb.log + emux_selfcheck + session tails into that
  history at 20/40/60 s - a screenshot taken while waiting now carries the
  evidence (v0.1.64 hid it behind the ERROR state, so the field screenshot
  showed only the transient touch-input line).
- desktopPreRepair() now installs the X-server CORE (xvfb x11-xkb-utils
  xkb-data xfonts-base) from the APP side when missing - with the LD_PRELOAD
  belt, the aptLock and the settle pass - before the X session starts, and
  returns a one-line summary that the pane history shows. The in-guest
  cascade becomes a fallback instead of the only path.
- desktopPreRepair returns String? (bare returns needed nulls - Kotlin).
versionCode 65.

## 20. v0.1.67 — shim v2: copy-fallback hardlink (the device-proof fix)

v0.1.66 field result: status-old EACCES persisted even with the shim loaded
AND LD_PRELOAD set AND the seccomp probe. Root insight: on arm64 glibc
implements link() AS linkat(), so the v0.1.53 link->linkat rewrite was a
no-op there - the broken syscall on this kernel/proot combo is the one
dpkg actually calls, and no amount of rerouting helps.
Shim v2 therefore intercepts link() AND linkat() and, when the real syscall
fails with EACCES/EEXIST/EPERM/ENOSYS, FALLS BACK TO COPYING the file
contents to the new name and reports success. dpkg's status->status-old
backup only needs the contents at the new name; legitimate failures
(missing source, unwritable directory) reproduce through the copy and keep
their errno. Verified in sandbox: passthrough keeps true hardlinks + true
errnos; forced/cross-device cases produce byte-identical copies; the
dpkg-style unlink+link backup loop succeeds repeatedly.
EMUX_LINKFIX_FORCE=1 forces the copy path (self-test hook).
All four ABI assets rebuilt; versionCode 67.

## 21. v0.1.70 — THE field pane finally talked: three stacked bugs, all now fixed

Pane (v0.1.69) printed:
  start: shim=true preload=true journalDirty=false prev=- xvfb=-
  dpkg repair ok=false: CANNOT LINK EXECUTABLE ".../native-bridge/proot":
  library "/usr/local/lib/emux/libemux-linkfix.so" not found: needed by
  main executable

1. HOST-SIDE LD_PRELOAD WITH A GUEST PATH (v0.1.63-0.1.69): the "belt" made
   Android's linker refuse to start proot itself - every repair/apt proot
   run died instantly for seven versions. Removed from both host envs; the
   belt now lives inside the GUEST payload
   ([ -f shim ] && export LD_PRELOAD=shim) where the path exists.
2. SHIM v1 WAS AN ARM64 NO-OP: it overrode link(), but arm64 glibc
   implements link() via linkat() internally - never interposable. v2
   (v0.1.67) intercepts link AND linkat with a copy fallback, but...
3. ...v2 NEVER REACHED THE DEVICE: startupRepair/desktopPreRepair skipped
   installLinkFixShim when "healthy" (old v1 file present). Shim install is
   now unconditional at startup repair (13-67 KB copy).
With all three fixed the chain is: proot starts -> guest dpkg gets the v2
shim via preload file + guest LD_PRELOAD -> linkat EACCES falls back to a
content copy -> status-old backup succeeds -> coreutils/xkb/Xvfb install ->
X starts -> frames stream.
versionCode 70.

## 22. v0.1.75 — the device's bogus errno was ENOENT all along

The v0.1.74 pane dumped the unfiltered selfcheck and finally showed the
real errno: `ln: failed to create hard link ...: No such file or directory`
- proot's dentry poisoning on this kernel answers the hardlink syscall with
ENOENT for paths that exist (EACCES/EEXIST on other devices). The shim's
fallback set did not include ENOENT, so every dpkg backup still failed, the
coreutils install died 30/30 cascade retries, Xvfb stayed absent
(`xvfb.log: <absent - Xvfb never launched>`).
Shim v4 widens the fallback set to EACCES/EEXIST/EPERM/ENOSYS/ENOENT/EXDEV/
EIO/EMLINK/EDQUOT/EINVAL. Safe: the copy re-derives the true error, so a
genuinely missing source or unwritable directory still fails with its real
errno (sandbox-verified: EXDEV auto-copies, ENOENT preserved, same-fs links
stay true hardlinks).
versionCode 75.

## 23. v0.1.76 — root cause closed: the bundled proot itself was too old

The v0.1.75 pane showed shim v4 LOADED and its copy fallback STILL failing
with ENOENT: on this kernel every syscall touching the poisoned path leaf
fails (link, openat, ...). That is exactly termux/proot#377 - the bundled
proot (5.1.107.x, pre-July-2026) probes the FULL path including the
nonexistent leaf during syscall translation, poisoning the dentry on
certain Android kernels; upstream fixed it with parent-only probes (#378)
and the outer-SIGSYS swallow (6b2b7cc). No guest-side shim can undo a
poisoned dentry; the engine had to be upgraded.

- assets/proot/<abi>/{proot,loader,loader32} = Termux proot 5.1.107.98 for
  all four ABIs (verified: bionic interp /system/bin/linker64, PROOT_LOADER
  and PROOT_TMP_DIR overrides present, version string 5.1.107.98). Stale
  loader32 dropped on 32-bit ABIs (new package ships none; code checks
  isFile before setting PROOT_LOADER_32).
- startupRepair re-seeds files/native-bridge on existing devices - without
  that, the old proot would survive every update because bootstrap never
  re-runs on a stamped rootfs (same trap as the helper scripts in v0.1.56).
- libtalloc/libandroid-shmem unchanged; linkfix shim kept as a belt (its
  copy fallback now also covers ENOENT, v0.1.75).
Expectation on the field device: dpkg's status-old backup works natively,
the coreutils/xvfb core install completes, Xvfb starts, frames stream.
versionCode 76.

## 24. v0.1.78 — ghost-package purge everywhere + field-provable shim version

The v0.1.76 pane (this round's screenshot) shows the WHOLE X transaction
(coreutils upgrade + xvfb/xserver-common/x11-xkb-utils/xfonts-base as NEW
packages) aborting inside dpkg - the status-old backup hardlink dies with
ENOENT on this kernel even under proot 5.1.107.98 + shim v4 - so
/usr/bin/Xvfb never lands and the pane waits for frames forever
("xvfb.log: <absent - Xvfb never launched>", "emux-x11: apt install failed -
healing" x30). Shim v5 (v0.1.77) makes the backup link survive; the v0.1.78
review then found two residual gaps that could still black-screen the desktop:

1. The ghost purge (db says "install ok installed", files GONE -> apt-get
   install NO-OPs forever) existed only in the app-start inline payload and
   only for coreutils/util-linux. The desktop cascade calls the in-rootfs
   emux-apt-repair helper, which had NO purge (the twins had drifted), and a
   ghost xvfb/x11-xkb-utils/xfonts-base is exactly what keeps /usr/bin/Xvfb
   absent after an aborted transaction.
2. The pane could not prove which shim generation sits on disk.

Changes:
- Generic GHOST_PURGE table (12 pkg:marker pairs incl. the desktop stack) in
  ALL THREE twins: BootstrapManager.GHOST_PURGE_PAYLOAD, assets/bin/
  emux-apt-repair, and emux-x11 ghost_purge() (run before the CORE loop and
  inside heal_dpkg between cascade retries). Keep the table identical.
- emux-x11: last-resort forced reinstall when the X server binary is still
  absent after the 30-try loop; xkbcomp preflight before launching Xvfb (a
  ghost x11-xkb-utils otherwise kills the server with "Failed to activate
  virtual core keyboard" before any client connects).
- Inline repair payload gained the base-package reinstall + settle pass so it
  matches the asset helper again.
- Shim v6: exported `emux-linkfix-v6` marker; the guest selfcheck greps it out
  of the .so on disk so the next field pane proves the shim generation.
  Behaviour identical to v5 (vanished backup source = success).
- Sandbox regression harness (minimal chroot jail + stub dpkg/apt-get that
  models apt's no-op-on-installed): v0.1.77 emux-x11 on a ghost-xvfb db dies
  with "no X server available" (field pane reproduced); v0.1.78 emux-x11
  purges, installs, launches Xvfb, exits 0; emux-apt-repair purges ghosts and
  reinstalls the base packages; native shim tests: same-fs links stay true
  hardlinks, vanished source => success, copy fallback byte-identical.
versionCode 78, rebuilt emux-debug.apk (same keystore).

### 24.1 Tooling landed with v0.1.78 (reuse, do not re-invent)
- `android-emux/tools/jailtest/` - the chroot-jail regression harness
  (`mkjail.sh` builds a minimal jail with stub dpkg/apt-get that model apt's
  no-op-on-installed semantics; `run.sh` runs the REAL emux-x11 /
  emux-apt-repair unmodified against the ghost-db field state and asserts
  purge/install/launch/selfcheck, plus re-runs v0.1.77 from git to prove the
  harness still reproduces the field failure). 11 assertions, ~6 s, needs
  root for chroot. RUN IT before shipping any edit to the cascade scripts.
- `android-emux/tools/build-apk/manual-build.sh` - the executable form of
  the §6 recipe that produced the v0.1.78 APK (gradle warm-up for classpaths
  + resources, kotlinc outside the gradle JVM, javac binding, D8, package,
  zipalign, apksigner with the committed keystore).

## 25. v0.1.79 — the -noshmem trap + never start Xvfb into a live transaction

The v0.1.78 field pane (two rounds) showed the hardlink bug CLOSED
(SHIM_LOADED / LINK_OK / emux-linkfix-v6) and apt completing - then a NEW
two-part death:
  1. "emux-x11: X0 never appeared with shm - retrying with -noshmem": the
     first Xvfb launch landed INSIDE our own background dpkg transaction
     (dpkg.log: "status half-configured libc-bin" = ld.so.cache gone at that
     instant; even /etc/profile's `id -u` failed: "/bin/sh: 12: id: not
     found"). Xvfb could not exec, the blind 15 s window expired on a server
     that was never merely slow.
  2. "(EE) Unrecognized option: -noshmem": xorg-server 21.1 (Ubuntu noble)
     REMOVED -noshmem from Xvfb - so the v0.1.39 shm retry AND the v0.1.62
     death-restart both died instantly, the session ended, frames never came.

Fixes:
- emux-x11 settles: bounded wait for apt/dpkg to go idle BEFORE the first
  Xvfb start; the REST (WM+capture) background install moved to AFTER the X
  socket is up so no transaction ever swaps libs under a starting server.
- -noshmem is now PROBED once ("Xvfb -noshmem -version"); empty on noble.
  The shm retry uses it only when the probe passes AND the previous log
  actually mentions shm; same for the death-restart.
- start_xvfb() distinguishes DIED (rc 1) from SLOW (rc 2, liveness check via
  kill -0): slow gets +60 s instead of a kill; died gets one same-flag retry
  (or a shm retry when warranted). 45 s first window instead of 15 s.
- /tmp/xvfb.log is rotated to .1 before every relaunch and the failure tail
  prints both - the field pane can now see the FIRST attempt's death reason.
- PATH pinned + id-independent root guard (mid-transaction coreutils swaps
  cannot derail the script anymore).
- DesktopService: bounded x11-session respawn (2x) when it dies pre-frames,
  mirroring the capture respawn - one bad window no longer black-screens
  the whole tab visit.
- jailtest harness: the stub Xvfb now models noble (fatal on -noshmem, logs
  argv) plus a /tmp/xvfb-slow one-shot 20 s start; run.sh asserts no LAUNCH
  ever passes -noshmem and that a slow server is waited out, not killed.
versionCode 79, rebuilt emux-debug.apk (same keystore).

## 26. v0.1.80 - stale-socket-proof launches + a log the user can actually read

v0.1.79 field pane: "(EE) Cannot establish any listening sockets - Make sure
an X server isn't already running". A retry/respawn launched Xvfb onto a
socket still held (or a stale X0 left by a SIGKILLed server): the v0.1.79
retry path removed /tmp/.X0-lock but NOT /tmp/.X11-unix/X0, and a respawned
session could race a half-alive Xvfb from the previous session (proot
tracees are detached, guest-side kills do not reach foreign instances).
Also: the user could not read the pane - notes rotate/scroll away faster
than a screenshot.

- emux-x11: kill_stale_x() (proc sweep + mkdir .X11-unix + drop lock AND X0)
  now runs before EVERY launch attempt (first, shm/same-flag retry, new
  socket-clash 3rd attempt, death-restart); attempts are echoed
  ("Xvfb attempt N: <flags>") so the pane names each try.
- DesktopService: killDesktopGuests() (host-authoritative) before a
  pre-frame x11 respawn; noteInfo history 6 -> 24 lines; xvfb.log tails
  widened (6 lines) PLUS xvfb.log.1 (previous attempt) in watcher and
  ctrl-wait notes; every note mirrored to guest /tmp/emux_desktop_diag.log
  (Terminal tab: cat it - no adb needed) on top of files/logs/desktop.log;
  one pointer note at session start.
- jailtest: stub Xvfb now models EADDRINUSE (fatal if X0 exists at launch,
  logs "saw-stale-socket"); new STALE scenario pre-creates X0+lock and
  asserts the sweep drops them before the first launch. 18 assertions.
versionCode 80, rebuilt emux-debug.apk (same keystore).

### 26.1 Keyboard layout (DIAGNOSED, deliberately NOT changed - user asked)
Symptom: physical Spanish keyboard; terminal only produces English; layout
shortcuts do nothing. Findings:
 1. Terminal path honours ANDROID's per-device physical-keyboard layout
    (VtTerminalView sends event.unicodeChar as UTF-8). The app has no
    layout of its own - "English only" means Android is handing US chars:
    the per-DEVICE layout (Settings > System > Languages & input > Physical
    keyboard > <device> > Keyboard layouts > enable Espanol) is separate
    from the system language list, and ROM-level switch shortcuts (Huawei
    class) often cycle only the on-screen IME, not external layouts.
 2. REAL app-side gap: dead keys. ES layouts emit dead-key events
    (unicodeChar==0) composed via KeyEvent.getDeadChar() on the NEXT event;
    VtTerminalView drops unicodeChar==0 events, so acute/tilde composition
    never reaches the guest - feels like "Spanish not working" even with
    the correct Android layout. Proposed fix (on user go): keep dead-key
    state, compose with getDeadChar(), fall back to literal chars.
 3. Desktop tab has NO keyboard injection wired (DesktopService.
    injectKeycode has zero callers) and guest X runs a single us layout, so
    no shortcut can switch there. Proposed (on go): wire KeyEvent -> X
    keycode -> XTEST fakeKey, and start X with
    `setxkbmap -layout us,es -option grp:alt_shift_toggle` so Alt+Shift
    switches inside the guest.

## 27. v0.1.81 - the orphan Xvfb only the HOST can kill

v0.1.80 field pane, now with the exact X internals visible:
"_XSERVTransSocketUNIXCreateListener: ...SocketCreateListener() failed /
_XSERVTransMakeAllCOTSServerListeners: server already running". An Xvfb
orphaned by an EARLIER desktop session (proot tracees are detached when a
session/app dies) still holds :0 - including the ABSTRACT unix socket, which
deleting /tmp/.X11-unix/X0 does NOT free. Guest-side sweeps cannot kill it:
proot only translates/signals pids of its OWN tracees, so a kill aimed at a
foreign instance's tracee goes nowhere (sandbox-verified: sweep matches the
pid, kill -9 "succeeds", holder lives). Only the app process (same uid, real
host /proc) can.

- DesktopService.killDesktopGuests(): NEW host-side pass FIRST - scan
  /proc/*/cmdline for Xvfb|Xorg|emux-capture|/xwd|emux-x11, verify the pid
  runs under our uid, Process.killProcess it. Runs at desktop start and
  (via the respawn path) before every pre-frame respawn, so an orphan
  self-heals within one respawn cycle.
- emux-x11 kill_stale_x(): TERM then KILL -9 (a half-dead Xvfb in the shm
  SIGBUS path ignores TERM); also fixes a collapsed "$$" self-pid guard
  that shipped in v0.1.80 (compared against a literal "$").
- emux-capture: PATH pin (same mid-transaction /etc/profile lesson; kills
  the "/bin/sh: 12: id: not found" noise in capture sessions).
- jailtest: ORPHAN scenario - a live holder process (fabricated /proc entry
  in the mount-less jail) must be killed by the sweep before X starts;
  wrapper now reaps so kill -0 sees real death. 23 assertions.
Reminder (v0.1.80): the FULL desktop log lives at guest
/tmp/emux_desktop_diag.log - cat it in the Terminal tab when a round fails.
versionCode 81, rebuilt emux-debug.apk (same keystore).

## 28. v0.1.82 - terminal dead-key composition + errno-level socket forensics

The v0.1.81 field pane repeated the generic clash fatal EVEN WITH the host
orphan kill - proving the message cannot distinguish its causes: X prints
"server already running / Cannot establish any listening sockets" for ANY
bind() failure (live orphan holder, proot socket translation, SELinux).
v0.1.82 ships the discrimination plus the keyboard fix the user approved
for the TERMINAL ONLY (desktop deliberately untouched - "should feel as
much as possible like a real laptop"):

- VtTerminalView dead-key composition: a dead-key press ('´' '`' '^' '~'
  '¨') arrives with KeyCharacterMap.COMBINING_ACCENT set; it is held and
  composed with the next character via KeyEvent.getDeadChar(accent, base).
  Non-composing follow-ups emit accent+base literally; control-key
  follow-ups emit the accent then the control sequence. Before this,
  '´'+'e' never produced 'é' on the Spanish physical keyboard.
- emux-x11 probe_sockets(): at start AND on a persistent clash, dumps
  /tmp/.X11-unix contents, the kernel tcp-table rows for :6000 (0x1770),
  and live bind probes via nc (tcp 6001 + unix path; rc 124 = bind ok).
- DesktopService host bind probe BEFORE any cleanup: ServerSocket(6000),
  an abstract LocalServerSocket, and a FILESYSTEM-namespace LocalSocket
  bind into the rootfs tmp. If the host binds all three fine while the
  guest still reports the clash, the cause is guest-internal (proot
  socket translation / SELinux), not an orphan - the two need completely
  different fixes and the pane can now tell them apart.
- jailtest unchanged (23 assertions; OLD scenario needs git history).
versionCode 82, rebuilt emux-debug.apk (same keystore).

## 29. v0.1.83 - in-app terminal keyboard layout + probes pinned in the pane

v0.1.82 field round: the clash fatal repeated AND the discriminating notes
(host bind probe, guest probes, attempt echoes) scrolled out of the pane
window before the user's screenshot - the recurring notes only keep the
latest lines. And the ROM's external-keyboard layout-switch shortcut still
never reaches the app (Spanish physical keyboard, English-only output).

- Settings > "Terminal physical keyboard layout" (Auto / Spanish ISO /
  English): TERMINAL-ONLY positional keycode table (ñ ´ ¨ ¡ ¿ º ç, shifted
  digits/symbols per ES ISO) feeding the v0.1.82 dead-key composition; Ctrl
  combos pass through untouched. Desktop tab unchanged (user constraint:
  the desktop must feel like a real laptop).
- Probe pinning: the host bind probe result AND the guest's last
  /tmp/emux_probes lines are now appended to EVERY recurring "t+Ns no
  frames yet" note and to the ctrl-wait note, so any screenshot carries the
  orphan-vs-guest-b bind discrimination. probe_sockets() also mirrors its
  lines to /tmp/emux_probes for the watcher to read.
versionCode 83, rebuilt emux-debug.apk (same keystore).

## 30. v0.1.84 - tcp-only Xvfb (the guest-bind workaround) + in-app layout shortcut

The v0.1.83 pinned probe delivered its verdict on the field:
"hostbind tcp6000=true abs=true" while the guest Xvfb still dies with the
generic clash fatal => NO orphan holder exists; the bind failure is
GUEST-INTERNAL (proot unix-socket translation / SELinux on the path and
abstract binds). It cannot be fixed from inside the guest, so v0.1.84
routes around it: the first Xvfb attempt is now `-nolisten unix` (tcp
transport only). Every client we run works over tcp: X11Client by design
(tcp 6000), and xwd/xterm/openbox through Xlib's DISPLAY=:0 tcp fallback
when the unix socket is unavailable. x_up() = X0 socket OR tcp 6000 open
(nc -z, else bash /dev/tcp); ALL gates use it (REST install, fail-loudly,
death-restart wait). Attempt 2 falls back to all transports in case
tcp-only mode is ever the problem; attempt 3 keeps the clash sweep.
- In-app layout shortcut: Alt+Shift toggles Terminal layout Auto<->Spanish
  and prints the mode (the ROM shortcut never reaches the app); the
  v0.1.83 Settings entry remains. Desktop tab still untouched.
- Host probe note lists unixpath FIRST (the pane truncates from the end).
- jailtest: 24 assertions (first launch must carry -nolisten unix).
versionCode 84, rebuilt emux-debug.apk (same keystore).

## 31. v0.1.85 - bind() errno extraction (shim v7) + sticky post-mortem

v0.1.84 field round: hostbind unixpath/tcp6000/abstract ALL true while even
the TCP-ONLY Xvfb attempt died instantly => socket bind() fails for guest
(proot tracee) processes on this device in every family, while the app
process binds fine. Neither orphans nor unix-translation alone explains it;
the missing datum is the ERRNO. v0.1.85 extracts it two ways:
- Shim v7 bind selftest (EMUX_LINKFIX_SELFTEST): raw socket()+bind() per
  family (AF_INET :6001, AF_UNIX path, AF_UNIX abstract), results appended
  to /tmp/emux_shimtest as BINDINET_<errno> / BINDUNIXP_<errno> /
  BINDABS_<errno> (0 = ok); the guest selfcheck now cats that file INTO
  /tmp/emux_selfcheck, so the PANE shows the errnos next to SHIM_LOADED.
  (write_result switched to O_APPEND - v6 truncated to the last line.)
- Sticky POST-MORTEM: when pre-frame respawns are exhausted, the pane error
  note packs hostProbe + guest probe file + FIRST lines of xvfb.log.1 and
  xvfb.log (the first attempt's real error) - the error line survives
  scrolling, unlike watcher notes.
Next-round decision table: BINDINET EACCES/EPERM => SELinux on tracee binds
(try proot env/upgrade); EADDRINUSE => hidden holder inside guest netns;
ENOSYS/EINVAL => proot syscall-translation bug (upgrade proot again).
versionCode 85, rebuilt emux-debug.apk (same keystore).

## 32. v0.1.86 - pure-IPv4-tcp Xvfb (the X-specific bind bypass) + full selfcheck in pane

v0.1.85 shim selftest on the field: BINDINET_0 BINDUNIXP_0 BINDABS_0 - raw
guest binds succeed in EVERY family, so proot/SELinux do not block bind()
generally. The failure is X-SPECIFIC, and two candidates fit all data:
(a) Xvfb's dual-stack tcp listener binds AF_INET6 first (host probe and shim
only exercised IPv4; a mangled sockaddr_in6 translation kills the whole tcp
transport), and (b) the unix listener uses /tmp/.X11-unix, which the field
probe showed with bizarre modes (drwx------ under a drwx-w--w- /tmp) and the
shim never tested. Both are sidestepped: attempt 1 is now
`-nolisten unix -nolisten inet6` (pure IPv4 tcp). Every client we run
speaks IPv4 tcp: X11Client dials 127.0.0.1:6000 by design; Xlib clients
(xwd/xterm/openbox) fall back from unix to tcp for DISPLAY=:0. Attempt 2
= tcp all families (unix off), attempt 3 = all transports.
Visibility: probe_sockets now also bind-probes tcp 6000 (X's exact port)
and mirrors all probe lines into /tmp/emux_selfcheck; DesktopService prints
the selfcheck in 340-char chunks (3 notes) so shim errnos AND guest probes
are pane-visible instead of a truncated 350-char prefix.
jailtest: 25 assertions (first attempt must drop unix AND inet6).
versionCode 86, rebuilt emux-debug.apk (same keystore).

## 33. v0.1.87 - Xvfb's own bind() becomes the witness (shim v8 bind logger)

v0.1.86 field: guest probes bind tcp 6000 (rc=0 - nc even accepted the
Android control plane's connect!), tcp 6001 and unix path (124=ok), shim
selftest BIND*_0 - sockets work perfectly in the guest, yet Xvfb still
dies with the generic listen fatal. The only witness left is Xvfb's own
bind() calls, so the shim (LD_PRELOADed into EVERY guest process via
/etc/ld.so.preload) now interposes bind(): passthrough semantics, but
every call appends family+errno (BINDINET_/BINDUNIX_/BINDOTH_ + OK/E<n>)
to /tmp/emux_bindlog. emux-x11 records each launch attempt's flags+rc to
/tmp/emux_attempts. The 15 s watcher paints both files every tick
("bindlog: ...", "attempts: ..."), so the next screenshot carries X's
exact per-bind errno and the flag sequence that produced it.
Shim marker v8; sandbox-verified with a bind() test program (BINDINET_OK).
No behaviour change otherwise; launch order stays v0.1.86's.
versionCode 87, rebuilt emux-debug.apk (same keystore).

## 34. v0.1.88 - complete bind telemetry (numeric families, full log, 3 log generations)

v0.1.87 field bindlog (last 6 only): ... BINDINET_OK BINDUNIX_OK BINDOTH_OK
BINDINET_OK BINDUNIX_E98 with attempts 1/2/3 all rc=1. Read: guest binds
mostly succeed; ONE unix bind hits EADDRINUSE (98) - and the truncation
hid which attempt/family sequence produced it (BINDOTH = AF_INET6=10
unlabelled). v0.1.88 makes the telemetry lossless:
- shim v9: numeric family tags (B2_=inet, B1_=unix, B10_=inet6, B<n>_ for
  the rest), so every line is unambiguous.
- Watcher paints the FULL bindlog (chunked 3x330), not takeLast(6).
- xvfb.log now keeps THREE generations (.2 retained): attempt 1's real
  error survived only two rotations before and was lost.
- attempts file gains epoch timestamps; attempts 1-2 also pass
  -nolisten local (on some X builds "unix" alone does not disable the
  unix transport; "local" is the classic umbrella name).
Sandbox-verified tag format with a double-bind program (B2_OK / B2_E98).
versionCode 88, rebuilt emux-debug.apk (same keystore).

## 35. v0.1.89 - THE lifecycle bug: startDesktop murdered its own first session

v0.1.88 field panes (two parts) showed: ctrl-wait expired ONE SECOND after
start (waitForPort succeeded against a STALE listener whose X handshake
then failed - connectControlPlane returned false on the first exception),
no attempts/bindlog notes ever (emux-x11 never reached a launch in the
visible window), and respawns chaining. Root cause found by reading the
lifecycle: section 0b (killDesktopGuests) runs AFTER the x11 session is
launched, and v0.1.81 added "emux-x11" to the sweep patterns - so EVERY
desktop start killed its own first x11 session milliseconds after spawn.
The pre-frame respawn chain that follows then creates Xvfb orphans across
proot instances (guest sweeps cannot signal foreign tracees) - the E98
"server already running" cascade of rounds 81-88 was largely SELF-infl
icted by this ordering.

- Reordered: 0b sweep + NEW port-6000-free verification loop (re-sweep up
  to 4x, notes "port6000 state at session start: free/HELD") now run
  BEFORE the x11 session is spawned.
- connectControlPlane: retry port+handshake until the deadline instead of
  returning false on the first handshake exception (kills the +1 s bogus
  "ctrl-wait expired" and lets a stale listener age out).
- On final ERROR: dump xvfb.log.2/.1/log + bindlog + attempts into history
  notes (chunked) - full telemetry survives the scroll window.
versionCode 89, rebuilt emux-debug.apk (same keystore).

## 36. v0.1.90 - color-coded pane + ROM-proof layout toggle (es⇄ key)

User request: paint what is going RIGHT in green and the rest in red.
- DiagColors classifier (shared): LINK_OK / SHIM_LOADED / SHIMTEST_*_OK /
  BIND*_0 / B<n>_OK / rc=0 / rc=124 / hostbind *=true / port6000 free /
  emux-linkfix-vN / installed / purged / dpkgdir+status perms / selfcheck
  header+shim ls lines / "shim=true preload=true" => GREEN; everything
  else RED. Applied to the DesktopView pane canvas (per line) and to the
  status TextView (per-line ForegroundColorSpan).
- Keyboard: the ROM swallows Alt+Shift on some devices, so the terminal
  extra-keys row gains an es⇄ key: toggles term_keylayout Auto<->Spanish
  and prints "[emux] terminal keyboard layout: X" into the session.
  Works from both tabs' key rows; desktop behaviour itself unchanged.
versionCode 90, rebuilt emux-debug.apk (same keystore).

## 37. v0.1.91 - Xvfb is UP; capture was the last missing link (+ terminal perf)

v0.1.90 field pane (color-coded, working as intended): "port6000 state at
session start: free", "attempts: ... attempt 1 rc=0 flags=-nolisten unix
-nolisten local -nolisten inet6", bindlog all B1_OK/B2_OK, xvfb.log only
cosmetic keysym warnings => THE X SERVER FINALLY RUNS on this device.
Zero frames remained because emux-capture started IMMEDIATELY at session
start, died (no X yet, and xwd/netpbm/nc not installed yet - they arrive
with the background REST pass), and burned its 3 respawns before the
toolchain landed. A healthy X with no capture = black pane forever.

- Capture start is now GATED: wait for guest tcp 6000 (<=120 s) AND for
  usr/bin/xwd + usr/bin/pnmtoplainpnm + bin/nc.openbsd on disk (<=240 s),
  then start; note "capture: X up and toolchain present". Respawn cap 3->40.
- emux-x11: fixed a stray "//" comment line (v0.1.86) that made dash print
  "//: Permission denied" on every run.
- Terminal cold-start perf (user request): TtyWrap PTY-wrapper probe result
  (argv prefix + dialect + ctty) is now cached in files/ttywrap.probe, so
  app launches skip re-exec'ing every wrapper candidate; startupRepair
  yields 6 s to the first session instead of racing it for CPU.
- DiagColors: "X up=true tools=true" green.
versionCode 91, rebuilt emux-debug.apk (same keystore).
