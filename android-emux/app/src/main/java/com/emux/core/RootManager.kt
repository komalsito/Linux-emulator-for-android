package com.emux.core

import android.content.Context
import com.emux.core.FsLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

enum class RootMode { NONE, SU, MAGISK }

/**
 * Runtime root detection + a single guarded execution path.
 *
 * Design rules (see docs/ROOT.md):
 *  - The app NEVER requires root. Root is an opt-in accelerator used only for:
 *      1. putting our child processes in a better cpuset/cgroup (perf+battery),
 *      2. optional /proc tuning,
 *      3. real `proot`-free chroot of the distro (bind-mount-free exec).
 *  - Every privileged call goes through [su] so we can log, throttle and revoke it.
 */
class RootManager(private val ctx: Context) {

    @Volatile var mode: RootMode = RootMode.NONE
        private set

    private val suBinary: String? by lazy {
        // Probe common su locations without executing anything first.
        listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug/bin/su")
            .firstOrNull { File(it).canExecute() }
    }

    suspend fun detect(): RootMode = withContext(Dispatchers.IO) {
        val magiskInstalled = try {
            ctx.packageManager.getPackageInfo("com.topjohnwu.magisk", 0)
            true
        } catch (_: Exception) { false }

        mode = if (suBinary != null && probeSu()) {
            if (magiskInstalled) RootMode.MAGISK else RootMode.SU
        } else RootMode.NONE
        mode
    }

    @Volatile private var suProbeDone = false

    private fun probeSu(): Boolean {
        // v0.1.5 ANR FIX (verified against the shipped dex): RootManager.detect()
        // runs in MainActivity.onCreate's coroutine AND in TerminalService.onCreate.
        // It spawned `su -c id -u` with NO timeout. On devices where an su binary
        // EXISTS but Magisk/SuperSU is not configured (or shows a first-grant
        // prompt), the child blocks forever waiting on stdin/supervisor → the UI
        // thread never gets its frame → Input dispatching timed out → system kills
        // the app ("EmuX keeps stopping"), identically on every launch. That was
        // the real Install-button + general crash cause; guarded code paths never
        // even got a chance to run.
        if (suProbeDone) return mode != RootMode.NONE && probedOk
        suProbeDone = true
        val ok = try {
            val p = ProcessBuilder(suBinary!!, "-c", "id -u")
                .redirectErrorStream(true)
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .start()
            val reader = BufferedReader(InputStreamReader(p.inputStream))
            // Hard 2.5 s watchdog: destroy() unblocks both readLine() and waitFor().
            val wd = Thread {
                try { Thread.sleep(2_500); p.destroy() } catch (_: Exception) {}
            }.apply { isDaemon = true }
            wd.start()
            val out = try { reader.readLine() } catch (_: Exception) { null }
            val code = try { p.waitFor() } catch (_: Exception) { -1 }
            wd.interrupt()
            code == 0 && out?.trim() == "0"
        } catch (_: Exception) { false }
        probedOk = ok
        return ok
    }

    @Volatile private var probedOk = false

    /** Run a command as root. Returns stdout, or null if unavailable/refused. */
    suspend fun su(command: String, timeoutMs: Long = 10_000): String? =
        withContext(Dispatchers.IO) {
            if (mode == RootMode.NONE) return@withContext null
            try {
                val p = ProcessBuilder(suBinary!!, "-c", command)
                    .redirectErrorStream(true).start()
                val reader = BufferedReader(InputStreamReader(p.inputStream))
                val sb = StringBuilder()
                var done = false
                val t = Thread {
                    try { Thread.sleep(timeoutMs); if (!done) p.destroy() } catch (_: Exception) {}
                }.also { it.isDaemon = true; it.start() }
                reader.forEachLine { sb.append(it).append('\n') }
                done = true
                val ok = p.waitFor() == 0
                t.interrupt()
                if (ok) sb.toString().trimEnd() else null
            } catch (_: Exception) { null }
        }

    /**
     * Power/perf win when rooted: pin OUR pid into the big-core cpuset and raise
     * the group's cpu.shares so the scheduler doesn't over-pack small cores.
     * Without root this is a no-op — Android keeps us inside our cgroup anyway.
     */
    suspend fun applyCpuTuning(pid: Int) {
        if (mode == RootMode.NONE) return
        // Best-effort; failures are silent because they change nothing user-visible.
        su("echo $pid > /dev/cpuset/foreground/tasks 2>/dev/null")
        su("echo 768 > /dev/cpuctl/foreground/cpu.shares 2>/dev/null")
    }
}
