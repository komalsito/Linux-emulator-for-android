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
    fun tmp(ctx: Context): File = File(ctx.cacheDir, "tmp").apply { mkdirs() }
    fun prefixFile(ctx: Context): File = File(root(ctx), ".env_version")

    fun isBootstrapped(ctx: Context): Boolean =
        prefixFile(ctx).exists() && prefixFile(ctx).readText().trim() == ENV_VERSION.toString() &&
            linuxRoot(ctx).exists()

    fun markBootstrapped(ctx: Context) {
        prefixFile(ctx).writeText(ENV_VERSION.toString())
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
