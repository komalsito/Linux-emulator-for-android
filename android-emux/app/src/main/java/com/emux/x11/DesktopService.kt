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
import kotlin.math.max
import kotlin.math.min

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

        /**
         * v0.1.62 FIELD FIX (EADDRINUSE on every app re-open): DesktopService
         * used to be constructed PER ACTIVITY while the process outlives the
         * activity (foreground terminal service keeps it alive). The old
         * instance's frame listener + reader thread + sessions leaked, still
         * holding 127.0.0.1:7001, so the new activity's fresh instance died
         * in openFrameListener with EADDRINUSE. One instance per process.
         */
        @Volatile private var inst: DesktopService? = null
        fun get(ctx: Context, root: RootManager): DesktopService =
            inst ?: synchronized(this) {
                inst ?: DesktopService(ctx.applicationContext, root).also { inst = it }
            }
        const val MAX_FPS_FOREGROUND = 30
        const val MAX_FPS_IDLE = 5
    }

    enum class State { IDLE, STARTING_X, AWAITING_FRAMES, CONNECTED, ERROR }
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
    /** v0.1.63: history+log entry WITHOUT setting the live error line. */
    fun noteInfo(msg: String) {
        val line = "[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}] $msg"
        synchronized(errHistory) {
            errHistory.addLast(line)
            // v0.1.80: 6 lines rotated away faster than the user could read
            // the pane ("auto scrolls and only leaves a couple lines"); the
            // pane keeps a longer window now, and the full log lives in the
            // two mirror files below.
            // v0.2.5 FIELD FIX: 24 notes rotate away exactly as fast as new
            // ones arrive once the pane is full - which is why the v0.2.3
            // scroll gesture "works until the output goes all along the
            // screen, that's where the scrolling stops". The pane can scroll
            // hundreds of lines; keep them (strings only, trivial memory).
            while (errHistory.size > 400) errHistory.removeFirst()
        }
        runCatching {
            val f = java.io.File(ctx.filesDir, "logs").apply { mkdirs() }
            java.io.File(f, "desktop.log").appendText(line + "\n")
        }
        // v0.1.80: guest-visible mirror - `cat /tmp/emux_desktop_diag.log` in
        // the Terminal tab shows the WHOLE desktop session story without adb
        // (the pane only ever shows a window; this never rotates away).
        runCatching {
            java.io.File(com.emux.core.FsLayout.linuxRoot(ctx), "tmp/emux_desktop_diag.log")
                .appendText(line + "\n")
        }
        com.emux.EmuXLog.i("desktop: $msg")
    }

    private fun setError(msg: String) {
        noteInfo(msg)
        errorText.value = msg
    }
    /**
     * v0.2.27 FIELD FIX: newest-NOTE-first rendering for the status
     * TextView - the surface users actually read. Twenty-six rounds of
     * pane screenshots were the TextView (full-height, oldest-first);
     * every canvas-side visibility fix (scroll, sticky-bottom,
     * newest-first) touched a layer nobody was looking at. Note order is
     * reversed; lines INSIDE each note stay chronological so multi-line
     * diagnostics still read top-down.
     */
    fun errorHistoryTextNewestFirst(maxNotes: Int = 60): String = synchronized(errHistory) {
        val live = errorText.value
        val base = if (errHistory.isEmpty()) mutableListOf<String>() else errHistory.toMutableList()
        if (live != null && base.none { it.endsWith(live) }) base.add(live)
        if (base.isEmpty()) return "?"
        base.takeLast(maxNotes).asReversed().joinToString("\n")
    }

    fun errorHistoryText(): String = synchronized(errHistory) {
        // v0.1.68: always include the LIVE error line - transient messages
        // set via errorText.value directly never entered the history, so a
        // screenshot taken between notes showed a single unexplained line.
        val live = errorText.value
        val base = if (errHistory.isEmpty()) mutableListOf() else errHistory.toMutableList()
        if (live != null && base.none { it.endsWith(live) }) base.add(live)
        if (base.isEmpty()) "?" else base.joinToString("\n")
    }

    var x11: X11Client? = null; private set
    // v0.2.30: last connectControlPlane failure, for the expiry note -
    // it used to die silently in logcat while the pane guessed.
    @Volatile private var lastCtrlError: String? = null
    // v0.2.5 FIELD FIX: this session's X display number (see startDesktop).
    @Volatile private var displayNum = 0
    // v0.2.38: the DesktopView's live aspect (w/h), reported from
    // onSizeChanged, so Xvfb geometry matches the VIEW and the letterbox
    // bars disappear by default (field request: "auto-detect the best fit
    // without bars at the sides").
    @Volatile var viewAspect: Float? = null
    // v0.2.8: /proc/uptime ticks at startDesktop; host-side kills only reap
    // processes OLDER than this desktop start (see killDesktopGuests).
    @Volatile private var desktopStartUpTicks = Long.MAX_VALUE
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
    private var captureRespawns = 0
    private var pumpedCaptureSession: TerminalSession? = null
    /** One-line pipeline state for the frameless pane header (see DesktopView).
     *  This is the antidote to "all green, no desktop": it names the stage
     *  that has NOT happened yet (capture not started / no frames), instead
     *  of re-listing probes that passed. */
    fun pipelineStatusText(): String {
        val x = when (state.value) {
            State.IDLE -> "X: idle"
            State.STARTING_X -> "X: starting"
            State.AWAITING_FRAMES -> "X: ready, waiting for frames"
            State.CONNECTED -> "X: connected"
            State.ERROR -> "X: error"
        }
        val cap = when {
            captureSession == null -> "capture: not started"
            captureSession?.state?.value == TerminalSession.State.DEAD ->
                "capture: exited (${captureSession?.exitCode})"
            else -> "capture: running"
        }
        return "$x · $cap · frames: $frameId"
    }
    // v0.1.79: bounded respawn for the X session when it dies before the
    // first frame (transient mid-transaction deaths, see emux-x11 v0.1.79).
    private var xRespawns = 0
    // v0.1.82/83: host bind probe result, pinned into every recurring pane
    // note so a screenshot always carries the orphan-vs-guest discrimination.
    private var hostProbeNote = ""

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

    /**
     * v0.1.101 MOBILE: X desktop size for this device, as "WxHx24".
     * Pixel count is capped at the old 720x1280 (921,600 px) so the
     * full-frame capture + per-pixel decode cost does not grow.
     *  - Phone (smallest width < 600dp): always 1280x720 landscape.
     *    A phone desktop is used rotated; a portrait X server on a
     *    landscape phone fits to a tiny 360px-wide strip, while a
     *    landscape server still fills the phone's width in portrait
     *    (letterboxed, pinch/double-tap to zoom) and fills it fully
     *    in landscape.
     *  - Tablet: the device's own aspect, long side capped at 1280.
     * Geometry only applies when the desktop STARTS (Xvfb is not
     * resized mid-session); rotating mid-session re-fits the existing
     * frame in DesktopView until the next desktop start.
     */
    private fun desktopGeometry(): String {
        // v0.2.38: prefer the VIEW's measured aspect - the X geometry then
        // matches the pane exactly and the contain-fit has no bars at all,
        // no double-tap needed. Pixel budget stays capped at the old
        // 720x1280 (921,600 px) so capture cost never grows.
        viewAspect?.takeIf { it in 0.4f..2.8f }?.let { a ->
            val longSide = 1280
            val shortSide = ((longSide / a).coerceIn(360f, 1280f).toInt() / 2 * 2)
            val px = longSide * shortSide
            if (px <= 921_600 * 2) return if (a >= 1f) "${longSide}x${shortSide}x24" else "${shortSide}x${longSide}x24"
        }
        val cfg = ctx.resources.configuration
        if (cfg.smallestScreenWidthDp in 1 until 600) return "1280x720x24"
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels; val h = dm.heightPixels
        if (w <= 0 || h <= 0) return "720x1280x24"
        val longSide = 1280
        val shortSide = ((longSide * min(w, h) / max(w, h)).coerceIn(480, 1280) / 2 * 2)
        return if (w >= h) "${longSide}x${shortSide}x24" else "${shortSide}x${longSide}x24"
    }

    // ------------------------------------------------------------------ start
    fun startDesktop(wmCommand: String = "openbox") {
        // v0.1.100: AWAITING_FRAMES is also an in-progress state —
        // a second startDesktop() here would launch a duplicate X/capture
        // pipeline on top of the one already waiting for frames.
        if (state.value == State.STARTING_X || state.value == State.AWAITING_FRAMES || state.value == State.CONNECTED) return
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
                // v0.1.68: UNCONDITIONAL start note with the host-side facts
                // (shim file? preload line? journal dirty? previous selfcheck
                // verdict? previous xvfb fatal line?) so ANY screenshot taken
                // during this session carries the diagnosis - the conditional
                // notes of v0.1.63-67 left the history empty exactly when the
                // files didn't exist yet.
                runCatching {
                    val shim = java.io.File(rootfs, "usr/local/lib/emux/libemux-linkfix.so").isFile
                    val preload = runCatching {
                        java.io.File(rootfs, "etc/ld.so.preload").readText().contains("linkfix")
                    }.getOrDefault(false)
                    val dirty = com.emux.core.BootstrapManager(ctx).aptJournalDirty(rootfs)
                    val prev = runCatching {
                        java.io.File(rootfs, "tmp/emux_selfcheck").readText().lineSequence()
                            .firstOrNull { it.startsWith("LINK_") || it.startsWith("SHIM_") || it.startsWith("DBSWAP") || it.startsWith("SHIMTEST") }
                    }.getOrNull()
                    val xl = runCatching {
                        java.io.File(rootfs, "tmp/xvfb.log").readText().lineSequence()
                            .filter { it.isNotBlank() }.toList().takeLast(1).firstOrNull()
                    }.getOrNull()
                    // v0.1.72: host-readable verdicts the guest payload may
                    // not have refreshed yet.
                    val dbw = runCatching { java.io.File(rootfs, "tmp/emux_dbswap").readText().trim() }.getOrNull()
                    val sht = runCatching { java.io.File(rootfs, "tmp/emux_shimtest").readText().trim() }.getOrNull()
                    noteInfo("start: shim=$shim preload=$preload journalDirty=$dirty prev=${prev ?: "-"} xvfb=${xl ?: "-"}")
                    noteInfo("verdicts: shimtest=${sht ?: "-"} dbswap=${dbw ?: "-"}")
                }
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
                // v0.1.56: heal dpkg BEFORE the cascade runs - on devices whose
                // in-rootfs helpers predate emux-apt-repair the cascade itself
                // cannot recover from a dirty journal ("E: dpkg was
                // interrupted" -> no Xvfb -> exit 1 -> rotating pane errors).
                // v0.1.100 WARM-START FAST PATH: if the whole desktop
                // stack is already on disk, the synchronous pre-repair
                // (apt-lock wait up to 90 s + a possible CORE apt install)
                // is pure overhead on every tab open — skip it. The
                // background startup repair still runs at app start, and
                // a missing file falls through to the full pre-repair.
                val stackPresent = listOf(
                    "usr/bin/Xvfb", "usr/bin/openbox", "usr/bin/xwd",
                    "usr/bin/pnmtoplainpnm", "bin/nc.openbsd",
                ).all { java.io.File(rootfs, it).isFile }
                // v0.2.33 FIELD FIX: "warm" must also mean a CLEAN dpkg
                // journal. The field rootfs carries half-configured
                // openbox/libc-bin from the old kill-cycles; half-configured
                // still puts files on disk, so the stack check passed and the
                // pre-repair was skipped FOREVER - apt stayed broken, the
                // background REST pass could never install xterm, and the
                // (perfectly streaming) desktop stayed windowless black.
                val journalDirty = com.emux.core.BootstrapManager(ctx).aptJournalDirty(rootfs) ||
                    com.emux.core.BootstrapManager(ctx).dpkgHalfConfigured(rootfs) ||
                    com.emux.core.BootstrapManager(ctx).dpkgLibcBroken(rootfs)
                val warmDesktop = stackPresent && !journalDirty
                if (warmDesktop) {
                    noteInfo("warm start: desktop stack present - skipping pre-repair")
                } else {
                    if (stackPresent) noteInfo(
                        "stack present but dpkg journal DIRTY - repairing before session (apt was unusable)")
                    // v0.2.1 FIELD FIX: surface pre-repair progress live. The
                    // v0.2.0 pane went silent for the whole (multi-minute)
                    // pre-repair window - indistinguishable from the wedged
                    // state this release fixes - so every step now lands in
                    // the history while it happens.
                    com.emux.core.BootstrapManager.progressNote = { m -> noteInfo(m) }
                    try {
                        com.emux.core.BootstrapManager(ctx).desktopPreRepair()
                            ?.let { noteInfo(it) }
                    } finally {
                        com.emux.core.BootstrapManager.progressNote = null
                    }
                }
                // v0.1.63: surface the guest dpkg selfcheck (shim loaded? link()
                // ok? db dir owner/mode?) in the pane history + desktop.log so
                // the next field screenshot answers WHY instead of guessing.
                runCatching {
                    val sc = java.io.File(rootfs, "tmp/emux_selfcheck")
                    if (sc.isFile) {
                        val sum = sc.readText().lineSequence()
                            .filter { it.startsWith("SHIM_") || it.startsWith("LINK_") || it.startsWith("dpkgdir") || it.startsWith("status u=") || it.startsWith("DBSWAP") }
                            .joinToString(" ").take(300)
                        if (sum.isNotBlank()) noteInfo("selfcheck: $sum")
                    }
                    // v0.1.64: Xvfb's OWN log is host-readable at
                    // <rootfs>/tmp/xvfb.log - its fatal line ("Address already
                    // in use", "Unrecognized option", shm errors ...) is the
                    // shortest possible answer to "why no tcp/6000".
                    val xl = java.io.File(rootfs, "tmp/xvfb.log")
                    if (xl.isFile) {
                        val t = xl.readText().lineSequence().filter { it.isNotBlank() }
                            .toList().takeLast(2).joinToString(" | ").take(300)
                        if (t.isNotBlank()) noteInfo("xvfb.log: $t")
                    }
                }

                // 0b) v0.1.54: clear orphaned desktop guests (and their X locks /
                //     tcp sockets) from a previous tab visit or app life BEFORE we
                //     bind :7001 and start a fresh capture - otherwise the old
                //     emux-capture keeps an ESTABLISHED socket on our port and the
                //     bind fails EADDRINUSE, and the old Xvfb holds :0.
                // v0.1.82 HOST BIND PROBE, before any cleanup: X's "server
                //     already running" hides the real errno. If the HOST can
                //     bind tcp 6000 / the unix socket path / an abstract socket
                //     fine, then NO orphan holds them and the failure is inside
                //     the guest (proot socket translation / SELinux) - the two
                //     cases need completely different fixes, and the field pane
                //     could not tell them apart until now.
                // v0.2.5 FIELD FIX: ROTATE to a free X display instead of
                // fighting stale holders. Five versions of kill-sweeps could
                // not reliably reap detached proot tracees from previous app
                // lives: they keep :0 / tcp 6000 ("_XSERVTransSocketUNIXCreate
                // Listener failed ... server already running", every ladder
                // attempt rc=1) while xwd happily grabs frames FROM THE
                // ZOMBIE - frames that never reach our new capture pipeline.
                // First free N in 0..3 (host can bind tcp 6000+N AND the
                // rootfs has no XN socket) becomes this session's display;
                // every component (emux-x11, emux-capture, X11Client, probes)
                // is told the number, so a held :0 can no longer black-screen
                // the tab.
                displayNum = (0..3).firstOrNull { n ->
                    val tcpFree = runCatching {
                        java.net.ServerSocket(6000 + n).close(); true
                    }.getOrDefault(false)
                    tcpFree && !java.io.File(rootfs, "tmp/.X11-unix/X$n").exists()
                } ?: 0
                noteInfo("desktop display: :$displayNum (tcp ${6000 + displayNum})")
                runCatching {
                    val tcpOk = runCatching {
                        java.net.ServerSocket(6000 + displayNum).close(); true
                    }.getOrElse { false }
                    val absOk = runCatching {
                        android.net.LocalServerSocket("emux-bind-probe").close(); true
                    }.getOrElse { false }
                    val fsOk = runCatching {
                        val p = java.io.File(rootfs, "tmp/.X11-unix/Xprobe-host")
                        p.parentFile?.mkdirs()
                        val s = android.net.LocalSocket()
                        s.bind(android.net.LocalSocketAddress(
                            p.absolutePath, android.net.LocalSocketAddress.Namespace.FILESYSTEM))
                        s.close(); p.delete(); true
                    }.getOrElse { false }
                    // unixpath first: it is the discriminating value and the pane truncates
                    hostProbeNote = "hostbind unixpath=$fsOk tcp6000=$tcpOk abs=$absOk"
                    noteInfo("host bind probe: $hostProbeNote")
                }
                // v0.2.6 FIELD FIX: drop STALE gate markers and socket files
                // from previous app lives. A leftover /tmp/emux_x_up made the
                // capture gate launch emux-capture against a server that no
                // longer existed (then rc=137 in the respawn churn), and
                // leftover XN socket files make Xvfb's unix listener die with
                // "server already running" even on a port nobody holds.
                // v0.2.19 FIELD FIX (the meta-bug): guest helper scripts are
                // re-seeded ONLY via startupRepairAsync -> reseedHelpersIf-
                // Needed, which on this ROM never runs - installNativeBridge()
                // (or staging) throws first and the runCatching swallows it,
                // so the rootfs kept running v0.2.16's emux-x11/emux-capture
                // through v0.2.17 AND v0.2.18 (field panes showed adoption
                // without the v0.2.18 self-heal/instrumentation). Every
                // guest-script fix since the last successful reseed was
                // DORMANT. Re-seed straight from APK assets on every desktop
                // start - a few KB of idempotent file writes - so the guest
                // can never lag the APK again.
                runCatching {
                    val bm = com.emux.core.BootstrapManager(ctx)
                    val staged = bm.stageHelperAssets()
                    bm.installHelperScripts(rootfs, staged) { _, _ -> }
                    bm.installCaptureConv(rootfs)
                    bm.installLinkFixShim(rootfs)
                    noteInfo("helpers re-seeded from APK assets (env " +
                        com.emux.core.FsLayout.ENV_VERSION + ")")
                }.onFailure {
                    noteInfo("helper re-seed failed (non-fatal): " + (it.message?.take(80) ?: "?"))
                }
                // v0.2.47 FIELD FIX: opening the Desktop tab used to kill the
                // Terminal tab on ~1 GB devices - Android's low-memory killer
                // reaped the terminal's proot (a background process once the
                // user switched tabs), and a tracee whose tracer dies prints
                // "proot warning: signal 11" (the pid-29008 ghost seen in
                // every round). Bias the LMK: terminal shells get protected
                // scores, desktop guests (self-healing, respawnable) become
                // the preferred sacrifice.
                runCatching {
                    val myUid = android.os.Process.myUid().toString()
                    java.io.File("/proc").listFiles()?.forEach { d ->
                        val pid = d.name.toIntOrNull() ?: return@forEach
                        val cmd = runCatching {
                            java.io.File(d, "cmdline").readBytes().decodeToString().replace('\u0000', ' ')
                        }.getOrDefault("")
                        if (!cmd.contains("proot")) return@forEach
                        val uidLine = runCatching {
                            java.io.File(d, "status").readLines().firstOrNull { it.startsWith("Uid:") } ?: ""
                        }.getOrDefault("")
                        if (uidLine.split(Regex("\\s+")).getOrNull(1) != myUid) return@forEach
                        val adj = when {
                            cmd.contains("emux-x11") || cmd.contains("emux-capture") -> "100"
                            cmd.contains("bash") || cmd.contains("/bin/sh") || cmd.contains("script") -> "-300"
                            else -> return@forEach
                        }
                        runCatching { java.io.File(d, "oom_score_adj").writeText(adj) }
                    }
                }
                desktopStartUpTicks = runCatching {
                    (java.io.File("/proc/uptime").readText().trim().split(" ")[0].toDouble() * 100).toLong() - 50
                }.getOrDefault(Long.MAX_VALUE)
                runCatching {
                    listOf("tmp/emux_x_up", "tmp/emux_display_num").forEach {
                        java.io.File(rootfs, it).delete()
                    }
                    (0..4).forEach { n -> java.io.File(rootfs, "tmp/.X11-unix/X$n").delete() }
                }
                killDesktopGuests()
                // v0.2.17 FIELD FIX: CONNECT, not bind, is the honest liveness
                // test. A wildcard bind() with SO_REUSEADDR SUCCEEDS over a
                // loopback-specific listener - which is why the host probe
                // printed "tcp6000=true / port6000: free" for ten versions
                // while the guest saw 6000 held, and why v0.2.16's verified
                // adoption handshook with a zombie our own killDesktopGuests()
                // was already tearing down (it still served xwd at :37, was
                // gone by :52, and the session launched ZERO Xvfbs). After the
                // kills, wait until nothing ANSWERS on the display port:
                // silence = clean slate for the ladder; still answering = a
                // live server we cannot kill = legitimate adoption.
                var free = false
                var sweeps = 0
                while (!free && sweeps < 5) {
                    free = !portAnswering(6000 + displayNum)
                    if (!free) {
                        sweeps++
                        noteInfo("port6000 STILL ANSWERING after sweep #$sweeps - re-sweeping")
                        runCatching { killDesktopGuests() }
                        Thread.sleep(2_000)
                    }
                }
                noteInfo("port6000 state at session start: " +
                    (if (free) "silent (clean slate - ladder will launch)"
                     else "ANSWERING (live server - adoption path)"))

                // v0.1.101 MOBILE: the desktop geometry used to be
                // fixed at 720x1280 portrait in emux-x11, whatever the
                // device. On a phone that letterboxes to a tiny strip
                // (landscape) or a narrow column (portrait) and makes
                // every X window control untappable. Pass the geometry
                // for THIS device instead (see desktopGeometry()).
                val desktopGeometry = desktopGeometry()
                noteInfo("desktop geometry: $desktopGeometry")
                val cmd = "emux-x11 $wmCommand $desktopGeometry $displayNum"
                xRespawns = 0
                // v0.1.80: tell the user WHERE the full log lives - the pane
                // only shows a scrolling window; the diag file never rotates.
                noteInfo("full desktop log: Terminal tab -> cat /tmp/emux_desktop_diag.log (or adb pull files/logs/desktop.log)")
                xSession = TerminalSession(9001, ctx, root, cmd).also { it.start() }

                // v0.1.18 BUGFIX #6: if the X server session dies (launcher exec
                // failed, su denied, Xvfb crashed), say so immediately instead of
                // leaving a black pane with no explanation.
                scope.launch {
                    xSession?.state?.collect { st ->
                        if (st == TerminalSession.State.DEAD && state.value != State.ERROR) {
                            // v0.2.7 FIELD FIX (the rc=137 murder loop): emux-x11
                            // is a LAUNCHER, not a daemon - it backgrounds Xvfb
                            // and the WM, then EXITS 0 by design. This watcher
                            // treated that normal exit as a pre-frame death:
                            // killDesktopGuests() SIGKILLed the HEALTHY running
                            // emux-capture (field pane: "capture died pre-frame
                            // (rc=137)" every ~15 s) and respawned emux-x11,
                            // whose fresh ladder then died on the STILL-ALIVE
                            // server's sockets ("server already running", all
                            // attempts rc=1) - an endless loop that murdered
                            // every capture ~2 s after start and truncated
                            // /tmp/emux_attempts each cycle. Clean exit =
                            // success: daemons are detached, keep them, keep
                            // the capture, respawn nothing.
                            if (xSession?.exitCode == 0) {
                                com.emux.EmuXLog.i("desktop: x11 launcher exited 0 (daemons detached) - not respawning")
                                noteInfo("x11 launcher exited cleanly - X/WM daemons detached, session continues")
                                return@collect
                            }
                            // v0.1.79: a PRE-FRAME death is often transient - the
                            // v0.1.78 field round died on a first launch that
                            // landed inside our own background dpkg transaction
                            // and a retry on a flag noble's Xvfb no longer has.
                            // emux-x11 now settles/probes; belt-and-braces we
                            // respawn the whole session twice before painting
                            // ERROR, so a single bad window cannot black-screen
                            // the tab for the whole visit.
                            if (frameId == 0 && xRespawns < 2 && state.value != State.IDLE) {
                                xRespawns++
                                com.emux.EmuXLog.i("desktop: x11 session died before first frame - respawning (#$xRespawns)")
                                noteInfo("x11 session died pre-frames - respawning (#$xRespawns)")
                                kotlinx.coroutines.delay(2_000)
                                // v0.1.80: host-authoritative cleanup BEFORE the
                                // new session: proot tracees (a half-alive Xvfb
                                // holding :0/tcp-6000) survive guest-side kills,
                                // and a retry into them dies with "Cannot
                                // establish any listening sockets" (field pane).
                                runCatching { killDesktopGuests() }
                                xSession = TerminalSession(9001 + xRespawns, ctx, root, cmd).also { it.start() }
                                pumpDiag("x11", xSession!!)
                                return@collect
                            }
                            // v0.1.43: include the captured session output tails
                            // (painted on the pane) — "exited code 0" alone gave
                            // the field no actionable information.
                            val tails = listOf("x11", "capture").mapNotNull { t ->
                                diag[t]?.toString()?.lineSequence()
                                    ?.filter { it.isNotBlank() }?.toList()?.takeLast(4)?.joinToString(" | ")
                            }.joinToString(" /// ")
                            val xlog = runCatching {
                                java.io.File(com.emux.core.FsLayout.linuxRoot(ctx), "tmp/xvfb.log")
                                    .readText().lineSequence().filter { it.isNotBlank() }
                                    .toList().takeLast(3).joinToString(" | ")
                            }.getOrDefault("")
                            // v0.1.85 STICKY POST-MORTEM: when respawns are
                            // exhausted the pane error line is what survives
                            // scrolling - pack the discrimination into it:
                            // host probe + guest probe file + FIRST lines of
                            // both xvfb logs (the first attempt's real error).
                            val rf = com.emux.core.FsLayout.linuxRoot(ctx)
                            fun first3(rel: String) = runCatching {
                                java.io.File(rf, rel).readText().lineSequence()
                                    .filter { it.isNotBlank() }.toList().take(3).joinToString(" | ")
                            }.getOrDefault("")
                            // v0.1.89: dump ALL telemetry into history notes
                            // when we finally give up - history survives the
                            // scroll window the user keeps losing data to.
                            listOf("tmp/xvfb.log.2", "tmp/xvfb.log.1", "tmp/xvfb.log",
                                   "tmp/emux_bindlog", "tmp/emux_attempts").forEach { rel ->
                                runCatching {
                                    val f = java.io.File(rf, rel)
                                    if (f.isFile) f.readText().lineSequence()
                                        .filter { it.isNotBlank() }.toList().joinToString(" | ")
                                        .chunked(330).take(4)
                                        .forEach { chk -> noteInfo("$rel: $chk") }
                                }
                            }
                            val pm = ("POSTMORTEM " + hostProbeNote +
                                " | g: " + guestProbeTail(rf) +
                                " | log1: " + first3("tmp/xvfb.log.1") +
                                " | log: " + first3("tmp/xvfb.log")).take(500)
                            noteInfo(pm)
                            dumpDiagBundleToTerminal()   // v0.2.22: mirror to Terminal
                            setError(
                                ("X server exited (code ${xSession?.exitCode}). " + tails +
                                    if (xlog.isNotBlank()) " || xvfb.log: $xlog" else "").take(500))
                            state.value = State.ERROR
                        }
                    }
                }


                // 1) Open our frame listener BEFORE starting capture so no frames lost.
                // v0.1.100: ALSO start the frame reader here, in parallel
                // with the control plane below. Previously beginFrameLoop()
                // ran only after the 60 s control-plane wait, so even a
                // capture helper that connected instantly could not deliver
                // a frame until that wait finished.
                openFrameListener()
                beginFrameLoop { frameCallback?.invoke() }
                // v0.2.48 FIELD FIX: once frames flow, the frameless pane
                // (and its history) is hidden behind the desktop bitmap and
                // the 60 s terminal auto-dump never arms - so a furniture
                // install failure became invisible again, and the field
                // terminal's degraded paste path keeps eating the long
                // commands that would reveal it. 90 s after a CONNECTED
                // desktop, if tint2 is still absent, put the REST apt log
                // tail INTO the history (visible via scroll/dump) - no
                // terminal archaeology required.
                scope.launch {
                    kotlinx.coroutines.delay(90_000)
                    runCatching {
                        val rf = com.emux.core.FsLayout.linuxRoot(ctx)
                        if (!java.io.File(rf, "usr/bin/tint2").isFile &&
                            state.value == State.CONNECTED) {
                            val tail = java.io.File(rf, "tmp/emux_rest.log")
                                .readLines().filter { it.isNotBlank() }.takeLast(8)
                                .joinToString(" | ").take(400)
                            noteInfo("furniture still missing 90s after CONNECT - " +
                                "REST tail: " + (tail.ifBlank { "<no /tmp/emux_rest.log>" }))
                        }
                    }
                }

                // 2) v0.1.91: start the capture helper ONLY once the guest X is
                //    actually up (tcp 6000) AND its toolchain (xwd/netpbm/nc,
                //    installed by the background REST pass) is on disk. Before
                //    this, capture started immediately, died on the missing
                //    X/tools, and burned its 3 respawns BEFORE the REST install
                //    landed - so a perfectly healthy Xvfb (v0.1.90 field:
                //    "attempt 1 rc=0", bindlog all OK) still produced zero
                //    frames forever.
                scope.launch {
                    // v0.1.97: gate on FILES, not host-side socket waits:
                    // emux-x11 touches /tmp/emux_x_up the moment the server is
                    // reachable from inside the guest (host waitForPort proved
                    // unreliable as the gate on the field device), and the
                    // toolchain files land with the REST pass. Narrate every
                    // 15 s so the pane is never silent while waiting.
                    var up = false; var tools = false; var w = 0
                    var gateNoted = false
                    while ((!(up && tools)) && w < 180 && state.value != State.IDLE) {
                        up = up or java.io.File(rootfs, "tmp/emux_x_up").isFile
                        // v0.2.30 FIELD FIX: the marker never appeared in the
                        // field (watcher subshell race) and the gate starved
                        // capture for 180 s BESIDE A LIVING SERVER (pane:
                        // "verify_x: UP ... xwd-rc0" above "marker=false
                        // (60/180)"). Since v0.2.6 deletes X0..X4 at session
                        // start, any XN socket file present now was created
                        // by THIS session's server - accept it as liveness
                        // and take the display number from it.
                        if (!up) {
                            // v0.2.31: exists(), NOT isFile() - a unix socket
                            // is not a regular file, so isFile() was blind to
                            // the very X1 socket this gate was added to see
                            // (field v0.2.30: marker=false beside a live :1).
                            val live = (0..4).firstOrNull { n ->
                                java.io.File(rootfs, "tmp/.X11-unix/X$n").exists()
                            }
                            if (live != null) {
                                up = true
                                displayNum = live
                                if (!gateNoted) {
                                    gateNoted = true
                                    noteInfo("capture gate: live X socket X$live found (marker absent) - using display :$live")
                                }
                            }
                        }
                        tools = listOf("usr/bin/xwd", "usr/bin/pnmtoplainpnm", "bin/nc.openbsd")
                            .all { java.io.File(rootfs, it).isFile }
                        if (!(up && tools)) {
                            kotlinx.coroutines.delay(1_000); w++
                            // v0.1.99 PERF: 1 s poll (was 5); note cadence kept
                            // at ~15 s.
                            if (w % 15 == 0) noteInfo(
                                "capture: waiting - X-up marker=$up toolchain=$tools ($w/180)")
                        }
                    }
                    if (!(up && tools) || state.value == State.IDLE) {
                        noteInfo("capture: gave up waiting (X up=$up tools=$tools)")
                        dumpDiagBundleToTerminal()
                        // v0.1.100: a gate timeout used to be silent in
                        // state terms — capture never started, yet the main
                        // flow still promoted the session to CONNECTED.
                        // Fail loudly so the tab can be retried (and so a
                        // missing toolchain is visible instead of "green").
                        if (state.value != State.IDLE) {
                            setError(
                                ("capture tools missing after 180s " +
                                    "(X up=$up tools=$tools) — " +
                                    "tap Desktop again to retry; if this repeats, " +
                                    "re-run Settings → Install desktop components").take(500))
                            state.value = State.ERROR
                        }
                        return@launch
                    }
                    noteInfo("capture: X up and toolchain present - starting emux-capture")
                    captureSession = TerminalSession(
                        9002, ctx, root,
                        "emux-capture 127.0.0.1:$FRAME_PORT $displayNum",
                    ).also { it.start() }
                    pumpDiag("capture", captureSession!!)
                }

                // v0.1.37 BUGFIX ("desktop says waiting for X frames" with zero
                // explanation): the X and capture sessions are HEADLESS — their
                // output (Xvfb crash logs, apk add failures, nc errors) vanished
                // into scrollback channels nobody reads. Mirror it into a ring
                // buffer + EmuXLog, and if no frame lands within 20 s surface the
                // tail verbatim in the desktop status line so the next field
                // screenshot names the failing component.
                xSession?.let { pumpDiag("x11", it) }
                captureSession?.let { pumpDiag("capture", it) }
                // v0.1.57: the capture helper dying used to be SILENT - only
                // the X session had a death watcher, so a dead emux-capture
                // (missing xwd/netpbm/nc, connect refused ...) left the pane
                // at "waiting for X frames" with no explanation at all.
                // v0.1.100: the old watcher did
                // `captureSession?.state?.collect {}` here, at a moment
                // when the async gate coroutine had usually NOT assigned
                // captureSession yet — so it collected nothing, forever,
                // and a dead capture helper was silent. Poll the CURRENT
                // session instead, so gate assignment and respawns are
                // both followed.
                scope.launch {
                    while (isActive && state.value != State.IDLE && state.value != State.ERROR) {
                        val cur = captureSession
                        if (cur != null && cur !== pumpedCaptureSession) {
                            pumpedCaptureSession = cur
                            pumpDiag("capture", cur)
                        }
                        if (cur != null && cur.state.value == TerminalSession.State.DEAD &&
                            frameId == 0) {
                            if (captureRespawns < 40) {
                                captureRespawns++
                                com.emux.EmuXLog.i("desktop: capture died before first frame - respawning (#$captureRespawns)")
                                noteInfo("capture died pre-frame (rc=${cur.exitCode}) - respawning #$captureRespawns")
                                delay(2_000)
                                if (state.value == State.IDLE || state.value == State.ERROR) break
                                captureSession = TerminalSession(
                                    9002 + captureRespawns, ctx, root,
                                    "emux-capture 127.0.0.1:$FRAME_PORT $displayNum",
                                ).also { it.start() }
                                pumpedCaptureSession = null
                            } else {
                                val tails = diag["capture"]?.toString()?.lineSequence()
                                    ?.filter { it.isNotBlank() }?.toList()?.takeLast(4)
                                    ?.joinToString(" | ") ?: ""
                                dumpDiagBundleToTerminal()   // v0.2.22: mirror to Terminal
                                setError(("capture helper exited (code ${cur.exitCode}). " + tails).take(500))
                                state.value = State.ERROR
                                break
                            }
                        }
                        delay(1_000)
                    }
                }
                // v0.1.100 FRAMELESS DEADLINE: once X is ready
                // (AWAITING_FRAMES) but no frame lands within 120 s, fail
                // loudly instead of sitting at "waiting for X frames"
                // forever. The tab can then be retried (IDLE/ERROR restart).
                scope.launch {
                    // v0.2.26 FIELD FIX: count frameless time FROM SESSION
                    // START, not from AWAITING_FRAMES. The old coroutine only
                    // ticked once the 60 s control-plane wait had finished,
                    // so a session stuck in STARTING_X never armed the dump
                    // or the deadline (field v0.2.25: Terminal showed no
                    // auto-dump at ~80 s because the timer had not begun).
                    var waited = 0L
                    while (isActive && state.value != State.IDLE &&
                        state.value != State.ERROR && state.value != State.CONNECTED) {
                        waited += 1_000
                        if (frameId > 0) return@launch
                        // v0.2.22: mirror the diagnostics into the TERMINAL
                        // tab (which reads linearly) - no typing, no
                        // scrolling, one screenshot.
                        if (waited == 60_000L) {
                            noteInfo("diag: 60s frameless - bundle dumped to Terminal tab")
                            dumpDiagBundleToTerminal()
                        }
                        if (waited >= 150_000L) {
                            dumpDiagBundleToTerminal()
                            setError("no desktop frames in 150s - full diagnostics auto-dumped to the Terminal tab; tap Desktop to retry")
                            state.value = State.ERROR
                            return@launch
                        }
                        delay(1_000)
                    }
                }
                scope.launch {
                    val startFrames = frameId
                    // v0.1.73/74: the v0.1.65 watcher RETURNED while state was
                    // still STARTING_X (the 60 s control wait sets CONNECTED
                    // later), so on slow first runs it died before noting
                    // anything - the pane stayed blind exactly during the
                    // install window. Now it notes every 15 s until frames
                    // arrive or a terminal state (5 min cap), and dumps the
                    // guest logs UNFILTERED (earlier filters hid the ln errno).
                    var elapsed = 0L
                    while (frameId == startFrames &&
                        state.value != State.IDLE && state.value != State.ERROR && elapsed < 300_000L) {
                        kotlinx.coroutines.delay(15_000)
                        elapsed += 15_000
                        if (frameId != startFrames) return@launch
                        val tails = listOf("x11", "capture").mapNotNull { t ->
                            diag[t]?.toString()?.lineSequence()
                                ?.filter { it.isNotBlank() }?.toList()?.takeLast(3)?.joinToString(" | ")
                        }.joinToString(" /// ")
                        noteInfo("t+${elapsed / 1000}s no frames yet. " + tails.take(350) +
                            " || " + hostProbeNote + " | guest: " + guestProbeTail(rootfs))
                        runCatching {
                            val xl = java.io.File(rootfs, "tmp/xvfb.log")
                            noteInfo("xvfb.log: " + (if (xl.isFile) xl.readText().lineSequence()
                                .filter { it.isNotBlank() }.toList().takeLast(6).joinToString(" | ").take(600)
                            else "<absent - Xvfb never launched>"))
                            // v0.1.80: the PREVIOUS attempt's log too - the
                            // field needs the first death reason, not only
                            // the last relaunch's.
                            val xl1 = java.io.File(rootfs, "tmp/xvfb.log.1")
                            if (xl1.isFile) noteInfo("xvfb.log.1: " + xl1.readText().lineSequence()
                                .filter { it.isNotBlank() }.toList().takeLast(4).joinToString(" | ").take(400))
                            // v0.1.87: Xvfb's OWN bind() errnos (shim v8 log)
                            // and the per-attempt flags/rc - the last missing
                            // witnesses, painted every tick so screenshots
                            // always carry them.
                            // v0.1.88: the FULL bindlog, chunked - takeLast(6)
                            // truncated exactly the entries that explain the
                            // failure (field round v0.1.87).
                            val bl = java.io.File(rootfs, "tmp/emux_bindlog")
                            if (bl.isFile) bl.readText().lineSequence()
                                .filter { it.isNotBlank() }.toList().joinToString(" ")
                                .chunked(330).take(3)
                                .forEach { chk -> noteInfo("bindlog: $chk") }
                            val at = java.io.File(rootfs, "tmp/emux_attempts")
                            if (at.isFile) noteInfo("attempts: " + at.readText().lineSequence()
                                .filter { it.isNotBlank() }.toList().takeLast(4).joinToString(" | ").take(400))
                            val xl2 = java.io.File(rootfs, "tmp/xvfb.log.2")
                            if (xl2.isFile) noteInfo("xvfb.log.2: " + xl2.readText().lineSequence()
                                .filter { it.isNotBlank() }.toList().take(3).joinToString(" | ").take(300))
                            val sc = java.io.File(rootfs, "tmp/emux_selfcheck")
                            // v0.1.86: chunked so the WHOLE selfcheck (shim
                            // errnos + guest probes) is pane-visible, not a
                            // 350-char prefix that cuts before the payload.
                            if (sc.isFile) sc.readText().chunked(340).take(3)
                                .forEach { chk -> noteInfo("selfcheck: $chk") }
                            // v0.1.77: dpkg's OWN log - names the exact package
                            // and step when a transaction fails.
                            val dl = java.io.File(rootfs, "var/log/dpkg.log")
                            if (dl.isFile) noteInfo("dpkg.log: " + dl.readText().lineSequence()
                                .filter { it.contains("error") || it.contains("status half") || it.contains("configure ") }
                                .toList().takeLast(2).joinToString(" | ").take(300))
                        }
                    }
                }

                // 3) Wait for :6000 then connect the X11 client (retry w/ backoff).
                //    v0.1.17: failure here is NON-FATAL now — frames keep flowing,
                //    we just lose XTEST input injection until the next retry.
                // v0.1.59+v0.1.61 FIELD FIX (the persistent "input disabled:
                // X server did not come up within 25s"): the old code made ONE
                // 25 s attempt plus ONE 30 s background retry. When the desktop
                // cascade has to install Xvfb first (minutes on slow links) both
                // expire while apt is still running and touch input stays dead
                // for the whole session even after X comes up. Now: a bounded
                // first attempt (60 s), then a background loop that retries
                // until connected or the desktop is stopped.
                var ctrlOk = connectControlPlane(60_000)
                if (!ctrlOk) {
                    // v0.1.74: snapshot WHY immediately, not only via watcher
                    runCatching {
                        val xt = diag["x11"]?.toString()?.lineSequence()
                            ?.filter { it.isNotBlank() }?.toList()?.takeLast(3)?.joinToString(" | ") ?: ""
                        val xl = java.io.File(rootfs, "tmp/xvfb.log")
                        noteInfo("ctrl-wait expired (ctrlErr=$lastCtrlError). x11: " + xt.take(250) +
                            " || " + hostProbeNote + " | guest: " + guestProbeTail(rootfs) +
                            " | xvfb.log: " + (if (xl.isFile) xl.readText().lineSequence()
                                .filter { it.isNotBlank() }.toList().takeLast(4).joinToString(" | ").take(400)
                            else "<absent>") +
                            (if (java.io.File(rootfs, "tmp/xvfb.log.1").isFile)
                                " | xvfb.log.1: " + java.io.File(rootfs, "tmp/xvfb.log.1").readText()
                                    .lineSequence().filter { it.isNotBlank() }.toList().takeLast(3)
                                    .joinToString(" | ").take(300) else ""))
                    }
                    errorText.value = "touch input: X TCP not up yet - retrying in background"
                    scope.launch {
                        while (isActive && x11 == null && state.value != State.IDLE && state.value != State.ERROR) {
                            delay(10_000)
                            if (connectControlPlane(20_000)) {
                                errorText.value = null
                                break
                            }
                        }
                    }
                }

                // v0.1.100: the frame reader now starts BEFORE the
                // control-plane wait (see section 1 above), so there is
                // nothing left to arm here. Promote the state on facts,
                // not on launch: CONNECTED only once a frame has actually
                // been decoded; otherwise AWAITING_FRAMES, which the
                // frameless deadline above turns into ERROR. This kills
                // the old "CONNECTED, 0 frames, tab tap does nothing"
                // dead end. A gate timeout or frame error that already
                // set ERROR/IDLE is never overwritten here.
                if (state.value == State.STARTING_X) {
                    state.value = if (frameId > 0) State.CONNECTED else State.AWAITING_FRAMES
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

    /** v0.1.97: when the capture gate gives up, dump the whole diagnostic
     *  bundle into the CURRENT TERMINAL session scrollback - the terminal can
     *  be scrolled (pgup) and screenshotted, unlike the pane's moving window. */
    private fun dumpDiagBundleToTerminal() {
        runCatching {
            val rf = com.emux.core.FsLayout.linuxRoot(ctx)
            val sb = StringBuilder()
            sb.append("\r\n===== EMUX DESKTOP DIAG BUNDLE =====\r\n")
            listOf("tmp/emux_desktop_diag.log", "tmp/emux_attempts", "tmp/emux_bindlog",
                   "tmp/emux_probes", "tmp/xvfb.log.2", "tmp/xvfb.log.1", "tmp/xvfb.log").forEach { rel ->
                val f = java.io.File(rf, rel)
                sb.append("----- $rel -----\r\n")
                sb.append(runCatching { f.readText().takeLast(3000) }.getOrDefault("<absent>"))
                sb.append("\r\n")
            }
            sb.append("===== END BUNDLE =====\r\n")
            com.emux.terminal.TerminalService.sessions().value.firstOrNull()?.write(sb.toString().toByteArray())
            noteInfo("diag bundle dumped into the Terminal tab scrollback (pgup to read)")
        }
    }

    /** v0.1.83: last guest probe lines (emux-x11 probe_sockets). */
    private fun guestProbeTail(rootfs: java.io.File): String = runCatching {
        java.io.File(rootfs, "tmp/emux_probes").readText().lineSequence()
            .filter { it.isNotBlank() }.toList().takeLast(3).joinToString(" | ").take(300)
    }.getOrDefault("")

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
        // v0.1.100: a retry must not inherit the previous attempt's
        // frame — otherwise frameId > 0 from a dead session can promote
        // the new attempt straight to CONNECTED with a stale bitmap.
        frameId = 0
        currentFrame = null
        pumpedCaptureSession = null
        captureRespawns = 0
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
        // v0.1.81 FIRST: host-authoritative orphan kill. Guest-side sweeps
        // (including the proot sweep below) CANNOT signal tracees of OTHER
        // proot instances - proot only translates pids of its own tracees -
        // so an Xvfb orphaned by a previous desktop visit keeps holding :0
        // (including the ABSTRACT unix socket, which deleting the X0 file
        // does not free) and every new Xvfb dies with "_XSERVTrans...
        // server already running / Cannot establish any listening sockets"
        // (v0.1.79+80 field panes). From the app process /proc is the real
        // host proc and orphans run under our uid: kill them directly.
        runCatching {
            val me = android.os.Process.myPid()
            val myUid = android.os.Process.myUid()
            // v0.2.40: sweep EVERY desktop client, not just the capture
            // chain. Orphaned xterms/openboxes from previous sessions sat
            // on dead displays (or worse, drew on a display a new session
            // was not capturing), while fresh sessions streamed an empty
            // root window. Age filter still protects this session's own
            // launches (they start after desktopStartUpTicks).
            val pat = Regex("Xvfb|Xorg|emux-capture|/xwd|emux-x11|openbox|tint2|pcmanfm|xterm|xclock|xeyes")
            val proc = java.io.File("/proc")
            proc.listFiles()?.forEach { d ->
                val pid = d.name.toIntOrNull() ?: return@forEach
                if (pid == me || !d.isDirectory) return@forEach
                val cmd = runCatching {
                    java.io.File(d, "cmdline").readBytes().decodeToString().replace('\u0000', ' ')
                }.getOrDefault("")
                if (cmd.isEmpty() || !pat.containsMatchIn(cmd)) return@forEach
                val uidLine = runCatching {
                    java.io.File(d, "status").readLines().firstOrNull { it.startsWith("Uid:") } ?: ""
                }.getOrDefault("")
                val uids = uidLine.split(Regex("\\s+"))
                if (uids.getOrNull(1) == myUid.toString()) {
                    // v0.2.8 FIELD FIX: age filter, host side. The sweep also
                    // matches the CURRENT session's capture/xwd; on ROMs where
                    // this host kill DOES work it murdered them mid-push.
                    // Only processes older than this desktop start are stale.
                    val ticks = runCatching {
                        val st = java.io.File(d, "stat").readText()
                        st.substringAfterLast(')').trim().split(Regex("\\s+"))[19].toLong()
                    }.getOrDefault(Long.MAX_VALUE)
                    if (ticks >= desktopStartUpTicks) return@forEach
                    com.emux.EmuXLog.i("desktop: host-killing orphan guest pid $pid: ${cmd.take(70)}")
                    android.os.Process.killProcess(pid)
                }
            }
        }
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

    /** v0.2.17: does anything actually ANSWER on this port? (connect, not
     *  bind - bind cannot see loopback-specific listeners, see startDesktop.) */
    private fun portAnswering(port: Int): Boolean = runCatching {
        java.net.Socket().apply {
            connect(java.net.InetSocketAddress("127.0.0.1", port), 300)
            close()
        }
        true
    }.getOrDefault(false)

    /** v0.1.59: one bounded attempt to reach the X server + handshake. */
    private suspend fun connectControlPlane(timeoutMs: Long): Boolean {
        // v0.1.89: the old form returned false on the FIRST handshake
        // exception - and waitForPort can succeed against a STALE listener
        // (orphan Xvfb from an earlier visit) whose handshake then fails,
        // producing a bogus "ctrl-wait expired" one second after start.
        // Now: keep retrying port+handshake until the deadline.
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: Exception? = null
        while (System.currentTimeMillis() < deadline) {
            try {
                // v0.2.6: the guest may have rotated displays (occupied
                // sockets); emux-x11 publishes the one it actually runs on.
                runCatching {
                    java.io.File(com.emux.core.FsLayout.linuxRoot(ctx), "tmp/emux_display_num")
                        .readText().trim().toIntOrNull()
                }.getOrNull()?.let { displayNum = it }
                // v0.2.29: the old tcp pre-connect could NEVER succeed
                // against a unix-only server (the field ROM's Xvfb binds
                // unix, never tcp), so it threw every retry and the
                // control plane expired beside a perfectly live display.
                // X11Client now tries the unix socket first and throws
                // only if both transports fail.
                val c = X11Client(
                    display = displayNum,
                    unixSocketPath = java.io.File(
                        com.emux.core.FsLayout.linuxRoot(ctx),
                        "tmp/.X11-unix/X$displayNum").absolutePath)
                c.connect()
                x11 = c
                inputDroppedNoted = false
                noteInfo("control plane UP via ${c.transport} :$displayNum - touch + keyboard live")
                Log.i(TAG, "X connected: ${c.screenWidth}x${c.screenHeight}@${c.depth} vendor=${c.vendor}")
                return true
            } catch (e: Exception) {
                last = e
                lastCtrlError = "${e.javaClass.simpleName}: ${e.message?.take(120)}"
                kotlinx.coroutines.delay(2_000)
            }
        }
        Log.e(TAG, "X control plane unavailable (frames still streamed)", last)
        return false
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
          // v0.2.7 FIELD FIX: re-accept after a capture stream ends. The
          // reader used to accept EXACTLY ONE connection per desktop start;
          // any capture death (respawn churn, the rc=137 loop, rotations)
          // left the session deaf forever - later captures connected and
          // pushed frames into a closed door (cap.err happily "writing PPM
          // file" while the pane sat at frames: 0). Now a finished stream
          // closes and the next helper connection is accepted, until stop.
          while (isActive) {
            var sock: Socket? = null
            // v0.2.24 FIELD FIX: count frames per connection. In per-frame
            // nc mode (the only mode since the v0.2.9 FIFO removal) EVERY
            // frame ends its connection with EOF, and the v0.2.7 re-accept
            // catch treated each of those as a stream death: a history
            // note PLUS a 500 ms pause per frame - ~2 fps and a flooded
            // pane the instant the (v0.2.23-fixed) native encoder starts
            // pushing at 10 fps. EOF after >=1 frame is now the NORMAL
            // per-frame handshake: re-accept immediately, stay silent.
            var framesOnConn = 0
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
                    framesOnConn++
                    // v0.1.100: CONNECTED means "a frame was decoded",
                    // not "the reader was launched".
                    if (state.value == State.AWAITING_FRAMES) state.value = State.CONNECTED

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
                // v0.2.7: a stream end is no longer terminal - the next
                // capture (respawn or rotated display) gets a fresh accept.
                // Only give up when the desktop is stopping or already
                // idle/erroring (v0.1.100 kept those guards).
                runCatching { sock?.close() }
                if (activeSock === sock) activeSock = null
                if (!isActive || state.value == State.IDLE || state.value == State.ERROR) break
                if (framesOnConn == 0) {
                    noteInfo("frame stream ended - waiting for the next capture connection")
                    kotlinx.coroutines.delay(500)
                }
                continue
            } finally {
                runCatching { sock?.close() }
                if (activeSock === sock) activeSock = null
            }
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

    // v0.2.44: keyboard injection for the Desktop tab (it never had any:
    // DesktopView had no InputConnection and no key routing, so typing on
    // the desktop went nowhere even with a live control plane).
    private var inputDroppedNoted = false
    private fun noteInputDropped() {
        if (!inputDroppedNoted) {
            inputDroppedNoted = true
            noteInfo("input dropped: control plane not connected (lastCtrlErr=$lastCtrlError)")
        }
    }
    fun injectKeysymTap(ks: Int): Boolean {
        val c = x11
        if (c == null) { noteInputDropped(); return false }
        return runCatching { c.typeKeysymTap(ks) }
            .getOrElse { noteInputDropped(); false }
    }
    fun injectChar(ch: Char): Boolean {
        val c = x11
        if (c == null) { noteInputDropped(); return false }
        return runCatching { c.typeChar(ch) }
            .getOrElse { noteInputDropped(); false }
    }

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
