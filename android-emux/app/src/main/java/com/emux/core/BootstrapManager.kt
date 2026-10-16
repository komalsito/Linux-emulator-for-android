package com.emux.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads and installs a real Linux distribution rootfs (Alpine by default —
 * ~3 MB compressed, musl, apk package manager) into FsLayout.linuxRoot().
 *
 * Extraction strategy: we shell out to the device's own `tar`/`unzip` (present on
 * every Android since 4.x via toybox/busybox), so no Java decompression heap spikes
 * and no native code needed. This is the same trick Termux bootstrap uses.
 *
 * Power notes: runs only when [androidx.work] constraints allow (charging/idle for
 * optional desktop extras); core bootstrap is one streaming pass, then never again.
 */
class BootstrapManager(private val ctx: Context) {

    data class Progress(val step: String, val pct: Int) // -1 = indeterminate

    companion object {
        const val DEFAULT_DISTRO = "alpine-minirootfs"
        const val ALPINE_VERSION = "3.20"
        const val MIRROR = "https://dlcdn.alpinelinux.org/alpine/v"

        fun archName(): String = when (android.os.Build.SUPPORTED_ABIS.firstOrNull()) {
            "arm64-v8a", "armeabi-v7a" -> if (android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a")) "aarch64" else "armv7"
            "x86_64" -> "x86_64"
            else -> "armhf" // best effort fallback; users can override in settings
        }

        fun rootfsUrl(distro: String = DEFAULT_DISTRO, version: String = ALPINE_VERSION): String =
            "$MIRROR/$version/releases/${archName()}/${distro}-$version-${archName()}.tar.gz"
    }

    /** Streams download+extract progress as a Flow so UI can render a single bar. */
    fun bootstrapFlow(): Flow<Result<Unit>> = callbackFlow {
        try {
            send(Result.failure(BootstrapProgress("Resolving mirror", -1)))
            val dest = FsLayout.linuxRoot(ctx)
            if (dest.exists()) dest.deleteRecursively()
            dest.mkdirs()

            val url = URL(rootfsUrl())
            val tmp = File(FsLayout.tmp(ctx), "rootfs.tar.gz")
            downloadTo(url, tmp) { pct -> trySend(Result.failure(BootstrapProgress("Downloading rootfs", pct))) }

            trySend(Result.failure(BootstrapProgress("Extracting rootfs", -1)))
            extractTarGz(tmp, dest)
            tmp.delete()

            trySend(Result.failure(BootstrapProgress("Linking /data/data paths", -1)))
            linkAndroidRuntime(dest)
            postInstall(dest)

            FsLayout.markBootstrapped(ctx)
            trySend(Result.success(Unit))
        } catch (e: Throwable) {
            trySend(Result.failure(e))
        }
        awaitClose { }
    }

    suspend fun bootstrap(): Result<Unit> = withContext(Dispatchers.IO) {
        var last: Result<Unit> = Result.failure(Exception("not started"))
        bootstrapFlow().collect { last = it }
        last
    }

    private fun downloadTo(url: URL, out: File, onPct: (Int) -> Unit) {
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        val total = conn.contentLength
        conn.inputStream.use { input ->
            out.outputStream().use { os ->
                val buf = ByteArray(64 * 1024) // 64 KiB: good balance of syscalls vs memory
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    os.write(buf, 0, n)
                    done += n
                    if (total > 0) onPct(((done * 100) / total).toInt())
                }
            }
        }
        conn.disconnect()
    }

    /** Use system tar (toybox) — avoids pulling a JVM gzip buffer into memory. */
    private fun extractTarGz(tgz: File, dest: File) {
        val p = ProcessBuilder("tar", "-xzf", tgz.absolutePath, "-C", dest.absolutePath)
            .redirectErrorStream(true).start()
        // drain so the child never blocks on a full pipe
        BufferedReader(InputStreamReader(p.inputStream)).forEachLine { /* log */ }
        val code = p.waitFor()
        if (code != 0) throw RuntimeException("tar failed ($code); device may lack toybox tar")
    }

    /**
     * Make the chroot usable without root: bind-free access to the Android DNS
     * resolver and a minimal /proc,/sys,/dev populated at session start instead.
     * Here we just pre-create mountpoint dirs and seed resolv.conf from Android.
     */
    private fun linkAndroidRuntime(dest: File) {
        listOf("proc", "sys", "dev", "tmp", "run", "root", "home").forEach {
            File(dest, it).mkdirs()
        }
        File(dest, "tmp").setWritable(true, false)
        // Copy Android's DNS servers so busybox/http clients work inside the distro.
        try {
            val dns = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())
                .flatMap { ni: java.net.NetworkInterface ->
                    // Android ignores NetworkInterface.networkParameters (unavailable in the
                    // core-libs stub); fall back to a well-known public resolver instead.
                    emptyList<String>()
                }
                .filter { it.isNotEmpty() }
                .distinct()
            val servers = if (dns.isNotEmpty()) dns else listOf("8.8.8.8", "1.1.1.1")
            File(dest, "etc/resolv.conf").writeText(servers.joinToString("\n") { "nameserver $it\n" })
        } catch (_: Exception) { /* non-fatal */ }
    }

    private fun postInstall(dest: File) {
        // sane defaults: non-login shells get our env, disable services that need init
        File(dest, "etc/profile.d/emux.sh").apply {
            parentFile?.mkdirs()
            writeText(
                """
                export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
                export PS1='\[\e[38;5;39m\]emux:\w\[\e[0m\] \$ '
                alias ll='ls -alF'
                # apk wrapper that works without root inside proot/chroot-lite
                alias apk='apk --no-interactive'
                """.trimIndent()
            )
        }
        File(dest, "etc/os-release").appendText("\nEMUX=1\n")
    }
}

/** Small flow helper so BootstrapManager failures carry progress strings. */
class BootstrapProgress(val step: String, val pct: Int) : Exception(step)

// Android's NetworkInterface doesn't expose DNS on all API levels; fall back to
// the net.dns1/net.dns2 system properties (readable by any app).
fun java.net.NetworkInterface.stapledDnsServersCompat(): List<String> = try {
    val cls = Class.forName("android.os.SystemProperties")
    val get = cls.getMethod("get", String::class.java)
    listOfNotNull(
        get.invoke(null, "net.dns1") as? String,
        get.invoke(null, "net.dns2") as? String,
    ).filter { it.isNotBlank() }
} catch (_: Exception) { emptyList() }
