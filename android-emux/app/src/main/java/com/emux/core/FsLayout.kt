package com.emux.core

import android.content.Context
import java.io.File

/**
 * Canonical filesystem layout, Termux-style: everything lives under the app's
 * private data dir (no permissions needed) and is bind-exposed to $HOME/shared.
 *
 * /data/data/com.emux/
 *   files/usr        -> bootstrap binary root (read-only-ish, patched shebangs)
 *   files/distros/<id> -> installed Linux distribution trees (chroot/proot
 *                         targets; v0.1.47 multi-distro: alpine/debian/ubuntu
 *                         can COEXIST; the ACTIVE one is the Settings choice)
 *   files/linuxroot  -> LEGACY single-distro tree (v0.1.46 and older); migrated
 *                         to files/distros/alpine on first access
 *   files/home       -> $HOME for sessions
 *   cache/tmp        -> $TMPDIR (cleared by OS on demand = friendly to storage)
 */
object FsLayout {
    const val ENV_VERSION = 47  // v0.2.25: damage-driven capture pacing re-seed  // v0.2.23: fixed aarch64 emux-rgb565 (_start argv bug) re-seed  // v0.2.20: watcher rotation-follow + complete self-heal re-seed  // v0.2.18: verify_x instrumentation + self-heal re-seed  // v0.2.16: verify_x handshake gating re-seed  // v0.2.15: perl encoder fallback + verbose conv probe re-seed  // v0.2.14: per-connection header + xterm re-seed  // v0.2.13: adopt-don't-fight ladder re-seed  // v0.2.12: conv selftest + loud stages re-seed  // v0.2.11: killer-subshell bounds instead of timeout(1) re-seed  // v0.2.10: MIT-SHM off + stage-wise grab re-seed  // v0.2.9: ladder death-check + capture timeout/per-frame push re-seed  // v0.2.8: age-filtered kill_stale_x re-seed  // v0.2.6: socket-occupation rotation + display marker re-seed  // v0.2.5: display rotation + capture session-display preflight re-seed  // v0.2.4: Xvfb unix-socket ladder + capture DISPLAY preflight re-seed  // v0.1.63: selfcheck + LD_PRELOAD belt  // v0.1.62: split cascade + xvfb watchdog re-seed  // v0.1.61: settle-pass helpers + control-plane retry re-seed  // v0.1.60: capture-chain + package-name fixes re-seed  // v0.1.59: longer guest waits re-seed  // v0.1.58: apt-collision-proof cascade re-seed  // v0.1.57: capture-toolchain cascade + repair-aware emux-capture re-seed  // v0.1.55: probe tr fix + gap-healing re-seed  // v0.1.54: terminal scrollback + desktop lifecycle fixes re-seed helpers  // v0.1.53: emux-apt-repair helper + link()->linkat() preload shim re-seed  // v0.1.47: multi-distro layout (files/distros/<id>, marker carries distro id) — bump re-seeds helpers; existing rootfs is MIGRATED, never re-downloaded  // v0.1.39: winsize watcher + Xvfb shm fallback re-seed  // v0.1.37: guest-PTY emux-init re-seed  // v0.1.36: emux-init PROOT_LOADER re-seed  // v0.1.34: emux-init.sh proot TMPDIR bind re-seed  // v0.1.28: fixed hasCompleteRootfs (was always false → re-download loop), proot native-bridge seeding, apk-installed shell/desktop components; re-seed is now CHEAP (marker re-stamp, no re-download)

    fun root(ctx: Context): File = rootOrFallback(ctx)
    fun usr(ctx: Context): File = File(root(ctx), "usr")

    /**
     * v0.1.47 MULTI-DISTRO: installed distros live in files/distros/<id>/.
     * The legacy single tree files/linuxroot is moved to files/distros/alpine
     * ONCE (cheap same-filesystem rename; guarded, idempotent, process-wide)
     * so a v0.1.46 install is preserved across the upgrade WITHOUT any
     * re-download. If the rename ever fails (in-use fd weirdness on an OEM
     * ROM), linuxRoot() below falls back to the legacy path while the active
     * distro is alpine — the tree is never orphaned.
     */
    fun distrosDir(ctx: Context): File = File(root(ctx), "distros").apply { runCatching { mkdirs() } }

    /** The distro id the user selected in Settings (default: alpine). */
    fun activeDistroId(ctx: Context): String =
        runCatching { DistroCatalog.requested(ctx).id }.getOrDefault(DistroCatalog.DEFAULT_ID)

    @Volatile private var legacyMigrated = false
    private fun migrateLegacyRootfs(ctx: Context) {
        if (legacyMigrated) return
        synchronized(this) {
            if (legacyMigrated) return
            runCatching {
                val legacy = File(root(ctx), "linuxroot")
                val alpine = File(distrosDir(ctx), "alpine")
                if (legacy.isDirectory && !alpine.exists()) {
                    alpine.parentFile?.mkdirs()
                    val ok = legacy.renameTo(alpine)
                    com.emux.EmuXLog.i(
                        if (ok) "migrated legacy linuxroot -> distros/alpine"
                        else "legacy linuxroot rename FAILED (falling back in-place)")
                }
            }
            legacyMigrated = true
        }
    }

    /**
     * The ACTIVE distro's rootfs (proot/chroot target). Every session,
     * desktop and bootstrap call site resolves through here, so switching the
     * Settings distro switches the whole environment. The returned directory
     * may not exist yet (fresh install of a newly selected distro) —
     * BootstrapManager creates it; emux-init.sh falls back to ANY installed
     * distro so the terminal never dies on a pending switch.
     */
    fun linuxRoot(ctx: Context): File {
        migrateLegacyRootfs(ctx)
        val d = File(distrosDir(ctx), activeDistroId(ctx))
        if (!d.isDirectory && activeDistroId(ctx) == "alpine") {
            val legacy = File(root(ctx), "linuxroot")
            if (legacy.isDirectory) return legacy   // rename failed earlier — keep working
        }
        return d
    }

    fun home(ctx: Context): File = File(root(ctx), "home").apply { mkdirs() }


    /**
     * v0.1.27 PERSISTENCE FIX #1: Context.getFilesDir() is @Nullable — it returns
     * null while the device sits in direct-boot state (before the user's first
     * unlock after a reboot). The old bare `File(ctx.filesDir, ...)` then threw a
     * KotlinNullPointerException inside isBootstrapped()'s try/catch, which was
     * swallowed and returned FALSE. Every code path that treats false as "not
     * installed" (EmuXApplication.scheduleAuto, MainActivity's bootstrap UI,
     * BootstrapManager's alreadyDone short-circuit) consequently re-ran the ENTIRE
     * download+extract on some launch — exactly the reported "either doesn't work
     * or re-installs every time I close and reopen the app". Mirror safeTmp()'s
     * proven pattern: never nullable, with an internal-storage fallback.
     */
    private fun rootOrFallback(ctx: Context): File = try {
        ctx.filesDir ?: File(ctx.applicationInfo.dataDir, "files")
    } catch (_: Throwable) {
        File("/data/data/${ctx.packageName}/files")
    }.apply { runCatching { mkdirs() } }
    /**
     * v0.1.9 CRASH FIX: Context.getCacheDir() is @Nullable (returns null while
     * the device is direct-boot-locked before first user unlock, and some OEM
     * ROMs return null under storage pressure). `File(null, "tmp")` then threw
     * KotlinNullPointerException — inside BootstrapWorker's withContext(IO)
     * CHILD scope, which CoroutineWorker does NOT catch → UncaughtExceptionHandler
     * → process death exactly when Install was tapped. Never nullable now.
     */
    fun safeTmp(ctx: Context): File = try {
        val c = ctx.cacheDir
        if (c != null) File(c, "tmp") else File(root(ctx), "tmp")
    } catch (_: Throwable) { File(root(ctx), "tmp") }.apply { runCatching { mkdirs() } }

    fun tmp(ctx: Context): File = safeTmp(ctx)

    /** v0.1.66: set by BootstrapManager when the selfcheck proves the seccomp
     *  avoider is the link(2) breaker on this device. */
    fun prootNoSeccompFlag(ctx: Context): File = File(root(ctx), ".proot-no-seccomp")
    fun prefixFile(ctx: Context): File = File(root(ctx), ".env_version")

    /**
     * v0.1.8 CRASH FIX (verified against shipped dex): this used to be a bare
     * `readText()` — if `.env_version` was ever written empty/corrupted (killed
     * mid-write during a previous bootstrap crash, storage pressure, OEM backup
     * restore), readText threw and the exception escaped MainActivity's
     * `b.root.post { … }` block. Code posted via View.post runs OUTSIDE any
     * try/catch on the main looper → uncaught → "EmuX keeps stopping" on every
     * launch of the affected install. Now it can never throw.
     */
    /**
     * v0.1.47 MULTI-DISTRO marker format: "<ENV_VERSION>:<distroId>" (e.g.
     * "21:alpine"). A bare "<ENV_VERSION>" (v0.1.46 and older) counts as
     * alpine — the only distro those builds could install — but since
     * ENV_VERSION bumped, such markers are stale anyway and land in the cheap
     * re-stamp path (no re-download; see BootstrapManager).
     */
    fun installedDistroId(ctx: Context): String? = try {
        val t = prefixFile(ctx).readText().trim()
        when {
            t.contains(':') -> {
                val parts = t.split(':', limit = 2)
                if (parts[0] == ENV_VERSION.toString() && parts[1].isNotBlank()) parts[1] else null
            }
            t == ENV_VERSION.toString() -> DistroCatalog.DEFAULT_ID
            else -> null
        }
    } catch (_: Throwable) { null }

    /**
     * v0.1.47: "bootstrapped" now means the ACTIVE distro (Settings choice) is
     * installed AND stamped. A pending distro switch (user picked Ubuntu while
     * Alpine is installed) correctly reads as NOT bootstrapped, which shows the
     * Install pane — but EmuXApplication only auto-schedules on a truly empty
     * environment, so a switch is never downloaded without an explicit tap.
     */
    fun isBootstrapped(ctx: Context): Boolean = try {
        installedDistroId(ctx) == activeDistroId(ctx) &&
            File(linuxRoot(ctx), "etc").isDirectory
    } catch (_: Throwable) { false }

    /** Ids of distros whose trees are complete on disk (marker-independent). */
    fun installedDistroIds(ctx: Context): List<String> = try {
        val dirs = distrosDir(ctx).listFiles()?.toList().orEmpty()
        val ids = dirs.filter { it.isDirectory }
            .mapNotNull { d ->
                // The DIRECTORY NAME decides the spec (Debian and Ubuntu trees
                // pass each other's shape probe — bash+dpkg+debian_version —
                // so sniffing alone would mislabel ubuntu/ as debian).
                val named = DistroCatalog.ALL.firstOrNull { it.id == d.name }
                if (named != null) {
                    if (hasCompleteRootfsAt(d, named)) named.id else null
                } else {
                    DistroCatalog.ALL.firstOrNull { s -> hasCompleteRootfsAt(d, s) }?.id
                }
            }
            .toMutableList()
        val legacy = File(root(ctx), "linuxroot")
        if (legacy.isDirectory && !ids.contains("alpine") &&
            hasCompleteRootfsAt(legacy, DistroCatalog.ALPINE)) {
            ids += "alpine"
        }
        ids
    } catch (_: Throwable) { emptyList() }

    /** True when ANY distro tree is complete (used to gate auto-scheduling). */
    fun anyDistroInstalled(ctx: Context): Boolean = installedDistroIds(ctx).isNotEmpty()

    /**
     * v0.1.27 PERSISTENCE FIX #2 — rootfs present but marker lost ≠ not installed.
     *
     * Verified against the Android 12+ spec (developer.android.com/guide/topics/
     * data/autobackup): with allowBackup="true" and no custom backup rules, Auto
     * Backup includes /data/data/<pkg>/shared_prefs and the DATABASES/scankd-style
     * key-value set, but NOT arbitrary filesDir content, on every app uninstall
     * ("Close and reopen the app" on OEM ROMs that auto-reinstall-on-restore, or
     * after a Play Store "re-download", restores shared_prefs while files/linuxroot
     * was wiped → stale state; conversely, a restore onto a device where the rootfs
     * DID survive but .env_version did not made isBootstrapped() false → full
     * re-download on every launch — the reported "re-installs every time").
     *
     * A complete Alpine tree (bin/sh + etc/alpine-release + apk tools) cannot
     * appear by accident: if it is there, the install IS done. We re-stamp the
     * marker instead of re-downloading ~3 MB + extracting thousands of files.
     */
    /**
     * v0.1.28 PERSISTENCE FIX (THE re-download-loop bug, verified against the
     * real alpine-minirootfs-3.20.3 tarball):
     *
     * The v0.1.27 version of this probe could NEVER return true on a complete
     * rootfs, which made every "re-stamp instead of re-download" path dead
     * code and turned every ENV_VERSION bump (i.e. every app update) into a
     * full wipe + re-download — the reported "installs every time" symptom.
     * Two factual errors, both verified by listing the actual tarball:
     *
     *  1. `bin/sh` is a symlink to the ABSOLUTE path /bin/busybox. On the
     *     Android host that target does not exist (/bin → /system/bin, no
     *     busybox), so File.exists() — which FOLLOWS symlinks — was always
     *     false even on a perfectly extracted tree. Probe the real file
     *     instead: bin/busybox is a regular ~900 KB file in every minirootfs.
     *     lstat-style (never follow the distro's absolute symlinks).
     *  2. The musl loader lives under lib/ (ld-musl-ARCH.so.1), not bin/.
     *     The old binDirHasMuslLoader(File(r, "bin")) scanned the wrong
     *     directory — always false.
     */
    fun hasCompleteRootfs(ctx: Context): Boolean =
        hasCompleteRootfsAt(linuxRoot(ctx), DistroCatalog.byId(activeDistroId(ctx)))

    /**
     * v0.1.47: completeness probe for ANY distro tree (used both for the
     * active rootfs and for scanning the files/distros/ tree to find co-installed
     * distros). Alpine keeps the v0.1.28 probe (busybox/sh + alpine-release +
     * musl loader — all lstat-based, never follows absolute guest symlinks).
     * Debian/Ubuntu probe: a shell (bash/dash), dpkg tooling and the
     * debian_version stamp (Ubuntu rootfs images ship it too); all checks are
     * symlink-tolerant (merged-/usr images symlink bin -> usr/bin, and
     * File.isFile FOLLOWS those internal links, which is exactly right here —
     * unlike the Alpine absolute-symlink case the targets stay inside the tree).
     */
    fun hasCompleteRootfsAt(r: File, spec: DistroSpec): Boolean {
        return try {
        if (!File(r, "etc").isDirectory) return false
        when (spec.pkg) {
            PkgManager.APK -> {
                val bin = File(r, "bin")
                (isRegularNoFollow(File(bin, "busybox")) || isRegularNoFollow(File(bin, "sh"))) &&
                    File(r, "etc/alpine-release").isFile &&
                    libDirHasMuslLoader(File(r, "lib"))
            }
            PkgManager.APT -> {
                (File(r, "bin/bash").isFile || File(r, "usr/bin/bash").isFile ||
                    File(r, "bin/dash").isFile || File(r, "usr/bin/dash").isFile) &&
                    (File(r, "usr/bin/dpkg").isFile || File(r, "bin/dpkg").isFile) &&
                    File(r, "etc/debian_version").isFile
            }
            PkgManager.PACMAN -> {
                (File(r, "bin/bash").isFile || File(r, "usr/bin/bash").isFile) &&
                    File(r, "usr/bin/pacman").isFile &&
                    File(r, "etc/pacman.conf").isFile
            }
            PkgManager.DNF -> {
                (File(r, "bin/bash").isFile || File(r, "usr/bin/bash").isFile) &&
                    File(r, "usr/bin/dnf").isFile &&
                    File(r, "etc/fedora-release").isFile
            }
        }
        } catch (_: Throwable) { false }
    }

    /** lstat-style check: true for a regular file OR a symlink (dangling or not) — never follows. */
    private fun isRegularNoFollow(f: File): Boolean = try {
        val a = java.nio.file.Files.readAttributes(
            f.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS)
        a.isRegularFile || a.isSymbolicLink
    } catch (_: Throwable) { false }

    private fun libDirHasMuslLoader(lib: File): Boolean = try {
        (lib.list()?.any { it.startsWith("ld-musl-") && it.endsWith(".so.1") }) == true
    } catch (_: Throwable) { false }

    /**
     * Canonical helper-bin directory (files/bin). v0.1.27: single source of truth
     * so every call site shares FsLayout.root()'s direct-boot fallback instead of
     * touching the @Nullable ctx.filesDir directly.
     */
    fun binDir(ctx: Context): File = File(root(ctx), "bin").apply { mkdirs() }

    /**
     * v0.1.28: directory holding the bundled bionic "native bridge" binaries —
     * Termux-built proot + libtalloc.so.2 + libandroid-shmem.so for the device
     * ABI (extracted from APK assets by bootstrap; see assets/proot/LICENSES.txt).
     * These use DT_RUNPATH (verified per-ABI), so pointing LD_LIBRARY_PATH at
     * this directory lets them load their libraries from app-private storage —
     * no Termux installation required. proot gives unrooted sessions a real
     * chroot-equivalent (absolute distro paths, PT_INTERP rewriting, working
     * xkbcomp for Xvfb), which is what makes the distro AND the X11 desktop
     * usable without root.
     */
    fun nativeBridgeDir(ctx: Context): File = File(root(ctx), "native-bridge").apply { mkdirs() }
    fun prootBin(ctx: Context): File = File(nativeBridgeDir(ctx), "proot")

    /** The ABI subdirectory of assets/proot/ matching this device. */
    fun nativeBridgeAssetAbi(): String = when {
        android.os.Build.SUPPORTED_ABIS.any { it == "arm64-v8a" } -> "arm64-v8a"
        android.os.Build.SUPPORTED_ABIS.any { it == "armeabi-v7a" } -> "armeabi-v7a"
        android.os.Build.SUPPORTED_ABIS.any { it == "x86_64" } -> "x86_64"
        android.os.Build.SUPPORTED_ABIS.any { it == "x86" } -> "x86"
        else -> "arm64-v8a"
    }

    /**
     * v0.1.28: shell components = the apk-installed PTY source (util-linux
     * script(1)) + bash. Without them the terminal has no PTY (raw pipes → no
     * echo → "keyboard dead"), so a rootfs missing them must NOT be stamped as
     * complete: the launch path then schedules a bootstrap pass which lands in
     * BootstrapManager's cheap re-seed branch (no re-download — the rootfs is
     * complete) and retries the apk install.
     */
    fun hasShellComponents(ctx: Context): Boolean = try {
        val r = linuxRoot(ctx)
        File(r, "usr/bin/script").isFile || File(r, "bin/script").isFile
    } catch (_: Throwable) { false }

    fun ensureMarkerForExistingRootfs(ctx: Context): Boolean {
        if (!hasCompleteRootfs(ctx)) return false
        if (!hasShellComponents(ctx)) return false
        // v0.1.47: stamp the ACTIVE distro — the marker may still name a
        // different one (that is exactly the switch-back case this re-stamps).
        markBootstrapped(ctx, activeDistroId(ctx))
        return isBootstrapped(ctx)
    }

    /**
     * v0.1.47: [distroId] is stamped into the marker ("<ENV>:<id>") so a
     * pending Settings switch never reads as "installed". Null keeps the
     * currently recorded id (legacy call sites); a bare-number legacy marker
     * is upgraded to "…:alpine".
     */
    fun markBootstrapped(ctx: Context, distroId: String? = null) {
        // v0.1.15: atomic write (tmp + rename). A killed mid-write used to leave a
        // truncated/empty .env_version → isBootstrapped() false → full re-download
        // on every launch even though the rootfs was perfectly fine.
        runCatching {
            val id = distroId ?: installedDistroId(ctx) ?: DistroCatalog.DEFAULT_ID
            val p = prefixFile(ctx)
            val tmp = File(p.parentFile, ".env_version.tmp")
            tmp.writeText("$ENV_VERSION:$id")
            if (!tmp.renameTo(p)) {
                p.delete(); tmp.renameTo(p)
            }
        }
    }

    /**
     * v0.1.15: like [markBootstrapped] but verifies the marker actually reads back
     * before returning true — BootstrapManager refuses to report success when the
     * completion state cannot be persisted (otherwise the next launch silently
     * starts the whole download again and users see "installation never finishes").
     */
    fun markBootstrappedChecked(ctx: Context, distroId: String? = null): Boolean {
        markBootstrapped(ctx, distroId)
        return try {
            installedDistroId(ctx) == (distroId ?: installedDistroId(ctx)) &&
                File(linuxRoot(ctx), "etc").isDirectory
        } catch (_: Throwable) { false }
    }

    /** Build the environment map injected into every session (POSIX-ish, minimal). */
    fun sessionEnv(ctx: Context): Map<String, String> = mapOf(
        "HOME" to home(ctx).absolutePath,
        "PREFIX" to usr(ctx).absolutePath,
        "TMPDIR" to tmp(ctx).absolutePath,
        // v0.1.17 BUGFIX: EMUX_ROOTFS was never exported. emux-init.sh resolves
        // the distro as ROOT="${EMUX_ROOT:-$HOME/../linuxroot}" — with HOME set
        // to files/home that expands to the NON-EXISTENT files/linuxroot (the
        // real tree is files/linuxroot, one level shallower), so the rootfs
        // check failed and the launcher exec'd plain Android sh: no distro PATH,
        // no distro binaries → empty-looking terminal where nothing works.
        "EMUX_ROOTFS" to linuxRoot(ctx).absolutePath,
        "EMUX_ROOT" to linuxRoot(ctx).absolutePath,
        // v0.1.19 BUGFIX L: files/bin holds the emux-* helper scripts (installed
        // by BootstrapManager since this version) and is prepended to PATH below
        // so bare `emux-x11` / `emux-capture` resolve from any distro shell.
        "EMUX_BIN" to File(root(ctx), "bin").absolutePath,
        // v0.1.19 BUGFIX L: files/bin was NOT on PATH at all — bare `emux-x11` /
        // `emux-capture` lookups from any distro shell failed with "not found",
        // both desktop helper sessions died instantly (pane stayed black) while
        // the terminal tab looked healthy. Prepend it so helpers resolve first,
        // before any same-named file inside the rootfs.
        "PATH" to "${File(root(ctx), "bin")}:${linuxRoot(ctx)}/usr/local/bin:${linuxRoot(ctx)}/usr/bin:" +
            "${linuxRoot(ctx)}/bin:${linuxRoot(ctx)}/usr/local/sbin:" +
            "${linuxRoot(ctx)}/usr/sbin:${linuxRoot(ctx)}/sbin:" +
            "${usr(ctx)}/bin:${usr(ctx)}/sbin:/system/bin:/system/xbin",
        "LANG" to "en_US.UTF-8",
        "TERM" to "xterm-256color",
        // v0.1.28: SHELL is used HOST-side by util-linux script(1) to run its
        // -c payload — it MUST be a shell that exists on the Android host.
        // /bin/sh only exists on Android 10+ (symlink to /system/bin); on
        // Android 7–9 it is absent and script(1) died instantly. The distro
        // login shell re-exports SHELL=/bin/bash via etc/profile.d/emux.sh
        // once inside proot/chroot.
        "SHELL" to "/system/bin/sh",
        // v0.1.28: bundled proot + its bionic libs (see nativeBridgeDir).
        "EMUX_NATIVE_BRIDGE" to nativeBridgeDir(ctx).absolutePath,
    ).let { env ->
        // v0.1.66: adaptive fallback - if the device selfcheck proved that
        // proot's seccomp avoider is what breaks link(2) (status-old EACCES
        // even with the shim loaded), the flag file makes EVERY proot run
        // (sessions included) use PROOT_NO_SECCOMP=1.
        if (prootNoSeccompFlag(ctx).isFile) env + ("PROOT_NO_SECCOMP" to "1") else env
    } + mapOf(
        // X11: westwayland/xserver will listen here; display number fixed at :0
        "DISPLAY" to ":0",
        // tell programs we're an emoji-capable mobile terminal like Termux does
        "EMUX" to "1",
    )
}
