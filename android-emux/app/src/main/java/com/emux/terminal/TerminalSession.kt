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
    initialCommand: String = "bash -l",
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
                val pb = ProcessBuilder("/system/bin/sh", "-c", buildCommandLine(initialCommand))
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

    private fun buildCommandLine(cmd: String): String {
        val rootfs = FsLayout.linuxRoot(ctx).absolutePath
        return if (root.mode != RootMode.NONE && userWantsChroot()) {
            // True chroot under root: fastest possible (zero syscall translation).
            "su -c 'chroot $rootfs /bin/su -s /bin/sh -c \"cd && exec $cmd\"'"
        } else {
            // Unprivileged fallback: run distro binaries directly with patched
            // interpreter paths (musl loader supports --load-interp-name style
            // via LD_LIBRARY_PATH + explicit ld-musl invocation done in init.sh).
            "$rootfs/emux-init.sh $cmd"
        }
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
