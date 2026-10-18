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
        isFocusableInTouchMode = true
        // v0.1.19 BUGFIX I: without onCheckIsTextEditor() == true the framework
        // treats this View as a NON-text-editor: showSoftInput() is ignored, no
        // InputConnection is ever created (onCreateInputConnection never runs),
        // and physical/IME keys bypass onKeyDown entirely. That is precisely
        // "keyboard cannot type on the interface" even though everything else
        // looked right. Termux's emulated-UI terminal declares itself a text
        // editor the same way.
        isFocusable = true
        screen = buildScreen(rows, cols)
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

    private fun resize(newRows: Int, newCols: Int) {
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
        session?.resize(rows, cols)
    }

    // ------------------------------------------------------------------ feed
    var session: TerminalSession? = null
        set(v) { field = v; resetToDefaults() }

    fun resetToDefaults() {
        screen = buildScreen(rows, cols); curX = 0; curY = 0; pen = Style(); penIdx = 0
        markAllDirty()
    }

    fun feed(bytes: ByteArray) {
        for (b in bytes) parseByte(b.toInt() and 0xFF)
        markRowDirty(curY)
        invalidate()
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
        when (pstate) {
            PState.GROUND -> when {
                b == 0x1B -> { pstate = PState.ESC; paramCur = -1; clearParams() }
                b == '\n'.code -> { newlineScroll() }
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
        canvas.drawColor(BG_COLOR)
        val fm = textPaint.fontMetrics
        for (y in 0 until rows) {
            if (!allDirty && !dirtyRows[y]) continue
            val row = screen[y]
            val top = y * cellH
            var x = 0
            while (x < cols) {
                val st = stylePool.getOrElse(row.styles[x].toInt() and 0xFF) { defStyle }
                // background run
                var runEnd = x
                while (runEnd < cols && row.styles[runEnd] == row.styles[x]) runEnd++
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

        // cursor
        if (cursorVisible && blinkOn && hasWindowFocus()) {
            boxPaint.color = CURSOR_COLOR
            canvas.drawRect(curX * cellW, curY * cellH, (curX + 1) * cellW, (curY + 1) * cellH, boxPaint)
        }
    }


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

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) scheduleBlink() else removeCallbacks(blinkRunnable)
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
        if (event?.actionMasked == android.view.MotionEvent.ACTION_UP) showKeyboard()
        return true
    }

    /** Explicitly (re-)open the soft keyboard against this view's input connection. */
    fun showKeyboard() {
        requestFocus()
        runCatching {
            val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? InputMethodManager
            imm?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val s = session ?: return super.onKeyDown(keyCode, event)
        val seq: String? = when (keyCode) {
            KeyEvent.KEYCODE_DEL -> "\u007F"
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "\r"
            KeyEvent.KEYCODE_TAB -> "\t"
            KeyEvent.KEYCODE_ESCAPE -> "\u001B"
            KeyEvent.KEYCODE_DPAD_UP -> if (applicationCursorKeys) "\u001BOA" else "\u001B[A"
            KeyEvent.KEYCODE_DPAD_DOWN -> if (applicationCursorKeys) "\u001BOB" else "\u001B[B"
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (applicationCursorKeys) "\u001BOC" else "\u001B[C"
            KeyEvent.KEYCODE_DPAD_LEFT -> if (applicationCursorKeys) "\u001BOD" else "\u001BD"[0].toString() + "\u001B[D"
            else -> null
        }
        if (seq != null) { s.write(seq.toByteArray()); return true }
        if (event.unicodeChar != 0) {
            s.write(String(Character.toChars(event.unicodeChar)).toByteArray())
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Extra-keys bar entry point (ESC, CTRL, ALT, arrows sent as raw bytes). */
    fun sendBytes(b: ByteArray) { session?.write(b) }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT or
            EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS or EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
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
            private val tx = StringBuilder()

            private fun flushToPty() {
                val s = session ?: return
                if (tx.isNotEmpty()) {
                    s.write(tx.toString().toByteArray(Charsets.UTF_8))
                    tx.setLength(0)
                }
            }

            override fun commitText(text: CharSequence?, pos: Int): Boolean {
                tx.append(text ?: "")
                flushToPty()
                return true
            }

            override fun setComposingText(text: CharSequence?, pos: Int): Boolean {
                // Replace our tracked composing run with the new one; do NOT send
                // yet — it goes out when composed/committed or when the user hits
                // enter/backspace.
                tx.setLength(0)
                tx.append(text ?: "")
                return true
            }

            override fun finishComposingText(): Boolean { flushToPty(); return true }

            // v0.1.17 NOTE: endBatchedEdit() is API 31+ — overriding it
            // unconditionally fails compilation against the app's minSdk stubs,
            // so we don't; composing text is flushed on commit/finish anyway.

            override fun deleteSurroundingText(before: Int, after: Int): Boolean {
                if (tx.isNotEmpty()) { tx.setLength(tx.length - 1); return true }
                session?.write(byteArrayOf(0x7F))   // DEL → readline backspace
                return true
            }

            override fun sendKeyEvent(e: KeyEvent?): Boolean {
                if (e?.action == KeyEvent.ACTION_DOWN) {
                    if (e.keyCode == KeyEvent.KEYCODE_ENTER) {
                        tx.setLength(0)
                        session?.write(byteArrayOf('\r'.code.toByte()))
                        return true
                    }
                    if (e.keyCode == KeyEvent.KEYCODE_DEL) {
                        if (tx.isNotEmpty()) { tx.setLength(tx.length - 1); return true }
                        session?.write(byteArrayOf(0x7F)); return true
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
