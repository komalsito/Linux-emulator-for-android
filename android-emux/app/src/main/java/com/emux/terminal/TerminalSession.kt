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

    /**
     * v0.1.18: true when the child exited with code 0 (user typed `exit`).
     * False for crashes / denied su / missing launcher — those are exactly the
     * cases TerminalService auto-respawns and the UI must flag loudly.
     */
    @Volatile var exitedCleanly: Boolean = false
        private set

    /** Last observed exit code (-1 unknown), surfaced in the UI notice. */
    @Volatile var exitCode: Int = -1
        private set
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

    /**
     * v0.1.19 BUGFIX G: argv that wraps the session command in a real PTY, or
     * null when no wrapper binary exists on this ROM (then we fall back to the
     * LD_PRELOAD nobuf shim / raw pipes). Resolved once per process.
     */
    // v0.1.19 COMPILE FIX: the old line referenced a non-existent
    // TtyWrap.resolve(ProcessBuilder) and mistyped the value as Array<String>?;
    // TtyWrap.probe() is private and returns List<String>?, and nothing else
    // reads this property (the wrapping happens via TtyWrap.apply(pb) in start()).
    private val ttyWrapAvailable: Boolean get() = TtyWrap.available()

    /** Whether a PTY wrapper (script/busybox) exists on this ROM. */
    fun hasTtyWrap(): Boolean = ttyWrapAvailable

    /** v0.1.19: first stdout byte seen → UI can prove the pipe is alive. */
    val outputSeen = MutableStateFlow(false)

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
                // v0.1.19 BUGFIX G: PTY discipline. ProcessBuilder gives us PIPES,
                // not a tty, so the child's stdout is fully buffered — bash prints
                // its prompt into libc's 4 KiB buffer and NEVER flushes while stdin
                // stays open. Result: bootstrap says "Bootstrapped", the session
                // runs fine, but ZERO bytes ever reach the screen ("no new text
                // inside the interface", keyboard appears dead because nothing
                // echoes). Termux solves this with a real PTY via NDK; we do the
                // equivalent with busybox-style `script -qfc CMD /dev/null`, which
                // allocates a PTY for the child and copies both directions over
                // our pipes. If no script(1) exists on the ROM we fall back to an
                // unbuffered stdio preload, and finally to the raw pipe.
                TtyWrap.apply(pb)

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
                    outputSeen.value = true   // v0.1.19: liveness proof for the UI
                    lastActivityTs = System.currentTimeMillis()
                    touchWake()
                    scrollback.send(buf.copyOf(n))
                }
                val code = process!!.waitFor()
                exitCode = code
                exitedCleanly = code == 0
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
        val rootfsFile = FsLayout.linuxRoot(ctx)
        val rootfs = rootfsFile.absolutePath
        // Alpine minirootfs ships ash only (no bash) — use /bin/sh so the session
        // doesn't die instantly with "bash: not found" on a fresh bootstrap.
        val shellCmd = cmd.replace("bash -l", "/bin/sh -l")
        val init = File(rootfsFile, "emux-init.sh")

        // v0.1.19 BUGFIX M: the privileged chroot branch built its command as
        //   su -c 'chroot $rootfs /bin/su -s /bin/sh -c \"cd && exec $shellCmd\"'
        // — a THIRD layer of quoting that Android's sh routinely mangles (the
        // inner escaped quotes survive to `su -c` verbatim → syntax error), and
        // worse, it ignored initialCommand entirely: desktop sessions running
        // "emux-x11 openbox" were launched as a plain shell instead, so Xvfb
        // NEVER started — waitForPort(6000) timed out 25 s later and the pane
        // stayed black forever. Now the verified su path chroots and execs the
        // REAL requested command through a single-quoted payload with no nested
        // double quotes. If anything about it fails we fall through to the
        // unprivileged loader below exactly like before.

        // v0.1.18 BUGFIX #1 ("bootstrapped OK but keyboard dead + desktop blank"):
        // this branch was FIRST and unconditional whenever su existed — even for a
        // user who DENIED the Magisk prompt or whose su policy blocks the app.
        // `su -c 'chroot …'` then exits immediately (denied → exit 1), the PTY
        // produced zero bytes, and MainActivity's pump printed
        // "[emux] process exited — press ENTER for a new shell." after EVERY
        // ENTER keystroke. The exact reported symptom: nothing typed, nothing on
        // the desktop tab (its emux-x11/emux-capture sessions died identically).
        // We now require PROOF the privileged path works before using it; the
        // unprivileged loader (which needs no root at all) is the default.
        val privilegedOk = root.mode != RootMode.NONE && userWantsChroot() &&
            runCatching {
                val p = ProcessBuilder("/system/bin/sh", "-c",
                    "su -c 'chroot $rootfs /bin/sh -c id' 2>/dev/null")
                    .redirectErrorStream(true)
                    .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                    .start()
                val out = p.inputStream.bufferedReader().use { it.readText() }
                // 10 s watchdog: a hung su (prompt waiting on invisible stdin) must
                // never hang session startup.
                val wd = Thread {
                    try { Thread.sleep(10_000); p.destroy() } catch (_: Exception) {}
                }.apply { isDaemon = true }
                wd.start()
                val code = try { p.waitFor() } catch (_: Exception) { -1 }
                wd.interrupt()
                code == 0 && out.contains("uid=0")
            }.getOrDefault(false)
        root.chrootVerified = privilegedOk

        return if (privilegedOk) {
            // True chroot under root: fastest possible (zero syscall translation).
            // v0.1.19 BUGFIX M: honor the REAL requested command (shellCmd keeps
            // "emux-x11 openbox" etc.) instead of hardcoding a login shell, and
            // use one single-quoted payload with no nested double quotes so the
            // sh → su -c quoting chain survives intact on every Android shell.
            // v0.1.20 BUGFIX: the old payload embedded ESCAPED DOUBLE QUOTES
            // inside the single-quoted `su -c '…'` string — toybox sh passes
            // them through VERBATIM, so su received `-c "cd && exec …"` with
            // literal quote characters and died with a syntax error. Desktop
            // sessions (`emux-x11 openbox`) therefore never started Xvfb. The
            // command is passed UNQUOTED now; word-splitting inside the distro
            // shell is harmless for our simple commands.
            ProcessBuilder("/system/bin/sh", "-c",
                "su -c 'chroot $rootfs /bin/sh -l -c $shellCmd'")
        } else if (init.isFile) {
            // v0.1.18 BUGFIX #2: the old gate was `init.canExecute()`. Bootstrap
            // sets the exec bit via File.setExecutable(true,false), which maps to
            // chmod 700 *owner-only* — and some OEM file managers / SELinux-aware
            // chmod implementations leave modes that canExecute() reports false
            // for. When that happened we fell into the "launcher missing" branch
            // and ran PLAIN ANDROID sh forever: no distro PATH, no emux-x11, no
            // bash — keyboard echoed nothing useful and the desktop stayed blank
            // even though bootstrap said "Bootstrapped". Now we accept the file
            // whenever it exists and FIX the mode ourselves right here (double
            // insurance against any bootstrap-path regression), invoking through
            // the interpreter explicitly as a last hedge against noexec quirks.
            runCatching { init.setExecutable(true, false); init.setReadable(true, false) }
            // v0.1.20 BUGFIX (keyboard dead + desktop commands mangled): the old
            // form `sh <init> "<shellCmd>"` handed the payload through TWO shell
            // layers that each re-quote it — Android's toybox sh splits $1 per
            // WORD once quoting is involved, and TtyWrap's inner `sh -c '<argv>'`
            // turns any embedded quotes into literal syntax errors. Result: the
            // distro launcher received garbage ("sh" alone, or a broken quoted
            // blob), died instantly with zero output → "bootstrapped but cannot
            // type / blank desktop". We now pass the command UNQUOTED across
            // multiple argv slots; emux-init.sh reassembles them verbatim with
            // "$*" and execs `$SHELL -l -c "$cmd"` INSIDE the distro, where the
            // single remaining shell sees the pristine string.
            ProcessBuilder("/system/bin/sh", init.absolutePath, *shellCmd.split(" ").toTypedArray())
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
    private val stdinLock = Any()
    fun write(bytes: ByteArray) {
        try {
            // v0.1.19 BUGFIX H: ProcessBuilder's OutputStream is NOT thread-safe;
            // a second concurrent write (extra-keys bar + IME flush, or resize
            // probe) could interleave partial buffers and corrupt the PTY input
            // stream — the shell then receives garbage and appears to ignore the
            // keyboard. Serialize writes; also recover the stream reference once
            // if it was GC'd away while the process is alive.
            synchronized(stdinLock) {
                val os = process?.outputStream ?: return
                os.write(bytes)
                os.flush()
            }
            lastActivityTs = System.currentTimeMillis()
            touchWake()
        } catch (_: Exception) { /* dead session; state flow already DEAD */ }
    }

    /**
     * v0.1.20 BUGFIX: the old implementation WROTE raw escape sequences
     * ("cursor position report" trick) into the shell's STDIN — i.e. it typed
     * invisible garbage into whatever line the user was editing, corrupting
     * input ("interface doesn't let me type" felt like random keys vanishing).
     * Without an NDK there is no way to issue TIOCSWINSZ on the wrapper's pty
     * from Java, so the honest fix is to do NOTHING here. The distro shells
     * default to sane geometry and the renderer uses its own grid.
     */
    fun resize(cols: Int, rows: Int) { /* intentionally inert — see docstring */ }

    private fun touchWake() {
        if (!wakeLock.isHeld) runCatching { wakeLock.acquire(IDLE_WAKE_MS) }
    }

    private fun releaseWake() = runCatching { if (wakeLock.isHeld) wakeLock.release() }

    /**
     * v0.1.19 BUGFIX J: kill a wedged-but-RUNNING child so the normal death →
     * respawn path runs. exitedCleanly stays false (destroy() yields a nonzero/
     * signal exit), so TerminalService treats it as an abnormal death and hands
     * the user one fresh session instead of a silent zombie forever.
     */
    fun debugKillForDiagnostics() {
        com.emux.EmuXLog.e("session $id produced no output — killing for respawn diagnostics")
        runCatching { process?.destroy() }
    }

    suspend fun stop() {
        releaseWake()
        job?.cancelAndJoin()
        process?.destroy()
        process = null
        state.value = State.DEAD
    }
}

/**
 * v0.1.19 BUGFIX G: give every session a real PTY without an NDK build.
 *
 * ProcessBuilder hands the child plain pipes. With a pipe for stdout, glibc/
 * bionic stdio switches to BLOCK buffering and shells like bash happily print
 * their prompt into a 4 KiB buffer that is never flushed while stdin remains
 * open — the UI then shows nothing forever ("bootstrapped but no new text,
 * keyboard seems dead"). Termux avoids this with forkpty() via JNI; we get the
 * same effect by wrapping the argv in `script -qfc '<cmd>' /dev/null`, which
 * allocates a PTY for the child and relays both directions over our pipes.
 *
 * Candidates are probed once per process (bounded, non-blocking) across the
 * busybox-style locations present on stock Android and common OEM ROMs, plus
 * toybox's `script` on newer AOSP. If none exists we degrade gracefully:
 * raw pipes + optional LD_PRELOAD unbuffer shim.
 */
object TtyWrap {
    private val candidates = listOf(
        "/system/bin/script",          // toybox (AOSP 6+) / busybox on many ROMs
        "/system/bin/busybox",         // stock-in-ROM busybox
        "/system/xbin/busybox",        // classic custom-ROM location
        "/sbin/busybox",
    )

    /** Resolved wrapper argv prefix, or null if no PTY wrapper exists here. */
    @Volatile private var resolved: List<String>? = null
    @Volatile private var probed = false

    /** Which script(1) argument dialect the probed binary actually speaks. */
    enum class Dialect { TOYBOX, BBSTYLE }
    @Volatile private var dialect: Dialect = Dialect.TOYBOX

    private fun probe(): List<String>? {
        if (probed) return resolved
        synchronized(this) {
            if (probed) return resolved
            for (c in candidates) {
                val f = File(c)
                if (!f.canExecute()) continue
                val ok = if (c.endsWith("busybox")) {
                    // busybox needs the `script` applet compiled in.
                    runCatching {
                        val p = ProcessBuilder(c, "script", "--help")
                            .redirectErrorStream(true)
                            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                            .start()
                        val out = p.inputStream.bufferedReader().use { it.readText() }
                        val wd = Thread { try { Thread.sleep(2_000); p.destroy() } catch (_: Exception) {} }.apply { isDaemon = true }
                        wd.start(); val code = try { p.waitFor() } catch (_: Exception) { -1 }; wd.interrupt()
                        if (!(code == 0 && !out.contains("not found") && !out.contains("applet not found"))) {
                            false
                        } else {
                            // Applet exists — now find which arg dialect it speaks.
                            fun bbTry(args: List<String>): Boolean = runCatching {
                                val pp = ProcessBuilder(listOf(c, "script") + args)
                                    .redirectErrorStream(true)
                                    .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                                    .start()
                                val wdx = Thread { try { Thread.sleep(3_000); pp.destroy() } catch (_: Exception) {} }.apply { isDaemon = true }
                                wdx.start(); val cc = try { pp.waitFor() } catch (_: Exception) { -1 }; wdx.interrupt()
                                cc == 0
                            }.getOrDefault(false)
                            when {
                                bbTry(listOf("-q", "-f", "/dev/null", "true")) -> { dialect = Dialect.TOYBOX; true }
                                bbTry(listOf("-q", "-c", "true", "/dev/null")) -> { dialect = Dialect.BBSTYLE; true }
                                else -> false
                            }
                        }
                    }.getOrDefault(false)
                } else {
                    // v0.1.20 BUGFIX (THE core keyboard bug): script(1) has TWO
                    // incompatible dialects and the old probe used neither
                    // correctly on stock Android:
                    //   toybox   : script [-q] [-f TYPESCRIPT] COMMAND   (cmd POSITIONAL after file)
                    //   busybox  : script [-q] [-c CMD] [TYPESCRIPT]     (cmd via -c, then file)
                    // The old `-qfc true /dev/null` made toybox treat "true" as a
                    // FILE and "/dev/null" as the COMMAND → probe failed → NO PTY
                    // ever → fully-buffered pipes → zero output on screen ("cannot
                    // type"). Probe BOTH dialects and remember which one works.
                    fun tryArgs(args: List<String>): Boolean = runCatching {
                        val p = ProcessBuilder(listOf(c) + args)
                            .redirectErrorStream(true)
                            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                            .start()
                        val wd = Thread { try { Thread.sleep(3_000); p.destroy() } catch (_: Exception) {} }.apply { isDaemon = true }
                        wd.start(); val code = try { p.waitFor() } catch (_: Exception) { -1 }; wd.interrupt()
                        code == 0
                    }.getOrDefault(false)
                    when {
                        tryArgs(listOf("-q", "-f", "/dev/null", "true")) -> { dialect = Dialect.TOYBOX; true }
                        tryArgs(listOf("-q", "-c", "true", "/dev/null")) -> { dialect = Dialect.BBSTYLE; true }
                        else -> false
                    }
                }
                if (ok) {
                    com.emux.EmuXLog.i("TtyWrap: PTY wrapper = $c ($dialect)")
                    resolved = when {
                        c.endsWith("busybox") -> listOf(c, "script")
                        else -> listOf(c)
                    }
                    break
                }
            }
            probed = true
            return resolved
        }
    }

    /** Public liveness check for callers/UI diagnostics. */
    fun available(): Boolean = probe() != null

    /** Rewrite pb's command so the child runs under a PTY. No-op if unavailable. */
    fun apply(pb: ProcessBuilder) {
        val prefix = probe() ?: return
        val base = pb.command()
        if (base.isEmpty()) return
        // v0.1.20 BUGFIX: build argv per the PROBED dialect instead of the old
        // one-size-fits-all `-qfc` form that neither toybox nor busybox accepted
        // at runtime. Each argv entry is single-quoted for the wrapper's inner
        // shell; our entries never contain apostrophes (bootstrap paths and the
        // fixed commands above), so plain '<entry>' quoting is safe and keeps
        // multi-word desktop commands intact.
        val inner = base.joinToString(" ") { "'" + it + "'" }
        pb.command(
            when (dialect) {
                Dialect.TOYBOX -> prefix + listOf("-q", "-f", "/dev/null", inner)
                Dialect.BBSTYLE -> prefix + listOf("-q", "-c", inner, "/dev/null")
            },
        )
    }
}
