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
    const val ENV_VERSION = 1   // bump when bootstrap format changes → triggers re-bootstrap

    fun root(ctx: Context): File = ctx.filesDir
    fun usr(ctx: Context): File = File(root(ctx), "usr")
    fun linuxRoot(ctx: Context): File = File(root(ctx), "linuxroot")
    fun home(ctx: Context): File = File(root(ctx), "home").apply { mkdirs() }
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
        "PATH" to "${usr(ctx)}/bin:${usr(ctx)}/sbin:" +
            "${linuxRoot(ctx)}/usr/bin:${linuxRoot(ctx)}/usr/sbin",
        "LANG" to "en_US.UTF-8",
        "TERM" to "xterm-256color",
        "SHELL" to "/bin/bash",
        // X11: westwayland/xserver will listen here; display number fixed at :0
        "DISPLAY" to ":0",
        // tell programs we're an emoji-capable mobile terminal like Termux does
        "EMUX" to "1",
    )
}
