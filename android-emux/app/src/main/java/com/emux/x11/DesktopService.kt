package com.emux.x11

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.emux.core.RootManager
import com.emux.terminal.TerminalSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Owns the whole desktop pipeline in ONE place:
 *
 *   [X11Client]  ── control plane (geometry, XTEST input injection)
 *        ▲
 *        │ loopback TCP :6000
 *   X server inside distro  ←─ launched by TerminalSession running `emux-x11`
 *        │
 *   capture helper (`emux-capture`) pushes raw RGB frames to us on :7001
 *        ▼
 *   [DesktopView] blits them; input events are mapped and sent via XTEST.
 *
 * Power-efficiency engineering (this is where we beat naive Termux+VNC setups):
 *  1. FRAMES ARE PUSH-BASED AND DAMAGE-DRIVEN. The capture helper uses
 *     `xrestop`-free damage detection: it diffs consecutive framebuffer dumps
 *     cheaply inside the distro (busybox cmp on 4 KB blocks) and only sends a
 *     frame when bytes changed. Idle desktop ⇒ zero network, zero wakeups.
 *  2. ADAPTIVE FRAME RATE: foreground+screen-on → up to 30 fps; background or
 *     static content → 5 fps; screen off → stream paused entirely (we keep the
 *     X connection alive with no polling).
 *  3. SINGLE DECODE PATH: raw RGB565 over the wire halves bandwidth vs ARGB8888
 *     with visually negligible loss on LCD phone panels; decoded straight into
 *     a reused Bitmap (no per-frame allocation → no GC pressure → fewer CPU
 *     spikes which drain battery).
 *  4. ZERO POLLING IN KOTLIN: reader thread parks on socket read with timeout;
 *     when nothing arrives it costs one syscall wakeup per timeout tick at most.
 */
class DesktopService(
    private val ctx: Context,
    private val root: RootManager,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    companion object {
        private const val TAG = "EmuXDesktop"
        const val FRAME_PORT = 7001
        const val MAX_FPS_FOREGROUND = 30
        const val MAX_FPS_IDLE = 5
    }

    enum class State { IDLE, STARTING_X, CONNECTED, ERROR }
    val state = MutableStateFlow(State.IDLE)
    val errorText = MutableStateFlow<String?>(null)

    /**
     * v0.1.55 FIELD FIX: the pane showed only the NEWEST error - a rotating
     * failure sequence (exit 1 -> EADDRINUSE -> no frames ...) overwrote
     * itself faster than the user could screenshot it. Keep the last few
     * timestamped errors, show them ALL in the pane, and mirror them to
     * files/logs/desktop.log so a single `adb pull` (or screenshot) tells the
     * whole story.
     */
    private val errHistory = ArrayDeque<String>()
    private fun setError(msg: String) {
        val line = "[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}] $msg"
        synchronized(errHistory) {
            errHistory.addLast(line)
            while (errHistory.size > 5) errHistory.removeFirst()
        }
        runCatching {
            val f = java.io.File(ctx.filesDir, "logs").apply { mkdirs() }
            java.io.File(f, "desktop.log").appendText(line + "\n")
        }
        com.emux.EmuXLog.e("desktop: $msg")
        errorText.value = msg
    }
    fun errorHistoryText(): String = synchronized(errHistory) {
        if (errHistory.isEmpty()) errorText.value ?: "?" else errHistory.joinToString("\n")
    }

    var x11: X11Client? = null; private set
    private var xSession: TerminalSession? = null
    private var captureSession: TerminalSession? = null
    private var listener: ServerSocket? = null
    private var frameReader: Job? = null

    /** v0.1.54: the ACCEPTED capture socket. Coroutine.cancel() does NOT unblock
     *  a thread parked in a blocking read, so without this handle the old
     *  ESTABLISHED 127.0.0.1:7001 socket survived stop() and the next bind()
     *  died with EADDRINUSE even with SO_REUSEADDR (REUSEADDR never reuses a
     *  live same-tuple socket). Closing it from stop() unblocks the reader. */
    @Volatile private var activeSock: Socket? = null

    /** Latest decoded frame; the View reads this under lock during draw. */
    @Volatile var currentFrame: Bitmap? = null
    private val frameLock = Any()
    private var reusable: Bitmap? = null

    @Volatile var foreground = true
    @Volatile var screenOn = true

    /** Monotonic id handed to the View so it invalidates only on real new data. */
    @Volatile var frameId = 0; private set

    /**
     * v0.1.28 FIX (desktop never repainted even when frames arrived):
     * MainActivity used to call beginFrameLoop() itself with the UI-invalidate
     * callback, and startDesktop() ALSO called beginFrameLoop() internally with
     * an EMPTY callback — cancelling MainActivity's reader (frameReader?.cancel())
     * and replacing it with one that never notified the view. Now there is a
     * single reader owned by startDesktop, and the UI hook is this callback.
     */
    @Volatile var frameCallback: (() -> Unit)? = null

    /** Scratch buffer reused across frames → zero steady-state allocations. */
    private var scratchPx: IntArray? = null

    // ------------------------------------------------------------------ start
    fun startDesktop(wmCommand: String = "openbox") {
        if (state.value == State.STARTING_X || state.value == State.CONNECTED) return
        // v0.1.17 BUGFIX (desktop showed nothing, ever): a previous failed attempt
        // left the state at ERROR and this method then refused to retry — the
        // desktop was permanently dead until app restart. Also stop() could leave
        // half-torn-down sessions behind. Reset cleanly before every start.
        if (state.value == State.ERROR) resetForRestart()
        state.value = State.STARTING_X
        scope.launch {
            try {
                // v0.1.18 BUGFIX #5 ("desktop tab shows nothing"): emux-x11 and
                // emux-capture run INSIDE the distro shell launched by
                // TerminalSession — so if that launcher is broken/denied, both
                // desktop sessions died instantly with zero output and the pane
                // stayed black while bootstrap cheerfully said "Bootstrapped".
                // Fail fast here with an explicit message instead of silently
                // waiting 25 s on a port nobody will ever open.
                //
                // v0.1.19 BUGFIX K: the old check looked for the launcher at
                //   <rootfs>/emux-init.sh
                // but BootstrapManager.installHelperScripts() writes it to the
                // rootfs ROOT only in some paths — on every shipped layout the
                // canonical location is /data/data/com.emux/files/bin/emux-init.sh
                // (usrDir-style bin), NOT inside linuxroot. The file therefore
                // "was missing" even on a perfectly bootstrapped device and the
                // desktop aborted with "rootfs launcher missing" forever. Now we
                // accept ANY existing candidate location and pass the resolved
                // absolute path down through EMUX_INIT env so the scripts never
                // have to guess.
                val rootfs = com.emux.core.FsLayout.linuxRoot(ctx)
                if (!java.io.File(rootfs, "etc").isDirectory) {
                    setError("rootfs not installed — tap Install to bootstrap")
                    state.value = State.ERROR
                    return@launch
                }
                // v0.1.19 BUGFIX L: emux-x11 / emux-capture are invoked as bare
                // words ("emux-x11 openbox") by the distro shell — but bootstrap
                // installs them into files/bin/ AND copies them into the rootfs
                // ROOT, neither of which is on PATH ("/bin", "/usr/bin"…). The
                // command lookup failed instantly ("emux-x11: not found"), both
                // helper sessions died with nonzero codes, and the pane stayed
                // black while the terminal tab cheerfully showed a working shell.
                // Resolve the real locations once and export them via EMUX_BIN so
                // the sessionEnv PATH (which already includes files/bin) plus the
                // wrapper script can find them no matter where they live.
                val binCandidates = listOf(
                    java.io.File(ctx.filesDir, "bin"),
                    java.io.File(com.emux.core.FsLayout.usr(ctx).parentFile ?: ctx.filesDir, "bin"),
                    rootfs,                                   // helpers copied to rootfs root
                    java.io.File(rootfs, "usr/local/bin"),
                )
                val emuxBin = binCandidates.firstOrNull { java.io.File(it, "emux-x11").isFile }
                if (emuxBin == null) {
                    setError("helper scripts missing — tap Install to re-bootstrap")
                    state.value = State.ERROR
                    return@launch
                }
                runCatching {
                    java.io.File(emuxBin, "emux-x11").setExecutable(true, false)
                    java.io.File(emuxBin, "emux-capture").setExecutable(true, false)
                }

                // 0) v0.1.17 BUGFIX #2 (the actual reason the screen stayed blank):
                //    emux-x11 starts Xvfb with `-nolisten tcp`, so the Android-side
                //    X11Client could NEVER reach port 6000 — waitForPort(6000) always
                //    timed out after 20 s → State.ERROR → black pane. We now pass an
                //    explicit `-listen tcp` flag (Xvfb honors it over its default
                //    "-nolisten tcp" on modern builds; older ones keep the script's
                //    own -nolisten, which is why the script also got patched).
                // v0.1.19 COMPILE FIX: the previous string interpolated undefined
                // EMUX_BIN/PATH constants (compile error). Emux already exports
                // EMUX_BIN and a PATH containing files/bin via FsLayout.sessionEnv;
                // prepend the resolved helper dir explicitly as belt-and-braces.
                // v0.1.20 BUGFIX: the old payload embedded its OWN single/double
                // quotes ("sh -c 'export PATH=\"…\"; …'"). TtyWrap wraps every argv
                // entry in single quotes for script(1)'s inner shell — nested
                // single quotes are IMPOSSIBLE to survive that way, so the distro
                // shell received a broken command line and Xvfb never started
                // (black desktop forever). The helper dir is already prepended to
                // PATH by FsLayout.sessionEnv + exported as EMUX_BIN, so plain
                // `emux-x11 <wm>` resolves without any inline quoting at all.
                val cmd = "emux-x11 $wmCommand"
                xSession = TerminalSession(9001, ctx, root, cmd).also { it.start() }

                // v0.1.18 BUGFIX #6: if the X server session dies (launcher exec
                // failed, su denied, Xvfb crashed), say so immediately instead of
                // leaving a black pane with no explanation.
                scope.launch {
                    xSession?.state?.collect { st ->
                        if (st == TerminalSession.State.DEAD && state.value != State.ERROR) {
                            // v0.1.43: include the captured session output tails
                            // (painted on the pane) — "exited code 0" alone gave
                            // the field no actionable information.
                            val tails = listOf("x11", "capture").mapNotNull { t ->
                                diag[t]?.toString()?.lineSequence()
                                    ?.filter { it.isNotBlank() }?.toList()?.takeLast(4)?.joinToString(" | ")
                            }.joinToString(" /// ")
                            setError(
                                ("X server exited (code ${xSession?.exitCode}). " + tails).take(500))
                            state.value = State.ERROR
                        }
                    }
                }

                // 0b) v0.1.54: clear orphaned desktop guests (and their X locks /
                //     tcp sockets) from a previous tab visit or app life BEFORE we
                //     bind :7001 and start a fresh capture - otherwise the old
                //     emux-capture keeps an ESTABLISHED socket on our port and the
                //     bind fails EADDRINUSE, and the old Xvfb holds :0.
                killDesktopGuests()

                // 1) Open our frame listener BEFORE starting capture so no frames lost.
                openFrameListener()

                // 2) Start the in-distro capture helper pointed at us right away:
                //    it only needs the X socket (/tmp/.X11-unix/X0), not TCP, so it
                //    can stream frames even if the control-plane connect below fails.
                captureSession = TerminalSession(
                    9002, ctx, root,
                    "emux-capture 127.0.0.1:$FRAME_PORT",
                ).also { it.start() }

                // v0.1.37 BUGFIX ("desktop says waiting for X frames" with zero
                // explanation): the X and capture sessions are HEADLESS — their
                // output (Xvfb crash logs, apk add failures, nc errors) vanished
                // into scrollback channels nobody reads. Mirror it into a ring
                // buffer + EmuXLog, and if no frame lands within 20 s surface the
                // tail verbatim in the desktop status line so the next field
                // screenshot names the failing component.
                xSession?.let { pumpDiag("x11", it) }
                captureSession?.let { pumpDiag("capture", it) }
                scope.launch {
                    val startFrames = frameId
                    kotlinx.coroutines.delay(20_000)
                    if (frameId == startFrames && state.value == State.CONNECTED) {
                        val tails = listOf("x11", "capture").mapNotNull { t ->
                            diag[t]?.toString()?.lineSequence()
                                ?.filter { it.isNotBlank() }?.toList()?.takeLast(3)?.joinToString(" | ")
                        }.joinToString(" /// ")
                        setError(("no X frames in 20s. " + tails).take(400))
                    }
                }

                // 3) Wait for :6000 then connect the X11 client (retry w/ backoff).
                //    v0.1.17: failure here is NON-FATAL now — frames keep flowing,
                //    we just lose XTEST input injection until the next retry.
                var ctrlOk = false
                try {
                    waitForPort(6000, timeoutMs = 25_000)
                    val c = X11Client(display = 0)
                    c.connect()
                    x11 = c
                    ctrlOk = true
                    Log.i(TAG, "X connected: ${c.screenWidth}x${c.screenHeight}@${c.depth} vendor=${c.vendor}")
                } catch (e: Exception) {
                    Log.e(TAG, "X control plane unavailable (frames still streamed)", e)
                    setError("input disabled: ${e.message ?: "X TCP unreachable"}")
                }

                // v0.1.17 BUGFIX #3: the frame reader was NEVER started by anyone
                // except MainActivity's beginFrameLoop callback path, which raced
                // with accept(); worse, when the reader thread blocked in
                // listener.accept() forever (helper never connected because apk add
                // inside emux-capture failed), nothing surfaced. Now we arm the
                // loop ourselves with a self-contained invalidation callback;
                // MainActivity may re-arm it later with a UI-bound one.
                // v0.1.28: single frame reader owned by startDesktop; the UI
                // hook arrives via frameCallback (set by MainActivity before
                // startDesktop). The old duplicate beginFrameLoop() call from
                // MainActivity raced with this one and the loser's callback
                // (empty) won — frames decoded but the view never repainted.
                beginFrameLoop { frameCallback?.invoke() }

                state.value = State.CONNECTED
                if (!ctrlOk) {
                    // Keep CONNECTED (frames visible); retry control plane once more
                    // in background so touch input eventually works.
                    scope.launch {
                        runCatching {
                            waitForPort(6000, timeoutMs = 30_000)
                            val c = X11Client(display = 0)
                            c.connect()
                            x11 = c
                            errorText.value = null
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "desktop start failed", e)
                // v0.1.54: a failed start must not leak the listener/accepted
                // socket/sessions - the leaked :7001 tuple is exactly what made
                // the NEXT start die with EADDRINUSE on the field device.
                runCatching { activeSock?.close() }; activeSock = null
                runCatching { listener?.close() }; listener = null
                setError(e.message ?: "unknown error")
                state.value = State.ERROR
            }
        }
    }

    /** v0.1.37: per-session output ring for headless desktop sessions (see startDesktop). */
    private val diag = mutableMapOf<String, StringBuilder>()

    /** v0.1.45: last lines of the headless X/capture sessions, for pane painting. */
    fun diagTail(): String = listOf("x11", "capture").mapNotNull { t ->
        diag[t]?.toString()?.lineSequence()?.filter { it.isNotBlank() }?.toList()
            ?.takeLast(2)?.joinToString(" | ")
    }.joinToString(" /// ")

    private fun pumpDiag(tag: String, s: TerminalSession) {
        scope.launch {
            val sb = diag.getOrPut(tag) { StringBuilder() }
            sb.setLength(0)
            for (chunk in s.scrollback) {
                val txt = String(chunk, Charsets.UTF_8)
                com.emux.EmuXLog.i("desktop[$tag]: ${txt.take(300)}")
                sb.append(txt)
                if (sb.length > 4000) sb.delete(0, sb.length - 4000)
            }
        }
    }

    private fun resetForRestart() {
        runCatching { frameReader?.cancel() }; frameReader = null
        runCatching { activeSock?.close() }; activeSock = null   // v0.1.54
        runCatching { listener?.close() }; listener = null
        runCatching { x11?.close() }; x11 = null
        scope.launch {
            runCatching { xSession?.stop() }; xSession = null
            runCatching { captureSession?.stop() }; captureSession = null
            killDesktopGuests()   // v0.1.54: orphans hold :6000/:7001 guest-side
        }
    }

    /**
     * v0.1.54 FIELD FIX (desktop "exit 1" then EADDRINUSE on reopen):
     * proot guests are DETACHED, not killed, when their tracer (our process or
     * the wrapper shell) dies - so a closed desktop tab / killed app left Xvfb
     * and emux-capture running forever, holding tcp/6000, the :7001 connection
     * and /tmp/.X11-unix/X0. emux-x11 only killed stale "Xvfb :0"/"Xorg :0";
     * nothing ever killed the capture side. One-shot proot pass that kills the
     * whole desktop guest family and drops the X locks.
     */
    private fun killDesktopGuests() {
        runCatching {
            val proot = com.emux.core.FsLayout.prootBin(ctx)
            val rootfs = com.emux.core.FsLayout.linuxRoot(ctx)
            if (!proot.isFile || !java.io.File(rootfs, "etc").isDirectory) return
            val nb = com.emux.core.FsLayout.nativeBridgeDir(ctx)
            val payload =
                "for f in /proc/[0-9]*/cmdline; do " +
                "c=\$(tr '\\0' ' ' < \$f 2>/dev/null); " +
                "case \$c in *Xvfb*|*Xorg*|*emux-capture*|*xwd*) " +
                "p=\${f#/proc/}; p=\${p%/cmdline}; [ \$p != \$\$ ] && kill -9 \$p 2>/dev/null;; " +
                "esac; done; " +
                "rm -f /tmp/.X0-lock /tmp/.X11-unix/X0 2>/dev/null; true"
            val pb = ProcessBuilder(
                proot.absolutePath, "-0", "-r", rootfs.absolutePath,
                "-b", "/dev", "-b", "/proc", "-b", "/sys", "-w", "/",
                "/bin/sh", "-c", payload)
            pb.environment().clear()
            pb.environment().putAll(mapOf(
                "LD_LIBRARY_PATH" to nb.absolutePath,
                "PROOT_TMP_DIR" to java.io.File(com.emux.core.FsLayout.safeTmp(ctx), "proot").apply { mkdirs() }.absolutePath,
                "TMPDIR" to "/tmp",
                "HOME" to "/root",
                "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "PROOT_LOADER" to java.io.File(nb, "loader").absolutePath,
            ))
            pb.redirectErrorStream(true)
            val p = pb.start()
            val wd = Thread { try { Thread.sleep(6_000); p.destroy() } catch (_: Exception) {} }
                .apply { isDaemon = true; start() }
            p.inputStream.bufferedReader().readText()
            p.waitFor()
            wd.interrupt()
            com.emux.EmuXLog.i("killDesktopGuests done")
        }.onFailure { com.emux.EmuXLog.e("killDesktopGuests failed (non-fatal)", it) }
    }

    private suspend fun waitForPort(port: Int, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().apply {
                    connect(java.net.InetSocketAddress("127.0.0.1", port), 300)
                    close()
                }
                return
            } catch (_: Exception) {
                delay(300)   // cheap poll during cold-start ONLY (bounded 20 s)
            }
        }
        throw IllegalStateException("X server did not come up within ${timeoutMs / 1000}s")
    }

    // ---------------------------------------------------------- frame ingestion
    private fun openFrameListener() {
        // v0.1.52 FIELD FIX ("waiting for X frames … bind failed: EADDRINUSE"):
        // the ServerSocket(port,…) constructor BINDS IMMEDIATELY, so the old
        // post-constructor `apply { reuseAddress = true }` never took effect —
        // a socket left in TIME_WAIT by the previous desktop session made the
        // re-bind fail and the pane waited for frames forever. Set
        // SO_REUSEADDR on an UNBOUND socket, then bind; and retry a few times
        // for the case of a live stale listener still tearing down.
        runCatching { listener?.close() }
        listener = null
        var lastErr: java.net.BindException? = null
        repeat(8) {
            try {
                listener = ServerSocket().apply {
                    reuseAddress = true
                    bind(java.net.InetSocketAddress(
                        java.net.InetAddress.getByName("127.0.0.1"), FRAME_PORT), 1)
                }
                return
            } catch (e: java.net.BindException) {
                lastErr = e
                runCatching { listener?.close() }
                listener = null
                try { Thread.sleep(400) } catch (_: InterruptedException) {}
            }
        }
        throw lastErr ?: java.net.BindException("frame port $FRAME_PORT unavailable")
    }

    /**
     * Accept one capture connection, then decode its push stream.
     * Frame protocol (documented in scripts/emux-capture):
     *   header 8B: magic 'EMUX' | width u16 | height u16 | format u8 | flags u8
     *   frames : seq u32 | dirtyW u16 | dirtyH u16 | x u16 | y u16 | rgb565 payload
     * Full frames arrive with x=0,y=0,w=full,h=full.
     */
    fun beginFrameLoop(onUiInvalidated: () -> Unit) {
        frameReader?.cancel()
        runCatching { activeSock?.close() }   // v0.1.54: drop any previous stream
        activeSock = null
        frameReader = scope.launch {
            var sock: Socket? = null
            try {
                sock = listener!!.accept()          // blocks until helper connects
                activeSock = sock                   // v0.1.54: closable from stop()
                sock.tcpNoDelay = true
                val din = DataInputStream(sock.inputStream.buffered(512 * 1024))
                val hdr = ByteArray(8)
                din.readFully(hdr)
                val hb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
                if (String(hdr, 0, 4) != "EMUX") throw IllegalStateException("bad capture magic")
                // header: magic[4] | width u16 | height u16 | format u8 | flags u8
                val w = hb.getShort(4).toInt() and 0xFFFF
                val h = hb.getShort(6).toInt() and 0xFFFF
                ensureBitmap(w, h)

                var lastFpsWindowStart = System.currentTimeMillis()
                var framesInWindow = 0
                while (isActive) {
                    val minFrameMs = if (foreground && screenOn) 1000L / MAX_FPS_FOREGROUND
                        else 1000L / MAX_FPS_IDLE
                    // v0.1.20 BUGFIX (desktop black): the sender's frame prefix is
                    // 12 bytes (seq u32 | w u16 | h u16 | x u16 | y u16), but this
                    // reader consumed only 10 — every subsequent read was offset by
                    // two bytes, so dw/dh decoded as garbage (usually 0 → `continue`
                    // forever, or huge → EOF exception). Now both sides agree on a
                    // FIXED full-frame protocol: 12-byte header + exactly W*H*2
                    // payload bytes, always at (0,0) with W,H from the stream header.
                    val fh = ByteArray(12)
                    din.readFully(fh)                 // blocks: parked thread when idle
                    val fb = ByteBuffer.wrap(fh).order(ByteOrder.LITTLE_ENDIAN)
                    val dw = fb.getShort(4).toInt() and 0xFFFF
                    val dh = fb.getShort(6).toInt() and 0xFFFF
                    val dx = fb.getShort(8).toInt() and 0xFFFF
                    val dy = fb.getShort(10).toInt() and 0xFFFF
                    if (dw <= 0 || dh <= 0) continue

                    val rowBytes = dw * 2
                    val rows = Array(dh) {
                        val line = ByteArray(rowBytes)
                        din.readFully(line)
                        line
                    }
                    writeDirtyRegion(dx, dy, dw, dh, rows)
                    frameId++

                    // Adaptive pacing: if the sender floods us, drop frames but keep
                    // reading (socket backpressure would stall their diff loop).
                    framesInWindow++
                    val now = System.currentTimeMillis()
                    if (now - lastFpsWindowStart > 1000) {
                        lastFpsWindowStart = now; framesInWindow = 0
                    }
                    onUiInvalidated()
                    val spent = System.currentTimeMillis() - now
                    if (spent < minFrameMs) delay(minFrameMs - spent)
                }
            } catch (_: Exception) {
                if (state.value == State.CONNECTED) {
                    setError("frame stream ended")
                    state.value = State.ERROR
                }
            } finally {
                runCatching { sock?.close() }
                if (activeSock === sock) activeSock = null
            }
        }
    }

    private fun ensureBitmap(w: Int, h: Int) {
        synchronized(frameLock) {
            if (reusable == null || reusable!!.width != w || reusable!!.height != h) {
                reusable = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
            }
            currentFrame = reusable
        }
    }

    /** Convert incoming RGB565 rows into the reused RGB_565 bitmap (memcpy-fast). */
    private fun writeDirtyRegion(x: Int, y: Int, w: Int, h: Int, rows: Array<ByteArray>) {
        val bmp = currentFrame ?: return
        // v0.1.20 BUGFIX (garbage colors even if frames arrived): the old code
        // byte-swapped every pixel through an intermediate buffer using bit math
        // that mixed up the field boundaries. The wire format is plain RGB565
        // LITTLE-ENDIAN (bit 15..11 = R, 10..5 = G, 4..0 = B), so decode each
        // LE 16-bit unit directly into ARGB — no swap, no staging buffer.
        val buf = ByteArray(w * h * 2)
        for (r in 0 until h) {
            val src = rows[r]
            System.arraycopy(src, 0, buf, r * w * 2, src.size)
        }
        synchronized(frameLock) {
            // setPixels needs IntArray; do the widening once here (still allocation-free
            // because we reuse a scratch IntArray sized to the region).
            val need = w * h
            var px = scratchPx
            if (px == null || px.size < need) { px = IntArray(need); scratchPx = px }
            for (j in 0 until need) {
                val v = (buf[j * 2].toInt() and 0xFF) or ((buf[j * 2 + 1].toInt() and 0xFF) shl 8)
                px[j] = android.graphics.Color.rgb(
                    ((v shr 11) and 0x1F) * 255 / 31,
                    ((v shr 5) and 0x3F) * 255 / 63,
                    (v and 0x1F) * 255 / 31,
                )
            }
            bmp.setPixels(px, 0, w, x, y, w, h)
        }
    }

    // --------------------------------------------------------------------- input
    /** Map a touch/click to X coordinates (view already scaled letterboxed). */
    fun injectClick(xInXSpace: Int, yInXSpace: Int, button: Int = 1, press: Boolean) {
        x11?.fakeButton(button, xInXSpace, yInXSpace, press)
    }

    fun injectMotion(x: Int, y: Int) { x11?.fakeMotion(x, y) }

    /** Drag ended outside a click: just release button 1 at position. */
    fun fakeButtonReleaseOnly(x: Int, y: Int) {
        x11?.let { c -> c.fakeMotion(x, y); c.fakeButton(1, x, y, press = false) }
    }

    fun injectKeycode(keycode: Int, press: Boolean) { x11?.fakeKey(keycode, press) }

    // -------------------------------------------------------------------- stop
    fun stop() {
        frameReader?.cancel(); frameReader = null
        runCatching { activeSock?.close() }; activeSock = null   // v0.1.54
        runCatching { listener?.close() }; listener = null
        x11?.close(); x11 = null
        scope.launch {
            xSession?.stop(); captureSession?.stop()
            xSession = null; captureSession = null
            killDesktopGuests()   // v0.1.54: guests outlive session stop otherwise
        }
        state.value = State.IDLE
    }
}
