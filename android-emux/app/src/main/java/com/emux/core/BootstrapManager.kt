package com.emux.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
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
        // NOTE: Alpine publishes point releases as .0 only (3.20.0, 3.20.3 …);
        // a bare "3.20" produces a 404 URL — that was the bootstrap failure.
        const val ALPINE_VERSION = "3.20.3"

        /** Mirrors tried in order — dlcdn occasionally has DNS/CDN hiccups. */
        val MIRRORS = listOf(
            "https://dlcdn.alpinelinux.org/alpine/v",
            "https://mirror.leaseweb.com/alpine/v",
            "https://ftp.halifax.rwth-aachen.de/alpine/v",
        )
        // Kept for compatibility with older call sites; the actual bootstrap loop
        // iterates over MIRRORS with fallback. Must be a compile-time constant,
        // so it cannot reference MIRRORS[0].
        const val MIRROR = "https://dlcdn.alpinelinux.org/alpine/v"

        /** "3.20.3" → "3.20" for the mirror's versioned directory layout. */
        fun majorVersion(v: String = ALPINE_VERSION): String =
            v.split('.').take(2).joinToString(".")

        fun archName(): String = when {
            // IMPORTANT: check arm64 FIRST. On 64-bit ARM devices SUPPORTED_ABIS is
            // ["arm64-v8a","armeabi-v7a","armeabi"] and the old `when(first)` form
            // could resolve to the wrong branch on some ROMs, producing a 404 URL
            // (armhf) → bootstrap failure with no visible error.
            android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a") -> "aarch64"
            android.os.Build.SUPPORTED_ABIS.contains("armeabi-v7a") -> "armv7"
            android.os.Build.SUPPORTED_ABIS.contains("x86_64") -> "x86_64"
            android.os.Build.SUPPORTED_ABIS.contains("x86") -> "x86"
            else -> "armv7" // best effort fallback; users can override via Settings mirror
        }

        fun rootfsUrl(distro: String = DEFAULT_DISTRO, version: String = ALPINE_VERSION): String =
            "$MIRROR/${majorVersion(version)}/releases/${archName()}/${distro}-$version-${archName()}.tar.gz"
    }

    /** Streams download+extract progress as a Flow so UI can render a single bar. */
    fun bootstrapFlow(): Flow<Result<Unit>> = callbackFlow {
        // v0.1.9 CRASH FIX (the real Install-button one): runBootstrapInternal's
        // outer try/catch already converts every failure into Result.failure —
        // the ONLY things that could still escape were (a) NPE from a null
        // cacheDir inside FsLayout.tmp() [fixed there], and (b) the detached
        // ioScope having no parent Job, so an escaped Throwable went to the
        // thread's UncaughtExceptionHandler and killed the whole process when
        // Install was tapped. Now the launch is guarded twice: explicit
        // supervisorContext for cancellation semantics + runCatching belt.
        val ioScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        ioScope.launch {
            runCatching { runBootstrapInternal { res -> trySend(res) } }
                .onFailure { e ->
                    com.emux.EmuXLog.e("bootstrap internal escape (guarded)", e)
                    BootstrapReporter.publishError(
                        ctx, "${e.javaClass.simpleName}: ${e.message ?: "no message"}")
                    trySend(Result.failure(e))
                }
        }
        awaitClose { ioScope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }

    private suspend fun runBootstrapInternal(emit: (Result<Unit>) -> Unit) {
        // v0.1.8: the caller (BootstrapWorker) already runs us on Dispatchers.IO;
        // progress is additionally mirrored to WorkManager via BootstrapReporter.
        val ctx = this.ctx
        fun report(step: String, pct: Int) {
            BootstrapReporter.publish(ctx, step, pct)
            emit(Result.failure(BootstrapProgress(step, pct)))
        }
        try {
            // v0.1.9 CRASH FIX (the real Install-button one, found by tracing the
            // shipped dex): `ctx.cacheDir` is @Nullable — on direct-boot-locked
            // devices and some OEM ROMs it returns null. FsLayout.tmp() then threw
            // KotlinNPE inside BootstrapWorker's withContext(IO); being a *child*
            // scope, that exception escaped CoroutineWorker's own try/catch and hit
            // the thread's UncaughtExceptionHandler → whole app killed the moment
            // Install was tapped. Every path touching cache files now uses a
            // non-null fallback.
            val dest = FsLayout.linuxRoot(ctx)
            val tmpFile = File(FsLayout.safeTmp(ctx), "rootfs.tar.gz")
            report("Resolving mirror", -1)

            // v0.1.13 STALL FIX #1 — top-down deleteRecursively() pre-wipe.
            // The old code ran Kotlin's dest.deleteRecursively() on
            // /data/data/com.emux/files/linuxroot right before downloading.
            // Kotlin's implementation FULLY ENUMERATES the tree into memory
            // before deleting anything; re-walking a large partially-extracted
            // rootfs (left over from an interrupted attempt) takes minutes on
            // ext4/f2fs. And because indeterminate progress frames only repaint
            // when the *next* step starts, the UI sat frozen at the previous
            // label ("Extracting rootfs" / "Linking…") — exactly the reported
            // "step takes forever / never passes" symptom.
            //
            // Fix:
            //  - skip the wipe entirely when there is nothing to clean (the
            //    common case: first run, or dest missing/empty);
            //  - otherwise use deleteBottomUp() — an explicit-stack walk that
            //    lstats each entry via java.nio (so FIFOs/device nodes are NEVER
            //    opened), deletes leaves as it descends and rmdirs deepest-first.
            //    No full enumeration pass, no blocking special files;
            //  - emit a dedicated "Cleaning previous rootfs" heartbeat frame so
            //    the UI visibly advances instead of looking stale.
            var hasEntries = false
            try {
                if (java.nio.file.Files.exists(dest.toPath())) {
                    java.nio.file.Files.newDirectoryStream(dest.toPath()).use { ds ->
                        hasEntries = ds.iterator().hasNext()
                    }
                }
            } catch (_: Throwable) {
                hasEntries = true   // unreadable → treat as dirty, bottom-up delete
            }
            if (hasEntries) {
                report("Cleaning previous rootfs", -1)
                runCatching { deleteBottomUp(dest) }
                    .onFailure { com.emux.EmuXLog.e("pre-wipe failed (continuing)", it) }
            }

            check(dest.mkdirs() || dest.isDirectory) { "Cannot create ${dest.absolutePath}" }

            // Build candidate URLs: user's custom mirror (Settings → "mirror") first,
            // then the built-in fallback list. Correct layout is
            // <base>/<major>/releases/<arch>/<distro>-<full-version>-<arch>.tar.gz
            // (a bare "3.20" 404s — Alpine publishes point releases like 3.20.3).
            val ver = ALPINE_VERSION
            val pathSuffix = "${majorVersion(ver)}/releases/${archName()}/$DEFAULT_DISTRO-$ver-${archName()}.tar.gz"
            // v0.1.9: prefs read is also guarded — a corrupted shared_prefs XML
            // throws on getSharedPreferences and previously escaped here in the
            // worker's child scope (uncaught → process death).
            val custom = runCatching {
                androidx.preference.PreferenceManager
                    .getDefaultSharedPreferences(ctx)
                    .getString("mirror", null)?.trim()?.takeIf { it.isNotBlank() }
            }.getOrNull()
            val bases = listOfNotNull(custom) + MIRRORS
            val tmp = tmpFile

            var lastErr: Throwable? = null
            var downloaded = false
            for (base in bases) {
                // Robust normalization: accept "…/alpine", "…/alpine/v", "…/alpine/v3.20"
                // or a full tarball URL from Settings → mirror, and always produce
                // <alpineBase>/<major>/releases/<arch>/<file>.
                val clean = base.trim().trimEnd('/')
                val url: URL = when {
                    clean.endsWith(".tar.gz") -> URL(clean)               // full file URL — use verbatim
                    clean.endsWith("/v") -> URL("$clean$pathSuffix")      // "…/alpine/v" + "3.20/releases/…"
                    // "…/alpine/v3.20" is a version directory: the tarball still lives
                    // under releases/<arch>/, so append that segment (was missing → 404).
                    Regex("/v[0-9]").containsMatchIn(clean) -> URL("$clean/releases/${archName()}/$DEFAULT_DISTRO-$ver-${archName()}.tar.gz")
                    else -> URL("$clean/${majorVersion(ver)}/releases/${archName()}/$DEFAULT_DISTRO-$ver-${archName()}.tar.gz")
                }
                report("Downloading rootfs (${url.host})", -1)
                try {
                    downloadTo(url, tmp) { pct -> report("Downloading rootfs", pct) }
                    downloaded = true
                    break
                } catch (e: Throwable) {
                    lastErr = e
                    tmp.delete()
                }
            }
            if (!downloaded) {
                // Surface the actual HTTP failure (incl. response code) so users can act on it.
                // lastErr is never an HttpURLConnection itself — unwrap the cause chain
                // to find one and surface its HTTP response code.
                val conn = generateSequence(lastErr) { it.cause }
                    .filterIsInstance<HttpURLConnection>()
                    .firstOrNull()
                val detail = conn?.let { "HTTP ${it.responseCode}" }
                    ?: lastErr?.toString() ?: "no attempt made"
                throw RuntimeException("All mirrors failed ($detail). Check Wi-Fi/mobile data and retry.", lastErr)
            }

            // Sanity-check what we downloaded: an HTML error page would otherwise
            // surface as a confusing "tar failed" message.
            val head = ByteArray(2)
            FileInputStream(tmp).use { it.read(head) }
            // gzip magic bytes are 0x1f 0x8b — the old check compared against 0x08,
            // which rejected every valid tarball ("Mirror returned non-gzip data").
            if (head[0] != 0x1f.toByte() || head[1] != 0x8b.toByte()) {
                throw RuntimeException("Mirror returned non-gzip data (${tmp.length()} bytes) — check network/mirror URL")
            }

            report("Extracting rootfs", -1)
            extractTarGz(tmp, dest)
            tmp.delete()

            // v0.1.13: the old single "Linking /data/data paths" frame covered
            // three distinct phases (special-file prune + mountpoint seeding,
            // resolver config, profile/os-release writes). Split into two
            // reported sub-steps so the UI visibly advances through them.
            //
            // v0.1.14 STALL FIX #3 — "stuck at Seeding/Sending resolver config".
            // The resolver-seeding block inside linkAndroidRuntime() used to run
            // *after* pruneSpecialFiles(dev): a bounded walk with a 10-second
            // wall-clock cap and a 4096-entry cap. On a rootfs left over from an
            // interrupted attempt, dev/ can hold tens of thousands of entries
            // (or be mid-corruption), so the prune silently gave up at its cap
            // and returned right when the time budget expired. Everything that
            // came after it in the same phase — including the resolv.conf seed
            // whose label was already on screen — then sat behind an operation
            // that had burned its whole budget doing nothing visible. Because
            // indeterminate progress frames only repaint when the NEXT step
            // starts, the UI froze at the resolver-config label exactly like the
            // earlier pre-wipe stall.
            // Fix:
            //  - reorder: the resolver seed + mountpoint dirs are cheap,
            //    metadata-only operations and now run FIRST, before any tree
            //    walk can eat time;
            //  - the dev/ prune is bounded harder (5 s) and wrapped in a hard
            //    watchdog thread so even a pathological filesystem hang cannot
            //    delay the next frame by more than a few seconds;
            //  - each remaining sub-step emits its own heartbeat frame
            //    ("Finalizing rootfs config", "Installing helper scripts") so
            //    no label ever has to cover multiple operations again.
            report("Linking /data/data paths", -1)
            seedResolverConfig(dest)          // fast, metadata-only — runs first now
            report("Pruning special files", -1)
            withHardTimeout(5_000L) { pruneSpecialFiles(File(dest, "dev"), maxMillis = 4_500L) }
            prepareMountpoints(dest)
            report("Finalizing rootfs config", -1)
            postInstall(dest)
            report("Installing helper scripts", -1)
            installHelperScripts(dest)

            FsLayout.markBootstrapped(ctx)
            emit(Result.success(Unit))
        } catch (e: Throwable) {
            // v0.1.8: surface the real reason to the observing UI through the
            // WorkManager progress data too (the Flow path only works in-process).
            // v0.1.10: use BootstrapDiagnostics so linkage errors show WHICH class
            // is missing instead of a bare "NoClassDefFoundError".
            com.emux.core.BootstrapDiagnostics.dumpFull(ctx, e)
            BootstrapReporter.publishError(
                ctx, com.emux.core.BootstrapDiagnostics.describe(e))
            emit(Result.failure(e))
        }
    }

    suspend fun bootstrap(): Result<Unit> = withContext(Dispatchers.IO) {
        // v0.1.10 CRITICAL FIX (found by decompiling the SHIPPED v0.1.9 dex):
        // BootstrapWorker calls mgr.bootstrap() WITHOUT passing a Context, so
        // `this` inside this lambda resolved to the LAMBDA RECEIVER — which is
        // not a Context — and Kotlin emitted an UNBOUND THIS REFERENCE to
        // android/content/Context in classes.dex. On devices where that class
        // fails late resolution (some OEM ROMs / direct-boot), the worker died
        // with NoClassDefFoundError the moment Install was tapped — exactly the
        // reported symptom. Every reference now goes through the explicit
        // `ctx` property; no implicit receiver use remains here.
        val appCtx = this@BootstrapManager.ctx
        var last: Result<Unit> = Result.failure(Exception("not started"))
        try {
            var sawProgress = false
            bootstrapFlow().collect { r ->
                // Progress emissions are encoded as Result.failure(BootstrapProgress);
                // they must never overwrite the real result, otherwise a *successful*
                // run still returned failure(the-last-progress-step), the worker
                // retried forever, and the UI printed "Bootstrap failed" over success.
                val c = r.exceptionOrNull()
                if (c is BootstrapProgress) { sawProgress = true; return@collect }
                last = r
            }
            if (last.isFailure && sawProgress && FsLayout.isBootstrapped(appCtx)) {
                Result.success(Unit)   // completed but final emission raced a progress frame
            } else last
        } catch (e: Throwable) {
            com.emux.core.BootstrapDiagnostics.dumpFull(appCtx, e)
            com.emux.EmuXLog.e("bootstrap() escape (guarded)", e)
            Result.failure(e)
        }
    }

    private fun downloadTo(url: URL, out: File, onPct: (Int) -> Unit) {
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        // Android's HttpURLConnection silently switches to GET on 3xx redirects; if
        // the server replies with a body *and* we had POSTed nothing this is fine,
        // but some captive-portal/mirror setups return 200+HTML. Surface real HTTP
        // errors instead of writing an error page to disk.
        val code = conn.responseCode
        if (code !in 200..299) {
            conn.disconnect()
            throw RuntimeException("Mirror returned HTTP $code for $url")
        }
        val total = conn.contentLength
        conn.inputStream.use { input ->
            out.outputStream().use { os ->
                val buf = ByteArray(64 * 1024) // 64 KiB: good balance of syscalls vs memory
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break          // real EOF; n==0 is legal, don't stop early
                    os.write(buf, 0, n)
                    done += n
                    if (total > 0) onPct(((done * 100) / total).toInt())
                }
                os.flush()
            }
        }
        conn.disconnect()
        if (out.length() == 0L) throw RuntimeException("Downloaded 0 bytes from $url")
    }

    /**
     * Extract the rootfs. v0.1.1 used `tar -xzf` directly — but many Android toybox
     * builds either lack `-z`, reject combined short flags, or are restricted for
     * unprivileged apps, which made extraction fail silently ("bootstrap failed,
     * nothing happens"). Now we try three strategies in order:
     *   1. sh pipeline: gzip -dc … | tar -x …  (works on every toybox/busybox)
     *   2. plain `tar -xzf`                     (full-featured tar, e.g. LineageOS)
     *   3. pure-Java GZIPInputStream + TarInputStream (no external binaries at all)
     */
    private fun extractTarGz(tgz: File, dest: File) {
        val cmds = listOf(
            arrayOf("/system/bin/sh", "-c", "gzip -dc '${tgz.absolutePath}' | tar -x -C '${dest.absolutePath}'"),
            arrayOf("tar", "-xzf", tgz.absolutePath, "-C", dest.absolutePath),
        )
        var lastErr = ""
        for (argv in cmds) {
            // v0.1.11 HANG GUARD: p.waitFor() with no timeout could block forever
            // if a toybox tar/gzip wedges on some ROMs (stuck process — the exact
            // class of "installation takes 5+ minutes" report). A watchdog thread
            // destroys the process after 3 minutes; destroy() unblocks both the
            // readLine loop and waitFor(), and we fall through to the next strategy.
            val r = runCatching {
                val p = ProcessBuilder(*argv).redirectErrorStream(true).start()
                val watchdog = Thread {
                    try { Thread.sleep(180_000); if (p.isAlive) p.destroy() } catch (_: Exception) {}
                }.apply { isDaemon = true; start() }
                try {
                    val out = StringBuilder()
                    BufferedReader(InputStreamReader(p.inputStream)).forEachLine { out.append(it).append('\n') }
                    val code = p.waitFor()
                    code to out.toString()
                } finally { watchdog.interrupt() }
            }
            // `continue` inside runCatching's inline lambda isn't allowed pre-2.2 —
            // handle the failure branch explicitly instead.
            if (r.isFailure) { lastErr = "spawn failed: ${r.exceptionOrNull()?.message}"; continue }
            val (code, out) = r.getOrThrow()
            if (code == 0 && File(dest, "etc").exists()) return
            lastErr = "exit $code: ${out.take(200)}"
        }
        // Strategy 3: no external binaries — stream through Java.
        runCatching { extractJava(tgz, dest) }
            .onFailure { throw RuntimeException("Extraction failed on this device. tar: $lastErr; java: ${it.message}") }
        if (!File(dest, "etc").exists()) throw RuntimeException("Extraction produced an incomplete rootfs (tar: $lastErr)")
    }

    /** Minimal streaming tar reader (ustar + gnu long names good enough for Alpine). */
    private fun extractJava(tgz: File, dest: File) {
        java.util.zip.GZIPInputStream(java.io.BufferedInputStream(FileInputStream(tgz), 64 * 1024))
            .buffered(64 * 1024).use { gz ->
            val hdr = ByteArray(512)
            var pendingLongName: String? = null
            while (true) {
                var read = 0
                while (read < 512) { val n = gz.read(hdr, read, 512 - read); if (n < 0) return; read += n }
                if (String(hdr, 0, 100).all { it == '\u0000' }) break // end-of-archive block
                val name0 = String(hdr, 0, 100).trim('\\', '\u0000')
                val sizeStr = String(hdr, 124, 12).trim('\\', '\u0000', ' ')
                val size = sizeStr.toLongOrNull(8) ?: 0L
                val type = hdr[156].toInt().toChar()
                val prefix = String(hdr, 345, 155).trim('\\', '\u0000')
                val name = pendingLongName ?: (if (prefix.isNotEmpty()) "$prefix/$name0" else name0)
                pendingLongName = null
                val target = File(dest, name).canonicalFile
                if (!target.path.startsWith(dest.canonicalFile.path + File.separator) && target != dest.canonicalFile) {
                    // path traversal guard (Alpine tars are clean, but be safe)
                    skip(gz, size); continue
                }
                when {
                    name0 == "././@LongLink" || name0 == "./@LongLink" /* GNU long name */ -> {
                        val long = ByteArray(size.toInt()); var o = 0
                        while (o < long.size) { val n = gz.read(long, o, long.size - o); if (n < 0) break; o += n }
                        pendingLongName = String(long, 0, o).trim('\u0000', '\n')
                        skip(gz, (512 - (size % 512)) % 512)
                        continue // the NEXT header carries this long name
                    }
                    type == '5' || name.endsWith("/") -> target.mkdirs()
                    type == '2' -> { /* symlink via android.system.Os (API 21+), best-effort */
                        val link = String(hdr, 157, 100).trim('\u0000')
                        target.parentFile?.mkdirs()
                        runCatching { android.system.Os.symlink(link, target.path) }
                        skip(gz, size)
                    }
                    type == '0' || type == '\u0000' || type == '1' -> {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { os -> var left = size; val buf = ByteArray(64 * 1024)
                            while (left > 0) { val n = gz.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; os.write(buf, 0, n); left -= n } }
                        if (type == '1') { /* hardlink: copy content from earlier entry not tracked; rare in minirootfs */ }
                        val mode = String(hdr, 100, 8).trim('\u0000', ' ').toIntOrNull(8) ?: 0
                        target.setExecutable(mode and 0x40 != 0, false)
                        skip(gz, (512 - (size % 512)) % 512)
                    }
                    else -> skip(gz, size)
                }
            }
        }
    }
    private fun skip(gz: java.io.InputStream, n: Long) { var left = n; val b = ByteArray(8192)
        while (left > 0) { val k = gz.read(b, 0, minOf(left, b.size.toLong()).toInt()); if (k < 0) break; left -= k } }

    /**
     * Copy the helper scripts from APK assets into the rootfs and make them
     * executable. Without this, TerminalSession's launch command
     * (`$rootfs/emux-init.sh …`) points at a non-existent file and every shell
     * dies instantly — which looked like "bootstrap succeeded but nothing happens".
     */
    private fun installHelperScripts(dest: File) {
        listOf("emux-init.sh", "emux-x11", "emux-capture").forEach { name ->
            try {
                ctx.assets.open("bin/$name").use { input ->
                    val out = File(dest, name)
                    out.outputStream().use { input.copyTo(it) }
                    out.setExecutable(true, false)
                    out.setReadable(true, false)
                }
            } catch (e: Exception) {
                throw RuntimeException("Missing asset bin/$name in APK", e)
            }
        }
        // Also stage them under $HOME so they're on PATH-ish locations for users.
        try {
            val home = FsLayout.home(ctx)
            listOf("emux-x11", "emux-capture").forEach { name ->
                val src = File(dest, name)
                if (src.exists()) src.copyTo(File(home, name), overwrite = true)
            }
        } catch (_: Exception) { /* convenience only, non-fatal */ }
    }

    /**
     * v0.1.14 SPLIT: pre-create the mountpoint dirs the session launcher binds
     * onto (/proc /sys /dev /tmp /run /root /home). Plain mkdirs — no
     * enumeration, cannot block. Extracted from linkAndroidRuntime(), which in
     * turn is kept as a thin wrapper over these three steps for any legacy
     * call sites and tests.
     */
    internal fun prepareMountpoints(dest: File) {
        listOf("proc", "sys", "dev", "tmp", "run", "root", "home").forEach {
            File(dest, it).mkdirs()
        }
        File(dest, "tmp").setWritable(true, false)
    }

    /**
     * Make the chroot usable without root: bind-free access to the Android DNS
     * resolver and a minimal /proc,/sys,/dev populated at session start instead.
     * v0.1.14: this is now just the ordered composition of the three cheap,
     * individually-reported sub-steps (resolver seed → dev/ prune → mountpoint
     * dirs). The old monolithic version ran the bounded-but-slow dev/ prune
     * FIRST and left the resolv.conf seed behind it on screen with no new
     * progress frame until everything finished — the exact mechanism of the
     * "stuck at Seeding resolver config" stall. bootstrapFlow() now calls the
     * pieces directly so each one gets its own heartbeat; this wrapper remains
     * for completeness/legacy callers.
     */
    internal fun linkAndroidRuntime(dest: File) {
        // v0.1.13 STALL FIX #2 — special files under dev/ blocking later
        // enumeration. Alpine's minirootfs ships /dev entries; on the pure-Java
        // extraction fallback they arrive as fifos/links. Any subsequent
        // recursive scan (or our own cleanup) that *opens* them can block
        // forever on an empty FIFO reader. pruneSpecialFiles only ever
        // lstats/unlinks — it never opens anything — and here it additionally
        // runs under withHardTimeout so even a kernel-level readdir hang can't
        // gate progress.
        seedResolverConfig(dest)
        withHardTimeout(5_000L) { pruneSpecialFiles(File(dest, "dev"), maxMillis = 4_500L) }
        prepareMountpoints(dest)
    }

    /**
     * v0.1.14: run [block] on a daemon thread and wait for it at most
     * [millis] ms. If the operation exceeds its budget we return anyway and
     * let the orphaned daemon finish (or die) in the background — bootstrap
     * progress must NEVER be gated on a filesystem walk again. This is the
     * hard backstop behind pruneSpecialFiles' own cooperative time cap: even
     * if a single readdir/lstat blocks indefinitely in kernel space (bad
     * f2fs state, hung fuse-like mount), the next progress frame lands within
     * [millis]. Best-effort semantics apply to everything it wraps, so losing
     * the result of a timed-out walk is acceptable by design.
     */
    private fun withHardTimeout(millis: Long, block: () -> Unit) {
        val done = java.util.concurrent.CountDownLatch(1)
        val t = Thread {
            try { runCatching { block() } } finally { done.countDown() }
        }.apply { isDaemon = true; name = "emux-bootstrap-timeout" }
        t.start()
        try {
            done.await(millis, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * v0.1.14 SPLIT: seed etc/resolv.conf from Android. Extracted out of
     * linkAndroidRuntime() so it gets its OWN progress frame and runs before
     * any tree walk — this block is metadata-only plus one tiny file write,
     * worst case milliseconds. (The old placement — after the dev/ prune — is
     * what made the UI sit at "Seeding resolver config" while the prune burned
     * its time budget doing nothing visible.)
     */
    internal fun seedResolverConfig(dest: File) {
        // Seed resolv.conf from Android's DNS properties (net.dns1/net.dns2 —
        // readable by any app).
        // v0.1.11 HANG FIX: this used to enumerate interfaces with
        //   Collections.list(NetworkInterface.getNetworkInterfaces())
        // and then threw every element away via `flatMap { emptyList() }`.
        // On many OEM ROMs that netlink enumeration blocks for minutes (or
        // indefinitely when a radio/tether iface is mid-transition), which froze
        // the bootstrap at "Linking /data/data paths" for 5+ minutes with no
        // progress — exactly the reported symptom. The result was *always* empty
        // anyway (the flatMap discarded everything), so the whole call was pure
        // dead weight; SystemProperties gives us real DNS without touching the
        // kernel netlink socket. If even that reflection fails, we fall back to
        // public resolvers. Total worst case here: milliseconds.
        // v0.1.13 THROW FIX: the write itself is now guarded too. If etc/ is
        // missing or etc/resolv.conf ended up as a dangling symlink after the
        // tar, the old unguarded writeText threw FileNotFoundException right
        // inside the linking phase and aborted the whole step ("not passing").
        // We mkdir parents first and delete-and-recreate special/dangling
        // targets before writing.
        try {
            val propDns = try {
                val cls = Class.forName("android.os.SystemProperties")
                val get = cls.getMethod("get", String::class.java)
                listOfNotNull(
                    get.invoke(null, "net.dns1") as? String,
                    get.invoke(null, "net.dns2") as? String,
                ).filter { it.isNotBlank() }.distinct()
            } catch (_: Exception) { emptyList() }
            val servers = propDns.ifEmpty { listOf("8.8.8.8", "1.1.1.1") }
            val rc = File(dest, "etc/resolv.conf")
            runCatching { rc.parentFile?.mkdirs() }
            replaceRegularFileWith(rc) {
                it.writeText(servers.joinToString("\n") { s -> "nameserver $s\n" })
            }
        } catch (_: Exception) { /* non-fatal */ }
    }

    /**
     * v0.1.13: ensure [f] exists as a fresh writable REGULAR file before running
     * [write] against it. Guards against the three shapes that made the old
     * unguarded writeText()/appendText() calls throw FileNotFoundException and
     * kill bootstrap mid-linking: a missing parent dir, a dangling symlink
     * (exists() == false but a link occupies the name), and special files
     * (fifo/socket/device) left over from a previous partial extract. All
     * operations are lstat/unlink based (java.nio) — nothing is ever opened,
     * so this cannot block on a FIFO either. Returns true when the write ran.
     */
    private fun replaceRegularFileWith(f: File, write: (File) -> Unit): Boolean {
        return try {
            f.parentFile?.mkdirs()
            val p = f.toPath()
            val attrs = runCatching {
                java.nio.file.Files.readAttributes(
                    p, java.nio.file.attribute.BasicFileAttributes::class.java,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)
            }.getOrNull()
            // Anything that isn't a plain regular file (symlink, fifo, socket,
            // device node) gets deleted first, so the write below always lands
            // on a brand-new regular file.
            if (attrs != null && !attrs.isRegularFile) runCatching { java.nio.file.Files.deleteIfExists(p) }
            write(f)
            true
        } catch (e: Throwable) {
            com.emux.EmuXLog.e("replaceRegularFileWith(${f.name}) failed (non-fatal)", e)
            false
        }
    }

    /**
     * v0.1.13: bounded, NON-OPENING prune of symlinks and non-regular files
     * under [root] (Alpine's /dev is full of them after the pure-Java tar
     * fallback: char/block device nodes, FIFOs, dangling links). Opening any of
     * those for read/write can block forever (classic empty-FIFO reader stall),
     * and Kotlin's File.walkTopDown()/deleteRecursively() enumerate deeply, so
     * we do an explicit-stack BFS instead:
     *   - every entry is inspected with java.nio lstat (NOFOLLOW_LINKS) — the
     *     target of a symlink is never resolved, nothing is ever opened;
     *   - hard caps (entries scanned / depth / wall time) guarantee termination
     *     even on a pathological tree;
     *   - directories are descended into, everything else non-regular/symlink
     *     is unlinked immediately (which also frees space for the re-extract).
     * Best-effort: individual failures are swallowed; the walk always returns.
     */
    private fun pruneSpecialFiles(root: File, maxEntries: Int = 4096, maxMillis: Long = 10_000L) {
        val start = SystemClockElapsed()
        var seen = 0
        val queue = ArrayDeque<java.nio.file.Path>()
        val rootP = root.toPath()
        if (!java.nio.file.Files.isDirectory(rootP)) return
        queue.addLast(rootP)
        while (queue.isNotEmpty() && seen < maxEntries && SystemClockElapsed() - start < maxMillis) {
            val dir = queue.removeFirst()
            try {
                java.nio.file.Files.newDirectoryStream(dir).use { ds ->
                    for (child in ds) {
                        if (++seen > maxEntries) return
                        val attrs = runCatching {
                            java.nio.file.Files.readAttributes(
                                child, java.nio.file.attribute.BasicFileAttributes::class.java,
                                java.nio.file.LinkOption.NOFOLLOW_LINKS)
                        }.getOrNull() ?: continue   // vanished concurrently
                        when {
                            attrs.isDirectory -> queue.addLast(child)
                            // symlink / fifo / socket / device node → unlink,
                            // never open, never follow.
                            else -> runCatching { java.nio.file.Files.deleteIfExists(child) }
                        }
                    }
                }
            } catch (_: Throwable) { /* unreadable dir: skip it, keep walking */ }
        }
    }

    /** Monotonic-ish elapsed ms; isolated so JVM unit tests can stub the class. */
    private fun SystemClockElapsed(): Long = try {
        android.os.SystemClock.elapsedRealtime()
    } catch (_: Throwable) {
        System.currentTimeMillis()
    }

    /**
     * v0.1.13 STALL FIX companion to the pre-wipe: bottom-up recursive delete
     * WITHOUT Kotlin's deleteRecursively(). Why not deleteRecursively()? It
     * fully enumerates the whole tree into memory BEFORE deleting its first
     * entry — on ext4/f2fs re-walking a large partially-extracted rootfs costs
     * minutes of readdir+stat syscalls, all invisible to the UI (no repaint
     * happens mid-step), which is exactly the "stuck at Extracting rootfs /
     * Linking…" freeze. deleteBottomUp() instead:
     *   - uses an EXPLICIT stack (no recursion → no StackOverflowError on deep
     *     trees);
     *   - lstats each entry through java.nio with NOFOLLOW_LINKS, so FIFOs and
     *     device nodes are identified and unlinked WITHOUT EVER BEING OPENED
     *     (opening a FIFO with no writer blocks forever — java.io.File APIs
     *     don't open on stat, but any accidental read would; NIO keeps it
     *     strictly metadata-only);
     *   - deletes leaves as it descends and rmdirs deepest-first, so total work
     *     is one pass and progress is monotone;
     *   - is bounded (entry cap + time cap) and best-effort: whatever survives
     *     is simply overwritten by the fresh extraction afterwards.
     * Returns the number of entries removed.
     */
    internal fun deleteBottomUp(top: File, maxMillis: Long = 60_000L): Int {
        val start = SystemClockElapsed()
        var removed = 0
        val topP = top.toPath()
        if (!runCatching { java.nio.file.Files.exists(topP) }.getOrDefault(false)) return 0
        if (!runCatching { java.nio.file.Files.isDirectory(topP) }.getOrDefault(false)) {
            runCatching { java.nio.file.Files.deleteIfExists(topP) }
            return 1
        }
        // Explicit DFS stack. Each frame is (dir, childrenIterator); the stream
        // backing the iterator stays referenced by its frame and is closed when
        // the frame pops — so no fd leaks even on huge trees. Frames are MUTATED
        // in place (iter/ds filled on first visit) so a parent's position never
        // shifts out from under its live DirectoryStream iterator.
        val stack = ArrayDeque<DeleteFrame>()
        stack.addLast(DeleteFrame(topP))
        outer@ while (stack.isNotEmpty()) {
            if (SystemClockElapsed() - start > maxMillis) break
            val frame = stack.last()
            if (frame.iter == null) {
                // First visit: open the child stream lazily (bounded reads).
                try {
                    val ds = java.nio.file.Files.newDirectoryStream(frame.dir)
                    frame.ds = ds
                    frame.iter = ds.iterator()
                } catch (_: Throwable) {
                    frame.iter = EmptyIter   // unreadable dir → treated as empty, rmdir'd below
                }
            }
            val itNow = frame.iter ?: EmptyIter
            var descended = false
            while (itNow.hasNext()) {
                if (SystemClockElapsed() - start > maxMillis) break@outer
                val child = itNow.next()
                val attrs = runCatching {
                    java.nio.file.Files.readAttributes(
                        child, java.nio.file.attribute.BasicFileAttributes::class.java,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS)
                }.getOrNull()
                when {
                    attrs == null -> removed++                        // vanished concurrently
                    attrs.isDirectory -> {                            // descend; parent frame stays
                        stack.addLast(DeleteFrame(child))
                        descended = true
                    }
                    else -> {   // regular / symlink / fifo / socket / device node
                        runCatching { java.nio.file.Files.deleteIfExists(child) }
                        removed++
                    }
                }
                if (descended) break          // process the new subtree first
            }
            if (!descended) {
                // Frame exhausted → rmdir the (now-empty) directory deepest-first.
                stack.removeLast().close()
                runCatching { java.nio.file.Files.deleteIfExists(frame.dir) }
                    .onFailure { runCatching { frame.dir.toFile().delete() } }
                removed++
            }
        }
        // Unwind any frames left on the stack after a time-cap break (closes fds).
        while (stack.isNotEmpty()) stack.removeLast().close()
        // Belt & braces: whatever the bounded walk left behind (cap hit), sweep
        // the top level non-recursively so extraction starts from a clean slate.
        // Never recursive, never blocking — lstat/delete only.
        runCatching {
            java.nio.file.Files.newDirectoryStream(topP).use { ds ->
                for (child in ds) runCatching { java.nio.file.Files.deleteIfExists(child) }
            }
        }
        return removed
    }

    /** One explicit-stack frame of deleteBottomUp; owns its DirectoryStream. */
    private class DeleteFrame(
        val dir: java.nio.file.Path,
        var ds: java.nio.file.DirectoryStream<java.nio.file.Path>? = null,
        var iter: Iterator<java.nio.file.Path>? = null,
    ) {
        fun close() { runCatching { ds?.close() } }
    }

    /** Shared empty iterator so unreadable dirs cost zero allocations. */
    private object EmptyIter : Iterator<java.nio.file.Path> {
        override fun hasNext() = false
        override fun next(): java.nio.file.Path = throw NoSuchElementException()
    }

    private fun postInstall(dest: File) {
        // sane defaults: non-login shells get our env, disable services that need init
        // v0.1.13 THROW FIX: both writes are guarded now. The old code did a bare
        // writeText() on etc/profile.d/emux.sh and — worse — an unguarded
        // File(dest,"etc/os-release").appendText("\nEMUX=1\n"), which throws
        // FileNotFoundException when os-release is missing or left as a dangling
        // symlink by the tar fallback, killing bootstrap right in the linking
        // phase ("step not passing"). Parents are mkdir'd first, special/dangling
        // targets are deleted-and-recreated via replaceRegularFileWith(), and the
        // EMUX=1 marker is appended IDEMPOTENTLY (no duplicate on retry).
        val profile = File(dest, "etc/profile.d/emux.sh")
        replaceRegularFileWith(profile) {
            it.writeText(
                """
                export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
                export PS1='\[\e[38;5;39m\]emux:\w\[\e[0m\] \$ '
                alias ll='ls -alF'
                # apk wrapper that works without root inside proot/chroot-lite
                alias apk='apk --no-interactive'
                """.trimIndent()
            )
        }

        val osr = File(dest, "etc/os-release")
        runCatching {
            // Delete-and-recreate special/dangling shapes first (never opens them),
            // then ensure a regular file exists so append can never throw.
            replaceRegularFileWith(osr) { f ->
                if (!f.exists()) {
                    f.parentFile?.mkdirs()
                    f.writeText("NAME=\"Alpine Linux\"\nID=alpine\n")
                } else if (f.length() == 0L) {
                    f.writeText("NAME=\"Alpine Linux\"\nID=alpine\n")
                }
            }
            // Idempotent marker: only append when absent, so repeated bootstraps
            // / retries never pile up "EMUX=1" lines.
            val content = runCatching { osr.readText() }.getOrNull()
            if (content != null && !Regex("(?m)^EMUX=1$").containsMatchIn(content)) {
                runCatching { osr.appendText("\nEMUX=1\n") }
            }
        }.onFailure { com.emux.EmuXLog.e("os-release marker failed (non-fatal)", it) }
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
