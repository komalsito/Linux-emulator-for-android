package com.emux.core

import android.content.Context
import java.io.File

/**
 * Canonical filesystem layout, Termux-style: everything lives under the app's
 * private data dir (no permissions needed) and is bind-exposed to $HOME/shared.
 *
 * /data/data/com.emux/
 *   files/usr        -> bootstrap binary root (read-only-ish, patched shebangs)
 *   files/linuxroot  -> full Linux distribution tree (chroot/proot target)
 *   files/home       -> $HOME for sessions
 *   cache/tmp        -> $TMPDIR (cleared by OS on demand = friendly to storage)
 */
object FsLayout {
    const val ENV_VERSION = 14  // v0.1.34: emux-init.sh proot TMPDIR bind re-seed  // v0.1.28: fixed hasCompleteRootfs (was always false → re-download loop), proot native-bridge seeding, apk-installed shell/desktop components; re-seed is now CHEAP (marker re-stamp, no re-download)

    fun root(ctx: Context): File = rootOrFallback(ctx)
    fun usr(ctx: Context): File = File(root(ctx), "usr")
    fun linuxRoot(ctx: Context): File = File(root(ctx), "linuxroot")
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
    fun isBootstrapped(ctx: Context): Boolean = try {
        val p = prefixFile(ctx)
        p.exists() && p.readText().trim() == ENV_VERSION.toString() &&
            File(linuxRoot(ctx), "etc").isDirectory
    } catch (_: Throwable) { false }

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
    fun hasCompleteRootfs(ctx: Context): Boolean = try {
        val r = linuxRoot(ctx)
        val bin = File(r, "bin")
        (isRegularNoFollow(File(bin, "busybox")) || isRegularNoFollow(File(bin, "sh"))) &&
            File(r, "etc/alpine-release").isFile &&
            libDirHasMuslLoader(File(r, "lib")) &&
            File(r, "etc").isDirectory
    } catch (_: Throwable) { false }

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
        markBootstrapped(ctx)
        return isBootstrapped(ctx)
    }

    fun markBootstrapped(ctx: Context) {
        // v0.1.15: atomic write (tmp + rename). A killed mid-write used to leave a
        // truncated/empty .env_version → isBootstrapped() false → full re-download
        // on every launch even though the rootfs was perfectly fine.
        runCatching {
            val p = prefixFile(ctx)
            val tmp = File(p.parentFile, ".env_version.tmp")
            tmp.writeText(ENV_VERSION.toString())
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
    fun markBootstrappedChecked(ctx: Context): Boolean {
        markBootstrapped(ctx)
        return try {
            prefixFile(ctx).exists() &&
                prefixFile(ctx).readText().trim() == ENV_VERSION.toString() &&
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
        // X11: westwayland/xserver will listen here; display number fixed at :0
        "DISPLAY" to ":0",
        // tell programs we're an emoji-capable mobile terminal like Termux does
        "EMUX" to "1",
    )
}
