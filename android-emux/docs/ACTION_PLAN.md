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
