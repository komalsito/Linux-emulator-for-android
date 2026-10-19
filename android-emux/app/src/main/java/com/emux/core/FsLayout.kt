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
    const val ENV_VERSION = 11  // v0.1.27: persistence fixes (marker survives OEM auto-backup restore) + keyboard IME fixes; forces one re-seed of helper scripts

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
    fun hasCompleteRootfs(ctx: Context): Boolean = try {
        val r = linuxRoot(ctx)
        File(r, "bin/sh").exists() &&
            File(r, "etc/alpine-release").isFile &&
            // v0.1.27 CORRECTION: the stock minirootfs tarball ships an EMPTY
            // etc/apk/ directory (only `apk fix`/`apk add` ever create files
            // inside it). Requiring a file there made this check false-negative
            // on every fresh install — which would have re-downloaded anyway.
            // The real completeness probe is the dynamic linker: a truncated or
            // partially-extracted tree never has a usable ld-musl-*.so.1.
            binDirHasMuslLoader(File(r, "bin")) &&
            File(r, "etc").isDirectory
    } catch (_: Throwable) { false }

    private fun binDirHasMuslLoader(bin: File): Boolean = try {
        (bin.list()?.any { it.startsWith("ld-musl-") && it.endsWith(".so.1") }) == true
    } catch (_: Throwable) { false }

    /**
     * Canonical helper-bin directory (files/bin). v0.1.27: single source of truth
     * so every call site shares FsLayout.root()'s direct-boot fallback instead of
     * touching the @Nullable ctx.filesDir directly.
     */
    fun binDir(ctx: Context): File = File(root(ctx), "bin").apply { mkdirs() }

    fun ensureMarkerForExistingRootfs(ctx: Context): Boolean {
        if (!hasCompleteRootfs(ctx)) return false
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
        "SHELL" to "/bin/bash",
        // X11: westwayland/xserver will listen here; display number fixed at :0
        "DISPLAY" to ":0",
        // tell programs we're an emoji-capable mobile terminal like Termux does
        "EMUX" to "1",
    )
}
