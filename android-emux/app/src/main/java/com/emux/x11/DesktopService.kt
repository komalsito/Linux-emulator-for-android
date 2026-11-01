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
            // v0.2.66 FIX (input-dead audit): the SHORT side is long*aspect on a
            // portrait pane and long/aspect on a landscape one. `longSide / a`
            // on a portrait phone yields >1280 and clamps to longSide itself, so
            // EVERY portrait device got a 1280x1280 SQUARE X screen: giant
            // letterbox bars the "no bars" comment promised away, ~1.6M px of
            // capture per frame (a large part of the 0.5 fps field readings),
            // and a tap->X mapping whose fit the pane never actually had.
            val longSide = 1280
            val shortSide = ((if (a >= 1f) longSide / a else longSide * a)
                .coerceIn(360f, 1280f).toInt() / 2 * 2)
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
                // v0.2.57 FIELD FIX: synchronous pre-session component
                // install. The furniture apt pass used to run INSIDE the
                // session (governor), racing guest execve against dpkg
                // transactions - proot's signal-11 wave then killed whoever
                // lost. Install host-driven, narrated, BEFORE the guest
                // session spawns; the Terminal tab stays idle through this
                // window by design. ~1 min on a fresh device, then
                // "components ready" and the session starts turnkey.
                com.emux.core.BootstrapManager.progressNote = { m -> noteInfo(m) }
                try {
                    val cn = com.emux.core.BootstrapManager(ctx).installDesktopComponents()
                    noteInfo(if (cn == null) "components ready (all present)" else "components ready: $cn")
                } finally {
                    com.emux.core.BootstrapManager.progressNote = null
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
                // v0.2.49: reap init-parented leftovers before investing in
                // a session - "failed to fork" inside the guest was orphan
                // accumulation, and a reboot used to be the only cure.
                com.emux.core.BootstrapManager.reapOrphanGuests { m -> noteInfo(m) }
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
                    bm.installWallpaper(rootfs)
                    noteInfo("helpers re-seeded from APK assets (env " +
                        com.emux.core.FsLayout.ENV_VERSION + ")")
                }.onFailure {
                    noteInfo("helper re-seed failed (non-fatal): " + (it.message?.take(80) ?: "?"))
                }
                // v0.2.47 FIELD FIX / v0.2.59 CORRECTION: opening the Desktop tab
                // used to kill the Terminal tab on ~1 GB devices - Android's
                // low-memory killer reaped the terminal's proot (a background
                // process once the user switched tabs), and a tracee whose
                // tracer dies prints "proot warning: signal 11" (the pid-29008
                // ghost in every round).
                //
                // The v0.2.47 implementation did not actually do what it
                // claimed, for two reasons measured here:
                //   1. Writing a NEGATIVE oom_score_adj requires CAP_SYS_RESOURCE,
                //      which an unprivileged app does not have. The "-300" that
                //      was supposed to protect the Terminal's proot has ALWAYS
                //      been silently swallowed by runCatching, while the "+100"
                //      on the desktop guests succeeded. Net effect: the desktop
                //      got more killable and the terminal got nothing.
                //   2. `if (!cmd.contains("proot")) return@forEach` skipped every
                //      real memory hog - Xvfb, Openbox, tint2 and pcmanfm are
                //      proot TRACEES whose own cmdline contains no "proot" - so
                //      the processes actually holding the framebuffer and the
                //      desktop were never adjusted at all.
                //
                // We cannot protect the terminal from userspace. What we can do
                // is make the desktop stack the preferred sacrifice and report
                // the truth, so a field log distinguishes "bias applied" from
                // "bias denied (needs root)" instead of implying success.
                var lmkRaised = 0; var lmkProtected = 0; var lmkDenied = 0
                runCatching {
                    val myUid = android.os.Process.myUid().toString()
                    java.io.File("/proc").listFiles()?.forEach { d ->
                        val cmd = runCatching {
                            java.io.File(d, "cmdline").readBytes().decodeToString().replace('\u0000', ' ')
                        }.getOrDefault("")
                        if (cmd.isBlank()) return@forEach
                        val uidLine = runCatching {
                            java.io.File(d, "status").readLines().firstOrNull { it.startsWith("Uid:") } ?: ""
                        }.getOrDefault("")
                        if (uidLine.split(Regex("\\s+")).getOrNull(1) != myUid) return@forEach
                        // Desktop-side first (highest = most killable), then the
                        // terminal side. Order matters: emux-x11's own launcher
                        // cmdline also mentions Xvfb.
                        val adj = when {
                            cmd.contains("emux-capture") || cmd.contains("/xwd") -> "950"
                            cmd.contains("emux-x11") -> "900"
                            cmd.contains("Xvfb") || cmd.contains("Xorg") -> "850"
                            cmd.contains("openbox") || cmd.contains("tint2") ||
                            cmd.contains("pcmanfm") || cmd.contains("picom") ||
                            cmd.contains("xsetroot") || cmd.contains("feh") -> "800"
                            cmd.contains("proot") &&
                            (cmd.contains("bash") || cmd.contains("/bin/sh") || cmd.contains("script")) -> "-300"
                            else -> return@forEach
                        }
                        val ok = runCatching {
                            val f = java.io.File(d, "oom_score_adj")
                            f.writeText(adj)
                            f.readText().trim() == adj   // verify: EPERM is silent
                        }.getOrDefault(false)
                        if (ok) { if (adj.startsWith("-")) lmkProtected++ else lmkRaised++ }
                        else if (adj.startsWith("-")) lmkDenied++
                    }
                }
                if (lmkDenied > 0) noteInfo(
                    "LMK: raised $lmkRaised desktop procs, but protecting the Terminal " +
                    "(oom_score_adj -300) was DENIED on $lmkDenied procs - that needs root. " +
                    "Under memory pressure Android can still reap the terminal's proot " +
                    "(\"proot warning: signal 11\"). picom is now opt-in to cut the pressure.")
                else if (lmkRaised + lmkProtected > 0) noteInfo(
                    "LMK bias applied: $lmkRaised desktop procs raised, $lmkProtected terminal procs protected")
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
                // v0.2.52 FIELD FIX: the control plane must follow display
                // rotations too - it pinned its display at connect time, so
                // after a self-heal rotation all input went to the old,
                // windowless server ("i cant click anything" even post-fix).
                // Watch the marker; on change, reconnect X11Client to the new
                // display (unix-first, as always).
                scope.launch {
                    while (isActive && state.value != State.IDLE) {
                        kotlinx.coroutines.delay(3_000)
                        val dn = runCatching {
                            java.io.File(rootfs, "tmp/emux_display_num").readText().trim().toIntOrNull()
                        }.getOrNull() ?: continue
                        if (dn != displayNum) {
                            noteInfo("display rotated to :$dn - reconnecting control plane")
                            displayNum = dn
                            runCatching { x11?.close() }
                            x11 = null
                            scope.launch { connectControlPlane(30_000) }
                        }
                    }
                }
                // v0.2.67 FIX (input-dead audit, round 2): CONTINUOUS input
                // proof + plane agreement. The connect-time proof certifies
                // the server ONCE; nothing revisited it, and the rotation
                // watcher above only reacts to a display NUMBER change. Two
                // failure classes therefore stayed invisible for ten
                // versions:
                //  (A) the server behind our socket dies and is restarted on
                //      the SAME number (LMK kill on a non-rooted device ->
                //      xvfb watchdog relaunch): emux-capture opens a FRESH
                //      xwd connection per frame and recovers transparently,
                //      while our persistent control-plane socket is a corpse
                //      - live desktop, green badge, zero response;
                //  (B) the two planes disagree on the display (zombie socket
                //      ladder / tcp-vs-unix fallbacks / marker races): input
                //      lands on a desktop nobody streams.
                // Every 5 s we (1) compare our display with the one
                // emux-capture publishes (/tmp/emux_cap_display - the capture
                // plane is by definition the truth of what the user sees),
                // and (2) round-trip a QueryPointer on the control plane, the
                // cheapest possible proof that OUR connection still has a
                // living server on the end. Either miss recycles the control
                // plane (and input rides the guest agent meanwhile).
                scope.launch {
                    while (isActive && state.value != State.IDLE) {
                        kotlinx.coroutines.delay(5_000)
                        // v0.2.68: record the publication even when STALE - a
                        // climbing capage on the badge is the field-visible
                        // proof of a dead capture helper (the frozen-photo
                        // failure mode round 3 fixes).
                        capturePubRaw()?.let { (n, ts) ->
                            captureDisplay = n
                            capturePubAtMs = ts * 1000
                        }
                        val cap = freshCaptureDisplay()
                        if (cap != null) captureDisplay = cap
                        val c = xtest() ?: continue
                        if (cap != null && cap != displayNum) {
                            displayNum = cap
                            recycleControlPlane(
                                "capture streams :$cap but the control plane is on a different display")
                            continue
                        }
                        val alive = runCatching { c.queryPointerRoot() }.getOrNull()
                        if (alive == null) {
                            keepaliveMiss++
                            if (keepaliveMiss >= 2) {
                                keepaliveMiss = 0
                                recycleControlPlane(
                                    "keepalive: the control-plane server missed 2 QueryPointer round-trips")
                            } else {
                                noteInfo("keepalive: QueryPointer miss #$keepaliveMiss (proot load?) - one more recycles the control plane")
                            }
                        } else keepaliveMiss = 0
                    }
                }
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
                        if (cur != null && cur.state.value == TerminalSession.State.DEAD) {
                            // v0.2.68 FIX (input-dead audit, round 3): the
                            // `frameId == 0` gate meant a capture helper that
                            // died AFTER delivering its first frame was never
                            // respawned and never reported - the pane kept
                            // painting its last bitmap forever while the
                            // "LIVE x fps" badge froze at the last computed
                            // rate (frameTimes is only trimmed when a NEW
                            // frame arrives). On device that reads exactly
                            // like "desktop shows, input dead": the screen is
                            // a photograph, the synthetic cursor still moves
                            // (app-side), taps inject into a real desktop the
                            // user cannot see, and nothing ever damages the
                            // frozen frame. Respawn on ANY death; the badge
                            // now also paints STALE when frames stop.
                            val pre = frameId == 0
                            if (captureRespawns < 40) {
                                captureRespawns++
                                com.emux.EmuXLog.i("desktop: capture died ${if (pre) "before first frame" else "AFTER frame $frameId"} - respawning (#$captureRespawns)")
                                noteInfo("capture died ${if (pre) "pre-frame" else "post-frame #$frameId"} (rc=${cur.exitCode}) - respawning #$captureRespawns")
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
            // v0.2.59 FIELD FIX — the sweep used to kill its own proot.
            // The pattern names ("Xvfb", "Xorg", "emux-capture", "xwd") were
            // literal text inside the `/bin/sh -c <payload>` argument, and
            // proot's OWN argv contains that same payload string. proot binds
            // the host /proc, so the loop walked straight into the tracer's
            // cmdline, matched *Xvfb*, and `kill -9`'d it. The `[ $p != $$ ]`
            // guard only excluded the shell, not its proot parent. Killing a
            // ptrace tracer is precisely how a tracee ends up reporting
            // "proot warning: signal 11" — the user's exact symptom — and it
            // also aborted the sweep before the X lock cleanup, leaving stale
            // locks that make the NEXT session adopt a dead display ("can't see
            // the desktop"). Patterns now travel in the ENVIRONMENT (never in
            // any cmdline) and the shell skips itself and its parent.
            val sweepPatterns = listOf("Xvfb", "Xorg", "emux-capture", "/xwd")
            // Hard guard on a wide blast radius: an EMPTY pattern would make the
            // case arm `*""*`, which matches every cmdline and kill -9's the
            // whole uid. The sentinel below cannot appear in a real argv, so a
            // short list degrades to "match nothing" rather than "match all".
            val neverMatch = "\u0001emux-never\u0001"
            fun sweepVar(i: Int) = sweepPatterns.getOrElse(i) { neverMatch }
                .ifBlank { neverMatch }
            val payload =
                "PP=\$(sed 's/.*) //' /proc/self/stat 2>/dev/null | cut -d' ' -f2); " +
                "for f in /proc/[0-9]*/cmdline; do " +
                "p=\${f#/proc/}; p=\${p%/cmdline}; " +
                "[ \"\$p\" = \"\$\$\" ] && continue; " +
                "[ -n \"\$PP\" ] && [ \"\$p\" = \"\$PP\" ] && continue; " +
                "c=\$(tr '\\0' ' ' < \$f 2>/dev/null); " +
                "case \$c in " +
                "*\"\$EMUX_SW1\"*|*\"\$EMUX_SW2\"*|*\"\$EMUX_SW3\"*|*\"\$EMUX_SW4\"*) " +
                "kill -9 \$p 2>/dev/null;; " +
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
                // v0.2.59: the match patterns live here, NOT in argv.
                "EMUX_SW1" to sweepVar(0),
                "EMUX_SW2" to sweepVar(1),
                "EMUX_SW3" to sweepVar(2),
                "EMUX_SW4" to sweepVar(3),
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
                // v0.2.67 FIX: the CAPTURE plane is the truth of what the user
                // sees; emux-capture publishes "<display> <epoch>" every loop.
                // When that publication is fresh it outranks the marker (which
                // a raced or orphaned guest writer can hold at a number nobody
                // streams), so a reconnect can never re-land on a display the
                // pane is not showing.
                freshCaptureDisplay()?.let { cap ->
                    if (cap != displayNum) {
                        noteInfo("control plane: capture streams :$cap, marker said :$displayNum - following the capture")
                        displayNum = cap
                    }
                    captureDisplay = cap
                }
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
                inputTransport = "xtest"
                inputAgentUp = true
                // v0.2.59: prove the control plane is real, not just connected.
                // A correct handshake yields a plausible geometry, an XTEST
                // opcode above 127 and a populated keymap; if any of those is
                // wrong the pane says so instead of claiming "input live".
                val wmUp = runCatching { c.isWindowManagerRunning() }.getOrDefault(false)
                noteInfo(
                    "control plane UP via ${c.transport} :$displayNum - XTEST opcode ${c.xtestOpcode}, " +
                    "${c.keymapSize()} keysyms, ${c.screenWidth}x${c.screenHeight}@${c.depth}, " +
                    "WM ${if (wmUp) "running" else "NOT running"}")
                Log.i(TAG, "X connected: ${c.screenWidth}x${c.screenHeight}@${c.depth} vendor=${c.vendor}")
                // v0.2.65: connected is not the same as WORKING. Prove injection
                // end-to-end before trusting it with the user's fingers, and if
                // the server ignores us, hand input to the guest agent instead.
                inputProof = "pending"
                xtestIgnored = false
                Thread({ runInputProof(c) }, "emux-input-proof").apply { isDaemon = true }.start()
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
    // v0.2.59 — TWO transports, tried in this order:
    //
    //  1. XTEST over the control plane ([X11Client]). This is the real path.
    //     It was dead for 52 rounds purely because of wire-protocol bugs in
    //     X11Client (big-endian setup length, QueryExtension sent as opcode 15
    //     = QueryTree, 24-byte FakeInput body instead of 36, rootX/rootY inside
    //     pad2) — not because of the ROM. Fixed there; verified end to end
    //     against Xvfb 1.21 + Openbox 3.6.1: an injected click focused an
    //     xterm and injected keystrokes ran a shell command. One persistent
    //     socket, ~0.02 ms per event, no guest process per keystroke, and
    //     keycodes come from the server's own GetKeyboardMapping so the shift
    //     level is always right.
    //
    //  2. The rootfs FIFO + guest xdotool agent, kept only as a fallback for a
    //     ROM where the host genuinely cannot reach the X socket. It costs one
    //     xdotool process per event under proot, so it is coarsely coalesced
    //     here. Its keyboard protocol was also wrong: the app sent a KEYSYM
    //     integer and the agent ran `xdotool keydown <int>`, but xdotool treats
    //     a bare number as a KEYCODE. Measured: `xdotool keydown 97` injects
    //     keycode 97, while keysym 97 ('a') is keycode 38 — so typing produced
    //     nothing. The agent now takes `T <text>` for xdotool type and
    //     `N <name>` for X11 key names, which xdotool resolves correctly.

    /** Human-readable input transport, painted on the pane by DesktopView. */
    @Volatile var inputTransport: String = "down"; private set

    /** Back-compat for the pane badge: true when ANY input path is live. */
    @Volatile var inputAgentUp = false; private set

    /**
     * v0.2.65 INPUT PROOF.
     *
     * Field v0.2.64: the desktop paints and the badge says
     * "INPUT: XTEST (control plane)" in green, yet no click or key does
     * anything. Every leg of the input path was individually plausible —
     * touches reach the view, the transform is correct, the control plane
     * handshakes, the 36-byte FakeInput body was measured against a live
     * Xvfb — and FakeInput is sendNoReply, so a server that IGNORES an event
     * looks exactly like one that honoured it. Nothing on the device could
     * tell the difference, and with no adb there was no way to find out.
     *
     * So the app now proves its own input on-device, the same way it already
     * proves the control plane ("prove the control plane is real, not just
     * connected"): inject a motion to a known point, read the pointer back
     * with QueryPointer, compare. Values:
     *   pending     - control plane not up yet, or proof not yet run
     *   ok          - the server moved the pointer where we asked
     *   ignored     - pointer did not move and the server sent no error
     *   rejected:E  - the server answered X error E for the injection
     *   no-ptr-reply- the server would not answer QueryPointer at all
     * On anything but `ok` the XTEST path is switched OFF (see [xtest]) and
     * input falls back to the guest xdotool agent, which injects from INSIDE
     * the guest and therefore does not depend on our wire encoding at all.
     */
    @Volatile var inputProof: String = "pending"; private set

    /** Set when the proof showed the server ignoring/rejecting FakeInput. */
    @Volatile private var xtestIgnored = false

    /** Touches that reached DesktopView.onTouchEvent (ACTION_DOWN). */
    @Volatile var touchCount: Long = 0L

    /** Injection calls the service actually made (any transport). */
    @Volatile var injectCount: Long = 0L

    fun noteTouch() { touchCount++ }

    /** What the guest agent's own markers say, for the pane badge. */
    fun agentEvidence(): String {
        val rt = com.emux.core.FsLayout.linuxRoot(ctx)
        val launched = java.io.File(rt, "tmp/emux_agent_launched").isFile
        val nox = java.io.File(rt, "tmp/emux_agent_noxtool").isFile
        val clicked = java.io.File(rt, "tmp/emux_agent_firstclick").isFile
        return when {
            !launched -> "agent=never-started"
            nox -> "agent=no-xdotool"
            clicked -> "agent=clicked"
            else -> "agent=idle"
        }
    }

    // X11 keysym -> xdotool key NAME, for the keysyms DesktopView produces.
    // xdotool resolves names through XStringToKeysym and the live keymap, so
    // this is layout-independent.
    private val keysymNames: Map<Int, String> = mapOf(
        0xFF08 to "BackSpace", 0xFF09 to "Tab", 0xFF0D to "Return",
        0xFF1B to "Escape", 0xFFFF to "Delete", 0xFF50 to "Home",
        0xFF51 to "Left", 0xFF52 to "Up", 0xFF53 to "Right",
        0xFF54 to "Down", 0xFF55 to "Prior", 0xFF56 to "Next",
        0xFF57 to "End", 0xFF58 to "Begin", 0xFF61 to "Print",
        0xFF63 to "Insert", 0xFFBE to "F1", 0xFFBF to "F2",
        0xFFC0 to "F3", 0xFFC1 to "F4", 0xFFC2 to "F5", 0xFFC3 to "F6",
        0xFFC4 to "F7", 0xFFC5 to "F8", 0xFFC6 to "F9", 0xFFC7 to "F10",
        0xFFC8 to "F11", 0xFFC9 to "F12", 0xFFE1 to "shift",
        0xFFE2 to "shift", 0xFFE3 to "ctrl", 0xFFE4 to "ctrl",
        0xFFE7 to "Meta_L", 0xFFE8 to "Meta_R", 0xFFE9 to "alt",
        0xFFEA to "alt", 0xFF7F to "Num_Lock", 0xFF14 to "Caps_Lock",
        0xFF02 to "Caps_Lock",
    )

    // --- FIFO transport (fallback) -----------------------------------------
    private var inputFifo: java.io.FileOutputStream? = null
    private var inputFifoKey: Any? = null
    private val inputExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "emux-input").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }
    }
    // A drag floods motion events far faster than a proot-spawned xdotool can
    // drain them; keep only the newest motion so a gesture never queues up.
    private val inputPending = java.util.ArrayDeque<String>()
    // Declared as java.lang.Object, NOT Any(): Kotlin's `Any` does not expose
    // wait()/notifyAll(), so the monitor calls below are unresolved references
    // and the module fails to compile. Caught by kotlinc, not by inspection.
    private val inputLock = java.lang.Object()
    @Volatile private var inputDropped = 0L

    private fun fifoFile() = java.io.File(com.emux.core.FsLayout.linuxRoot(ctx), "tmp/emux_in")

    private fun fifoFileKey(f: java.io.File): Any? = runCatching {
        java.nio.file.Files.readAttributes(f.toPath(),
            java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
    }.getOrNull()

    /**
     * Enqueue one agent line. NEVER blocks the caller: open(2) on a FIFO blocks
     * until a reader appears, and write(2) blocks once the pipe fills — both
     * used to happen on the UI thread from onTouchEvent, which is an ANR and
     * reads to the user as "the desktop ignores my finger".
     */
    private fun inputLine(line: String): Boolean {
        // v0.2.66 FIX (input-dead audit): the gate must ask "is XTEST USABLE",
        // not "is the control plane CONNECTED". The old test returned false
        // whenever x11.connected, but this fallback branch is only reachable
        // when xtest() == null, i.e. exactly when connected == true AND the
        // v0.2.65 proof set xtestIgnored. So on a server that ignores FakeInput
        // every event was silently dropped here and the advertised guest-agent
        // fallback never received a single line. Use xtest() (which already
        // factors in connected && opcode>127 && !ignored).
        if (xtest() != null) return false                     // XTEST owns input
        synchronized(inputLock) {
            // Coalesce consecutive motion events into the newest one.
            if (line.startsWith("M ")) {
                val it = inputPending.descendingIterator()
                while (it.hasNext()) if (it.next().startsWith("M ")) it.remove() else break
            }
            if (inputPending.size > 512) { inputPending.pollFirst(); inputDropped++ }
            inputPending.addLast(line)
            inputLock.notifyAll()
        }
        return true
    }

    private fun inputWriterLoop() {
        while (true) {
            val line = synchronized(inputLock) {
                // v0.2.59: wait whenever the queue is empty. The previous
                // condition ALSO required `x11?.connected != true`, so the
                // moment the XTEST control plane came up this loop stopped
                // waiting, pollFirst() returned null and `?: continue` spun
                // the executor thread at 100% CPU for the entire desktop
                // session (battery drain + scheduler pressure that starved
                // the very input path it was meant to back up).
                while (inputPending.isEmpty()) {
                    runCatching { inputLock.wait(250) }
                }
                inputPending.pollFirst()
            } ?: continue
            runCatching {
                // v0.2.65: open(2) on a FIFO BLOCKS until a reader appears. If
                // the agent never started — or started and found no xdotool, in
                // which case its read loop is gone — this thread would park
                // here forever with no error anywhere, and the pane would keep
                // advertising a transport that cannot deliver. The agent's own
                // markers answer the question before we touch the FIFO.
                val rt = com.emux.core.FsLayout.linuxRoot(ctx)
                if (!java.io.File(rt, "tmp/emux_agent_launched").isFile) {
                    noteInputDropped("input dropped: the guest input agent never started (no /tmp/emux_agent_launched)")
                    runCatching { Thread.sleep(1_000) }; return@runCatching
                }
                if (java.io.File(rt, "tmp/emux_agent_noxtool").isFile) {
                    noteInputDropped("input dropped: guest xdotool is missing, the agent cannot replay events")
                    runCatching { Thread.sleep(1_000) }; return@runCatching
                }
                val f = fifoFile()
                // Reopen when the agent recreated the FIFO (it rm+mkfifo's on
                // every session start); a cached stream then points at an
                // unlinked inode and every write silently vanishes.
                val key = fifoFileKey(f)
                if (inputFifo == null || (key != null && key != inputFifoKey)) {
                    runCatching { inputFifo?.close() }
                    inputFifo = java.io.FileOutputStream(f, false)
                    inputFifoKey = key
                }
                inputFifo!!.write((line + "\n").toByteArray())
                inputFifo!!.flush()
                if (!inputAgentUp) { inputAgentUp = true; inputTransport = "agent" }
            }.onFailure {
                inputAgentUp = false
                inputTransport = "down"
                runCatching { inputFifo?.close() }
                inputFifo = null; inputFifoKey = null
                noteInputDropped()
            }
        }
    }

    private var inputWriterStarted = false
    private fun ensureInputWriter() {
        if (inputWriterStarted) return
        inputWriterStarted = true
        inputExecutor.execute { runCatching { inputWriterLoop() } }
    }

    // --- injection entry points --------------------------------------------
    /** True when the XTEST control plane is live and owning input. */
    private fun xtest(): X11Client? =
        x11?.takeIf { it.connected && it.xtestOpcode > 127 && !xtestIgnored }

    // ------------------------------------------------------------------
    // v0.2.67 FIX (input-dead audit, round 2): a control plane that FAILS A
    // WRITE IS A DEAD CONTROL PLANE - and until now nothing in the app could
    // ever notice. `X11Client.connected` only flips in connect()/close(); the
    // inject path never reads the socket, so a connection whose server went
    // away mid-session (Xvfb restarted on the SAME display number after an
    // LMK kill on a non-rooted device, xvfb watchdog recycle, proot socket
    // translation drop, server-side client disconnect) kept "owning" input
    // forever: every fakeButton threw inside runCatching, the note landed in
    // the history ring that frames flow over (invisible), the badge stayed
    // green on the connect-time proof, inj= kept counting writes that never
    // arrived, and the FIFO fallback could not engage because xtest() still
    // returned the corpse (connected == true). Field signature, verbatim
    // from the v0.2.66 device screenshot: "INPUT: XTEST (control plane)" +
    // "proof=ok touch=18 inj=36 agent=idle" over a desktop that opens
    // nothing and never damages (steady "LIVE 0.5 fps" idle heartbeat).
    // Two consecutive failed writes now recycle the connection: close it,
    // hand input to the guest agent IMMEDIATELY (xtest() goes null so the
    // inputLine gate opens), and reconnect + re-proof in the background.
    @Volatile private var ctrlWriteFails = 0
    @Volatile private var keepaliveMiss = 0
    @Volatile var injectFail: Long = 0L; private set
    @Volatile private var recycling = false

    /** The display the control plane is currently connected to. */
    val controlDisplay: Int get() = displayNum

    /** The display emux-capture actually streams (published guest-side). */
    @Volatile var captureDisplay: Int = -1; private set

    private fun controlPlaneFailure(reason: String) {
        injectFail++
        ctrlWriteFails++
        if (ctrlWriteFails >= 2) recycleControlPlane(reason)
        else noteInfo("control plane write failed (#$ctrlWriteFails): ${reason.take(160)}")
    }

    private fun recycleControlPlane(reason: String) {
        if (recycling) return
        recycling = true
        ctrlWriteFails = 0
        noteInfo(
            "v0.2.67: control plane DEAD (${reason.take(200)}) - input hands over to the " +
                "guest agent now; reconnecting + re-proofing in the background")
        runCatching { x11?.close() }
        x11 = null
        inputProof = "pending"
        val rt = com.emux.core.FsLayout.linuxRoot(ctx)
        inputTransport =
            if (java.io.File(rt, "tmp/emux_agent_launched").isFile) "agent" else "down"
        inputAgentUp = inputTransport == "agent"
        scope.launch {
            runCatching { connectControlPlane(30_000) }
            recycling = false
        }
    }

    /** v0.2.67: parse "<display> <epoch>" as published by emux-capture. */
    private fun freshCaptureDisplay(maxAgeSec: Long = 30): Int? = runCatching {
        val t = java.io.File(com.emux.core.FsLayout.linuxRoot(ctx), "tmp/emux_cap_display")
            .readText().trim()
        val parts = t.split(Regex("\\s+"))
        val n = parts.getOrNull(0)?.toIntOrNull() ?: return@runCatching null
        val ts = parts.getOrNull(1)?.toLongOrNull() ?: return@runCatching null
        val age = System.currentTimeMillis() / 1000 - ts
        if (age in 0..maxAgeSec) n else null
    }.getOrNull()

    // v0.2.68: the publication's AGE is the on-screen proof that the capture
    // helper is alive: emux-capture rewrites /tmp/emux_cap_display every loop,
    // so a dead helper freezes the epoch and this number climbs forever.
    private fun capturePubRaw(): Pair<Int, Long>? = runCatching {
        val parts = java.io.File(com.emux.core.FsLayout.linuxRoot(ctx), "tmp/emux_cap_display")
            .readText().trim().split(Regex("\\s+"))
        val n = parts.getOrNull(0)?.toIntOrNull() ?: return@runCatching null
        val ts = parts.getOrNull(1)?.toLongOrNull() ?: return@runCatching null
        n to ts
    }.getOrNull()

    @Volatile private var capturePubAtMs = -1L

    /** Seconds since emux-capture last published; -1 if it never did. */
    val capturePubAgeSec: Long get() =
        if (capturePubAtMs < 0) -1 else (System.currentTimeMillis() - capturePubAtMs) / 1000

    private fun surfaceXtestErrors(c: X11Client) {
        val errs = c.takeErrors()
        if (errs.isNotEmpty()) noteInfo("XTEST rejected an event: ${errs.first().describe()}")
    }

    /**
     * v0.2.65: see [inputProof]. Runs once per control-plane connection, on its
     * own daemon thread — never the FIFO writer's single thread (which would
     * stall real input behind it) and never the UI thread.
     */
    private fun runInputProof(c: X11Client) {
        runCatching {
            val p0 = c.queryPointerRoot()
            if (p0 == null) { inputProof = "no-ptr-reply"; return }
            // v0.2.66 FIX (input-dead audit): aim AWAY from the nearest edge.
            // With a naive +48 the target clamps onto p0 when the pointer sits
            // near the far edge, and the proof then "passes" on a server that
            // ignored the motion entirely (p1 == p0 == target).
            val dxp = if (p0.first + 48 <= c.screenWidth - 4) 48 else -48
            val dyp = if (p0.second + 48 <= c.screenHeight - 4) 48 else -48
            val txp = (p0.first + dxp).coerceIn(4, (c.screenWidth - 4).coerceAtLeast(8))
            val typ = (p0.second + dyp).coerceIn(4, (c.screenHeight - 4).coerceAtLeast(8))
            c.fakeMotion(txp, typ)
            Thread.sleep(250)
            val p1 = c.queryPointerRoot()
            val errs = c.takeErrors()
            if (p1 != null && kotlin.math.abs(p1.first - txp) <= 4 &&
                kotlin.math.abs(p1.second - typ) <= 4
            ) {
                inputProof = "ok"
                com.emux.EmuXLog.i("input proof OK: server pointer moved to $txp,$typ as injected")
            } else {
                xtestIgnored = true
                inputProof =
                    if (errs.isNotEmpty()) "rejected:" + errs.first().describe().take(24)
                    else "ignored"
                // v0.2.66 FIX (input-dead audit): the pane badge painted from
                // inputTransport, which was set to "xtest" at connect time and
                // never revisited - so a server that ignores FakeInput still
                // showed a GREEN "INPUT: XTEST (control plane)". Re-state the
                // transport now, from what will actually carry the events.
                val rt = com.emux.core.FsLayout.linuxRoot(ctx)
                inputTransport =
                    if (java.io.File(rt, "tmp/emux_agent_launched").isFile) "agent" else "down"
                inputAgentUp = inputTransport == "agent"
                noteInfo(
                    "input proof FAILED (server $inputProof): ptr=$p1 wanted=$txp,$typ - " +
                        "XTEST disabled, input falls back to the guest agent (${agentEvidence()})")
            }
        }.onFailure { e ->
            inputProof = "proof-error"
            com.emux.EmuXLog.e("input proof threw", e)
        }
    }

    fun injectClick(xInXSpace: Int, yInXSpace: Int, button: Int = 1, press: Boolean) {
        injectCount++
        val c = xtest()
        if (c != null) {
            runCatching { c.fakeButton(button, xInXSpace, yInXSpace, press); surfaceXtestErrors(c) }
                .onFailure { controlPlaneFailure("XTEST click failed: $it") }
            return
        }
        ensureInputWriter()
        inputLine("M $xInXSpace $yInXSpace 0")
        inputLine(if (press) "D $button 0 0" else "U $button 0 0")
    }

    fun injectMotion(x: Int, y: Int) {
        injectCount++
        val c = xtest()
        if (c != null) {
            runCatching { c.fakeMotion(x, y) }
                .onFailure { controlPlaneFailure("XTEST motion failed: $it") }
            return
        }
        ensureInputWriter(); inputLine("M $x $y 0")
    }

    /** Drag ended outside a click: just release button 1 at position. */
    fun fakeButtonReleaseOnly(x: Int, y: Int) {
        injectCount++
        val c = xtest()
        if (c != null) {
            runCatching { c.fakeButton(1, x, y, false); surfaceXtestErrors(c) }
                .onFailure { controlPlaneFailure("XTEST release failed: $it") }
            return
        }
        ensureInputWriter(); inputLine("M $x $y 0"); inputLine("U 1 0 0")
    }

    fun injectKeycode(keycode: Int, press: Boolean) {
        injectCount++
        // v0.2.66 FIX: was the one entry point that bypassed xtest(), so it
        // kept injecting into a server the proof had just declared dead while
        // every other path had fallen back.
        val c = xtest()
        if (c != null) {
            runCatching { c.fakeKey(keycode, press); surfaceXtestErrors(c) }
                .onFailure { controlPlaneFailure("XTEST key failed: $it") }
            return
        }
        noteInputDropped("keycode $keycode dropped: no XTEST, and the agent protocol is keysym-name based")
    }

    private var inputDroppedNoted = false
    private var inputDroppedReason = ""

    /**
     * v0.2.65: re-note when the REASON changes. The old once-per-session flag
     * meant the first (often transient) reason masked every later, more
     * specific one — and the specific one is the one that names the remedy.
     */
    private fun noteInputDropped(reason: String =
        "input dropped: no XTEST control plane and the guest FIFO agent is unreachable " +
        "(is xdotool installed? see /tmp/emux_agent.log)") {
        if (!inputDroppedNoted || reason != inputDroppedReason) {
            inputDroppedNoted = true
            inputDroppedReason = reason
            noteInfo(reason)
        }
    }

    /** Tap one X11 keysym (used for Return/BackSpace/arrows/... from DesktopView). */
    fun injectKeysymTap(ks: Int): Boolean {
        injectCount++
        val c = xtest()
        if (c != null) {
            val ok = runCatching { c.typeKeysymTap(ks) }.getOrDefault(false)
            if (ok) { surfaceXtestErrors(c); return true }
            noteInputDropped("keysym 0x${Integer.toHexString(ks)} is not in the server keymap")
            return false
        }
        ensureInputWriter()
        val name = keysymNames[ks]
        return if (name != null) {
            inputLine("N $name")
        } else if (ks in 0x20..0x7E) {
            // Printable ASCII: send the character, xdotool type resolves it.
            inputLine("T " + ks.toChar())
        } else {
            noteInputDropped("no agent encoding for keysym 0x${Integer.toHexString(ks)}")
            false
        }
    }

    /** Type one character. */
    fun injectChar(ch: Char): Boolean {
        // Route control chars through their keysym: over the FIFO the payload
        // is line-delimited, so a literal '\n' would be parsed as the start of
        // the next command.
        if (ch == '\n' || ch == '\r') return injectKeysymTap(0xFF0D)
        if (ch == '\t') return injectKeysymTap(0xFF09)
        if (ch == '\b') return injectKeysymTap(0xFF08)
        val c = xtest()
        if (c != null) {
            // v0.2.67 FIX: falling through to inputLine() here was a silent
            // drop - while XTEST owns input, inputLine()'s gate returns false,
            // so a failed typeChar (write threw, or keysym not in keymap)
            // vanished with no note at all. Distinguish the two.
            val res = runCatching { c.typeChar(ch) }
            res.onFailure { controlPlaneFailure("typeChar threw: $it"); return false }
            if (res.getOrDefault(false)) { surfaceXtestErrors(c); return true }
            noteInputDropped("char '$ch' has no keymap encoding on this server - dropped")
            return false
        }
        ensureInputWriter()
        return inputLine("T $ch")
    }

    /**
     * Scroll wheel. X encodes the wheel as BUTTONS, not a separate event type:
     * 4 = up, 5 = down, 6 = left, 7 = right (buttons 6/7 are the XFree86
     * horizontal extension and are ignored by clients that do not know them).
     * Each "click" is a press+release pair at the pointer's current position.
     */
    fun injectScroll(xInXSpace: Int, yInXSpace: Int, button: Int, clicks: Int) {
        injectCount++
        if (clicks <= 0 || button !in 4..7) return
        val c = xtest()
        if (c != null) {
            runCatching {
                // fakeButton() emits the motion itself, so the wheel lands
                // wherever the fingers are rather than at the last click.
                repeat(clicks.coerceAtMost(12)) {
                    c.fakeButton(button, xInXSpace, yInXSpace, true)
                    c.fakeButton(button, xInXSpace, yInXSpace, false)
                }
                surfaceXtestErrors(c)
            }.onFailure { noteInputDropped("XTEST scroll failed: $it") }
            return
        }
        ensureInputWriter()
        inputLine("M $xInXSpace $yInXSpace 0")
        repeat(clicks.coerceAtMost(12)) {
            inputLine("D $button 0 0")
            inputLine("U $button 0 0")
        }
    }

    /**
     * Tap a keysym with Ctrl / Alt / Super held down.
     *
     * Shift is deliberately NOT a parameter: [X11Client.typeKeysymTap] already
     * applies the shift level it read out of the server's own keymap, so holding
     * Shift here too would double-apply it. Ctrl+Shift+T therefore works by
     * passing the keysym for 'T' with ctrl=true.
     *
     * Over XTEST the modifiers are real key presses around the tap. Over the
     * FIFO agent they are folded into one `xdotool key ctrl+alt+<name>`, which
     * xdotool resolves through XStringToKeysym - so a single ASCII char is a
     * valid name and no keysym table is needed for letters.
     */
    fun injectKeysymCombo(ks: Int, ctrl: Boolean = false, alt: Boolean = false,
                          meta: Boolean = false): Boolean {
        injectCount++
        if (!ctrl && !alt && !meta) return injectKeysymTap(ks)
        val c = xtest()
        if (c != null) {
            val held = ArrayList<Int>(3)
            val ok = runCatching {
                // Order matters only for tidiness; release is the reverse.
                if (ctrl) c.keysymKeycode(0xFFE3).takeIf { it > 0 }?.let { held.add(it); c.fakeKey(it, true) }
                if (alt)  c.keysymKeycode(0xFFE9).takeIf { it > 0 }?.let { held.add(it); c.fakeKey(it, true) }
                if (meta) c.keysymKeycode(0xFFEB).takeIf { it > 0 }?.let { held.add(it); c.fakeKey(it, true) }
                val r = c.typeKeysymTap(ks)
                held.asReversed().forEach { c.fakeKey(it, false) }
                r
            }.getOrDefault(false)
            if (ok) { surfaceXtestErrors(c); return true }
            if (held.isEmpty()) {
                noteInputDropped("modifier keysym is not in the server keymap (no Ctrl/Alt/Super)")
                return false
            }
        }
        ensureInputWriter()
        val name = keysymNames[ks] ?: if (ks in 0x20..0x7E) ks.toChar().toString() else null
        if (name == null) {
            noteInputDropped("no agent encoding for keysym 0x${Integer.toHexString(ks)}")
            return false
        }
        val parts = ArrayList<String>(4)
        if (ctrl) parts.add("ctrl")
        if (alt) parts.add("alt")
        if (meta) parts.add("super")
        parts.add(name)
        return inputLine("N " + parts.joinToString("+"))
    }

    /** Type a whole IME commit at once — one agent line, one xdotool process. */
    fun injectText(text: String): Boolean {
        if (text.isEmpty()) return true
        val c = xtest()
        if (c != null) {
            val ok = runCatching { c.typeString(text) }.getOrDefault(false)
            if (ok) { surfaceXtestErrors(c); return true }
        }
        ensureInputWriter()
        // The FIFO protocol is one command per LINE, so an embedded newline
        // would be parsed as the next command (and an empty line dropped).
        // Normalise CRLF, then emit text segments as `T` and each newline as
        // an explicit `N Return`.
        var ok = true
        text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
            .forEachIndexed { i, seg ->
                if (i > 0) ok = inputLine("N Return") && ok
                if (seg.isNotEmpty()) ok = inputLine("T $seg") && ok
            }
        return ok
    }

    // -------------------------------------------------------------------- stop
    fun stop() {
        frameReader?.cancel(); frameReader = null
        runCatching { activeSock?.close() }; activeSock = null   // v0.1.54
        runCatching { listener?.close() }; listener = null
        x11?.close(); x11 = null
        // v0.2.59: drop the queued input and the FIFO handle. The writer thread
        // is a daemon and is reused by the next session; leaving the stream open
        // would keep writing into a FIFO the next agent has already unlinked.
        synchronized(inputLock) { inputPending.clear() }
        runCatching { inputFifo?.close() }; inputFifo = null; inputFifoKey = null
        inputAgentUp = false; inputTransport = "down"
        scope.launch {
            xSession?.stop(); captureSession?.stop()
            xSession = null; captureSession = null
            killDesktopGuests()   // v0.1.54: guests outlive session stop otherwise
        }
        state.value = State.IDLE
    }
}
