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

        /**
         * v0.2.79: the NATIVE path's own foreground ceiling. It was hardcoded to
         * 333 ms (3 fps) while the socket path used MAX_FPS_FOREGROUND = 30 - so
         * the pipeline every modern device actually runs (`src=native` on the
         * badge) was capped ten times lower than the one it replaced. That cap is
         * invisible at 1280 long side, where the pipe alone takes ~2 s per
         * refresh, and it is the ONLY thing you feel once the capture resolution
         * drops. Lower than the socket path's 30 on purpose: a native refresh
         * re-reads and re-decodes the FULL screen every frame (GetImage ->
         * RGB565 -> IntArray -> Bitmap), so its per-frame CPU cost is much higher
         * than the socket path's diff decode. 10 fps is where the pipe stops
         * being the limiter on a healthy device without cooking the phone.
         */
        const val MAX_FPS_NATIVE_FOREGROUND = 10

        /** v0.2.79: capture quality default - the long side of the X screen, in
         *  pixels. Unchanged from every previous release, so nobody's desktop
         *  gets softer without asking; Settings -> "Desktop capture quality"
         *  offers 1280/960/720/540 and the pane shows the rate each one gets. */
        const val DEFAULT_CAPTURE_LONG_SIDE = 1280

        /** v0.2.79: set by Settings -> "Restart desktop now", consumed by
         *  MainActivity.onResume. The X screen size is fixed when Xvfb starts, so
         *  a capture-quality change cannot be applied to a live session. */
        @Volatile var restartDesktopRequested = false
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
    /** v0.2.92: badge-readable form of the last control-plane reconnect failure. */
    val ctrlErrorText: String? get() = lastCtrlError
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
            nativeFresh -> "capture: native XGetImage ($nativeFrames frames)"
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

    // --------------------------------------------------------------------- v0.2.71
    // NATIVE IN-APP CAPTURE — the x0vncserver/x11vnc model, finally.
    // Every proven X-remoting project reads the framebuffer through ONE
    // persistent X connection (MIT-SHM when available, GetImage(ZPixmap) as
    // the universal fallback). Our per-frame guest pipeline
    // (xwd | xwdtopnm | pnmtoplainpnm | nc → :7001) spawns 4+ proot'd
    // processes PER FRAME on a rootfs whose package manager cannot run —
    // the field verdicts (v0.2.69 capage=22s, v0.2.70 capage=15s with a
    // healthy input plane) are that chain wedging/dying cyclically. Native
    // capture needs only the X socket the control plane already proves
    // works: zero guest binaries, zero FIFOs, zero per-frame processes.
    // The legacy pipeline is kept as an automatic fallback.
    @Volatile var captureMode: String = "-"; private set
    @Volatile var nativeFrames: Long = 0; private set
    @Volatile private var nativeFrameAtMs = 0L
    private var nativeJob: Job? = null
    private var nativeClient: X11Client? = null

    // v0.2.72 telemetry: the STALE pane must name WHY capture is silent.
    // v0.2.71's field round said only "no frames arriving" while the actual
    // cause (BadLength on every GetImage) sat invisible in the protocol.
    @Volatile var nativeGrabMs: Long = -1; private set
    // v0.2.80: sweep-level truth for the LIVE badge. nativeGrabMs is only set when
    // a FULL sweep completes; on the field device it stayed -1 while bands kept
    // landing, so the badge claimed "1.0 fps" for what was really partial
    // repaints - the "sloppy" feel. These say where the sweep got to and why.
    @Volatile var nativeBandY: Int = 0; private set
    @Volatile var nativeMisses: Int = 0; private set
    @Volatile var nativeLastErr: String = ""; private set
    /** v0.2.81: is the live capture connection DAMAGE-driven? Badge says so. */
    @Volatile var nativeDamageOn: Boolean = false; private set
    @Volatile var nativeErr: String = ""; private set
    /** v0.2.74: measured pipe throughput (KB/s) and current band height —
     *  painted on the STALE pane so the field tells us the real ceiling. */
    @Volatile var nativeKbPerS: Long = -1; private set
    @Volatile var nativeBandRows: Int = 0; private set
    @Volatile private var lastAnyFrameAtMs = 0L

    // ------------------------------------------------------------- v0.2.84
    // PROOF vs PAINT. The band-diff painter (see diffUpload) makes "no frame
    // delivered" a SUCCESS state: an idle desktop produces no changed bands,
    // so nothing is uploaded and nativeFrames stays put. Every liveness gate
    // that used frame delivery as its proof would then declare a perfectly
    // healthy pipeline dead (and the badge showed "LIVE 0.0 fps" for what is
    // really "nothing changed"). So the two notions are now separate:
    //   nativeProofAtMs      - the last grab that SUCCEEDED (bytes crossed the
    //                          wire, whatever the diff made of them);
    //   lastSourceProofAtMs  - the same, for ANY capture source (native, fbdir,
    //                          mailbox, socket) - the silence watchdog's clock;
    //   lastAnyFrameAtMs     - the last frame that actually PAINTED new pixels.
    @Volatile private var nativeProofAtMs = 0L
    @Volatile private var lastSourceProofAtMs = 0L

    /** Bands found byte-identical and skipped, since session start (telemetry). */
    @Volatile var diffSkips: Long = 0; private set

    /** Wall-clock ms of the last PAINTED update - the badge's idle detector. */
    val lastFrameAtMs: Long get() = lastAnyFrameAtMs

    /**
     * What the pipe can actually carry, in full-screen frames per second,
     * from the measured KB/s: the REAL capture rate - a ceiling that does not
     * fall to zero when the desktop is idle. Badge shows `cap~Nfps`.
     */
    val nativeCapacityFps: Int get() {
        val kb = nativeKbPerS
        if (kb <= 0 || sessionW <= 0 || sessionH <= 0) return -1
        return ((kb.toLong() * 1024L) / (sessionW.toLong() * sessionH * 2L)).toInt()
    }

    /** True while native capture's grab path has worked within the last 15 s —
     *  the guest publication file is then stale BY DESIGN and must not be
     *  used for capage/plane-agreement/wedge decisions.
     *  v0.2.84: keyed on grab PROOF, not painted frames - an idle desktop
     *  paints nothing by design and is still perfectly fresh. */
    private val nativeFresh: Boolean
        get() = nativeProofAtMs != 0L &&
            System.currentTimeMillis() - nativeProofAtMs < 15_000

    /**
     * Dedicated capture connection + GetImage loop. Runs until the session
     * stops; reconnects (with backoff) across server deaths/rotations, so a
     * display rotation or an LMK-kill of Xvfb self-heals without touching
     * the control plane.
     */
    // ------------------------------------------------------------------
    // v0.2.106: memory-mapped -fbdir framebuffer (see startNativeCapture).
    // Xvfb opens its -fbdir file with O_CREAT and no O_TRUNC, extends it once
    // and unlinks it at exit, so a live mapping can never shrink under us
    // (the SIGBUS case); an unlinked file just stops changing, which the
    // healer's socket cross-check catches.
    @Volatile private var fbMap: java.nio.MappedByteBuffer? = null
    @Volatile private var fbMapOff = 0
    @Volatile private var fbMapBpl = 0
    @Volatile private var fbMapW = 0
    @Volatile private var fbMapH = 0
    @Volatile private var fbMapB = 2
    // v0.2.109 SERVER IDENTITY, done right. v0.2.90 compared the setup
    // reply's resource-id-base between the capture and input connections, but
    // the X server hands EVERY CLIENT its own base - two connections to the
    // same Xvfb always differ. So the guard rejected every healthy control
    // plane ("control plane reached a different X server"), input fell back to
    // the busy capture socket or the per-event xdotool agent, and taps felt
    // slow and flaky. Atoms ARE per server: the capture interns a unique name,
    // the input plane looks it up with only-if-exists - same id = same server,
    // None = a different one.
    private val serverToken = "_EMUX_SRV_" + java.lang.Long.toHexString(System.nanoTime())
    @Volatile private var capTokenAtom = 0

    /** true = [c] is the server the capture streams (or there is nothing to compare). */
    private fun onCapturedServer(c: X11Client): Boolean {
        val tok = capTokenAtom
        if (tok == 0 || nativeClient?.connected != true) return true
        return runCatching { c.internAtom(serverToken, onlyIfExists = true) }.getOrDefault(tok) == tok
    }

    /** v0.2.109: bytes per pixel of the live native pipeline (2 = 565, 4 = XRGB). */
    @Volatile private var pxB = 2
    private var fbCheck: ByteArray? = null

    private fun openFbMap(w: Int, h: Int): Boolean {
        closeFbMap()
        return runCatching {
            val f = java.io.File(com.emux.core.FsLayout.linuxRoot(ctx), "tmp/emux_fbdir/Xvfb_screen0")
            if (!f.isFile) return@runCatching false
            val len = f.length()
            if (len < 200L || len > 64_000_000L) return@runCatching false
            java.io.RandomAccessFile(f, "r").use { raf ->
                val mb = raf.channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, len)
                val head = ByteArray(minOf(len, 65_536L).toInt())
                mb.duplicate().get(head)
                val hd = xwdHeaderOf(head, head.size) ?: return@runCatching false
                if (hd.w != w || hd.h != h || hd.bpp != pxB * 8 || !hd.le || hd.bpl < w * pxB)
                    return@runCatching false
                if (hd.off.toLong() + hd.bpl.toLong() * h > len) return@runCatching false
                fbMapOff = hd.off; fbMapBpl = hd.bpl; fbMapW = w; fbMapH = h; fbMapB = pxB
                fbMap = mb
                true
            }
        }.getOrDefault(false)
    }

    private fun closeFbMap() { fbMap = null }

    /** Copy a region of the mapped framebuffer into [out] as contiguous
     *  RGB565 LE rows (the same layout grabRegion565 produces).
     *  @return bytes written, or -1 when the map is off or the region invalid. */
    private fun fbRegion(x: Int, y: Int, bw: Int, rows: Int, out: ByteArray): Int {
        val mb = fbMap ?: return -1
        if (x < 0 || y < 0 || bw <= 0 || rows <= 0 || x + bw > fbMapW || y + rows > fbMapH) return -1
        val b = fbMapB
        val n = bw * rows * b
        if (out.size < n) return -1
        return try {
            val d = mb.duplicate()
            for (r in 0 until rows) {
                d.position(fbMapOff + (y + r) * fbMapBpl + x * b)
                d.get(out, r * bw * b, bw * b)
            }
            n
        } catch (t: Throwable) {
            closeFbMap()
            noteInfo("-fbdir map read failed (${t.javaClass.simpleName}) - damage bands back on GetImage")
            -1
        }
    }

    private fun fbCheckBuf(n: Int): ByteArray {
        var b = fbCheck
        if (b == null || b.size < n) { b = ByteArray(n); fbCheck = b }
        return b
    }

    private fun regionEquals(a: ByteArray, b: ByteArray, n: Int): Boolean {
        var i = 0
        while (i < n) { if (a[i] != b[i]) return false; i++ }
        return true
    }

    fun startNativeCapture(onUiInvalidated: () -> Unit) {
        nativeJob?.cancel()
        nativeJob = scope.launch {
            var backoff = 500L
            while (isActive && state.value != State.IDLE && state.value != State.ERROR) {
                var c: X11Client? = null
                try {
                    // v0.2.91: the capture loop follows the guest published
                    // display ITSELF on every (re)connect instead of waiting
                    // for the 3 s rotation watcher to update displayNum. The
                    // v0.2.89/v0.2.90 field rounds had the capture retrying a
                    // dead :0 for minutes while the healthy desktop sat on :1
                    // and the marker file said so.
                    runCatching {
                        java.io.File(com.emux.core.FsLayout.linuxRoot(ctx), "tmp/emux_display_num")
                            .readText().trim().toIntOrNull()
                    }.getOrNull()?.let { dn ->
                        if (dn != displayNum) {
                            noteInfo("native capture: guest published :$dn (was :$displayNum) - following")
                            displayNum = dn
                        }
                    }
                    c = X11Client(
                        display = displayNum,
                        unixSocketPath = java.io.File(
                            com.emux.core.FsLayout.linuxRoot(ctx),
                            "tmp/.X11-unix/X$displayNum").absolutePath,
                    ).also { it.connect(10_000) }
                    nativeClient = c
                    capInputOk = false   // v0.2.92: per-connection fact - re-prove
                    backoff = 500L
                    if (captureMode != "native") {
                        captureMode = "native"
                        noteInfo(
                            "native capture UP: XGetImage(ZPixmap) on :$displayNum " +
                                "${c.screenWidth}x${c.screenHeight} depth${c.depth} — " +
                                "no guest binaries in the frame path")
                    }
                    var misses = 0
                    // v0.2.73 BANDED CAPTURE (the x11vnc model, completed).
                    // Field v0.2.72 telemetry: `nat=no-reply(timeout/eof)` —
                    // the 6-unit wire fix landed, but ONE 3.5 MB full-screen
                    // reply cannot cross this device's proot-translated
                    // socket inside any sane timeout. Small replies pass.
                    // So: probe the pipe once per connection with a 16-row
                    // band, size every later band to ~1.2 s of measured
                    // pipe, and paint each band the moment it lands. The
                    // pane goes live progressively instead of never.
                    val w = c.screenWidth; val h = c.screenHeight
                    // v0.2.109 TRUE COLOR: the pipeline carries the server's own
                    // pixels end to end - 2 bytes (RGB565) on a depth-16 screen,
                    // 4 bytes (XRGB8888) on depth 24. Everything below sizes
                    // its buffers by this, never by a literal 2.
                    val pB = if (c.depth >= 24) 4 else 2
                    pxB = pB
                    // v0.2.109: stamp THIS server with an atom only it knows,
                    // so the input plane can prove it reached the same one.
                    capTokenAtom = runCatching { c.internAtom(serverToken) }.getOrDefault(0)
                    // v0.2.80 FIELD FIX ("capture only goes up to 1 fps" while
                    // the badge still read 1280x...): the X screen size is fixed
                    // when Xvfb starts, so a quality change lands ONLY via a
                    // restart - and if the old server is still dying when the new
                    // one starts, the launcher's adopt-don't-fight rule (v0.2.13)
                    // adopts the OLD server at the OLD geometry and the setting
                    // silently does nothing. Verify against the live server and
                    // fix it once, loudly, instead of never.
                    if (sessionLongSide > 0 && maxOf(w, h) != sessionLongSide &&
                        sessionLongSide == captureLongSide() && geometryFixes < 2
                    ) {
                        geometryFixes++
                        noteInfo(
                            "GEOMETRY MISMATCH: live server ${w}x${h} but the requested " +
                                "long side is $sessionLongSide - the quality change did not " +
                                "land; restarting the desktop once (fix #$geometryFixes)")
                        scope.launch {
                            kotlinx.coroutines.delay(1_500)
                            stop()
                            kotlinx.coroutines.delay(2_500)
                            startDesktop(wmCommand = lastWm)
                        }
                    }
                    var bandRows = 64
                    var refreshes = 0
                    var kbEwma = -1L          // v0.2.80: measured from real bands
                    // v0.2.84: sweep bands land as RAW RGB565 (grabRegion565)
                    // and go through the band-diff painter - half the memory of
                    // the old IntArray bandBuf, and unchanged rows are never
                    // converted nor uploaded at all.
                    var bandBytes = ByteArray(w * bandRows * pB)
                    fun setBands(r: Int) {
                        bandRows = r.coerceIn(4, h)
                        nativeBandRows = bandRows
                        if (bandBytes.size < w * bandRows * pB) bandBytes = ByteArray(w * bandRows * pB)
                    }
                    run {
                        val p0 = System.currentTimeMillis()
                        val probeBytes = ByteArray(w * 16 * pB)
                        val probeN = runCatching { c.grabRegion565(0, 0, w, 16, 10_000, probeBytes, pB) }.getOrDefault(-1)
                        val pms = (System.currentTimeMillis() - p0).coerceAtLeast(1)
                        if (probeN > 0) {
                            nativeProofAtMs = System.currentTimeMillis()
                            lastSourceProofAtMs = nativeProofAtMs
                            nativeKbPerS = (w * 16 * pB) / pms
                            kbEwma = nativeKbPerS
                            setBands(((nativeKbPerS * 1024 * 6 / 5) / (w * pB)).toInt().coerceIn(8, h))
                            noteInfo("native probe on :$displayNum: ${w}x16 in ${pms}ms -> ~${nativeKbPerS}KB/s -> band ${bandRows} rows of $h")
                        } else {
                            setBands(8)
                            noteInfo("native probe FAILED on :$displayNum (${c.lastGrabError}) - minimal 8-row bands; KB/s will be measured from live bands instead")
                            if (c.lastGrabError.startsWith("xerr") && fbMailSession == null) {
                                ensureFbMail("native probe rejected on :$displayNum (${c.lastGrabError})")
                            }
                        }
                    }
                    val bandTimeout = 8_000
                    // v0.2.81 DAMAGE-DRIVEN CAPTURE. Polling full sweeps made
                    // every visual response wait behind a whole screenful of
                    // pixels that had not changed - on this device's pipe that
                    // is seconds, and no pacing or quality change can fix the
                    // architecture. With DAMAGE the server accumulates what
                    // clients actually drew; we read that region, grab ONLY it,
                    // paint it, and go back to sleep. An idle desktop now costs
                    // almost nothing and answers in one round trip; a dragging
                    // window costs its own rectangle instead of the screen.
                    val dmgOk = runCatching { c.initDamageTracking() }.getOrDefault(false)
                    nativeDamageOn = dmgOk
                    if (dmgOk) noteInfo(
                        "native capture on :$displayNum: DAMAGE-driven partial updates ON - " +
                            "only changed rectangles cross the pipe")
                    else noteInfo(
                        "native capture on :$displayNum: DAMAGE unavailable - full sweeps as before")
                    // v0.2.106 ZERO-COPY PIXELS: Xvfb runs with -fbdir, so its
                    // framebuffer IS a file in the shared rootfs. Map it once
                    // per connection and serve damage bands from memory: the
                    // proot-translated socket now carries only DamageNotify
                    // events and the (validated) healer sweep, instead of
                    // every changed pixel. Falls back to GetImage per band on
                    // any mismatch or read failure.
                    val fbOk = openFbMap(w, h)
                    noteInfo(if (fbOk)
                        "native capture on :$displayNum: -fbdir framebuffer MAPPED " +
                            "(${w}x${h}x${pB * 8}, bpl=$fbMapBpl) - damage bands read from memory, not the socket"
                        else "native capture on :$displayNum: -fbdir map unavailable - damage bands via GetImage")
                    var fbSuspect = 0
                    // v0.2.108: once two socket sweeps in a row AGREE with the
                    // map, the healer itself reads the map too; every 8th sweep
                    // still goes over the socket as the cross-check.
                    var fbGood = 0
                    var sweepNo = 0
                    val partCap = (h / 2).coerceAtLeast(64)
                    // v0.2.83: a band taller than this is grabbed in chunks, so
                    // even a full-screen damage burst paints progressively
                    // instead of vanishing behind one giant reply.
                    val chunkRows = minOf(partCap, 256)
                    // v0.2.84: chunks land raw (565) for the band-diff painter.
                    val partBytes = ByteArray(w * chunkRows * pB)
                    // v0.2.83 PERSISTENT DIRTY BANDS - the replacement for the
                    // v0.2.82 union rectangle. That design merged ALL damage
                    // since the last tick into ONE rect, and whenever the rect
                    // covered >60% of the screen (or was taller than partCap)
                    // it gave up and ran a FULL BANDED SWEEP. A drag is exactly
                    // that case: the moving window damages its new position,
                    // the exposure damages the old one, and the union of the
                    // two is half the screen - so every drag frame paid a full
                    // refresh, and on a slow pipe the sweep was still running
                    // long after the finger stopped: "it waits for the input to
                    // stop and then the window appears at the destination".
                    // Bands keep damage as damage: horizontal strips, merged
                    // only with strips they (nearly) touch, PERSISTENT across
                    // ticks (a band this tick's budget could not paint is still
                    // pending next tick), each painted from its own small grab.
                    // Nothing in the damage path can trigger a full sweep now;
                    // the sweep stays what it should be - the healer.
                    val dirtyBands = ArrayList<IntArray>()   // [x0, y0, x1, y1]
                    val BAND_MARGIN = 24
                    fun addDirty(nx0: Int, ny0: Int, nx1: Int, ny1: Int) {
                        if (nx1 <= nx0 || ny1 <= ny0) return
                        var x0 = nx0; var y0 = ny0; var x1 = nx1; var y1 = ny1
                        var i = 0
                        while (i < dirtyBands.size) {
                            val b = dirtyBands[i]
                            // merge when the row spans overlap or nearly touch:
                            // two strips 20 rows apart are one cheaper grab.
                            if (y0 - BAND_MARGIN <= b[3] && b[1] - BAND_MARGIN <= y1) {
                                x0 = minOf(x0, b[0]); y0 = minOf(y0, b[1])
                                x1 = maxOf(x1, b[2]); y1 = maxOf(y1, b[3])
                                dirtyBands.removeAt(i)
                                i = 0   // the grown strip may touch another; rescan
                            } else i++
                        }
                        dirtyBands.add(intArrayOf(x0, y0, x1, y1))
                    }
                    // Two band-grab failures in a row hand over to the banded
                    // sweep, whose adaptive band sizing is the real healer for
                    // a degrading pipe.
                    var dmgMisses = 0
                    // v0.2.84 ADAPTIVE HEALER: the diff made an all-unchanged
                    // sweep almost free, but it still crosses the pipe. Stretch
                    // the cadence while sweeps keep coming back clean (idle
                    // desktop) and snap back the moment anything uploads.
                    val HEALER_TICKS = 150
                    var healerEvery = HEALER_TICKS
                    var cleanHealers = 0
                    // v0.2.82: start one tick below the healer so the FIRST loop
                    // iteration is a full sweep. Damage events only report
                    // changes AFTER we subscribe; without this the pane sat on
                    // nothing (or on the previous session's photograph) until the
                    // healer tick, ~5 s in - which on a slow device reads as
                    // "the desktop is dead".
                    var ticks = 149
                    while (isActive && state.value != State.IDLE && state.value != State.ERROR) {
                        val t0 = System.currentTimeMillis()
                        ensureBitmap(w, h, pB == 4)
                        // v0.2.83: once native has proven itself, retire the
                        // guest mailbox pipelines - co-running them is CPU
                        // contention against the very grabs that make the
                        // desktop feel fast (see retireMailPipelines).
                        if (nativeFresh && (fbMailSession != null || captureSession != null))
                            retireMailPipelines()
                        if (dmgOk) {
                            ticks++
                            // ~every 5 s a full sweep anyway: the healer, for
                            // anything DAMAGE cannot see (server-side sprite
                            // draws, a missed event, our own bookkeeping).
                            if (ticks % healerEvery != 0) {
                                // Block for the first rect ONLY when nothing is
                                // pending (the idle path costs one parked read,
                                // never a poll); when bands are waiting from the
                                // last tick's budget, just skim whatever accrued
                                // while we painted.
                                val rects = if (dirtyBands.isEmpty()) c.drainDamageRects(50)
                                            else c.drainDamageRects(2, 0)
                                for (r in rects) {
                                    addDirty(
                                        r[0].coerceIn(0, w), r[1].coerceIn(0, h),
                                        (r[0] + r[2]).coerceIn(0, w),
                                        (r[1] + r[3]).coerceIn(0, h))
                                }
                                if (dirtyBands.isEmpty()) continue   // the drain already waited
                                // v0.2.84 DRAG PRIORITY: while a button is
                                // down, the band under the pointer goes FIRST -
                                // the user's eye is there, and on a
                                // budget-limited tick the region being watched
                                // must never be the one left pending. Other
                                // ticks keep top-down order.
                                if (ptrDown && System.currentTimeMillis() - ptrAtMs < 1_500) {
                                    val py = ptrY
                                    dirtyBands.sortBy { b ->
                                        when {
                                            py < b[1] -> b[1] - py
                                            py >= b[3] -> py - b[3] + 1
                                            else -> 0
                                        }
                                    }
                                } else dirtyBands.sortBy { it[1] }
                                var painted = false
                                var failed = false
                                var bands = 0
                                // A damage tick may not own the connection for
                                // longer than its budget; unpainted bands stay
                                // pending and resume next tick (immediately -
                                // the drain above does not block when bands are
                                // waiting). This is what keeps continuous damage
                                // (video) from starving a drag, and a drag from
                                // starving the healer.
                                while (dirtyBands.isNotEmpty() && !failed && bands < 6 &&
                                    System.currentTimeMillis() - t0 < 120L) {
                                    val b = dirtyBands.removeAt(0)
                                    val bw = (b[2] - b[0]).coerceIn(1, w)
                                    var by = b[1]
                                    while (by < b[3]) {
                                        val rows = minOf(chunkRows, b[3] - by)
                                        val gt = System.currentTimeMillis()
                                        val n = fbRegion(b[0], by, bw, rows, partBytes).takeIf { it > 0 }
                                            ?: runCatching {
                                                c.grabRegion565(b[0], by, bw, rows, bandTimeout, partBytes, pB)
                                            }.getOrDefault(-1)
                                        if (n <= 0) {
                                            addDirty(b[0], by, b[0] + bw, b[3])  // keep the rest pending
                                            failed = true
                                            break
                                        }
                                        nativeProofAtMs = System.currentTimeMillis()
                                        lastSourceProofAtMs = nativeProofAtMs
                                        // v0.2.84: upload only the 32-row
                                        // slices that actually differ from the
                                        // mirror. DAMAGE says the server
                                        // touched these rows; the diff says
                                        // which of them the USER will see
                                        // change (a cursor blink back to the
                                        // same pixels, an expose of unchanged
                                        // wallpaper - all free now).
                                        if (diffUpload(b[0], by, bw, rows, partBytes, 0, w, h, pB) > 0) {
                                            frameId++
                                            onUiInvalidated()      // progressive paint per chunk
                                            painted = true
                                        }
                                        val bms = (System.currentTimeMillis() - gt).coerceAtLeast(1)
                                        val bkb = (bw.toLong() * rows * pB) / bms
                                        kbEwma = if (kbEwma <= 0) bkb else (kbEwma * 3 + bkb) / 4
                                        if (nativeKbPerS <= 0) nativeKbPerS = kbEwma
                                        by += rows
                                    }
                                    bands++
                                }
                                if (failed) {
                                    dmgMisses++
                                    nativeLastErr = c.lastGrabError.ifBlank { "null" }
                                    if (dmgMisses < 2) {
                                        kotlinx.coroutines.delay(30)
                                        continue    // retry the pending bands next tick
                                    }
                                    dmgMisses = 0
                                    // fall through to the banded sweep: two
                                    // failures in a row say the pipe, not the
                                    // rectangle, is the problem.
                                } else {
                                    dmgMisses = 0
                                    if (painted) {
                                        lastAnyFrameAtMs = System.currentTimeMillis()
                                        nativeGrabMs = System.currentTimeMillis() - t0
                                        nativeFrames++
                                        nativeFrameAtMs = lastAnyFrameAtMs
                                        capturePubAtMs = nativeFrameAtMs
                                        nativeMisses = 0; nativeLastErr = ""
                                        nativeBandY = h
                                        healerEvery = HEALER_TICKS; cleanHealers = 0
                                        if (state.value == State.AWAITING_FRAMES)
                                            state.value = State.CONNECTED
                                        // damage mode paces at 8 ms, not the
                                        // sweep ceiling: the grab of a small
                                        // strip is cheap, and this is what
                                        // makes a drag follow the finger
                                        // instead of the sweep.
                                        if (nativeGrabMs < 8L) kotlinx.coroutines.delay(8L - nativeGrabMs)
                                    }
                                    continue
                                }
                            }
                            // healer tick: fall through to the sweep WITHOUT
                            // clearing dirtyBands - they are cleared only after
                            // a sweep that actually completed (see below), so a
                            // capped sweep cannot lose pre-healer damage.
                        }
                        var y = 0
                        var ok = true
                        // v0.2.83 FIRST-SWEEP CAP: a sweep that cannot finish in
                        // 8 s stops and yields to the damage path, which is what
                        // keeps the desktop interactive while the healer tick
                        // finishes the rest. Before this, a ~200 KB/s pipe made
                        // the OPENING sweep run for tens of seconds with every
                        // touch queued behind it - the field's "slow to open".
                        // Only when DAMAGE is live: without it the sweep is the
                        // ONLY frame source and must run to completion.
                        val sweepDeadline = if (dmgOk) t0 + 8_000L else Long.MAX_VALUE
                        var capped = false
                        var sweepUploaded = 0
                        var fbChecked = 0
                        var fbMismatch = 0
                        sweepNo++
                        val fbSweep = fbMap != null && fbGood >= 2 && sweepNo % 8 != 0
                        while (y < h) {
                            if (System.currentTimeMillis() > sweepDeadline) {
                                capped = true
                                noteInfo("native sweep capped at y=$y/$h after 8s - damage path takes over; the healer finishes the rest")
                                break
                            }
                            val rows = minOf(bandRows, h - y)
                            val tb = System.currentTimeMillis()
                            val n = (if (fbSweep) fbRegion(0, y, w, rows, bandBytes).takeIf { it > 0 } else null)
                                ?: runCatching { c.grabRegion565(0, y, w, rows, bandTimeout, bandBytes, pB) }.getOrDefault(-1)
                            if (n <= 0) { ok = false; break }
                            // v0.2.106: the socket sweep is the ground truth -
                            // check the mapped framebuffer against it, band by
                            // band. A map that reads a different server's file
                            // (stray Xvfb) or a dead inode disagrees on nearly
                            // every band and gets dropped below.
                            if (fbMap != null && !fbSweep) {
                                val chk = fbCheckBuf(w * rows * pB)
                                if (fbRegion(0, y, w, rows, chk) > 0) {
                                    fbChecked++
                                    if (!regionEquals(chk, bandBytes, w * rows * pB)) fbMismatch++
                                }
                            }
                            nativeProofAtMs = System.currentTimeMillis()
                            lastSourceProofAtMs = nativeProofAtMs
                            // v0.2.80: KB/s from the bands that ACTUALLY land, so
                            // the badge can never show -1 on a live session (the
                            // field screenshot did: probe failed, no sweep ever
                            // completed, both readings -1).
                            val bms = (System.currentTimeMillis() - tb).coerceAtLeast(1)
                            val bkb = (w * rows * pB) / bms
                            kbEwma = if (kbEwma <= 0) bkb else (kbEwma * 3 + bkb) / 4
                            if (nativeKbPerS <= 0) nativeKbPerS = kbEwma
                            // v0.2.84: the healer sweep is a DIFF now - an idle
                            // desktop costs the grab plus a memcmp per band and
                            // uploads nothing, invalidates nothing, and does not
                            // dent the LIVE fps reading with fake frames.
                            if (diffUpload(0, y, w, rows, bandBytes, 0, w, h, pB) > 0) {
                                sweepUploaded++
                                frameId++
                                lastAnyFrameAtMs = System.currentTimeMillis()
                                onUiInvalidated()          // progressive paint per band
                            }
                            y += rows
                        }
                        if (!ok) {
                            misses++
                            nativeMisses = misses
                            nativeLastErr = c.lastGrabError.ifBlank { "null" }
                            nativeBandY = y
                            nativeErr = nativeLastErr
                            // v0.2.80 ADAPTIVE BANDS: a band that cannot cross the
                            // pipe in 8 s stalls the whole sweep (and the pane
                            // keeps showing the previous sweep's pixels below
                            // y). Halve the band so the scan keeps moving; it
                            // regrows after a clean sweep below.
                            if (bandRows > 4) setBands(bandRows / 2)
                            if (misses <= 2 || misses % 10 == 0) {
                                noteInfo("native band miss #$misses on :$displayNum at y=$y: $nativeErr")
                            }
                            if (nativeFrames == 0L && nativeErr.startsWith("xerr") && fbMailSession == null) {
                                ensureFbMail("native grabs rejected on :$displayNum ($nativeErr)")
                            }
                            if (misses >= 3) {
                                throw java.io.IOException("native grab failed ${misses}x: $nativeErr")
                            }
                            kotlinx.coroutines.delay(200L * misses)
                            continue
                        }
                        misses = 0
                        nativeErr = ""
                        nativeMisses = 0
                        nativeLastErr = ""
                        nativeBandY = if (capped) y else h
                        // v0.2.106: two sweeps in a row where >75% of the bands
                        // disagree with the map = the map is not this server's
                        // framebuffer. Busy content (video) can disagree on a few
                        // bands through timing alone, never on almost all of them.
                        if (fbMap != null && fbChecked >= 4) {
                            if (fbMismatch * 4 > fbChecked * 3) {
                                fbGood = 0
                                fbSuspect++
                                if (fbSuspect >= 2) {
                                    closeFbMap()
                                    noteInfo("-fbdir map DISAGREES with the server ($fbMismatch/$fbChecked bands, twice) - dropped; damage bands back on GetImage")
                                }
                            } else { fbSuspect = 0; fbGood++ }
                        }
                        nativeGrabMs = System.currentTimeMillis() - t0
                        // v0.2.83: a sweep that ran to completion repainted the
                        // whole screen, so every pending damage band is now
                        // redundant. A CAPPED sweep did not - keep the set.
                        if (!capped) dirtyBands.clear()
                        // v0.2.80: regrow towards ~1.2 s of measured pipe after a
                        // clean sweep, so adaptive halving is not a one-way ratchet.
                        // v0.2.83: a capped sweep is evidence the pipe is SLOWER
                        // than the current bands assume - never regrow on one.
                        if (!capped && bandRows < h) {
                            val target = if (nativeKbPerS > 0)
                                ((nativeKbPerS * 1024 * 6 / 5) / (w * pB)).toInt().coerceIn(8, h)
                            else bandRows * 2
                            if (target > bandRows) setBands(minOf(target, bandRows * 2))
                        }
                        captureDisplay = displayNum
                        refreshes++
                        // v0.2.84 PROOF vs PAINT: a completed sweep proves the
                        // pipeline even when the diff found nothing to upload;
                        // it counts as a FRAME only when pixels changed.
                        // Edge case: an all-black first screen diffs clean
                        // against the zeroed mirror, yet the pane (a fresh
                        // black bitmap) is already correct and the frameless
                        // deadline needs frameId to move - promote like a
                        // painted sweep.
                        if (sweepUploaded == 0 && refreshes == 1) {
                            frameId++
                            lastAnyFrameAtMs = System.currentTimeMillis()
                            onUiInvalidated()
                            sweepUploaded = 1
                        }
                        if (sweepUploaded > 0) {
                            nativeFrames++
                            nativeFrameAtMs = System.currentTimeMillis()
                            capturePubAtMs = nativeFrameAtMs   // capage badge follows the real source
                            if (state.value == State.AWAITING_FRAMES) state.value = State.CONNECTED
                            healerEvery = HEALER_TICKS
                            cleanHealers = 0
                        } else if (!capped) {
                            // idle desktop: stretch the healer towards ~4x while
                            // sweeps keep coming back byte-clean.
                            cleanHealers++
                            if (cleanHealers >= 2)
                                healerEvery = minOf(healerEvery * 2, HEALER_TICKS * 4)
                        }
                        if (refreshes % 25 == 0) {
                            noteInfo("native capture: ${nativeGrabMs}ms/refresh, band ${bandRows}r, ~${nativeKbPerS}KB/s, frames=$nativeFrames, diff-skips=$diffSkips, healer=${healerEvery}t")
                            // v0.2.80: remember the pipe so Settings can predict
                            // fps per quality option, and the next start can be
                            // sized from evidence instead of a default.
                            runCatching {
                                androidx.preference.PreferenceManager
                                    .getDefaultSharedPreferences(ctx).edit()
                                    .putString("capture_kbps", nativeKbPerS.toString())
                                    .putString("capture_geom", "${w}x${h}")
                                    .apply()
                            }
                        }
                        // v0.2.79: was `333L` (3 fps) hardcoded, ten times below
                        // the socket path's own ceiling. The grab itself is the
                        // real pacer - when it exceeds the budget we only yield
                        // 30 ms so the UI can breathe - so this raises the
                        // ceiling without ever demanding more than the pipe
                        // measured it can carry.
                        val minFrameMs = if (foreground && screenOn)
                            1_000L / MAX_FPS_NATIVE_FOREGROUND else 1_000L / MAX_FPS_IDLE
                        if (nativeGrabMs < minFrameMs) kotlinx.coroutines.delay(minFrameMs - nativeGrabMs)
                        else kotlinx.coroutines.delay(30L)   // let the UI breathe under proot load
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (nativeFrames > 0 || captureMode == "native") {
                        noteInfo("native capture down (${e.message?.take(120) ?: e.javaClass.simpleName}) — reconnecting in ${backoff}ms")
                    }
                    captureMode = if (nativeFrames > 0) "native-retry" else captureMode
                } finally {
                    runCatching { c?.close() }
                    closeFbMap()   // v0.2.106: remapped on the next connection
                    if (nativeClient === c) {
                        nativeClient = null
                        // v0.2.92: the connection that carried capture-plane
                        // input is gone - demote the transport honestly.
                        if (capInputOk) {
                            capInputOk = false
                            inputTransport =
                                if (java.io.File(com.emux.core.FsLayout.linuxRoot(ctx), "tmp/emux_agent_launched").isFile) "agent" else "down"
                            noteInfo("capture-plane input went down with the capture connection - input now $inputTransport")
                        }
                    }
                }
                if (!isActive || state.value == State.IDLE || state.value == State.ERROR) break
                kotlinx.coroutines.delay(backoff)
                backoff = (backoff * 2).coerceAtMost(8_000)
            }
        }
    }

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
    /**
     * v0.2.79 CAPTURE QUALITY: the LONG side of the X screen the user asked for.
     *
     * The X screen size IS the capture size. GetImage returns the server's own
     * pixels, so there is no way to send fewer bytes per frame without a smaller
     * screen - and the frame cost is what the field feels: time per refresh is
     * ~(W*H*2) / measured pipe KB/s. On the v0.2.77 device that reported "LIVE
     * 0.5 fps", ~1.5 MB per refresh over a ~900 KB/s proot-translated pipe is
     * ~2 s, and no amount of host-side cleverness shortens a reply the server
     * has not finished writing. Halving the long side quarters the bytes.
     *
     * Default stays 1280 (nothing gets softer unless you ask); the choice exists
     * because the trade is the user's, not ours: fewer pixels = bigger, softer
     * text and a proportionally higher frame rate.
     */
    private fun captureQualityPref(): String = runCatching {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString("capture_quality", DEFAULT_CAPTURE_LONG_SIDE.toString())
    }.getOrNull() ?: DEFAULT_CAPTURE_LONG_SIDE.toString()

    /** v0.2.108: the device's own long side in pixels (what "native" means). */
    private fun deviceLongSide(): Int = runCatching {
        val dm = ctx.resources.displayMetrics
        maxOf(dm.widthPixels, dm.heightPixels)
    }.getOrDefault(DEFAULT_CAPTURE_LONG_SIDE).coerceIn(480, 3840)

    /**
     * v0.2.108 HIGH RESOLUTION: the 1280 ceiling existed because every pixel
     * crossed the proot socket. Damage bands now come from the mapped -fbdir
     * framebuffer, so the screen can match the panel: "native" = device
     * pixels at 1x UI, "native2x" = device pixels with the UI scaled 2x
     * (same layout size as 1280, every edge twice as sharp).
     */
    private fun captureLongSide(): Int = when (val q = captureQualityPref()) {
        "native", "native2x" -> deviceLongSide()
        else -> (q.toIntOrNull() ?: DEFAULT_CAPTURE_LONG_SIDE).coerceIn(480, 3840)
    }

    /** v0.2.109: X screen depth - 24 (true color, default) or 16. */
    private fun colorDepth(): Int = runCatching {
        androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
            .getString("color_depth", "24")
    }.getOrNull()?.toIntOrNull()?.takeIf { it == 16 || it == 24 } ?: 24

    /** v0.2.108: guest UI scale factor (GDK_SCALE / Xft.dpi / MATE scaling). */
    private fun uiScale(): Int = if (captureQualityPref() == "native2x") 2 else 1

    /** The long side the RUNNING session actually started at. */
    @Volatile private var sessionLongSide = 0

    /** X screen size of the live session - what every frame costs. */
    val captureGeometry: String get() =
        if (sessionW > 0 && sessionH > 0) "${sessionW}x${sessionH}" else "?"

    /** v0.2.80: where the in-flight sweep is, for the LIVE badge. */
    val nativeSweepProgress: String get() =
        if (sessionH > 0) "$nativeBandY/$sessionH" else "?"

    /** True when the user changed capture quality and the session predates it. */
    val captureQualityPending: Boolean get() =
        sessionLongSide > 0 && captureLongSide() != sessionLongSide

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
            val longSide = captureLongSide()
            // The short-side floor scales WITH the choice (360 at 1280, the
            // v0.2.66 value, so today's behaviour is bit-identical). A fixed 360
            // floor at a 540 long side would force a 2:3 screen onto a 9:19.5
            // pane and letterbox the very session the user made smaller to speed
            // up.
            val shortSide = ((if (a >= 1f) longSide / a else longSide * a)
                .coerceIn(longSide * 360f / 1280f, longSide.toFloat()).toInt() / 2 * 2)
            val px = longSide * shortSide
            // v0.2.74: depth 16, not 24. The field pipe (proot-translated
            // server writes) moves ~bytes, not pixels: every capture payload
            // is 2 B/px instead of 4 — half the wire, half the timeout risk —
            // and ZPixmap depth-16 IS RGB565, the format the whole frame path
            // already speaks. Guest scripts untouched (RES passes through).
            // v0.2.108: budget raised from 2x 720p to 4K-class - the mapped
            // framebuffer path no longer pays per pixel on the socket.
            if (px <= 3840 * 2400) return if (a >= 1f) "${longSide}x${shortSide}x${colorDepth()}" else "${shortSide}x${longSide}x${colorDepth()}"
        }
        val cfg = ctx.resources.configuration
        // At the 1280 default every number below is bit-identical to the
        // hardcoded values it replaces (720, 480); they scale with the choice so
        // a smaller screen keeps the PANE's aspect instead of being coerced into
        // a squarer one - the v0.2.66 letterbox bug, one level down.
        val longSide = captureLongSide()
        val phoneShort = (longSide * 720 / 1280) / 2 * 2
        if (cfg.smallestScreenWidthDp in 1 until 600) return "${longSide}x${phoneShort}x${colorDepth()}"
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels; val h = dm.heightPixels
        if (w <= 0 || h <= 0) return "${phoneShort}x${longSide}x${colorDepth()}"
        val shortSide = ((longSide * min(w, h) / max(w, h))
            .coerceIn(longSide * 480 / 1280, longSide) / 2 * 2)
        return if (w >= h) "${longSide}x${shortSide}x${colorDepth()}" else "${shortSide}x${longSide}x${colorDepth()}"
    }

    // ------------------------------------------------------------------ start
    // v0.2.80: verified-restart bookkeeping (see the GEOMETRY MISMATCH check in
    // the native loop). Capped for the whole process lifetime on purpose: a
    // server that keeps coming up at the wrong size must not restart-loop.
    @Volatile private var geometryFixes = 0
    @Volatile private var lastWm = "openbox"
    /** v0.2.108: EMUX_DESKTOP_ID of the live session ("" = none / stopping). */
    @Volatile private var desktopId = ""

    /**
     * v0.2.101: the desktop the user gets is the desktop they installed.
     * openbox was the hardcoded default; installing a full session into the
     * rootfs (field: 'sudo apt install ubuntu-desktop') changed nothing. The
     * host picks the WM by what exists in the ACTIVE rootfs: GNOME
     * (gnome-session) wins, then Xfce (startxfce4), else the seeded openbox
     * desktop. The guest launcher (emux-x11, ENV_VERSION 82 re-seed) owns the
     * details: a session bus via dbus-launch (no systemd under proot),
     * furniture skip (the session brings its own panel/icons/desktop), an
     * instant-death fallback to openbox, and the button ladder never replaces
     * a session. Honest expectation: GNOME Shell software-renders under
     * proot+Xvfb and may be unusably slow or die outright (then openbox
     * returns automatically); Xfce is the realistic full-session choice.
     */
    private fun defaultWmCommand(): String {
        val rootfs = com.emux.core.FsLayout.linuxRoot(ctx)
        // v0.2.106: GNOME moved LAST. GNOME Shell needs real GL and dies
        // instantly under proot+Xvfb (field vc201/vc202), and a leftover
        // gnome-session from an earlier ubuntu-desktop attempt used to win
        // over a working MATE/Xfce - the user then got the openbox fallback.
        return when {
            java.io.File(rootfs, "usr/bin/mate-session").isFile -> "mate-session"
            java.io.File(rootfs, "usr/bin/startxfce4").isFile -> "startxfce4"
            java.io.File(rootfs, "usr/bin/startlxqt").isFile -> "startlxqt"
            java.io.File(rootfs, "usr/bin/lxsession").isFile -> "lxsession"
            java.io.File(rootfs, "usr/bin/gnome-session").isFile -> "gnome-session"
            else -> "openbox"
        }
    }

    fun startDesktop(wmCommand: String = defaultWmCommand()) {
        // v0.2.106: "auto" / blank = the installed desktop (Settings default).
        val wm = if (wmCommand.isBlank() || wmCommand == "auto") defaultWmCommand() else wmCommand
        lastWm = wm
        // v0.1.100: AWAITING_FRAMES is also an in-progress state —
        // a second startDesktop() here would launch a duplicate X/capture
        // pipeline on top of the one already waiting for frames.
        if (state.value == State.STARTING_X || state.value == State.AWAITING_FRAMES || state.value == State.CONNECTED) return
        // v0.2.108: no session is live until the new launch stamps its id, so
        // the start-time sweep removes EVERY earlier desktop's processes.
        desktopId = ""
        // v0.1.17 BUGFIX (desktop showed nothing, ever): a previous failed attempt
        // left the state at ERROR and this method then refused to retry — the
        // desktop was permanently dead until app restart. Also stop() could leave
        // half-torn-down sessions behind. Reset cleanly before every start.
        if (state.value == State.ERROR) resetForRestart()
        // v0.2.71: every session starts with clean native-capture state —
        // a stale job from a stopped session must never be mistaken for a
        // running one by the gate's `nativeJob?.isActive` check.
        runCatching { nativeJob?.cancel() }; nativeJob = null
        runCatching { nativeClient?.close() }; nativeClient = null
        capInputOk = false   // v0.2.92: capture-plane input dies with the session
        nativeFrames = 0L; nativeFrameAtMs = 0L; captureMode = "-"
        nativeGrabMs = -1L; nativeErr = ""; lastAnyFrameAtMs = 0L
        nativeKbPerS = -1L; nativeBandRows = 0
        nativeDamageOn = false
        // v0.2.84: proof clocks + the band-diff mirror die with the session.
        nativeProofAtMs = 0L; lastSourceProofAtMs = 0L; diffSkips = 0
        invalidatePrevRaw()
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
                    java.io.File(emuxBin, "emux-fbmail").setExecutable(true, false)
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
                // v0.2.78 STRAY-XVFB FIX (half 1 of 2 - the other half is
                // emux-x11's retire_own_xvfb): pick the display by CONNECT, not
                // by BIND. The bind test has two failure modes, and each one
                // sends the session somewhere it should not go:
                //   * a bind FAILURE is not evidence of occupation. A wildcard
                //     bind over a loopback-specific listener succeeds (the
                //     v0.2.17 lesson, finally applied here too), and an
                //     SELinux denial on xserver_port would make EVERY candidate
                //     look taken - firstOrNull then returns null and the `?: 0`
                //     silently puts the session on :0;
                //   * a stale X$n socket FILE whose server is long dead looks
                //     occupied forever, so the session walks to :1 while the
                //     guest ladder starts yet another server on the way. That
                //     combination is the reported "stray Xvfb on :0 before
                //     settling on :1".
                // Honest test: nothing ANSWERS on tcp 6000+n, and nothing
                // ANSWERS on the unix socket. A file with no listener behind it
                // is debris (the launcher sweeps it), not an occupant.
                displayNum = (0..3).firstOrNull { n ->
                    !portAnswering(6000 + n) && !unixAnswering(rootfs, n)
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
                // v0.2.70 FIX (input-dead audit, round 4): THE STREAM WAS THE
                // DESIGNATED LMK SACRIFICE. This ladder set emux-capture/xwd to
                // oom_score_adj 950 (max killable) "so the desktop stack is the
                // preferred sacrifice" while the terminal protection (-300)
                // needs root and is DENIED on every unrooted device - i.e. on
                // exactly the devices in the field the ladder bought NOTHING
                // and guaranteed that the first memory-pressure event kills
                // the capture helper. Field vc169 shows the cycle: capture
                // dies minutes (or seconds) into every session, capage
                // climbs, respawns repeat, the pane alternates LIVE/STALE and
                // the user experiences "input dead" behind a photograph.
                // New policy: (1) capture/xwd are NEVER bias-adjusted; (2) the
                // positive ladder is applied ONLY when the terminal protection
                // actually succeeded (root); without root we touch nothing and
                // say so, instead of volunteering the eyes of the desktop.
                var lmkRaised = 0; var lmkProtected = 0; var lmkDenied = 0
                runCatching {
                    val myUid = android.os.Process.myUid().toString()
                    val desktopAdj = mutableListOf<Pair<java.io.File, String>>()
                    val terminalPids = mutableListOf<java.io.File>()
                    java.io.File("/proc").listFiles()?.forEach { d ->
                        val cmd = runCatching {
                            java.io.File(d, "cmdline").readBytes().decodeToString().replace('\u0000', ' ')
                        }.getOrDefault("")
                        if (cmd.isBlank()) return@forEach
                        val uidLine = runCatching {
                            java.io.File(d, "status").readLines().firstOrNull { it.startsWith("Uid:") } ?: ""
                        }.getOrDefault("")
                        if (uidLine.split(Regex("\\s+")).getOrNull(1) != myUid) return@forEach
                        // v0.2.70: emux-capture / xwd deliberately absent - the
                        // stream is never the sacrifice (killing it frees
                        // little and reads to the user as "desktop dead").
                        val adj = when {
                            cmd.contains("emux-capture") || cmd.contains("/xwd") -> return@forEach
                            cmd.contains("emux-x11") -> "900"
                            cmd.contains("Xvfb") || cmd.contains("Xorg") -> "850"
                            cmd.contains("openbox") || cmd.contains("tint2") ||
                            cmd.contains("pcmanfm") || cmd.contains("picom") ||
                            cmd.contains("xsetroot") || cmd.contains("feh") -> "800"
                            cmd.contains("proot") &&
                            (cmd.contains("bash") || cmd.contains("/bin/sh") || cmd.contains("script")) -> "TERM"
                            else -> return@forEach
                        }
                        if (adj == "TERM") terminalPids.add(d) else desktopAdj.add(d to adj)
                    }
                    // Phase 1: try to protect the terminal; learn if we have root.
                    terminalPids.forEach { d ->
                        val ok = runCatching {
                            val f = java.io.File(d, "oom_score_adj")
                            f.writeText("-300")
                            f.readText().trim() == "-300"   // verify: EPERM is silent
                        }.getOrDefault(false)
                        if (ok) lmkProtected++ else lmkDenied++
                    }
                    // Phase 2: positive bias only when protection worked (root).
                    if (lmkDenied == 0) desktopAdj.forEach { (d, adj) ->
                        val ok = runCatching {
                            val f = java.io.File(d, "oom_score_adj")
                            f.writeText(adj)
                            f.readText().trim() == adj
                        }.getOrDefault(false)
                        if (ok) lmkRaised++
                    }
                }
                if (lmkDenied > 0) noteInfo(
                    "LMK: no root - terminal protection DENIED on $lmkDenied procs, so the " +
                        "desktop bias ladder was SKIPPED entirely (v0.2.70: raising desktop " +
                        "adjs without root only guaranteed the capture helper was killed " +
                        "first; field vc169 died that way every session)")
                else if (lmkRaised + lmkProtected > 0) noteInfo(
                    "LMK bias applied: $lmkRaised desktop procs raised, $lmkProtected terminal procs protected")
                desktopStartUpTicks = runCatching {
                    (java.io.File("/proc/uptime").readText().trim().split(" ")[0].toDouble() * 100).toLong() - 50
                }.getOrDefault(Long.MAX_VALUE)
                runCatching {
                    listOf("tmp/emux_x_up", "tmp/emux_display_num").forEach {
                        java.io.File(rootfs, it).delete()
                    }
                    // v0.2.84: a stale -fbdir screen file would paint the
                    // PREVIOUS session's photograph before the new Xvfb starts
                    // (mmap writes never touch mtime, so the reader cannot
                    // date it - delete it instead; emux-x11 recreates it).
                    java.io.File(rootfs, "tmp/emux_fbdir/Xvfb_screen0").delete()
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
                sessionLongSide = maxOf(
                    desktopGeometry.substringBefore('x').toIntOrNull() ?: 0,
                    desktopGeometry.substringAfter('x').substringBefore('x').toIntOrNull() ?: 0)
                sessionW = desktopGeometry.substringBefore('x').toIntOrNull() ?: 0
                sessionH = desktopGeometry.substringAfter('x').substringBefore('x').toIntOrNull() ?: 0
                noteInfo("desktop geometry: $desktopGeometry")
                // v0.2.108: every process of this desktop inherits
                // EMUX_DESKTOP_ID (killDesktopGuests finds the whole tree by it,
                // daemons that double-forked away from the launcher included)
                // and EMUX_SCALE (HiDPI: GTK/Qt/Xft scale factor for the guest).
                desktopId = "d" + System.currentTimeMillis()
                val cmd = "EMUX_DESKTOP_ID=$desktopId EMUX_SCALE=${uiScale()} emux-x11 $wm $desktopGeometry $displayNum"
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
                            // v0.2.77 FIELD FIX (the pane wall of post-mortem):
                            // the mailbox round proved frames can be flowing
                            // (src=mail painting 1280x694) while the LAUNCHER
                            // exits non-zero a second later (warm-start detach
                            // path, or a detached daemon's rc leaking out).
                            // The old fall-through treated ANY post-frame
                            // launcher death as terminal: setError plus the
                            // full diag bundle flooded the pane over a LIVING
                            // desktop ("starts briefly then gives this
                            // output"). Judge by the SERVER, not the rc:
                            if (frameId > 0) {
                                val srvAlive = runCatching {
                                    java.net.Socket().also { sk ->
                                        sk.connect(java.net.InetSocketAddress("127.0.0.1", 6000 + displayNum), 1_500)
                                        sk.close()
                                    }
                                    true
                                }.getOrDefault(false) ||
                                    java.io.File(rootfs, "tmp/.X11-unix/X$displayNum").exists()
                                if (srvAlive) {
                                    noteInfo(
                                        "v0.2.77: x11 launcher exited rc=${xSession?.exitCode} AFTER frames with the " +
                                            "server still alive - daemons detached, session continues")
                                    return@collect
                                }
                                if (xRespawns < 3 && state.value != State.IDLE) {
                                    xRespawns++
                                    noteInfo(
                                        "v0.2.77: x11 server died AFTER frame $frameId (rc=${xSession?.exitCode}) - " +
                                            "respawning the whole X session (#$xRespawns)")
                                    kotlinx.coroutines.delay(2_000)
                                    runCatching { killDesktopGuests() }
                                    xSession = TerminalSession(9001 + xRespawns, ctx, root, cmd).also { it.start() }
                                    pumpDiag("x11", xSession!!)
                                    return@collect
                                }
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
                // v0.2.76: the mailbox reader runs from session start so ANY
                // writer (mail-mode capture, fbmail, even a plain legacy loop
                // refreshing emux_frame.rgb between wedges) paints immediately.
                startMailReader()
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
                            clearXtestQueue()   // v0.2.83: queued writes belong to the old display
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
                        // v0.2.71: when NATIVE capture is the frame source the
                        // guest publication file is stale BY DESIGN (nothing
                        // rewrites it) — adopting it would climb capage and
                        // trip the wedge guard against a perfectly healthy
                        // stream.
                        if (!nativeFresh) {
                            capturePubRaw()?.let { (n, ts) ->
                                captureDisplay = n
                                capturePubAtMs = ts * 1000
                            }
                        }
                        // v0.2.69 WEDGE GUARD: a capture helper can be ALIVE
                        // but stuck (xwd hung inside grab_frame); its session
                        // never reports DEAD, so the respawn watcher never
                        // fires and the pane stays a photograph with a frozen
                        // badge. emux-capture republishes /tmp/emux_cap_display
                        // at the TOP of every loop, so >20 s without a
                        // publication means the loop is not looping: stop the
                        // session (the respawn watcher recreates it) and name
                        // the likely killer from the capture's own error log.
                        val pubAge = capturePubAgeSec
                        if (!nativeFresh && pubAge in 21..3_600 && frameId > 0 &&
                            state.value == State.CONNECTED) {
                            errTail = runCatching {
                                java.io.File(rootfs, "tmp/emux_cap.err").readText()
                                    .lineSequence().filter { it.isNotBlank() }.toList().takeLast(3)
                                    .joinToString(" | ").take(200)
                            }.getOrDefault("<no cap.err>")
                            lastCapErr = errTail
                            noteInfo(
                                "capture WEDGED or DEAD: no publication for ${pubAge}s - " +
                                    "killing and respawning; cap.err tail: $errTail")
                            runCatching { captureSession?.stop() }
                            // v0.2.71: if the NATIVE loop is the one that
                            // stalled (job died or grab kept failing), the
                            // legacy respawn watcher has nothing to respawn —
                            // restart native directly.
                            if (nativeFrames > 0 && nativeJob?.isActive != true) {
                                noteInfo("wedge guard: restarting native XGetImage capture loop")
                                startNativeCapture { frameCallback?.invoke() }
                            }
                        }
                        val cap = freshCaptureDisplay()
                        if (cap != null) captureDisplay = cap
                        // v0.2.91: the /tmp/emux_cap_display publication can be
                        // HOURS stale (nothing rewrites it once the legacy
                        // capture helper is retired), which blinded the
                        // display-mismatch check below for whole sessions
                        // (field capage=27263s). The guest marker
                        // /tmp/emux_display_num is what the CURRENT launcher
                        // publishes - re-aim from it too. This duplicates the
                        // 3 s rotation watcher ON PURPOSE: that watcher
                        // demonstrably missed rotations in the v0.2.90 field
                        // round (ctrl stayed :0 for minutes while the marker
                        // said :1), while this 5 s loop demonstrably runs -
                        // it keeps the badge capage ticking.
                        val mk = runCatching {
                            java.io.File(com.emux.core.FsLayout.linuxRoot(ctx), "tmp/emux_display_num")
                                .readText().trim().toIntOrNull()
                        }.getOrNull()
                        if (mk != null && mk != displayNum) {
                            noteInfo("input watchdog: guest marker says :$mk, control plane on :$displayNum - re-aiming")
                            displayNum = mk
                            recycleControlPlane("guest published display :$mk but the control plane is on :$displayNum")
                            continue
                        }
                        val c = xtest()
                        if (c == null) {
                            // v0.2.92 CAPTURE-PLANE INPUT: the control plane can
                            // stay down for many minutes (proof=pending, the
                            // v0.2.91 retry loop) while frames flow and the user
                            // keeps tapping. The capture client IS a proven,
                            // identity-guaranteed connection to the server the
                            // user watches - prove XTEST over IT and inject
                            // through it, instead of stranding input on the
                            // per-event xdotool agent with no button proof.
                            val ncap = nativeClient?.takeIf { it.connected && it.xtestOpcode > 127 }
                            // v0.2.93: exponential backoff after failures (15 s x
                            // 2^fails, capped at 2 min) - a failing proof serializes
                            // against the capture loop's grabs and must not keep
                            // stealing its round-trips every 15 s.
                            val capBackoffMs = (15_000L shl capProofFails.coerceAtMost(3)).coerceAtMost(120_000L)
                            if (ncap != null && !capInputOk && frameId > 0 &&
                                System.currentTimeMillis() - lastCapProofMs > capBackoffMs) {
                                lastCapProofMs = System.currentTimeMillis()
                                Thread({ runCatching { runCaptureInputProof(ncap) } }, "emux-cap-input-proof")
                                    .apply { isDaemon = true }.start()
                            }
                            continue
                        }
                        // v0.2.90 SERVER-IDENTITY GUARD (the stale-input-server
                        // fix): display NUMBERS cannot distinguish two servers
                        // claiming the same number. The v0.2.89 field state was a
                        // leaked :0 zombie answering the input plane (proof ok,
                        // fail=0, inj climbing) while the capture streamed the
                        // real desktop and xdotool showed the real server pointer
                        // NEVER MOVED - every tap went into the zombie. The setup
                        // reply resource-id-base is unique per server instance:
                        // when the input plane base differs from the live capture
                        // plane base, the input plane is on a wrong server -
                        // recycle it and re-aim at the one the user sees.
                        val ncap = nativeClient
                        if (ncap != null && ncap.connected && !onCapturedServer(c)) {
                            // two strikes: one failed lookup under load is not proof
                            identityMiss++
                            if (identityMiss >= 2) {
                                identityMiss = 0
                                displayNum = ncap.displayNum
                                captureDisplay = ncap.displayNum
                                recycleControlPlane(
                                    "input plane is on a different X server than the streamed one " +
                                        "(server token missing on :" + ncap.displayNum + ") - reconnecting")
                                continue
                            }
                        } else identityMiss = 0
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

                // v0.2.72 SILENCE WATCHDOG: the v0.2.71 field round proved a
                // capture path can be "connected" (src=native on the badge)
                // while delivering ZERO frames, and every existing guard
                // (respawn watcher, wedge guard) keys off the LEGACY session
                // or its publication file. This watchdog keys off the only
                // truth that matters — pixels arriving — and brings the guest
                // pipeline up if nothing has delivered for 25 s, whatever the
                // reason (native wire rejection, proot wedge, server death).
                scope.launch {
                    kotlinx.coroutines.delay(30_000)
                    while (isActive && state.value != State.IDLE && state.value != State.ERROR) {
                        kotlinx.coroutines.delay(5_000)
                        // v0.2.75: the mailbox loop's guest side is a normal
                        // session on a SIGSEGV-prone ROM — respawn it here.
                        fbMailSession?.let { fb ->
                            if (fb.state.value == TerminalSession.State.DEAD) {
                                fbMailSession = null
                                // v0.2.83: a mailbox that dies WHILE NATIVE IS
                                // FRESH was retired (or was redundant) - do not
                                // respawn guest load against a healthy stream.
                                if (nativeFresh) {
                                    noteInfo("fbmail died but native capture is fresh ($nativeFrames frames) - mailbox stays retired")
                                } else {
                                    noteInfo("fbmail session died (rc=${fb.exitCode}) - respawning")
                                    ensureFbMail("respawn after guest death")
                                }
                            }
                        }
                        // v0.2.84: silence = no source PROVED itself (a frame
                        // that diffed clean is a healthy idle pipeline, not
                        // silence - counting paints here would fire the
                        // watchdog on a perfectly fine still desktop).
                        val silentMs = if (lastSourceProofAtMs == 0L) Long.MAX_VALUE
                            else System.currentTimeMillis() - lastSourceProofAtMs
                        if (silentMs <= 25_000) continue
                        val why = nativeErr.ifBlank { if (nativeFrames > 0) "native silent" else "native no-frames" }
                        if (captureSession == null) {
                            ensureMailCapture("watchdog: ${silentMs / 1000}s without any frame ($why)")
                            kotlinx.coroutines.delay(20_000)
                            continue
                        }
                        if (fbMailSession == null) {
                            ensureFbMail("watchdog: mailboxes silent while capture session lives ($why)")
                            kotlinx.coroutines.delay(20_000)
                            continue
                        }
                        kotlinx.coroutines.delay(15_000)
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
                        // v0.2.71: native capture needs X ITSELF and nothing
                        // else — no toolchain, no guest processes, no FIFO.
                        // Start it the moment the server answers; on this
                        // rootfs (package manager cannot run, musl loader
                        // FAILED) it outranks the toolchain gate entirely.
                        if (up && nativeJob?.isActive != true) {
                            startNativeCapture { frameCallback?.invoke() }
                        }
                        if (!(up && tools)) {
                            kotlinx.coroutines.delay(1_000); w++
                            // v0.1.99 PERF: 1 s poll (was 5); note cadence kept
                            // at ~15 s.
                            if (w % 15 == 0) noteInfo(
                                "capture: waiting - X-up marker=$up toolchain=$tools ($w/180)")
                        }
                    }
                    if (!(up && tools) || state.value == State.IDLE) {
                        // v0.2.71: the toolchain gate is no longer fatal when
                        // native capture is already delivering — the guest
                        // pipeline (xwd/netpbm/nc) is OPTIONAL now.
                        if (up && nativeFrames > 0 && state.value != State.IDLE) {
                            noteInfo(
                                "capture: toolchain never arrived but NATIVE XGetImage is " +
                                    "delivering ($nativeFrames frames) - continuing without emux-capture")
                            return@launch
                        }
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
                    // v0.2.71: native capture won the race (it starts as soon
                    // as X is up, minutes before the REST-pass toolchain
                    // lands). The guest pipeline stays PARKED — zero per-frame
                    // processes on the device. It only starts if native
                    // produced nothing during the whole gate window.
                    if (nativeFrames > 0) {
                        noteInfo(
                            "capture: NATIVE XGetImage is the frame source " +
                                "($nativeFrames frames during gate) - guest emux-capture stays parked")
                        return@launch
                    }
                    // v0.2.75: the fallback ladder is native -> fbmail ->
                    // (watchdog) legacy. The guest socket pipeline is the
                    // last resort, not the second: it wedged every round.
                    ensureMailCapture("gate: native produced no frames in the gate window")
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
                            // v0.2.83: while NATIVE capture is fresh, a dead
                            // guest capture helper is either retired BY DESIGN
                            // (retireMailPipelines) or redundant - respawning it
                            // would put per-frame proot load back beside the
                            // healthy stream. Drop the handle; the silence
                            // watchdog restarts the ladder if native goes quiet.
                            if (nativeFresh) {
                                if (captureSession === cur) captureSession = null
                                pumpedCaptureSession = null
                                com.emux.EmuXLog.i("desktop: capture session died but native is fresh ($nativeFrames frames) - not respawning")
                                delay(1_000)
                                continue
                            }
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
                                capErr = runCatching {
                                    java.io.File(rootfs, "tmp/emux_cap.err").readText()
                                        .lineSequence().filter { it.isNotBlank() }.toList().takeLast(3)
                                        .joinToString(" | ").take(200)
                                }.getOrDefault("<no cap.err>")
                                lastCapErr = capErr
                                noteInfo("capture died ${if (pre) "pre-frame" else "post-frame #$frameId"} (rc=${cur.exitCode}) - respawning #$captureRespawns; cap.err: $capErr")
                                delay(2_000)
                                if (state.value == State.IDLE || state.value == State.ERROR) break
                                captureSession = TerminalSession(
                                    9002 + captureRespawns, ctx, root,
                                    "emux-capture 127.0.0.1:$FRAME_PORT $displayNum" +
                                        if (capMailMode) " mail" else "",
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
        invalidatePrevRaw()   // v0.2.84: bitmap gone, mirror meaningless
        pumpedCaptureSession = null
        captureRespawns = 0
        runCatching { frameReader?.cancel() }; frameReader = null
        runCatching { activeSock?.close() }; activeSock = null   // v0.1.54
        runCatching { listener?.close() }; listener = null
        runCatching { x11?.close() }; x11 = null
        clearXtestQueue()   // v0.2.83
        scope.launch {
            runCatching { xSession?.stop() }; xSession = null
            runCatching { captureSession?.stop() }; captureSession = null
            runCatching { fbJob?.cancel() }; fbJob = null
            runCatching { mailJob?.cancel() }; mailJob = null
            capMailMode = false
            runCatching { fbMailSession?.stop() }; fbMailSession = null; fbFrames = 0L
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
        // v0.2.108 THE FORCE-STOP EQUIVALENT. Field: a desktop that went stale
        // stayed stale across "Restart desktop" and only Force stop cured it.
        // The pattern sweep below knows Xvfb/openbox/tint2 by name, but a full
        // session (mate-session, mate-panel, settings daemon, its own
        // dbus-daemon, Firefox...) survived every restart - and double-forked
        // daemons are no longer children of anything we track. Every desktop
        // process carries EMUX_DESKTOP_ID in its environment (see the launch
        // command): kill every same-uid process whose marker is not the live
        // session's - all of them when stopping. Terminal-tab shells never
        // carry the marker, so they survive exactly as with force stop's
        // opposite: only the desktop goes.
        runCatching {
            val me = android.os.Process.myPid()
            val live = desktopId
            var killed = 0
            java.io.File("/proc").listFiles()?.forEach { d ->
                val pid = d.name.toIntOrNull() ?: return@forEach
                if (pid == me) return@forEach
                val env = runCatching { java.io.File(d, "environ").readBytes() }.getOrNull() ?: return@forEach
                if (env.isEmpty()) return@forEach
                val s = String(env, Charsets.ISO_8859_1)
                val i = s.indexOf("EMUX_DESKTOP_ID=")
                if (i < 0 || (i > 0 && s[i - 1] != '\u0000')) return@forEach
                val end = s.indexOf('\u0000', i).let { if (it < 0) s.length else it }
                val id = s.substring(i + "EMUX_DESKTOP_ID=".length, end)
                if (live.isNotEmpty() && id == live) return@forEach
                android.os.Process.killProcess(pid)
                killed++
            }
            if (killed > 0) com.emux.EmuXLog.i("desktop: killed $killed process(es) of previous desktop session(s) by EMUX_DESKTOP_ID")
        }
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
            val pat = Regex("Xvfb|Xorg|emux-capture|/xwd|emux-x11|openbox|tint2|pcmanfm|xterm|xclock|xeyes|" +
                "mate-session|mate-panel|mate-settings-daemon|marco|caja|wnck-applet|notification-area-applet|jgmenu")
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
                proot.absolutePath, "--link2symlink", "-0", "-r", rootfs.absolutePath,
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

    /** v0.2.78: does a listener answer on the guest's X$n unix socket? A socket
     *  FILE with nothing behind it is debris, not an occupant - and under proot
     *  the unix transport is the one that reliably works, so it is the honest
     *  half of the display pick. */
    private fun unixAnswering(rootfs: java.io.File, n: Int): Boolean = runCatching {
        val s = android.net.LocalSocket()
        s.connect(android.net.LocalSocketAddress(
            java.io.File(rootfs, "tmp/.X11-unix/X$n").absolutePath,
            android.net.LocalSocketAddress.Namespace.FILESYSTEM))
        s.close(); true
    }.getOrDefault(false)

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
                // v0.2.90: the live CAPTURE client paints what the user
                // actually sees; when it is connected, its display and server
                // identity outrank every marker file - two servers can claim
                // the same display number (the v0.2.89 field state: a leaked
                // :0 zombie answered the control plane while the desktop
                // streamed from :1), and a number-comparing guard can never
                // see that.
                nativeClient?.takeIf { it.connected }?.let { nc ->
                    if (displayNum != nc.displayNum) {
                        noteInfo("control plane: capture client is on :" + nc.displayNum +
                            " - following it (was :" + displayNum + ")")
                        displayNum = nc.displayNum
                        captureDisplay = nc.displayNum
                    }
                }
                val c = X11Client(
                    display = displayNum,
                    unixSocketPath = java.io.File(
                        com.emux.core.FsLayout.linuxRoot(ctx),
                        "tmp/.X11-unix/X$displayNum").absolutePath)
                c.connect()
                // v0.2.90: connected to SOMETHING - prove it is the SAME
                // server the capture streams. The setup reply resource-id-
                // base is unique per server instance, so this is wire-level
                // truth no stale marker or socket collision can fake: a wrong
                // server answers every request (proof ok, fail 0) while
                // every injection lands on a desktop nobody sees. Throw so
                // the deadline loop re-aims and retries; displayNum now
                // follows the capture client, so the retry lands right.
                nativeClient?.takeIf { it.connected }?.let { nc ->
                    if (!onCapturedServer(c)) {
                        runCatching { c.close() }
                        throw java.io.IOException(
                            "control plane reached a different X server than the captured one (:" +
                                nc.displayNum + ") - server token not found")
                    }
                }
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
                // v0.2.92: the retry telemetry (rxfail=/ctrlErr=) belongs to
                // the outage that just ended - clear it on success.
                reconnectFails = 0
                lastCtrlError = null
                // v0.2.78: both are per-CONNECTION facts (XTEST GrabControl is
                // per-client too), so a fresh connection must re-measure.
                buttonDelivered = null
                btnMode = "-"
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
                    lastAnyFrameAtMs = System.currentTimeMillis()
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

    private fun ensureBitmap(w: Int, h: Int, trueColor: Boolean = pxB == 4) {
        // v0.2.109: ARGB_8888 for a true-color session - a 24-bit desktop
        // squeezed into an RGB_565 bitmap would band exactly like before.
        val cfg = if (trueColor) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565
        synchronized(frameLock) {
            if (reusable == null || reusable!!.width != w || reusable!!.height != h || reusable!!.config != cfg) {
                reusable = Bitmap.createBitmap(w, h, cfg)
                prevRaw = null   // a new bitmap is blank: the mirror must not claim otherwise
            }
            currentFrame = reusable
        }
    }

    // ------------------------------------------------------------------ v0.2.84
    // BAND-DIFF PAINTER: "only repaint what changed, on the app side".
    //
    // prevRaw mirrors EXACTLY what the bitmap shows, as raw RGB565 LE bytes -
    // the same format every source already delivers (GetImage depth-16
    // replies, the -fbdir screen file, emux_frame.rgb). Any full or partial
    // frame goes through diffUpload(): it compares 32-row bands against
    // prevRaw, and only the bands that actually differ are LUT-converted and
    // uploaded with setPixels; identical bands cost a byte-compare and
    // nothing else. A frame with zero changed bands is skipped ENTIRELY - no
    // frameId++, no invalidate, no redraw.
    //
    // This kills the last unconditional full-screen work: the ~5 s healer
    // sweep used to re-upload 1.5 MB of identical pixels every time (and the
    // mailbox paths converted their WHOLE frame per pixel with Color.rgb).
    // Invariant: every bitmap write through this class updates prevRaw, so
    // the mirror never drifts; paths that cannot maintain it (the legacy
    // socket push, the generic IntArray paintMail) call invalidatePrevRaw()
    // and the next diff repaints once, fully.
    /** Rows compared/uploaded per slice. 32 rows of 576 px = 36 KB - small
     *  enough to upload precisely, big enough that the compare stays a tight
     *  byte loop (a full 1.5 MB frame is 40 slices). */
    private val RAW_BAND = 32
    private var prevRaw: ByteArray? = null
    private var prevW = 0
    private var prevH = 0
    private var diffPx: IntArray? = null

    private fun ensurePrevRaw(w: Int, h: Int, b: Int = 2): ByteArray {
        var p = prevRaw
        if (p == null || prevW != w || prevH != h || p.size != w * h * b) {
            p = ByteArray(w * h * b)
            prevRaw = p; prevW = w; prevH = h
        }
        return p
    }

    /** Forget the mirror - next diffUpload repaints everything once. */
    private fun invalidatePrevRaw() { prevRaw = null }

    /**
     * Compare a raw RGB565 LE block ([w] x [rows] at frame position [fx],[fy],
     * pixels starting at raw[off0], row-contiguous) against the mirror and
     * upload only the changed [RAW_BAND]-row slices into the bitmap.
     *
     * @return number of slices uploaded; 0 means the block was byte-identical
     *         to what is already on screen and NOTHING was touched.
     */
    private fun diffUpload(fx: Int, fy: Int, w: Int, rows: Int,
                           raw: ByteArray, off0: Int, bmpW: Int, bmpH: Int, pb: Int = 2): Int {
        val bmp = currentFrame ?: return 0
        val prev = ensurePrevRaw(bmpW, bmpH, pb)
        var px = diffPx
        val need = bmpW * RAW_BAND
        if (px == null || px.size < need) { px = IntArray(need); diffPx = px }
        val lut = X11Client.RGB565_TO_ARGB
        var uploaded = 0
        var r = 0
        synchronized(frameLock) {
            while (r < rows) {
                val sliceRows = minOf(RAW_BAND, rows - r)
                val len = sliceRows * w * pb
                val ro = off0 + r * w * pb
                val po = (fy + r) * bmpW * pb + fx * pb
                // v0.2.106 STALE-PIXEL FIX: compare ROW BY ROW. The mirror's
                // stride is the screen width; a damage band narrower than the
                // screen has a stride of its own width. The old contiguous
                // compare walked the mirror with the band's stride, so from the
                // second row on it compared against the WRONG mirror bytes - a
                // narrow changed band (a blinking cursor, a scrolled strip,
                // a menu item highlight) over flat background could compare
                // "identical", skip the upload AND skip the mirror update, and
                // that strip then stayed stale on screen until a full repaint.
                val rowLen = w * pb
                var changed = false
                var rr0 = 0
                while (rr0 < sliceRows && !changed) {
                    val a = ro + rr0 * rowLen
                    val b = po + rr0 * bmpW * pb
                    var i = 0
                    while (i < rowLen && raw[a + i] == prev[b + i]) i++
                    if (i < rowLen) changed = true
                    rr0++
                }
                if (changed) {
                    var j = 0; var k = 0
                    if (pb == 4) {
                        // XRGB8888 LE: bytes B,G,R,X
                        while (k < len) {
                            px[j++] = -0x1000000 or
                                ((raw[ro + k + 2].toInt() and 0xFF) shl 16) or
                                ((raw[ro + k + 1].toInt() and 0xFF) shl 8) or
                                (raw[ro + k].toInt() and 0xFF)
                            k += 4
                        }
                    } else {
                        while (k < len) {
                            val v = (raw[ro + k].toInt() and 0xFF) or
                                ((raw[ro + k + 1].toInt() and 0xFF) shl 8)
                            px[j++] = lut[v]
                            k += 2
                        }
                    }
                    bmp.setPixels(px, 0, w, fx, fy + r, w, sliceRows)
                    for (rr in 0 until sliceRows) {
                        System.arraycopy(raw, ro + rr * w * pb,
                            prev, po + rr * bmpW * pb, w * pb)
                    }
                    uploaded++
                } else diffSkips++
                r += sliceRows
            }
        }
        return uploaded
    }

    /** Convert incoming RGB565 rows into the reused RGB_565 bitmap (memcpy-fast). */
    private fun writeDirtyRegion(x: Int, y: Int, w: Int, h: Int, rows: Array<ByteArray>) {
        val bmp = currentFrame ?: return
        lastSourceProofAtMs = System.currentTimeMillis()
        // v0.2.84: this region path does not maintain the byte mirror.
        invalidatePrevRaw()
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
            // v0.2.84: one LUT load per pixel instead of Color.rgb()'s
            // multiplies/divides (same table the diff painter uses).
            val lut = X11Client.RGB565_TO_ARGB
            for (j in 0 until need) {
                val v = (buf[j * 2].toInt() and 0xFF) or ((buf[j * 2 + 1].toInt() and 0xFF) shl 8)
                px[j] = lut[v]
            }
            bmp.setPixels(px, 0, w, x, y, w, h)
        }
    }

    // ------------------------------------------------------------------ v0.2.75
    // FILESYSTEM-MAILBOX CAPTURE (emux-fbmail). Four field rounds killed four
    // socket transports on this device (guest pipeline wedge, full-GetImage
    // timeout, banded-GetImage X errors) while the shared rootfs filesystem
    // never failed once — every marker file crosses it cleanly. So pixels now
    // travel by file: the guest writes xwd -root to a temp file and renames it
    // atomically; the host polls, parses the XWD header and paints. The host
    // read path never enters proot; the guest side is ONE xwd per frame over
    // the guest-local socket (the only X data path this device ever liked).
    @Volatile private var fbMailSession: TerminalSession? = null
    private var fbJob: Job? = null
    private var mailJob: Job? = null
    /** v0.2.76: the geometry Xvfb was launched with this session — the raw
     *  RGB565 mailbox has no header, so its size check IS the geometry. */
    @Volatile private var sessionW = 0
    @Volatile private var sessionH = 0
    @Volatile private var capMailMode = false
    @Volatile var fbFrames: Long = 0; private set
    private var fbPx: IntArray? = null

    /** v0.2.76: legacy helper, mailbox mode — the proven xwd+convert chain
     *  MINUS the nc push that wedged proot sockets for ten versions. */
    private fun ensureMailCapture(reason: String) {
        if (captureSession != null) return
        // v0.2.83: never start a guest pipeline BESIDE a healthy native one.
        // Both paint the same bitmap, and the guest side (xwd full-screen dump
        // per frame under proot) steals the CPU/proot budget from the very
        // grabs that make the desktop feel fast - co-running pipelines is the
        // "everything is slow" state the field reported. The silence watchdog
        // re-arms this the moment native actually goes quiet (nativeFresh false).
        if (nativeFresh) {
            noteInfo("mail capture NOT started (native XGetImage is fresh, $nativeFrames frames): $reason")
            return
        }
        // v0.2.84: with the -fbdir framebuffer file present, the mail reader
        // already paints with ZERO guest processes - spawning the xwd text
        // chain beside it would only steal CPU from the reads.
        if (fbdirAvailable()) {
            noteInfo("mail capture NOT started (Xvfb -fbdir file present - zero-process mailbox is active): $reason")
            return
        }
        capMailMode = true
        noteInfo("v0.2.76 mail capture: $reason - emux-capture in mailbox mode (file only, no nc push)")
        captureSession = TerminalSession(
            9002 + captureRespawns, ctx, root,
            "emux-capture 127.0.0.1:$FRAME_PORT $displayNum mail",
        ).also { it.start() }
    }

    private fun ensureFbMail(reason: String) {
        if (fbMailSession != null) return
        // v0.2.83: same gate as ensureMailCapture - see the comment there.
        if (nativeFresh) {
            noteInfo("fbmail NOT started (native XGetImage is fresh, $nativeFrames frames): $reason")
            return
        }
        // v0.2.84: -fbdir SUPERSEDES emux-fbmail - same XWD format, written by
        // the server itself instead of a per-frame guest xwd under proot.
        if (fbdirAvailable()) {
            noteInfo("fbmail NOT started (Xvfb -fbdir file supersedes the per-frame xwd chain): $reason")
            return
        }
        noteInfo("v0.2.75 fbmail: $reason - starting filesystem-mailbox capture (guest xwd -> file -> host read)")
        fbMailSession = TerminalSession(9003, ctx, root, "emux-fbmail $displayNum").also { it.start() }
    }

    /**
     * v0.2.83: STOP the guest mailbox/legacy pipelines while native capture is
     * delivering. The guest chains dump the FULL screen per frame (xwd under
     * proot) and repaint the same bitmap native does - so besides the CPU
     * contention, whichever writer lands last wins, which is how a healthy
     * 30 fps damage stream still looked "sloppy" on the field device: every
     * few frames a 2-second-old mailbox photograph overwrote it.
     *
     * This is level-triggered from the native loop (nativeFrames >= 8), and it
     * is safe in both directions: if native later dies, nativeFresh goes false
     * and the silence watchdog restarts the mailboxes within ~25 s.
     */
    private suspend fun retireMailPipelines() {
        var did = false
        runCatching { fbMailSession?.let { did = true; it.stop() } }
        fbMailSession = null
        runCatching { captureSession?.let { did = true; it.stop() } }
        captureSession = null
        pumpedCaptureSession = null
        if (did) noteInfo(
            "native capture is the frame source ($nativeFrames frames) - guest mailbox " +
                "pipelines RETIRED (the watchdog restarts them if native ever goes silent)")
    }

    /**
     * v0.2.76 UNIFIED MAILBOX READER. The field device proved (user's own
     * terminal, v0.2.75 round) that /tmp/emux_frame.rgb — raw RGB565, exactly
     * W*H*2 bytes — sits on the shared rootfs being refreshed by the guest
     * capture loop WHILE its nc push wedges: the file was 17 s fresh beside
     * capage=38s. So the app now reads mailboxes instead of sockets:
     *   1) emux_frame.rgb  — headerless raw RGB565 (size = geometry check,
     *      mtime-stable read guards against the in-place write tearing);
     *   2) emux_fb.xwd     — atomic-rename XWD drops from emux-fbmail.
     * Both reads happen entirely OUTSIDE proot.
     */
    private fun startMailReader() {
        mailJob?.cancel()
        mailJob = scope.launch {
            val root = com.emux.core.FsLayout.linuxRoot(ctx)
            val rgb = java.io.File(root, "tmp/emux_frame.rgb")
            val xwd = java.io.File(root, "tmp/emux_fb.xwd")
            // v0.2.84 FBDIR — THE ZERO-PROCESS FALLBACK. emux-x11 now starts
            // Xvfb with `-fbdir /tmp/emux_fbdir`, which makes the SERVER ITSELF
            // keep its framebuffer in an mmap'ed file in XWD format — the same
            // format emux_fb.xwd uses, so the parser is shared. The guest-side
            // chain the field measured as the slowest thing on the device
            // (xwd | xwdtopnm | pnmtoplainpnm: every frame becomes a ~10 MB
            // TEXT file under proot, plus a socket push or file copy) simply
            // does not exist on this path: the host reads the file straight
            // from the page cache and the band-diff painter uploads only what
            // changed. One caveat drives the design: mmap writes never touch
            // mtime, so there is no freshness signal — the reader re-reads on
            // every tick and lets the diff decide. An unchanged desktop costs
            // one page-cache read plus a memcmp and paints NOTHING.
            val fbd = java.io.File(root, "tmp/emux_fbdir/Xvfb_screen0")
            var rgbMod = -1L; var xwdMod = -1L
            var rawBuf: ByteArray? = null
            while (isActive && state.value != State.IDLE && state.value != State.ERROR) {
                // fbdir only while native is NOT proven fresh: co-reading
                // would compete with native grabs and could paint an older
                // copy over a newer damage frame.
                if (!nativeFresh && fbd.isFile) {
                    val want = fbd.length()
                    if (want in 105..64_000_000L) {
                        val wi = want.toInt()
                        var buf = rawBuf
                        if (buf == null || buf.size < wi) { buf = ByteArray(wi); rawBuf = buf }
                        val got = runCatching {
                            java.io.DataInputStream(java.io.FileInputStream(fbd)).use {
                                it.readFully(buf, 0, wi)
                            }
                            wi
                        }.getOrDefault(-1)
                        if (got == wi) {
                            lastSourceProofAtMs = System.currentTimeMillis()
                            val hd = xwdHeaderOf(buf, got)
                            if (hd != null && hd.bpp == 16 && hd.le && hd.bpl == hd.w * 2 &&
                                got >= hd.off + hd.w * hd.h * 2) {
                                // fast path: contiguous RGB565 LE, straight
                                // into the band differ. (A torn read - Xvfb
                                // redrawing mid-read - shows for one tick and
                                // the next read heals it; the old xwd chain
                                // had the same race with a 100x bigger window.)
                                paintRawFrame(hd.w, hd.h, buf, hd.off, "fbdir")
                            } else {
                                val dec = decodeXwdToFrame(buf.copyOf(got))
                                if (dec != null) paintMail(dec.first, dec.second, dec.third, "fbdir")
                            }
                        }
                    }
                }
                val rm = rgb.lastModified()
                if (rm > 0 && rm != rgbMod) {
                    rgbMod = rm
                    val want = sessionW * sessionH * 2
                    if (want > 0 && rgb.length() == want.toLong()) {
                        val before = rgb.lastModified()
                        val bytes = runCatching { rgb.readBytes() }.getOrNull()
                        if (bytes != null && bytes.size == want && rgb.lastModified() == before) {
                            // v0.2.84: headerless raw RGB565 - the per-pixel
                            // Color.rgb conversion loop is gone; the band
                            // differ converts only what changed.
                            paintRawFrame(sessionW, sessionH, bytes, 0, "mail")
                        }
                    }
                }
                val xm = xwd.lastModified()
                if (xm > 0 && xm != xwdMod && xwd.length() > 104) {
                    xwdMod = xm
                    val bytes = runCatching { xwd.readBytes() }.getOrNull()
                    if (bytes != null) {
                        val hd = xwdHeaderOf(bytes)
                        if (hd != null && hd.bpp == 16 && hd.le && hd.bpl == hd.w * 2 &&
                            bytes.size >= hd.off + hd.w * hd.h * 2) {
                            paintRawFrame(hd.w, hd.h, bytes, hd.off, "fbmail")
                        } else {
                            val dec = decodeXwdToFrame(bytes)
                            if (dec != null) paintMail(dec.first, dec.second, dec.third, "fbmail")
                        }
                    }
                }
                kotlinx.coroutines.delay(150)
            }
        }
    }

    /**
     * v0.2.84: paint one raw RGB565 LE full frame (pixels at raw[off0],
     * row-contiguous) through the band-diff painter. Shared by every mailbox
     * source: the -fbdir screen file, emux_frame.rgb and 16-bpp emux_fb.xwd
     * dumps. A frame in which nothing changed is skipped ENTIRELY: no
     * conversion, no setPixels, no frameId++, no invalidate.
     */
    private fun paintRawFrame(w: Int, h: Int, raw: ByteArray, off0: Int, mode: String) {
        lastSourceProofAtMs = System.currentTimeMillis()
        if (w <= 0 || h <= 0) return
        ensureBitmap(w, h)
        val n = diffUpload(0, 0, w, h, raw, off0, w, h)
        if (n == 0) return
        fbFrames++
        if (captureMode != mode) {
            captureMode = mode
            noteInfo(
                "v0.2.84 mailbox capture UP ($mode): ${w}x${h} band-diffed from the shared filesystem" +
                    if (mode == "fbdir") " - Xvfb -fbdir: ZERO guest processes per frame" else "")
        }
        lastAnyFrameAtMs = System.currentTimeMillis()
        capturePubAtMs = lastAnyFrameAtMs
        captureDisplay = displayNum
        frameId++
        if (state.value == State.AWAITING_FRAMES) state.value = State.CONNECTED
        frameCallback?.invoke()
    }

    private fun paintMail(w: Int, h: Int, px: IntArray, mode: String) {
        lastSourceProofAtMs = System.currentTimeMillis()
        synchronized(frameLock) {
            ensureBitmap(w, h)
            currentFrame?.setPixels(px, 0, w, 0, 0, w, h)
        }
        // v0.2.84: the generic IntArray path does not maintain the byte
        // mirror - forget it, and the next diffUpload repaints once, fully.
        invalidatePrevRaw()
        fbFrames++
        if (captureMode != mode) {
            captureMode = mode
            noteInfo("v0.2.76 mailbox capture UP ($mode): painting ${w}x${h} from the shared filesystem, no socket in the frame path")
        }
        lastAnyFrameAtMs = System.currentTimeMillis()
        capturePubAtMs = lastAnyFrameAtMs
        captureDisplay = displayNum
        frameId++
        if (state.value == State.AWAITING_FRAMES) state.value = State.CONNECTED
        frameCallback?.invoke()
    }

    /** The XWD header fields the readers need; [le] is the HEADER endianness. */
    private class XwdHdr(val le: Boolean, val w: Int, val h: Int, val off: Int,
                         val bpl: Int, val bpp: Int, val depth: Int, val byteOrder: Int)

    /**
     * Parse an XWD header — `xwd -root` dumps AND Xvfb's -fbdir screen files
     * are the same format (v0.2.84 validated byte-for-byte against a real
     * Xvfb_screen0: header_size 160, file_version 7, ZPixmap, depth/bpp 16,
     * bytes_per_line = width*2, ncolors at offset 76, pixels at
     * header_size + ncolors*12). Header endianness is DETECTED by
     * plausibility, never assumed — the same rule decodeXwdToFrame has used
     * since v0.2.75 (this xwd writes a big-endian header while its image
     * byte_order field says LSBFirst).
     */
    private fun xwdHeaderOf(b: ByteArray, size: Int = b.size): XwdHdr? {
        if (size < 104) return null
        val leO = listOf(java.nio.ByteOrder.BIG_ENDIAN, java.nio.ByteOrder.LITTLE_ENDIAN)
            .firstOrNull { o ->
                val hs = java.nio.ByteBuffer.wrap(b, 0, 4).order(o).int
                val w = java.nio.ByteBuffer.wrap(b, 16, 4).order(o).int
                val h = java.nio.ByteBuffer.wrap(b, 20, 4).order(o).int
                hs in 96..4096 && w in 1..8192 && h in 1..8192
            } ?: return null
        val bb = java.nio.ByteBuffer.wrap(b).order(leO)
        val headerSize = bb.getInt(0)
        val depth = bb.getInt(12); val w = bb.getInt(16); val h = bb.getInt(20)
        val byteOrder = bb.getInt(28); val bpp = bb.getInt(44); val bpl = bb.getInt(48)
        val ncolors = bb.getInt(76)
        if (w <= 0 || h <= 0 || w > 4096 || h > 4096 || depth == 0) return null
        if (bpp != 16 && bpp != 24 && bpp != 32) return null
        val off = headerSize + ncolors * 12
        if (off < 0 || off.toLong() + bpl.toLong() * h > size.toLong()) return null
        return XwdHdr(leO == java.nio.ByteOrder.LITTLE_ENDIAN, w, h, off, bpl, bpp, depth, byteOrder)
    }

    /** True when the -fbdir framebuffer file is present: every mailbox frame
     *  can then be had with ZERO guest processes, so the per-frame xwd chains
     *  are strictly worse and must not start (v0.2.84). */
    private fun fbdirAvailable(): Boolean = runCatching {
        val f = java.io.File(com.emux.core.FsLayout.linuxRoot(ctx), "tmp/emux_fbdir/Xvfb_screen0")
        f.isFile && f.length() > 104
    }.getOrDefault(false)

    /** Parse an XWD dump (xwd -root) into packed ARGB. Endian- and depth-
     *  agnostic (16/24/32 bpp, LSB/MSB); returns null on anything unexpected
     *  so the loop simply waits for the next mailbox drop. */
    private fun decodeXwdToFrame(b: ByteArray): Triple<Int, Int, IntArray>? {
        val hd = xwdHeaderOf(b) ?: return null
        val w = hd.w; val h = hd.h; val off = hd.off; val bpl = hd.bpl; val bpp = hd.bpp
        var px = fbPx; if (px == null || px.size < w * h) { px = IntArray(w * h); fbPx = px }
        val lsb = hd.byteOrder == 0
        val step = bpp / 8
        var y = 0
        while (y < h) {
            val row = off + y * bpl
            var x = 0
            while (x < w) {
                val o = row + x * step
                px[y * w + x] = if (bpp == 16) {
                    val v = if (lsb) (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
                            else ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)
                    android.graphics.Color.rgb(
                        ((v shr 11) and 0x1F) * 255 / 31,
                        ((v shr 5) and 0x3F) * 255 / 63,
                        (v and 0x1F) * 255 / 31)
                } else {
                    val ri = if (lsb) step - 2 else 0      // BGRx / xRGB family
                    val gi = if (lsb) step - 3 else 1
                    val bi = if (lsb) step - 4 else 2
                    val r = b[o + ri].toInt() and 0xFF
                    val g = b[o + gi].toInt() and 0xFF
                    val bl = b[o + bi].toInt() and 0xFF
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
                }
                x++
            }
            y++
        }
        return Triple(w, h, px)
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

    // ------------------------------------------------------------------
    // v0.2.78: the button leg of the input proof. `inputProof` alone could
    // only ever say whether MOTION was honoured, and a window manager that
    // grabs buttons leaves motion untouched - so ~25 releases shipped
    // `proof=ok touch=11 inj=32 fail=0` over a desktop where nothing could be
    // opened. buttonDelivered is null until measured, then true/false.
    @Volatile var buttonDelivered: Boolean? = null; private set
    /** How buttons get through: "-" (not measured yet) or "pervious" (the
     *  server's normal path - our fake input is subject to other clients'
     *  grabs, which is precisely how a window manager swallowed every click).
     *  There is no "impervious" mode: XTEST GrabControl(impervious) was
     *  MEASURED not to rescue a passive Frame-context grab (case G of
     *  tools/x11probe/button_delivery_test.sh), so the ladder never enters it.
     *  The primitive itself stays - see X11Client.xtestGrabControl. */
    @Volatile var btnMode: String = "-"
    /** The guest ladder's own report, painted on the pane. */
    val wmMode: String get() = guestTmp("emux_wm_mode")
    val btnAction: String get() = guestTmp("emux_btn_action")
    /** rc.xml grab contexts as audited by the launcher ("none" when clean). */
    val rcGrabs: String get() = guestTmp("emux_rcxml_grabs")

    private fun guestTmp(name: String): String = runCatching {
        java.io.File(com.emux.core.FsLayout.linuxRoot(ctx), "tmp/$name")
            .readText().trim().lineSequence().firstOrNull()?.take(60) ?: ""
    }.getOrDefault("")

    /** The launcher's escalation ladder reads this; it deletes it after acting. */
    private fun publishBtnVerdict(state: String, detail: String) = runCatching {
        val f = java.io.File(com.emux.core.FsLayout.linuxRoot(ctx), "tmp/emux_btn_verdict")
        f.parentFile?.mkdirs()
        f.writeText("${System.currentTimeMillis() / 1000} $state ${detail.take(100)}")
    }.isSuccess

    /** Set when the proof showed the server ignoring/rejecting FakeInput. */
    @Volatile private var xtestIgnored = false

    /** Touches that reached DesktopView.onTouchEvent (ACTION_DOWN). */
    @Volatile var touchCount: Long = 0L

    // v0.2.84 DRAG PRIORITY: the capture loop orders pending damage bands by
    // distance to this pointer while a button is down, so the region the user
    // is watching is the region that paints first (see the damage loop).
    @Volatile private var ptrX = 0
    @Volatile private var ptrY = 0
    @Volatile private var ptrDown = false
    @Volatile private var ptrAtMs = 0L
    private fun notePointer(x: Int, y: Int, down: Boolean?) {
        ptrX = x; ptrY = y; ptrAtMs = System.currentTimeMillis()
        lastUserInputMs = ptrAtMs   // v0.2.95: proof anti-race stamp (every pointer injection)
        if (down != null) ptrDown = down
    }

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
            // v0.2.92: control plane down does NOT mean input must fall back
            // to the per-event xdotool agent. When the capture client's own
            // motion proof has passed, the capture connection carries XTEST
            // injection until the control plane reconnects.
            ?: nativeClient?.takeIf { capInputOk && it.connected && it.xtestOpcode > 127 }

    // ------------------------------------------------------------------
    // v0.2.83 XTEST PUMP: input writes happen on a dedicated thread, never on
    // the UI thread.
    //
    // The field report ("during drag there is no animation - it waits for the
    // input to stop and then the window appears at the destination") is two
    // bugs stacked. The capture half was the full-sweep fallback (fixed by the
    // damage-band loop); THIS is the input half: onTouchEvent called
    // fakeMotion() directly. That is a ~36-byte socket write - normally
    // microseconds - but the control-plane socket ends at an Xvfb running as a
    // proot tracee, and when the server falls behind (exactly during a drag:
    // WM move + expose + client redraws) the send buffer fills and write(2)
    // BLOCKS. On the UI thread that stalls the whole touch pipeline: Android
    // stops delivering MotionEvents, the finger keeps moving unseen, and when
    // the block clears, a burst of stale motions floods the server at once.
    // The window "teleports" at the end of the gesture.
    //
    // The pump never blocks the caller: inject* appends to a bounded deque
    // under a small lock (O(1), no I/O). Consecutive pending MOTIONS collapse
    // into the newest one - only the finger's LATEST position is worth
    // injecting; the intermediate ones are frames nobody will ever see - while
    // buttons, keys and text keep strict FIFO order, so a press can never
    // overtake the motion that aims it. Every task re-resolves xtest() at RUN
    // time, so a transport recycled mid-queue degrades to a drop instead of a
    // write into a closed socket.
    private class XtestTask(val motion: Boolean, var body: (X11Client) -> Unit)
    private val xtestQueue = java.util.ArrayDeque<XtestTask>()
    // java.lang.Object, not Any(): Kotlin's Any does not expose wait/notifyAll
    // (the same trap the FIFO writer's inputLock documents).
    private val xtestLock = java.lang.Object()
    @Volatile private var xtestPumpStarted = false
    private val xtestExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "emux-xtest").apply { isDaemon = true; priority = Thread.MAX_PRIORITY }
    }

    private fun ensureXtestPump() {
        if (xtestPumpStarted) return
        xtestPumpStarted = true
        xtestExecutor.execute { xtestPumpLoop() }
    }

    /** Queue one XTEST write. [motion] tasks coalesce with a pending tail motion. */
    private fun postXtest(motion: Boolean, body: (X11Client) -> Unit) {
        lastUserInputMs = System.currentTimeMillis()   // v0.2.95: proof anti-race stamp
        ensureXtestPump()
        synchronized(xtestLock) {
            if (motion) {
                val last = xtestQueue.peekLast()
                if (last != null && last.motion) {
                    // replace in place: order preserved (the motion still
                    // precedes anything queued before it), zero allocation,
                    // and the server only ever sees the newest finger position.
                    last.body = body
                    xtestLock.notifyAll()
                    return
                }
            }
            while (xtestQueue.size >= 512) { xtestQueue.pollFirst(); inputDropped++ }
            xtestQueue.addLast(XtestTask(motion, body))
            xtestLock.notifyAll()
        }
    }

    private fun xtestPumpLoop() {
        while (true) {
            val t = synchronized(xtestLock) {
                while (xtestQueue.isEmpty()) runCatching { xtestLock.wait(500) }
                xtestQueue.pollFirst()
            } ?: continue
            // Resolve the transport at run time: between queueing and running,
            // the proof may have disabled XTEST or a recycle may have replaced
            // the client. A stale task must never write into a closed socket.
            val c = xtest() ?: continue
            runCatching { t.body(c); surfaceXtestErrors(c) }
                .onFailure { controlPlaneFailure("XTEST pump write failed: $it") }
        }
    }

    /** Drop queued writes for a connection that is being torn down. */
    private fun clearXtestQueue() = synchronized(xtestLock) { xtestQueue.clear() }

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
    @Volatile private var identityMiss = 0   // v0.2.109: consecutive server-token misses
    /** v0.2.92: failed control-plane reconnects since the last success (badge: rxfail=). */
    @Volatile private var reconnectFails = 0
    fun reconnectFailCount(): Int = reconnectFails
    // v0.2.92 CAPTURE-PLANE INPUT: set when the capture client's own motion
    // proof passed - XTEST injection then rides the capture connection
    // while the control plane is down (see runCaptureInputProof).
    // lastCapProofMs throttles proof attempts to one per 15 s.
    @Volatile private var capInputOk = false
    @Volatile private var lastCapProofMs = 0L
    /** v0.2.93: consecutive failed capture-plane proofs - drives the retry backoff. */
    @Volatile private var capProofFails = 0

    /** v0.2.95: last USER input (pointer/key/text injection). Proofs that
     *  warp the pointer, or map the red probe window, must not run against a
     *  live gesture: a proof raced by the user's own rapid input fails
     *  SPURIOUSLY, and the button ladder then escalates (rc.xml rewrite /
     *  WM reconfigure / WM switch) against a desktop the user is actively
     *  using - the field report: "interact too fast and the input breaks;
     *  sometimes the click lands on the red probe window". Stamped from
     *  notePointer() (every pointer injection, any transport) and
     *  postXtest() (every queued XTEST write: keys, text, combos). */
    @Volatile private var lastUserInputMs = 0L

    /** True when the user injected something within the last [ms]. */
    private fun userActive(ms: Long = 3_000) =
        System.currentTimeMillis() - lastUserInputMs < ms

    /** Wait for a user-quiet window (proofs run in the pause between
     *  gestures). Returns false if the user is still active after [budgetMs]. */
    private fun awaitQuiet(quietMs: Long, budgetMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            if (!userActive(quietMs)) return true
            runCatching { Thread.sleep(200) }
        }
        return !userActive(quietMs)
    }
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
        // v0.2.83: queued writes belong to the DEAD connection - a press/release
        // pair replayed against the replacement socket minutes later is worse
        // than a dropped click.
        clearXtestQueue()
        inputProof = "pending"
        buttonDelivered = null      // v0.2.78: re-measure on the new connection
        btnMode = "-"
        val rt = com.emux.core.FsLayout.linuxRoot(ctx)
        inputTransport =
            if (java.io.File(rt, "tmp/emux_agent_launched").isFile) "agent" else "down"
        inputAgentUp = inputTransport == "agent"
        // v0.2.92: do not demote the badge to "agent" when the capture plane
        // is already carrying XTEST input - it stays the active transport.
        if (capInputOk && nativeClient?.connected == true) inputTransport = "capture"
        scope.launch {
            // v0.2.91: one failed reconnect used to leave input dead FOREVER -
            // a single connectControlPlane(30 s) that expired (server not up
            // yet, proot churn, a dying display) was never retried, while the
            // 5 s watchdog keepalive had no connection left to keep alive and
            // the guest agent fallback pointed at an agent that was never
            // started (field: proof=ignored + INPUT agent + agent=never-started
            // for a whole session). Retry until the control plane is actually
            // UP (or the session stops); connectControlPlane re-reads the guest
            // marker and the capture publication on every attempt, so each
            // retry re-aims at whatever display the guest is really on.
            while (isActive && state.value != State.IDLE && state.value != State.ERROR) {
                val ok = runCatching { connectControlPlane(30_000) }.getOrDefault(false)
                if (ok) break
                // v0.2.92: a reconnect that never completes used to be
                // INVISIBLE - proof=pending (yellow) with no reason anywhere,
                // for a whole field session while the user tapped a desktop
                // that ignored every click. Name every failed attempt, count
                // them for the badge (rxfail=), and paint the last error
                // (ctrlErr=) - a screenshot must say WHY input is on the
                // fallback and what keeps the control plane out.
                reconnectFails++
                noteInfo(
                    "control plane reconnect #$reconnectFails FAILED after 30s " +
                        "(${lastCtrlError ?: "unspecified"}) - frames still flow; retrying in 5s")
                kotlinx.coroutines.delay(5_000)
            }
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

    /** v0.2.84: seconds since ANY capture source last PROVED itself (a
     *  successful grab or mailbox read - changed pixels or not); -1 if never.
     *  The badge's idle-vs-dead discriminator: a diffed pipeline delivers no
     *  frames on a still desktop while staying perfectly alive. */
    val sourceProofAgeSec: Long get() =
        if (lastSourceProofAtMs == 0L) -1
        else (System.currentTimeMillis() - lastSourceProofAtMs) / 1000

    /** v0.2.70: last capture error tail, painted on the pane while STALE so a
     *  screenshot names the tool killing the stream (no adb, no terminal). */
    @Volatile var lastCapErr: String = ""; private set
    private var errTail: String = ""
    private var capErr: String = ""

    private fun surfaceXtestErrors(c: X11Client) {
        val errs = c.takeErrors()
        if (errs.isNotEmpty()) noteInfo("XTEST rejected an event: ${errs.first().describe()}")
    }

    /**
     * v0.2.65: see [inputProof]. Runs once per control-plane connection, on its
     * own daemon thread — never the FIFO writer's single thread (which would
     * stall real input behind it) and never the UI thread.
     *
     * v0.2.78: it now has TWO legs. Motion (the original QueryPointer round
     * trip) proves the server honours FakeInput at all; the button leg
     * ([X11Client.probeButtonDelivery]) proves a click actually reaches an
     * application window. Only the second one can see a window manager that
     * XGrabButton()s the pointer, which is the failure this release fixes.
     */
    private fun runInputProof(c: X11Client) {
        runCatching {
            // v0.2.95 anti-race gate (see runCaptureInputProof): a fresh
            // control plane usually reconnects BECAUSE the user was tapping
            // fast - prove into a quiet window, never against a live
            // gesture. Daemon thread, so the wait is free; runCatching
            // keeps a recycled plane from becoming an exception fountain.
            while (userActive(700)) runCatching { Thread.sleep(250) }
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
                inputProof = "motion-ok"
                com.emux.EmuXLog.i("input proof: MOTION ok (server pointer moved to $txp,$typ as injected)")
                // v0.2.78: motion is not interaction. Prove a button too, and
                // escalate if buttons are being swallowed.
                buttonProofAndEscalate(c)
            } else {
                xtestIgnored = true
                inputProof =
                    if (errs.isNotEmpty()) "rejected:" + errs.first().describe().take(24)
                    else "ignored"
                buttonDelivered = null
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
                // v0.2.80 FIELD FIX ("desktop still very sloppy"): this proof ran
                // ONCE per connection, at the worst possible moment - while the
                // desktop stack is still settling under proot. One raced
                // QueryPointer then condemned the whole session to the guest
                // xdotool agent, which execs a process per event: every tap and
                // every drag motion pays a guest round trip. Re-probe on a
                // backoff and promote the moment the server honours FakeInput.
                scheduleReproof(c)
            }
        }.onFailure { e ->
            inputProof = "proof-error"
            com.emux.EmuXLog.e("input proof threw", e)
        }
    }

    /**
     * v0.2.80: see [runInputProof]. Backoff re-probe of the MOTION leg only (it is
     * cheap and it is the leg a settling server fails); on success the transport
     * is promoted back to XTEST and the button leg runs its normal escalation.
     * Stops as soon as anything else has already fixed the transport.
     */
    private fun scheduleReproof(c: X11Client) {
        scope.launch {
            // v0.2.82: indefinite, not six tries. The field device that reported
            // "clicks do nothing" was stuck on the guest agent for the whole
            // session; if the ignore was a boot-time race (desktop stack still
            // settling, WM startup grab) the window to notice can be minutes.
            // 3 s, 10 s, 20 s, 40 s, then every minute for as long as the
            // session lives and the agent is still carrying input.
            var i = 0
            while (true) {
                val wait = longArrayOf(3_000, 10_000, 20_000, 40_000)
                    .getOrElse(i) { 60_000L }
                i++
                kotlinx.coroutines.delay(wait)
                if (!c.connected || c.xtestOpcode <= 127) return@launch
                if (!xtestIgnored && inputTransport == "xtest") return@launch
                val p0 = runCatching { c.queryPointerRoot() }.getOrNull() ?: continue
                val dx = if (p0.first + 48 <= c.screenWidth - 4) 48 else -48
                val dy = if (p0.second + 48 <= c.screenHeight - 4) 48 else -48
                val tx = (p0.first + dx).coerceIn(4, (c.screenWidth - 4).coerceAtLeast(8))
                val ty = (p0.second + dy).coerceIn(4, (c.screenHeight - 4).coerceAtLeast(8))
                runCatching { c.fakeMotion(tx, ty) }
                kotlinx.coroutines.delay(250)
                val p1 = runCatching { c.queryPointerRoot() }.getOrNull() ?: continue
                if (kotlin.math.abs(p1.first - tx) <= 4 && kotlin.math.abs(p1.second - ty) <= 4) {
                    xtestIgnored = false
                    inputProof = "motion-ok"
                    inputTransport = "xtest"
                    inputAgentUp = false
                    noteInfo(
                        "input re-proof PASSED after ${wait / 1000}s of backoff - " +
                            "XTEST restored, guest agent retired")
                    buttonProofAndEscalate(c)
                    return@launch
                }
            }
        }
    }

    /**
     * v0.2.92: CAPTURE-PLANE INPUT. The 18:15 field round proved a session
     * can outlive its control plane for many minutes: after a recycle the
     * reconnect can keep failing (proof=pending, yellow, forever - the
     * v0.2.91 retry loop never gives up but also never succeeds) while the
     * CAPTURE connection streams the desktop happily and the guest agent
     * carries motion (the cursor follows the finger). But the agent is one
     * xdotool process per event, and worse: the BUTTON proof and its whole
     * escalation ladder (rc.xml rewrite -> reconfigure -> WM switch - the
     * proven click-swallow remedy) only ever ran over a control-plane
     * connection, so a mid-session button swallow went unmeasured and
     * unfixed while the user tapped a desktop that ignored every click.
     *
     * The capture client is a fully working X11Client to the server the
     * user is actually watching (its identity is what the v0.2.90 guard
     * compares AGAINST), so when the control plane is down we prove XTEST
     * over the capture connection and inject through it until the control
     * plane returns. Same server, same wire, same pump - a different socket.
     *
     * Runs on its own daemon thread, never the capture loop thread: the
     * proof's QueryPointer round trips and the probe window's event reads
     * interleave with GetImage grabs through the same locks the capture
     * loop already uses.
     */
    private fun runCaptureInputProof(c: X11Client) {
        // v0.2.93: the v0.2.92 silent '?: return' made every failed proof look
        // like nothing happened - on the field device the QueryPointer
        // round-trip was racing the capture loop's ~10/s GetImage grabs on the
        // SAME connection and losing (replies cross-consumed - see
        // X11Client.roundTripLock), so the proof retried every 15 s forever
        // and the badge stayed yellow. Name the failure in the note history
        // and back off exponentially instead of hammering the capture loop.
        // v0.2.95 ANTI-RACE GATE: the motion proof WARPED the pointer while
        // the user was mid-drag, and the button leg MAPPED the red probe
        // window under their taps (field: "interact too fast and input
        // breaks; sometimes the click lands on the red window"). Wait for a
        // quiet window; a raced proof proves nothing either way.
        // v0.2.96 LIVENESS FIX: the one-shot ABORT meant a session whose
        // first minutes were active (the user tests by using the desktop)
        // kept input on the per-event xdotool agent - one guest process
        // exec PER EVENT, the reported "sloppy and slow" - until a fresh
        // attempt happened to START inside a >=700 ms pause. Park here
        // instead: this daemon thread sleeps while the user glides, the
        // proof runs at the FIRST pause, and the heartbeat below keeps the
        // 5 s watchdog from stacking a second proof thread while this one
        // parks (lastCapProofMs is the watchdog's own start gate).
        while (c.connected && userActive(700)) {
            lastCapProofMs = System.currentTimeMillis()
            runCatching { Thread.sleep(250) }
        }
        if (!c.connected) return
        val p0 = runCatching { c.queryPointerRoot() }.getOrNull()
        if (p0 == null) {
            capProofFails++
            noteInfo(
                "capture-plane input proof: QueryPointer over the capture connection " +
                    "got no reply (read=${c.lastReadErr.ifBlank { "?" }}) - retry " +
                    "with backoff (${15_000L shl capProofFails.coerceAtMost(3)} ms); input " +
                    "stays on the guest agent meanwhile")
            return
        }
        val dxp = if (p0.first + 48 <= c.screenWidth - 4) 48 else -48
        val dyp = if (p0.second + 48 <= c.screenHeight - 4) 48 else -48
        val txp = (p0.first + dxp).coerceIn(4, (c.screenWidth - 4).coerceAtLeast(8))
        val typ = (p0.second + dyp).coerceIn(4, (c.screenHeight - 4).coerceAtLeast(8))
        runCatching { c.fakeMotion(txp, typ) }
        Thread.sleep(250)
        val p1 = runCatching { c.queryPointerRoot() }.getOrNull()
        if (p1 != null && kotlin.math.abs(p1.first - txp) <= 4 &&
            kotlin.math.abs(p1.second - typ) <= 4
        ) {
            capInputOk = true
            capProofFails = 0
            inputTransport = "capture"
            noteInfo(
                "capture-plane input proof PASSED (pointer followed to $txp,$typ on " +
                    "the capture connection) - XTEST rides the capture plane until " +
                    "the control plane reconnects; the button proof runs there too")
            // v0.2.92: the button leg is the one that detects the
            // motion-works-clicks-don't swallow class - run it over the
            // capture connection too, so the ladder can fire even while
            // the control plane is down.
            buttonProofAndEscalate(c)
        } else {
            noteInfo(
                "capture-plane input proof FAILED (ptr=$p1 wanted=$txp,$typ) - the " +
                    "server ignores FakeInput right now; input stays on the guest agent")
        }
    }

    /**
     * v0.2.78 — measure button delivery, then escalate until clicks work.
     *
     * The ladder is ordered cheapest-and-most-correct first, and every rung is
     * a measured result from tools/x11probe/button_delivery_test.sh:
     *   1. ask the GUEST to rewrite the stamped rc.xml and `openbox
     *      --reconfigure`. This removes the CAUSE (a Frame/Client/Desktop
     *      mousebind becomes an XGrabButton, and xorg checks passive grabs on
     *      every window from the root down, so the application never sees the
     *      press) without touching the session: no restart, no lost windows.
     *      (case D/H: DELIVERED)
     *   2. ask the GUEST to switch window manager, or run with none - the
     *      report that started this fix measured that killing Openbox alone
     *      makes an injected click arrive. (case A: DELIVERED)
     *
     * There is deliberately no XTEST GrabControl(impervious) rung. It is the
     * documented one-request fix and it was tried first: the server accepts the
     * request without error and the click is STILL swallowed by a passive
     * Frame-context grab (case G). The primitive stays in
     * [X11Client.xtestGrabControl] for an ACTIVE grab, and the measurement stays
     * here so it is not re-shipped as a fix.
     *
     * Note what does NOT help: the guest xdotool agent. It injects through the
     * same XTEST extension, so the same grab swallows it identically - which is
     * why a failed button proof must never be reported as "input fell back to
     * the agent" (that fallback is for servers that ignore FakeInput entirely).
     */
    private fun buttonProofAndEscalate(c: X11Client) {
        // v0.2.95: the button probe MAPS A WINDOW and clicks it - never run
        // it under the user's fingers (their taps land on the probe window
        // and their drags race the probe's own pointer work).
        // v0.2.96 LIVENESS FIX: the deferral was ONE-SHOT and nothing ever
        // re-ran the button leg (scheduleReproof returns the moment the
        // transport is already XTEST), so a session whose first 10 s were
        // active never measured click delivery at all - a swallow class went
        // undetected for its whole life. Park for the quiet window on this
        // daemon thread instead: same anti-race guarantee (never probe
        // under live input), but the probe provably runs at the first
        // >=1.5 s pause.
        // v0.2.109 FIELD ("the start menu closes by itself if I take too
        // long"): the probe maps a window and CLICKS it, which dismisses any
        // open menu or popup. It ran at the first 1.5 s pause of EVERY new
        // control-plane connection - and the broken identity guard recycled
        // that connection constantly. Now: once per X server (the verdict is
        // a property of the server + WM, not of a socket), and only after
        // 8 s without input.
        val tok = capTokenAtom
        if (tok != 0 && probedToken == tok) {
            buttonDelivered = probedDelivered
            btnMode = probedBtnMode
            inputProof = probedProof
            return
        }
        if (userActive(8_000)) inputProof = "motion-ok"   // parked, not dead
        while (userActive(8_000) && c.connected) runCatching { Thread.sleep(250) }
        if (!c.connected) return
        try { buttonProofAndEscalateOnce(c) } finally {
            probedToken = tok
            probedDelivered = buttonDelivered
            probedBtnMode = btnMode
            probedProof = inputProof
        }
    }

    // v0.2.109: last button verdict, keyed by the server token.
    @Volatile private var probedToken = 0
    @Volatile private var probedDelivered: Boolean? = null
    @Volatile private var probedBtnMode = "-"
    @Volatile private var probedProof = "pending"

    private fun buttonProofAndEscalateOnce(c: X11Client) {
        var r = runCatching { c.probeButtonDelivery() }.getOrNull()
        if (r == null) {
            inputProof = "btn-probe-error"
            noteInfo("button proof could not run (probe threw) - see logcat")
            return
        }
        com.emux.EmuXLog.i("button proof: delivered=${r.delivered} ${r.detail}")
        if (r.delivered) {
            buttonDelivered = true
            btnMode = "pervious"
            inputProof = "ok"
            publishBtnVerdict("ok", r.detail)
            noteInfo("button proof OK: ${r.detail}")
            return
        }
        buttonDelivered = false
        inputProof = "motion-only"
        noteInfo(
            "BUTTONS ARE SWALLOWED (motion works): ${r.detail} - a window manager is " +
                "holding a passive button grab. rc.xml binds: ${rcGrabs.ifEmpty { "?" }}, wm=${wmMode.ifEmpty { "?" }}")

        // v0.2.95: the ladder MUTATES the desktop the user is looking at
        // (rc.xml rewrite, WM reconfigure, WM switch). A probe that failed
        // only because the user's own rapid input raced it must NOT
        // escalate against a live gesture.
        // v0.2.96 LIVENESS FIX: the one-shot deferral never re-ran (same seam
        // as the head of this function). Park for the quiet window, then
        // RE-CONFIRM the failure there before mutating the desktop - a
        // probe raced by live input must not cost an rc.xml rewrite.
        while (userActive(1_500) && c.connected) runCatching { Thread.sleep(250) }
        if (!c.connected) return
        val rq = runCatching { c.probeButtonDelivery() }.getOrNull()
        if (rq != null && rq.delivered) {
            btnRecovered(c, rq, "quiet-window re-probe: the failed probe was raced by live input")
            return
        }
        // --- stage 1: guest rewrites rc.xml + reconfigures the WM ----------
        publishBtnVerdict("dead", "stage1 ${r.detail}")
        com.emux.EmuXLog.i("button ladder: stage 1 requested (guest rc.xml rewrite + reconfigure)")
        var waited = 0
        while (waited < 12_000 && c.connected) { Thread.sleep(500); waited += 500
            if (!java.io.File(com.emux.core.FsLayout.linuxRoot(ctx), "tmp/emux_btn_verdict").exists()) break }
        Thread.sleep(1_500)
        // v0.2.96: WAIT for the quiet window - the one-shot awaitQuiet
        // could skip this re-probe entirely, and nothing ever came back.
        while (c.connected && userActive(1_500)) runCatching { Thread.sleep(250) }
        if (c.connected) {   // re-probe in the quiet window
            r = runCatching { c.probeButtonDelivery() }.getOrNull() ?: r
            if (r.delivered) { btnRecovered(c, r, "stage 1: guest rewrote rc.xml + reconfigured the WM"); return }
        }

        // --- stage 2: the guest switches window manager, or runs without one -
        // XTEST GrabControl(impervious) was tried here first and MEASURED not to
        // help: against the Frame-context grab that caused this bug the server
        // accepted the request and the click was still swallowed (case G of
        // tools/x11probe/button_delivery_test.sh). The primitive stays available
        // in X11Client for an ACTIVE grab, but the ladder does not depend on it -
        // and it is not free: while impervious, a WM mid-drag stops seeing our
        // motion. What does work is removing the grabber (case A: no WM at all).
        if (c.connected) {
            publishBtnVerdict("dead", "stage2 ${r.detail}")
            noteInfo("button ladder stage 2: asking the guest to switch window manager " +
                "(or run without one - unmanaged windows still take clicks)")
            var w2 = 0
            while (w2 < 14_000 && c.connected) { Thread.sleep(500); w2 += 500
                if (!java.io.File(com.emux.core.FsLayout.linuxRoot(ctx), "tmp/emux_btn_verdict").exists()) break }
            Thread.sleep(2_000)
            // v0.2.96: WAIT for the quiet window - see stage 1 below.
            while (c.connected && userActive(1_500)) runCatching { Thread.sleep(250) }
            if (c.connected) {   // re-probe in the quiet window
                r = runCatching { c.probeButtonDelivery() }.getOrNull() ?: r
                if (r.delivered) { btnRecovered(c, r, "stage 2: wm now ${wmMode.ifEmpty { "?" }}"); return }
            }
        }
        inputProof = "motion-only"
        buttonDelivered = false
        noteInfo(
            "buttons STILL swallowed after both ladder stages (${r.detail}). wm=${wmMode.ifEmpty { "?" }}, " +
                "ladder=${btnAction.ifEmpty { "no action recorded" }}, rc.xml grabs=${rcGrabs.ifEmpty { "?" }}. " +
                "Run: emux-x11 doctor")
    }

    private fun btnRecovered(c: X11Client, r: X11Client.ButtonProbe, how: String) {
        buttonDelivered = true
        if (btnMode == "-") btnMode = "pervious"
        inputProof = "ok"
        publishBtnVerdict("recovered", "$how; ${r.detail}")
        noteInfo("buttons DELIVERED again - $how (${r.detail})")
        com.emux.EmuXLog.i("button ladder recovered: $how")
    }

    fun injectClick(xInXSpace: Int, yInXSpace: Int, button: Int = 1, press: Boolean) {
        injectCount++
        notePointer(xInXSpace, yInXSpace, press)
        if (xtest() != null) {
            // v0.2.83: queued, not written here - see the XTEST PUMP comment.
            // fakeButton() emits its own motion first, so the click still lands
            // under the finger, and FIFO order keeps it behind any pending drag.
            postXtest(motion = false) { c -> c.fakeButton(button, xInXSpace, yInXSpace, press) }
            return
        }
        ensureInputWriter()
        inputLine("M $xInXSpace $yInXSpace 0")
        inputLine(if (press) "D $button 0 0" else "U $button 0 0")
    }

    fun injectMotion(x: Int, y: Int) {
        injectCount++
        notePointer(x, y, null)
        if (xtest() != null) {
            // The hot path of a drag: O(1) enqueue, coalesced with any motion
            // still waiting, written on the pump thread. The UI thread never
            // touches the socket, so a full send buffer can no longer stall
            // touch dispatch (v0.2.83).
            postXtest(motion = true) { c -> c.fakeMotion(x, y) }
            return
        }
        ensureInputWriter(); inputLine("M $x $y 0")
    }

    /** Drag ended outside a click: just release button 1 at position. */
    fun fakeButtonReleaseOnly(x: Int, y: Int) {
        injectCount++
        notePointer(x, y, false)
        if (xtest() != null) {
            postXtest(motion = false) { c -> c.fakeButton(1, x, y, false) }
            return
        }
        ensureInputWriter(); inputLine("M $x $y 0"); inputLine("U 1 0 0")
    }

    fun injectKeycode(keycode: Int, press: Boolean) {
        injectCount++
        // v0.2.66 FIX: was the one entry point that bypassed xtest(), so it
        // kept injecting into a server the proof had just declared dead while
        // every other path had fallen back.
        if (xtest() != null) {
            postXtest(motion = false) { c -> c.fakeKey(keycode, press) }
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
            // v0.2.83: the WRITE is queued on the pump, but the keymap check
            // stays synchronous - it is a pure in-memory lookup, and callers
            // use `false` to mean "no encoding exists anywhere" (DesktopView
            // then falls back to its own paths). Queueing that decision would
            // make the return value a lie.
            if (c.keysymKeycode(ks) < 0) {
                noteInputDropped("keysym 0x${Integer.toHexString(ks)} is not in the server keymap")
                return false
            }
            postXtest(motion = false) { it.typeKeysymTap(ks) }
            return true
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
            // v0.2.83: same split as injectKeysymTap - synchronous keymap
            // verdict, queued write. The keysym computed here is exactly the
            // one X11Client.typeChar would use (Latin-1 direct, else the
            // Unicode range), so the false verdict matches the old
            // typeChar()==false case one-for-one.
            val ks = if (ch.code < 0x100) ch.code else 0x01000000 + ch.code
            if (c.keysymKeycode(ks) < 0) {
                noteInputDropped("char '$ch' has no keymap encoding on this server - dropped")
                return false
            }
            postXtest(motion = false) { it.typeChar(ch) }
            return true
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
        notePointer(xInXSpace, yInXSpace, null)
        if (xtest() != null) {
            // v0.2.83: one queued task for the whole burst - a wheel flick is
            // one logical event, and splitting it across pump turns would let
            // a drag motion interleave between the clicks.
            postXtest(motion = false) { c ->
                // fakeButton() emits the motion itself, so the wheel lands
                // wherever the fingers are rather than at the last click.
                repeat(clicks.coerceAtMost(12)) {
                    c.fakeButton(button, xInXSpace, yInXSpace, true)
                    c.fakeButton(button, xInXSpace, yInXSpace, false)
                }
            }
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
     * v0.2.85 MOUSE/TRACKPAD WHEEL: one whole ACTION_SCROLL gesture - BOTH
     * axes, every whole notch - as a single queued XTEST burst (one buffered
     * write on the wire; see [X11Client.fakeScrollBurst]). [vNotches] positive
     * = wheel up = button 4, negative = button 5; [hNotches] positive = right
     * = button 7, negative = left = button 6. DesktopView owns the fractional
     * accumulation (trackpads send 0.1-notch events); values arrive here whole.
     */
    fun injectScrollPair(xInXSpace: Int, yInXSpace: Int, vNotches: Int, hNotches: Int) {
        if (vNotches == 0 && hNotches == 0) return
        injectCount++
        notePointer(xInXSpace, yInXSpace, null)
        val vBtn = if (vNotches > 0) 4 else 5
        val hBtn = if (hNotches > 0) 7 else 6
        val vn = minOf(kotlin.math.abs(vNotches), 24)
        val hn = minOf(kotlin.math.abs(hNotches), 24)
        if (xtest() != null) {
            postXtest(motion = false) { c ->
                c.fakeScrollBurst(xInXSpace, yInXSpace, vBtn, vn, hBtn, hn)
            }
            return
        }
        ensureInputWriter()
        inputLine("M $xInXSpace $yInXSpace 0")
        repeat(vn) { inputLine("D $vBtn 0 0"); inputLine("U $vBtn 0 0") }
        repeat(hn) { inputLine("D $hBtn 0 0"); inputLine("U $hBtn 0 0") }
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
            // v0.2.83: the whole combo is ONE queued task - modifier down,
            // tap, modifier up must not be interleaved with anything else
            // (least of all a coalescing drag motion). The keymap verdicts
            // stay synchronous: they are pure lookups, and they decide which
            // transport gets the combo at all.
            if (c.keysymKeycode(ks) >= 0) {
                postXtest(motion = false) { cc ->
                    val held = ArrayList<Int>(3)
                    // Order matters only for tidiness; release is the reverse.
                    if (ctrl) cc.keysymKeycode(0xFFE3).takeIf { it > 0 }?.let { held.add(it); cc.fakeKey(it, true) }
                    if (alt)  cc.keysymKeycode(0xFFE9).takeIf { it > 0 }?.let { held.add(it); cc.fakeKey(it, true) }
                    if (meta) cc.keysymKeycode(0xFFEB).takeIf { it > 0 }?.let { held.add(it); cc.fakeKey(it, true) }
                    cc.typeKeysymTap(ks)
                    held.asReversed().forEach { cc.fakeKey(it, false) }
                }
                return true
            }
            val anyMod = listOfNotNull(
                if (ctrl) 0xFFE3 else null,
                if (alt) 0xFFE9 else null,
                if (meta) 0xFFEB else null,
            ).any { c.keysymKeycode(it) > 0 }
            if (!anyMod) {
                noteInputDropped("modifier keysym is not in the server keymap (no Ctrl/Alt/Super)")
                return false
            }
            // Base keysym missing from the XTEST keymap but modifiers present:
            // pre-v0.2.83 this fell through to the FIFO agent (typeKeysymTap
            // returned false with held non-empty). Keep that path.
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
        if (xtest() != null) {
            // v0.2.83: the whole commit is one queued task (typeString batches
            // its own writes), so an IME paste can neither be split by a drag
            // motion nor block the UI thread on the socket.
            postXtest(motion = false) { it.typeString(text) }
            return true
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
        desktopId = ""   // v0.2.108: the stop sweep kills the whole marked tree
        frameReader?.cancel(); frameReader = null
        runCatching { activeSock?.close() }; activeSock = null   // v0.1.54
        runCatching { listener?.close() }; listener = null
        x11?.close(); x11 = null
        clearXtestQueue()   // v0.2.83: never replay input into the next session
        // v0.2.59: drop the queued input and the FIFO handle. The writer thread
        // is a daemon and is reused by the next session; leaving the stream open
        // would keep writing into a FIFO the next agent has already unlinked.
        synchronized(inputLock) { inputPending.clear() }
        runCatching { inputFifo?.close() }; inputFifo = null; inputFifoKey = null
        inputAgentUp = false; inputTransport = "down"
        scope.launch {
            xSession?.stop(); captureSession?.stop()
            runCatching { fbJob?.cancel() }; fbMailSession?.stop()
            xSession = null; captureSession = null
            killDesktopGuests()   // v0.1.54: guests outlive session stop otherwise
        }
        state.value = State.IDLE
    }
}
