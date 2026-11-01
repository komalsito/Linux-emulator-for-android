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
        // v0.1.15: hard cap for helper-script asset copies (real ones are <1 KiB).
        const val MAX_ASSET_BYTES = 1_048_576L

        /**
         * v0.1.53: guest-side dpkg/apt repair (inlined twin of the
         * emux-apt-repair helper so rootfs trees seeded by older APKs are
         * covered too). Order matters: preload shim -> drop stale backup ->
         * configure -a -> journal quarantine + retry -> fix-broken. Always
         * exits 0; callers treat it as hygiene, never as a gate.
         */
        /**
         * v0.1.63: guest-side self-check written to /tmp/emux_selfcheck (host
         * visible at <rootfs>/tmp/emux_selfcheck) and echoed to the repair
         * run's stderr: does the link shim actually load, does link(2)
         * succeed as fake root, and who owns /var/lib/dpkg.
         */
        private const val APT_SELFCHECK_PAYLOAD =
            // v0.1.72: load-time shim self-test first - independent of ANY
            // distro package (the old ln-based probe measured the missing
            // coreutils, not the syscall): REAL_OK / COPY_OK / FAIL_<errno>.
            "EMUX_LINKFIX_SELFTEST=1 LD_PRELOAD=/usr/local/lib/emux/libemux-linkfix.so /bin/true 2>/dev/null; " +
            "cat /tmp/emux_shimtest 2>/dev/null; " +
            "{ echo \"== emux dpkg selfcheck ==\"; " +
            // v0.1.85: shim v7 bind selftest (BINDINET_/BINDUNIXP_/BINDABS_
            // + errno) - the exact errnos of the guest bind failures.
            "cat /tmp/emux_shimtest 2>/dev/null; " +
            "ls -l /usr/local/lib/emux/ 2>&1; " +
            "cat /etc/ld.so.preload 2>&1; " +
            // v0.1.78: which shim generation is ON DISK (grepped from the .so)
            // - proves the device carries the v6 blob, not a stale one.
            "grep -a -o 'emux-linkfix-v[0-9]*' /usr/local/lib/emux/libemux-linkfix.so 2>/dev/null | head -1; " +
            "if grep -q libemux-linkfix /proc/self/maps; then echo SHIM_LOADED; else echo SHIM_NOT_LOADED; fi; " +
            "rm -f /var/lib/dpkg/status-old /etc/passwd.lock /etc/shadow.lock /etc/group.lock /etc/gshadow.lock; " +
            // v0.1.71: the ln-based probe needs coreutils... which is what we
            // are trying to install; report its absence instead of a bogus
            // LINK_FAIL, and keep the errno text marked so the pane shows it.
            "if ! command -v ln >/dev/null 2>&1; then echo LINK_UNTESTED_NO_LN; " +
            "elif ln /var/lib/dpkg/status /var/lib/dpkg/status-old 2>/tmp/link_err; then echo LINK_OK; " +
            "else echo LINK_FAIL:; echo LINKERR:; cat /tmp/link_err; fi; " +
            "rm -f /var/lib/dpkg/status-old /etc/passwd.lock /etc/shadow.lock /etc/group.lock /etc/gshadow.lock; " +
            "stat -c 'dpkgdir u=%u m=%a' /var/lib/dpkg 2>&1; " +
            "stat -c 'status u=%u m=%a' /var/lib/dpkg/status 2>&1; " +
            "cat /tmp/emux_dbswap 2>/dev/null; " +
            "id -u; } > /tmp/emux_selfcheck 2>&1; cat /tmp/emux_selfcheck >&2"

                /**
         * v0.1.78: generic GHOST-PACKAGE purge. An interrupted transaction (or
         * a killed extraction) leaves a package "install ok installed" in the
         * dpkg db while its files are GONE; apt-get install then NO-OPs on it
         * ("already the newest version") and every cascade/retry loop waits
         * for a binary that can never appear - the v0.1.76/77 field pane
         * (xvfb transaction aborted on the coreutils upgrade, /usr/bin/Xvfb
         * absent forever). Purging the db entry makes the next install really
         * unpack. v0.1.77 only purged coreutils/util-linux here, and nothing
         * in the desktop cascade - this table covers the desktop-critical
         * packages too. Shell twin lives in assets/bin/emux-apt-repair and
         * emux-x11 ghost_purge(): keep the package:marker table identical.
         */
        /** v0.1.99: non-blocking ld.so.cache builder, mkdir-locked. */
        private const val LDCONFIG_GATE_LINE =
            "if [ ! -s /etc/ld.so.cache ] && mkdir /tmp/.ldcfg.lock 2>/dev/null; then " +
            "(ldconfig 2>/dev/null; rmdir /tmp/.ldcfg.lock 2>/dev/null) & fi\n"

        private const val GHOST_PURGE_PAYLOAD =
            "for pair in " +
            "\"coreutils:/usr/bin/id\" \"util-linux:/usr/bin/script\" \"bash:/bin/bash\" " +
            "\"xvfb:/usr/bin/Xvfb\" \"xserver-common:/etc/X11/Xsession\" " +
            "\"x11-xkb-utils:/usr/bin/xkbcomp\" \"xfonts-base:/usr/share/fonts/X11/misc\" " +
            "\"x11-apps:/usr/bin/xwd\" \"netpbm:/usr/bin/pnmtoplainpnm\" " +
            "\"netcat-openbsd:/bin/nc.openbsd\" \"openbox:/usr/bin/openbox\" " +
            "\"xterm:/usr/bin/xterm\" ; do " +
            "gp=\${pair%%:*}; gm=\${pair#*:}; " +
            "if [ ! -e \"\$gm\" ] && dpkg -s \"\$gp\" 2>/dev/null | grep -q \"install ok installed\"; then " +
            "echo \"emux-repair: ghost package \$gp (db installed, \$gm missing) - purging\" >&2; " +
            "dpkg --remove --force-all \"\$gp\" >&2 || true; fi; done; "

        private const val APT_REPAIR_PAYLOAD =
            // v0.1.94 PERF: build the dynamic-linker cache FIRST. An interrupted
            // transaction leaves libc-bin half-configured = NO /etc/ld.so.cache
            // = every guest exec crawls the default lib paths (the "terminal
            // slow until you reopen the app" symptom: reopen is fast because
            // the repair finished the cache in between). ldconfig alone takes
            // ~1 s and unblocks everything that follows.
            "ldconfig >/dev/null 2>&1 || true; " +
            // v0.1.70: belt INSIDE the guest shell (the path exists only there).
            "[ -f /usr/local/lib/emux/libemux-linkfix.so ] && export LD_PRELOAD=/usr/local/lib/emux/libemux-linkfix.so; " +
            "export DEBIAN_FRONTEND=noninteractive; " +
            "SHIM=/usr/local/lib/emux/libemux-linkfix.so; " +
            "if [ -f \"\$SHIM\" ]; then grep -qsx \"\$SHIM\" /etc/ld.so.preload || echo \"\$SHIM\" >> /etc/ld.so.preload; fi; " +
            "rm -f /var/lib/dpkg/status-old /etc/passwd.lock /etc/shadow.lock /etc/group.lock /etc/gshadow.lock; " +
            "if ! dpkg --configure -a >&2; then " +
            "mkdir -p /var/lib/dpkg/updates.emux-broken 2>/dev/null; " +
            "for f in /var/lib/dpkg/updates/*; do [ -e \"\$f\" ] && mv \"\$f\" /var/lib/dpkg/updates.emux-broken/ 2>/dev/null; done; " +
            "rm -f /var/lib/dpkg/status-old; dpkg --configure -a >&2 || true; fi; " +
            // v0.1.78: generic ghost purge (was: two hardcoded coreutils/
            // util-linux checks in v0.1.77) + base-package reinstall so a
            // purged coreutils/util-linux/bash lands again right away (the
            // terminal needs them before any desktop cascade runs), + settle
            // pass - all in parity with assets/bin/emux-apt-repair.
            GHOST_PURGE_PAYLOAD +
            "apt-get install -y -f --no-install-recommends >&2 || true; " +
            "apt-get install -y --no-install-recommends coreutils util-linux bash >&2 || true; " +
            "i=0; while [ \$i -lt 2 ]; do dpkg --configure -a >&2 && break; i=\$((i+1)); done; " +
            "date +%s > /var/lib/emux-repair.stamp 2>/dev/null || true; " +
            APT_SELFCHECK_PAYLOAD

@Volatile private var startupRepairStarted = false

        /**
         * v0.1.54: in-process mutex for proot apt/dpkg runs. startupRepairAsync
         * (daemon thread at app start) and a bootstrap extras pass could both
         * run apt inside proot at once; the second dies on the dpkg lock and
         * (on the desktop cascade path) bricked the Xvfb install -> "X server
         * exited (code 1)". Guest-side retries in emux-x11 cover foreign apt
         * runs; this covers our own.
         */
        /**
         * v0.2.49 FIELD FIX: reap ORPHANED guests (PPid == 1, our uid).
         * Field: "apt-get: fatal error, failed to fork" - forty-eight
         * rounds of SIGHUP-immortal leftovers (proot shells, apt/dpkg
         * remnants, nc pumps, tracer-less tracees) accumulated until
         * fork() hit the process/memory ceiling on a 1 GB device.
         * Current-session processes are parented by the app, never by
         * init, so PPid==1 is a precise orphan filter; the pattern set
         * is widened to the apt/nc/script remnants earlier sweeps missed.
         */
        internal fun reapOrphanGuests(log: (String) -> Unit = {}) {
            runCatching {
                val myUid = android.os.Process.myUid().toString()
                val pat = Regex("proot|Xvfb|Xorg|emux-|xterm|xclock|xeyes|openbox|" +
                    "tint2|pcmanfm|mousepad|apt-get|dpkg|/nc|script")
                java.io.File("/proc").listFiles()?.forEach { d ->
                    val pid = d.name.toIntOrNull() ?: return@forEach
                    val st = runCatching { java.io.File(d, "status").readLines() }.getOrDefault(emptyList())
                    val ppid = st.firstOrNull { it.startsWith("PPid:") }
                        ?.split(Regex("\\s+"))?.getOrNull(1) ?: return@forEach
                    if (ppid != "1") return@forEach
                    val uidLine = st.firstOrNull { it.startsWith("Uid:") } ?: return@forEach
                    if (uidLine.split(Regex("\\s+")).getOrNull(1) != myUid) return@forEach
                    val cmd = runCatching {
                        java.io.File(d, "cmdline").readBytes().decodeToString().replace('\u0000', ' ')
                    }.getOrDefault("")
                    if (pat.containsMatchIn(cmd)) {
                        log("reaping orphan pid $pid: ${cmd.take(60)}")
                        android.os.Process.killProcess(pid)
                    }
                }
            }
        }

        private val aptLockObj = java.util.concurrent.locks.ReentrantLock()
        internal fun aptLock(): java.util.concurrent.locks.ReentrantLock = aptLockObj

        /**
         * v0.2.1 FIELD FIX: live progress sink for long proot/apt runs. The
         * v0.2.0 desktop hang was SILENT - desktopPreRepair() can legally run
         * for minutes (90 s lock wait + dpkg repair + X-core apt install) and
         * the pane emitted nothing in that window, so the field could not
         * tell "working" from "wedged". DesktopService wires this to noteInfo.
         */
        @Volatile var progressNote: ((String) -> Unit)? = null
        private var lastProgressAt = 0L
        internal fun progressThrottled(msg: String, minGapMs: Long = 20_000L) {
            val now = System.currentTimeMillis()
            if (now - lastProgressAt < minGapMs) return
            lastProgressAt = now
            progressNote?.invoke(msg)
        }
        // NOTE: Alpine publishes point releases as .0 only (3.20.0, 3.20.3 …);
        // a bare "3.20" produces a 404 URL — that was the bootstrap failure.
        const val ALPINE_VERSION = "3.20.3"

        /** Mirrors tried in order — dlcdn occasionally has DNS/CDN hiccups. */
        val MIRRORS = listOf(
            "https://dlcdn.alpinelinux.org/alpine/v",
            "https://mirror.leaseweb.com/alpine/v",
            // v0.1.30: TUNA (Tsinghua) mirror — dlcdn/leaseweb can be slow or
            // unreachable from mainland-China networks (HarmonyOS field report);
            // also used as a rotation target for the apk repositories.
            "https://mirrors.tuna.tsinghua.edu.cn/alpine/v",
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

        // ---- v0.1.16 single-pass machinery (process-wide state) ------------
        // Process-wide because the W-M worker and the in-process runner use
        // DIFFERENT BootstrapManager instances — instance state would not dedupe.
        val activeLock = Any()
        @Volatile private var activeId: Long = 0L
        @Volatile private var activeSlot: Slot? = null
        // Monotonic progress floor: a later phase may never be overwritten by
        // an earlier phase's label (guards against any residual label hijack).
        val progressLock = Any()
        private var lastPhaseRank = -1
        private var lastPhasePassId = -1L

        /**
         * v0.1.16 STALL FIX #5b — PROCESS-WIDE PASS FLOOR. Once a pass reaches a
         * terminal state, every pass with a LOWER id is permanently stale: it
         * must neither run nor emit progress frames. Without this, a straggler
         * collector (e.g. MainActivity's 8-second safety net attaching after the
         * WorkManager pass already finished) could acquire a fresh OWNER slot
         * and re-announce "Resolving mirror"/"Downloading rootfs"/"Installing
         * helper scripts", hijacking the UI back onto an old label — the exact
         * "helper scripts taking forever" symptom reported for v0.1.15.
         */
        @Volatile private var completedFloorId: Long = 0L
        fun isStalePass(id: Long): Boolean = id <= completedFloorId
        fun raiseFloorTo(id: Long) = synchronized(activeLock) {
            if (id > completedFloorId) completedFloorId = id
        }

        /** Order of bootstrap phases; unknown/pct updates map to the current rank. */
        private val PHASES = listOf(
            "Resolving mirror",
            "Downloading rootfs",
            "Cleaning previous rootfs",
            "Extracting rootfs",
            "Linking /data/data paths",
            "Pruning special files",
            "Finalizing rootfs config",
            "Installing helper script",      // prefix-match (per-script frames)
            "Seeding native bridge",
            "Installing shell components",
            "Installing desktop components",
            "Bootstrapped",
        )

        fun phaseRank(step: String): Int {
            for (i in PHASES.indices) {
                if (step.startsWith(PHASES[i])) return i
            }
            return -1
        }

        internal fun nextPassId(): Long = synchronized(activeLock) { ++activeId }
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
        //
        // v0.1.16 STALL FIX #5 — CONCURRENT-PASS PROGRESS HIJACK (the reason
        // "Installing helper scripts" STILL looked stuck after fix #4). The
        // WorkManager pass and MainActivity's 8-second in-process safety net
        // (ensureBootstrapCompletes → BootstrapRunner.run) each started their
        // OWN full download+extract pass against the same dest/ and tarball.
        // The second pass re-announced every earlier label through the shared
        // LiveData mirror, overwriting the leader's newer labels — so the UI
        // froze on whatever label the straggler happened to be at, forever,
        // while both passes fought over rootfs.tar.gz mid-extraction.
        // Now exactly ONE pass ever runs per process: late collectors ATTACH to
        // the in-flight pass instead of starting a duplicate.
        val self = this@BootstrapManager
        val listener: (Result<Unit>) -> Unit = { res -> trySend(res) }
        val slot = self.acquireSlot(listener)
        when (slot.role) {
            Slot.Role.ATTACHED -> { /* leader drives our channel; just wait */ }
            // v0.1.16 STALL FIX #5b: a terminal pass already completed in this
            // process — acquireSlot short-circuited and delivered success via
            // `emit` above. Never start a duplicate download/extract pass.
            Slot.Role.STALE -> { /* result already delivered; nothing to run */ }
            Slot.Role.OWNER -> {
                val ioScope = kotlinx.coroutines.CoroutineScope(
                    kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
                ioScope.launch {
                    runCatching { self.runBootstrapInternal(slot) }
                        .onFailure { e ->
                            com.emux.EmuXLog.e("bootstrap internal escape (guarded)", e)
                            BootstrapReporter.publishError(
                                self.ctx, "${e.javaClass.simpleName}: ${e.message ?: "no message"}")
                            slot.finish(Result.failure(e))
                        }
                }
            }
        }
        awaitClose { self.releaseSlot(slot, listener) }
    }

    // ---- v0.1.16 single-pass machinery ------------------------------------

    /** One in-flight bootstrap pass; fan-outs results to every attached flow. */
    private class Slot(val id: Long) {
        enum class Role { OWNER, ATTACHED, STALE }
        var role: Role = Role.OWNER
        var attachedTo: Slot? = null
        private val listeners = java.util.concurrent.CopyOnWriteArrayList<(Result<Unit>) -> Unit>()
        fun addListener(l: (Result<Unit>) -> Unit) { listeners.add(l) }
        fun removeListener(l: (Result<Unit>) -> Unit) { listeners.remove(l) }
        @Volatile private var terminalDelivered = false
        fun broadcast(r: Result<Unit>) { listeners.forEach { l -> runCatching { l(r) } } }
        /** Deliver the terminal result once, then mark the slot finished. */
        fun finish(r: Result<Unit>) {
            if (r.isSuccess || r.exceptionOrNull() !is BootstrapProgress) {
                synchronized(this) {
                    if (!terminalDelivered) {
                        terminalDelivered = true
                        // v0.1.16: raise the process-wide floor BEFORE fanning
                        // out so any straggler collector that reacts to this
                        // terminal event immediately sees its own pass as stale.
                        if (id > 0L) raiseFloorTo(id)
                        broadcast(r)
                    }
                    finished.set(true)
                }
                // Clear the process-wide slot so the next call starts a fresh
                // pass (a completed rootfs short-circuits anyway via
                // isBootstrapped; this only matters for retry passes).
                synchronized(activeLock) { if (activeSlot === this) activeSlot = null }
            } else broadcast(r)   // progress frame: fan out to all collectors
        }
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    private fun acquireSlot(emit: (Result<Unit>) -> Unit): Slot = synchronized(activeLock) {
        val cur = activeSlot
        if (cur != null && !cur.finished.get()) {
            // Late collector: attach its channel listener to the leader pass and
            // do NOT start a duplicate download/extract.
            cur.addListener(emit)
            Slot(-1L).also { it.role = Slot.Role.ATTACHED; it.attachedTo = cur }
        } else {
            val s = Slot(++activeId)
            // v0.1.16 STALL FIX #5b: if a terminal pass with id >= our new id
            // already completed (e.g. the WorkManager pass won the race against
            // MainActivity's 8-second safety net), this caller is late — do NOT
            // start a duplicate full pass. Deliver success immediately instead.
            if (isStalePass(s.id)) {
                s.role = Slot.Role.STALE
                emit(Result.success(Unit))
                return@synchronized s
            }
            s.addListener(emit)
            activeSlot = s
            s
        }
    }

    private fun releaseSlot(slot: Slot, listener: (Result<Unit>) -> Unit): Unit = synchronized(activeLock) {
        when (slot.role) {
            Slot.Role.ATTACHED -> {
                // Detach this flow's channel from the leader so a cancelled
                // collector never receives (or blocks) further broadcasts.
                slot.attachedTo?.removeListener(listener)
            }
            Slot.Role.OWNER -> {
                slot.removeListener(listener)
                // If the owner pass already reached a terminal state, clear the
                // process-wide slot so the next caller starts a fresh attempt.
                if (activeSlot === slot && slot.finished.get()) {
                    activeSlot = null
                }
            }
            Slot.Role.STALE -> { /* never registered as leader; nothing to release */ }
        }
        Unit
    }

    private suspend fun runBootstrapInternal(slot: Slot) {
        // v0.1.8: the caller (BootstrapWorker) already runs us on Dispatchers.IO;
        // progress is additionally mirrored to WorkManager via BootstrapReporter.
        val ctx = this.ctx
        fun report(step: String, pct: Int) {
            // v0.1.16 STALL FIX #5b: a pass that became stale mid-flight (another
            // collector's terminal result raised the floor while we were still
            // running — possible because the leader can outlive its own flow if
            // the UI detaches and re-attaches) must stop emitting frames entirely
            // so it can never hijack the displayed label back to an old phase.
            if (isStalePass(slot.id)) return
            // v0.1.16: monotonic phase ordering. Never let a frame regress the
            // displayed phase (e.g. a straggler re-announcing "Downloading
            // rootfs" after we reached "Installing helper scripts"). Same-phase
            // updates (download percentages) always pass. Reset per pass.
            val rank = phaseRank(step)
            synchronized(progressLock) {
                if (lastPhasePassId != slot.id) { lastPhasePassId = slot.id; lastPhaseRank = -1 }
                if (rank >= 0) {
                    if (rank < lastPhaseRank) return
                    lastPhaseRank = rank
                } else if (lastPhaseRank >= 0 && step.isEmpty()) return
            }
            BootstrapReporter.publish(ctx, step, pct)
            slot.broadcast(Result.failure(BootstrapProgress(step, pct)))
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
            // v0.1.47 MULTI-DISTRO: every step below works against the SELECTED
            // distro (Settings → Linux distribution). FsLayout.linuxRoot()
            // already points at files/distros/<id> (active) and migrates the
            // legacy files/linuxroot tree on first access.
            val spec = DistroCatalog.requested(ctx)
            val dest = FsLayout.linuxRoot(ctx)
            val tmpFile = File(FsLayout.safeTmp(ctx), "rootfs-${spec.id}.archive")
            // v0.1.47: sweep the pre-multi-distro fixed-name leftover, if any.
            runCatching { File(FsLayout.safeTmp(ctx), "rootfs.tar.gz").delete() }
            // v0.1.15 AUDIT FIX — stale tarball reuse. downloadTo() opens the output
            // stream with FileOutputStream (append semantics, no truncation). If a
            // previous attempt was killed mid-download, the partial rootfs.tar.gz
            // survives in cache; the next attempt then APPENDS the fresh bytes to
            // the old ones → corrupt archive ("tar failed" / incomplete rootfs) or,
            // worse when the server returns 304-ish cached bodies, a silently wrong
            // file that passes the gzip-magic check but explodes during extraction.
            // Always start from a clean slate.
            runCatching { if (tmpFile.exists()) tmpFile.delete() }
            // v0.1.16: short-circuit if a concurrent pass already finished the
            // install while we were acquiring the slot (e.g. the W-M worker won
            // the race against MainActivity's 8-second safety net). Without this
            // the second caller would wipe and re-download a perfectly good rootfs.
            // v0.1.16b: guard the whole check — FsLayout.isBootstrapped touches
            // files/prefs and any escaped Throwable here used to abort bootstrap
            // right after extraction with no progress frame, which surfaced as
            // "the step never passes". On doubt we proceed rather than throw.
            val alreadyDone = runCatching { FsLayout.isBootstrapped(ctx) }.getOrDefault(false)
            if (alreadyDone || isStalePass(slot.id)) {
                report("Bootstrapped", 100)
                slot.finish(Result.success(Unit))
                return
            }
            // v0.1.27 PERSISTENCE FIX #2: marker missing but a COMPLETE rootfs on
            // disk (backup/restore wiped .env_version, OEM storage manager pruned
            // it, or ENV_VERSION was bumped by an app update). Re-stamp instead of
            // wiping + re-downloading the whole distro — this is what made the app
            // "re-install every time I close and reopen it". We still refresh the
            // helper scripts below so an ENV_VERSION bump re-seeds patched assets.
            val r = runCatching { FsLayout.hasCompleteRootfs(ctx) }.getOrDefault(false)
            if (r) {
                com.emux.EmuXLog.i("rootfs complete but marker stale — re-stamping, no re-download")
                // v0.1.27: per-script frames carry rank 7 ("Installing helper
                // script…"); the monotonic phase floor would then permanently
                // block the "Finalizing rootfs config" frame (rank 6). Order the
                // reports so ranks advance monotonically within this branch too.
                report("Installing helper scripts", -1)
                val staged = stageHelperAssets()
                installHelperScripts(dest, staged) { step, pct -> report(step, pct) }
                report("Finalizing rootfs config", -1)
                seedResolverConfig(dest)
                prepareMountpoints(dest)
                postInstall(dest, spec)
                // v0.1.28: the re-seed path must also (re-)install the native
                // bridge + distro components — this is what upgrades an existing
                // rootfs after an app update WITHOUT re-downloading it, and what
                // retries a failed apk-extras pass from a previous launch
                // (FsLayout.ensureMarkerForExistingRootfs refuses to re-stamp
                // while shell components are missing, so we land here instead).
                report("Seeding native bridge", -1)
                installNativeBridge()
                val extrasErr = installDistroExtras(dest, spec, null) { step, pct -> report(step, pct) }
                if (extrasErr != null) throw java.io.IOException(extrasErr)
                val restamped = runCatching { FsLayout.markBootstrappedChecked(ctx, spec.id) }.getOrDefault(false)
                if (!restamped) {
                    throw java.io.IOException("existing rootfs found, but completion marker could not be written (storage full?)")
                }
                report("Bootstrapped", 100)
                slot.finish(Result.success(Unit))
                return
            }
            // v0.1.47 SWITCH GUARD: reaching here means the ACTIVE distro is
            // genuinely absent (the cheap re-stamp branch above already handled
            // marker-loss on an intact tree). If ANOTHER distro is installed,
            // this pass would be a fresh download of a newly SELECTED distro —
            // only allowed when the user tapped Install for exactly this distro
            // (MainActivity stamps DistroCatalog.markSwitchConfirmed). Stale
            // auto-scheduled worker jobs therefore can never swap environments
            // silently; they fail with this actionable message instead.
            if (runCatching { FsLayout.anyDistroInstalled(ctx) }.getOrDefault(false) &&
                !DistroCatalog.switchConfirmed(ctx)) {
                throw RuntimeException(
                    "${spec.label} is not installed yet — tap Install to download it " +
                        "(your other installed distros stay untouched)")
            }
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

            // v0.1.47 MULTI-DISTRO candidate list, in priority order:
            //  - Settings → "mirror" holding a FULL tarball URL (.tar.gz/.tar.xz)
            //    always wins — works for ANY distro (self-hosted images);
            //  - Alpine: mirror BASE normalization exactly as before (custom
            //    base → built-in MIRRORS; "…/alpine", "…/alpine/v",
            //    "…/alpine/v3.20" all resolve to <base>/<major>/releases/<arch>/
            //    <distro>-<full-version>-<arch>.tar.gz — a bare "3.20" 404s);
            //  - Debian/Ubuntu: SHA256-pinned proot-distro release images first,
            //    Canonical cloud-images fallback second (Ubuntu only).
            // v0.1.9: prefs read is also guarded — a corrupted shared_prefs XML
            // throws on getSharedPreferences and previously escaped here in the
            // worker's child scope (uncaught → process death).
            val custom = runCatching {
                androidx.preference.PreferenceManager
                    .getDefaultSharedPreferences(ctx)
                    .getString("mirror", null)?.trim()?.takeIf { it.isNotBlank() }
            }.getOrNull()
            val customIsTarball = custom != null &&
                (custom.endsWith(".tar.gz") || custom.endsWith(".tar.xz"))

            class Cand(val url: URL, val sha: String?, val mirrorBase: String?)
            val cands = mutableListOf<Cand>()
            if (customIsTarball) {
                custom?.let { cu ->
                    runCatching { cands += Cand(URL(cu), null, null) }
                        .onFailure { com.emux.EmuXLog.e("custom mirror URL unparsable", it) }
                }
            }
            if (spec.id == "alpine") {
                val ver = ALPINE_VERSION
                val pathSuffix = "${majorVersion(ver)}/releases/${archName()}/$DEFAULT_DISTRO-$ver-${archName()}.tar.gz"
                val bases = listOfNotNull(custom?.takeIf { !customIsTarball }) + MIRRORS
                for (base in bases) {
                    val clean = base.trim().trimEnd('/')
                    val url: URL = when {
                        clean.endsWith(".tar.gz") -> URL(clean)           // full file URL — verbatim
                        clean.endsWith("/v") -> URL("$clean$pathSuffix")  // "…/alpine/v" + "3.20/releases/…"
                        Regex("/v[0-9]").containsMatchIn(clean) ->
                            URL("$clean/releases/${archName()}/$DEFAULT_DISTRO-$ver-${archName()}.tar.gz")
                        else -> URL("$clean/${majorVersion(ver)}/releases/${archName()}/$DEFAULT_DISTRO-$ver-${archName()}.tar.gz")
                    }
                    cands += Cand(url, null, clean)
                }
            } else {
                val images = spec.candidates(archName())
                if (images.isEmpty() && !customIsTarball) {
                    throw RuntimeException(
                        "${spec.label} has no rootfs image for ${archName()} devices — pick another distro in Settings")
                }
                for (img in images) runCatching { cands += Cand(URL(img.url), img.sha256, null) }
                    .onFailure { com.emux.EmuXLog.e("catalog URL unparsable: ${img.url}", it) }
            }
            val tmp = tmpFile

            var lastErr: Throwable? = null
            var downloaded = false
            var archiveIsXz = false
            var workingMirrorBase: String? = null   // v0.1.28: mirror that served the rootfs → seeded into etc/apk/repositories
            for (c in cands) {
                report("Downloading rootfs (${c.url.host})", -1)
                try {
                    downloadTo(c.url, tmp) { pct -> report("Downloading rootfs", pct) }
                    // Sanity-check what we downloaded PER CANDIDATE (an HTML error
                    // page must fall through to the next mirror, not surface later
                    // as a confusing "tar failed"). gzip magic = 1f 8b;
                    // xz magic = fd 37 7a 58 5a 00.
                    val xz = c.url.path.endsWith(".xz")
                    val head = ByteArray(6)
                    FileInputStream(tmp).use { it.read(head) }
                    val magicOk = if (xz)
                        head[0] == 0xfd.toByte() && head[1] == 0x37.toByte() && head[2] == 0x7a.toByte() &&
                            head[3] == 0x58.toByte() && head[4] == 0x5a.toByte() && head[5] == 0x00.toByte()
                    else head[0] == 0x1f.toByte() && head[1] == 0x8b.toByte()
                    if (!magicOk) {
                        throw RuntimeException(
                            "Mirror returned non-${if (xz) "xz" else "gzip"} data (${tmp.length()} bytes) — check network/mirror URL")
                    }
                    // v0.1.47: SHA256 verification for pinned catalog images
                    // (proot-distro publishes sums; corruption/tamper → next
                    // candidate instead of a broken rootfs).
                    if (c.sha != null) {
                        report("Verifying checksum", -1)
                        val actual = sha256Of(tmp)
                        if (!actual.equals(c.sha, ignoreCase = true)) {
                            throw RuntimeException(
                                "SHA256 mismatch — download corrupt (expected ${c.sha.take(16)}…, got ${actual.take(16)}…)")
                        }
                    }
                    downloaded = true
                    archiveIsXz = xz
                    workingMirrorBase = c.mirrorBase
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
                throw RuntimeException("All mirrors failed for ${spec.label} ($detail). Check Wi-Fi/mobile data and retry.", lastErr)
            }

            report("Extracting rootfs", -1)
            // v0.1.15 AUDIT FIX — extraction into a DIRTY destination. The pre-wipe
            // above is bounded (entry cap + 60 s time cap); if it hit its cap, or
            // if the previous attempt died mid-extract, dest/ can still contain a
            // partially-extracted tree. Alpine's tarball is mostly HARDLINKS to
            // already-extracted inodes: GNU tar hardlink entries carry size=0 and
            // no payload, so "tar -x" against a tree missing the link TARGETs
            // fails ("Cannot create symlink to ..."), and toybox tar silently
            // produces zero-length files for them → an incomplete rootfs that
            // later breaks apk/shell launch in confusing ways. Same story for the
            // pure-Java fallback (type '1' hardlinks are skipped entirely). Fix:
            // verify dest really is empty right before extracting; if anything
            // survived, do one more quick bottom-up pass (short budget) and then
            // proceed — extraction always starts from a clean slate now.
            var dirty = false
            try {
                if (java.nio.file.Files.exists(dest.toPath())) {
                    java.nio.file.Files.newDirectoryStream(dest.toPath()).use { ds ->
                        dirty = ds.iterator().hasNext()
                    }
                }
            } catch (_: Throwable) { dirty = true }
            if (dirty) {
                com.emux.EmuXLog.e("dest not empty after pre-wipe — running second cleanup pass")
                runCatching { deleteBottomUp(dest, maxMillis = 20_000L) }
            }
            extractArchive(tmp, dest, archiveIsXz)
            tmp.delete()
            // v0.1.48: pd/official images wrap the tree in one top-level dir
            unwrapSingleTopDir(dest)

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
            postInstall(dest, spec)
            // v0.1.15: the generic label is emitted once, then installHelperScripts
            // reports a per-script frame ("Installing helper script: <name>") so
            // the UI advances through every copy instead of freezing on one label.
            report("Installing helper scripts", -1)
            // v0.1.16 STALL FIX #5c — ASSET PRE-STAGE. The per-script work is
            // ~700-byte copies that take milliseconds; the only way this step
            // can burn minutes is a wedged AAsset handle (assets.open() /
            // read() blocking in native code under storage pressure — seen on
            // several OEM ROMs). withHardTimeout's watchdog returns after 8 s
            // but the *daemon thread stays blocked forever*, and its open FD
            // makes subsequent assets.open() calls queue behind it — so pass 2+
            // still stalls even though bootstrap "continues". Fix: pre-read ALL
            // helper assets ONCE through a single hard-timeout stage before the
            // per-script loop. If staging times out we skip the asset layer
            // entirely and fall back to copying from the already-extracted rootfs
            // (dest/emux-* survive every previous successful extract), which is
            // pure file I/O and can never wedge on AAsset internals.
            val staged = stageHelperAssets()
            installHelperScripts(dest, staged) { step, pct -> report(step, pct) }
            // v0.1.28: seed the bundled proot native bridge (assets/proot/<abi>)
            // and install the distro components via apk (util-linux `script` =
            // the PTY source for the keyboard fix; bash; and — unless disabled in
            // Settings — the X11 desktop stack). Shell components are FATAL on
            // failure: without script(1) there is no PTY and the terminal echoes
            // nothing (the reported "keyboard not working"). The next launch
            // then lands in the cheap re-stamp branch above (rootfs survives —
            // hasCompleteRootfs is fixed — no re-download), which retries apk.
            report("Seeding native bridge", -1)
            installNativeBridge()
            val extrasErr = installDistroExtras(dest, spec, workingMirrorBase) { step, pct -> report(step, pct) }
            if (extrasErr != null) throw java.io.IOException(extrasErr)
            // v0.1.16: the per-script frames above are "Installing helper
            // script: <name>" (rank 7). Emit a terminal-rank frame so the label
            // floor advances PAST the helper phase — a residual late/old-pass
            // frame can then never re-pin the UI on "Installing helper scripts"
            // again (the exact reported symptom), and the user sees the phase
            // actually complete instead of hanging on the last sub-step name.
            report("Bootstrapped", 100)

            // v0.1.15 AUDIT FIX — verified completion before marking. The old code
            // wrote .env_version unconditionally; if that single write threw (full
            // disk, perms), isBootstrapped() stayed false forever and every app
            // launch re-ran the ENTIRE bootstrap even though the rootfs was fine.
            // Now: sanity-check the tree first (fail loudly if extraction produced
            // junk), then mark with retry + atomic tmp+rename, and only declare
            // success when the marker actually reads back.
            check(File(dest, "etc").isDirectory && File(dest, "bin").exists()) {
                "Bootstrap completed but rootfs looks incomplete (${dest.absolutePath})"
            }
            val marked = runCatching {
                var ok = FsLayout.markBootstrappedChecked(ctx, spec.id)
                if (!ok) { try { Thread.sleep(150) } catch (_: InterruptedException) {}; ok = FsLayout.markBootstrappedChecked(ctx, spec.id) }
                ok
            }.getOrDefault(false)
            if (!marked) {
                throw java.io.IOException("rootfs installed, but completion marker could not be written (storage full?)")
            }
            slot.finish(Result.success(Unit))
        } catch (e: Throwable) {
            // v0.1.8: surface the real reason to the observing UI through the
            // WorkManager progress data too (the Flow path only works in-process).
            // v0.1.10: use BootstrapDiagnostics so linkage errors show WHICH class
            // is missing instead of a bare "NoClassDefFoundError".
            com.emux.core.BootstrapDiagnostics.dumpFull(ctx, e)
            // v0.1.50: embed the RUNNING app version in every failure banner —
            // field screenshots otherwise cannot prove which build produced
            // them (the v0.1.48/49 round turned out to be a device still on
            // 0.1.47; only the error string gave it away).
            BootstrapReporter.publishError(
                ctx, "v${com.emux.EmuXLog.appVersion(ctx)} · " +
                    com.emux.core.BootstrapDiagnostics.describe(e))
            slot.finish(Result.failure(e))
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
    private fun extractTarGz(tgz: File, dest: File, allowHostTools: Boolean = true) {
        val cmds = if (!allowHostTools) emptyList() else listOf(
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
        runCatching {
            java.util.zip.GZIPInputStream(java.io.BufferedInputStream(FileInputStream(tgz), 64 * 1024))
                .use { extractTarStream(it, dest) }
        }
            .onFailure { throw RuntimeException("Extraction failed on this device. tar: $lastErr; java: ${it.message}") }
        if (!rootfsLooksExtracted(dest)) throw RuntimeException("Extraction produced an incomplete rootfs (tar: $lastErr)")
    }

    /**
     * v0.1.47: .tar.xz extraction (the Debian/Ubuntu catalog images). Host
     * tooling first — Android 10+ toybox ships xz(1), other ROMs often have a
     * GNU tar with -J — then the pure-Java LZMA2 decoder (org.tukaani:xz,
     * 0BSD-licensed) so extraction NEVER depends on what the device carries.
     * Same watchdog + strategy-fallthrough shape as extractTarGz; the longer
     * budget accounts for xz being slower to decompress than gzip on big images.
     */
    private fun extractTarXz(txz: File, dest: File, allowHostTools: Boolean = true) {
        val cmds = if (!allowHostTools) emptyList() else listOf(
            arrayOf("/system/bin/sh", "-c", "xz -dc '${txz.absolutePath}' | tar -x -C '${dest.absolutePath}'"),
            arrayOf("/system/bin/sh", "-c", "unxz -c '${txz.absolutePath}' | tar -x -C '${dest.absolutePath}'"),
            arrayOf("tar", "-xJf", txz.absolutePath, "-C", dest.absolutePath),
        )
        var lastErr = ""
        for (argv in cmds) {
            val r = runCatching {
                val p = ProcessBuilder(*argv).redirectErrorStream(true).start()
                val watchdog = Thread {
                    try { Thread.sleep(600_000); if (p.isAlive) p.destroy() } catch (_: Exception) {}
                }.apply { isDaemon = true; start() }
                try {
                    val out = StringBuilder()
                    BufferedReader(InputStreamReader(p.inputStream)).forEachLine { out.append(it).append('\n') }
                    val code = p.waitFor()
                    code to out.toString()
                } finally { watchdog.interrupt() }
            }
            if (r.isFailure) { lastErr = "spawn failed: ${r.exceptionOrNull()?.message}"; continue }
            val (code, out) = r.getOrThrow()
            if (code == 0 && File(dest, "etc").exists()) return
            lastErr = "exit $code: ${out.take(200)}"
        }
        // Pure-Java last resort (XZ for Java).
        runCatching {
            org.tukaani.xz.XZInputStream(java.io.BufferedInputStream(FileInputStream(txz), 64 * 1024))
                .use { extractTarStream(it, dest) }
        }.onFailure { throw RuntimeException("xz extraction failed on this device. tar: $lastErr; java: ${it.message}") }
        if (!rootfsLooksExtracted(dest)) throw RuntimeException("Extraction produced an incomplete rootfs (tar: $lastErr)")
    }

    /**
     * v0.1.51: wrapper-aware post-extract probe. pd/official images keep the
     * tree inside ONE top-level dir until unwrapSingleTopDir() runs (which
     * happens in the CALLER, after extraction) — so the end-of-extraction
     * completeness check must accept the wrapped shape, otherwise a perfect
     * extraction is reported as "incomplete rootfs" (the v0.1.48-50 field
     * loop: check threw before the unwrap ever ran).
     */
    private fun rootfsLooksExtracted(dest: File): Boolean {
        if (File(dest, "etc").isDirectory) return true
        val ch = runCatching { dest.listFiles() }.getOrNull()
        return ch != null && ch.size == 1 && ch[0].isDirectory &&
            File(ch[0], "etc").isDirectory
    }

    /** Compression-aware dispatch (xz for the Debian/Ubuntu images, gz for Alpine).
     *  [allowHostTools]=false forces the pure-Java path (device-like ROMs with
     *  no xz binary; also how the sandbox harness verifies the real sequence). */
    private fun extractArchive(arc: File, dest: File, xz: Boolean, allowHostTools: Boolean = true) =
        if (xz) extractTarXz(arc, dest, allowHostTools) else extractTarGz(arc, dest, allowHostTools)

    /**
     * Minimal streaming tar reader. v0.1.48 REWRITE (field bug: Ubuntu install
     * produced an EMPTY rootfs): the v0.1.47 parser desynchronized on the very
     * first entry of every proot-distro image — those are PAX extended headers
     * ('x'), and the old `else -> skip(gz, size)` branch skipped the payload
     * but NOT the 512-byte block padding, so the next "header" read was
     * mid-payload garbage/zeros → immediate end-of-archive → empty tree →
     * bootstrap aborted before helper scripts (the v0.1.47 field screenshot).
     * Also fixed: FORWARD hardlinks (Alpine lists busybox applet links BEFORE
     * bin/busybox; the old copy-on-sight silently dropped ~330 of them) are now
     * deferred and resolved after the pass; PAX path/linkpath/size overrides
     * and GNU 'K' long-link extensions are honored; every branch consumes
     * payload + padding exactly.
     * Takes an ALREADY-DECOMPRESSED stream (gzip or xz).
     */
    private fun extractTarStream(gzIn: java.io.InputStream, dest: File) {
        val gz = gzIn
        val hdr = ByteArray(512)
        var pendingLongName: String? = null
        var pendingLongLink: String? = null
        var paxPath: String? = null
        var paxLink: String? = null
        var paxSize: Long? = null
        // forward hardlinks: (target file, source rel path) resolved post-pass
        val deferred = mutableListOf<Pair<File, String>>()
        while (true) {
            var read = 0
            while (read < 512) { val n = gz.read(hdr, read, 512 - read); if (n < 0) return; read += n }
            if (String(hdr, 0, 100).all { it == '\u0000' }) break // end-of-archive block
            val name0 = String(hdr, 0, 100).trim('\\', '\u0000')
            var size = String(hdr, 124, 12).trim('\\', '\u0000', ' ').toLongOrNull(8) ?: 0L
            val type = hdr[156].toInt().toChar()
            val prefix = String(hdr, 345, 155).trim('\\', '\u0000')
            var link = String(hdr, 157, 100).trim('\\', '\u0000')
            var name = when {
                paxPath != null -> paxPath!!
                pendingLongName != null -> pendingLongName!!
                prefix.isNotEmpty() -> "$prefix/$name0"
                else -> name0
            }
            if (paxLink != null) link = paxLink!!
            else if (pendingLongLink != null) link = pendingLongLink!!
            if (paxSize != null) size = paxSize!!
            val pad = ((512 - (size % 512)) % 512)
            // ---- extension headers: consume payload+padding, affect NEXT entry
            when (type) {
                'x', 'X' -> {   // PAX extended header (per-entry overrides)
                    val payload = ByteArray(minOf(size, 1L * 1024 * 1024).toInt())
                    var o = 0
                    while (o < payload.size) { val n = gz.read(payload, o, payload.size - o); if (n < 0) break; o += n }
                    if (size > payload.size) skip(gz, size - payload.size)
                    skip(gz, pad)
                    var r = 0
                    while (r < o) {
                        var sp = r
                        while (sp < o && payload[sp].toInt().toChar().isDigit()) sp++
                        val len = String(payload, r, sp - r).toIntOrNull() ?: break
                        if (r + len > o) break
                        val rec = String(payload, r, len).trimEnd('\n')
                        val kv = rec.substringAfter(' ')
                        when (kv.substringBefore('=')) {
                            "path" -> paxPath = kv.substringAfter('=')
                            "linkpath" -> paxLink = kv.substringAfter('=')
                            "size" -> paxSize = kv.substringAfter('=').toLongOrNull()
                        }
                        r += len
                    }
                    continue
                }
                'g' -> { skip(gz, size); skip(gz, pad); continue }   // PAX global: ignore
                'L' -> {   // GNU long name
                    val long = ByteArray(size.toInt()); var o = 0
                    while (o < long.size) { val n = gz.read(long, o, long.size - o); if (n < 0) break; o += n }
                    pendingLongName = String(long, 0, o).trim('\u0000', '\n')
                    skip(gz, pad)
                    continue
                }
                'K' -> {   // GNU long link target
                    val long = ByteArray(size.toInt()); var o = 0
                    while (o < long.size) { val n = gz.read(long, o, long.size - o); if (n < 0) break; o += n }
                    pendingLongLink = String(long, 0, o).trim('\u0000', '\n')
                    skip(gz, pad)
                    continue
                }
            }
            val target = File(dest, name).canonicalFile
            if (!target.path.startsWith(dest.canonicalFile.path + File.separator) && target != dest.canonicalFile) {
                // path traversal guard
                skip(gz, size); skip(gz, pad)
                pendingLongName = null; pendingLongLink = null; paxPath = null; paxLink = null; paxSize = null
                continue
            }
            when {
                type == '5' || name.endsWith("/") -> {
                    target.mkdirs()
                    skip(gz, size); skip(gz, pad)
                }
                type == '2' -> { /* symlink via android.system.Os (API 21+), best-effort */
                    target.parentFile?.mkdirs()
                    runCatching { android.system.Os.symlink(link, target.path) }
                        .recoverCatching {
                            // JVM-side fallback (unit harness; also covers ROMs
                            // where the Os binding is missing)
                            java.nio.file.Files.createSymbolicLink(
                                target.toPath(), java.nio.file.Paths.get(link))
                        }
                    skip(gz, size); skip(gz, pad)
                }
                type == '1' -> {   // hardlink: copy target; defer if forward-referenced
                    target.parentFile?.mkdirs()
                    // v0.1.48: linknames arrive BOTH "./bin/busybox" (GNU relative)
                    // AND "/bin/busybox" (Alpine absolute-in-rootfs). File(dest,
                    // "/abs") IGNORES dest — normalize to rootfs-relative.
                    val srcRel = link.removePrefix("./").trimStart('/')
                    val srcF = File(dest, srcRel)
                    if (srcF.isFile) {
                        runCatching {
                            java.nio.file.Files.copy(srcF.toPath(), target.toPath(),
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                        }
                    } else {
                        deferred += target to srcRel
                    }
                    skip(gz, size); skip(gz, pad)
                }
                type == '0' || type == '\u0000' -> {
                    target.parentFile?.mkdirs()
                    target.outputStream().use { os ->
                        var left = size
                        val buf = ByteArray(64 * 1024)
                        while (left > 0) { val n = gz.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; os.write(buf, 0, n); left -= n }
                    }
                    val mode = String(hdr, 100, 8).trim('\u0000', ' ').toIntOrNull(8) ?: 0
                    target.setExecutable(mode and 0x40 != 0, false)
                    skip(gz, pad)
                }
                else -> {   // char/block/fifo/socket: skip safely (never create)
                    skip(gz, size); skip(gz, pad)
                }
            }
            pendingLongName = null; pendingLongLink = null
            paxPath = null; paxLink = null; paxSize = null
        }
        // resolve forward hardlinks now that their targets exist
        for ((target, srcRel) in deferred) {
            runCatching {
                val srcF = File(dest, srcRel)
                if (srcF.isFile) {
                    java.nio.file.Files.copy(srcF.toPath(), target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                } else {
                    com.emux.EmuXLog.e("tar: hardlink source missing: $srcRel (for ${target.path})")
                }
            }
        }
    }

    /**
     * v0.1.48: proot-distro release tarballs (and several official images)
     * wrap the whole rootfs in ONE top-level directory (e.g.
     * "ubuntu-noble-aarch64/"); the Alpine minirootfs does not. Without
     * unwrapping, dest/etc never appears and bootstrap aborts right after
     * extraction. If dest holds exactly one directory that itself looks like
     * a rootfs (has etc/), hoist its children up with cheap same-fs renames.
     */
    internal fun unwrapSingleTopDir(dest: File) {
        runCatching {
            val children = dest.listFiles() ?: return
            if (children.size != 1) return
            val top = children[0]
            if (!top.isDirectory || !File(top, "etc").isDirectory) return
            com.emux.EmuXLog.i("rootfs wrapped in ${top.name}/ — unwrapping")
            top.listFiles()?.forEach { c ->
                runCatching {
                    java.nio.file.Files.move(c.toPath(), File(dest, c.name).toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
            runCatching { java.nio.file.Files.deleteIfExists(top.toPath()) }
        }.onFailure { com.emux.EmuXLog.e("unwrapSingleTopDir failed", it) }
    }

    private fun skip(gz: java.io.InputStream, n: Long) { var left = n; val b = ByteArray(8192)
        while (left > 0) { val k = gz.read(b, 0, minOf(left, b.size.toLong()).toInt()); if (k < 0) break; left -= k } }

    /**
     * Copy the helper scripts from APK assets into the rootfs and make them
     * executable. Without this, TerminalSession's launch command
     * (`$rootfs/emux-init.sh …`) points at a non-existent file and every shell
     * dies instantly — which looked like "bootstrap succeeded but nothing happens".
     *
     * v0.1.15 STALL FIX #4 — "stuck at Installing helper scripts". Three bugs
     * made this step hang or abort; none of them actually involved the ~700-byte
     * copies themselves, which are instant:
     *  1. NO PER-SCRIPT PROGRESS FRAME. The single "Installing helper scripts"
     *     label covered three asset opens + writes + two more copies to $HOME.
     *     Because indeterminate frames only repaint when the NEXT step starts,
     *     any one blocked operation froze the UI on this label forever — the
     *     same repaint-starvation mechanism as the pre-wipe and resolver stalls.
     *     Now each script gets its own heartbeat frame ("Installing helper
     *     script: <name>").
     *  2. UNGUARDED WRITES ON HOSTILE TARGETS. `File(dest, name)` can be a
     *     dangling symlink or special file left by the pure-Java tar fallback
     *     (Alpine ships ./emux-* nowhere, but a partially-extracted tree from an
     *     interrupted attempt can occupy those names with anything). The old
     *     bare outputStream() then threw FileNotFoundException → bootstrap
     *     aborted right in this step. All targets now go through
     *     replaceRegularFileWith() — lstat-based, delete-and-recreate, never
     *     opens FIFOs/device nodes.
     *  3. UNBOUNDED ASSET READ. assets.open() + copyTo had no size cap and no
     *     watchdog; a corrupt AAsset handle (seen on some OEM ROMs after storage
     *     pressure) could spin inside read(). Each copy now runs under
     *     withHardTimeout(8 s) AND streams through readAssetBounded(), which
     *     enforces a per-file byte budget while reading. Worst case per script:
     *     seconds, not minutes.
     * The $HOME staging copy is likewise guarded and bounded (it used to run a
     * recursive Kotlin copyTo against a possibly-symlinked destination).
     */
    /**
     * v0.1.16 STALL FIX #5c — read every helper asset ONCE into memory under a
     * single hard-timeout watchdog. Returns name→bytes for whatever completed;
     * missing entries fall back to the already-extracted copy in dest/ (see
     * [installHelperScripts]). The whole staging pass is bounded: worst case it
     * costs ~4 s and then bootstrap proceeds without the asset layer at all,
     * so "Installing helper scripts" can never again be held hostage by a
     * wedged AAsset handle on OEM ROMs.
     */
    internal fun stageHelperAssets(): Map<String, ByteArray> {
        val names = listOf("emux-init.sh", "emux-x11", "emux-capture", "emux-fbmail", "emux-init-probe.sh", "emux-winsize", "emux-apt-repair")
        val result = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
        withHardTimeout(4_000L) {
            for (name in names) {
                try {
                    ctx.assets.open("bin/$name").use { input ->
                        result[name] = readBounded(input, MAX_ASSET_BYTES)
                    }
                } catch (e: Throwable) {
                    com.emux.EmuXLog.e("asset stage $name failed (will use rootfs copy)", e)
                }
            }
        }
        return result
    }

    internal fun installHelperScripts(
        dest: File,
        staged: Map<String, ByteArray>,
        report: (String, Int) -> Unit,
    ) {
        // v0.1.23 SELF-HEAL: if this pass ran with assets unavailable (staged
        // map empty — wedged AAsset handle on some OEM ROMs) but a previous
        // bootstrap already installed the helpers into files/bin/, reuse those
        // bytes instead of falling back to the possibly-corrupt rootfs copy.
        // Without this, a re-bootstrap after an update could OVERWRITE good
        // rootfs copies with garbage read through a hostile path.
        var healed = staged
        if (healed.isEmpty()) {
            val binDir0 = java.io.File(ctx.filesDir, "bin")
            healed = listOf("emux-init.sh", "emux-x11", "emux-capture", "emux-init-probe.sh", "emux-winsize", "emux-apt-repair").mapNotNull { n ->
                val f = java.io.File(binDir0, n)
                if (f.isFile && f.length() in 1..MAX_ASSET_BYTES)
                    runCatching { FileInputStream(f).use { readBounded(it, MAX_ASSET_BYTES) } }
                        .getOrNull()?.let { n to it }
                else null
            }.toMap()
        }
        val stagedFinal = healed
        listOf("emux-init.sh", "emux-x11", "emux-capture", "emux-init-probe.sh", "emux-winsize", "emux-apt-repair").forEach { name ->
            // Heartbeat BEFORE each unit of work so the UI always advances even
            // if the following operation hits its timeout.
            report("Installing helper script: $name", -1)
            val out = File(dest, name)
            // v0.1.16: bytes come from the pre-staged map when available. The
            // per-script work is now pure file I/O (replaceRegularFileWith +
            // an in-memory write) — instant, no AAsset call inside the hot loop.
            // If staging didn't produce this asset (timed-out/corrupt), fall
            // back to the copy already present in the extracted rootfs; only if
            // NEITHER source exists do we fail — and even then non-fatally for
            // convenience scripts, fatally only for emux-init.sh which the shell
            // launcher requires.
            var bytes = stagedFinal[name]
            if (bytes == null) {
                bytes = runCatching {
                    if (out.isFile && out.length() in 1..MAX_ASSET_BYTES) FileInputStream(out).use {
                        readBounded(it, MAX_ASSET_BYTES)
                    } else null
                }.getOrNull()
            }
            if (bytes == null) {
                if (name == "emux-init.sh") {
                    throw RuntimeException("Missing/unreadable asset bin/$name in APK")
                }
                com.emux.EmuXLog.e("helper script $name unavailable (continuing)")
                return@forEach
            }
            val written = arrayOf(false)
            withHardTimeout(8_000L) {
                // Never open a hostile target: guarantee a fresh regular file,
                // then write from memory (instant, cannot block).
                if (!replaceRegularFileWith(out) { f -> f.outputStream().use { it.write(bytes) } }) {
                    throw java.io.IOException("could not create ${out.absolutePath}")
                }
                out.setExecutable(true, false)
                out.setReadable(true, false)
                written[0] = true
            }
            if (!written[0]) {
                com.emux.EmuXLog.e("helper script $name timed out (continuing)")
            }
        }
        // Also stage them under $HOME so they're on PATH-ish locations for users.
        // v0.1.23: emux-init.sh is included now — TerminalSession reads the
        // launcher body from files/bin/ or the rootfs copy; if BOTH were lost
        // (storage pressure cleared cache/home dirs, interrupted cleanup), the
        // $HOME mirror is a third guaranteed-readable source in app-private
        // storage that tar extraction can never clobber.
        try {
            val home = FsLayout.home(ctx)
            listOf("emux-init.sh", "emux-x11", "emux-capture", "emux-init-probe.sh", "emux-winsize", "emux-apt-repair").forEach { name ->
                val src = File(dest, name)
                if (src.isFile) {
                    runCatching {
                        withHardTimeout(8_000L) {
                            FileInputStream(src).use { inp ->
                                val bytes = readBounded(inp, MAX_ASSET_BYTES)
                                replaceRegularFileWith(File(home, name)) { dst ->
                                    dst.outputStream().use { it.write(bytes) }
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) { /* convenience only, non-fatal */ }
        // v0.1.19 BUGFIX L: install the helpers into files/bin/ as well. That
        // directory is the FIRST entry of FsLayout.sessionEnv()'s PATH, so bare
        // `emux-x11` / `emux-capture` lookups from any distro shell now resolve;
        // previously they existed only at the rootfs root + $HOME, neither of
        // which is on PATH — desktop sessions died instantly with
        // "emux-x11: not found" while bootstrap cheerfully said "Bootstrapped".
        try {
            val binDir = java.io.File(ctx.filesDir, "bin").apply { mkdirs() }
            listOf("emux-init.sh", "emux-x11", "emux-capture", "emux-init-probe.sh", "emux-winsize", "emux-apt-repair").forEach { name ->
                val src = File(dest, name)
                if (!src.isFile) return@forEach
                runCatching {
                    withHardTimeout(8_000L) {
                        val bytes = stagedFinal[name] ?: FileInputStream(src).use { readBounded(it, MAX_ASSET_BYTES) }
                        if (replaceRegularFileWith(java.io.File(binDir, name)) { dst ->
                                dst.outputStream().use { it.write(bytes) } }) {
                            java.io.File(binDir, name).setExecutable(true, false)
                        }
                    }
                }
            }
        } catch (_: Exception) { /* convenience mirror, non-fatal */ }
        // v0.1.28: install the session helpers into $ROOT/usr/local/bin/ — the
        // canonical location INSIDE the distro. In proot/chroot sessions the
        // login shell's PATH (/etc/profile.d/emux.sh) resolves `emux-x11` /
        // `emux-capture` from there; previously they only existed at the rootfs
        // ROOT (`/emux-x11` inside the chroot — NOT on PATH → "not found" →
        // black desktop even in a working root session).
        try {
            val ulb = File(dest, "usr/local/bin").apply { mkdirs() }
            // v0.1.38: emux-winsize joins the in-distro helpers (guest PTY size sync).
            listOf("emux-x11", "emux-capture", "emux-winsize", "emux-apt-repair").forEach { name ->
                val bytes = stagedFinal[name] ?: return@forEach
                runCatching {
                    if (replaceRegularFileWith(File(ulb, name)) { dst ->
                            dst.outputStream().use { it.write(bytes) } }) {
                        File(ulb, name).setExecutable(true, false)
                        File(ulb, name).setReadable(true, false)
                    }
                }
            }
        } catch (_: Exception) { /* non-fatal: desktop only */ }
    }

    // ---- v0.1.28: native bridge (bundled proot) + apk distro components ----

    /**
     * Extract assets/proot/<abi>/{proot,libtalloc.so.2,libandroid-shmem.so}
     * into files/native-bridge/. These are Termux-built bionic binaries
     * (DT_RUNPATH → LD_LIBRARY_PATH wins; INTERP=/system/bin/linker64) so they
     * exec directly from app-private storage — legal because the app targets
     * SDK 28 (see build.gradle.kts). proot then gives every session a real
     * chroot-equivalent WITHOUT root: absolute distro paths resolve, PT_INTERP
     * is rewritten, xkbcomp works for Xvfb. Licenses: assets/proot/LICENSES.txt.
     */
    private fun installNativeBridge() {
        val abi = FsLayout.nativeBridgeAssetAbi()
        val out = FsLayout.nativeBridgeDir(ctx)
        // v0.1.36 CRITICAL ADDITION: the Termux proot build execs guest binaries
        // through a companion LOADER binary whose path is compiled in as
        // /data/data/com.termux/files/usr/libexec/proot/loader — unreachable on
        // any non-Termux device, which made every guest execve die with
        // "Permission denied / the loader was not found". proot honors
        // PROOT_LOADER/PROOT_LOADER_32 overrides, so we ship the matching loader
        // binaries (same package, same version) and point proot at them.
        for (name in listOf("proot", "libtalloc.so.2", "libandroid-shmem.so", "loader", "loader32")) {
            runCatching {
                val bytes = ctx.assets.open("proot/$abi/$name").use { readBounded(it, 8L * 1024 * 1024) }
                val f = File(out, name)
                replaceRegularFileWith(f) { it.outputStream().use { o -> o.write(bytes) } }
                f.setExecutable(true, false)
                f.setReadable(true, false)
            }.onFailure { com.emux.EmuXLog.e("native bridge asset $name ($abi) failed", it) }
        }
    }

    /**
     * Install the distro components with the rootfs' own apk, executed from the
     * HOST through the musl loader (verified end-to-end against Alpine 3.20.3:
     * `ld-musl-<arch>.so.1 --library-path lib:usr/lib sbin/apk --root <rootfs>
     * add …` works outside any chroot as an ordinary app process).
     *
     * Shell components (util-linux → script(1) = the PTY, bash) are required:
     * returns false if they cannot be installed even after mirror rotation.
     * Desktop components (Xvfb/xkbcomp/openbox/xterm/xwd/xhost/netpbm) are
     * best-effort unless disabled in Settings.
     */
    /**
     * Install the distro components with the rootfs' own apk.
     *
     * v0.1.29 CRITICAL FIX — v0.1.28 ran apk HOST-side through the musl loader
     * and that fails on real devices: musl resolves DNS via /etc/resolv.conf,
     * which DOES NOT EXIST on Android → musl falls back to 127.0.0.1:53
     * (nothing listens) → every mirror lookup dies → "shell components could
     * not be installed" with the rootfs otherwise fine. (Sandbox testing
     * missed this because the test host OS has a real /etc/resolv.conf.)
     *
     * apk now runs INSIDE the bundled proot: guest /etc/resolv.conf is our
     * seeded one (net.dns* or 8.8.8.8/1.1.1.1), /dev+/proc are bound,
     * package triggers execute natively (absolute distro paths resolve) and
     * TLS uses the rootfs' own CA bundle — exactly how proot-distro drives
     * apt/apk/pacman on unrooted Android.
     *
     * Fallbacks (should proot be blocked on some ROM, e.g. ptrace restrictions):
     * host-side loader exec, then host-side exec with IP-literal repositories
     * (Java resolves the hostnames through Android's resolver, bypassing
     * musl's dead localhost DNS). Success is judged by the ACTUAL BINARIES on
     * disk (apk may exit nonzero on benign trigger noise) and the apk output
     * tail is returned so failures surface verbatim in the bootstrap UI.
     *
     * @return null on success, else a human-readable failure summary.
     */
    private fun installDistroExtras(
        dest: File,
        spec: DistroSpec,
        mirrorBase: String?,
        report: (String, Int) -> Unit,
    ): String? {
        seedDevNodes(dest)
        return when (spec.pkg) {
            PkgManager.APK -> installApkExtras(dest, mirrorBase, report)
            PkgManager.APT -> installAptExtras(dest, spec, report)
            PkgManager.PACMAN, PkgManager.DNF -> installGenericPmExtras(dest, spec, report)
        }
    }

    /**
     * v0.1.47: Arch (pacman) / Fedora (dnf) component install inside the
     * bundled proot — same model as [installAptExtras] minus the apt-specific
     * sources surgery (the proot-distro images ship preconfigured pacman/dnf
     * repos, incl. ALARM mirrors for Arch ARM). The shell-component probe is
     * disk-based: both images preinstall bash + util-linux, so the terminal
     * works even fully OFFLINE; the package manager only runs for genuinely
     * missing pieces (desktop stack, user extras).
     *
     * @return null on success, else a human-readable failure summary.
     */
    private fun installGenericPmExtras(
        dest: File,
        spec: DistroSpec,
        report: (String, Int) -> Unit,
    ): String? {
        val proot = FsLayout.prootBin(ctx)
        if (!proot.isFile) {
            return "no execution engine (proot missing at ${proot.absolutePath}) — cannot run ${spec.pkg.name.lowercase()}"
        }
        fun shellOk() =
            (File(dest, "usr/bin/script").isFile || File(dest, "bin/script").isFile) &&
                (File(dest, "usr/bin/bash").isFile || File(dest, "bin/bash").isFile)

        var tail = ""
        report("Installing shell components", -1)
        var ok = shellOk()
        if (!ok) {
            val r = runPmProot(proot, dest, spec, spec.shellPkgs, "Installing shell components", report)
            tail = "proot-${spec.pkg.name.lowercase()}: ${r.tail}"
            ok = r.ok || shellOk()
        }
        if (!ok) {
            val probe = runCatching { deviceProbe(dest) }.getOrDefault("probe unavailable")
            return "shell components (bash/util-linux) could not be installed via ${spec.pkg.name.lowercase()}.\n" +
                "--- device probe ---\n$probe--- output ---\n${tail.take(600)}"
        }

        // User-facing convenience packages, best effort (never fatal).
        runCatching {
            report("Installing user extras", -1)
            val r = runPmProot(proot, dest, spec, spec.extraPkgs, "Installing user extras", report)
            if (!r.ok) com.emux.EmuXLog.e("user extras failed (non-fatal): ${r.tail}")
        }

        val desktopWanted = runCatching {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
                .getBoolean("desktop_components", true)
        }.getOrDefault(true)
        if (desktopWanted) {
            report("Installing desktop components", -1)
            var dok = File(dest, "usr/bin/Xvfb").isFile
            if (!dok) {
                val r = runPmProot(proot, dest, spec, spec.desktopPkgs, "Installing desktop components", report)
                dok = r.ok || File(dest, "usr/bin/Xvfb").isFile
                if (!dok) com.emux.EmuXLog.e("desktop components failed (non-fatal): ${r.tail}")
            }
        }
        return null
    }

    /**
     * One pacman/dnf run inside the bundled proot (env mirrors [runApkProot];
     * PROOT_TMP_DIR + PROOT_LOADER are the make-or-break variables for the
     * Termux proot build). Payloads:
     *  - pacman: `-Sy --noconfirm --needed` (DB refresh + idempotent install)
     *  - dnf: `-y install` with a `--skip-broken` first pass so one renamed
     *    desktop-side package can never fail the whole group (Fedora renames
     *    X11 packages between releases; the on-disk Xvfb probe is the judge).
     */
    private fun runPmProot(
        proot: File, dest: File, spec: DistroSpec, pkgs: List<String>,
        phase: String, report: (String, Int) -> Unit,
    ): ApkRun {
        val hostTmp = prootTmp(ctx)
        val nb = FsLayout.nativeBridgeDir(ctx)
        val pk = pkgs.joinToString(" ")
        val payload = when (spec.pkg) {
            PkgManager.PACMAN ->
                "pacman -Sy --noconfirm --needed $pk"
            PkgManager.DNF ->
                "dnf -y --skip-broken install $pk || dnf -y install $pk"
            else -> return ApkRun(false, "runPmProot called for ${spec.pkg}")
        }
        val argv = listOf(
            proot.absolutePath, "-0", "-r", dest.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-w", "/",
            "/bin/sh", "-c", payload,
        )
        val env = mutableMapOf(
            "LD_LIBRARY_PATH" to nb.absolutePath,
            "PROOT_TMP_DIR" to hostTmp.absolutePath,
            "TMPDIR" to "/tmp",
            "HOME" to "/root",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG" to "C",
            "PROOT_LOADER" to File(nb, "loader").absolutePath,
        )
        val l32 = File(nb, "loader32")
        if (l32.isFile) env["PROOT_LOADER_32"] = l32.absolutePath
        return runApkProcess(argv, env, pkgs, phase, report)
    }

    /** Alpine component install via the rootfs' own apk (v0.1.29+ logic). */
    private fun installApkExtras(
        dest: File,
        mirrorBase: String?,
        report: (String, Int) -> Unit,
    ): String? {
        seedApkRepositories(dest, mirrorBase)
        val proot = FsLayout.prootBin(ctx)
        val loader = runCatching {
            File(dest, "lib").listFiles()?.firstOrNull {
                it.name.startsWith("ld-musl-") && it.name.endsWith(".so.1") && it.isFile
            }
        }.getOrNull()
        if (!File(dest, "sbin/apk").isFile) {
            return "rootfs has no sbin/apk — extraction incomplete? Tap Install to retry."
        }
        if (!proot.isFile && loader == null) {
            return "no execution engine (proot missing at ${proot.absolutePath}, no musl loader in rootfs) — cannot run apk"
        }

        val shellPkgs = listOf("util-linux", "bash")
        fun shellOk() = File(dest, "usr/bin/script").isFile && File(dest, "bin/bash").isFile

        var tail = ""
        report("Installing shell components", -1)
        var ok = false
        // 1) proot (primary) — DNS works via the seeded guest resolv.conf
        if (!ok && proot.isFile) {
            val r = runApkProot(proot, dest, shellPkgs, "Installing shell components", report)
            tail = "proot-apk: ${r.tail}"
            ok = r.ok || shellOk()
        }
        // 1b) proot again with a rotated mirror (regional blocks: dlcdn or
        //     leaseweb may be unreachable on some networks — TUNA etc.)
        if (!ok && proot.isFile) {
            seedApkRepositories(dest, null, rotate = 2)
            val r = runApkProot(proot, dest, shellPkgs, "Installing shell components", report)
            tail = "proot-apk(mirror2): ${r.tail}\n$tail"
            ok = r.ok || shellOk()
        }
        // 1c) v0.1.31: proot with IP-LITERAL repositories — ZERO DNS dependency
        //     inside the guest. Carrier networks that block/poison UDP/53 to
        //     foreign resolvers made attempts 1/1b fail even with a perfectly
        //     seeded resolv.conf; Java resolves every mirror through Android's
        //     own resolver here and apk walks the repository lines by itself.
        if (!ok && proot.isFile && rewriteRepositoriesToIps(dest, mirrorBase)) {
            val r = runApkProot(proot, dest, shellPkgs, "Installing shell components", report)
            tail = "proot-apk(ip-repos): ${r.tail}\n$tail"
            ok = r.ok || shellOk()
        }
        // 2) v0.1.31: host-side loader exec with the SAME multi-mirror IP-literal
        //    repository set (ROMs where proot/ptrace is blocked). The old
        //    intermediate "loader + hostname repos" attempt was REMOVED: the
        //    Android host has no /etc/resolv.conf at all, so musl DNS can never
        //    work there (v0.1.29 proved it) — going straight to IP literals
        //    saves minutes of doomed retries. The old single-mirror IP attempt
        //    (rotate=1 → leaseweb only) is superseded by the multi-mirror set.
        if (!ok && loader != null && rewriteRepositoriesToIps(dest, mirrorBase)) {
            val r = runApkLoader(loader, dest, shellPkgs, "Installing shell components", report)
            tail = "loader-apk(ip-repos): ${r.tail}\n$tail"
            ok = r.ok || shellOk()
        }
        if (!ok) {
            // v0.1.30: embed a device probe so ONE screenshot pinpoints the
            // broken layer (HarmonyOS/OEM hardening vs ptrace block vs DNS).
            val probe = runCatching { deviceProbe(dest) }.getOrDefault("probe unavailable")
            return "shell components (util-linux/bash) could not be installed via apk.\n" +
                "--- device probe ---\n$probe--- apk output ---\n${tail.take(600)}"
        }

        // v0.1.46: user-facing convenience packages, best effort (never fatal).
        // sudo (Alpine community) for the familiar privilege workflow — inside
        // proot we are already root (fake-root), and the shipped sudoers covers
        // root; apt (Alpine community, APT 2.x built for musl) because users
        // coming from Debian-style distros expect it. NOTE: on Alpine, `apk`
        // remains THE system package manager; `apt` manages its own lists and
        // cannot replace apk for system components.
        if (ok) {
            val extras = listOf("sudo", "apt")
            report("Installing user extras (sudo, apt)", -1)
            val r = when {
                proot.isFile -> runApkProot(proot, dest, extras, "Installing user extras", report)
                loader != null -> runApkLoader(loader, dest, extras, "Installing user extras", report)
                else -> null
            }
            if (r == null || !r.ok) {
                com.emux.EmuXLog.e("user extras sudo/apt failed (non-fatal): ${r?.tail}")
            }
        }

        val desktopWanted = runCatching {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
                .getBoolean("desktop_components", true)
        }.getOrDefault(true)
        if (desktopWanted) {
            val deskPkgs = listOf("xvfb", "xkbcomp", "xkeyboard-config", "openbox",
                "xterm", "xwd", "xhost", "netpbm")
            report("Installing desktop components", -1)
            var dok = false
            var dtail = ""
            if (proot.isFile) {
                val r = runApkProot(proot, dest, deskPkgs, "Installing desktop components", report)
                dtail = "proot-apk: ${r.tail}"
                dok = r.ok || File(dest, "usr/bin/Xvfb").isFile
            }
            if (!dok && loader != null) {
                val r = runApkLoader(loader, dest, deskPkgs, "Installing desktop components", report)
                dtail = "loader-apk: ${r.tail}\n$dtail"
                dok = File(dest, "usr/bin/Xvfb").isFile
            }
            if (!dok) {
                com.emux.EmuXLog.e("desktop components failed (non-fatal): $dtail")
            }
        }
        return null
    }

    // ---- v0.1.47: Debian/Ubuntu component install via apt inside proot ----

    /**
     * Install the distro components with the rootfs' own apt-get, executed
     * INSIDE the bundled proot — the exact model Termux's proot-distro uses
     * for Debian/Ubuntu on unrooted Android: guest /etc/resolv.conf is our
     * seeded one, /dev+/proc are bound, dpkg triggers execute natively and TLS
     * uses the rootfs' own CA keyrings.
     *
     * Success is judged by the ACTUAL BINARIES on disk (apt/dpkg can exit
     * nonzero on benign trigger noise under fake-root). The proot-distro images
     * ship bash + util-linux preinstalled, so the shell-component probe passes
     * OFFLINE for them and apt only runs for genuinely missing pieces. Mirror
     * rotation (TUNA/AliYun/USTC — the defaults are slow or blocked on some
     * networks, field-verified) and IP-literal sources mirror the apk fallback
     * ladder; the tail output is returned so failures surface verbatim.
     *
     * @return null on success, else a human-readable failure summary.
     */
    private fun installAptExtras(
        dest: File,
        spec: DistroSpec,
        report: (String, Int) -> Unit,
    ): String? {
        writeAptPolicy(dest)
        val proot = FsLayout.prootBin(ctx)
        if (!proot.isFile) {
            return "no execution engine (proot missing at ${proot.absolutePath}) — cannot run apt. Tap Install to retry."
        }
        fun shellOk() =
            (File(dest, "usr/bin/script").isFile || File(dest, "bin/script").isFile) &&
                (File(dest, "usr/bin/bash").isFile || File(dest, "bin/bash").isFile)

        var tail = ""
        report("Installing shell components", -1)
        var ok = shellOk()
        if (!ok) {
            // v0.1.53: repair dpkg BEFORE rotating mirrors. On the field device
            // every rotation (and the IP-literal last resort) died with the
            // same dpkg status-old error — mirror rotation can never fix a
            // broken package manager, and the failure banner blamed the
            // network for what was a local dpkg/proot fault.
            val rep = runRepairProot(proot, dest)
            tail = "proot-repair: ${rep.tail}\n"
            ok = shellOk()
        }
        if (!ok) {
            // attempt 0: the image's own sources; 1..2: rotated CN-friendly mirrors.
            for (rotate in 0..2) {
                if (rotate > 0) seedAptSources(dest, spec, rotate)
                val r = runAptProot(proot, dest, spec.shellPkgs, "Installing shell components", report)
                tail = "proot-apt(rotate=$rotate): ${r.tail}\n$tail"
                if (r.ok || shellOk()) { ok = true; break }
            }
            // last resort: IP-literal sources (zero DNS dependency inside the guest).
            if (!ok && rewriteAptSourcesToIps(dest, spec)) {
                val r = runAptProot(proot, dest, spec.shellPkgs, "Installing shell components", report)
                tail = "proot-apt(ip-repos): ${r.tail}\n$tail"
                ok = r.ok || shellOk()
            }
        }
        if (!ok) {
            val probe = runCatching { deviceProbe(dest) }.getOrDefault("probe unavailable")
            return "shell components (bash/util-linux) could not be installed via apt.\n" +
                "--- device probe ---\n$probe--- apt output ---\n${tail.take(600)}"
        }

        // User-facing convenience packages, best effort (never fatal).
        runCatching {
            report("Installing user extras", -1)
            val r = runAptProot(proot, dest, spec.extraPkgs, "Installing user extras", report)
            if (!r.ok) com.emux.EmuXLog.e("user extras failed (non-fatal): ${r.tail}")
        }

        val desktopWanted = runCatching {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
                .getBoolean("desktop_components", true)
        }.getOrDefault(true)
        if (desktopWanted) {
            report("Installing desktop components", -1)
            var dtail = ""
            var dok = File(dest, "usr/bin/Xvfb").isFile
            if (!dok) {
                val r = runAptProot(proot, dest, spec.desktopPkgs, "Installing desktop components", report)
                dtail = "proot-apt: ${r.tail}"
                dok = r.ok || File(dest, "usr/bin/Xvfb").isFile
            }
            if (!dok) {
                // one retry with a rotated mirror before giving up (desktop is non-fatal)
                seedAptSources(dest, spec, 1)
                val r = runAptProot(proot, dest, spec.desktopPkgs, "Installing desktop components", report)
                dtail = "proot-apt(mirror2): ${r.tail}\n$dtail"
                dok = File(dest, "usr/bin/Xvfb").isFile
            }
            if (!dok) {
                com.emux.EmuXLog.e("desktop components failed (non-fatal): $dtail")
            }
        }
        return null
    }

    /**
     * One apt-get run inside the bundled proot: `update` then `install`. The
     * update re-runs every time (seconds) so rotated/repaired sources always
     * take effect and stale lists from a killed attempt never linger. Env
     * mirrors runApkProot — PROOT_TMP_DIR and PROOT_LOADER are the two make-or-
     * break variables for the Termux proot build on non-Termux devices.
     */
    private fun runAptProot(
        proot: File, dest: File, pkgs: List<String>, phase: String, report: (String, Int) -> Unit,
    ): ApkRun = withAptLock(onBusy = { ApkRun(false, "apt lock busy - another apt/dpkg run is in progress") }) {
        val hostTmp = prootTmp(ctx)
        val nb = FsLayout.nativeBridgeDir(ctx)
        // v0.1.52 FIELD FIX: a killed apt run (user closes the app mid
        // desktop-components install) leaves dpkg's journal dirty — every
        // later apt invocation then dies with "dpkg was interrupted, you
        // must manually run dpkg --configure -a". Self-heal before each run;
        // idempotent and cheap when the state is clean.
        // v0.1.53: the heal is now APT_REPAIR_PAYLOAD — the bare
        // `dpkg --configure -a || true` could never succeed on kernels where
        // proot's link(2) translation breaks dpkg's status-old backup
        // (EACCES/EEXIST, termux/proot#377), so the dirty state survived
        // every retry and bricked the desktop install on the field device.
        // v0.1.61 FIELD FIX: under proot, a FIRST-pass postinst can fail on a
        // proot statx quirk (sandbox-reproduced with the pinned noble image:
        // libpaper1's postinst does echo>/etc/papersize.dpkg-inst then ucf
        // stats it -> "stat: cannot statx ... No such file" -> postinst exit 1
        // -> libgs10/libspectre1/libimlib2/libobrender/openbox left unpacked
        // and apt exits 100). A plain retry of dpkg --configure -a configures
        // everything cleanly (verified: 0 stuck packages), so settle after
        // every install pass instead of leaving the tree half-configured.
        val payload = APT_REPAIR_PAYLOAD + "; " +
            "apt-get update -o APT::Sandbox::User=root; " +
            "apt-get install -y --no-install-recommends -o APT::Sandbox::User=root " +
            pkgs.joinToString(" ") + "; " +
            "i=0; while [ \$i -lt 2 ]; do dpkg --configure -a >&2 && break; i=\$((i + 1)); done"
        val argv = listOf(
            proot.absolutePath, "-0", "-r", dest.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-w", "/",
            "/bin/sh", "-c", payload,
        )
        val env = mutableMapOf(
            "LD_LIBRARY_PATH" to nb.absolutePath,
            "PROOT_TMP_DIR" to hostTmp.absolutePath,
            "TMPDIR" to "/tmp",
            "HOME" to "/root",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG" to "C",
            "PROOT_LOADER" to File(nb, "loader").absolutePath,
        )
        // v0.1.70 CRITICAL: the v0.1.63 "belt" set LD_PRELOAD on the HOST proot
        // process with a GUEST path - Android's linker then refused to start
        // proot at all ("CANNOT LINK EXECUTABLE ... libemux-linkfix.so not
        // found"), killing every repair/apt proot run for seven versions.
        // The belt now lives INSIDE the guest payload (APT_REPAIR_PAYLOAD).
        // v0.1.66: adaptive PROOT_NO_SECCOMP (see runRepairProot)
        if (FsLayout.prootNoSeccompFlag(ctx).isFile)
            env["PROOT_NO_SECCOMP"] = "1"
        val l32 = File(nb, "loader32")
        if (l32.isFile) env["PROOT_LOADER_32"] = l32.absolutePath
        return runApkProcess(argv, env, pkgs, phase, report)
    }

    /**
     * Guest-side apt/dpkg hygiene for proot:
     *  - policy-rc.d → exit 101 so dpkg never tries to START services (there is
     *    no init system under proot; without this some postinst scripts hang
     *    or fail noisily);
     *  - 99emux config: run the download sandbox as (fake) root — the _apt
     *    user drop cannot work under proot — plus sane retry counts.
     */
    private fun writeAptPolicy(dest: File) {
        runCatching {
            val pr = File(dest, "usr/sbin/policy-rc.d")
            replaceRegularFileWith(pr) { it.writeText("#!/bin/sh\nexit 101\n") }
            pr.setExecutable(true, false)
        }
        runCatching {
            File(dest, "etc/apt/apt.conf.d").mkdirs()
            replaceRegularFileWith(File(dest, "etc/apt/apt.conf.d/99emux")) {
                it.writeText("APT::Sandbox::User \"root\";\nAcquire::Retries \"2\";\n")
            }
        }
    }

    /**
     * Overwrite the apt sources with the given mirror rotation (0 = distro
     * default). Writes deb822 form (native on bookworm/noble) into the
     * canonical .sources file and disables the legacy one-line sources.list so
     * the two can never fight. Signed-By points at the keyring the images ship.
     */
    private fun seedAptSources(dest: File, spec: DistroSpec, rotate: Int) {
        runCatching {
            val dir = File(dest, "etc/apt/sources.list.d").apply { mkdirs() }
            when (spec.id) {
                "debian" -> {
                    val codename = DistroCatalog.osReleaseField(dest, "VERSION_CODENAME") ?: "bookworm"
                    val base = DistroCatalog.DEBIAN_MIRRORS[rotate.coerceIn(0, DistroCatalog.DEBIAN_MIRRORS.size - 1)]
                    val sec = DistroCatalog.DEBIAN_SECURITY_MIRRORS[rotate.coerceIn(0, DistroCatalog.DEBIAN_SECURITY_MIRRORS.size - 1)]
                    val text = "Types: deb\n" +
                        "URIs: $base\n" +
                        "Suites: $codename $codename-updates\n" +
                        "Components: main contrib non-free non-free-firmware\n" +
                        "Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg\n" +
                        "\n" +
                        "Types: deb\n" +
                        "URIs: $sec\n" +
                        "Suites: $codename-security\n" +
                        "Components: main contrib non-free non-free-firmware\n" +
                        "Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg\n"
                    replaceRegularFileWith(File(dir, "debian.sources")) { it.writeText(text) }
                }
                "ubuntu" -> {
                    val codename = DistroCatalog.osReleaseField(dest, "VERSION_CODENAME") ?: "noble"
                    val ports = archName() == "aarch64" || archName() == "armv7"
                    val mirrors = if (ports) DistroCatalog.UBUNTU_PORTS_MIRRORS else DistroCatalog.UBUNTU_MIRRORS
                    val base = mirrors[rotate.coerceIn(0, mirrors.size - 1)]
                    val text = "Types: deb\n" +
                        "URIs: $base\n" +
                        "Suites: $codename $codename-updates $codename-backports $codename-security\n" +
                        "Components: main universe restricted multiverse\n" +
                        "Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n"
                    replaceRegularFileWith(File(dir, "ubuntu.sources")) { it.writeText(text) }
                }
                else -> return
            }
            replaceRegularFileWith(File(dest, "etc/apt/sources.list")) {
                it.writeText("# managed by EmuX — see etc/apt/sources.list.d/\n")
            }
            com.emux.EmuXLog.i("apt sources seeded (rotate=$rotate) for ${spec.id}")
        }.onFailure { com.emux.EmuXLog.e("seedAptSources failed (non-fatal)", it) }
    }

    /**
     * Last-ditch DNS fallback for apt (mirrors rewriteRepositoriesToIps for
     * apk): resolve EVERY known mirror host with Java (Android's resolver) and
     * write IP-literal http:// sources — https to a bare IP fails certificate
     * hostname validation. Trust is NOT downgraded: plain-http apt is still
     * fully signature-verified through the Signed-By archive keyrings.
     */
    private fun rewriteAptSourcesToIps(dest: File, spec: DistroSpec): Boolean {
        fun toHttpIp(u: String): String? = runCatching {
            val url = java.net.URL(u)
            val ip = java.net.InetAddress.getByName(url.host).hostAddress ?: return@runCatching null
            val hostPart = if (ip.contains(':')) "[$ip]" else ip   // IPv6 literals
            "http://$hostPart${url.path}"
        }.getOrNull()
        return runCatching {
            val dir = File(dest, "etc/apt/sources.list.d").apply { mkdirs() }
            when (spec.id) {
                "debian" -> {
                    val codename = DistroCatalog.osReleaseField(dest, "VERSION_CODENAME") ?: "bookworm"
                    val mainIps = LinkedHashSet<String>()
                    val secIps = LinkedHashSet<String>()
                    DistroCatalog.DEBIAN_MIRRORS.forEach { m -> toHttpIp(m)?.let { mainIps += it } }
                    DistroCatalog.DEBIAN_SECURITY_MIRRORS.forEach { m -> toHttpIp(m)?.let { secIps += it } }
                    if (mainIps.isEmpty()) return false
                    val text = buildString {
                        append("Types: deb\nURIs: ").append(mainIps.joinToString(" ")).append('\n')
                        append("Suites: $codename $codename-updates\n")
                        append("Components: main contrib non-free non-free-firmware\n")
                        append("Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg\n")
                        if (secIps.isNotEmpty()) {
                            append("\nTypes: deb\nURIs: ").append(secIps.joinToString(" ")).append('\n')
                            append("Suites: $codename-security\n")
                            append("Components: main contrib non-free non-free-firmware\n")
                            append("Signed-By: /usr/share/keyrings/debian-archive-keyring.gpg\n")
                        }
                    }
                    replaceRegularFileWith(File(dir, "debian.sources")) { it.writeText(text) }
                }
                "ubuntu" -> {
                    val codename = DistroCatalog.osReleaseField(dest, "VERSION_CODENAME") ?: "noble"
                    val ports = archName() == "aarch64" || archName() == "armv7"
                    val mirrors = if (ports) DistroCatalog.UBUNTU_PORTS_MIRRORS else DistroCatalog.UBUNTU_MIRRORS
                    val ips = LinkedHashSet<String>()
                    mirrors.forEach { m -> toHttpIp(m)?.let { ips += it } }
                    if (ips.isEmpty()) return false
                    val text = "Types: deb\n" +
                        "URIs: ${ips.joinToString(" ")}\n" +
                        "Suites: $codename $codename-updates $codename-backports $codename-security\n" +
                        "Components: main universe restricted multiverse\n" +
                        "Signed-By: /usr/share/keyrings/ubuntu-archive-keyring.gpg\n"
                    replaceRegularFileWith(File(dir, "ubuntu.sources")) { it.writeText(text) }
                }
                else -> return false
            }
            replaceRegularFileWith(File(dest, "etc/apt/sources.list")) {
                it.writeText("# managed by EmuX (IP-literal mode)\n")
            }
            com.emux.EmuXLog.i("apt sources rewritten to IP literals for ${spec.id}")
            true
        }.getOrDefault(false)
    }

    /** Streaming SHA-256 (hex) of a file — verifies pinned catalog images. */
    private fun sha256Of(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { b -> "%02x".format(b) }
    }

    /** First repo URL from the guest's apt config (deb822 or one-line). */
    private fun firstAptRepoUrl(dest: File): String? = runCatching {
        val dir = File(dest, "etc/apt/sources.list.d")
        val deb822 = dir.listFiles()?.firstOrNull { it.name.endsWith(".sources") && it.isFile }
        deb822?.readLines()?.firstOrNull { it.startsWith("URIs:") }
            ?.substringAfter("URIs:")?.trim()?.split(' ')?.firstOrNull()
            ?: File(dest, "etc/apt/sources.list").takeIf { it.isFile }?.readLines()
                ?.firstOrNull { it.startsWith("deb ") }?.split(' ')?.getOrNull(1)
    }.getOrNull()

    /** Outcome of one apk/apt run: exit status + last output lines for diagnostics. */
    private class ApkRun(val ok: Boolean, val tail: String)

    /**
     * v0.1.30: layered device probe appended to apk-failure messages so ONE
     * screenshot from the field pinpoints the broken layer. HarmonyOS/OEM ROMs
     * can harden SELinux beyond AOSP (exec of app_data_file even for the
     * legacy untrusted_app_27 domain, or ptrace for proot) — each layer is
     * probed individually:
     *   android/model · effective targetSdk · SELinux domain (/proc/self/attr/current)
     *   exec(loader) · exec(proot) · proot-ptrace (tiny real proot run) · java DNS · resolv.conf
     */
    private fun deviceProbe(dest: File): String {
        val sb = StringBuilder()
        runCatching {
            sb.append("android=${android.os.Build.VERSION.SDK_INT} model=${android.os.Build.MODEL}\n")
            sb.append("targetSdk=${ctx.applicationInfo.targetSdkVersion}\n")
            val domain = runCatching {
                File("/proc/self/attr/current").readText().trim('\u0000', '\n', '\r')
            }.getOrNull()
            sb.append("selinux=${domain ?: "?"}\n")
            val loader = runCatching {
                File(dest, "lib").listFiles()?.firstOrNull {
                    it.name.startsWith("ld-musl-") && it.name.endsWith(".so.1") && it.isFile
                }
            }.getOrNull()
            sb.append(if (loader == null) "loader=missing\n"
                      else "exec(loader)=${probeExec(listOf(
                          loader.absolutePath, "--library-path",
                          "${File(dest, "lib").absolutePath}:${File(dest, "usr/lib").absolutePath}",
                          File(dest, "bin/busybox").absolutePath, "echo", "LOADER_EXEC_OK",
                      ), expect = "LOADER_EXEC_OK")}\n")
            val proot = FsLayout.prootBin(ctx)
            // v0.1.36: probes must mirror runApkProot's env (PROOT_TMP_DIR +
            // PROOT_LOADER) or they report false failures.
            val nbDir = FsLayout.nativeBridgeDir(ctx)
            val nb = mutableMapOf(
                "LD_LIBRARY_PATH" to nbDir.absolutePath,
                "PROOT_LOADER" to File(nbDir, "loader").absolutePath,
            )
            val l32p = File(nbDir, "loader32")
            if (l32p.isFile) nb["PROOT_LOADER_32"] = l32p.absolutePath
            sb.append(if (!proot.isFile) "proot=missing\n"
                      else "exec(proot)=${probeExec(listOf(proot.absolutePath, "--version"), nb)}\n")
            if (proot.isFile) {
                // v0.1.34/35: the micro-run must mirror runApkProot's environment
                // (PROOT_TMP_DIR = writable host scratch) or it reproduces the
                // false "ptrace blocked" verdict caused by proot's compiled-in
                // Termux temp dir being uncreatable here.
                val ht = prootTmp(ctx)
                sb.append("proot-ptrace=${probeExec(
                    listOf(proot.absolutePath, "-0", "-r", dest.absolutePath,
                        "-b", "/dev", "/bin/true"),
                    nb + ("PROOT_TMP_DIR" to ht.absolutePath))}\n")
            }
            sb.append("dns(java)=${runCatching {
                java.net.InetAddress.getByName("mirrors.tuna.tsinghua.edu.cn").hostAddress
            }.getOrElse { "FAIL ${it.javaClass.simpleName}" }}\n")
            sb.append("resolv.conf=${runCatching {
                File(dest, "etc/resolv.conf").readText().trim().replace("\n", " | ")
            }.getOrDefault("?")}\n")
            // v0.1.31: network-layer probes. The v0.1.30 banner proved exec works
            // but could not show WHETHER the seeded nameservers or the mirror
            // host are reachable at all (UDP/53 blackholing vs TCP firewalls vs
            // DNS-less guest). TCP connect probes separate those cases in one
            // screenshot, each capped at 2 s.
            val ns = runCatching {
                File(dest, "etc/resolv.conf").readText().lineSequence()
                    .firstOrNull { it.startsWith("nameserver") }?.substringAfter(' ')?.trim()
            }.getOrNull()
            sb.append("dns-tcp=${if (ns.isNullOrBlank()) "no-resolv-conf" else "$ns:53 ${tcpProbe(ns, 53)}"}\n")
            // v0.1.47: works for apk-based AND apt-based distros.
            val repoHost = runCatching {
                val apkRepos = File(dest, "etc/apk/repositories")
                val first = if (apkRepos.isFile)
                    apkRepos.readText().lineSequence().firstOrNull { it.isNotBlank() }
                else firstAptRepoUrl(dest)
                first?.takeIf { it.isNotBlank() }?.let { java.net.URL(it).host }
            }.getOrNull()
            if (!repoHost.isNullOrBlank()) {
                sb.append("repo-tcp=$repoHost:443 ${tcpProbe(repoHost, 443)}\n")
            }
        }
        return sb.toString()
    }

    /** TCP connect probe (2 s cap): reachable vs firewalled vs DNS-less. */
    private fun tcpProbe(host: String, port: Int): String = runCatching {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress(host, port), 2_000)
            "ok"
        }
    }.getOrElse { e -> "FAIL(${e.javaClass.simpleName})" }

    /**
     * Start [argv] and classify the outcome: started(exit=N) vs DENIED(msg).
     * v0.1.31: optional [expect] marker — exit 0 alone is not proof the exec
     * chain worked (a loader invoked with no arguments prints its usage banner
     * and exits 1, which made the old probe line look like a failure while
     * proving nothing). With [expect], "ok" requires the marker in stdout.
     */
    private fun probeExec(
        argv: List<String>,
        env: Map<String, String> = emptyMap(),
        expect: String? = null,
    ): String = runCatching {
        val pb = ProcessBuilder(argv)
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)
        pb.redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        val p = pb.start()
        val wd = Thread { try { Thread.sleep(8_000); p.destroy() } catch (_: Exception) {} }.apply { isDaemon = true }
        wd.start()
        val out = runCatching { p.inputStream.bufferedReader().readText().take(400) }.getOrDefault("")
        val code = try { p.waitFor() } catch (_: Exception) { -1 }
        wd.interrupt()
        val clean = out.replace('\n', ' ').trim()
        if (code == 0 && (expect == null || clean.contains(expect))) "ok" else "exit=$code $clean"
    }.getOrElse { e -> "DENIED(${e.message?.take(90)})" }

    // ---- v0.1.53: dpkg/apt self-repair (field round: Ubuntu 24.04 arm64) ----
    //
    // Field device state (screenshots): `apt install sudo` ->
    //   E: dpkg was interrupted, you must manually run 'dpkg --configure -a'
    //   dpkg: error: error creating new backup file
    //   '/var/lib/dpkg/status-old': Permission denied
    // Two independent causes, both fatal for EVERY later apt/dpkg run (and
    // therefore for the desktop stack, which emux-x11 installs via apt):
    //  1) a killed apt run left dpkg's journal (var/lib/dpkg/updates/*) dirty;
    //  2) dpkg's db backup is unlink(status-old)+link(status,status-old), and
    //     under the bundled proot the legacy link(2) syscall is translated
    //     through proot's seccomp avoider in a way several Android kernels
    //     answer with a bogus errno (EEXIST on 3.4 per termux/proot#377,
    //     EACCES on this field device) while linkat(2) translates fine.
    // Fix: ship a tiny LD_PRELOAD shim implementing link() via linkat()
    // (assets/linkfix/<abi>, freestanding so glibc AND musl guests can load
    // it), register it in /etc/ld.so.preload, drop a stale status-old, and
    // quarantine an unrecoverable journal before re-running configure -a.

    /** assets/linkfix/<abi> dir matching the guest arch (assets/proot naming). */
    private fun linkFixAbiDir(): String = when (archName()) {
        "aarch64" -> "arm64-v8a"
        "armv7" -> "armeabi-v7a"
        "x86_64" -> "x86_64"
        else -> "x86"
    }

    /**
     * Copy the link()->linkat() preload shim into the rootfs and register it
     * in /etc/ld.so.preload (idempotent). Non-fatal: without it, distros whose
     * kernel translates link(2) correctly keep working as before.
     */
    /** v0.1.95: native PPM->RGB565 converter for emux-capture. Debian/Ubuntu
     *  ship mawk as awk, and mawk DROPS NUL bytes from printf "%c" - black
     *  pixels are two NULs in RGB565 - so the awk conversion path can never
     *  produce a valid frame stream there (all-green diagnostics, zero
     *  frames, forever). Freestanding static binary, per ABI like the shim. */
    internal fun installCaptureConv(dest: File) {
        runCatching {
            val out = File(dest, "usr/local/lib/emux/emux-rgb565")
            out.parentFile?.mkdirs()
            ctx.assets.open("capture/${linkFixAbiDir()}/emux-rgb565").use { ins ->
                replaceRegularFileWith(out) { f -> f.outputStream().use { os -> ins.copyTo(os) } }
            }
            out.setExecutable(true, false)
            out.setReadable(true, false)
        }
    }

    /** v0.2.51: bundle+seed the desktop wallpaper (pcmanfm reads it). */
    internal fun installWallpaper(dest: File) {
        runCatching {
            val out = File(dest, "usr/share/emux/wall.png")
            out.parentFile?.mkdirs()
            ctx.assets.open("wallpaper/wall.png").use { ins ->
                replaceRegularFileWith(out) { f -> f.outputStream().use { os -> ins.copyTo(os) } }
            }
        }
    }

    internal fun installLinkFixShim(dest: File) {
        runCatching {
            val out = File(dest, "usr/local/lib/emux/libemux-linkfix.so")
            out.parentFile?.mkdirs()
            ctx.assets.open("linkfix/${linkFixAbiDir()}/libemux-linkfix.so").use { ins ->
                replaceRegularFileWith(out) { f -> f.outputStream().use { os -> ins.copyTo(os) } }
            }
            out.setReadable(true, false)
            out.parentFile?.setReadable(true, false)
            out.parentFile?.setExecutable(true, false)
            val line = "/usr/local/lib/emux/libemux-linkfix.so"
            val preload = File(dest, "etc/ld.so.preload")
            val cur = runCatching { preload.readText() }.getOrDefault("")
            if (cur.lineSequence().none { it.trim() == line }) {
                preload.parentFile?.mkdirs()
                val text = if (cur.isBlank()) "$line\n" else cur.trimEnd() + "\n" + line + "\n"
                replaceRegularFileWith(preload) { it.writeText(text) }
            }
            preload.setReadable(true, false)
            runCatching {
                val dbdir = File(dest, "var/lib/dpkg")
                if (dbdir.isDirectory) {
                    dbdir.setReadable(true, false)
                    dbdir.setExecutable(true, false)
                    dbdir.setWritable(true, true)
                }
                listOf("status", "status-old", "available").forEach { n2 ->
                    val f = File(dbdir, n2)
                    if (f.isFile) { f.setReadable(true, false); f.setWritable(true, true) }
                }
            }
            com.emux.EmuXLog.i("linkfix shim installed for ${linkFixAbiDir()}")
        }.onFailure { com.emux.EmuXLog.e("linkfix shim install failed (non-fatal)", it) }
    }

    /**
     * v0.1.68: the ONE EACCES path no shim can touch - real DAC. If
     * /var/lib/dpkg is owned by a FOREIGN uid (left over by whatever wrote
     * this rootfs before), fake-root dpkg gets EACCES from the real kernel on
     * every create/link inside it, preload or not. We cannot chown without
     * root, but we CAN rebuild the directory app-owned: copy the tree (reads
     * only) into dpkg.emux, rename the foreign one aside, rename ours in -
     * renames need write on var/lib only, proven by a probe file first.
     */
    internal fun hostDpkgDbSwap(dest: File): String = runCatching {
        val db = File(dest, "var/lib/dpkg")
        if (!db.isDirectory) return@runCatching "DBSWAP: no-dbdir"
        val st = runCatching { android.system.Os.stat(db.absolutePath) }.getOrNull()
        val mine = android.os.Process.myUid()
        if (st != null && st.st_uid == mine) return@runCatching "DBSWAP: owner-ok(${st.st_uid})"
        val parent = db.parentFile ?: return@runCatching "DBSWAP: no-parent"
        val probe = File(parent, ".emuxwtest")
        if (!runCatching { probe.createNewFile() }.getOrDefault(false))
            return@runCatching "DBSWAP: parent-readonly(uid=${st?.st_uid})"
        probe.delete()
        val nu = File(parent, "dpkg.emux")
        runCatching { nu.deleteRecursively() }
        nu.mkdirs()
        db.walkTopDown().forEach { f ->
            val rel = f.relativeTo(db).path
            if (rel.isEmpty()) return@forEach
            val t = File(nu, rel)
            if (f.isDirectory) t.mkdirs() else runCatching { f.copyTo(t, true) }
        }
        val old = File(parent, "dpkg.foreign-uid")
        runCatching { old.deleteRecursively() }
        if (db.renameTo(old) && nu.renameTo(db)) "DBSWAP: swapped(uid=${st?.st_uid}->mine)"
        else "DBSWAP: rename-failed(uid=${st?.st_uid})"
    }.getOrElse { "DBSWAP: err ${it.message?.take(60)}" }

    /** Host-visible, allocation-cheap check: dpkg journal left dirty? */
    internal fun aptJournalDirty(dest: File): Boolean = runCatching {
        File(dest, "var/lib/dpkg/updates").listFiles()?.any { it.isFile } == true
    }.getOrDefault(false)

    /**
     * v0.2.34 FIELD FIX: half-configured WITHOUT a dirty journal. The field
     * rootfs sat at "status half-configured openbox/libc-bin" with an EMPTY
     * updates/ dir - aptJournalDirty() said clean, the warm gate skipped the
     * repair forever, and every later `apt-get install` (xterm!) died on the
     * half-configured packages. Streamed desktop, zero windows, forever.
     */
    internal fun dpkgHalfConfigured(dest: File): Boolean = runCatching {
        File(dest, "var/lib/dpkg/status").useLines { ls ->
            ls.any { it == "Status: install ok half-configured" }
        }
    }.getOrDefault(false)

    /**
     * v0.2.41 FIELD FIX: libc-bin not fully installed => guest userspace
     * SIGSEGVs (field v0.2.40: "proot warning: signal 11" where the shell
     * prompt should be; terminal unusable; every new X client dies into
     * the half-swapped library set while old processes keep running).
     * The half-configured scan can miss it (status can read e.g.
     * "install reinstreq half-installed"), so check libc-bin's own
     * Status line explicitly: anything but "install ok installed" means
     * the rootfs must be healed BEFORE any shell or client launches.
     */
    internal fun dpkgLibcBroken(dest: File): Boolean = runCatching {
        var pkg = ""
        File(dest, "var/lib/dpkg/status").useLines { ls ->
            for (l in ls) {
                if (l.startsWith("Package: ")) pkg = l.substring(9).trim()
                else if (l.startsWith("Status: ") && pkg == "libc-bin" &&
                    l != "Status: install ok installed") return@runCatching true
            }
            false
        }
    }.getOrDefault(false)

    /**
     * v0.2.64 TERMINAL HEALTH GATE.
     *
     * FIELD REPORT (v0.2.63): "It opens now" — the launch crash was fixed — but
     * typing ANY command in the terminal printed `proot warning: signal 11`.
     *
     * The invariant has been written down since v0.2.41: "anything but
     * `install ok installed` means the rootfs must be healed BEFORE any shell or
     * client launches." But the gate enforcing it was only ever wired to two
     * places — the desktop cascade (DesktopService:399) and the bootstrap
     * pre-repair (desktopPreRepair, above). TerminalService.startSession checked
     * nothing but `prootBin(ctx).isFile`, i.e. "does the proot binary EXIST",
     * never "is the guest's libc sane". So a rootfs left half-configured by an
     * interrupted apt/dpkg transaction — exactly what a run of crash-looping
     * installs and force-stops produces — spawned proot happily and then
     * SIGSEGV'd every single execve, with no detector and no repair on that
     * path. Worse, each instant death fed the auto-respawn watcher, which burnt
     * MAX_AUTO_RESTARTS on a rootfs that could never work, after which the
     * terminal stayed permanently dead until an app restart.
     *
     * This is that missing gate, factored out so every launch path shares one
     * implementation. It is deliberately CHEAP when the guest is healthy — one
     * listFiles() plus two streamed reads of var/lib/dpkg/status, a few
     * milliseconds — so it is safe before every session start, and it is
     * FAIL-OPEN: any throw is swallowed and reported as HEALTHY, because a
     * damaged terminal still beats no terminal.
     *
     * NOTE the damage-only trigger: unlike desktopPreRepair this does NOT repair
     * on a missing emux-repair.stamp. That stamp is a desktop-completeness
     * concern; keying on it here would put a ~1 min apt run in front of the very
     * first shell on an otherwise-pristine rootfs.
     */
    enum class GuestHealth { HEALTHY, REPAIRED, REPAIR_FAILED, NOT_READY, LOCK_BUSY }

    fun ensureGuestHealthy(tag: String, note: ((String) -> Unit)? = null): GuestHealth = runCatching {
        val spec = DistroCatalog.requested(ctx)
        if (spec.pkg != PkgManager.APT) return@runCatching GuestHealth.HEALTHY
        val dest = FsLayout.linuxRoot(ctx)
        if (!File(dest, "etc").isDirectory) return@runCatching GuestHealth.NOT_READY
        val proot = FsLayout.prootBin(ctx)
        if (!proot.isFile) return@runCatching GuestHealth.NOT_READY

        val dirty = aptJournalDirty(dest)
        val half = dpkgHalfConfigured(dest)
        val libc = dpkgLibcBroken(dest)
        if (!dirty && !half && !libc) return@runCatching GuestHealth.HEALTHY

        // Name the actual damage: "signal 11" tells the user nothing, and the
        // three failure modes have different remedies.
        val why = listOfNotNull(
            if (libc) "libc-bin not fully installed" else null,
            if (half) "dpkg package(s) half-configured" else null,
            if (dirty) "interrupted dpkg journal" else null,
        ).joinToString("; ")
        com.emux.EmuXLog.i("$tag: guest rootfs damaged ($why) - running pre-session dpkg repair")
        note?.invoke("$tag: repairing guest rootfs ($why) - usually under a minute")

        val r = runRepairProot(proot, dest)
        com.emux.EmuXLog.i("$tag: repair ok=${r.ok} tail=${r.tail.take(240)}")
        if (!r.ok) {
            // An apt run already in flight is the documented SIGSEGV-wave source
            // (see the v0.2.57 note below): the caller must WAIT, not spawn a
            // shell into a library set dpkg is mid-swap on.
            if (r.tail.contains("apt lock busy")) {
                note?.invoke("$tag: another apt/dpkg run is in progress - waiting for it to finish")
                return@runCatching GuestHealth.LOCK_BUSY
            }
            note?.invoke("$tag: repair failed - ${r.tail.take(160)}")
            return@runCatching GuestHealth.REPAIR_FAILED
        }
        // Re-verify: apt can exit 0 and still leave libc-bin short — that is the
        // whole reason dpkgLibcBroken exists as a separate detector.
        if (aptJournalDirty(dest) || dpkgHalfConfigured(dest) || dpkgLibcBroken(dest)) {
            com.emux.EmuXLog.e("$tag: repair reported ok but the rootfs is STILL damaged")
            note?.invoke("$tag: repair ran but the rootfs is still damaged - shell may be unreliable")
            GuestHealth.REPAIR_FAILED
        } else {
            note?.invoke("$tag: guest rootfs repaired")
            GuestHealth.REPAIRED
        }
    }.getOrElse { e ->
        com.emux.EmuXLog.e("$tag: health gate threw - failing open", e)
        GuestHealth.HEALTHY
    }

    /**
     * One guest-side repair run inside the bundled proot — the same steps as
     * assets/bin/emux-apt-repair (inlined so it also works on rootfs trees
     * seeded by older APKs that lack the helper script).
     */
    private fun runRepairProot(
        proot: File, dest: File,
        payload: String = APT_REPAIR_PAYLOAD,
        noSeccomp: Boolean = false,
    ): ApkRun = withAptLock(onBusy = { ApkRun(false, "apt lock busy - another apt/dpkg run is in progress") }) {
        val hostTmp = prootTmp(ctx)
        val nb = FsLayout.nativeBridgeDir(ctx)
        val argv = listOf(
            proot.absolutePath, "-0", "-r", dest.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-w", "/",
            "/bin/sh", "-c", payload,
        )
        val env = mutableMapOf(
            "LD_LIBRARY_PATH" to nb.absolutePath,
            "PROOT_TMP_DIR" to hostTmp.absolutePath,
            "TMPDIR" to "/tmp",
            "HOME" to "/root",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG" to "C",
            "PROOT_LOADER" to File(nb, "loader").absolutePath,
        )
        val l32 = File(nb, "loader32")
        if (l32.isFile) env["PROOT_LOADER_32"] = l32.absolutePath
        // v0.1.70: guest-side belt only (see APT_REPAIR_PAYLOAD); host env
        // must NEVER carry the guest preload path (see runAptProot).
        // v0.1.66: adaptive - once the selfcheck proves the seccomp avoider is
        // the link(2) breaker on this kernel, every proot run disables it.
        if (noSeccomp || FsLayout.prootNoSeccompFlag(ctx).isFile)
            env["PROOT_NO_SECCOMP"] = "1"
        runApkProcess(argv, env, listOf("dpkg-repair"), "Repairing package manager",
            { ph, _ -> Companion.progressThrottled("$ph...") })
    }

    /**
     * v0.1.53: app-start hygiene for APT distros. The v0.1.52 self-heal lived
     * ONLY inside runAptProot, i.e. only inside a fresh bootstrap — a device
     * whose rootfs was already marked bootstrapped never ran it again, so a
     * broken dpkg stayed broken forever (the field device). This runs once
     * per process start, in a daemon thread, and no-ops on healthy systems
     * (three cheap File stats: dirty journal? shim present? stamp present?).
     */
    /**
     * v0.1.56 FIELD FIX: helper scripts (emux-x11 / emux-apt-repair / probe)
     * were ONLY re-seeded inside bootstrap passes - which never run again on a
     * stamped rootfs. A device updated from an old APK therefore kept running
     * OLD in-guest helpers forever: the field device still executed the
     * pre-repair emux-x11 (no journal quarantine, no apt retries), which is
     * why the desktop cascade kept dying with "E: dpkg was interrupted" even
     * though v0.1.53+ shipped the cure. Re-seed whenever the completion
     * marker's ENV stamp is older than this APK's.
     */
    private fun reseedHelpersIfNeeded(dest: File) {
        runCatching {
            val marker = runCatching { FsLayout.prefixFile(ctx).readText().trim() }.getOrDefault("")
            val env = marker.substringBefore(':').toIntOrNull() ?: 0
            if (env >= FsLayout.ENV_VERSION) return
            com.emux.EmuXLog.i("helper re-seed: marker env $env < ${FsLayout.ENV_VERSION} - refreshing in-rootfs helpers")
            val staged = stageHelperAssets()
            installHelperScripts(dest, staged) { _, _ -> }
            FsLayout.markBootstrapped(ctx, FsLayout.installedDistroId(ctx))
        }.onFailure { com.emux.EmuXLog.e("helper re-seed failed (non-fatal)", it) }
    }

    /**
     * v0.1.56: synchronous dpkg/apt heal for the desktop path. The desktop
     * cascade in emux-x11 cannot heal a dirty journal on devices whose
     * in-rootfs helper copy predates emux-apt-repair; running the repair from
     * the app side BEFORE the X session starts makes the cascade's apt install
     * of Xvfb & friends actually succeed.
     */
    fun desktopPreRepair(): String? {
        // v0.1.58: wait (bounded) for any IN-PROCESS apt run (the app-start gap
        // install of the desktop stack) to finish before judging dpkg state -
        // apt reports "dpkg was interrupted" for a journal that is merely IN
        // USE, and the desktop cascade then raced the multi-minute install
        // (field pane: "E: dpkg was interrupted ... apt install failed").
        var note: String? = null
        Companion.progressNote?.invoke("pre-repair: waiting for background apt lock (up to 90 s)...")
        val got = runCatching {
            // v0.2.42: 90 s was too short to out-wait a running background
            // repair; proceeding unlocked launched clients INTO the repair's
            // library swaps (the signal-11 wave). Four minutes of narrated
            // waiting beats a segfaulted session.
            Companion.aptLock().tryLock(240, java.util.concurrent.TimeUnit.SECONDS)
        }.getOrDefault(true)
        Companion.progressNote?.invoke(
            if (got) "pre-repair: apt lock acquired"
            else "pre-repair: apt lock STILL BUSY after 90 s - proceeding anyway")
        try {
        runCatching {
            val spec = DistroCatalog.requested(ctx)
            if (spec.pkg != PkgManager.APT) return null
            val dest = FsLayout.linuxRoot(ctx)
            if (!File(dest, "etc").isDirectory) return null
            val proot = FsLayout.prootBin(ctx)
            if (!proot.isFile) return null
            // v0.1.72: the core install used to sit BEHIND the dirty-journal
            // gate - on a clean-but-incomplete device (journal healed, Xvfb
            // still missing) it never ran and the desktop kept waiting. Gate
            // only the journal repair; always heal missing CORE packages.
            installLinkFixShim(dest)
            installCaptureConv(dest)
            if (aptJournalDirty(dest) || dpkgHalfConfigured(dest) || dpkgLibcBroken(dest) ||
                !File(dest, "var/lib/emux-repair.stamp").isFile) {
                Companion.progressNote?.invoke("pre-repair: dpkg journal dirty - repairing (usually < 1 min)...")
                val r = runRepairProot(proot, dest)
                com.emux.EmuXLog.i("desktopPreRepair: ok=${r.ok} tail=${r.tail.take(200)}")
                note = "dpkg repair ok=${r.ok}: " + r.tail.take(180)
            }
            // v0.1.65: install the X-server CORE from the app side (LD_PRELOAD
            // belt + aptLock + settle pass) instead of leaving it to the
            // in-guest cascade - by the time the X session starts, Xvfb is
            // either present or the pane history says exactly why not.
            // v0.2.2 FIELD FIX: gate on the WHOLE desktop stack, not just
            // Xvfb+id. Field pane (v0.2.1): Xvfb up (xvfb.log keysym warnings,
            // attempt rc=0) but "capture: waiting - X-up marker=true
            // toolchain=false" and "no window manager found", with dpkg.log
            // stuck at "status half-configured xvfb/libc-bin". Xvfb existed
            // (half-configured), so the old gate skipped installing; the
            // capture toolchain (xwd/xwdtopnm/pnmtoplainpnm/nc) and a WM
            // stayed missing; and the in-guest cascade that tried to install
            // them runs INSIDE the desktop session - so every respawn /
            // tab-revisit killDesktopGuests() shot apt mid-configure, which
            // is what manufactures the half-configured journal that breaks
            // the next attempt. Installing the full stack host-side (aptLock
            // + bounded run) puts it out of reach of session teardown, and
            // the guest cascade then has nothing left to apt.
            val required = listOf(
                "usr/bin/Xvfb",      // xvfb
                "usr/bin/id",        // coreutils (maintainer scripts need it)
                "usr/bin/xkbcomp",   // x11-xkb-utils
                "usr/bin/xwd",       // x11-apps (grab)
                "usr/bin/xwdtopnm",  // netpbm (XWD -> PNM converter)
                "usr/bin/pnmtoplainpnm", // netpbm (PNM -> text)
                "usr/bin/openbox",   // window manager (else blank root window)
            )
            val ncPaths = listOf("usr/bin/nc", "bin/nc", "usr/bin/nc.openbsd", "bin/nc.openbsd")
            val ncOk = ncPaths.any { File(dest, it).isFile }
            val missing = required.filter { !File(dest, it).isFile }
            if (missing.isNotEmpty() || !ncOk) {
                val missNames = missing.map { it.substringAfterLast('/') } +
                    if (!ncOk) listOf("nc") else emptyList()
                Companion.progressNote?.invoke(
                    "pre-repair: desktop stack incomplete (missing: " +
                        missNames.joinToString(",") + ") - installing via apt, first run can take minutes")
                // coreutils first in the list: without /usr/bin/id and
                // /usr/bin/tr the maintainer scripts of EVERY later package
                // fail ("/bin/sh: 12: id: not found" on the field device).
                val pkgs = (listOf("coreutils") + spec.desktopPkgs).distinct()
                val rc = runAptProot(proot, dest, pkgs, "Installing desktop stack") { ph, _ ->
                    Companion.progressThrottled("$ph...")
                }
                com.emux.EmuXLog.i("desktopPreRepair stack install: ok=${rc.ok} tail=${rc.tail.take(200)}")
                note = (note ?: "") + " | stack install ok=${rc.ok}: " + rc.tail.take(180)
                // v0.2.2: an interrupted install (timeout/kill) leaves the
                // journal dirty again - heal it NOW so the next start does
                // not inherit "dpkg was interrupted".
                if (!rc.ok && aptJournalDirty(dest)) {
                    Companion.progressNote?.invoke("pre-repair: install interrupted - re-healing dpkg journal")
                    val r2 = runRepairProot(proot, dest)
                    com.emux.EmuXLog.i("desktopPreRepair re-heal: ok=${r2.ok}")
                    note = (note ?: "") + " | re-heal ok=${r2.ok}"
                }
            }
        }.onFailure { com.emux.EmuXLog.e("desktopPreRepair failed (non-fatal)", it) }
        } finally { if (got) runCatching { Companion.aptLock().unlock() } }
        return note
    }

    /**
     * v0.2.57 FIELD FIX: synchronous PRE-SESSION component install.
     * The furniture (tint2/pcmanfm/picom/xdotool/mousepad) used to be
     * installed by the in-session governor on first Desktop-tab open -
     * i.e. apt transactions ran WHILE guest clients execve'd, and proot's
     * exec-during-dpkg-transaction SIGSEGV wave ("proot warning: signal 11")
     * killed whichever client lost the race: sometimes the panel, sometimes
     * the icon layer, sometimes the input agent's xdotool. Doing the same
     * install HERE - host-driven, aptLock-held, before a single guest
     * process exists - keeps the session steady state transaction-free (see
     * the NEEDCORE/NEEDREST reasoning above) and makes a fresh device
     * turnkey: ~1 min of narrated progress, "components ready", session up.
     */
    fun installDesktopComponents(): String? {
        var note: String? = null
        val got = runCatching {
            Companion.aptLock().tryLock(240, java.util.concurrent.TimeUnit.SECONDS)
        }.getOrDefault(true)
        if (!got) return "components: apt lock busy - skipped (governor will retry)"
        try {
        runCatching {
            val spec = DistroCatalog.requested(ctx)
            if (spec.pkg != PkgManager.APT) return null
            val dest = FsLayout.linuxRoot(ctx)
            if (!File(dest, "etc").isDirectory) return null
            val proot = FsLayout.prootBin(ctx)
            if (!proot.isFile) return null
            val want = mapOf(
                "usr/bin/tint2" to "tint2",
                "usr/bin/pcmanfm" to "pcmanfm",
                "usr/bin/picom" to "picom",
                "usr/bin/xdotool" to "xdotool",
                "usr/bin/mousepad" to "mousepad",
                "usr/bin/xprop" to "x11-utils",
                "usr/bin/xsetroot" to "x11-xserver-utils",
            )
            val missing = want.filter { !File(dest, it.key).isFile }.values.distinct()
            if (missing.isEmpty()) return null
            Companion.progressNote?.invoke(
                "components: missing " + missing.joinToString(",") +
                    " - installing before session (first run ~1 min)")
            val r = runAptProot(proot, dest, missing, "Installing desktop components") { ph, _ ->
                Companion.progressThrottled("$ph...")
            }
            com.emux.EmuXLog.i("installDesktopComponents: ok=${r.ok} tail=${r.tail.take(200)}")
            note = "components install ok=${r.ok}: " + r.tail.take(180)
            // interrupted install => dirty journal => heal before session
            if (!r.ok && aptJournalDirty(dest)) {
                val r2 = runRepairProot(proot, dest)
                com.emux.EmuXLog.i("installDesktopComponents re-heal: ok=${r2.ok}")
                note = note + " | re-heal ok=${r2.ok}"
            }
        }.onFailure { com.emux.EmuXLog.e("installDesktopComponents failed (non-fatal)", it) }
        } finally { runCatching { Companion.aptLock().unlock() } }
        return note
    }

    fun startupRepairAsync(force: Boolean = false) {
        synchronized(Companion) {
            if (startupRepairStarted && !force) return
            startupRepairStarted = true
        }
        Thread {
            runCatching {
                // v0.2.49: clear accumulated orphans from previous app lives
                // before anything new tries to fork inside the guest.
                reapOrphanGuests { com.emux.EmuXLog.i(it) }
                // v0.1.91: yield the CPU to the first terminal/desktop start -
                // this repair's proot run (dpkg --configure -a, ghost purge)
                // raced session launches on slow tablets ("terminal is a
                // little slow to start"). Skip the courtesy when forced.
                // v0.1.93: longer courtesy AND background scheduling class -
                // the first-launch complaint was this repair (configuring
                // libc-bin/xvfb triggers for minutes on first run) eating the
                // CPU the cold terminal session needs; children (proot, dpkg)
                // inherit the nice level.
                if (!force) Thread.sleep(4_000)
                runCatching {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
                }
                val spec = DistroCatalog.requested(ctx)
                if (spec.pkg != PkgManager.APT) return@runCatching
                val dest = FsLayout.linuxRoot(ctx)
                if (!File(dest, "etc").isDirectory) return@runCatching
                val proot = FsLayout.prootBin(ctx)
                if (!proot.isFile) return@runCatching
                // v0.1.76: the bundled proot is now the current Termux build
                // (5.1.107.98, carries the termux/proot#377/#378 link-translation
                // fixes). Existing devices keep files/native-bridge from THEIR
                // install time unless we re-seed it here - bootstrap never
                // re-runs on a stamped rootfs.
                installNativeBridge()
                reseedHelpersIfNeeded(dest)   // v0.1.56: refresh OLD in-rootfs helpers
                // v0.1.94/95: the ld.so.cache login gate must reach EXISTING
                // rootfs trees - the profile is seeded at bootstrap, so devices
                // that updated later never received it and kept paying the
                // no-cache tax on every exec until the repair finished.
                runCatching {
                    val prof = File(dest, "etc/profile.d/emux.sh")
                    if (prof.isFile) {
                        val t = prof.readText()
                        val oldLine = "[ -s /etc/ld.so.cache ] || ldconfig 2>/dev/null\n"
                        when {
                            t.contains(".ldcfg.lock") -> { /* already v0.1.99 form */ }
                            t.contains(oldLine) -> prof.writeText(t.replace(oldLine, LDCONFIG_GATE_LINE))
                            else -> prof.appendText(LDCONFIG_GATE_LINE)
                        }
                    }
                }
                // v0.1.70: ALWAYS refresh the shim - the healthy-skip below
                // kept the v1 shim (which only overrode link(), a no-op on
                // arm64 where glibc builds link() on linkat()) on the field
                // device forever; v2 (link+linkat, copy fallback) never
                // arrived. ~13-67 KB copy, cheap every startup.
                installLinkFixShim(dest)
                installCaptureConv(dest)
                // v0.1.68: foreign-uid dpkg db rebuild (real-DAC EACCES path)
                val sw = hostDpkgDbSwap(dest)
                com.emux.EmuXLog.i("startupRepair: $sw")
                // v0.1.71: own file - the repair payload REWRITES
                // emux_selfcheck, which used to erase the DBSWAP line before
                // the pane could read it.
                runCatching {
                    File(dest, "tmp").mkdirs()
                    File(dest, "tmp/emux_dbswap").writeText(sw + "\n")
                }
                // v0.1.72: run the selfcheck EVERY launch (1 s proot shell) so
                // the pane always carries a FRESH shim verdict - previously
                // the file could be days old and every screenshot read stale
                // evidence.
                runCatching { runRepairProot(proot, dest, APT_SELFCHECK_PAYLOAD) }
                val shim = File(dest, "usr/local/lib/emux/libemux-linkfix.so")
                val stamp = File(dest, "var/lib/emux-repair.stamp")
                // v0.1.55: ALSO heal missing base/desktop packages. The field
                // device still had no coreutils (tr/tty/groups), no util-linux
                // script(1) (=> pty=false raw-pipe terminal) and no Xvfb
                // (=> desktop cascade every time), because those installs only
                // ever ran inside bootstrap - which never re-runs on a stamped
                // rootfs. Host-side file probes are cheap; install only gaps.
                val gaps = mutableListOf<String>()
                if (!File(dest, "usr/bin/script").isFile && !File(dest, "bin/script").isFile) gaps += "util-linux"
                if (!File(dest, "usr/bin/tr").isFile) gaps += "coreutils"
                if (!File(dest, "bin/bash").isFile && !File(dest, "usr/bin/bash").isFile) gaps += "bash"
                val desktopWanted = runCatching {
                    androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
                        .getBoolean("desktop_components", true)
                }.getOrDefault(true)
                // v0.1.57: Xvfb alone is NOT a working desktop - the capture
                // pipeline needs xwd (x11-apps), pnmtoplainpnm (netpbm) and nc
                // (netcat-openbsd) too; probing only Xvfb left devices with a
                // running X server and zero frames ("waiting for X frames").
                if (desktopWanted && (!File(dest, "usr/bin/Xvfb").isFile ||
                            !File(dest, "usr/bin/pnmtoplainpnm").isFile ||
                            !(File(dest, "usr/bin/xwd").isFile || File(dest, "usr/local/bin/xwd").isFile) ||
                            !(File(dest, "usr/bin/nc").isFile || File(dest, "bin/nc").isFile))
                ) gaps += spec.desktopPkgs
                // v0.1.94: half-configured packages (interrupted REST pass on
                // the field device) count as dirty - the healthy-skip below
                // must not paper over an unfinished db.
                val dirty = aptJournalDirty(dest) || runCatching {
                    File(dest, "var/lib/dpkg/status").readText()
                        .let { it.contains("half-configured") || it.contains("half-installed") }
                }.getOrDefault(false)
                if (!force && !dirty && shim.isFile && stamp.isFile && gaps.isEmpty()) return@runCatching
                installLinkFixShim(dest)
                installCaptureConv(dest)
                if (dirty || force || !stamp.isFile) {
                    val r = runRepairProot(proot, dest)
                    com.emux.EmuXLog.i("startupRepair: ok=${r.ok} tail=${r.tail.take(200)}")
                }
                // v0.1.66 ADAPTIVE SECCOMP PROBE: the field device kept hitting
                // status-old EACCES even with the shim loaded and LD_PRELOAD
                // set - consistent with proot's seccomp avoider mangling the
                // link/linkat fallback on this kernel. Ask the device itself:
                // selfcheck once normally, once with PROOT_NO_SECCOMP=1; if
                // only the latter links OK, persist the flag so EVERY proot
                // run (terminal + desktop sessions included) uses it.
                runCatching {
                    val sc = File(dest, "tmp/emux_selfcheck")
                    val t1 = runCatching { sc.readText() }.getOrDefault("")
                    if (t1.contains("LINK_FAIL") && !FsLayout.prootNoSeccompFlag(ctx).isFile) {
                        runRepairProot(proot, dest, APT_SELFCHECK_PAYLOAD, noSeccomp = true)
                        val t2 = runCatching { sc.readText() }.getOrDefault("")
                        com.emux.EmuXLog.i("seccomp probe: plain=${t1.lineSequence().firstOrNull { it.startsWith("LINK_") }} noseccomp=${t2.lineSequence().firstOrNull { it.startsWith("LINK_") }}")
                        if (t2.contains("LINK_OK")) {
                            FsLayout.prootNoSeccompFlag(ctx).writeText("1")
                            com.emux.EmuXLog.i("PROOT_NO_SECCOMP enabled for this device")
                        }
                    }
                }
                if (gaps.isNotEmpty()) {
                    val pkgs = gaps.distinct()
                    com.emux.EmuXLog.i("startupRepair: installing missing components: $pkgs")
                    val r2 = runAptProot(proot, dest, pkgs, "Installing missing components") { _, _ -> }
                    com.emux.EmuXLog.i("startupRepair install: ok=${r2.ok} tail=${r2.tail.take(200)}")
                }
            }.onFailure { com.emux.EmuXLog.e("startupRepair failed (non-fatal)", it) }
        }.apply { isDaemon = true; name = "emux-startup-repair" }.start()
    }

    /** apk inside the bundled proot — guest DNS/certs/triggers are all native. */
    private fun runApkProot(
        proot: File, dest: File, pkgs: List<String>, phase: String, report: (String, Int) -> Unit,
    ): ApkRun {
        // v0.1.34 CRITICAL FIX (field probe, Android 12 MRDI-W09): proot is a
        // Termux build whose COMPILED-IN temp dir is /data/data/com.termux/
        // files/usr/tmp — nonexistent and uncreatable on any non-Termux device.
        // With no writable scratch dir proot dies with "can't create glue
        // rootfs" / "execve(/sbin/apk): Permission denied".
        // v0.1.35 CORRECTION (field probe round 2): proot reads PROOT_TMP_DIR,
        // NOT TMPDIR — v0.1.34 set the wrong variable and the error survived.
        // TMPDIR stays "/tmp" for the GUEST (rootfs /tmp exists and is writable
        // through proot); the host scratch dir goes into PROOT_TMP_DIR. The
        // v0.1.34 same-path bind was dropped by proot ("sanitizing the guest
        // path … No such file or directory") because Android cache paths do
        // not exist inside the guest — so no extra bind at all now.
        val hostTmp = prootTmp(ctx)
        val nb = FsLayout.nativeBridgeDir(ctx)
        val argv = listOf(
            proot.absolutePath, "-0", "-r", dest.absolutePath,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-w", "/",
            "/sbin/apk", "add", "--arch", archName(), "--no-cache",
        ) + pkgs
        val env = mutableMapOf(
            "LD_LIBRARY_PATH" to nb.absolutePath,
            "PROOT_TMP_DIR" to hostTmp.absolutePath,
            "TMPDIR" to "/tmp",
            "HOME" to "/root",
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG" to "C",
            // v0.1.36: point proot at the shipped companion loader (see
            // installNativeBridge) — without it every guest execve fails.
            "PROOT_LOADER" to File(nb, "loader").absolutePath,
        )
        val l32 = File(nb, "loader32")
        if (l32.isFile) env["PROOT_LOADER_32"] = l32.absolutePath
        return runApkProcess(argv, env, pkgs, phase, report)
    }

    /**
     * v0.1.34: host-writable scratch dir for the bundled proot (see runApkProot).
     * cacheDir-based, always writable by us, and proot's temp files are pure
     * scratch, so OS cleanup is harmless.
     */
    internal fun prootTmp(ctx: android.content.Context): File =
        File(FsLayout.safeTmp(ctx), "proot").apply { mkdirs() }

    /** apk host-side via the musl loader (fallback; Android has no /etc/resolv.conf). */
    private fun runApkLoader(
        loader: File, dest: File, pkgs: List<String>, phase: String, report: (String, Int) -> Unit,
    ): ApkRun {
        val argv = listOf(
            loader.absolutePath, "--library-path",
            "${File(dest, "lib").absolutePath}:${File(dest, "usr/lib").absolutePath}",
            File(dest, "sbin/apk").absolutePath,
            "--root", dest.absolutePath,
            "--arch", archName(),
            "add", "--no-cache",
        ) + pkgs
        val env = mapOf(
            "TMPDIR" to File(dest, "tmp").absolutePath,
            "HOME" to File(dest, "root").absolutePath,
            "PATH" to "/system/bin:/system/xbin",
            "LANG" to "C",
        )
        return runApkProcess(argv, env, pkgs, phase, report)
    }

    /** v0.1.58: bounded-waitable mutex for proot apt/dpkg runs (ReentrantLock
     *  so desktopPreRepair can tryLock instead of blocking the desktop start
     *  forever behind a multi-minute gap install). */
    private inline fun <T> withAptLock(
        timeoutMs: Long = 12 * 60_000L,
        onBusy: () -> T,
        block: () -> T,
    ): T {
        // v0.2.1 FIELD FIX: this used to be an UNBOUNDED lock(). A zombie
        // holder (see runApkProcess: pipe-read that never reached EOF) made
        // every later apt/dpkg run - including desktopPreRepair on every
        // Desktop-tab open - wait behind it forever. Runs are now capped
        // (runApkProcess waitFor), so a bounded wait longer than any single
        // run cap is always sufficient; past it we bail out instead of
        // wedging the UI coroutine.
        Companion.progressThrottled("waiting for apt lock (up to ${timeoutMs / 60_000} min)...")
        val got = runCatching {
            Companion.aptLock().tryLock(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        if (!got) {
            com.emux.EmuXLog.e("apt lock still busy after ${timeoutMs / 60_000} min - skipping this run")
            Companion.progressNote?.invoke("apt lock busy - skipped (another repair still running)")
            return onBusy()
        }
        try { return block() } finally { Companion.aptLock().unlock() }
    }

    private fun runApkProcess(
        argv: List<String>,
        env: Map<String, String>,
        pkgs: List<String>,
        phase: String,
        report: (String, Int) -> Unit,
        timeoutMs: Long = 15 * 60_000L,
    ): ApkRun = runCatching {
        val pb = ProcessBuilder(argv)
        pb.environment().clear()
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)
        // v0.2.1 FIELD FIX (the desktop hang): output goes to a FILE, not a
        // pipe. The old BufferedReader(p.inputStream).forEachLine only
        // returns at pipe EOF, i.e. when EVERY writer closes - a stuck or
        // destroyed proot can leave orphaned tracees holding the write end,
        // so this read blocked forever, holding aptLock, and every later
        // apt/dpkg run queued behind the zombie (Desktop tab sat at
        // "Starting X server..." with zero new lines, forever). A file has
        // no orphan-EOF problem: the wait below is bounded and orphans just
        // keep appending harmlessly.
        val log = File(ctx.cacheDir, "aptrun-" + pkgs.joinToString("_").take(40) + ".log")
        runCatching { log.parentFile?.mkdirs(); if (log.isFile) log.delete() }
        pb.redirectOutput(log)
        val p = pb.start()
        // Bounded wait in 3 s slices: keeps progress reporting live and makes
        // a hung guest incapable of wedging the desktop start path again.
        val deadline = System.currentTimeMillis() + timeoutMs
        var exited = false
        var lastLen = -1L
        while (!exited && System.currentTimeMillis() < deadline) {
            exited = p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            val len = runCatching { log.length() }.getOrDefault(-1L)
            if (len != lastLen) {
                lastLen = len
                report(phase, -1)
                Companion.progressThrottled("$phase: running...")
            }
        }
        if (!exited) {
            val mins = timeoutMs / 60_000L
            com.emux.EmuXLog.e("apk run TIMEOUT after $mins min: $phase - destroying forcibly")
            Companion.progressNote?.invoke("$phase: TIMEOUT after $mins min - killed, continuing")
            p.destroyForcibly()
            runCatching { p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) }
            return ApkRun(false, "TIMEOUT after $mins min\n" + tailOf(log))
        }
        val code = p.exitValue()
        val tail = tailOf(log)
        tail.lineSequence().forEach { com.emux.EmuXLog.i("apk: $it") }
        ApkRun(code == 0, tail)
    }.getOrElse { e ->
        com.emux.EmuXLog.e("apk ${pkgs.joinToString(",")} failed to even start", e)
        ApkRun(false, "${e.javaClass.simpleName}: ${e.message}")
    }

    /** Last [n] non-blank lines of a run log ("" if absent/unreadable). */
    private fun tailOf(f: File, n: Int = 15): String = runCatching {
        f.readLines().filter { it.isNotBlank() }.takeLast(n).joinToString("\n")
    }.getOrDefault("")

    /**
     * Last-ditch DNS fallback for apk (both inside proot and host-side): resolve
     * the repository hostnames with Java (Android's resolver) and rewrite
     * etc/apk/repositories with IP literals over http:// (https to a bare IP
     * fails certificate hostname validation). Alpine mirrors serve plain http
     * and apk follows https redirects where issued.
     *
     * v0.1.31 CRITICAL FIX: the old version rewrote ONLY the lines already in
     * the file — i.e. a SINGLE mirror (whichever the rotation had last seeded,
     * typically leaseweb/NL). From mainland-China networks that one foreign
     * host is routinely unreachable, and with one repository line apk has no
     * fallback → the last-resort attempt died exactly like the others (the
     * v0.1.30 field banner). We now emit IP-literal lines for EVERY known
     * mirror (preferred/working mirror first — it already proved reachable by
     * serving the rootfs tarball — then the built-in list, deduped), so apk's
     * own per-repository fallback walks the whole set on this network-hostile
     * path too.
     */
    private fun rewriteRepositoriesToIps(dest: File, preferredBase: String? = null): Boolean {
        val rc = File(dest, "etc/apk/repositories")
        return runCatching {
            val bases = LinkedHashSet<String>()
            branchUrl(preferredBase)?.let { bases += it }
            MIRRORS.forEach { m -> branchUrl(m)?.let { bases += it } }
            val out = LinkedHashSet<String>()
            for (b in bases) {
                val u = runCatching { java.net.URL(b) }.getOrNull() ?: continue
                val ip = runCatching { java.net.InetAddress.getByName(u.host).hostAddress }.getOrNull()
                    ?: continue
                out += "http://$ip${u.path}/main"
                out += "http://$ip${u.path}/community"
            }
            if (out.isEmpty()) return false
            replaceRegularFileWith(rc) { it.writeText(out.joinToString("\n") + "\n") }
            com.emux.EmuXLog.i("apk repositories rewritten to IP literals: $out")
            true
        }.getOrDefault(false)
    }

    /**
     * Point the rootfs' /dev entries at the ANDROID host's device nodes via
     * absolute symlinks (valid because host-side apk exec sees the real /dev;
     * under proot the whole host /dev is bind-mounted anyway). apk/openssl need
     * at minimum /dev/urandom.
     */
    private fun seedDevNodes(dest: File) {
        val dev = File(dest, "dev").apply { mkdirs() }
        for (n in listOf("null", "zero", "full", "random", "urandom", "tty")) {
            runCatching {
                val f = File(dev, n)
                val p = f.toPath()
                val attrs = runCatching {
                    java.nio.file.Files.readAttributes(
                        p, java.nio.file.attribute.BasicFileAttributes::class.java,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS)
                }.getOrNull()
                if (attrs == null) android.system.Os.symlink("/dev/$n", f.absolutePath)
            }
        }
    }

    /** Write etc/apk/repositories for <branch>/main + <branch>/community. */
    private fun seedApkRepositories(dest: File, mirrorBase: String?, rotate: Int = 0) {
        val branch = branchUrl(mirrorBase)
            ?: (MIRRORS[rotate.coerceIn(0, MIRRORS.size - 1)].trimEnd('/') + majorVersion())
        val rc = File(dest, "etc/apk/repositories")
        runCatching { rc.parentFile?.mkdirs() }
        replaceRegularFileWith(rc) {
            it.writeText("$branch/main\n$branch/community\n")
        }
        com.emux.EmuXLog.i("apk repositories -> $branch")
    }

    /** ".../alpine/v", ".../alpine/v3.20", full tarball URL → ".../alpine/v3.20". */
    private fun branchUrl(base: String?): String? {
        base ?: return null
        val clean = base.trim().trimEnd('/')
        Regex("^(.*?/v\\d+\\.\\d+)").find(clean)?.let { return it.groupValues[1] }
        if (clean.endsWith("/v")) return clean + majorVersion()
        return null
    }

    /** Max size accepted for a helper-script asset (real ones are <1 KiB). */
    private fun readBounded(input: java.io.InputStream, maxBytes: Long): ByteArray {
        val buf = ByteArray(8 * 1024)
        val out = java.io.ByteArrayOutputStream(buf.size)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw java.io.IOException("asset stream exceeded $maxBytes bytes")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
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
            // v0.1.31 CRITICAL FIX (root cause of the v0.1.30 field failure
            // "shell components could not be installed via apk" on Android 12):
            // net.dns1/net.dns2 were REMOVED in Android 8 (API 26) — on every
            // modern device the property lookup below returns EMPTY, so the
            // guest resolv.conf always fell back to 8.8.8.8/1.1.1.1, which
            // carrier networks (mainland-China especially, but also several
            // EU/mobile NATs) routinely block or poison on UDP/53. apk inside
            // proot then could not resolve ANY mirror and both proot attempts
            // died with DNS errors. Ask the ACTIVE Android network for its real
            // DNS servers instead (LinkProperties, API 21+) — those are provably
            // reachable from this device — and keep public resolvers only as
            // extra fallbacks (musl honours MAXNS=3 nameservers).
            val liveDns = runCatching {
                val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
                    as? android.net.ConnectivityManager
                val net = cm?.activeNetwork
                net?.let { cm?.getLinkProperties(it) }?.dnsServers
                    ?.mapNotNull { it.hostAddress }?.filter { it.isNotBlank() }?.distinct()
            }.getOrNull().orEmpty()
            val propDns = try {
                val cls = Class.forName("android.os.SystemProperties")
                val get = cls.getMethod("get", String::class.java)
                listOfNotNull(
                    get.invoke(null, "net.dns1") as? String,
                    get.invoke(null, "net.dns2") as? String,
                ).filter { it.isNotBlank() }.distinct()
            } catch (_: Exception) { emptyList() }
            val servers = (liveDns + propDns +
                listOf("8.8.8.8", "1.1.1.1", "223.5.5.5", "114.114.114.114"))
                .distinct().take(3)
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

    private fun postInstall(dest: File, spec: DistroSpec) {
        // v0.1.53: seed the link()->linkat() preload shim BEFORE any apt run
        // (both bootstrap branches reach postInstall before installDistroExtras),
        // so the very first dpkg invocation on link()-broken kernels works.
        installLinkFixShim(dest)
        installCaptureConv(dest)
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
            // v0.1.21 BUGFIX (desktop blank): the old line REPLACED PATH with
            // distro dirs only — every login shell therefore lost files/bin
            // (where emux-x11 / emux-capture live) and /system/bin, so
            // `emux-x11 openbox` died with "not found" inside the otherwise
            // healthy distro shell. Prepend distro dirs instead of overwriting,
            // and pin $EMUX_BIN + Android bins first. Plain string concatenation
            // (no Kotlin interpolation) keeps the shell $vars literal.
            it.writeText(
                "# v0.1.21: keep inherited PATH; prepend distro + helper dirs\n" +
                "export PATH=\"/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:\$PATH\"\n" +
                // v0.1.94 PERF: first login builds the cache if missing.
                // v0.1.99 PERF: ...in the BACKGROUND - the synchronous form
                // cost the first terminal prompt a full ldconfig under proot
                // (tens of seconds on slow tablets). Execs fall back to the
                // default lib paths until the cache lands.
                LDCONFIG_GATE_LINE +
                "[ -n \"\$EMUX_BIN\" ] && export PATH=\"\$EMUX_BIN:/system/bin:/system/xbin:\$PATH\"\n" +
                                "# v0.1.28: host-side SHELL=/system/bin/sh (util-linux script(1) needs a host shell);\n" +
                "# inside the distro, advertise the real shell to child programs.\n" +
                "if [ -x /bin/bash ]; then export SHELL=/bin/bash; else export SHELL=/bin/sh; fi\n" +
"export PS1='\\[\\e[38;5;39m\\]emux:\\w\\[\\e[0m\\] \\$ '\n" +
                "alias ll='ls -alF'\n" +
                // v0.1.47: package-manager comfort alias per distro family.
                (when (spec.pkg) {
                    PkgManager.APK ->
                        "# apk wrapper that works without root inside proot/chroot-lite\n" +
                            "alias apk='apk --no-interactive'\n"
                    PkgManager.APT ->
                        "# apt comfort for proot (fake-root, no init system)\n" +
                            "alias apt-install='apt-get install -y --no-install-recommends'\n"
                    PkgManager.PACMAN ->
                        "# pacman comfort alias\n" +
                            "alias pac='pacman -Sy'\n"
                    PkgManager.DNF ->
                        "# dnf comfort alias\n" +
                            "alias dnf-install='dnf -y install'\n"
                })
            )
        }
        // v0.1.47: seed a minimal /etc/hosts when the image lacks one (the
        // Alpine minirootfs ships WITHOUT it; sudo/sshd-style tools and some
        // X11 auth paths stall on localhost lookups otherwise). Debian/Ubuntu
        // images already carry one and are left untouched.
        runCatching {
            val hosts = File(dest, "etc/hosts")
            if (!hosts.isFile) {
                replaceRegularFileWith(hosts) {
                    it.writeText("127.0.0.1 localhost\n::1 localhost\n")
                }
            }
        }

        val osr = File(dest, "etc/os-release")
        runCatching {
            // v0.1.15 AUDIT FIX — content preservation. The old delete-and-recreate
            // ran unconditionally on a REGULAR os-release: it deleted the distro's
            // real file and replaced it with a 2-line stub, silently dropping
            // VERSION/ID_LIKE/PRETTY_NAME etc. (apk and lsb-style tooling parse
            // those). Now we only delete shapes that cannot be written to safely
            // (symlink/fifo/socket/device/dangling link); an existing regular file
            // is left untouched and merely gets the marker appended.
            val p = osr.toPath()
            val attrs = runCatching {
                java.nio.file.Files.readAttributes(
                    p, java.nio.file.attribute.BasicFileAttributes::class.java,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)
            }.getOrNull()
            if (attrs != null && !attrs.isRegularFile) {
                runCatching { java.nio.file.Files.deleteIfExists(p) }
            }
            if (!osr.isFile || osr.length() == 0L) {
                runCatching { osr.parentFile?.mkdirs() }
                // v0.1.47: stub matches the ACTIVE distro (was Alpine-hardcoded).
                runCatching { osr.writeText("NAME=\"${spec.label}\"\nID=${spec.id}\n") }
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
