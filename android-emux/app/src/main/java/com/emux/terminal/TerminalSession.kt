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

        // v0.1.22 BUGFIX (terminal echoed typed characters but NEVER ran a
        // command — "i can write on the terminal" with zero output): Android's
        // toybox `script` allocates the PTY and relays bytes, but it does NOT
        // set the pty slave as the child's controlling terminal (no TIOCSCTTY).
        // Alpine ash then runs with isatty(stdin)==true yet no ctty → job
        // control fails ("can't fork for job control: Inappropriate ioctl"),
        // SIGTTIN/SIGTTOU stop any foreground child, and every command you run
        // appears to do nothing forever. busybox script DOES call TIOCSCTTY.
        // Probe order therefore prefers a working busybox; toybox remains the
        // fallback (better echo/no-PTY than nothing at all).
        val CANDIDATES = listOf(
            "/system/bin/busybox",         // custom ROMs: full applet set, proper ctty
            "/system/xbin/busybox",        // classic custom-ROM location
            "/sbin/busybox",
            // v0.1.28 CORRECTION: AOSP toybox has NO script applet (verified
            // against the AOSP tree — toys/other and toys/pending contain no
            // script.c), so /system/bin/script only exists on a few OEM ROMs.
            // Five release cycles tuned quoting for this phantom binary while
            // stock devices fell through to raw pipes (no echo = "keyboard
            // dead"). The PRIMARY wrapper is now the rootfs's own util-linux
            // script(1), exec'd through the musl loader (TtyWrap.
            // setDistroCandidates) — installed by bootstrap via apk and
            // verified end-to-end (real pts + TIOCSCTTY job control).
            "/system/bin/script",          // OEM ROMs that ship it; harmless to probe
        )
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
                // v0.1.28: resolve the rootfs-backed PTY wrapper (util-linux
                // script(1) installed by bootstrap, exec'd through the musl
                // loader) BEFORE probing — on stock Android there is no
                // busybox and AOSP toybox has NO script applet at all
                // (verified against the AOSP tree), so the distro wrapper is
                // the ONLY PTY source on unrooted devices.
                TtyWrap.setDistroCandidates(ctx)
                // Launch through our shell-init script which sets up the
                // proot/loader session (see assets/bin/emux-init.sh).
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
        // v0.1.28: the bootstrap installs real bash into the rootfs (apk
        // extras), so "bash -l" is honored as-is now — the old forced rewrite
        // to /bin/sh was a workaround for minirootfs shipping ash only.
        // Apostrophes are still normalized to U+02BC so the quoting chain
        // (TtyWrap single-quoting, `export EMUX_ARGV1='…'`) can never break.
        val shellCmd = cmd.replace("'", "ʼ")
        val init = File(rootfsFile, "emux-init.sh")
        // v0.1.23 BUGFIX: the old gate was `init.isFile` ONLY — pointing at the
        // copy extracted INTO the rootfs by tar. On a partially-extracted /
        // interrupted bootstrap that file can be missing even though the
        // authoritative files/bin/emux-init.sh mirror (written directly by
        // installHelperScripts) is perfectly fine; we then fell into the
        // "launcher missing" branch and ran PLAIN Android sh forever — no
        // distro PATH, no emux-x11, no musl loader: exactly the reported
        // "bootstrapped but cannot type / black desktop". Accept either copy.
        val binMirror = File(File(ctx.filesDir, "bin"), "emux-init.sh")
        val haveLauncher = init.isFile || binMirror.isFile

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
            // v0.1.28 QUOTING FIX: the old form `su -c 'chroot $rootfs /bin/sh -l -c $shellCmd'`
            // passed the command UNQUOTED, so `emux-x11 openbox` became
            // `-c emux-x11` with $0=openbox — the WM argument was silently
            // eaten. Wrap the payload in escaped double quotes inside the
            // single-quoted su string (shellCmd is apostrophe-free by
            // construction, so this layer survives sh → su → sh intact).
            // Helper scripts now live in /usr/local/bin INSIDE the rootfs
            // (bootstrap v0.1.28), which the chroot login shell's PATH covers.
            val suSafe = shellCmd.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$").replace("`", "\\`")
            ProcessBuilder("/system/bin/sh", "-c",
                "su -c 'chroot $rootfs /bin/sh -l -c \"$suSafe\"'")
        } else if (haveLauncher) {
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
            // v0.1.21 BUGFIX: passed the command split across multiple argv slots;
            // TtyWrap's single-quoting + the inner shell's re-splitting mangled
            // it. v0.1.22 BUGFIX (the REAL reason typing still did nothing):
            // BOTH previous forms executed our app-data launcher through
            // /system/bin/sh — and since Android 10 the app data dir is mounted
            // nosuid|noexec for zygote-spawned processes (untrusted_app SELinux
            // domain). Every attempt to run files/linuxroot/emux-init.sh died
            // with EACCES ("Permission denied") before producing a single byte
            // of output → session exited instantly, respawn looped, terminal
            // looked permanently dead no matter how correct the quoting was.
            // Termux sidesteps this by never exec'ing from files/: it passes
            // code via `sh -c`. We do the same: read the launcher's text here
            // in Kotlin (plain file read — always allowed) and hand Android's
            // sh the script body plus our one clean argv line. The body contains
            // no apostrophes, so the wrapper's '<entry>' quoting keeps it intact
            // through script(1)'s inner shell too.
            // v0.1.23 BUGFIX: read from the files/bin/ mirror FIRST, then the
            // rootfs copy. The bin/ copy is written by bootstrap directly into
            // app-private storage; the rootfs one lands through tar extraction
            // and a partially-extracted tree can leave it truncated or as a
            // dangling symlink — readText() then threw, we fell into the
            // direct-invocation branch, and on Android 10+ that path dies
            // with EACCES on the noexec mount → instant silent exit ("cannot
            // type" survived every previous fix round).
            val candidates = listOf(
                File(File(ctx.filesDir, "bin"), "emux-init.sh"),
                File(FsLayout.home(ctx), "emux-init.sh"),
                init,
            )
            val body = candidates.firstNotNullOfOrNull { f ->
                runCatching { if (f.isFile) f.readText() else null }.getOrNull()
            } ?: ""
            // v0.1.24 BUGFIX: the old guard demanded the script body contain NO
            // apostrophes at all — but every shipped emux-init.sh has comments
            // with apostrophes, so this branch NEVER ran on real installs. We
            // fell into direct launcher invocation (`sh files/.../emux-init.sh`),
            // which Android 10+ SELinux DENIES for app-data paths → instant
            // silent death ("cannot type" persisted through v0.1.22/23 despite
            // the noexec fix being present). Now we sanitize the body instead:
            // drop single-quote characters from comment lines only (code lines
            // of our launcher are quote-free by design; anything else is
            // belt-and-braces removed too), keeping the '-quoted wrapper form
            // safe even after TtyWrap re-quotes it for script(1).
            val safeBody = body.lineSequence().joinToString("\n") { line ->
                if (line.trimStart().startsWith("#")) line.replace("'", "") else line
            }.replace("'", "")
            if (safeBody.isNotBlank()) {
                // v0.1.23 BUGFIX: the old line was `EMUX_ARGV1='…' exec sh` —
                // a variable assignment applied to the exec BUILTIN. POSIX is
                // explicit that assignments prefixed to `exec` affect only the
                // builtin itself and are NOT exported into the replaced
                // process, so EMUX_ARGV1 never existed inside emux-init.sh:
                // CMD fell back to "$*" (empty) and desktop sessions silently
                // ran a plain shell instead of "emux-x11 openbox" → black pane
                // forever. Fix: export the variable in its own statement BEFORE
                // the body runs, so it is genuinely part of the environment the
                // launcher inherits.
                ProcessBuilder(
                    "/system/bin/sh", "-c",
                    "export EMUX_ARGV1='$shellCmd'\n$safeBody",
                )
            } else if (haveLauncher) {
                // Fallback (body unreadable or contains apostrophes): direct
                // invocation — works on ROMs without the noexec mount, fails
                // loudly on ones that have it (visible via the notice below).
                val launcher = if (binMirror.isFile && runCatching { binMirror.length() > 40 }.getOrDefault(false)) binMirror else init
                ProcessBuilder("/system/bin/sh", launcher.absolutePath, shellCmd)
            } else {
                ProcessBuilder("/system/bin/sh", "-c",
                    "echo '[emux] rootfs launcher missing — tap Install to finish bootstrap'; exec /system/bin/sh -l")
            }
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
    // v0.1.22: order lives in TerminalSession.CANDIDATES (busybox first — see
    // ctty comment there). Kept as a single source of truth for the probe.
    private val candidates = TerminalSession.CANDIDATES

    /** Resolved wrapper argv prefix, or null if no PTY wrapper exists here. */
    @Volatile private var resolved: List<String>? = null
    @Volatile private var probed = false

    /** Which script(1) argument dialect the probed binary actually speaks. */
    enum class Dialect { TOYBOX, BBSTYLE }
    @Volatile private var dialect: Dialect = Dialect.TOYBOX

    /** True when the resolved wrapper gives the child a CONTROLLING terminal
     *  (busybox does; toybox script relays bytes but never calls TIOCSCTTY). */
    @Volatile var hasCtty = false
        private set

    private fun probe(): List<String>? {
        if (probed) return resolved
        synchronized(this) {
            if (probed) return resolved
            for (c in candidates) {
                val f = File(c)
                if (!f.canExecute()) continue
                val ok: Boolean = if (c.endsWith("busybox")) {
                    // busybox needs the `script` applet compiled in.
                    val bbDialectOk = runCatching {
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
                    // v0.1.22 CTTY PROOF: don't trust dialect alone — run an
                    // actual job-control command through the wrapper and verify
                    // output arrives AND the child sees a controlling tty. A
                    // wrapper that fails this is skipped so we can fall through
                    // to any other candidate instead of shipping sessions where
                    // commands silently hang (the reported symptom).
                    if (bbDialectOk) {
                        // v0.1.25 BUGFIX: previously a failed ctty check made this
                        // candidate return false and be SKIPPED entirely — combined
                        // with the broken verifyCtty quoting, that meant even a good
                        // busybox was discarded and no PTY wrapper survived. The
                        // dialect probe already proved bytes relay; keep the wrapper
                        // either way and just record whether ctty works.
                        hasCtty = verifyCtty(listOf(c, "script"))
                        if (!hasCtty) com.emux.EmuXLog.e("TtyWrap: $c script applet relays but FAILED ctty/job-control test — job control will misbehave")
                        true
                    } else false
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
                    val relayOk = when {
                        tryArgs(listOf("-q", "-f", "/dev/null", "true")) -> { dialect = Dialect.TOYBOX; true }
                        tryArgs(listOf("-q", "-c", "true", "/dev/null")) -> { dialect = Dialect.BBSTYLE; true }
                        else -> false
                    }
                    // v0.1.22: toybox script passes relay/dialect tests but does
                    // NOT give the child a controlling terminal. We still accept
                    // it as LAST resort (echo works; simple non-job-control
                    // commands usually work), but flag hasCtty=false so the UI
                    // can tell the user why Ctrl-Z/& may misbehave.
                    if (relayOk) hasCtty = verifyCtty(listOf(c))
                    relayOk
                }
                if (ok) {
                    com.emux.EmuXLog.i("TtyWrap: PTY wrapper = $c ($dialect, ctty=$hasCtty)")
                    resolved = when {
                        c.endsWith("busybox") -> listOf(c, "script")
                        else -> listOf(c)
                    }
                    break
                }
            }
            // v0.1.28: PRIMARY wrapper on stock devices — the rootfs' own
            // util-linux script(1) (installed by bootstrap via `apk add
            // util-linux`), exec'd through the musl loader:
            //   <rootfs>/lib/ld-musl-<arch>.so.1 --library-path <lib>:<usr/lib> <rootfs>/usr/bin/script
            // Verified end-to-end: allocates a real pts AND sets the
            // controlling terminal (TIOCSCTTY), so echo + job control work.
            // Legal to exec from app-data because the app targets SDK 28
            // (Android 10+ W^X exempts untrusted_app_27 — the Termux model).
            if (resolved == null) {
                val dp = distroPrefix
                if (dp != null) {
                    fun tryArgs(args: List<String>): Boolean = runCatching {
                        val pb = ProcessBuilder(dp + args)
                            .redirectErrorStream(true)
                            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                        pb.environment()["SHELL"] = "/system/bin/sh"
                        val p = pb.start()
                        val wd = Thread { try { Thread.sleep(3_000); p.destroy() } catch (_: Exception) {} }.apply { isDaemon = true }
                        wd.start(); val code = try { p.waitFor() } catch (_: Exception) { -1 }; wd.interrupt()
                        code == 0
                    }.getOrDefault(false)
                    // util-linux script runs its -c payload through $SHELL (or
                    // /bin/sh, which does NOT exist on Android 7–9) — the real
                    // session env sets SHELL=/system/bin/sh (FsLayout.sessionEnv).
                    val relayOk = when {
                        // util-linux script speaks the busybox dialect:
                        //   script [-q] -c COMMAND [TYPESCRIPT]
                        tryArgs(listOf("-q", "-c", "true", "/dev/null")) -> { dialect = Dialect.BBSTYLE; true }
                        tryArgs(listOf("-q", "-f", "/dev/null", "true")) -> { dialect = Dialect.TOYBOX; true }
                        else -> false
                    }
                    if (relayOk) {
                        hasCtty = verifyCtty(dp)
                        resolved = dp
                        com.emux.EmuXLog.i("TtyWrap: PTY wrapper = distro script via musl loader ($dialect, ctty=$hasCtty)")
                    } else {
                        com.emux.EmuXLog.e("TtyWrap: distro script exists but both arg dialects failed")
                    }
                }
            }
            probed = true
            return resolved
        }
    }

    /**
     * v0.1.28: argv prefix that runs the ROOTFS util-linux script(1) through
     * the musl loader, or null when the rootfs/loader/script are missing.
     * Computed once per process (recomputed if a later bootstrap pass installs
     * the components — see [setDistroCandidates]).
     */
    @Volatile private var distroPrefix: List<String>? = null
    @Volatile private var distroProbed = false

    fun setDistroCandidates(ctx: Context) {
        runCatching {
            val rootfs = com.emux.core.FsLayout.linuxRoot(ctx)
            val script = File(rootfs, "usr/bin/script")
            if (!script.isFile) return@runCatching
            val loader = File(rootfs, "lib").listFiles()?.firstOrNull {
                it.name.startsWith("ld-musl-") && it.name.endsWith(".so.1") && it.isFile
            } ?: return@runCatching
            val prefix = listOf(
                loader.absolutePath,
                "--library-path",
                "${File(rootfs, "lib").absolutePath}:${File(rootfs, "usr/lib").absolutePath}",
                script.absolutePath,
            )
            val changed = prefix != distroPrefix
            distroPrefix = prefix
            if (changed) synchronized(this) {
                // components appeared after an earlier probe (first launch:
                // sessions start before bootstrap finishes) — re-probe.
                if (probed && resolved == null) { probed = false }
                distroProbed = true
            }
        }
    }

    /**
     * v0.1.22 BUGFIX GROUNDWORK: prove the wrapper actually delivers output for
     * a shell that tries job control. Runs `<prefix> <sh -c 'ps'>` style test:
     * ash with no ctty hangs/behaves differently than with one. We check the
     * simplest observable property: a backgrounded pipeline completes and its
     * output reaches us within the watchdog window.
     */
    private fun verifyCtty(prefix: List<String>): Boolean = runCatching {
        // v0.1.25 BUGFIX: the old test was `sh -c 'sleep 0.1 & wait; echo CTTYOK'`
        // wrapped in single quotes — and apply() ALSO single-quotes every argv
        // entry for script's inner shell. Nested single quotes are impossible in
        // POSIX sh, so the quoting broke mid-string ("Unexpected token"), the
        // probe process exited non-zero, verifyCtty returned false for EVERY
        // candidate (including a perfectly good busybox), TtyWrap resolved to
        // null → NO PTY at all → raw pipes → fully buffered output → exactly the
        // reported symptom on stock Android where /system/bin/script exists but
        // busybox does not. Use apostrophe-free commands only: toybox/busybox
        // ps both print a TTY column ('?' when no controlling terminal).
        val inner = "sleep 0.1; ps -o tty= -p $$ ; echo CTTYOK"
        val args = when (dialect) {
            Dialect.TOYBOX -> listOf("-q", "-f", "/dev/null", inner)
            Dialect.BBSTYLE -> listOf("-q", "-c", inner, "/dev/null")
        }
        val pb = ProcessBuilder(prefix + args)
            .redirectErrorStream(true)
            .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
        // v0.1.28: util-linux script runs its -c payload via $SHELL — pin the
        // HOST shell (Android 7–9 have no /bin/sh) like the session env does.
        pb.environment()["SHELL"] = "/system/bin/sh"
        val p = pb.start()
        val sb = StringBuilder()
        val reader = Thread {
            try { p.inputStream.bufferedReader().forEachLine { sb.appendLine(it) } } catch (_: Exception) {}
        }.apply { isDaemon = true }
        reader.start()
        val wd = Thread { try { Thread.sleep(4_000); p.destroy() } catch (_: Exception) {} }.apply { isDaemon = true }
        wd.start(); val code = try { p.waitFor() } catch (_: Exception) { -1 }; wd.interrupt()
        // v0.1.25: CTTYOK proves the relay works; a TTY column that is NOT "?"
        // proves the child actually has a controlling terminal (busybox yes,
        // toybox script no). Accepting without ctty was the old behavior — we
        // keep it but only after the strict check fails, via the caller's
        // hasCtty flag, so sessions still get echo even on stock ROMs.
        code == 0 && sb.contains("CTTYOK") && !sb.contains("?")
    }.getOrDefault(false)

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
        // v0.1.24 BUGFIX (THE reason typing still did nothing on stock
        // Android): script(1) builds the child command as `sh -c '<inner>'`,
        // so every apostrophe inside an entry became a QUOTE TERMINATOR for
        // that inner shell. The launcher line we pass contains many
        // apostrophes — `/system/bin/sh` (toybox sh's own name!), `-c`, and
        // the `export EMUX_ARGV1='…'` wrapper — so quoting broke mid-string:
        // toybox sh received garbage like `xport EMUX_ARGV1=` → "not found",
        // the session died instantly, respawn looped, and the terminal looked
        // permanently dead even though bootstrap said "Bootstrapped".
        // POSIX single-quote semantics: close ', insert '\'' , reopen '.
        val inner = base.joinToString(" ") { "'" + it.replace("'", "'\\''") + "'" }
        pb.command(
            when (dialect) {
                Dialect.TOYBOX -> prefix + listOf("-q", "-f", "/dev/null", inner)
                Dialect.BBSTYLE -> prefix + listOf("-q", "-c", inner, "/dev/null")
            },
        )
    }
}
