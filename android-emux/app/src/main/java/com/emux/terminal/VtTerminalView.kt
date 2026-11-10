package com.emux.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.TextPaint
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.util.TypedValue
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import kotlin.math.ceil
import kotlin.math.max

/**
 * EmuX terminal renderer — a compact, allocation-free VT100/ANSI surface.
 *
 * Why not embed Termux's TerminalView directly? Its public artifact exposes the
 * emulator core (com.termux:terminal-emulator) but the JNI PTY layer lives in
 * app_process code we deliberately don't ship; rendering through our own view
 * keeps the whole stack auditable and adds two power wins over stock Termux:
 *
 *  - DAMAGE-ONLY INVALIDATION: rows are marked dirty by the parser; onDraw
 *    repaints only those row bands → typical shell prompt costs ~2 rows of
 *    text draw instead of a full-screen redraw.
 *  - NO ANIMATION TIMERS: cursor blink uses invalidate-on-schedule at 500 ms
 *    ONLY while visible & focused; when the Activity pauses or IME hides we
 *    cancel it entirely (Termux keeps a frame callback alive otherwise).
 *
 * Supported escape sequences (the practical 95% used by shells/TUIs):
 *  CSI m (SGR colors incl. 256 + truecolor), H/f (cursor pos), J (erase disp),
 *  K (erase line), A/B/C/D/E/F/G cursor moves, s/u save/restore, h/l modes
 *  (DECCKM, wrap, visible cursor), P (DCH), @ (ICH), d (line pos), n (CPR),
 *  OSC 0/2 (title ignored→kept for badge), BEL swallowed silently.
 */
class VtTerminalView(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    // ------------------------------------------------------------------ model
    class Style(var fg: Int = DEFAULT_FG, var bg: Int = Color.TRANSPARENT,
                var bold: Boolean = false, var inverse: Boolean = false,
                var underline: Boolean = false)

    private val COLS_DEFAULT = 80
    private var cols = COLS_DEFAULT
    private var rows = 40

    /** Screen buffer: each cell = codepoint + style index into [stylePool]. */
    private class Row(val chars: IntArray, val styles: ByteArray) {
        fun setStyle(x: Int, v: Int) { styles[x] = v.toByte() }
    }
    private var screen: Array<Row>
    private var curX = 0
    private var curY = 0
    private var savedX = 0
    private var savedY = 0
    private var wrapNext = false
    private var altScreen = false
    private var cursorVisible = true
    private var applicationCursorKeys = false
    // v0.1.82: held dead-key accent (COMBINING_ACCENT-masked char) awaiting the
    // next character for getDeadChar() composition. Terminal-only fix.
    private var pendingDeadAccent = 0

    private val stylePool = ArrayList<Style>()
    private val defStyle = Style().also { stylePool.add(it) }

    private var pen = Style()
    private var penIdx = 0
    private fun styleIndex(s: Style): Int {
        // intern styles so cells store a byte → tiny memory + fast compare
        val i = stylePool.indexOfFirst {
            it.fg == s.fg && it.bg == s.bg && it.bold == s.bold &&
            it.inverse == s.inverse && it.underline == s.underline
        }
        if (i >= 0) return i
        stylePool.add(Style(s.fg, s.bg, s.bold, s.inverse, s.underline))
        return stylePool.size - 1
    }

    // damage tracking — MUST be resized together with [screen] in resize();
    // a stale fixed-size array caused ArrayIndexOutOfBounds / OOM churn (v0.1.5 bugfix)
    private var dirtyRows = BooleanArray(rows) { true }
    private var allDirty = true

    // ---- v0.1.54: SCROLLBACK -------------------------------------------------
    // Rows scrolled off the top are kept in [history] (plain-shell mode only;
    // alternate-screen apps like less/vim manage their own viewport). The view
    // can render any window of history+screen: [scrollOffset] = how many rows
    // back from live we are (0 = live). Finger-drag scrolls, double-tap jumps
    // to live, ExtraKeys pgup/pgdn page through it.
    private val history = ArrayDeque<Row>()
    private val MAX_HISTORY = 3000
    private var scrollOffset = 0
    private var touchLastY = 0f
    private var touchAcc = 0f
    private var dragging = false
    private var lastTapTs = 0L
    private val touchSlopPx = android.view.ViewConfiguration.get(context).scaledTouchSlop * 1.5f

    // v0.1.98 COPY/PASTE (user request): long-press then drag = select text
    // (release copies to the Android clipboard); long-press WITHOUT dragging =
    // paste the clipboard into the session. Selection renders as a blue
    // overlay; it clears on the next touch or after copying.
    private var selecting = false
    private var selMoved = false
    private var selA = -1; private var selACol = 0
    private var selB = -1; private var selBCol = 0
    private val lpHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lpRunnable: Runnable? = null
    private val selPaint = Paint().apply { color = 0x663399FF; style = Paint.Style.FILL }

    /** Push a row that is about to scroll off the top into scrollback. */
    private fun pushHistory(r: Row) {
        history.addLast(r)
        while (history.size > MAX_HISTORY) history.removeFirst()
        // keep the SAME content on screen while the user reads history
        if (scrollOffset > 0) scrollOffset = (scrollOffset + 1).coerceAtMost(history.size)
    }

    /** Scroll the viewport by [deltaRows] towards history (+) / live (-). */
    fun scrollViewportBy(deltaRows: Int) {
        synchronized(modelLock) {
            if (history.isEmpty()) return
            val nv = (scrollOffset + deltaRows).coerceIn(0, history.size)
            if (nv != scrollOffset) { scrollOffset = nv; markAllDirty() }
        }
        invalidate()
    }

    /** Page through scrollback: +1 page up (older), -1 page down (towards live). */
    fun scrollPage(pagesUp: Int) = scrollViewportBy(pagesUp * (rows / 2))

    /** Jump back to the live edge. */
    fun scrollToEnd() {
        synchronized(modelLock) {
            if (scrollOffset != 0) { scrollOffset = 0; markAllDirty() }
        }
        invalidate()
    }

    // ------------------------------------------------------------------ paint
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = 34f
    }
    private val boxPaint = Paint().apply { style = Paint.Style.FILL }
    private var cellW = 0f
    private var cellH = 0f
    private var baseline = 0f

    init {
        // v0.1.101 MOBILE: the paint was 34px RAW — about 17dp on a
        // 2x tablet but only ~11dp on a 3x phone, so phone terminal
        // text was tiny and the grid ballooned to 100+ columns.
        // 13sp keeps text readable on phones and close to the old
        // look on tablets; onSizeChanged() re-derives cols/rows.
        textPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 13f, resources.displayMetrics)
        isFocusableInTouchMode = true
        // v0.1.19 BUGFIX I: without onCheckIsTextEditor() == true the framework
        // treats this View as a NON-text-editor: showSoftInput() is ignored, no
        // InputConnection is ever created (onCreateInputConnection never runs),
        // and physical/IME keys bypass onKeyDown entirely. That is precisely
        // "keyboard cannot type on the interface" even though everything else
        // looked right. Termux's emulated-UI terminal declares itself a text
        // editor the same way.
        isFocusable = true
        // v0.1.20 BUGFIX: MainActivity.onResume does
        //   b.termContainer.findViewWithTag<VtTerminalView>("term")?.requestFocus()
        // but the tag was only ever set inside onTouchEvent — i.e. AFTER the
        // first tap. Before any touch the lookup returned null, focus was never
        // restored (after tab switches / activity pause-resume), and the IME
        // connection died with it: typing did nothing. Set the identity here.
        tag = "term"
        screen = buildScreen(rows, cols)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // v0.1.56: start the parser thread (see feed())
        if (parserThread == null) {
            parserThread = Thread({
                while (!Thread.currentThread().isInterrupted) {
                    val first = runCatching { feedQueue.poll(300, java.util.concurrent.TimeUnit.MILLISECONDS) }.getOrNull()
                        ?: continue
                    synchronized(modelLock) {
                        parseChunk(first)
                        // drain the whole burst under one lock hold: one
                        // coherent repaint instead of N partial ones
                        while (true) {
                            val nxt = feedQueue.poll() ?: break
                            parseChunk(nxt)
                        }
                        markRowDirty(curY)
                    }
                    scheduleInvalidate()
                }
            }, "emux-vt-parser").apply { isDaemon = true; start() }
        }
        if (tag == null) tag = "term"
        // v0.1.25 BUGFIX: request focus as soon as the view enters the window.
        // Previously focus only came from MainActivity.onResume's tag lookup or
        // a tap; if the activity was already resumed when this view got added
        // (tab switch, session attach), nothing ever focused it -> onCreateInputConnection
        // never ran -> IME had no editor to type into ("cannot write on the terminal").
        post { requestFocus(); showKeyboard() }
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        // Keep the soft keyboard bound to this view whenever it gains focus.
        if (gainFocus) showKeyboard()
    }

    override fun onCheckIsTextEditor(): Boolean = true

    private fun buildScreen(r: Int, c: Int): Array<Row> =
        Array(r) { Row(IntArray(c) { 0x20 }, ByteArray(c)) }


    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        cellW = textPaint.measureText("M")
        val fm = textPaint.fontMetrics
        cellH = ceil(fm.descent - fm.ascent)
        baseline = -fm.ascent
        // GUARD (v0.1.5 crash fix): Robolectric/edge layouts can hand us a huge
        // or degenerate size while cellW/cellH are still ~0 → (w / cellW).toInt()
        // overflows to a negative/huge number and buildScreen() dies with
        // OutOfMemoryError "Requested array size exceeds VM limit". Same guard
        // protects real devices from pathological multi-window sizes.
        if (cellW < 1f || cellH < 1f || w <= 0 || h <= 0) { scheduleBlink(); return }
        val newCols = (w / cellW).toInt().coerceIn(20, 400)
        val newRows = (h / cellH).toInt().coerceIn(8, 500)
        if (newCols != cols || newRows != rows) resize(newRows, newCols)
        scheduleBlink()
    }

    private fun resize(newRows: Int, newCols: Int) = synchronized(modelLock) {
        val old = screen
        val ns = buildScreen(newRows, newCols)
        for (y in 0 until minOf(old.size, newRows)) {
            val orow = old[y]; val nrow = ns[y]
            val n = minOf(orow.chars.size, newCols)
            System.arraycopy(orow.chars, 0, nrow.chars, 0, n)
            System.arraycopy(orow.styles, 0, nrow.styles, 0, n)
        }
        screen = ns; rows = newRows; cols = newCols
        dirtyRows = BooleanArray(newRows) { true }   // keep damage map in sync with screen
        curX = curX.coerceAtMost(cols - 1); curY = curY.coerceAtMost(rows - 1)
        markAllDirty()
        // v0.1.38: publish the renderer grid for the guest winsize watcher
        // (assets/bin/emux-winsize): without TIOCSWINSZ from Java the guest PTY
        // stays 0x0 and readline's redraws/wrapping garble the terminal.
        runCatching {
            java.io.File(com.emux.core.FsLayout.linuxRoot(context), "tmp/emuxsize")
                .writeText("$newRows $newCols\n")
        }
        session?.resize(rows, cols)
    }

    // ------------------------------------------------------------------ feed
    var session: TerminalSession? = null
        set(v) {
            field = v
            resetToDefaults()
            // v0.1.35: a session swap (respawn / in-place PTY upgrade) replaces
            // the process behind the InputConnection; re-bind the soft keyboard
            // immediately so the swap never leaves the user without input.
            post { showKeyboard() }
        }

    fun resetToDefaults() {
        synchronized(modelLock) {
            screen = buildScreen(rows, cols); curX = 0; curY = 0; pen = Style(); penIdx = 0
            history.clear(); scrollOffset = 0   // v0.1.54: fresh session = fresh scrollback
            synchronized(echoLock) { echoSent.clear(); echoSuspect = false }   // v0.2.93: re-probe per session
            remoteEcho = false
            markAllDirty()
        }
        invalidate()
    }

    @Volatile var fedBytes: Long = 0L
        private set

    /**
     * v0.1.55: raw-pipe sessions (no PTY) emit bare LF - there is no tty
     * driver to apply ONLCR - so lines staircased down the screen. When the
     * session has no line discipline we emulate the tty: LF also returns the
     * carriage. PTY sessions keep strict VT behaviour (the guest tty already
     * sends CRLF).
     */
    @Volatile var onlcr = false
    // v0.2.80 FIELD FIX (staircased terminal): measure the wire, do not trust the
    // flag. A real PTY with ONLCR can NEVER put a bare 0x0A on the wire - the tty
    // driver rewrites every LF to CR LF - so three bare LFs prove there is no line
    // discipline, whichever flag claims otherwise. ptyWrapped is an INTENT (set
    // from `guestPty` before the guest script(1) is known alive); on one field ROM
    // the wrapper segfaulted (musl loader FAILED), the shell re-exec'd on raw
    // pipes, the flag stayed true, ONLCR emulation stayed off, and every line
    // staircased one indent further right. The renderer now catches that itself.
    private var prevByte = -1
    private var bareLf = 0
    @Volatile var noLineDiscipline = false; private set

    // v0.1.55 UTF-8 decoder state (the old byte-wise feed rendered every
    // multibyte char as mojibake instead of the real glyph).
    private var utf8Left = 0
    private var utf8Cp = 0

    /**
     * v0.1.56 ANR FIX ("app not responding" during `apt list`): feed() used to
     * parse every chunk AND invalidate ON THE UI THREAD. A burst of a few
     * thousand lines (apt list, log tails, cat of a big file) saturated the
     * main looper with parse+full-repaint work until input dispatch timed
     * out. Now feed() only enqueues; a dedicated parser thread mutates the
     * model under [modelLock] and the UI thread repaints at a 30 fps cap.
     */
    private val feedQueue = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    private val modelLock = Any()
    @Volatile private var invalidatePending = false
    private val invalidateRunnable = Runnable { invalidatePending = false; invalidate() }
    private var parserThread: Thread? = null

    private fun scheduleInvalidate() {
        if (invalidatePending) return
        invalidatePending = true
        postDelayed(invalidateRunnable, 33)   // ~30 fps cap, coalesces bursts
    }

    fun feed(bytes: ByteArray) {
        // v0.2.93: session bytes run through the remote-echo probe first - a
        // chunk that is just the tty echoing our own locally-displayed
        // keystrokes is consumed here and never rendered (see remoteEcho).
        var b = bytes
        if (!remoteEcho) b = stripRemoteEcho(b)
        fedBytes += b.size          // v0.1.45 diag counter
        feedQueue.add(b)
    }

    /** v0.2.93: consume the prefix of [bytes] that is the session echoing back
     *  what localEcho() already displayed; two consecutive full matches PROVE
     *  remote echo (one alone could be program output starting with the same
     *  byte). Returns the chunk with the echoed prefix removed (possibly empty). */
    private fun stripRemoteEcho(bytes: ByteArray): ByteArray {
        synchronized(echoLock) {
            if (echoSent.isEmpty()) return bytes
            if (System.currentTimeMillis() - echoSentAt > 800) {
                echoSent.clear(); echoSuspect = false; return bytes
            }
            var i = 0
            while (i < bytes.size && echoSent.isNotEmpty() &&
                bytes[i] == echoSent.peekFirst()) {
                echoSent.removeFirst(); i++
            }
            if (i == 0) {   // program output, not our echo
                echoSent.clear(); echoSuspect = false; return bytes
            }
            if (echoSent.isEmpty()) {
                if (echoSuspect) {
                    remoteEcho = true
                    com.emux.EmuXLog.i("vt: remote echo PROVEN - local echo off (the tty echoes by itself)")
                } else echoSuspect = true
            }
            return if (i >= bytes.size) ByteArray(0) else bytes.copyOfRange(i, bytes.size)
        }
    }

    /** v0.2.93: local display bytes bypass the probe - they are the copy
     *  localEcho() is about to paint, not session output. */
    private fun feedLocal(bytes: ByteArray) {
        fedBytes += bytes.size
        feedQueue.add(bytes)
    }

    private fun parseChunk(bytes: ByteArray) {
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (utf8Left == 0) {
                when {
                    v < 0x80 -> parseByte(v)
                    v in 0xC2..0xDF -> { utf8Cp = v and 0x1F; utf8Left = 1 }
                    v in 0xE0..0xEF -> { utf8Cp = v and 0x0F; utf8Left = 2 }
                    v in 0xF0..0xF4 -> { utf8Cp = v and 0x07; utf8Left = 3 }
                    else -> parseByte(0xFFFD)   // stray continuation byte
                }
            } else {
                if (v and 0xC0 == 0x80) {
                    utf8Cp = (utf8Cp shl 6) or (v and 0x3F)
                    if (--utf8Left == 0) parseByte(utf8Cp)
                } else {
                    utf8Left = 0
                    parseByte(0xFFFD)
                    parseByte(v)
                }
            }
        }
    }

    // ANSI state machine
    private enum class PState { GROUND, ESC, CSI, OSC_STRING }
    private var pstate = PState.GROUND
    private val paramsArr = IntArray(16)
    private var paramsSize = 0
    private val params: List<Int> get() = paramsArr.toList().subList(0, paramsSize)
    private fun addParam(v: Int) { if (paramsSize < paramsArr.size) paramsArr[paramsSize++] = v }
    private fun clearParams() { paramsSize = 0 }
    private var paramCur = -1
    private var oscBuf = StringBuilder()

    private fun parseByte(b: Int) {
        // v0.2.80: bare-LF detector (see noLineDiscipline). Runs in EVERY parser
        // state on purpose: with a line discipline on the wire even LF bytes
        // inside OSC/DCS strings arrive as CR LF.
        if (b == 10 && prevByte != 13) {
            if (++bareLf >= 3 && !noLineDiscipline) {
                noLineDiscipline = true
                onlcr = true
                session?.noteNoLineDiscipline()
            }
        }
        prevByte = b
        when (pstate) {
            PState.GROUND -> when {
                b == 0x1B -> { pstate = PState.ESC; paramCur = -1; clearParams() }
                b == '\n'.code -> { newlineScroll(); if (onlcr) { curX = 0; wrapNext = false } }
                b == '\r'.code -> { curX = 0; wrapNext = false }
                b == '\t'.code -> { curX = ((curX / 8) + 1).coerceAtMost(cols - 1) * 8 }
                b == 0x08 -> { curX = (curX - 1).coerceAtLeast(0) }
                b == 0x07 -> { /* BEL: intentionally silent */ }
                b < 32 -> { /* other C0 ignored */ }
                else -> putChar(b)
            }
            PState.ESC -> when (b) {
                '['.code -> pstate = PState.CSI
                ']'.code -> { pstate = PState.OSC_STRING; oscBuf.setLength(0) }
                '7'.code -> { savedX = curX; savedY = curY; pstate = PState.GROUND }
                '8'.code -> { curX = savedX; curY = savedY; pstate = PState.GROUND }
                'c'.code -> { resetToDefaults(); pstate = PState.GROUND }
                'D'.code, 'E'.code, 'M'.code, '(' .code -> pstate = PState.GROUND // simplified
                else -> pstate = PState.GROUND
            }
            PState.OSC_STRING -> {
                if (b == 0x07 || b == '\\'.code /* after ESC */) pstate = PState.GROUND
                else if (b > 31) oscBuf.append(b.toChar())
            }
            PState.CSI -> handleCsi(b)
        }
    }

    private fun handleCsi(b: Int) {
        when {
            b in '0'.code..'9'.code -> paramCur = (if (paramCur < 0) 0 else paramCur * 10) + (b - '0'.code)
            b == ';'.code -> { addParam(if (paramCur < 0) 0 else paramCur); paramCur = -1 }
            b == '?'.code -> { /* private mode marker, ignore */ }
            b in 0x40..0x7F -> { // final byte
                if (paramCur >= 0) { addParam(paramCur); paramCur = -1 }
                dispatchCsi(b.toChar())
                pstate = PState.GROUND
            }
            else -> { /* intermediate bytes ignored */ }
        }
    }

    private fun p(i: Int, def: Int = 0) = params.getOrElse(i) { def }.let { if (it == 0 && def != 0) def else it }

    private fun dispatchCsi(final: Char) {
        when (final) {
            'A' -> moveY(-p(0, 1))
            'B' -> moveY(p(0, 1))
            'C' -> moveX(p(0, 1))
            'D' -> moveX(-p(0, 1))
            'E' -> { curX = 0; moveY(p(0, 1)) }
            'F' -> { curX = 0; moveY(-p(0, 1)) }
            'G', '`' -> curX = (p(0, 1) - 1).coerceIn(0, cols - 1)
            'd' -> curY = (p(0, 1) - 1).coerceIn(0, rows - 1)
            'H', 'f' -> {
                curY = (p(0, 1) - 1).coerceIn(0, rows - 1)
                curX = (p(1, 1) - 1).coerceIn(0, cols - 1)
            }
            'J' -> eraseDisplay(p(0))
            'K' -> eraseLine(p(0))
            'L' -> insertLines(p(0, 1))
            'M' -> deleteLines(p(0, 1))
            'P' -> deleteChars(p(0, 1))
            '@' -> insertChars(p(0, 1))
            'X' -> eraseChars(p(0, 1))
            'S' -> scrollUp(p(0, 1))
            'T' -> scrollDown(p(0, 1))
            's' -> { savedX = curX; savedY = curY }
            'u' -> { curX = savedX; curY = savedY }
            'm' -> applySgr()
            'h' -> setMode(true)
            'l' -> setMode(false)
            'n' -> if (p(0) == 6) reportCursorPos()
            't' -> { /* window ops ignored */ }
        }
    }

    // ---- operations ----
    private fun putChar(cp: Int) {
        if (wrapNext) { curX = 0; newlineScroll(); wrapNext = false }
        val row = screen[curY]
        row.chars[curX] = cp
        row.styles[curX] = styleIndex(pen).toByte()
        markRowDirty(curY)
        curX++
        if (curX >= cols) { curX = cols - 1; wrapNext = true } // deferred wrap like real terminals
    }

    private fun moveX(d: Int) { curX = (curX + d).coerceIn(0, cols - 1); wrapNext = false }
    private fun moveY(d: Int) { curY = (curY + d).coerceIn(0, rows - 1); wrapNext = false }

    private fun newlineScroll() {
        if (curY == rows - 1) scrollUp(1) else curY++
        wrapNext = false
    }

    private fun scrollUp(n: Int) {
        repeat(n) {
            // v0.1.54: the row leaving the top goes to scrollback (plain shell
            // mode only - alt-screen TUIs repaint the whole screen themselves)
            if (!altScreen) pushHistory(screen[0])
            for (y in 0 until rows - 1) { screen[y] = screen[y + 1] }
            screen[rows - 1] = Row(IntArray(cols) { 0x20 }, ByteArray(cols))
        }
        markAllDirty()
    }

    private fun scrollDown(n: Int) {
        repeat(n) {
            for (y in rows - 1 downTo 1) { screen[y] = screen[y - 1] }
            screen[0] = Row(IntArray(cols) { 0x20 }, ByteArray(cols))
        }
        markAllDirty()
    }

    private fun blankRow(y: Int) { screen[y] = Row(IntArray(cols) { 0x20 }, ByteArray(cols)); markRowDirty(y) }

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> { clearRange(curY, curX, curY, cols); for (y in curY + 1 until rows) blankRow(y) }
            1 -> { for (y in 0 until curY) blankRow(y); clearRange(curY, 0, curY, curX + 1) }
            2, 3 -> for (y in 0 until rows) blankRow(y)
        }
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            0 -> clearRange(curY, curX, curY, cols)
            1 -> clearRange(curY, 0, curY, curX + 1)
            2 -> blankRow(curY)
        }
    }

    private fun clearRange(y0: Int, x0: Int, y1: Int, x1: Int) {
        val row = screen[y0]
        for (x in x0 until minOf(x1, cols)) { row.chars[x] = 0x20; row.setStyle(x, 0) }
        markRowDirty(y0)
    }

    private fun insertLines(n: Int) {
        repeat(n) {
            for (y in rows - 1 downTo curY + 1) screen[y] = screen[y - 1]
            screen[curY] = Row(IntArray(cols) { 0x20 }, ByteArray(cols))
        }
        markAllDirty()
    }

    private fun deleteLines(n: Int) {
        // v0.1.54: CSI M loses rows at the region top - archive them too when
        // the region is the whole screen (the common shell case).
        if (!altScreen && curY == 0) repeat(minOf(n, rows)) { pushHistory(screen[0]) }
        repeat(n) {
            for (y in curY until rows - 1) screen[y] = screen[y + 1]
            screen[rows - 1] = Row(IntArray(cols) { 0x20 }, ByteArray(cols))
        }
        markAllDirty()
    }

    private fun deleteChars(n: Int) {
        val row = screen[curY]
        for (x in curX until cols) {
            val src = x + n
            row.chars[x] = if (src < cols) row.chars[src] else 0x20
            row.setStyle(x, if (src < cols) row.styles[src].toInt() else 0)
        }
        markRowDirty(curY)
    }

    private fun insertChars(n: Int) {
        val row = screen[curY]
        for (x in cols - 1 downTo curX) {
            val src = x - n
            row.chars[x] = if (src >= curX && src >= 0) row.chars[src] else 0x20
            row.setStyle(x, if (src >= curX && src >= 0) row.styles[src].toInt() else 0)
        }
        markRowDirty(curY)
    }

    private fun eraseChars(n: Int) {
        val row = screen[curY]
        for (x in curX until minOf(curX + n, cols)) { row.chars[x] = 0x20; row.setStyle(x, 0) }
        markRowDirty(curY)
    }

    private fun reportCursorPos() {
        session?.write("\u001B[${curY + 1};${curX + 1}R".toByteArray())
    }

    private fun setMode(on: Boolean) {
        if (params.isEmpty()) return
        val mode = paramsArr[0]
        when (mode) {
            25 -> { cursorVisible = on; scheduleBlink() }
            1 -> applicationCursorKeys = on
            7 -> { /* autowrap always on in this impl */ }
            1049, 47, 1047 -> { /* alt screen: single-buffer impl, just clear */ 
                if (on) { altScreen = true; for (y in 0 until rows) blankRow(y) } else altScreen = false }
        }
    }

    // ---- SGR ----
    private fun applySgr() {
        if (params.isEmpty()) { pen = Style(); penIdx = styleIndex(pen); return }
        var i = 0
        val s = Style(pen.fg, pen.bg, pen.bold, pen.inverse, pen.underline)
        while (i < params.size) {
            val v: Int = paramsArr[i]
            when (v) {
                0 -> { s.fg = DEFAULT_FG; s.bg = Color.TRANSPARENT; s.bold = false; s.inverse = false; s.underline = false }
                1 -> s.bold = true
                4 -> s.underline = true
                7 -> s.inverse = true
                22 -> { s.bold = false }
                24 -> s.underline = false
                27 -> s.inverse = false
                in 30..37 -> s.fg = ansiColor(v - 30, bold = s.bold)
                38 -> { i = parseExtColor(i, s, fg = true) }
                39 -> s.fg = DEFAULT_FG
                in 40..47 -> s.bg = ansiColor(v - 40, bold = false)
                48 -> { i = parseExtColor(i, s, fg = false) }
                49 -> s.bg = Color.TRANSPARENT
                in 90..97 -> s.fg = ansiColor(v - 90 + 8, bold = true)
            }
            i++
        }
        pen = s; penIdx = styleIndex(pen)
    }

    private fun parseExtColor(i: Int, s: Style, fg: Boolean): Int {
        // 38;5;n  or  38;2;r;g;b
        val kind = params.getOrNull(i + 1) ?: return i
        return when (kind) {
            5 -> {
                val n = params.getOrNull(i + 2) ?: 0
                val c = xterm256(n)
                if (fg) s.fg = c else s.bg = c
                i + 2
            }
            2 -> {
                val r = params.getOrNull(i + 2) ?: 0
                val g = params.getOrNull(i + 3) ?: 0
                val bl = params.getOrNull(i + 4) ?: 0
                val c = Color.rgb(r, g, bl)
                if (fg) s.fg = c else s.bg = c
                i + 4
            }
            else -> i
        }
    }

    private fun ansiColor(idx: Int, bold: Boolean): Int = PALETTE[idx + if (bold && idx < 8) 8 else 0]

    private fun xterm256(n: Int): Int = when {
        n < 16 -> PALETTE[n]
        n in 16..231 -> {
            val v = n - 16
            val r = (v / 36) * 51; val g = ((v % 36) / 6) * 51; val b = (v % 6) * 51
            Color.rgb(r, g, b)
        }
        else -> { val gr = 8 + (n - 232) * 10; Color.rgb(gr, gr, gr) }
    }

    // ------------------------------------------------------------------ draw
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        synchronized(modelLock) { drawLocked(canvas) }
    }

    /** Absolute virtual row -> Row (history + live grid), null if outside. */
    private fun rowAt(a: Int): Row? = synchronized(modelLock) {
        val hsize = history.size
        when {
            a < 0 || a >= hsize + rows -> null
            a < hsize -> history[a]
            else -> screen[a - hsize]
        }
    }

    private fun normSel(): IntArray {
        var a1 = selA; var c1 = selACol; var a2 = selB; var c2 = selBCol
        if (a1 > a2 || (a1 == a2 && c1 > c2)) {
            val ta = a1; val tc = c1; a1 = a2; c1 = c2; a2 = ta; c2 = tc
        }
        return intArrayOf(a1, c1, a2, c2)
    }

    private fun selectedText(): String {
        if (selA < 0 || selB < 0) return ""
        val (a1, c1, a2, c2) = listOf(normSel()[0], normSel()[1], normSel()[2], normSel()[3])
        val sb = StringBuilder()
        for (a in a1..a2) {
            val r = rowAt(a) ?: continue
            val line = buildString(r.chars.size) { for (cp in r.chars) append(cp.toChar()) }
            val cs = if (a == a1) c1.coerceIn(0, line.length) else 0
            val ce = if (a == a2) (c2 + 1).coerceIn(0, line.length) else line.length
            if (cs < ce) sb.append(line.substring(cs, ce))
            if (a != a2) sb.append('\n')
        }
        return sb.toString()
    }

    private fun cellOf(x: Float, y: Float): Pair<Int, Int> {
        val hsize = history.size
        val vTop = hsize - scrollOffset
        val row = (y / cellH).toInt().coerceIn(0, rows - 1)
        val col = (x / cellW).toInt().coerceIn(0, cols - 1)
        return (vTop + row) to col
    }

    private fun copySelection() {
        val txt = selectedText()
        selA = -1; selB = -1; invalidate()
        if (txt.isBlank()) return
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("emux terminal", txt))
        android.widget.Toast.makeText(context, "copied ${txt.length} chars", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun pasteClipboard() {
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
        val clip = cm?.primaryClip ?: return
        if (clip.itemCount == 0) return
        val txt = clip.getItemAt(0).coerceToText(context)?.toString() ?: return
        session?.write(txt.toByteArray())
        android.widget.Toast.makeText(context, "pasted ${txt.length} chars", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun drawLocked(canvas: Canvas) {
        canvas.drawColor(BG_COLOR)
        val fm = textPaint.fontMetrics
        // v0.1.54: render the scrollback window (history + live grid) instead of
        // only the live grid. v = virtual row index; offset 0 == live view.
        val hsize = history.size
        val vTop = hsize - scrollOffset
        for (y in 0 until rows) {
            // v0.1.43 CRITICAL RENDER FIX: the "damage-only" repaint was
            // self-defeating — every onDraw begins with a FULL-canvas
            // drawColor(BG), but only dirty rows were re-drawn afterwards. On a
            // full-view invalidate() (what feed() issues) the clip covers the
            // whole view, so the clear wiped everything and only the rows that
            // changed in that instant survived until the next frame wiped them
            // again: "text disappears", vanishing notices, black screen with a
            // lone cursor. Damage tracking only works with clipped partial
            // invalidates; the reliable fix is to repaint every row (a text
            // grid of <=40 runs/row is trivially cheap per frame).
            val v = vTop + y
            val row = if (v < hsize) history[v] else screen[v - hsize]
            val rcols = row.chars.size      // rows keep their birth width across resizes
            // v0.2.121 SELECTION FIX: `top` must exist BEFORE the overlay below.
            // It used to be declared after it, so the overlay's bare `top`
            // silently resolved to View.top (= 0): the blue selection band was
            // painted at the TOP ROW of the view no matter which rows were
            // selected (field: "the selection shows at the top but text is not
            // accurately selected" - copying still worked, only the highlight
            // was drawn in the wrong place).
            val top = y * cellH
            // v0.1.98: selection overlay for this row
            if (selA >= 0 && selB >= 0) {
                val ns = normSel()
                if (v in ns[0]..ns[2]) {
                    val cs = if (v == ns[0]) ns[1] else 0
                    val ce = if (v == ns[2]) ns[3] + 1 else row.chars.size
                    if (cs < ce) canvas.drawRect(cs * cellW, top, ce * cellW, top + cellH, selPaint)
                }
            }
            var x = 0
            while (x < rcols) {
                val st = stylePool.getOrElse(row.styles[x].toInt() and 0xFF) { defStyle }
                // background run
                var runEnd = x
                while (runEnd < rcols && row.styles[runEnd] == row.styles[x]) runEnd++
                if (st.bg != Color.TRANSPARENT || st.inverse) {
                    boxPaint.color = if (st.inverse) st.fg else st.bg
                    if (boxPaint.color != Color.TRANSPARENT)
                        canvas.drawRect(x * cellW, top, runEnd * cellW, top + cellH, boxPaint)
                }
                // foreground run (draw as one substring per style run)
                if (runEnd - x > 0) {
                    val sb = StringBuilder(runEnd - x)
                    for (cx in x until runEnd) sb.appendCodePoint(row.chars[cx])
                    textPaint.color = if (st.inverse) BG_COLOR else st.fg
                    textPaint.isFakeBoldText = st.bold
                    val flags = textPaint.flags
                    textPaint.flags = if (st.underline) flags or Paint.UNDERLINE_TEXT_FLAG
                                      else flags and Paint.UNDERLINE_TEXT_FLAG.inv()
                    canvas.drawText(sb.toString(), x * cellW, top + baseline, textPaint)
                }
                x = runEnd
            }
            dirtyRows[y] = false
        }
        allDirty = false

        // cursor (live edge only - a blinking cursor inside scrollback is a lie)
        if (scrollOffset == 0 && cursorVisible && blinkOn && hasWindowFocus()) {
            boxPaint.color = CURSOR_COLOR
            canvas.drawRect(curX * cellW, curY * cellH, (curX + 1) * cellW, (curY + 1) * cellH, boxPaint)
        }

        // v0.1.54: scrollback affordances - position thumb on the right edge and
        // a one-line hint while the user is NOT at the live edge.
        if (hsize > 0) {
            val trackH = height.toFloat()
            val thumbH = max(cellH, trackH * rows.toFloat() / (hsize + rows))
            val y0 = trackH * vTop.toFloat() / (hsize + rows)
            boxPaint.color = if (scrollOffset > 0) 0xFF5A6470.toInt() else 0xFF2A3038.toInt()
            canvas.drawRect(width - dp(3f), y0, width.toFloat(), y0 + thumbH, boxPaint)
        }
        if (scrollOffset > 0) {
            textPaint.color = 0xFF8899AA.toInt()
            textPaint.isFakeBoldText = false
            textPaint.flags = 0
            canvas.drawText("-- scrollback $scrollOffset/$hsize - drag or pgdn to return, double-tap = live --",
                cellW, baseline, textPaint)
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density


    // ---- blink, scheduled only while attached+visible ----
    private var blinkOn = true
    private val blinkRunnable = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow) return
            blinkOn = !blinkOn
            invalidateRect(curX, curY)
            postDelayed(this, BLINK_MS)
        }
    }

    private fun scheduleBlink() {
        removeCallbacks(blinkRunnable)
        if (cursorVisible) postDelayed(blinkRunnable, BLINK_MS)
    }

    private fun invalidateRect(x: Int, y: Int) =
        invalidate((x * cellW).toInt(), (y * cellH).toInt(), ((x + 1) * cellW).toInt(), ((y + 1) * cellH).toInt())

    private fun markRowDirty(y: Int) { if (y in dirtyRows.indices) dirtyRows[y] = true }
    private fun markAllDirty() { allDirty = true }

    /**
     * v0.1.35 BUGFIX ("keyboard disappears after a short time"): banner/pane
     * visibility changes in this window and OEM IME idle-hide behaviour can
     * dismiss the soft keyboard while the terminal keeps focus. Re-assert the
     * keyboard on every window-focus gain so typing is never silently dead.
     * (Merged into the pre-existing focus override that drives cursor blink.)
     */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        runCatching { parserThread?.interrupt() }; parserThread = null
        removeCallbacks(invalidateRunnable)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) { scheduleBlink(); post { showKeyboard() } }
        else removeCallbacks(blinkRunnable)
    }

    // ------------------------------------------------------------------ input
    override fun onTouchEvent(event: android.view.MotionEvent?): Boolean {
        // v0.1.17 BUGFIX (keyboard dead): two defects combined made typing impossible.
        //  1. The view was added programmatically WITHOUT a tag, but MainActivity
        //     calls findViewWithTag<VtTerminalView>("term")?.requestFocus() in
        //     onResume — always null, so focus was never restored after the IME
        //     or another window stole it. Now we set our own tag here as a
        //     belt-and-suspenders fix that works regardless of how we're added.
        //  2. A tap only called requestFocus(); if the view already HAD focus the
        //     call is a no-op and the soft keyboard stayed hidden forever (the
        //     IME is only shown on the focus transition). Now every tap forces
        //     focus + explicitly shows the keyboard again.
        if (tag == null) tag = "term"
        if (!isFocused) requestFocus()
        // v0.1.54 SCROLLBACK GESTURES: one-finger vertical drag scrolls the
        // history (the grid has no other use for drags - mouse reporting is not
        // implemented), double-tap jumps to the live edge, a plain tap keeps the
        // v0.1.17 focus+keyboard behaviour untouched.
        when (event?.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                touchLastY = event.y; touchAcc = 0f; dragging = false
                // v0.1.98: arm the long-press (select or paste) timer
                selA = -1; selB = -1
                lpRunnable?.let { lpHandler.removeCallbacks(it) }
                val dx = event.x; val dy2 = event.y
                lpRunnable = Runnable {
                    selecting = true; selMoved = false
                    val (a, c) = cellOf(dx, dy2)
                    selA = a; selACol = c; selB = a; selBCol = c
                    invalidate()
                }
                lpHandler.postDelayed(lpRunnable!!, 400)
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dy = event.y - touchLastY
                if (selecting) {
                    val (a, c) = cellOf(event.x, event.y)
                    if (a != selB || c != selBCol) { selMoved = true; selB = a; selBCol = c; invalidate() }
                    return true
                }
                if (!dragging && kotlin.math.abs(dy) > touchSlopPx && history.isNotEmpty()) {
                    dragging = true; touchAcc = 0f
                    lpRunnable?.let { lpHandler.removeCallbacks(it) }
                }
                if (dragging) {
                    touchLastY = event.y
                    touchAcc += dy / cellH          // finger down = pull older rows in
                    val r = touchAcc.toInt()
                    if (r != 0) { scrollViewportBy(r); touchAcc -= r.toFloat() }
                    return true
                }
            }
            android.view.MotionEvent.ACTION_UP -> {
                lpRunnable?.let { lpHandler.removeCallbacks(it) }
                if (selecting) {
                    selecting = false
                    if (selMoved) copySelection() else pasteClipboard()
                    return true
                }
                val now = System.currentTimeMillis()
                if (dragging) {
                    dragging = false
                } else if (now - lastTapTs < 300) {
                    lastTapTs = 0L
                    scrollToEnd()                   // double-tap = back to live
                } else {
                    lastTapTs = now
                    showKeyboard()
                }
            }
            android.view.MotionEvent.ACTION_CANCEL -> {
                dragging = false
                lpRunnable?.let { lpHandler.removeCallbacks(it) }
                selecting = false
            }
        }
        return true
    }

    /** Explicitly (re-)open the soft keyboard against this view's input connection. */
    fun showKeyboard(forced: Boolean = false) {
        requestFocus()
        runCatching {
            val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? InputMethodManager
            // v0.1.35: SHOW_IMPLICIT can be refused right after the IME was
            // dismissed (banner layout changes, OEM idle-hide); the forced flag
            // is reserved for explicit re-asserts after bootstrap UI updates.
            imm?.showSoftInput(this,
                if (forced) InputMethodManager.SHOW_FORCED
                else InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /**
     * v0.1.35 BUGFIX ("keyboard disappears after a short time"): banner/pane
     * visibility changes in this window and OEM IME idle-hide behaviour can
     * dismiss the soft keyboard while the terminal keeps focus; the re-assert
     * lives in onWindowFocusChanged (merged with cursor-blink control).
     */

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val s = session ?: return super.onKeyDown(keyCode, event)
        // v0.2.4 FIELD REQUEST: Ctrl+V pastes the clipboard. Until now the
        // ONLY paste path was the long-press trackpad gesture; hardware or
        // soft Ctrl+V did nothing at all ("ctrl V doesnt work").
        if (keyCode == KeyEvent.KEYCODE_V && event.isCtrlPressed &&
            !event.isAltPressed && !event.isShiftPressed) {
            pasteClipboard()
            return true
        }
        // v0.2.111: the Linux-terminal convention too - Ctrl+Shift+V pastes,
        // Ctrl+Shift+C copies the current selection (plain Ctrl+C stays SIGINT).
        if (event.isCtrlPressed && event.isShiftPressed && !event.isAltPressed) {
            if (keyCode == KeyEvent.KEYCODE_V) { pasteClipboard(); return true }
            if (keyCode == KeyEvent.KEYCODE_C && selA >= 0 && selB >= 0) { copySelection(); return true }
        }
        // v0.1.84: IN-APP layout switch shortcut - the ROM's external-keyboard
        // layout shortcut never reaches this app (field device), so Alt+Shift
        // (either press order) toggles the terminal layout Auto<->Spanish and
        // prints the new mode. Terminal-only; desktop untouched.
        val isShiftKey = keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT
        val isAltKey = keyCode == KeyEvent.KEYCODE_ALT_LEFT || keyCode == KeyEvent.KEYCODE_ALT_RIGHT
        if ((isShiftKey || isAltKey) && event.isAltPressed && event.isShiftPressed) {
            val sp = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            val cur = sp.getString("term_keylayout", "auto") ?: "auto"
            val next = if (cur == "es") "auto" else "es"
            sp.edit().putString("term_keylayout", next).apply()
            val msg = "\r\n[emux] terminal keyboard layout: $next\r\n"
            val mb = msg.toByteArray()
            s.write(mb)
            localEcho(mb)
            return true
        }
        // v0.1.83: optional in-app physical layout (TERMINAL ONLY; the user
        // asked for the desktop to stay untouched). ROM shortcuts that switch
        // an external keyboard's layout never reach this app on several
        // devices, so "Spanish (ISO)" maps the position-based Android
        // keycodes to ES characters here; dead accents then compose through
        // the getDeadChar() path below. Ctrl combos always pass through
        // untouched (Ctrl+C etc. must keep working).
        val ucRaw = layoutChar(keyCode, event, event.unicodeChar)
        // v0.1.82 TERMINAL-ONLY dead-key composition (field request: Spanish
        // physical keyboard). A dead-key press ('´', '`', '^', '~', '¨')
        // arrives with the COMBINING_ACCENT bit set; hold it and compose with
        // the NEXT character via KeyEvent.getDeadChar(). Before this, the
        // accent event (unicodeChar carrying the accent bit) fell through the
        // unicodeChar!=0 path as a raw combining char or was dropped, so
        // '´'+'e' never produced 'é' - Spanish felt "not working" even with
        // the correct Android layout. Desktop tab intentionally untouched.
        if (ucRaw != 0 && (ucRaw and android.view.KeyCharacterMap.COMBINING_ACCENT) != 0) {
            pendingDeadAccent = ucRaw and android.view.KeyCharacterMap.COMBINING_ACCENT_MASK
            return true
        }
        if (pendingDeadAccent != 0) {
            val accent = pendingDeadAccent
            pendingDeadAccent = 0
            val base = ucRaw
            if (base != 0) {
                val composed = android.view.KeyEvent.getDeadChar(accent, base)
                val out = if (composed != 0) String(Character.toChars(composed))
                else String(Character.toChars(accent)) + String(Character.toChars(base))
                val bytes = out.toByteArray()
                s.write(bytes)
                localEcho(bytes)
                return true
            }
            // non-printable follow-up (ESC/arrows/...): emit accent literally
            val ab = String(Character.toChars(accent)).toByteArray()
            s.write(ab)
            localEcho(ab)
            // fall through to the control-key handling below
        }
        val seq: String? = when (keyCode) {
            KeyEvent.KEYCODE_DEL -> "\u007F"
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "\r"
            KeyEvent.KEYCODE_TAB -> "\t"
            KeyEvent.KEYCODE_ESCAPE -> "\u001B"
            KeyEvent.KEYCODE_DPAD_UP -> if (applicationCursorKeys) "\u001BOA" else "\u001B[A"
            KeyEvent.KEYCODE_DPAD_DOWN -> if (applicationCursorKeys) "\u001BOB" else "\u001B[B"
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (applicationCursorKeys) "\u001BOC" else "\u001B[C"
            // v0.1.20 BUGFIX: the old "else" branch was
            //   "\u001BD"[0].toString() + "\u001B[D"  → ESC ESC [ D
            // a stray extra ESC before the CSI, which readline interprets as
            // Meta- prefix and then discards — left arrow appeared dead.
            KeyEvent.KEYCODE_DPAD_LEFT -> if (applicationCursorKeys) "\u001BOD" else "\u001B[D"
            else -> null
        }
        if (seq != null) {
            val bytes = seq.toByteArray()
            s.write(bytes)
            if (seq == "\r") localEcho(bytes)   // CR echoes as newline; arrows/ESC stay silent
            return true
        }
        if (event.unicodeChar != 0) {
            val bytes = String(Character.toChars(event.unicodeChar)).toByteArray()
            s.write(bytes)
            localEcho(bytes)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * v0.1.83: positional ES/EN physical-keyboard map for the TERMINAL tab
     * (Settings > Terminal physical keyboard layout). Android keycodes are
     * US-position-based; when the ROM never switches the external layout to
     * Spanish (field device), "es" remaps the symbol keys to their Spanish
     * ISO characters. Dead accents ('´', '`', '¨') are returned with the
     * COMBINING_ACCENT bit so the getDeadChar() composition above applies.
     * Letters/digits/Ctrl combos always pass through unchanged.
     */
    private fun layoutChar(keyCode: Int, event: KeyEvent, fallback: Int): Int {
        val kl = runCatching {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .getString("term_keylayout", "auto")
        }.getOrDefault("auto") ?: "auto"
        if (kl != "es" || event.isCtrlPressed) return fallback
        val sh = event.isShiftPressed
        val c: Char = when (keyCode) {
            KeyEvent.KEYCODE_GRAVE -> if (sh) 'ª' else 'º'
            KeyEvent.KEYCODE_2 -> if (sh) '"' else null
            KeyEvent.KEYCODE_3 -> if (sh) '·' else null
            KeyEvent.KEYCODE_6 -> if (sh) '&' else null
            KeyEvent.KEYCODE_7 -> if (sh) '/' else null
            KeyEvent.KEYCODE_8 -> if (sh) '(' else null
            KeyEvent.KEYCODE_9 -> if (sh) ')' else null
            KeyEvent.KEYCODE_0 -> if (sh) '=' else null
            KeyEvent.KEYCODE_MINUS -> if (sh) '?' else '\''
            KeyEvent.KEYCODE_EQUALS -> if (sh) '¿' else '¡'
            KeyEvent.KEYCODE_LEFT_BRACKET -> if (sh) '^' else '`'
            KeyEvent.KEYCODE_RIGHT_BRACKET -> if (sh) '*' else '+'
            KeyEvent.KEYCODE_BACKSLASH -> if (sh) 'Ç' else 'ç'
            KeyEvent.KEYCODE_SEMICOLON -> if (sh) 'Ñ' else 'ñ'
            KeyEvent.KEYCODE_APOSTROPHE -> if (sh) '¨' else '´'
            KeyEvent.KEYCODE_COMMA -> if (sh) ';' else ','
            KeyEvent.KEYCODE_PERIOD -> if (sh) ':' else '.'
            KeyEvent.KEYCODE_SLASH -> if (sh) '_' else '-'
            else -> null
        } ?: return fallback
        return if (c == '´' || c == '`' || c == '¨')
            c.code or android.view.KeyCharacterMap.COMBINING_ACCENT
        else c.code
    }

    /** v0.1.40: renderer state for field diagnostics (fed into the stream dbg line). */
    fun dbgState(): String =
        "cur=($curX,$curY) grid=${rows}x${cols} pty=${session?.ptyWrapped}" +
        " fed=$fedBytes/pumped=${session?.pumpedBytes ?: 0}"

    /** Extra-keys bar entry point (ESC, CTRL, ALT, arrows sent as raw bytes). */
    fun sendBytes(b: ByteArray) { session?.write(b) }

    /**
     * v0.1.31 DEGRADED-MODE ECHO ("cannot type" last-resort): with NO PTY wrapper
     * the shell's line discipline cannot echo keystrokes (raw pipes), so typing
     * was invisible even though bytes reached the shell. While the session runs
     * unwrapped we mirror printable bytes (plus CR→newline, DEL→backspace) onto
     * the screen ourselves. Skipped entirely when a real PTY is attached — the
     * tty driver echoes natively and double-echo would corrupt the line.
     */
    // v0.2.4 FIELD REQUEST: the degraded-mode visual erase used to be
    // UNBOUNDED - holding backspace wiped the `root@localhost:~#` prompt and
    // every line of earlier output ("make the prompt not deleteable, and
    // everything past it"). Count what the CURRENT input line echoed and
    // refuse DEL below that floor. With a real PTY this whole path is
    // skipped (the tty line discipline echoes and readline guards the
    // prompt), so PTY sessions keep their native behaviour.
    private var localLineLen = 0

    // v0.2.93 REMOTE-ECHO PROBE (field: "terminal types double - one keystroke
    // shows 2 chars but the shell reads 1"). localEcho exists for sessions with
    // no line discipline to echo for us, but the v0.2.80 bare-LF detector can
    // flip noLineDiscipline (and clear ptyWrapped) on a session whose tty
    // ECHOES fine - local echo then paints each keystroke NEXT TO the tty's own
    // echo: 2 glyphs on screen, 1 byte in the shell. The probe settles it
    // empirically instead of trusting flags: the printable bytes localEcho
    // displays are remembered; if the session feeds the SAME bytes back (the
    // tty echoing), TWO consecutive full matches PROVE the remote side echoes -
    // the matched copy is consumed (never rendered) and local echo turns off
    // for the rest of the session. Genuinely echo-less raw-pipe sessions never
    // match and keep the degraded-mode behaviour exactly as before.
    @Volatile private var remoteEcho = false
    private val echoLock = Any()
    private val echoSent = java.util.ArrayDeque<Byte>()
    private var echoSentAt = 0L
    private var echoSuspect = false

    private fun localEcho(bytes: ByteArray) {
        // v0.2.93: PROVEN remote echo outranks every heuristic - the tty echoes
        // by itself, painting these bytes here too would double every keystroke.
        if (remoteEcho) return
        // v0.2.80: local echo belongs to raw-pipe sessions; a session whose PTY
        // flag lied (dead guest wrapper) is a raw-pipe session in fact.
        if (session?.ptyWrapped != false && !noLineDiscipline) return
        val out = ByteArray(bytes.size * 3)
        var n = 0
        var plain = true   // v0.2.93: probe only when display == written bytes
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            when {
                // v0.1.44: visual erase (back-space, space, back-space) — a bare
                // cursor-left left the deleted glyph on screen ("cannot delete
                // the text I typed" in degraded mode).
                v == 0x7F -> { plain = false
                    if (localLineLen > 0) {
                        localLineLen--
                        out[n++] = 0x08; out[n++] = 0x20; out[n++] = 0x08
                    }
                }
                v == '\r'.code -> { plain = false; out[n++] = '\n'.code.toByte(); localLineLen = 0 }
                v >= 0x20 -> { out[n++] = v.toByte(); localLineLen++ }
            }
        }
        if (n > 0) {
            if (plain) synchronized(echoLock) {   // v0.2.93: arm the echo probe
                for (i in 0 until n) echoSent.addLast(out[i])
                echoSentAt = System.currentTimeMillis()
            }
            feedLocal(out.copyOf(n))
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // v0.1.20 BUGFIX: TYPE_TEXT_VARIATION_VISIBLE_PASSWORD is the standard
        // terminal trick (Termux uses it): every known soft keyboard disables
        // autocorrect, capitalization and word suggestions for it, so composed
        // text arrives verbatim instead of being silently "corrected". The old
        // flags still let some IMEs suggest-replace, duplicating keystrokes.
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT or
            EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : BaseInputConnection(this, false) {
            // v0.1.17 BUGFIX (keyboard could not type at all): the IME talks to
            // the input connection's EDITABLE TEXT buffer, not to commitText().
            // Gboard in particular writes typed characters through
            // setComposingText()/finishComposingText() and only calls commitText
            // for whole-word candidates. The old IC implemented neither composing
            // path nor a text buffer, so every keystroke went into a no-op —
            // exactly "keyboard cannot type on the interface". We now maintain a
            // small local buffer (so surrounding-text / backspace queries work),
            // flush committed+composing text to the PTY, and translate IME
            // enter/delete into real terminal bytes.
            // v0.1.20 BUGFIX (duplicated + lost characters): the old code kept a
            // buffer `tx`, appended to it in BOTH setComposingText and commitText
            // (so an IME that composes then commits sends the text twice), and
            // CLEARED tx on Enter (so un-flushed composing text vanished). Now
            // every character goes straight to the PTY immediately; `tx` only
            // tracks the current composing run so backspace can erase it.
            private val tx = StringBuilder()

            private fun writeToPty(text: CharSequence) {
                if (text.isEmpty()) return
                val bytes = text.toString().toByteArray(Charsets.UTF_8)
                session?.write(bytes)
                localEcho(bytes)
            }

            override fun commitText(text: CharSequence?, pos: Int): Boolean {
                // Composing runs are already on the PTY; committing NEW text means
                // send exactly what isn't already streamed.
                val t = text ?: ""
                val sent = if (tx.isNotEmpty() && t.startsWith(tx)) t.substring(tx.length) else t
                tx.setLength(0)
                writeToPty(sent)
                return true
            }

            override fun setComposingText(text: CharSequence?, pos: Int): Boolean {
                val t = text ?: ""
                // Stream only the DELTA vs the previous composing run; shrink → BS.
                if (t.length < tx.length) {
                    repeat(tx.length - t.length) { session?.write(byteArrayOf(0x7F)) }
                } else if (t.length > tx.length && t.startsWith(tx)) {
                    writeToPty(t.substring(tx.length))
                } else if (t != tx) {
                    // Whole-run replacement: erase ours, stream theirs.
                    repeat(tx.length) { session?.write(byteArrayOf(0x7F)) }
                    writeToPty(t)
                }
                tx.setLength(0); tx.append(t)
                return true
            }

            override fun finishComposingText(): Boolean { tx.setLength(0); return true }

            // v0.1.17 NOTE: endBatchedEdit() is API 31+ — overriding it
            // unconditionally fails compilation against the app's minSdk stubs,
            // so we don't; composing text is flushed on commit/finish anyway.

            override fun deleteSurroundingText(before: Int, after: Int): Boolean {
                // Characters are already ON the PTY, so backspace must ALWAYS be
                // forwarded — dropping it into the local buffer (old behaviour)
                // desynced the shell line from what we had typed.
                val n = if (tx.isNotEmpty()) { tx.setLength(tx.length - 1); 0 } else 0
                repeat((before.coerceAtLeast(1)) - n) {
                    val bs = byteArrayOf(0x7F)
                    session?.write(bs)
                    localEcho(bs)
                }
                return true
            }

            override fun sendKeyEvent(e: KeyEvent?): Boolean {
                if (e?.action == KeyEvent.ACTION_DOWN) {
                    if (e.keyCode == KeyEvent.KEYCODE_ENTER) {
                        // v0.1.20: NEVER discard tx here — the composing text is
                        // already streamed to the PTY; just reset tracking + CR.
                        tx.setLength(0)
                        val cr = byteArrayOf('\r'.code.toByte())
                        session?.write(cr)
                        localEcho(cr)
                        return true
                    }
                    if (e.keyCode == KeyEvent.KEYCODE_DEL) {
                        if (tx.isNotEmpty()) tx.setLength(tx.length - 1)
                        val bs = byteArrayOf(0x7F)
                        session?.write(bs)
                        localEcho(bs)
                        return true
                    }
                    onKeyDown(e.keyCode, e)
                }
                return true
            }

            // Honest (empty) editing state: the "document" is the live PTY stream.
            override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence =
                if (tx.length > 0) tx.substring(0, minOf(length, tx.length)) else ""
            override fun getTextAfterCursor(length: Int, flags: Int): CharSequence = ""
            override fun getSelectedText(flags: Int): CharSequence = ""
        }
    }

    companion object {
        const val BLINK_MS = 530L
        val DEFAULT_FG = Color.parseColor("#E8E8E8")
        val BG_COLOR = Color.parseColor("#101216")
        val CURSOR_COLOR = Color.parseColor("#5AA7FF")
        val PALETTE = intArrayOf(
            Color.parseColor("#000000"), Color.parseColor("#CC3333"), Color.parseColor("#33AA33"),
            Color.parseColor("#CCCC33"), Color.parseColor("#3355CC"), Color.parseColor("#CC33CC"),
            Color.parseColor("#33AAAA"), Color.parseColor("#CCCCCC"), Color.parseColor("#555555"),
            Color.parseColor("#FF5555"), Color.parseColor("#55FF55"), Color.parseColor("#FFFF55"),
            Color.parseColor("#5588FF"), Color.parseColor("#FF55FF"), Color.parseColor("#55FFFF"),
            Color.parseColor("#FFFFFF"),
        )
    }
}
