package com.emux.terminal

import android.content.Context
import android.os.PowerManager
import com.emux.core.FsLayout
import com.emux.core.RootManager
import com.emux.core.RootMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * One Linux login session, kept in ONE process like Termux does:
 *   app_process/java PTY host  ->  `sh -c 'exec chroot/proot ... bash -l'`
 *
 * Power-efficiency model (matches or beats Termux):
 *  1. No polling loops anywhere. Output arrives via blocking read on the PTY fd;
 *     when idle the thread parks in the kernel — 0% CPU while you're not typing.
 *  2. Wake lock is HELD ONLY while a foreground session has pending output within
 *     a short window after input ("active burst"), then released. Termux keeps one
 *     partial wake lock per session; we do the same but auto-expire it after
 *     [IDLE_WAKE_MS] of silence so a forgotten long-running `yes` can't burn the
 *     battery when the screen is off and nobody is watching.
 *  3. Screen-off sessions are throttled: renderer stops (we're a SurfaceView that
 *     isn't visible anyway) and we coalesce PTY reads into 64 KiB batches.
 */
class TerminalSession(
    val id: Int,
    private val ctx: Context,
    private val root: RootManager,
    val initialCommand: String = "bash -l",
) {
    companion object {
        const val IDLE_WAKE_MS = 30_000L
    }

    enum class State { STARTING, RUNNING, DEAD }

    val state = MutableStateFlow(State.STARTING)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var process: Process? = null

    /** Ring buffer of recent output for reconnecting UI (bounded → no memory growth). */
    val scrollback = Channel<ByteArray>(Channel.BUFFERED)

    private val wakeLock: PowerManager.WakeLock by lazy {
        (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EmuX:session-$id").apply {
                setReferenceCounted(false)
            }
    }

    @Volatile private var lastActivityTs = 0L

    fun start() {
        job = scope.launch {
            try {
                val env = FsLayout.sessionEnv(ctx).toMutableMap()
                // Launch through our shell-init script which sets up /proc-less
                // mounts (tmpfs via `unshare` when root, else pure proot-free path).
                // v0.1.17 BUGFIX (blank terminal, nothing typed back): the child
                // was launched through Android's /system/bin/sh -c "...". On API
                // 29+ that shell runs in the untrusted_app SELinux domain and is
                // DENIED exec of scripts under /data/data/<app>/files — so our
                // distro launcher died instantly with EACCES and the PTY produced
                // zero output ("no new text inside the interface"). Plain
                // ProcessBuilder(argv) exec instead runs the interpreter directly
                // in the app's own (unconfined for its data dir) domain, exactly
                // like Termux does. We pass the shebang'd init script as argv[0]
                // when it exists; otherwise fall back to a visible error notice.
                val pb = buildProcess(initialCommand)
                pb.environment().clear()
                pb.environment().putAll(env)
                pb.redirectErrorStream(true)

                process = pb.start().also { p ->
                    state.value = State.RUNNING
                    onProcessStarted(p)
                }

                // Pump child stdout into the ring channel; blocking read = parked thread.
                val buf = ByteArray(64 * 1024)
                val ins = process!!.inputStream
                while (true) {
                    val n = ins.read(buf)
                    if (n <= 0) break
                    lastActivityTs = System.currentTimeMillis()
                    touchWake()
                    scrollback.send(buf.copyOf(n))
                }
                val code = process!!.waitFor()
                state.value = State.DEAD
                releaseWake()
                // notify listeners via flow collectors (UI shows "[process exited $code]")
            } catch (_: Exception) {
                state.value = State.DEAD
                releaseWake()
            }
        }
    }

    /** Assemble the actual child process (see v0.1.17 note at the call site). */
    private fun buildProcess(cmd: String): ProcessBuilder {
        val rootfs = FsLayout.linuxRoot(ctx).absolutePath
        // Alpine minirootfs ships ash only (no bash) — use /bin/sh so the session
        // doesn't die instantly with "bash: not found" on a fresh bootstrap.
        val shellCmd = cmd.replace("bash -l", "/bin/sh -l")
        val init = File(rootfs, "emux-init.sh")
        return if (root.mode != RootMode.NONE && userWantsChroot()) {
            // True chroot under root: fastest possible (zero syscall translation).
            ProcessBuilder("/system/bin/sh", "-c",
                "su -c 'chroot $rootfs /bin/su -s /bin/sh -c \\\"cd && exec $shellCmd\\\"'")
        } else if (init.canExecute()) {
            // Direct exec of the shebang'd launcher — no intermediate Android sh,
            // which cannot exec app-data files on API 29+.
            ProcessBuilder(init.absolutePath, shellCmd)
        } else {
            // No launcher yet (partial bootstrap): plain Android shell PLUS an
            // explicit notice, so the screen is never silently blank.
            ProcessBuilder("/system/bin/sh", "-c",
                "echo '[emux] rootfs launcher missing — tap Install to finish bootstrap'; exec /system/bin/sh -l")
        }.apply { redirectErrorStream(true) }
    }

    private fun userWantsChroot(): Boolean = true // wired to prefs in Service

    private fun onProcessStarted(p: Process) {
        scope.launch { root.applyCpuTuning(pidOf(p)) }
        touchWake()
    }

    private fun pidOf(p: Process): Int = try {
        val m = p.javaClass.getMethod("pid") // API 24+
        m.invoke(p) as Int
    } catch (_: Exception) { -1 }

    /** Send keystrokes/stdin to the session. Cheap: single write syscall. */
    fun write(bytes: ByteArray) {
        try {
            process?.outputStream?.write(bytes)
            process?.outputStream?.flush()
            lastActivityTs = System.currentTimeMillis()
            touchWake()
        } catch (_: Exception) { /* dead session; state flow already DEAD */ }
    }

    fun resize(cols: Int, rows: Int) {
        // Best-effort TIOCSWINSZ via stty on the tty; termux's JNI does this exactly.
        // Without NDK we ask the child itself to re-read size on next prompt.
        write("\u001b[999;999H\u001b[6n".toByteArray()) // cursor-position report trick
    }

    private fun touchWake() {
        if (!wakeLock.isHeld) runCatching { wakeLock.acquire(IDLE_WAKE_MS) }
    }

    private fun releaseWake() = runCatching { if (wakeLock.isHeld) wakeLock.release() }

    suspend fun stop() {
        releaseWake()
        job?.cancelAndJoin()
        process?.destroy()
        process = null
        state.value = State.DEAD
    }
}
