package com.emux.x11

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.util.TypedValue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Desktop surface for the X11 framebuffer.
 *
 * Rendering: single drawBitmap per frame into a letterboxed, user-zoomable
 * viewport using HARDWARE bitmaps → blit goes through RenderThread with zero
 * CPU pixel work. We only invalidate when a new frame id arrives (no vsync
 * chasing when idle).
 *
 * Input mapping (this is what makes touch feel native instead of VNC-ish):
 *  - tap            → button-1 press+release at mapped point
 *  - drag           → motion events coalesced to ≤30/s (radio/CPU friendly)
 *  - long-press     → button-3 (right-click), the classic mobile-X convention
 *  - two-finger     → pan/zoom the VIEWPORT only (does not touch X coords)
 *  - pinch-to-zoom clamped so one X px ≥ 0.25 screen px (no mushy aliasing)
 */
class DesktopView(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var desktop: DesktopService? = null

    // viewport transform: screen = (x - tx) * scale
    private var scale = 1f
    private var tx = 0f
    private var ty = 0f

    // v0.1.101 MOBILE: raw px text sizes were density-blind — 18px is
    // ~6sp on a 3x phone screen but ~12sp on a 2x tablet. Use sp.
    private fun sp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var lastFrameId = -1

    // v0.2.3 FIELD FIX: the frameless pane (the diagnostics surface users
    // actually screenshot) painted a FIXED last-16-lines window with no way
    // to move - "I can't move between the desktop errors, it doesn't let me
    // scroll". While no frames exist there is no X to inject touches into,
    // so a one-finger drag now scrolls the FULL history instead; clipping
    // keeps scrolled lines out of the status area. Reset when frames arrive.
    private var histScrollPx = 0f
    private var lastHistY = 0f
    private var histScrollable = false
    // v0.2.21 FIELD FIX: sticky-bottom history. The v0.2.3 scroll opened at
    // offset 0 - the TOP of the history. While content fit one screen that
    // was invisible; once the ring grew past a screen (field v0.2.20:
    // "this was all i could see" = pages of old selfcheck blocks), every
    // pane screenshot showed the OLDEST lines and all recent diagnostics
    // sat below the fold. Terminal-standard behaviour: pinned to newest
    // unless the user scrolls up; scrolling back to the bottom re-pins.
    private var histStickBottom = true
    private var histMaxScroll = 0f
    // v0.2.37: double-tap toggles letterbox-fit <-> fill-screen (cover).
    // Field: the old double-tap 2.5x zoom read as a bug ("enters full
    // screen but zooms in"); what was wanted is the no-bars view at
    // natural scale. Cover fills both axes (cropping overflow); two-
    // finger pan reaches cropped edges; pinch still zooms.
    private var coverMode = false
    private val frameless: Boolean get() = desktop?.currentFrame == null

    private val srcRect = Rect()
    private val dstRect = Rect()

    init {
        keepScreenOn = false /* activity decides; view never holds locks */
        // v0.2.44: the Desktop tab must accept keys/IME to type into X
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // v0.2.44 FIELD FIX: keyboard input for the desktop. Hardware keys and
    // the soft keyboard (via InputConnection) are translated to X keysyms
    // and injected through XTEST - before this, the Desktop tab silently
    // swallowed every keystroke ("the little terminal on the desktop
    // doesnt work").
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent): Boolean {
        val d = desktop
        if (d != null) {
            val ks = when (keyCode) {
                android.view.KeyEvent.KEYCODE_ENTER,
                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> 0xFF0D
                android.view.KeyEvent.KEYCODE_DEL -> 0xFF08
                android.view.KeyEvent.KEYCODE_FORWARD_DEL -> 0xFFFF
                android.view.KeyEvent.KEYCODE_TAB -> 0xFF09
                android.view.KeyEvent.KEYCODE_ESCAPE -> 0xFF1B
                android.view.KeyEvent.KEYCODE_DPAD_UP -> 0xFF52
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> 0xFF54
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> 0xFF51
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> 0xFF53
                android.view.KeyEvent.KEYCODE_PAGE_UP -> 0xFF55
                android.view.KeyEvent.KEYCODE_PAGE_DOWN -> 0xFF56
                android.view.KeyEvent.KEYCODE_MOVE_HOME -> 0xFF50
                android.view.KeyEvent.KEYCODE_MOVE_END -> 0xFF57
                else -> {
                    val uc = event.unicodeChar
                    if (uc != 0) { d.injectChar(uc.toChar()); return true }
                    return super.onKeyDown(keyCode, event)
                }
            }
            d.injectKeysymTap(ks)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onCreateInputConnection(
        outAttrs: android.view.inputmethod.EditorInfo,
    ): android.view.inputmethod.InputConnection {
        outAttrs.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : android.view.inputmethod.BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                text?.forEach { desktop?.injectChar(it) }
                return true
            }
            override fun deleteSurroundingText(before: Int, after: Int): Boolean {
                repeat(before) { desktop?.injectKeysymTap(0xFF08) }
                return true
            }
            override fun sendKeyEvent(event: android.view.KeyEvent): Boolean {
                if (event.action == android.view.KeyEvent.ACTION_DOWN) {
                    when {
                        event.keyCode == android.view.KeyEvent.KEYCODE_DEL ->
                            desktop?.injectKeysymTap(0xFF08)
                        event.keyCode == android.view.KeyEvent.KEYCODE_ENTER ->
                            desktop?.injectKeysymTap(0xFF0D)
                        event.unicodeChar != 0 ->
                            desktop?.injectChar(event.unicodeChar.toChar())
                    }
                }
                return true
            }
        }
    }

    // v0.1.25 DIAGNOSTIC: one-shot red flash so a permanently black pane can
    // be split into "view never draws" (no red at all -> layout/z-order/size)
    // vs "frames never arrive" (red appears, then dark background).
    @Volatile private var debugRedUntilMs = 0L
    fun debugFlashRed() {
        debugRedUntilMs = System.currentTimeMillis() + 1200
        postInvalidateOnAnimation()
        postDelayed({ postInvalidateOnAnimation() }, 1300)
    }

    // v0.2.33: host-side LIVE badge. A streaming but windowless desktop is
    // pure black - indistinguishable from death (field v0.2.31/32). Count
    // frame arrivals over a 2 s window and paint the rate on the bitmap so
    // "frames are flowing" is visible proof independent of guest windows.
    private val frameTimes = ArrayDeque<Long>()

    // v0.2.38: report the live view aspect so the next desktop start
    // sizes Xvfb to THIS pane (no letterbox bars by default).
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h > 0) desktop?.viewAspect = w.toFloat() / h.toFloat()
    }

    fun onNewFrame(frameId: Int) {
        if (frameId != lastFrameId) {
            lastFrameId = frameId
            histScrollPx = 0f          // v0.2.3: history scroll is frameless-only
            histStickBottom = true     // v0.2.21: next frameless visit starts live
            val now = System.currentTimeMillis()
            frameTimes.addLast(now)
            while (frameTimes.isNotEmpty() && now - frameTimes.first() > 2_000) frameTimes.removeFirst()
            postInvalidateOnAnimation()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (debugRedUntilMs > System.currentTimeMillis()) {
            canvas.drawColor(Color.RED); return
        }
        canvas.drawColor(Color.rgb(0x10, 0x14, 0x1A))
        val f = desktop?.currentFrame ?: run {
            // v0.1.25 DIAGNOSTIC: no frames yet — say so ON the pane instead of
            // showing an unexplained uniform dark rectangle.
            // v0.1.100: name the pipeline stage that has NOT happened
            // yet (X ready? capture running? frames: 0?) above the
            // "waiting" line — probe history alone cannot answer that.
            val pipe = Paint().apply { color = Color.argb(0xE0, 0xFF, 0xFF, 0xFF); textSize = sp(12f) }
            canvas.drawText((desktop?.pipelineStatusText() ?: "desktop: not started").take(190), 24f, height / 2f - 36f, pipe)
            val p = Paint().apply { color = Color.argb(0xB0, 0xFF, 0x8A, 0x65); textSize = sp(16f) }
            canvas.drawText("waiting for X frames…", 24f, height / 2f, p)
            // v0.1.41: paint the failure tail ON the pane too — the status
            // TextView is easy to miss off-screen, and these lines are the only
            // field-visible proof of WHY X/capture died (Xvfb log, apk, nc…).
            // v0.1.45: while waiting, also show the live capture/X log tail so
            // the pane is never silent (errorText only lands on terminal events).
            // v0.1.69 FIELD FIX: the pane canvas - the ONLY surface users
            // actually screenshot - painted errorText, i.e. the single newest
            // line. Every history/selfcheck/diagnostic improvement since
            // v0.1.55 landed in the status TextView or the history ring and
            // never appeared here. Paint the FULL history (start note,
            // selfcheck, DBSWAP, xvfb.log tails, errors) plus the live line.
            val err = desktop?.errorHistoryText()?.takeIf { it != "?" && it.isNotBlank() }
                ?: desktop?.diagTail()?.takeIf { it.isNotBlank() }
            if (!err.isNullOrBlank()) {
                // v0.1.90 (user request): color-code the pane - GREEN for
                // lines that prove health, RED for everything else.
                val pOk = Paint().apply { color = Color.argb(0xE0, 0x66, 0xBB, 0x6A); textSize = sp(11f) }
                val pBad = Paint().apply { color = Color.argb(0xE0, 0xEF, 0x53, 0x50); textSize = sp(11f) }
                // v0.2.3: FULL scrollable history (was: fixed takeLast(16)).
                // v0.2.26 FIELD FIX: NEWEST FIRST. Sticky-bottom still lost
                // to trackpad drags in the field - every screenshot kept
                // showing the session-top selfcheck blocks while the live
                // lines hid below. Reversing the order makes scroll offset 0
                // (the default, any drag state, any screenshot) show the
                // freshest diagnostics; scrolling walks back in time.
                val lines = err.lines().filter { it.isNotBlank() }.take(400).asReversed()
                val lineH = 24f
                val startY = height / 2f + 36f
                val avail = height - startY - sp(14f)
                val contentH = lines.size * lineH
                val maxScroll = max(0f, contentH - avail)
                histMaxScroll = maxScroll
                histScrollPx = histScrollPx.coerceIn(0f, maxScroll)   // 0 = newest
                histScrollable = maxScroll > 0f
                val first = (histScrollPx / lineH).toInt().coerceIn(0, max(0, lines.size - 1))
                val visible = (avail / lineH).toInt() + 2
                val last = min(lines.size, first + visible)
                canvas.save()
                canvas.clipRect(0, (startY - lineH).toInt(), width, height)
                var y = startY + (first * lineH - histScrollPx)
                for (i in first until last) {
                    canvas.drawText(lines[i].take(190), 24f, y,
                        if (DiagColors.healthy(lines[i])) pOk else pBad)
                    y += lineH
                }
                canvas.restore()
                if (histScrollable) {
                    val hint = Paint().apply { color = Color.argb(0x90, 0xFF, 0xFF, 0xFF); textSize = sp(10f) }
                    canvas.drawText(
                        "NEWEST first (lines ${first + 1}..$last of ${lines.size}) - drag to walk back in time",
                        24f, height - sp(6f), hint)
                }
            } else {
                histScrollable = false
            }
            // keep repainting while frameless so late errorText appears
            postInvalidateOnAnimation()
            return
        }
        // fit once, then honor user zoom/pan (v0.2.37: cover fills screen)
        val fitScale = if (coverMode) max(width.toFloat() / f.width, height.toFloat() / f.height)
                       else min(width.toFloat() / f.width, height.toFloat() / f.height)
        val s = fitScale * scale.coerceIn(1f, 6f)
        val w = f.width * s; val h = f.height * s
        val left = (width - w) / 2 + tx
        val top = (height - h) / 2 + ty
        srcRect.set(0, 0, f.width, f.height)
        dstRect.set(left.toInt(), top.toInt(), (left + w).toInt(), (top + h).toInt())
        canvas.drawBitmap(f, srcRect, dstRect, bmpPaint)
        // v0.2.33 LIVE badge (see onNewFrame)
        val fps = frameTimes.size / 2f
        if (fps > 0f) {
            val badge = Paint().apply { color = Color.argb(0xC0, 0x66, 0xBB, 0x6A); textSize = sp(10f) }
            canvas.drawText("LIVE %.1f fps".format(fps), 16f, sp(16f), badge)
        }
    }

    /** screen px → X px (clamped into server geometry). */
    private fun mapToX(sx: Float, sy: Float): Pair<Int, Int> {
        val f = desktop?.currentFrame ?: return 0 to 0
        val fitScale = if (coverMode) max(width.toFloat() / f.width, height.toFloat() / f.height)
                       else min(width.toFloat() / f.width, height.toFloat() / f.height)
        val s = fitScale * scale.coerceIn(1f, 6f)
        val w = f.width * s; val h = f.height * s
        val left = (width - w) / 2 + tx
        val top = (height - h) / 2 + ty
        val x = ((sx - left) / s).toInt().coerceIn(0, f.width - 1)
        val y = ((sy - top) / s).toInt().coerceIn(0, f.height - 1)
        return x to y
    }

    // ------------------------- gestures -------------------------
    private var downTime = 0L
    private var downX = 0f; private var downY = 0f
    private var dragging = false
    private var panning = false
    private var lastMotionSendTs = 0L
    private var longPressFired = false

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val d = detector
            d ?: return false
            scale = (scale * d.scaleFactor).coerceIn(1f, 6f)
            invalidate(); return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        val multiTouch = event.pointerCount > 1
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downTime = System.currentTimeMillis()
                downX = event.x; downY = event.y
                lastHistY = event.y
                dragging = false; panning = false; longPressFired = false
                removeCallbacks(longPressRunnable)
                // v0.2.3: no X server behind a frameless pane - a long-press
                // right-click there injects into nothing, so don't arm it.
                if (!frameless) postDelayed(longPressRunnable, 450)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                panning = true
                removeCallbacks(longPressRunnable)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (panning || multiTouch) {
                    // two-finger: pan viewport
                    if (event.historySize == 0 && event.pointerCount >= 2) {
                        // use centroid delta approximation
                        val cx = (event.getX(0) + event.getX(1)) / 2
                        val cy = (event.getY(0) + event.getY(1)) / 2
                        tx += cx - lastPanX; ty += cy - lastPanY
                        lastPanX = cx; lastPanY = cy
                        invalidate()
                    }
                    return true
                }
                val dx = event.x - downX; val dy = event.y - downY
                // v0.2.3: frameless = diagnostics surface; one-finger drag
                // scrolls the history instead of injecting motion into a
                // non-existent X server.
                if (frameless && !multiTouch) {
                    histScrollPx += lastHistY - event.y
                    lastHistY = event.y
                    histScrollPx = histScrollPx.coerceIn(0f, histMaxScroll)
                    invalidate()
                    return true
                }
                if (!dragging && (dx * dx + dy * dy) > 18 * 18) {
                    dragging = true
                    removeCallbacks(longPressRunnable)
                }
                if (dragging) {
                    val now = System.currentTimeMillis()
                    if (now - lastMotionSendTs > 33) {          // ≤30 motion events/s
                        lastMotionSendTs = now
                        val (x, y) = mapToX(event.x, event.y)
                        desktop?.injectMotion(x, y)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                // Double-tap zoom (see field above) — checked before
                // the click, so the zooming tap does not also click.
                val nowMs = System.currentTimeMillis()
                val isDoubleTap = !dragging && !panning && !longPressFired &&
                    nowMs - lastTapUpMs < 300 &&
                    abs(event.x - lastTapUpX) < 40 && abs(event.y - lastTapUpY) < 40
                lastTapUpMs = nowMs; lastTapUpX = event.x; lastTapUpY = event.y
                if (isDoubleTap) {
                    // v0.2.37: fill-screen toggle (was 2.5x zoom).
                    coverMode = !coverMode
                    scale = 1f; tx = 0f; ty = 0f
                    invalidate()
                    dragging = false; panning = false
                    return true
                }
                val (x, y) = mapToX(event.x, event.y)
                if (!longPressFired && !panning && !frameless) {
                    if (dragging) {
                        // releasing after drag: send button release at final pos
                        desktop?.fakeButtonReleaseOnly(x, y)
                    } else {
                        desktop?.injectClick(x, y, button = 1, press = true)
                        postDelayed({ desktop?.injectClick(x, y, button = 1, press = false) }, 40)
                        // v0.2.44: tapping the desktop focuses the view and
                        // raises the soft keyboard so typing reaches X.
                        requestFocus()
                        val imm = context.getSystemService(
                            android.view.inputmethod.InputMethodManager::class.java)
                        imm?.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                    }
                }
                dragging = false; panning = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable); dragging = false; panning = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private var lastPanX = 0f; private var lastPanY = 0f
    // v0.1.101 MOBILE: double-tap zoom. On a phone the fit-to-screen
    // desktop makes X controls (titlebar buttons, menus) a few px
    // wide and untappable; pinch exists but is undiscoverable and
    // awkward one-handed. Double-tap toggles fit <-> 2.5x centred on
    // the tap; a second double-tap returns to fit. Trade-off: the
    // second tap of a double-tap is NOT sent as an X click (the first
    // tap already was), so X double-click is sacrificed for zoom.
    private var lastTapUpMs = 0L
    private var lastTapUpX = 0f
    private var lastTapUpY = 0f

    private val longPressRunnable = Runnable {
        if (!dragging && !panning) {
            longPressFired = true
            val (x, y) = mapToX(downX, downY)
            desktop?.injectClick(x, y, button = 3, press = true)
            postDelayed({ desktop?.injectClick(x, y, button = 3, press = false) }, 60)
        }
    }
}

/**
 * v0.1.90: pane line health classifier (user request: green = going right,
 * red = everything else). Shared by DesktopView canvas and the status
 * TextView so both surfaces agree.
 */
object DiagColors {
    private val OK = Regex(
        "LINK_OK|SHIM_LOADED|SHIMTEST_[A-Z_]*OK|BIND[A-Z]*_0\\b|B\\d+_OK|rc=0\\b|rc=124|" +
        "unixpath=true|tcp6000=true|abs=true|port6000 state at session start: free|" +
        "emux-linkfix-v\\d|apt-stub: installed|ghost package|purged|dpkgdir u=0|" +
        "status u=0 m=|== emux dpkg selfcheck|total \\d|-rw-.*linkfix|" +
        "^/usr/local/lib/emux|^0$|shim=true preload=true|terminal keyboard layout: es|X up=true tools=true|" +
        "first grab ok|push channel open|X reachable|converter=native|copied |pasted ")
    fun healthy(line: String): Boolean = OK.containsMatchIn(line)
    fun green(): Int = 0xFF66BB6A.toInt()
    fun red(): Int = 0xFFEF5350.toInt()
}
