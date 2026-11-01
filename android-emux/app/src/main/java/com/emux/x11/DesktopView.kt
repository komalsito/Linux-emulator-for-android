package com.emux.x11

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
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
 *  - tap             → button-1 press+release at the mapped point
 *  - drag            → motion events coalesced to ≤30/s (radio/CPU friendly)
 *  - long-press      → button-3 DOWN and HELD, so long-press-then-drag is a
 *                      right-drag; released where the finger lifts
 *  - two-finger tap  → button-2 (middle click) — the only practical way to
 *                      paste into an X terminal
 *  - two-finger drag → X scroll wheel (4/5 vertical, 6/7 horizontal) at
 *                      fit-to-screen; VIEWPORT pan once zoomed, where panning
 *                      is what you actually need
 *  - pinch-to-zoom   → viewport only, clamped so one X px ≥ 0.25 screen px
 *  - double-tap      → toggle letterbox-fit ↔ fill-screen
 *  - "keys" toggle   → on-screen Esc/Tab/Ctrl/Alt/Super/BackSpace + wheel.
 *                      Ctrl/Alt/Super are STICKY: arm one and the next key from
 *                      the soft keyboard becomes that shortcut (Ctrl+C).
 *
 * Discoverability (v0.2.61): the bar is hidden behind that toggle, so on the
 * FIRST desktop session after install the bar opens itself and a gesture card
 * fades in over the live desktop for a few seconds. Any touch dismisses the
 * card; the user's own bar choice is remembered from then on.
 */
class DesktopView(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var desktop: DesktopService? = null

    // viewport transform: screen = (x - tx) * scale
    private var scale = 1f
    // v0.2.58: one-shot first-tap mapping note (evidence round)
    private var mapNotePosted = false
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
                    if (uc != 0) {
                        // Ctrl/Alt from the helper bar apply to hardware keys
                        // too, so Ctrl+C works without a physical keyboard.
                        if (stickyCtrl || stickyAlt || stickyMeta) {
                            d.injectKeysymCombo(keysymFor(uc.toChar()), stickyCtrl, stickyAlt, stickyMeta)
                            clearSticky()
                        } else d.injectChar(uc.toChar())
                        return true
                    }
                    return super.onKeyDown(keyCode, event)
                }
            }
            sendKeysym(ks)
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
                // v0.2.59: one call, not one-per-character. Over the FIFO path
                // each character used to cost a whole xdotool process under
                // proot, which made the soft keyboard unusable.
                text?.let { t ->
                    val d = desktop
                    if (d != null && (stickyCtrl || stickyAlt || stickyMeta)) {
                        // A sticky modifier turns the commit into a shortcut:
                        // tap Ctrl on the helper bar, type "c", get Ctrl+C.
                        // Sent per character because xdotool/XTEST combos are
                        // keysym-based, not text-based.
                        for (ch in t) d.injectKeysymCombo(keysymFor(ch), stickyCtrl, stickyAlt, stickyMeta)
                        clearSticky()
                    } else {
                        d?.injectText(t.toString())
                    }
                }
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
                            sendKeysym(0xFF08)
                        event.keyCode == android.view.KeyEvent.KEYCODE_ENTER ->
                            sendKeysym(0xFF0D)
                        event.unicodeChar != 0 -> {
                            val uc = event.unicodeChar
                            if (stickyCtrl || stickyAlt || stickyMeta) {
                                desktop?.injectKeysymCombo(keysymFor(uc.toChar()), stickyCtrl, stickyAlt, stickyMeta)
                                clearSticky()
                            } else desktop?.injectChar(uc.toChar())
                        }
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
    // v0.2.68: wall-clock of the last frame that actually arrived. The fps
    // badge used to freeze at its last value when capture died (frameTimes is
    // only trimmed on NEW frames), so a dead stream kept advertising itself
    // as LIVE over a frozen photograph forever.
    private var lastFrameMs = 0L

    // v0.2.38: report the live view aspect so the next desktop start
    // sizes Xvfb to THIS pane (no letterbox bars by default).
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h > 0) desktop?.viewAspect = w.toFloat() / h.toFloat()
    }

    fun onNewFrame(frameId: Int) {
        if (frameId != lastFrameId) {
            lastFrameId = frameId
            // v0.2.66: Xvfb parks the pointer at the screen centre, so start the
            // drawn cursor there - the pane must never be cursor-less again.
            if (cursorX < 0f) {
                val f = desktop?.currentFrame
                if (f != null) { cursorX = f.width / 2f; cursorY = f.height / 2f }
            }
            // v0.2.61: the intro must key off the FIRST FRAME, not off view
            // construction - before that there is no desktop to explain.
            if (!introChecked) { introChecked = true; maybeShowIntro() }
            histScrollPx = 0f          // v0.2.3: history scroll is frameless-only
            histStickBottom = true     // v0.2.21: next frameless visit starts live
            val now = System.currentTimeMillis()
            frameTimes.addLast(now)
            lastFrameMs = now
            while (frameTimes.isNotEmpty() && now - frameTimes.first() > 2_000) frameTimes.removeFirst()
            postInvalidateOnAnimation()
        }
    }

    /**
     * v0.2.62 CRASH FIX: onDraw is a View callback — it runs on the main looper
     * OUTSIDE every guard this app has (MainActivity's v0.1.3 catch(Throwable)
     * covers only doOnCreate; postSafely's v0.1.8 catch covers only posted
     * Runnables). A throw here is the system "EmuX keeps stopping" dialog, on
     * every frame, i.e. an unopenable app. This pane also gained real drawing code
     * in v0.2.60/61 (intro card, helper bar, Chip, fadeTick) that had never run on
     * a device, so it is the most likely such site. Degrade to a dark pane with a
     * one-line note: a blank desktop is recoverable, a dead process is not.
     */
    override fun onDraw(canvas: Canvas) {
        try {
            drawPane(canvas)
        } catch (e: Throwable) {
            runCatching { com.emux.EmuXLog.e("DesktopView.onDraw failed", e) }
            runCatching {
                canvas.drawColor(Color.rgb(0x10, 0x14, 0x1A))
                val p = Paint().apply {
                    color = Color.argb(0xE0, 0xEF, 0x53, 0x50); textSize = sp(13f)
                }
                canvas.drawText(
                    "desktop pane failed to draw: ${e.javaClass.simpleName}: ${e.message}".take(150),
                    24f, height / 2f, p)
            }
            // Stop the fade ticker so a broken draw cannot re-throw every frame.
            runCatching { removeCallbacks(fadeTick); hintUntilMs = 0L }
        }
    }

    private fun drawPane(canvas: Canvas) {
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
        // v0.2.66: the synthetic pointer, above the frame and below the badges
        drawCursor(canvas)
        // v0.2.33 LIVE badge (see onNewFrame)
        val fps = frameTimes.size / 2f
        if (fps > 0f) {
            // v0.2.68 FIX (input-dead audit, round 3): a capture helper that
            // dies AFTER the first frame used to leave this badge frozen at
            // "LIVE 0.5 fps" over the last bitmap FOREVER (frameTimes is only
            // trimmed when a new frame arrives, and nothing repainted). The
            // pane was a photograph that claimed to be alive - which is why
            // "desktop shows but nothing opens" survived ten versions even
            // with a healthy input path. Age the badge: >8 s without a frame
            // is STALE, in red, ticking, and repainting itself.
            val ageMs = System.currentTimeMillis() - lastFrameMs
            if (ageMs > 8_000) {
                val stale = Paint().apply { color = Color.argb(0xE0, 0xEF, 0x53, 0x50); textSize = sp(10f) }
                canvas.drawText(
                    "STALE ${ageMs / 1000}s - no frames arriving (capture dead? respawning) " +
                        // v0.2.72: and NAME the capture failure on the pane —
                        // the wire-level rejection (BadLength=16 on v0.2.71's
                        // GetImage, timeout, bad depth...) or "silent", so a
                        // screenshot ends the archaeology instead of starting it.
                        "capage=${desktop?.capturePubAgeSec ?: -1}s " +
                        "nat=${(desktop?.nativeErr ?: "").ifBlank { "silent" }.take(22)}",
                    16f, sp(16f), stale)
                // v0.2.70: name the killer ON the pane - the capture's own
                // error tail, so the next field screenshot ends the archaeology.
                val ce = (desktop?.lastCapErr ?: "").takeIf { it.isNotBlank() }
                if (ce != null) {
                    val cep = Paint().apply { color = Color.argb(0xE0, 0xEF, 0x53, 0x50); textSize = sp(9f) }
                    canvas.drawText("cap.err: $ce", 16f, sp(84f), cep)
                }
                postInvalidateOnAnimation()   // keep ticking while stale
            } else {
                val badge = Paint().apply { color = Color.argb(0xC0, 0x66, 0xBB, 0x6A); textSize = sp(10f) }
                canvas.drawText("LIVE %.1f fps".format(fps), 16f, sp(16f), badge)
            }
            // v0.2.53: input health ON the bitmap - the history TextView is
            // invisible once frames flow, and "clicks do nothing" needed an
            // always-visible answer: INPUT: agent = FIFO writer connected.
            val tr = desktop?.inputTransport ?: "down"
            val up = tr != "down"
            val ib = Paint().apply { color = if (up) Color.argb(0xC0, 0x66, 0xBB, 0x6A) else Color.argb(0xC0, 0xEF, 0x53, 0x50); textSize = sp(10f) }
            canvas.drawText(
                when (tr) {
                    "xtest" -> "INPUT: XTEST (control plane)"
                    "agent" -> "INPUT: guest xdotool agent (slow fallback)"
                    else    -> "INPUT: down - no XTEST, no agent"
                }, 16f, sp(28f), ib)
            // v0.2.65: the INPUT PROOF line, painted on the framebuffer itself
            // so it survives in any screenshot the user can take:
            //   touch= ACTION_DOWNs that reached this view (finger leg)
            //   inj=   injection calls the service actually made (app leg)
            //   proof= the SERVER's verdict via a QueryPointer round-trip
            //   agent= the guest agent's own forensic markers
            // One line, four legs, no adb required.
            val proof = desktop?.inputProof ?: "pending"
            val pb = Paint().apply {
                color = when {
                    proof == "ok" -> Color.argb(0xC0, 0x66, 0xBB, 0x6A)
                    proof == "pending" -> Color.argb(0xC0, 0xFF, 0xCA, 0x63)
                    else -> Color.argb(0xC0, 0xEF, 0x53, 0x50)
                }
                textSize = sp(10f)
            }
            // v0.2.67 FIX (input-dead audit, round 2): the line gains three
            // fields that name the two failure classes a connect-time proof
            // cannot see: fail= injection writes that THREW (a control plane
            // whose socket died mid-session keeps counting inj= while every
            // write fails), ctrl=/cap= the display each plane is actually on
            // (a split means input goes to a desktop nobody sees).
            canvas.drawText(
                "proof=$proof touch=${desktop?.touchCount ?: 0} inj=${desktop?.injectCount ?: 0} " +
                    "fail=${desktop?.injectFail ?: 0} ctrl=:${desktop?.controlDisplay ?: -1} " +
                    "cap=:${desktop?.captureDisplay ?: -1} capage=${desktop?.capturePubAgeSec ?: -1}s " +
                    // v0.2.71: name the frame SOURCE on the badge — a screenshot
                    // must show whether pixels come from the in-app XGetImage
                    // loop (src=native) or the parked guest pipeline (src=-).
                    "src=${desktop?.captureMode ?: "-"} " +
                    (desktop?.agentEvidence() ?: ""), 16f, sp(40f), pb)
            drawGestureHint(canvas)
        }
        // v0.2.60: on-screen Esc/Tab/Ctrl/Alt/Super + wheel, drawn last so it
        // sits above the framebuffer.
        drawOverlay(canvas)
        // v0.2.61: one-shot gesture card, on top of even the overlay.
        drawIntroCard(canvas)
    }

    /** screen px → X px (clamped into server geometry). */
    private fun mapToX(sx: Float, sy: Float, markCursor: Boolean = true): Pair<Int, Int> {
        val f = desktop?.currentFrame ?: return 0 to 0
        val fitScale = if (coverMode) max(width.toFloat() / f.width, height.toFloat() / f.height)
                       else min(width.toFloat() / f.width, height.toFloat() / f.height)
        val s = fitScale * scale.coerceIn(1f, 6f)
        val w = f.width * s; val h = f.height * s
        val left = (width - w) / 2 + tx
        val top = (height - h) / 2 + ty
        val x = ((sx - left) / s).toInt().coerceIn(0, f.width - 1)
        val y = ((sy - top) / s).toInt().coerceIn(0, f.height - 1)
        // v0.2.66: remember where the X pointer now is, so the pane can DRAW a
        // cursor. A captured frame can never contain the real one: the X
        // server's sprite layer removes the cursor from every GetImage, which
        // is why the desktop has been cursor-less on device and every tap had
        // no visible consequence at all.
        if (markCursor) { cursorX = x.toFloat(); cursorY = y.toFloat() }
        return x to y
    }

    /** X px → view px, the exact inverse of [mapToX] (for the drawn cursor). */
    private fun xToView(x: Float, y: Float): Pair<Float, Float> {
        val f = desktop?.currentFrame ?: return -1f to -1f
        val fitScale = if (coverMode) max(width.toFloat() / f.width, height.toFloat() / f.height)
                       else min(width.toFloat() / f.width, height.toFloat() / f.height)
        val s = fitScale * scale.coerceIn(1f, 6f)
        val w = f.width * s; val h = f.height * s
        val left = (width - w) / 2 + tx
        val top = (height - h) / 2 + ty
        return left + x * s to top + y * s
    }

    /**
     * v0.2.66: draw the pointer. The capture pipeline cannot show the X cursor
     * (the server strips the sprite from every GetImage), so without this the
     * user gets no feedback at all that a tap landed - which read as "input is
     * dead" even when the proof said ok. Trackpad/mouse hover moves it without
     * clicking (see onHoverEvent).
     */
    private var cursorX = -1f
    private var cursorY = -1f
    private val cursorPath = android.graphics.Path()
    private fun drawCursor(canvas: Canvas) {
        if (cursorX < 0f || cursorY < 0f) return
        val (vx, vy) = xToView(cursorX, cursorY)
        if (vx < -40f || vy < -40f || vx > width + 40f || vy > height + 40f) return
        val u = dp(1.2f)                       // one "cursor pixel" in view px
        cursorPath.reset()
        cursorPath.moveTo(vx, vy)
        cursorPath.lineTo(vx, vy + 14 * u)
        cursorPath.lineTo(vx + 3.6f * u, vy + 10.6f * u)
        cursorPath.lineTo(vx + 6.2f * u, vy + 15.4f * u)
        cursorPath.lineTo(vx + 8.4f * u, vy + 14.2f * u)
        cursorPath.lineTo(vx + 5.8f * u, vy + 9.6f * u)
        cursorPath.lineTo(vx + 10.4f * u, vy + 9.6f * u)
        cursorPath.close()
        canvas.drawPath(cursorPath, Paint().apply {
            style = Paint.Style.FILL; color = Color.WHITE; isAntiAlias = true
        })
        canvas.drawPath(cursorPath, Paint().apply {
            style = Paint.Style.STROKE; strokeWidth = 1.4f * u
            color = Color.argb(0xE0, 0x10, 0x14, 0x1A); isAntiAlias = true
        })
    }

    /** Trackpad / mouse hover: move the drawn cursor, never inject a click. */
    override fun onHoverEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_HOVER_MOVE ||
            event.actionMasked == android.view.MotionEvent.ACTION_HOVER_ENTER) {
            mapToX(event.x, event.y)
            invalidate()
            return true
        }
        return super.onHoverEvent(event)
    }

    // ------------------------- gestures -------------------------
    // v0.2.60 gesture set. Everything here is reachable with one or two
    // fingers on a phone, and nothing requires a hardware keyboard:
    //   tap                     -> button 1 click
    //   drag                    -> X motion with button 1 semantics (window drag)
    //   long-press              -> button 3 DOWN, held; drag it for a right-drag,
    //                              released where the finger lifts
    //   two-finger tap          -> button 2 (middle) - the only practical way to
    //                              paste into an X terminal
    //   two-finger drag         -> X scroll wheel (buttons 4/5 vertical, 6/7
    //                              horizontal) while at fit-to-screen; viewport
    //                              pan while zoomed, where panning is the thing
    //                              you actually need
    //   pinch                   -> zoom the viewport (unchanged)
    //   double-tap              -> toggle letterbox-fit <-> fill-screen
    //   helper bar (toggle "keys")-> Esc/Tab/Ctrl/Alt/Super/BackSpace + wheel, for
    //                              the keys a soft keyboard does not have
    private var downTime = 0L
    private var downX = 0f; private var downY = 0f
    private var dragging = false
    private var panning = false            // >=2 fingers down: suppresses tap->click
    private var lastMotionSendTs = 0L
    private var longPressFired = false
    private var rightHeld = false          // long-press armed button 3 and has not released it

    /** True while the in-progress gesture started on an overlay control. */
    private var overlayDown = false
    private var twoFingerMoved = false
    private var twoFingerDownTs = 0L
    private var twoFingerX = 0f; private var twoFingerY = 0f
    private var twoFingerStartX = 0f; private var twoFingerStartY = 0f
    private var scrollAccumY = 0f; private var scrollAccumX = 0f
    private var lastPanX = 0f; private var lastPanY = 0f

    // Sticky modifiers: tap Ctrl on the helper bar, then type 'c' on the soft
    // keyboard and get Ctrl+C. Cleared by the next key that consumes them.
    private var stickyCtrl = false
    private var stickyAlt = false
    private var stickyMeta = false
    private var helperBarVisible = false

    // v0.2.61 DISCOVERABILITY. Two things made the new input model hard to
    // find: the gesture cheat-sheet was drawn only on the FRAMELESS pane, so it
    // vanished at the exact moment the desktop appeared and the user could act
    // on it, and the helper bar sat behind a "keys" toggle nobody knows to look
    // for. Now, once per install, the first frame that arrives opens the helper
    // bar and shows a fading card OVER the live desktop; any touch dismisses it.
    // From then on the user's own choice of bar visibility is remembered.
    // getSharedPreferences can throw on some OEM ROMs (see BootstrapManager),
    // so every access is guarded and the intro simply never shows if it does.
    private val uiPrefs
        get() = runCatching {
            context.getSharedPreferences("emux_desktop_ui", Context.MODE_PRIVATE)
        }.getOrNull()
    private var introChecked = false
    private var hintUntilMs = 0L

    private val fadeTick = object : Runnable {
        override fun run() {
            if (hintUntilMs <= System.currentTimeMillis()) return
            invalidate()
            postDelayed(this, FADE_TICK_MS)
        }
    }

    /** Runs on the first frame: opens the bar + shows the card, once ever. */
    private fun maybeShowIntro() {
        val p = uiPrefs
        val seen = runCatching { p?.getBoolean(KEY_INTRO_SEEN, false) ?: false }.getOrDefault(false)
        if (seen) {
            helperBarVisible = runCatching { p?.getBoolean(KEY_BAR_VISIBLE, false) ?: false }
                .getOrDefault(false)
            return
        }
        helperBarVisible = true
        hintUntilMs = System.currentTimeMillis() + HINT_MS
        runCatching {
            p?.edit()?.putBoolean(KEY_INTRO_SEEN, true)
                ?.putBoolean(KEY_BAR_VISIBLE, true)?.apply()
        }
        removeCallbacks(fadeTick)
        postDelayed(fadeTick, FADE_TICK_MS)
    }

    private fun persistBarVisibility() {
        runCatching { uiPrefs?.edit()?.putBoolean(KEY_BAR_VISIBLE, helperBarVisible)?.apply() }
    }

    private fun dismissIntroCard() {
        if (hintUntilMs == 0L) return
        hintUntilMs = 0L
        removeCallbacks(fadeTick)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(fadeTick)
    }

    private fun dp(v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    /** True when the viewport is zoomed/cropped, so two fingers must pan. */
    private fun zoomed() = scale > 1.01f || coverMode

    private fun centroidX(e: MotionEvent): Float {
        var t = 0f; for (i in 0 until e.pointerCount) t += e.getX(i); return t / e.pointerCount
    }
    private fun centroidY(e: MotionEvent): Float {
        var t = 0f; for (i in 0 until e.pointerCount) t += e.getY(i); return t / e.pointerCount
    }

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            scale = (scale * detector.scaleFactor).coerceIn(1f, 6f)
            invalidate(); return true
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        val multiTouch = event.pointerCount > 1
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // v0.2.61: the intro card is dismissed by the first touch, so
                // it can never sit between the user and the desktop.
                dismissIntroCard()
                // v0.2.65: proof that touches reach this view at all. Field
                // v0.2.64 could not distinguish "the server ignores injection"
                // from "the view never sees the finger"; the pane badge prints
                // this counter next to the injection counter so one screenshot
                // separates the two.
                desktop?.noteTouch()
                // Consume the whole gesture here. Hit-testing on ACTION_UP as
                // well would fire every chip twice - a Ctrl tap would toggle
                // itself back off and Esc would be sent twice.
                overlayDown = onOverlayTouch(event.x, event.y)
                if (overlayDown) return true
                downTime = System.currentTimeMillis()
                downX = event.x; downY = event.y
                lastHistY = event.y
                dragging = false; panning = false; longPressFired = false
                twoFingerMoved = false
                removeCallbacks(longPressRunnable)
                // v0.2.3: no X server behind a frameless pane - a long-press
                // right-click there injects into nothing, so don't arm it.
                if (!frameless) postDelayed(longPressRunnable, LONG_PRESS_MS)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                panning = true
                removeCallbacks(longPressRunnable)
                // A second finger ends any right-button hold, otherwise the
                // button stays down with nothing left to release it.
                releaseRightButton(event)
                twoFingerDownTs = System.currentTimeMillis()
                twoFingerMoved = false
                scrollAccumY = 0f; scrollAccumX = 0f
                val cx = centroidX(event); val cy = centroidY(event)
                twoFingerStartX = cx; twoFingerStartY = cy
                twoFingerX = cx; twoFingerY = cy
                lastPanX = cx; lastPanY = cy
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (panning || multiTouch) {
                    if (event.pointerCount < 2) return true
                    val cx = centroidX(event); val cy = centroidY(event)
                    val dx = cx - twoFingerX; val dy = cy - twoFingerY
                    if (abs(cx - twoFingerStartX) > TWO_FINGER_TAP_SLOP ||
                        abs(cy - twoFingerStartY) > TWO_FINGER_TAP_SLOP) twoFingerMoved = true
                    twoFingerX = cx; twoFingerY = cy
                    if (zoomed()) {
                        tx += cx - lastPanX; ty += cy - lastPanY
                        lastPanX = cx; lastPanY = cy
                        invalidate()
                    } else if (twoFingerMoved && !frameless) {
                        // At fit-to-screen there is nothing to pan, so the same
                        // gesture drives the X scroll wheel. Wheel events are
                        // accumulated and quantised: one click per
                        // SCROLL_STEP_PX of travel, so a slow drag does not
                        // machine-gun the server.
                        val (x, y) = mapToX(cx, cy)
                        scrollAccumY += dy; scrollAccumX += dx
                        var n = (scrollAccumY / SCROLL_STEP_PX).toInt()
                        if (n != 0) {
                            scrollAccumY -= n * SCROLL_STEP_PX
                            // fingers travel DOWN (dy>0) => content scrolls down => button 5
                            desktop?.injectScroll(x, y, if (n > 0) 5 else 4, abs(n))
                        }
                        n = (scrollAccumX / SCROLL_STEP_PX).toInt()
                        if (n != 0) {
                            scrollAccumX -= n * SCROLL_STEP_PX
                            desktop?.injectScroll(x, y, if (n > 0) 7 else 6, abs(n))
                        }
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
                    if (now - lastMotionSendTs > 33) {          // <=30 motion events/s
                        lastMotionSendTs = now
                        val (x, y) = mapToX(event.x, event.y)
                        // While a long-press holds button 3 this is a right-drag.
                        desktop?.injectMotion(x, y)
                    }
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                // Two-finger TAP = middle click: little travel, short window.
                if (!twoFingerMoved && !frameless &&
                    System.currentTimeMillis() - twoFingerDownTs < TWO_FINGER_TAP_MS) {
                    val i = event.actionIndex
                    val (x, y) = mapToX(event.getX(i), event.getY(i))
                    desktop?.injectClick(x, y, button = 2, press = true)
                    postDelayed({ desktop?.injectClick(x, y, button = 2, press = false) }, 45)
                    requestFocus()
                }
                // Whatever the remaining finger does next must not become a
                // tap->click of its own.
                twoFingerMoved = true
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                if (rightHeld) {
                    releaseRightButton(event)
                    dragging = false; panning = false
                    return true
                }
                if (overlayDown) { overlayDown = false; dragging = false; panning = false; return true }
                // v0.2.66 FIX (input-dead audit): a double-tap MUST reach X as a
                // real double-click. pcmanfm desktop icons, GTK "Execute File"
                // dialogs and file managers all activate on the SECOND
                // ButtonPress; v0.2.37-v0.2.65 consumed the second tap here for
                // the fit<->fill view toggle, so the desktop only ever saw ONE
                // click per gesture - i.e. "the desktop shows but nothing
                // opens", no matter how healthy the transport was. The view
                // toggle now lives on the FIT chip of the helper bar.
                lastTapUpMs = System.currentTimeMillis()
                lastTapUpX = event.x; lastTapUpY = event.y
                val (x, y) = mapToX(event.x, event.y)
                // v0.2.58 EVIDENCE ROUND: one-shot pane note carrying the
                // exact view->X mapping of the first tap, so a screenshot
                // discriminates a mapping fault from a guest-side drop
                // with zero terminal round-trips.
                if (!mapNotePosted) {
                    mapNotePosted = true
                    desktop?.noteInfo("input map: view(${event.x.toInt()},${event.y.toInt()}) -> X($x,$y) cover=$coverMode zoom=$scale")
                }
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
                removeCallbacks(longPressRunnable)
                releaseRightButton(event)
                overlayDown = false
                dragging = false; panning = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private var lastTapUpMs = 0L
    private var lastTapUpX = 0f
    private var lastTapUpY = 0f

    private val longPressRunnable = Runnable {
        if (!dragging && !panning) {
            longPressFired = true
            val (x, y) = mapToX(downX, downY)
            // v0.2.60: hold button 3 DOWN instead of tapping it. The old
            // press+60ms+release made a right-drag impossible and released the
            // button wherever the finger had first touched, so context menus
            // that track the pointer closed immediately.
            desktop?.injectClick(x, y, button = 3, press = true)
            rightHeld = true
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private fun releaseRightButton(e: MotionEvent) {
        if (!rightHeld) return
        rightHeld = false
        val (x, y) = mapToX(e.x, e.y)
        desktop?.injectClick(x, y, button = 3, press = false)
    }

    // ------------------------- on-screen key helpers -------------------------
    private class Chip(val label: String, val id: Int)
    private val chips = listOf(
        Chip("Esc", CHIP_ESC), Chip("Tab", CHIP_TAB), Chip("Ctrl", CHIP_CTRL),
        Chip("Alt", CHIP_ALT), Chip("Super", CHIP_META), Chip("\u25B2", CHIP_UP),
        Chip("\u25BC", CHIP_DOWN), Chip("\u232B", CHIP_BKSP), Chip("FIT", CHIP_FILL),
    )
    private val chipRects = ArrayList<RectF>()
    private var toggleRect = RectF()
    private fun barHeight() = dp(40f)

    /** Keysym for a character: Latin-1 maps 1:1, everything else is 0x01000000+cp. */
    private fun keysymFor(ch: Char) = if (ch.code < 0x100) ch.code else 0x01000000 + ch.code

    private fun clearSticky() {
        if (!stickyCtrl && !stickyAlt && !stickyMeta) return
        stickyCtrl = false; stickyAlt = false; stickyMeta = false
        invalidate()
    }

    /** Inject a keysym, spending any sticky modifiers on it. */
    private fun sendKeysym(ks: Int) {
        val d = desktop ?: return
        if (stickyCtrl || stickyAlt || stickyMeta) {
            d.injectKeysymCombo(ks, stickyCtrl, stickyAlt, stickyMeta)
            clearSticky()
        } else {
            d.injectKeysymTap(ks)
        }
    }

    private fun handleChip(id: Int) {
        val d = desktop ?: return
        val (cx, cy) = mapToX(width / 2f, height / 2f, markCursor = false)
        when (id) {
            CHIP_ESC -> sendKeysym(0xFF1B)
            CHIP_TAB -> sendKeysym(0xFF09)
            CHIP_BKSP -> sendKeysym(0xFF08)
            CHIP_CTRL -> { stickyCtrl = !stickyCtrl; invalidate() }
            CHIP_ALT -> { stickyAlt = !stickyAlt; invalidate() }
            CHIP_META -> { stickyMeta = !stickyMeta; invalidate() }
            CHIP_UP -> d.injectScroll(cx, cy, 4, 3)
            CHIP_DOWN -> d.injectScroll(cx, cy, 5, 3)
            // v0.2.66 FIX: the fit<->fill view toggle USED to live on double-tap,
            // which made a real double-click impossible - and every desktop
            // (pcmanfm icons, file managers, "Execute/Cancel" dialogs) needs a
            // double-click to activate anything. Double-tap is now delivered to
            // X as a genuine double-click; the view toggle lives here instead.
            CHIP_FILL -> { coverMode = !coverMode; scale = 1f; tx = 0f; ty = 0f; invalidate() }
            CHIP_TOGGLE -> { helperBarVisible = !helperBarVisible; persistBarVisibility(); invalidate() }
        }
        performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** Overlay hit-test. Returns true when the touch was consumed by a control. */
    private fun onOverlayTouch(sx: Float, sy: Float): Boolean {
        if (toggleRect.contains(sx, sy)) { handleChip(CHIP_TOGGLE); return true }
        if (!helperBarVisible || sy < height - barHeight()) return false
        for (i in chips.indices) {
            if (i >= chipRects.size) return false
            val r = chipRects[i]
            if (sx >= r.left && sx <= r.right) { handleChip(chips[i].id); return true }
        }
        return false
    }

    private fun drawOverlay(canvas: Canvas) {
        // Always-on toggle: the bar itself is hidden by default because tint2
        // lives at the bottom of the X screen and a permanent strip would cover
        // the panel the user is trying to click.
        val ts = dp(30f)
        toggleRect = RectF(width - ts - dp(6f), dp(6f), width - dp(6f), dp(6f) + ts)
        val boxP = Paint().apply { color = Color.argb(0xB0, 0x1B, 0x24, 0x30); style = Paint.Style.FILL }
        canvas.drawRoundRect(toggleRect, dp(6f), dp(6f), boxP)
        val lbl = Paint().apply {
            textSize = sp(9f); isAntiAlias = true; textAlign = Paint.Align.CENTER
            color = if (helperBarVisible) Color.argb(0xF0, 0x4F, 0xC3, 0xF7) else Color.argb(0xC0, 0xFF, 0xFF, 0xFF)
        }
        canvas.drawText("keys", toggleRect.centerX(), toggleRect.centerY() + sp(3.5f), lbl)

        if (helperBarVisible) {
            val h = barHeight(); val top = height - h
            chipRects.clear()
            canvas.drawRect(0f, top, width.toFloat(), height.toFloat(),
                Paint().apply { color = Color.argb(0xD8, 0x10, 0x16, 0x1E) })
            val n = chips.size
            val pad = dp(4f)
            val cw = (width - pad * (n + 1)) / n
            val tp = Paint().apply { textSize = sp(11f); isAntiAlias = true; textAlign = Paint.Align.CENTER }
            for (i in 0 until n) {
                val l = pad + i * (cw + pad)
                val r = RectF(l, top + pad, l + cw, height - pad)
                chipRects.add(r)
                val on = when (chips[i].id) {
                    CHIP_CTRL -> stickyCtrl; CHIP_ALT -> stickyAlt; CHIP_META -> stickyMeta; else -> false
                }
                canvas.drawRoundRect(r, dp(5f), dp(5f), Paint().apply {
                    color = if (on) Color.argb(0xE8, 0x4F, 0xC3, 0xF7) else Color.argb(0x44, 0xFF, 0xFF, 0xFF)
                    style = Paint.Style.FILL
                })
                tp.color = if (on) Color.argb(0xFF, 0x08, 0x14, 0x1C) else Color.argb(0xE8, 0xFF, 0xFF, 0xFF)
                canvas.drawText(chips[i].label, r.centerX(), r.centerY() + sp(4f), tp)
            }
        }
        if (stickyCtrl || stickyAlt || stickyMeta) {
            val mods = buildString {
                if (stickyCtrl) append("Ctrl ")
                if (stickyAlt) append("Alt ")
                if (stickyMeta) append("Super ")
            }.trim()
            val mp = Paint().apply { color = Color.argb(0xE0, 0x4F, 0xC3, 0xF7); textSize = sp(10f) }
            canvas.drawText("next key: $mods + ...", 16f, sp(72f), mp)
        }
    }

    private fun drawGestureHint(canvas: Canvas) {
        // v0.2.67 FIX: the legend used to paint at sp(40)/sp(50) - the SAME y
        // as the v0.2.65 proof line (sp(40)) - so in every field screenshot
        // the two overprinted into unreadable garbage and the one line that
        // disambiguates the input path ("proof=ok touch=18 inj=36 ...") could
        // not be read. Legend moves below the badges; text now matches the
        // v0.2.66 gesture contract (double-tap is a real double-click; the
        // fit<->fill toggle lives on the FIT chip).
        val hp = Paint().apply { color = Color.argb(0x78, 0xFF, 0xFF, 0xFF); textSize = sp(9f) }
        canvas.drawText("tap=click  long-press=right(hold+drag)  2-finger tap=middle", 16f, sp(52f), hp)
        canvas.drawText("2-finger drag=scroll (pan when zoomed)  pinch=zoom  double-tap=2xclick  FIT=fill",
            16f, sp(62f), hp)
    }

    /**
     * v0.2.61: the one-shot "how do I drive this" card.
     *
     * Drawn over the LIVE desktop (the frameless pane already has its own
     * cheat-sheet), faded out over the last third of [HINT_MS], dismissed by
     * any touch. Kept clear of the top-right "keys" toggle so the thing the
     * card tells you about stays tappable while you read it.
     */
    private fun drawIntroCard(canvas: Canvas) {
        val now = System.currentTimeMillis()
        if (hintUntilMs <= now) return
        val remain = hintUntilMs - now
        val fadeMs = HINT_MS / 3
        val a = if (remain < fadeMs) (255f * remain / fadeMs).toInt().coerceIn(0, 255) else 255
        val lines = arrayOf(
            "tap  =  left click",
            "long-press  =  right click (hold + drag to right-drag)",
            "two-finger tap  =  middle click (paste in a terminal)",
            "two-finger drag  =  scroll wheel  \u00b7  pans when zoomed",
            "pinch  =  zoom  \u00b7  double-tap  =  fit / fill",
            "keys  =  Esc Tab Ctrl Alt Super arrows \u232b",
            "Ctrl/Alt/Super are sticky: tap Ctrl, then type c \u2192 Ctrl+C",
        )
        val pad = dp(10f)
        val lh = sp(12.5f)
        val titleH = sp(16f)
        val cardW = width - dp(52f)          // leave the "keys" toggle clear
        val cardH = pad * 2 + titleH + lh * lines.size
        val top = dp(46f)
        val card = RectF(dp(8f), top, dp(8f) + cardW, top + cardH)
        canvas.drawRoundRect(card, dp(8f), dp(8f), Paint().apply {
            color = Color.argb((0xE6 * a / 255).coerceIn(0, 255), 0x10, 0x16, 0x1E)
            style = Paint.Style.FILL
        })
        val title = Paint().apply {
            isAntiAlias = true; textSize = sp(11.5f)
            color = Color.argb((0xFF * a / 255).toInt().coerceIn(0, 255), 0x4F, 0xC3, 0xF7)
        }
        canvas.drawText("Using the desktop   (tap to dismiss)", card.left + pad,
            card.top + pad + titleH * 0.75f, title)
        val body = Paint().apply {
            isAntiAlias = true; textSize = sp(9.5f)
            color = Color.argb((0xE8 * a / 255).toInt().coerceIn(0, 255), 0xFF, 0xFF, 0xFF)
        }
        var y = card.top + pad + titleH + lh * 0.8f
        for (l in lines) { canvas.drawText(l, card.left + pad, y, body); y += lh }
    }

    private companion object {
        const val LONG_PRESS_MS = 450L
        const val TWO_FINGER_TAP_MS = 260L
        const val TWO_FINGER_TAP_SLOP = 26f
        const val SCROLL_STEP_PX = 44f
        const val CHIP_ESC = 1; const val CHIP_TAB = 2; const val CHIP_CTRL = 3
        const val CHIP_ALT = 4; const val CHIP_META = 5; const val CHIP_UP = 6
        const val CHIP_DOWN = 7; const val CHIP_BKSP = 8; const val CHIP_TOGGLE = 9
        const val CHIP_FILL = 10
        // v0.2.61 intro card: long enough to read seven lines on a phone,
        // short enough that it is gone before the user wants the screen back.
        const val HINT_MS = 11_000L
        const val FADE_TICK_MS = 200L
        const val KEY_INTRO_SEEN = "gesture_intro_seen"
        const val KEY_BAR_VISIBLE = "helper_bar_visible"
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
