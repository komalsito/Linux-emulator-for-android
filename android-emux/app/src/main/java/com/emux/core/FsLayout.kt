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
    const val ENV_VERSION = 8   // v0.1.24: TtyWrap apostrophe-proof quoting + launcher body sanitization (the no-apostrophes guard made the sh -c path DEAD CODE — every session fell into the SELinux-denied direct exec)

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
