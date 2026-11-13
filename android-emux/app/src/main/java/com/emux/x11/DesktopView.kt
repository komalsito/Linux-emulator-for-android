package com.emux.x11

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.InputDevice
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
 *  - mouse/trackpad  → the DIRECT path (v0.2.85): buttons press on press
 *                      (right-click is a right-click, not a long-press),
 *                      hover moves the real X pointer, wheel/two-finger
 *                      scroll quantises into X buttons 4-7 with per-axis
 *                      remainder accumulation; fingers keep everything above
 *                      untouched, and a stylus IS a finger here
 *                      (v0.2.95: a DOWN with no button bits is trackpad
 *                      contact, not a click - it used to press button 1 and
 *                      turn a two-finger scroll into a drag-select; and a
 *                      wheel event is now accepted on its source alone, so
 *                      the v0.2.86 touchscreen-device veto can no longer
 *                      drop a trackpad's scroll; v0.2.98: this ROM sends
 *                      no ACTION_SCROLL and never tags the gesture
 *                      classifier, so a scroll that arrives as a
 *                      held-button hover stream is quantised into the
 *                      wheel and a generic BUTTON_PRESS is deferred -
 *                      a scroll start can no longer drag-select;
 *                      v0.2.99: the pad tags its contact stream
 *                      TOOL_TYPE_FINGER - the DEVICE bit decides the
 *                      route, so contact and two-finger scroll reach the
 *                      mouse path instead of the finger machine;
 *                      v0.2.100: the device-first rule is toolType-blind,
 *                      and a second finger hijacks our own drag-lock
 *                      press back into the wheel)
 *  (v0.2.89: the "keys" toggle and its helper key bar are REMOVED - field
 *   request; the view stays fit-to-screen.)
 *
 * Discoverability (v0.2.61): on the FIRST desktop session after install a
 * gesture card fades in over the live desktop for a few seconds; any touch
 * dismisses it.
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
    // v0.2.80: fps the user can act on = COMPLETED full sweeps per second, not
    // the band paint rate. The field badge's "1.0 fps" was bands landing while no
    // sweep ever finished (nativeGrabMs -1) - partial repaints, the "sloppy" feel.
    private var fpsWinStart = 0L
    private var fpsWinBase = -1L
    private var sweepFps = 0f
    // v0.2.68: wall-clock of the last frame that actually arrived. The fps
    // badge used to freeze at its last value when capture died (frameTimes is
    // only trimmed on NEW frames), so a dead stream kept advertising itself
    // as LIVE over a frozen photograph forever.
    private var lastFrameMs = 0L

    // v0.2.88 STARTUP OVERLAY: a black-but-streaming pane is the first-boot
    // NORM, not a failure - a fresh rootfs spends minutes inside apt/dpkg
    // (CORE+REST) before anything paints, and the v0.2.87 field screenshots
    // (HUAWEI MRDI-W09, fresh Ubuntu rootfs) showed exactly that: frames
    // flowing over a black screen with a tiny badge, nothing telling the
    // user it was working. sawContent flips once a frame actually carries
    // pixels (probed ~1/s on a 4x6 grid); until then a card names the stage
    // and shows the live diagnostic tail. Any touch dismisses it, like the
    // intro card - it must never sit between the user and a desktop that
    // just arrived.
    private var sawContent = false
    private var startupDismissed = false
    private var lastContentProbeMs = 0L
    private var contentProbeBmp: android.graphics.Bitmap? = null

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
            // v0.2.88: capture stopped = the desktop is restarting; re-arm
            // the startup overlay so the next frames period is explained
            // again (unless the user already dismissed it this session).
            sawContent = false
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
        // v0.2.112: tiles - only the changed ones are re-uploaded to the GPU
        f.draw(canvas, left, top, w, h, bmpPaint)
        // v0.2.66: the synthetic pointer, above the frame and below the badges
        drawCursor(canvas)
        // v0.2.88: frames flow but nothing painted yet (first-boot apt
        // churn) -> explain it on the pane instead of an unexplained black
        // screen; dismissed by any touch, gone on first non-black probe.
        if (!sawContent && !startupDismissed) {
            probeFrameContent(f)
            if (!sawContent) {
                drawStartupCard(canvas)
                postInvalidateDelayed(1_000)
            }
        }
        // v0.2.33 LIVE badge (see onNewFrame)
        val fps = frameTimes.size / 2f
        // v0.2.111 CLEAN DESKTOP (field request): the diagnostic badge lines
        // (LIVE/INPUT/proof + gesture legend) are hidden unless Settings >
        // "Show desktop diagnostics" is on. They come back by themselves only
        // when the capture goes STALE - the one moment they are worth reading.
        val staleNow = run {
            val ageMs = System.currentTimeMillis() - lastFrameMs
            val proofAge = desktop?.sourceProofAgeSec ?: -1L
            ageMs > 8_000 && !(proofAge in 0..8)
        }
        if (fps > 0f && (showHud() || staleNow)) {
            // v0.2.68 FIX (input-dead audit, round 3): a capture helper that
            // dies AFTER the first frame used to leave this badge frozen at
            // "LIVE 0.5 fps" over the last bitmap FOREVER (frameTimes is only
            // trimmed when a new frame arrives, and nothing repainted). The
            // pane was a photograph that claimed to be alive - which is why
            // "desktop shows but nothing opens" survived ten versions even
            // with a healthy input path. Age the badge: >8 s without a frame
            // is STALE, in red, ticking, and repainting itself.
            val ageMs = System.currentTimeMillis() - lastFrameMs
            // v0.2.84: "no frames for 8 s" is only STALE when no capture
            // source has PROVEN itself in that window either. With band-diff
            // capture a quiet desktop sends no frames BY DESIGN while its
            // grabs/reads keep succeeding - that is IDLE (green badge below),
            // not dead (this red one). The field's "LIVE 0.0 fps" complaint
            // and a false STALE are the same confusion from two sides.
            val proofAge = desktop?.sourceProofAgeSec ?: -1L
            if (ageMs > 8_000 && !(proofAge in 0..8)) {
                val stale = Paint().apply { color = Color.argb(0xE0, 0xEF, 0x53, 0x50); textSize = sp(10f) }
                canvas.drawText(
                    "STALE ${ageMs / 1000}s - no frames arriving (capture dead? respawning) " +
                        // v0.2.72: and NAME the capture failure on the pane —
                        // the wire-level rejection (BadLength=16 on v0.2.71's
                        // GetImage, timeout, bad depth...) or "silent", so a
                        // screenshot ends the archaeology instead of starting it.
                        "capage=${desktop?.capturePubAgeSec ?: -1}s " +
                        "nat=${(desktop?.nativeErr ?: "").ifBlank { "silent" }.take(22)} " +
                        // v0.2.74: the measured pipe ceiling and band size,
                        // on the pane, in the screenshot, no adb required.
                        "pipe=${desktop?.nativeKbPerS ?: -1}K b=${desktop?.nativeBandRows ?: 0}r",
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
                // v0.2.79: "LIVE 0.5 fps" said the desktop was slow but not why,
                // or what to do about it. Frame time is bytes/pipe, so paint all
                // three numbers next to the rate: what one frame costs (the X
                // screen size - capture cannot be smaller than the screen), how
                // fast the pipe actually measured, and the resulting refresh cost.
                // Settings -> "Desktop capture quality" trades the first for the
                // last, and now the pane shows whether it worked.
                val d = desktop
                val nf = d?.nativeFrames ?: -1L
                val nowMs = System.currentTimeMillis()
                if (fpsWinStart == 0L || nowMs - fpsWinStart >= 2_000) {
                    if (fpsWinBase >= 0 && nf >= fpsWinBase && fpsWinStart > 0L)
                        sweepFps = (nf - fpsWinBase) * 1000f /
                            (nowMs - fpsWinStart).coerceAtLeast(1)
                    fpsWinStart = nowMs
                    fpsWinBase = nf
                }
                val kb = d?.nativeKbPerS ?: -1L
                val ms = d?.nativeGrabMs ?: -1L
                // v0.2.84 HONEST RATE: LIVE shows the measured update rate
                // while updates flow; IDLE says nothing has CHANGED (which for
                // a damage-driven, band-diffed pipeline is the success state,
                // not a stall); cap~Nfps is the pipe's real full-frame
                // capacity from the measured KB/s - the capture rate that
                // never collapses to 0.0 because the desktop is quiet.
                val head = if (ageMs > 2_000) "IDLE ${ageMs / 1000}s - nothing changing"
                           else "LIVE %.1f fps".format(sweepFps)
                val cap = d?.nativeCapacityFps ?: -1
                var line = head + "  %s  %dKB/s".format(d?.captureGeometry ?: "?", kb) +
                    (if (cap > 0) "  cap~%dfps".format(cap) else "") +
                    "  %dms/refresh".format(ms)
                // v0.2.80: when no sweep has completed, or one is stuck, say where
                // it got to and why - the -1/-1 badge answered none of that.
                if (d != null && (d.nativeMisses > 0 || ms <= 0)) {
                    line += "  sweep %s miss=%d %s".format(
                        d.nativeSweepProgress, d.nativeMisses,
                        d.nativeLastErr.take(16))
                }
                // v0.2.81: which capture architecture is live. "damage" means the
                // server reports what changed and only that crosses the pipe -
                // the difference between a desktop that follows your finger and
                // one that repaints itself on a timer.
                if (d?.nativeDamageOn == true) line += "  mode=damage"
                else if (d != null) line += "  mode=sweep"
                // v0.2.83: and WHICH PIPELINE is painting. mode= describes how
                // native capture works; src= names the frame source itself
                // (native / fbmail / mail / native-retry). Before this, a
                // mailbox fallback co-running with native was invisible on the
                // badge even though it was overwriting fresh damage frames with
                // 2-second-old full-screen photographs - the "sloppy" verdict
                // with no visible cause.
                line += "  src=${d?.captureMode ?: "-"}"
                
                canvas.drawText(line, 16f, sp(16f), badge)
                // v0.2.84: while IDLE, tick once a second so the readout stays
                // true (frames are not arriving to invalidate us).
                if (ageMs > 2_000) postInvalidateDelayed(1_000)
                if (desktop?.captureQualityPending == true) {
                    val pend = Paint().apply { color = Color.argb(0xC0, 0xFF, 0xB7, 0x4D); textSize = sp(9f) }
                    // sp(28f) is the INPUT line, so this rides at the bottom of
                    // the badge stack where nothing else paints.
                    canvas.drawText("capture quality changed - restart the desktop to apply it",
                        16f, sp(96f), pend)
                }
            }
            // v0.2.53: input health ON the bitmap - the history TextView is
            // invisible once frames flow, and "clicks do nothing" needed an
            // always-visible answer: INPUT: agent = FIFO writer connected.
            val tr = desktop?.inputTransport ?: "down"
            // v0.2.78: a GREEN badge must mean "you can click", not "a socket
            // connected". This is the same class of lie as audit v0.2.66 item 6
            // (the badge painted from a connect-time flag) but one level deeper:
            // here everything about the transport is fine, MOTION is honoured,
            // and the desktop still ignores every touch because a window
            // manager holds a passive button grab. So the button leg of the
            // proof gets its own field and its own colour.
            val btn = desktop?.buttonDelivered
            val btnDead = btn == false
            val btnTxt = when (btn) {
                null -> "btn=..."
                true -> "btn=" + (desktop?.btnMode ?: "ok")
                false -> "btn=DEAD"
            }
            val wmTxt = "wm=" + (desktop?.wmMode?.take(9)?.ifEmpty { "?" } ?: "?")
            val up = tr != "down" && !btnDead
            val ib = Paint().apply {
                color = when {
                    btnDead -> Color.argb(0xC0, 0xEF, 0x53, 0x50)
                    up -> Color.argb(0xC0, 0x66, 0xBB, 0x6A)
                    tr != "down" -> Color.argb(0xC0, 0xFF, 0xCA, 0x63)  // measuring
                    else -> Color.argb(0xC0, 0xEF, 0x53, 0x50)
                }
                textSize = sp(10f)
            }
            canvas.drawText(
                (when {
                    btnDead && (tr == "xtest" || tr == "capture") -> "INPUT: buttons SWALLOWED (motion ok)"
                    tr == "xtest" -> "INPUT: XTEST (control plane)"
                    // v0.2.92: XTEST over the capture connection - shown when
                    // the control plane is down but its motion proof passed.
                    tr == "capture" -> "INPUT: XTEST (capture plane)"
                    tr == "agent" -> "INPUT: guest xdotool agent (slow fallback)"
                    else -> "INPUT: down - no XTEST, no agent"
                }) + "  $btnTxt $wmTxt", 16f, sp(28f), ib)
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
                    // v0.2.92: motion verified but the button leg has not
                    // reported yet is MEASURING, not failing - it used to
                    // paint red and read as 'input dead' in field shots.
                    proof == "motion-ok" -> Color.argb(0xC0, 0xFF, 0xCA, 0x63)
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
                    (desktop?.agentEvidence() ?: "") +
                    // v0.2.92: while the control plane cannot reconnect, say
                    // HOW MANY attempts failed and the last error - a yellow
                    // proof=pending with no reason was undiagnosable in the
                    // field (the 18:15 round: clicks dead a whole session).
                    (if (proof == "pending")
                        " rxfail=${desktop?.reconnectFailCount() ?: 0}" +
                            (desktop?.ctrlErrorText?.let { " ctrlErr=" + it.take(46) } ?: "")
                    else ""), 16f, sp(40f), pb)
            drawGestureHint(canvas)
        }
        // v0.2.60: on-screen Esc/Tab/Ctrl/Alt/Super + wheel, drawn last so it
        // sits above the framebuffer.
        drawOverlay(canvas)
        // v0.2.61: one-shot gesture card, on top of even the overlay.
        drawIntroCard(canvas)
    }

    /**
     * v0.2.88: is the streaming frame actually showing anything yet? The
     * frames are HARDWARE bitmaps (getPixel would throw), so the frame is
     * scaled into a tiny 4x6 SOFTWARE bitmap and that is probed instead -
     * 24 samples, ~1/s, negligible next to the capture pipe. Any sample at
     * lum >= 0.12 (the painted root #20344d is ~0.2) means the desktop has
     * content and the startup card never shows again this session.
     */
    private fun probeFrameContent(f: TiledFrame) {
        val now = System.currentTimeMillis()
        if (now - lastContentProbeMs < 1_000) return
        lastContentProbeMs = now
        runCatching {
            val probe = contentProbeBmp ?: android.graphics.Bitmap.createBitmap(
                4, 6, android.graphics.Bitmap.Config.ARGB_8888).also { contentProbeBmp = it }
            val pc = Canvas(probe)
            f.draw(pc, 0f, 0f, 4f, 6f, null)
            pc.setBitmap(null)
            for (py in 0 until 6) for (px in 0 until 4) {
                val p = probe.getPixel(px, py)
                val lum = (0.299f * Color.red(p) + 0.587f * Color.green(p) +
                    0.114f * Color.blue(p)) / 255f
                if (lum >= 0.12f) { sawContent = true; return }
            }
        }
    }

    /**
     * v0.2.88: the "it is working, be patient" card. Lower-center, clear of
     * the badge stack (top-left) and the helper bar (bottom strip); carries
     * the freshest diagnostic lines so a screenshot alone answers "what is
     * it doing". Dismissed by any touch (see ACTION_DOWN, both paths).
     */
    private fun drawStartupCard(canvas: Canvas) {
        val pad = dp(12f)
        val lh = sp(11.5f)
        val hist = desktop?.errorHistoryText()?.takeIf { it != "?" && it.isNotBlank() }
        val lines = hist?.lines()?.filter { it.isNotBlank() }?.takeLast(8) ?: emptyList()
        val cardW = (width * 0.86f).coerceAtMost(dp(440f))
        val cardH = pad * 2 + sp(30f) + lines.size * lh
        val bottomGuard = dp(8f)   // v0.2.89: helper bar removed
        val top = (height - bottomGuard) - cardH - dp(12f)
        val left = (width - cardW) / 2f
        val card = RectF(left, top, left + cardW, top + cardH)
        canvas.drawRoundRect(card, dp(8f), dp(8f), Paint().apply {
            color = Color.argb(0xE6, 0x10, 0x16, 0x1E); style = Paint.Style.FILL
        })
        var y = top + pad + sp(13f)
        val tp = Paint().apply {
            isAntiAlias = true; textSize = sp(13f); color = Color.argb(0xF0, 0x4F, 0xC3, 0xF7)
        }
        canvas.drawText("Linux desktop is starting…", left + pad, y, tp)
        y += sp(9f)
        val np = Paint().apply {
            isAntiAlias = true; textSize = sp(10f); color = Color.argb(0xC8, 0xFF, 0xFF, 0xFF)
        }
        canvas.drawText(
            "first boot installs packages (slow under proot) - live status:",
            left + pad, y, np)
        for (l in lines) {
            y += lh
            val hp = Paint().apply {
                isAntiAlias = true; textSize = sp(9f)
                color = if (DiagColors.healthy(l)) Color.argb(0xE0, 0x66, 0xBB, 0x6A)
                        else Color.argb(0xE0, 0xEF, 0x53, 0x50)
            }
            canvas.drawText(l.take(80), left + pad, y, hp)
        }
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
        // v0.2.85: a real mouse brings its OWN system-drawn cursor; drawing
        // ours under it shows two pointers a few px apart - the "virtual mouse
        // lagging behind the real one" field complaint. Fingers clear the flag
        // on ACTION_DOWN and get the synthetic cursor back.
        // v0.2.86: ...and an IDLE or unplugged mouse gets it back too: the
        // suppression only lives REAL_MOUSE_GRACE_MS past the last mouse
        // event, so a stray mouse-classified hover can never leave the pane
        // permanently cursor-less ("there is no virtual mouse").
        if (realMouseCursor &&
            System.currentTimeMillis() - lastMouseInputMs < REAL_MOUSE_GRACE_MS) return
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

    /**
     * Trackpad / mouse hover.
     *
     * v0.2.85 REAL POINTER FOLLOW: for a mouse/trackpad this now injects the
     * hover into X (coalesced to <=30/s with a trailing flush, so the FINAL
     * position always lands - hover highlights live or die on it), and the
     * synthetic drawn cursor hides: Android already draws the system pointer
     * for a real mouse, and two cursors a few px apart read as "virtual mouse
     * lagging behind the real one". Finger/stylus hover keeps the old
     * behaviour exactly: move the drawn cursor, inject nothing.
     */
    override fun onHoverEvent(event: android.view.MotionEvent): Boolean {
        noteEventShape("hover", event)   // v0.2.97: evidence - a trackpad can deliver whole gestures as hover
        val a = event.actionMasked
        if (a == android.view.MotionEvent.ACTION_HOVER_EXIT) {
            val f = desktop?.currentFrame
            if (f != null && event.buttonState == 0 && lastHoverViewY >= 0f &&
                event.y >= lastHoverViewY && isMouseInput(event)) {
                val (x, y) = mapToX(event.x, event.y, false)
                if (y >= f.height * 9 / 10) {
                    cancelEdgeSnap()
                    val bottom = f.height - 1
                    val r = Runnable { edgeSnap = null; desktop?.injectMotion(x, bottom) }
                    edgeSnap = r
                    postDelayed(r, 60)
                }
            }
        } else if (a == android.view.MotionEvent.ACTION_HOVER_MOVE ||
            a == android.view.MotionEvent.ACTION_HOVER_ENTER) {
            if (a == android.view.MotionEvent.ACTION_HOVER_ENTER) cancelEdgeSnap()
            lastHoverViewY = event.y
        }
        if (a == android.view.MotionEvent.ACTION_HOVER_MOVE ||
            a == android.view.MotionEvent.ACTION_HOVER_ENTER) {
            if (isMouseInput(event)) {
                noteMouseActive()
                // v0.2.98 burst tracer: the scroll gesture reaches us ONLY as
                // hover (no touch stream, no ACTION_SCROLL, no classifier
                // tag), so each continuous hover burst is recorded: count,
                // travelled pixels, any button bits seen, duration - one
                // readable line per gesture instead of deduped silence.
                val hnow = System.currentTimeMillis()
                if (hoverBurstN == 0 || hnow - hoverBurstLastMs > 800L) {
                    noteHoverFlush("gap")
                    hoverBurstN = 1
                    hoverBurstStartMs = hnow
                    hoverBurstLastX = event.x; hoverBurstLastY = event.y
                    hoverBurstDx = 0f; hoverBurstDy = 0f
                } else {
                    hoverBurstN++
                    hoverBurstDx += event.x - hoverBurstLastX
                    hoverBurstDy += event.y - hoverBurstLastY
                    hoverBurstLastX = event.x; hoverBurstLastY = event.y
                }
                hoverBurstLastMs = hnow
                hoverBurstBtnMask = hoverBurstBtnMask or event.buttonState
                scheduleHoverFlush()
                // v0.2.98: some ROMs attach wheel axes to generic motion
                // instead of sending ACTION_SCROLL - take them from hover too.
                val av = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val ah = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                if (av != 0f || ah != 0f) {
                    val k = mouseScrollScale() * (if (mouseNaturalScroll()) -1f else 1f)
                    wheelAccumV = (wheelAccumV + av * k).coerceIn(-64f, 64f)
                    wheelAccumH = (wheelAccumH + ah * k).coerceIn(-64f, 64f)
                    val nv = wheelAccumV.toInt(); if (nv != 0) wheelAccumV -= nv
                    val nh = wheelAccumH.toInt(); if (nh != 0) wheelAccumH -= nh
                    if (nv != 0 || nh != 0) {
                        val (x, y) = mapToX(event.x, event.y)
                        desktop?.injectScrollPair(x, y, nv, nh)
                    }
                    return true
                }
                // v0.2.97 MERGED-STREAM SCROLL (hover shape): if the ROM sends
                // the two-finger swipe as one moving HOVER point tagged
                // AMBIGUOUS_GESTURE (the classifier's two-finger-swipe
                // hint, API 29+), its delta is the wheel - same quantised
                // accumulators and natural-scroll setting as every other
                // wheel path.
                if (android.os.Build.VERSION.SDK_INT >= 29 &&
                    (event.classification == MotionEvent.CLASSIFICATION_AMBIGUOUS_GESTURE ||
                        event.classification == 3 /* CLASSIFICATION_TWO_FINGER_SWIPE, API 34 */)) {
                    if (!hoverWheelArmed) {
                        hoverWheelArmed = true
                        wheelCentroidX = event.x; wheelCentroidY = event.y
                    }
                    val dx = event.x - wheelCentroidX
                    val dy = event.y - wheelCentroidY
                    wheelCentroidX = event.x; wheelCentroidY = event.y
                    val dir = if (mouseNaturalScroll()) -1f else 1f
                    wheelAccumV = (wheelAccumV + (-dy / SCROLL_STEP_PX) * dir).coerceIn(-64f, 64f)
                    wheelAccumH = (wheelAccumH + (dx / SCROLL_STEP_PX) * dir).coerceIn(-64f, 64f)
                    val nv = wheelAccumV.toInt(); if (nv != 0) wheelAccumV -= nv
                    val nh = wheelAccumH.toInt(); if (nh != 0) wheelAccumH -= nh
                    if (nv != 0 || nh != 0) {
                        val (x, y) = mapToX(event.x, event.y)
                        desktop?.injectScrollPair(x, y, nv, nh)
                    }
                    return true
                }
                // v0.2.98 HELD-BUTTON HOVER SCROLL. The evidence round showed
                // this ROM sends no ACTION_SCROLL and never tags the gesture
                // classifier (cls=0 on every event), and the user's scroll
                // attempts left no touch-stream lines at all - the gesture can
                // only arrive as hover motion with a button held (bits on the
                // hover event, or a generic ACTION_BUTTON_PRESS before it).
                // For a real mouse this state is unreachable (button-down
                // converts the stream into touch), and real drags on this pad
                // are touch streams, so nothing legitimate is hijacked: while
                // any mouse button is held, hover deltas are the WHEEL.
                val hoverHeld = event.buttonState != 0 || mouseExtraButton != 0 ||
                    pendingPressBtn != 0
                if (hoverHeld) {
                    if (!hoverWheelArmed) {
                        hoverWheelArmed = true
                        wheelCentroidX = event.x; wheelCentroidY = event.y
                        if (!hoverWheelNoted) {
                            hoverWheelNoted = true
                            desktop?.noteInfo(
                                "trackpad scroll: held-button hover stream -> wheel (v0.2.98)")
                        }
                        // a deferred BUTTON_PRESS was really a scroll start:
                        // cancel it instead of clicking
                        cancelPendingPress()
                        // a press that already fired (the 90 ms window closed
                        // before the first motion) is taken back
                        if (mouseExtraButton != 0) {
                            val (x, y) = mapToX(event.x, event.y)
                            desktop?.injectClick(x, y, mouseExtraButton, press = false)
                            mouseExtraButton = 0
                        }
                    }
                    val dx = event.x - wheelCentroidX
                    val dy = event.y - wheelCentroidY
                    wheelCentroidX = event.x; wheelCentroidY = event.y
                    val dir = if (mouseNaturalScroll()) -1f else 1f
                    wheelAccumV = (wheelAccumV + (-dy / SCROLL_STEP_PX) * dir).coerceIn(-64f, 64f)
                    wheelAccumH = (wheelAccumH + (dx / SCROLL_STEP_PX) * dir).coerceIn(-64f, 64f)
                    val nv = wheelAccumV.toInt(); if (nv != 0) wheelAccumV -= nv
                    val nh = wheelAccumH.toInt(); if (nh != 0) wheelAccumH -= nh
                    if (nv != 0 || nh != 0) {
                        val (x, y) = mapToX(event.x, event.y)
                        desktop?.injectScrollPair(x, y, nv, nh)
                    }
                    return true
                }
                if (hoverWheelArmed) hoverWheelArmed = false
                if (!frameless) {
                    val (x, y) = mapToX(event.x, event.y)
                    val now = System.currentTimeMillis()
                    val dt = now - lastHoverInjectMs
                    if (dt >= HOVER_MIN_INTERVAL_MS) {
                        lastHoverInjectMs = now
                        if (hoverTrailPosted) {
                            removeCallbacks(hoverTrailRunnable); hoverTrailPosted = false
                        }
                        pendingHoverX = -1
                        desktop?.injectMotion(x, y)
                    } else {
                        // Inside the coalescing window: remember the position
                        // and let the trailing runnable inject it once. Without
                        // the trailing flush the pointer would stop up to 33 ms
                        // short of where the mouse actually is.
                        pendingHoverX = x; pendingHoverY = y
                        if (!hoverTrailPosted) {
                            hoverTrailPosted = true
                            postDelayed(hoverTrailRunnable, HOVER_MIN_INTERVAL_MS - dt)
                        }
                    }
                }
                return true
            }
            mapToX(event.x, event.y)
            invalidate()
            return true
        }
        return super.onHoverEvent(event)
    }

    private var lastHoverInjectMs = 0L
    private var pendingHoverX = -1
    private var pendingHoverY = -1
    private var hoverTrailPosted = false
    private val hoverTrailRunnable = Runnable {
        hoverTrailPosted = false
        val x = pendingHoverX; val y = pendingHoverY
        if (x >= 0) {
            pendingHoverX = -1
            lastHoverInjectMs = System.currentTimeMillis()
            desktop?.injectMotion(x, y)
        }
    }

    // ------------------------- mouse / trackpad (v0.2.85, v0.2.95) -------------------------
    // External pointers get the DIRECT path: real buttons (press on a DOWN
    // that CARRIES a button - v0.2.95: a button-less DOWN is trackpad
    // contact, not a click, and pressing button 1 for it turned a
    // two-finger scroll into a drag-select; no tap delay,
    // no long-press-to-right-click), real wheel events
    // (ACTION_SCROLL with fractional trackpad deltas and per-axis remainder
    // accumulation - v0.2.95: accepted on source alone, so the v0.2.86
    // touchscreen-device veto can no longer drop a trackpad's scroll),
    // and hover that moves the X pointer. Fingers and styli
    // keep the touch gesture machine untouched - a stylus is touch here by
    // design (M-Pencil users get the same tap/long-press/two-finger model).

    /** True while a real mouse pointer is active: the system draws THAT
     *  cursor, so the synthetic one must not double it. A finger touch
     *  clears it (fingers have no system cursor).
     *  v0.2.86 FIX ("no virtual mouse"): the suppression now EXPIRES after
     *  [REAL_MOUSE_GRACE_MS] of mouse silence. A disconnected mouse, an idle
     *  trackpad - or one stray ROM hover event that classified as mouse -
     *  used to hide the synthetic cursor permanently, and the pane was
     *  cursor-less with no way to bring it back short of a finger tap. The
     *  v0.2.66 contract stands: the pane ALWAYS shows where the X pointer
     *  is. The mouse path marks cursorX/cursorY as it injects, so when the
     *  suppression lapses the synthetic cursor reappears exactly at the real
     *  X pointer - not at some stale finger position. */
    private var realMouseCursor = false
    private var lastMouseInputMs = 0L
    private fun noteMouseActive() {
        realMouseCursor = true
        lastMouseInputMs = System.currentTimeMillis()
    }

    /** Mouse or trackpad? toolType decides; stylus/finger sources never count
     *  even when a ROM tags them SOURCE_MOUSE. */
    private fun isMouseInput(e: MotionEvent): Boolean {
        // v0.2.100: the device-first rule is now TOOLTYPE-BLIND. The pad
        // tags its contact stream with a NON-mouse toolType, but the notes
        // never printed it - v0.2.99 guessed TOOL_TYPE_FINGER and may have
        // guessed wrong (STYLUS/ERASER equally possible) or the tag may
        // FLICKER per event within one gesture (the vc197 log shows the
        // same gesture producing both [touch] and [mouse] lines). Any
        // SOURCE_MOUSE event from a device without the SOURCE_TOUCHSCREEN
        // bit is now mouse input, whatever it claims to be held by. The
        // toolType also joins the evidence key and note text below.
        // v0.2.99: the DEVICE decides first, not the toolType. The cover
        // trackpad ("HUAWEI Glide Keyboard", SOURCE_MOUSE, no TOUCHSCREEN
        // bit) tags its buttonless contact stream - including the two-finger
        // scroll's POINTER_DOWN pc=2 - as TOOL_TYPE_FINGER; the old
        // toolType-first veto routed those to the finger machine, whose
        // single-finger drag semantics produced the field "it grabs as
        // selecting/grabbing instead of scrolling" - while three rounds of
        // mouse-path fixes never saw a single one of those events. A real
        // touchscreen finger always comes from a device carrying
        // SOURCE_TOUCHSCREEN, so the device bit keeps the whole finger
        // vocabulary (and the v0.2.86 PC-mode retagging veto below)
        // intact; only a non-touchscreen pointer device's finger-tagged
        // stream moves to the mouse path, where the v0.2.96 contact model
        // owns it (glide = pointer motion, POINTER_DOWN pc=2 = wheel,
        // quick lift = click, tap-then-glide = drag).
        if (e.isFromSource(InputDevice.SOURCE_MOUSE)) {
            val d = e.device
            // v0.2.102 THE REAL SCROLL FIX: this was
            // '(d.sources and SOURCE_TOUCHSCREEN) == 0' - a PARTIAL-bit test.
            // SOURCE_TOUCHSCREEN is SOURCE_CLASS_POINTER | 0x1000 = 0x1002, and
            // EVERY pointer device carries the SOURCE_CLASS_POINTER bit 0x2,
            // so the AND was never 0 for any pointer device and the whole
            // v0.2.99/v0.2.100 device-first rule NEVER FIRED ONCE. The vc200
            // field log is the proof: Glide Keyboard sources 0x1002313
            // (TOUCHPAD|MOUSE|KEYBOARD|DPAD - no touchscreen bit), its
            // two-finger scroll events arrive src=0x2002 (SOURCE_MOUSE), and
            // they STILL routed [touch] with tt=1. The test must demand the
            // FULL mask, exactly like InputDevice.supportsSource() (which the
            // device-inventory note at the bottom of this file already used,
            // printing the right answer while the routing used the wrong one).
            if (d != null && d.sources and InputDevice.SOURCE_TOUCHSCREEN != InputDevice.SOURCE_TOUCHSCREEN) return true
        }
        if (e.pointerCount > 0) {
            when (e.getToolType(0)) {
                MotionEvent.TOOL_TYPE_MOUSE -> return true
                MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER,
                MotionEvent.TOOL_TYPE_FINGER -> return false
            }
        }
        if (!e.isFromSource(InputDevice.SOURCE_MOUSE)) return false
        // v0.2.86 FIX ("desktop not interactable, no virtual mouse"): toolType
        // UNKNOWN + SOURCE_MOUSE is trusted ONLY when the originating device is
        // not itself a touchscreen. Desktop-mode/PC-mode ROMs (Huawei-class)
        // re-tag plain finger touches as SOURCE_MOUSE with toolType UNKNOWN;
        // the old source-only fallback sent every finger gesture down the mouse
        // path - which hides the synthetic cursor (realMouseCursor = true, so
        // the pane looks cursor-less), strips the whole finger vocabulary
        // (long-press right-click, two-finger scroll, tap timing) and, worst,
        // can split ONE gesture across both paths when the classification
        // flickers between DOWN and UP: the mouse path presses X button 1, the
        // finger path receives the UP and never releases it, and a button left
        // down in the server swallows every later click - the v0.2.79 lesson,
        // reintroduced through a new seam. A real mouse/trackpad device never
        // carries SOURCE_TOUCHSCREEN, so this cannot cost the v0.2.85 feature.
        val dev = e.device ?: return true
        // v0.2.102: same full-mask fix - the partial-bit test always returned
        // false here too, so the v0.2.86 toolType-UNKNOWN trust rule was dead
        // code from the day it shipped. A touchscreen device carries the full
        // 0x1002 mask and stays vetoed (the Huawei PC-mode re-tagging guard);
        // a pad (0x1002313) does not and is trusted.
        return dev.sources and InputDevice.SOURCE_TOUCHSCREEN != InputDevice.SOURCE_TOUCHSCREEN
    }

    /** ACTION_SCROLL is a WHEEL event, not a finger gesture, so it must NOT
     *  go through [isMouseInput]'s v0.2.86 touchscreen-device veto.
     *
     *  v0.2.95 REGRESSION (trackpad two-finger scroll silently dropped):
     *  that veto exists to stop *touch* streams being misclassified as
     *  mouse (Huawei-class PC-mode ROMs re-tag plain finger touches as
     *  SOURCE_MOUSE with toolType UNKNOWN, on a device that also carries
     *  SOURCE_TOUCHSCREEN). But a wheel event carries no fingers at all -
     *  a touchscreen two-finger drag NEVER arrives as ACTION_SCROLL, it
     *  arrives as a touch stream - so no finger gesture can be misrouted
     *  here, while a trackpad whose InputDevice reports SOURCE_TOUCHSCREEN
     *  (the MatePad keyboard-cover trackpad does) had every one of its
     *  scroll events rejected: isMouseInput said false, the event was not
     *  SOURCE_TOUCHPAD either, onGenericMotionEvent returned false, and
     *  the page never scrolled. The source alone decides: MOUSE or
     *  TOUCHPAD is a wheel, anything else is not. */
    private fun isWheelInput(e: MotionEvent): Boolean {
        // v0.2.96: accept ACTION_SCROLL from ANY source. The v0.2.95 source
        // list was still too narrow for the cover trackpad, whose scroll
        // events the ROM can tag touchscreen-class - and no touch STREAM
        // ever arrives as ACTION_SCROLL (the v0.2.86 veto's concern), so
        // there is no finger gesture this can misroute. Unusual sources are
        // still noted once for the next field round.
        if (e.isFromSource(InputDevice.SOURCE_MOUSE) ||
            e.isFromSource(InputDevice.SOURCE_TOUCHPAD)) return true
        if (!scrollDropNoted) {
            scrollDropNoted = true
            desktop?.noteInfo(
                "scroll accepted from unusual source=0x" +
                    Integer.toHexString(e.source) +
                    " dev=" + (e.device?.name ?: "?") + " (v0.2.96: any ACTION_SCROLL is a wheel)")
        }
        return true
    }

    /** Android button bits -> X button numbers. X: 1 left, 2 middle, 3 right,
     *  8 back, 9 forward. TERTIARY is the middle button on Android. */
    private fun xButtonForBits(bits: Int): Int = when {
        bits and MotionEvent.BUTTON_PRIMARY != 0 -> 1
        bits and MotionEvent.BUTTON_SECONDARY != 0 -> 3
        bits and MotionEvent.BUTTON_TERTIARY != 0 -> 2
        bits and MotionEvent.BUTTON_BACK != 0 -> 8
        bits and MotionEvent.BUTTON_FORWARD != 0 -> 9
        // v0.2.95: NO bits = NO button (was: default 1). A trackpad finger
        // touching the pad reports ACTION_DOWN with buttonState 0 - that is
        // contact, not a click - and the old default pressed X button 1 for
        // it, so a two-finger scroll that arrives as a touch stream dragged
        // a selection across the page instead of scrolling. Callers must
        // treat 0 as "nothing pressed" (onMouseTouchEvent does).
        else -> 0
    }

    // Wheel accumulators. Trackpads send small FRACTIONAL values (0.1-notch
    // events are normal); only whole notches become X wheel clicks, and the
    // remainder carries into the next event, so a slow two-finger crawl still
    // scrolls exactly, and a fast flick does not round away its tail.
    private var wheelAccumV = 0f
    private var wheelAccumH = 0f

    // The mouse DOWN/UP path holds ONE button; BUTTON_PRESS/RELEASE (fired
    // when a second button joins a held one, e.g. right-click during a drag)
    // tracks its own. Both must die on CANCEL or the button stays down in the
    // server and every later click is swallowed - the v0.2.79 lesson.
    private var mouseHeldButton = 0
    private var mouseExtraButton = 0
    private var mouseOverlayDown = false
    private var lastMouseMotionTs = 0L
    // v0.2.95: a mouse DOWN that carried NO button bits (trackpad finger
    // contact, not a click - see xButtonForBits). While set, MOVE is hover,
    // never a drag; cleared by every gesture exit (releaseMouseButtons /
    // releaseMouseButtonsIfHeld).
    private var mouseContactDown = false
    // v0.2.96 TRACKPAD CONTACT MODEL. The MatePad keyboard-cover trackpad
    // delivers its whole gesture vocabulary as BUTTONLESS mouse-classified
    // streams: a glide is a contact+MOVE, a physical click reports button
    // bits. The v0.2.95 contact change stopped the glide from drag-selecting
    // (right) but left the stream NO vocabulary at all - a tap did nothing
    // (no button bits on its DOWN/UP) and a two-finger scroll just glided
    // the pointer: the reported "scrolling does not work, inputs are
    // sloppy". These fields synthesize the standard trackpad semantics
    // host-side (see DOWN/MOVE/POINTER_DOWN/POINTER_UP/UP):
    //   tap (quick lift, no travel)  -> button 1 click
    //   tap, then a new contact that MOVES (drag-lock idiom) -> button 1
    //     press + real drag, released at lift
    //   a second pointer joins the contact -> two-finger SCROLL (wheel
    //     4-7, same accumulators + natural-scroll setting as ACTION_SCROLL)
    //   two-finger tap (no travel)   -> button 3 right click (the finger
    //     machine keeps its middle-click two-finger tap)
    private var contactDownMs = 0L
    private var contactDownX = 0f
    private var contactDownY = 0f
    private var contactMoved = false
    private var dragLockPending = false
    // v0.2.100: button 1 currently held came from the drag-lock synthesis
    // (not real button bits) - POINTER_DOWN can take it back for a scroll.
    private var dragLockHeld = false
    private var lastSynthTapMs = 0L
    // a ROM that ALSO reports the tap as ACTION_BUTTON_PRESS would turn a
    // synthesized tap into a double click - suppress ours when a real
    // press just arrived (see onGenericMotionEvent).
    private var lastMouseBtnPressMs = 0L
    private var contactWheel = false
    private var contactWheelMoved = false
    private var contactWheelStartMs = 0L
    private var wheelCentroidX = 0f
    private var wheelCentroidY = 0f
    // v0.2.97: the hover shape of the merged-stream scroll (see onHoverEvent).
    private var hoverWheelArmed = false
    // v0.2.98: one-shot pane note the first time the held-button hover
    // synthesis fires, so a field screenshot confirms the path ran.
    private var hoverWheelNoted = false
    // v0.2.98: hover-burst tracer (see onHoverEvent) - the scroll gesture on
    // the cover trackpad is visible ONLY as hover, so each continuous burst
    // is counted and flushed as a single evidence line.
    private var hoverBurstN = 0
    private var hoverBurstDx = 0f
    private var hoverBurstDy = 0f
    private var hoverBurstBtnMask = 0
    private var hoverBurstStartMs = 0L
    private var hoverBurstLastMs = 0L
    private var hoverBurstLastX = 0f
    private var hoverBurstLastY = 0f
    private var hoverBurstFlushPosted = false
    private val hoverBurstFlushRunnable = Runnable {
        hoverBurstFlushPosted = false
        noteHoverFlush("idle")
    }
    private fun scheduleHoverFlush() {
        if (hoverBurstFlushPosted) removeCallbacks(hoverBurstFlushRunnable)
        hoverBurstFlushPosted = true
        postDelayed(hoverBurstFlushRunnable, 1_500L)
    }
    private fun noteHoverFlush(reason: String) {
        if (hoverBurstN == 0) return
        desktop?.noteInfo(
            "input ev: HOVER_BURST n=" + hoverBurstN +
                " dx=" + hoverBurstDx.toInt() + " dy=" + hoverBurstDy.toInt() +
                " btn=0x" + Integer.toHexString(hoverBurstBtnMask) +
                " dur=" + (hoverBurstLastMs - hoverBurstStartMs) + "ms" +
                " end=" + reason + " [hover]")
        hoverBurstN = 0; hoverBurstDx = 0f; hoverBurstDy = 0f; hoverBurstBtnMask = 0
    }
    // v0.2.98: a generic-motion BUTTON_PRESS is deferred by DEFER_PRESS_MS so
    // a two-finger scroll that starts with a press cannot click or
    // drag-select (see onGenericMotionEvent and onHoverEvent).
    private var pendingPressBtn = 0
    private var pendingPressX = 0f
    private var pendingPressY = 0f
    private val pendingPressRunnable = Runnable {
        val b = pendingPressBtn
        pendingPressBtn = 0
        if (b != 0 && b != mouseHeldButton && mouseExtraButton == 0 && !frameless) {
            mouseExtraButton = b
            val (x, y) = mapToX(pendingPressX, pendingPressY)
            desktop?.injectClick(x, y, b, press = true)
        }
    }
    private fun cancelPendingPress() {
        removeCallbacks(pendingPressRunnable)
        pendingPressBtn = 0
    }

    // --------------------- v0.2.97 EVIDENCE ROUND ---------------------
    // "Still can't scroll" after v0.2.96 means this ROM delivers the
    // two-finger gesture in a shape NONE of our paths recognise. Instead of
    // another blind fix, the pane's note history now carries ONE line per
    // DISTINCT input event shape of the session - action, source,
    // pointerCount, the gesture classifier's verdict, button bits, device -
    // logged at EVERY entry point (finger path, mouse path, hover, wheel),
    // plus the input device inventory on the first frame. The next field
    // screenshot then names exactly what the cover trackpad sends for each
    // gesture, and the fix follows the evidence instead of the guess.
    private val notedEventShapes = HashSet<String>()
    private var inputDevicesNoted = false
    private fun noteEventShape(where: String, e: MotionEvent, extra: String = "") {
        // v0.2.112 PERF: this ran on EVERY touch/hover event (a trackpad
        // hovers at ~120 Hz) and built a fresh key string each time - pure
        // allocation churn on the UI thread in normal use. Evidence is
        // collected only while the diagnostics badge is switched on.
        if (!hudCached()) return
        // v0.2.98: button bits join the dedup key. The v0.2.97 key dropped
        // them, so a hover stream carrying BUTTON_PRIMARY (the shape the
        // field report points at: "moves as if selecting") deduped into the
        // buttonless hover note and the one interesting line never appeared.
        val tt = if (e.pointerCount > 0) e.getToolType(0) else -1
        if (!notedEventShapes.add(where + "|" + e.actionMasked + "|" + e.source +
                "|" + e.pointerCount + "|0x" + Integer.toHexString(e.buttonState) +
                "|" + tt)) return
        val cls = if (android.os.Build.VERSION.SDK_INT >= 29) e.classification else -1
        desktop?.noteInfo(
            "input ev: " + MotionEvent.actionToString(e.actionMasked).removePrefix("ACTION_") +
                " src=0x" + Integer.toHexString(e.source) +
                " pc=" + e.pointerCount +
                " tt=" + tt +
                " cls=" + cls +
                " btn=0x" + Integer.toHexString(e.buttonState) +
                " dev=" + (e.device?.name ?: "?") +
                (if (extra.isEmpty()) "" else " " + extra) +
                " [" + where + "]")
    }
    private fun noteInputDevices() {
        if (inputDevicesNoted) return
        inputDevicesNoted = true
        runCatching {
            val ids = android.view.InputDevice.getDeviceIds()
            for (id in ids) {
                val d = android.view.InputDevice.getDevice(id) ?: continue
                if ((d.sources and android.view.InputDevice.SOURCE_CLASS_POINTER) == 0) continue
                desktop?.noteInfo(
                    "input dev: \"" + d.name + "\" id=" + d.id +
                        " src=0x" + Integer.toHexString(d.sources) +
                        (if (d.supportsSource(android.view.InputDevice.SOURCE_MOUSE)) " MOUSE" else "") +
                        (if (d.supportsSource(android.view.InputDevice.SOURCE_TOUCHPAD)) " TOUCHPAD" else "") +
                        (if (d.supportsSource(android.view.InputDevice.SOURCE_TOUCHSCREEN)) " TOUCHSCREEN" else "") +
                        (if (d.supportsSource(android.view.InputDevice.SOURCE_STYLUS)) " STYLUS" else ""))
            }
        }
    }
    // v0.2.95: one-shot diagnostic for a scroll event the view refuses, so
    // a future field round can tell "no scroll event arrived" from "one
    // arrived with a source this build does not accept".
    private var scrollDropNoted = false

    /** Settings live in the DEFAULT shared prefs (SettingsActivity's file),
     *  and getSharedPreferences can throw on some OEM ROMs - guarded, with
     *  sane defaults, like every other prefs access in this view. */
    private val settingsPrefs
        get() = runCatching {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        }.getOrNull()

    private fun mouseScrollScale(): Float =
        settingsPrefs?.getString("mouse_scroll_scale", "3")?.toFloatOrNull()
            ?.coerceIn(0.1f, 16f) ?: 3f

    // v0.2.131: natural is the DEFAULT now - on a touchscreen-first device
    // the content must follow the fingers; the old classic-wheel default is
    // exactly what the "scrolling is backwards" field report hit. Turn the
    // switch OFF in Settings for the classic wheel direction.
    private fun mouseNaturalScroll(): Boolean =
        settingsPrefs?.getBoolean("mouse_natural_scroll", true) ?: true

    /** v0.2.96: average position of the first pointers - the centroid a
     *  two-finger trackpad scroll is measured from. */
    private fun pointerCentroid(e: MotionEvent): Pair<Float, Float> {
        val n = e.pointerCount.coerceIn(1, 3)
        var sx = 0f; var sy = 0f
        for (i in 0 until n) { sx += e.getX(i); sy += e.getY(i) }
        return sx / n to sy / n
    }

    /**
     * Wheel + extra mouse buttons. ACTION_SCROLL is the usual route a
     * trackpad two-finger scroll arrives by - but not the only shape it
     * takes: some trackpads/ROMs deliver the same gesture as a touch
     * stream (DOWN with no button bits, then MOVE), which the mouse path
     * below must treat as contact, not a click (v0.2.95).
     * ACTION_BUTTON_PRESS/RELEASE carry any button pressed or released
     * while another is already down (the first press of a gesture comes
     * through onTouchEvent as ACTION_DOWN instead).
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                if (!isWheelInput(event)) {
                    // v0.2.95: say so once, with the source, instead of
                    // dropping it silently - the pre-fix behaviour was a
                    // trackpad that "does not scroll anywhere" with no
                    // evidence at all about which event arrived.
                    if (!scrollDropNoted) {
                        scrollDropNoted = true
                        desktop?.noteInfo(
                            "trackpad scroll ignored: source=0x" +
                                Integer.toHexString(event.source) +
                                " tool=" + (if (event.pointerCount > 0) event.getToolType(0) else -1) +
                                " dev=" + (event.device?.name ?: "?"))
                    }
                    return false
                }
                if (frameless) return false
                noteMouseActive()
                val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
                val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
                if (v == 0f && h == 0f) {
                    // v0.2.97 evidence: a wheel event with no value in either
                    // axis is indistinguishable from "no wheel event arrived" -
                    // say so once, with the source.
                    noteEventShape("wheel-zero", event, "v=" + v + " h=" + h)
                    return false
                }
                noteEventShape("wheel", event, "v=" + v + " h=" + h)
                val k = mouseScrollScale() * (if (mouseNaturalScroll()) -1f else 1f)
                wheelAccumV = (wheelAccumV + v * k).coerceIn(-64f, 64f)
                wheelAccumH = (wheelAccumH + h * k).coerceIn(-64f, 64f)
                val nv = wheelAccumV.toInt(); if (nv != 0) wheelAccumV -= nv
                val nh = wheelAccumH.toInt(); if (nh != 0) wheelAccumH -= nh
                if (nv != 0 || nh != 0) {
                    val (x, y) = mapToX(event.x, event.y)
                    // one call = one batched XTEST burst for the whole gesture
                    desktop?.injectScrollPair(x, y, nv, nh)
                }
                return true
            }
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> {
                if (!isMouseInput(event) || frameless) return false
                // v0.2.98: this branch had no shape note, so a two-finger
                // scroll reported as press + hover motion never appeared in
                // the evidence log at all.
                noteEventShape("btn", event, "ab=0x" + Integer.toHexString(event.actionButton))
                noteMouseActive()
                val b = xButtonForBits(event.actionButton)
                // v0.2.95: xButtonForBits now returns 0 for "no button" -
                // a BUTTON_PRESS with no action button is not a press.
                if (b == 0) return true
                val press = event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS
                // v0.2.96: remember a REAL press so the synthesized
                // tap-to-click never doubles a ROM that reports a tap as
                // BOTH a buttonless contact and a BUTTON_PRESS.
                if (press) lastMouseBtnPressMs = System.currentTimeMillis()
                val (x, y) = mapToX(event.x, event.y)
                if (press) {
                    // v0.2.98: DEFER the X press by DEFER_PRESS_MS. On the
                    // cover trackpad a two-finger scroll can arrive as
                    // ACTION_BUTTON_PRESS followed by a hover-motion stream;
                    // injecting the press immediately turned every scroll
                    // start into a drag-select ("moves as if selecting").
                    // Hover motion inside the window cancels the pending
                    // press - that was a scroll; a RELEASE before any motion
                    // is a real quick click and is delivered whole; neither,
                    // and the runnable presses a held button.
                    // v0.2.86 guard kept at fire time: some trackpads report
                    // a physical click as BOTH an ACTION_DOWN (handled by
                    // onMouseTouchEvent) and an ACTION_BUTTON_PRESS. Pressing
                    // the same X button twice turned one click into a
                    // double-click; the DOWN path's button is never re-pressed
                    // here, and releaseMouseButtons releases a shared button
                    // exactly once.
                    pendingPressBtn = b
                    pendingPressX = event.x; pendingPressY = event.y
                    removeCallbacks(pendingPressRunnable)
                    postDelayed(pendingPressRunnable, DEFER_PRESS_MS)
                } else {
                    if (pendingPressBtn == b) {
                        // quick click with no motion in between: whole click
                        removeCallbacks(pendingPressRunnable)
                        pendingPressBtn = 0
                        if (b != mouseHeldButton) {
                            desktop?.injectClick(x, y, b, press = true)
                            desktop?.injectClick(x, y, b, press = false)
                        }
                    } else if (mouseExtraButton == b) {
                        mouseExtraButton = 0
                        desktop?.injectClick(x, y, b, press = false)
                    }
                }
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    /**
     * The mouse gesture path: DOWN presses the mapped button immediately
     * (right-click is a RIGHT-CLICK, not a 450 ms long-press), MOVE with a
     * button down is a real drag (motion coalesced to <=30/s), UP releases.
     * None of the finger machinery runs: no tap delay, no double-tap state,
     * no long-press runnable, no two-finger anything - a physical mouse
     * reports its own buttons and its own timing, and double-clicks arrive as
     * two real DOWN/UP pairs the X client times for itself.
     * v0.2.95: "presses on DOWN" means a DOWN that CARRIES a button. A
     * trackpad finger touching the pad reports DOWN with buttonState 0 -
     * contact, not a click - and pressing button 1 for it turned a
     * two-finger scroll into a drag-select. Such a DOWN only tracks the
     * pointer (MOVE becomes hover); a real click still arrives as
     * ACTION_BUTTON_PRESS, or as a DOWN with its button set.
     */
    // v0.2.106: pointer-mode SWIPE state (see the MOVE branch).
    private var swipeAnchored = false
    private var swipeNotches = 0
    private var swipeGestures = 0

    /** v0.2.106: buttonless single-contact travel on an external pointer device
     *  scrolls (Android <= 13 pointer-mode SWIPE). Read live; default ON. */
    private fun padSwipeScroll(): Boolean =
        settingsPrefs?.getBoolean("trackpad_swipe_scroll", true) ?: true

    private fun noteSwipeOnce() {
        swipeGestures++
        if (swipeGestures <= 3) desktop?.noteInfo(
            "trackpad scroll: pointer-mode SWIPE (1-pointer buttonless contact) -> wheel, " +
                "anchored at the cursor (v0.2.106, gesture #$swipeGestures)")
    }

    /** v0.2.106 evidence: one line per scroll gesture (first 12 per session) -
     *  how many notches it produced. "0 notches" on a real swipe means the
     *  pointer never moved and the next round needs the raw axes. */
    private fun noteSwipeEnd(e: MotionEvent) {
        if (!swipeAnchored) return
        swipeAnchored = false
        if (swipeGestures <= 12) desktop?.noteInfo(
            "trackpad scroll gesture end: notches=$swipeNotches pc=${e.pointerCount} " +
                "travel=${abs(e.x - contactDownX).toInt()}x${abs(e.y - contactDownY).toInt()}")
        swipeNotches = 0
    }

    private fun onMouseTouchEvent(e: MotionEvent): Boolean {
        noteEventShape("mouse", e)   // v0.2.97: evidence - what the mouse/trackpad path sees
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dismissIntroCard()
                startupDismissed = true   // v0.2.88: startup card yields to input
                desktop?.noteTouch()
                noteMouseActive()
                // v0.2.86: a new DOWN is an exit for whatever the PREVIOUS
                // gesture left held (a lost UP/CANCEL - e.g. the stream
                // switched to the finger path mid-press, or the window lost
                // focus). The v0.2.85 comment claimed "even a new DOWN goes
                // through here"; the code never did. Now it does.
                releaseMouseButtons(e)
                mouseOverlayDown = onOverlayTouch(e.x, e.y)
                if (mouseOverlayDown) return true
                requestFocus()
                if (!frameless) {
                    val (x, y) = mapToX(e.x, e.y)
                    mouseHeldButton = xButtonForBits(e.buttonState)
                    if (mouseHeldButton == 0) {
                        // v0.2.95: button-less DOWN (trackpad finger contact,
                        // or a tap-to-click whose press arrives separately as
                        // ACTION_BUTTON_PRESS). Press NOTHING - the old code
                        // pressed X button 1 here, so the MOVE that followed
                        // a two-finger scroll dragged a selection across the
                        // page. The pointer still tracks; see MOVE.
                        mouseContactDown = true
                        // v0.2.96: start of a synthesized gesture - remember
                        // time and place so UP can tell a TAP (click) from a
                        // glide, and arm drag-lock when it follows a tap.
                        contactDownMs = System.currentTimeMillis()
                        contactDownX = e.x; contactDownY = e.y
                        contactMoved = false
                        contactWheel = false
                        contactWheelMoved = false
                        dragLockPending = contactDownMs - lastSynthTapMs <= MOUSE_DRAG_LOCK_MS
                    } else {
                        dragLockPending = false
                        // v0.2.86: if a BUTTON_PRESS for the same button already
                        // arrived (ROMs differ on which comes first), adopt it
                        // instead of pressing twice - one physical click must be
                        // ONE X press.
                        if (mouseExtraButton == mouseHeldButton) mouseExtraButton = 0
                        else desktop?.injectClick(x, y, mouseHeldButton, press = true)
                    }
                    val imm = context.getSystemService(
                        android.view.inputmethod.InputMethodManager::class.java)
                    imm?.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                noteMouseActive()
                if (mouseOverlayDown || frameless) return true
                if (contactWheel) {
                    // v0.2.96: two-finger scroll synthesized from the contact
                    // stream (this ROM never sends ACTION_SCROLL for it): the
                    // centroid delta becomes the AXIS_VSCROLL/HSCROLL analog
                    // (-dy up / +dx right), quantised into wheel notches with
                    // the SAME accumulators, natural-scroll setting and one
                    // batched burst as the ACTION_SCROLL path - 44 px of
                    // travel per notch, like the finger machine.
                    val (cx, cy) = pointerCentroid(e)
                    val dx = cx - wheelCentroidX; val dy = cy - wheelCentroidY
                    wheelCentroidX = cx; wheelCentroidY = cy
                    if (abs(dx) > 1f || abs(dy) > 1f) contactWheelMoved = true
                    val dir = if (mouseNaturalScroll()) -1f else 1f
                    wheelAccumV = (wheelAccumV + (-dy / SCROLL_STEP_PX) * dir).coerceIn(-64f, 64f)
                    wheelAccumH = (wheelAccumH + (dx / SCROLL_STEP_PX) * dir).coerceIn(-64f, 64f)
                    val nv = wheelAccumV.toInt(); if (nv != 0) wheelAccumV -= nv
                    val nh = wheelAccumH.toInt(); if (nh != 0) wheelAccumH -= nh
                    if (nv != 0 || nh != 0) {
                        // v0.2.106: a pointer-mode SWIPE moves its single
                        // reported pointer AWAY from the cursor (AOSP keeps the
                        // real cursor still and reports cursor + finger travel),
                        // so scroll where the gesture STARTED - under the
                        // cursor - and leave the X pointer where it is.
                        val (x, y) = if (swipeAnchored) mapToX(contactDownX, contactDownY, false)
                                     else mapToX(cx, cy)
                        desktop?.injectScrollPair(x, y, nv, nh)
                        swipeNotches += abs(nv) + abs(nh)
                    }
                    return true
                }
                if (mouseHeldButton == 0) {
                    // v0.2.97 MERGED-STREAM SCROLL: many touchpad firmwares
                    // deliver a two-finger swipe as ONE moving point (the
                    // fingers merged - no POINTER_DOWN ever comes, so the
                    // v0.2.96 pair synthesis never armed) tagged by the
                    // gesture classifier AMBIGUOUS_GESTURE, its documented
                    // two-finger-swipe hint (API 29+). That single point's
                    // delta is the wheel - same quantised accumulators and
                    // natural-scroll setting as every other wheel path. The
                    // classifier changing its mind exits the mode and the
                    // pointer resumes gliding.
                    if (android.os.Build.VERSION.SDK_INT >= 29 && mouseContactDown &&
                        (e.classification == MotionEvent.CLASSIFICATION_AMBIGUOUS_GESTURE ||
                        e.classification == 3 /* CLASSIFICATION_TWO_FINGER_SWIPE, API 34 */)) {
                        if (!contactWheel) {
                            contactWheel = true
                            contactWheelMoved = false
                            contactWheelStartMs = System.currentTimeMillis()
                            wheelCentroidX = e.x; wheelCentroidY = e.y
                        }
                        val dx = e.x - wheelCentroidX
                        val dy = e.y - wheelCentroidY
                        wheelCentroidX = e.x; wheelCentroidY = e.y
                        if (abs(dx) > 1f || abs(dy) > 1f) contactWheelMoved = true
                        val dir = if (mouseNaturalScroll()) -1f else 1f
                        wheelAccumV = (wheelAccumV + (-dy / SCROLL_STEP_PX) * dir).coerceIn(-64f, 64f)
                        wheelAccumH = (wheelAccumH + (dx / SCROLL_STEP_PX) * dir).coerceIn(-64f, 64f)
                        val nv = wheelAccumV.toInt(); if (nv != 0) wheelAccumV -= nv
                        val nh = wheelAccumH.toInt(); if (nh != 0) wheelAccumH -= nh
                        if (nv != 0 || nh != 0) {
                            val (x, y) = mapToX(e.x, e.y)
                            desktop?.injectScrollPair(x, y, nv, nh)
                        }
                        return true
                    }
                    if (contactWheel) contactWheel = false   // classifier changed its mind
                    // v0.2.95: contact-only trackpad touch (DOWN carried no
                    // button): the pointer still follows the finger, as
                    // HOVER. Motion with no button held can never select or
                    // drag - and without it the pointer freezes at the
                    // touch-down point for the whole scroll gesture, so the
                    // scroll would land wherever the gesture started.
                    if (!mouseContactDown) return true
                    if (abs(e.x - contactDownX) > TAP_SLOP ||
                        abs(e.y - contactDownY) > TAP_SLOP) contactMoved = true
                    // v0.2.96 drag-lock: a contact that starts right after a
                    // synthesized tap and then MOVES is the tap-then-glide
                    // drag idiom - press button 1 now; the release paths let
                    // go of it at lift.
                    if (dragLockPending && contactMoved) {
                        dragLockPending = false
                        mouseHeldButton = 1
                        dragLockHeld = true   // v0.2.100: POINTER_DOWN can give it back
                        val (x, y) = mapToX(e.x, e.y)
                        desktop?.injectClick(x, y, 1, press = true)
                        return true
                    }
                    // v0.2.106 THE POINTER-MODE SWIPE (root cause of the whole
                    // scroll saga on the Glide Keyboard). On Android <= 13 a
                    // touchpad in pointer mode reports gestures through
                    // TouchInputMapper's PointerGesture machine: ONE finger
                    // moving = HOVER (no DOWN at all); TWO fingers = a DOWN at
                    // the cursor (PRESS), and when they move together the
                    // gesture becomes SWIPE - still exactly ONE pointer, no
                    // button bits, moving from the cursor by the fingers'
                    // travel. Only fingers spread wide apart (FREEFORM) produce
                    // the POINTER_DOWN pc=2 that v0.2.96 waited for. So the
                    // everyday two-finger scroll arrived here as a buttonless
                    // single-contact glide and moved the pointer instead of the
                    // wheel. A buttonless contact that travels (and is not the
                    // tap-then-glide drag above) IS that swipe: wheel it,
                    // anchored at the cursor. Setting: "trackpad_swipe_scroll".
                    if (contactMoved && padSwipeScroll()) {
                        contactWheel = true
                        swipeAnchored = true
                        contactWheelMoved = true          // never a tap at lift
                        contactWheelStartMs = System.currentTimeMillis()
                        // count the travel that crossed the slop, so a short
                        // flick still scrolls
                        wheelCentroidX = contactDownX; wheelCentroidY = contactDownY
                        wheelAccumV = 0f; wheelAccumH = 0f
                        cancelPendingPress()
                        noteSwipeOnce()
                        // re-enter MOVE: the contactWheel branch consumes this
                        // event's delta right away
                        return onMouseTouchEvent(e)
                    }
                    // Before the slop is crossed the contact is still
                    // undecided (tap or swipe): with swipe-scroll on, keep the
                    // X pointer still, exactly like Android keeps its cursor
                    // still during a PRESS.
                    if (padSwipeScroll()) return true
                    val now = System.currentTimeMillis()
                    // v0.2.96: 60 motion events/s (was 30): the XTEST pump
                    // coalesces pending motions to the newest anyway, so the
                    // extra rate only sharpens the pointer.
                    if (now - lastMouseMotionTs >= 16) {   // <=60 motion events/s
                        lastMouseMotionTs = now
                        val (x, y) = mapToX(e.x, e.y)
                        desktop?.injectMotion(x, y)
                    }
                    return true
                }
                val now = System.currentTimeMillis()
                if (now - lastMouseMotionTs >= 16) {   // v0.2.96: 60/s (was 30)
                    lastMouseMotionTs = now
                    val (x, y) = mapToX(e.x, e.y)
                    desktop?.injectMotion(x, y)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // v0.2.96: a second pointer joining a BUTTONLESS contact is
                // a two-finger scroll - the one trackpad gesture this ROM
                // does not synthesize into ACTION_SCROLL. (With a button
                // held it is a finger joining mid-drag: leave the drag.)
                noteMouseActive()
                if (mouseOverlayDown || frameless) return true
                if (e.pointerCount >= 2 && (mouseHeldButton == 0 || dragLockHeld)) {
                    // v0.2.100: a second finger joining OUR OWN drag-lock
                    // press is not a chorded drag - on a trackpad it means
                    // the tap-glide became a two-finger scroll: give the
                    // synthesized press back and take the wheel. (A REAL
                    // button press - bits or BUTTON_PRESS - still keeps the
                    // drag: the guard is dragLockHeld, not != 0.)
                    if (dragLockHeld) {
                        dragLockHeld = false
                        val (x, y) = mapToX(e.x, e.y)
                        desktop?.injectClick(x, y, mouseHeldButton, press = false)
                        mouseHeldButton = 0
                    }
                    contactWheel = true
                    contactWheelMoved = false
                    contactWheelStartMs = System.currentTimeMillis()
                    val (cx, cy) = pointerCentroid(e)
                    wheelCentroidX = cx; wheelCentroidY = cy
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                noteMouseActive()
                // v0.2.96: the scroll pair is dissolving (this event still
                // counts the lifting pointer). A two-finger TAP - no travel,
                // quick lift - is a RIGHT click, fired here at the first
                // lift exactly like the finger machine fires its middle
                // click. contactWheelMoved stays true so the trailing UP of
                // the remaining finger cannot also click.
                if (contactWheel && e.pointerCount <= 2) {
                    if (!contactWheelMoved &&
                        System.currentTimeMillis() - contactWheelStartMs <= TWO_FINGER_TAP_MS) {
                        val i = e.actionIndex
                        val (x, y) = mapToX(e.getX(i), e.getY(i))
                        desktop?.injectClick(x, y, 3, press = true)
                        desktop?.injectClick(x, y, 3, press = false)
                    }
                    contactWheel = false
                    contactWheelMoved = true
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                noteMouseActive()
                noteSwipeEnd(e)
                if (mouseOverlayDown) { mouseOverlayDown = false; return true }
                if (!frameless && mouseContactDown && mouseHeldButton == 0 && !contactWheelMoved) {
                    // v0.2.96 tap-to-click: a buttonless contact that lifts
                    // fast without travel is a CLICK - the press this ROM
                    // never reports with button bits. A second quick tap
                    // lands as a second press/release pair, so the X client
                    // times it as a real double click. Suppressed when a
                    // real ACTION_BUTTON_PRESS just arrived (double-press
                    // guard) and after a two-finger gesture.
                    val now = System.currentTimeMillis()
                    if (!contactMoved && now - lastMouseBtnPressMs >= 250L &&
                        (dragLockPending || now - contactDownMs <= MOUSE_TAP_MS)) {
                        // v0.2.97: a quick lift while the classifier still
                        // said two-finger (merged stream - no POINTER_UP will
                        // ever come) is a two-finger TAP = right click; a
                        // plain quick lift stays a left click. The pair-armed
                        // contactWheel can never be true here - POINTER_UP
                        // clears it and poisons contactWheelMoved.
                        val btn = if (contactWheel) 3 else 1
                        val (x, y) = mapToX(e.x, e.y)
                        desktop?.injectClick(x, y, btn, press = true)
                        desktop?.injectClick(x, y, btn, press = false)
                        if (btn == 1) lastSynthTapMs = now
                    }
                    dragLockPending = false
                }
                releaseMouseButtons(e)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                mouseOverlayDown = false
                noteSwipeEnd(e)
                releaseMouseButtons(e)
                return true
            }
        }
        return true
    }

    /** Release everything the mouse path pressed, at the lift point. A button
     *  left down in the server wedges every later click (v0.2.79), so every
     *  exit - UP, CANCEL, even a new DOWN - goes through here.
     *  v0.2.86: when the DOWN path adopted a BUTTON_PRESS (extra == held)
     *  there is only ONE press in the server, so only ONE release is sent. */
    private fun releaseMouseButtons(e: MotionEvent) {
        cancelPendingPress()       // v0.2.98: gesture teardown fires no deferred press
        mouseContactDown = false   // v0.2.95: the contact-only touch is over too
        contactWheel = false       // v0.2.96: a scroll gesture ends here too
        dragLockPending = false    // v0.2.96: and a drag-lock that never moved
        dragLockHeld = false       // v0.2.100: and its button goes with it
        if (!frameless && (mouseHeldButton != 0 || mouseExtraButton != 0)) {
            val (x, y) = mapToX(e.x, e.y)
            val held = mouseHeldButton; val extra = mouseExtraButton
            mouseExtraButton = 0; mouseHeldButton = 0
            if (extra != 0) desktop?.injectClick(x, y, extra, press = false)
            if (held != 0 && held != extra) desktop?.injectClick(x, y, held, press = false)
        } else {
            mouseExtraButton = 0; mouseHeldButton = 0
        }
    }

    /**
     * v0.2.86 FIX ("the desktop is not interactable"): event-less release of
     * whatever the mouse path holds, at the last known X pointer position.
     * The gesture stream can stop belonging to the mouse path WITHOUT an
     * UP/CANCEL ever reaching it:
     *   - a finger joins mid-press (pointerCount becomes 2, the intercept in
     *     onTouchEvent no longer matches, and the finger state machine - which
     *     knows nothing of mouseHeldButton - owns the rest of the stream);
     *   - a ROM reclassifies the lift (toolType/source flicker between DOWN
     *     and UP sends the UP down the finger path);
     *   - the window loses focus or the view detaches mid-press.
     * In every one of those, button 1 stayed DOWN in the X server and the
     * server then swallowed every later click - the exact v0.2.78/79
     * click-swallow signature, which is why the field read "desktop not
     * interactable" with a healthy capture pipe. Called from all of the above
     * exits; a no-op when nothing is held.
     */
    private fun releaseMouseButtonsIfHeld() {
        cancelPendingPress()       // v0.2.98: gesture teardown fires no deferred press
        mouseContactDown = false   // v0.2.95: a contact-only touch ends here too
        contactWheel = false       // v0.2.96: a scroll gesture ends here too
        dragLockPending = false    // v0.2.96: and a drag-lock that never moved
        dragLockHeld = false       // v0.2.100: and its button goes with it
        if (mouseHeldButton == 0 && mouseExtraButton == 0) return
        val held = mouseHeldButton; val extra = mouseExtraButton
        mouseHeldButton = 0; mouseExtraButton = 0; mouseOverlayDown = false
        if (frameless || cursorX < 0f || cursorY < 0f) return
        val x = cursorX.toInt(); val y = cursorY.toInt()
        if (extra != 0) desktop?.injectClick(x, y, extra, press = false)
        if (held != 0 && held != extra) desktop?.injectClick(x, y, held, press = false)
    }

    // ------------------------- gestures -------------------------
    // v0.2.60 gesture set. Everything here is reachable with one or two
    // fingers on a phone, and nothing requires a hardware keyboard:
    //   tap                     -> button 1 click
    //   double-tap              -> two button-1 clicks (a real double-click)
    //   drag                    -> button 1 DOWN, motion, button 1 UP: a real
    //                              left-drag (v0.2.79 - it used to send motion
    //                              only and then release a button it had never
    //                              pressed, so nothing on the desktop could be
    //                              dragged at all)
    //   double-tap-and-hold     -> the same left-drag, but the button goes down
    //                              on the hold so you can grab before moving
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
    // v0.2.79 LEFT-DRAG. `leftHeld` means button 1 is DOWN IN THE SERVER for
    // this gesture, so something MUST release it: on finger lift, on cancel, and
    // when a second finger turns the gesture into a pan/zoom. A button left down
    // wedges every click that follows, which is why every exit path releases it.
    private var leftHeld = false
    // The finger currently down is the SECOND tap of a double-tap, so holding it
    // (without moving) grabs with button 1 down - the double-tap-and-hold alias
    // for a left-drag. A quick double-tap lifts before DT_HOLD_MS and stays a
    // double-CLICK, which is how desktop icons open (v0.2.66); that behaviour is
    // deliberately untouched.
    private var dtHoldArmed = false

    /** True while the in-progress gesture started on an overlay control. */
    private var overlayDown = false
    private var twoFingerMoved = false
    private var twoFingerDownTs = 0L
    private var twoFingerX = 0f; private var twoFingerY = 0f
    private var twoFingerStartX = 0f; private var twoFingerStartY = 0f
    private var scrollAccumY = 0f; private var scrollAccumX = 0f
    private var lastPanX = 0f; private var lastPanY = 0f

    // Sticky modifiers. v0.2.89: the helper bar that set them is gone; nothing
    // currently sets these, the plumbing stays for a future input path.
    private var stickyCtrl = false
    private var stickyAlt = false
    private var stickyMeta = false

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

    /** Runs on the first frame: shows the gesture card, once ever.
     *  v0.2.89: the helper-bar auto-open is gone with the bar itself. */
    private fun maybeShowIntro() {
        runCatching { noteInputDevices() }   // v0.2.97: input device inventory, once per session
        val p = uiPrefs
        val seen = runCatching { p?.getBoolean(KEY_INTRO_SEEN, false) ?: false }.getOrDefault(false)
        if (seen) return
        hintUntilMs = System.currentTimeMillis() + HINT_MS
        runCatching { p?.edit()?.putBoolean(KEY_INTRO_SEEN, true)?.apply() }
        removeCallbacks(fadeTick)
        postDelayed(fadeTick, FADE_TICK_MS)
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
        removeCallbacks(hoverTrailRunnable)
        hoverTrailPosted = false
        // v0.2.86: the DesktopService singleton OUTLIVES this view; a mouse
        // button still held at detach would stay down in the X server and
        // swallow the next session's clicks.
        releaseMouseButtonsIfHeld()
    }

    /**
     * v0.2.86: losing window focus mid-press (task switch, notification
     * shade, screen off, an activity on top) can end the event stream
     * without an UP or CANCEL ever reaching this view - the mouse path would
     * keep its button down in the server forever. Same rule the finger path
     * already applies on CANCEL: when the window goes away, let go.
     */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) releaseMouseButtonsIfHeld()
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

    // v0.2.105: state for the Android 14+ touchpad two-finger-swipe wheel.
    // The anchor is where the cursor was when the swipe began: the fake
    // finger moves with the gesture, but X must scroll the window UNDER THE
    // CURSOR, not wherever the synthetic finger has wandered to.
    private var tpAnchorX = 0f
    private var tpAnchorY = 0f
    private var tpLastX = 0f
    private var tpLastY = 0f
    private var tpScrollNoted = false

    @androidx.annotation.RequiresApi(34)
    private fun onTouchpadScroll(e: MotionEvent): Boolean {
        noteEventShape("touchpad-scroll", e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dismissIntroCard()
                startupDismissed = true
                noteMouseActive()
                // drop any held button / pending drag-lock so a swipe can never
                // continue (or start) a left-drag
                releaseMouseButtons(e)
                tpAnchorX = e.x; tpAnchorY = e.y
                tpLastX = e.x; tpLastY = e.y
                wheelAccumV = 0f; wheelAccumH = 0f
                if (!tpScrollNoted) {
                    tpScrollNoted = true
                    desktop?.noteInfo("trackpad scroll: TWO_FINGER_SWIPE classification -> wheel (v0.2.105)")
                }
            }
            MotionEvent.ACTION_MOVE -> {
                noteMouseActive()
                if (frameless) return true
                val dx = e.x - tpLastX
                val dy = e.y - tpLastY
                tpLastX = e.x; tpLastY = e.y
                val dir = if (mouseNaturalScroll()) -1f else 1f
                wheelAccumV = (wheelAccumV + (-dy / SCROLL_STEP_PX) * dir).coerceIn(-64f, 64f)
                wheelAccumH = (wheelAccumH + (dx / SCROLL_STEP_PX) * dir).coerceIn(-64f, 64f)
                val nv = wheelAccumV.toInt()
                if (nv != 0) wheelAccumV -= nv
                val nh = wheelAccumH.toInt()
                if (nh != 0) wheelAccumH -= nh
                if (nv != 0 || nh != 0) {
                    val (x, y) = mapToX(tpAnchorX, tpAnchorY, false)
                    desktop?.injectScrollPair(x, y, nv, nh)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                wheelAccumV = 0f; wheelAccumH = 0f
            }
        }
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        cancelEdgeSnap()   // v0.2.112: a press follows HOVER_EXIT - never snap under a click
        // v0.2.105: ANDROID 14+ TOUCHPAD GESTURES. A real touchpad's two-finger
        // swipe is NOT delivered as two pointers: the framework's gesture
        // converter emits ONE fake-finger DOWN/MOVE/UP stream classified
        // CLASSIFICATION_TWO_FINGER_SWIPE (3). Every older wheel path keyed on
        // AMBIGUOUS_GESTURE (1), so the swipe fell through to the single-contact
        // model and became a left-drag (text selection). Intercept it here,
        // before isMouseInput()/scaleDetector, and turn its delta into wheel
        // notches. Three-finger swipes and touchpad pinches are swallowed so
        // they can never become a left-drag either.
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            when (event.classification) {
                // literals: CLASSIFICATION_MULTI_FINGER_SWIPE (4) is not in the
                // compileSdk 34 android.jar, so name none of them here
                3 /* TWO_FINGER_SWIPE */ -> return onTouchpadScroll(event)
                4 /* MULTI_FINGER_SWIPE */,
                5 /* PINCH */ -> {
                    noteEventShape("touchpad-swallow", event)
                    return true
                }
            }
        }
        // v0.2.85: a real mouse/trackpad button gets the DIRECT path (press on
        // DOWN, release on UP, no finger-gesture interpretation at all). The
        // stylus deliberately does NOT take this path - it is touch by design.
        // v0.2.96: the pointerCount == 1 gate is gone - a trackpad's
        // two-finger scroll arrives as a 2-POINTER MOUSE stream on this ROM,
        // and routing that to the finger machine fed scaleDetector instead
        // of the wheel synthesis onMouseTouchEvent owns since v0.2.96.
        if (isMouseInput(event)) return onMouseTouchEvent(event)
        noteEventShape("touch", event)   // v0.2.97: evidence - what the finger path sees
        scaleDetector.onTouchEvent(event)
        val multiTouch = event.pointerCount > 1
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // v0.2.88: the startup card yields to the first touch,
                // exactly like the intro card - never between user and
                // desktop.
                startupDismissed = true
                // v0.2.85: a finger is back - the synthetic cursor is the only
                // cursor a finger has, so un-hide it (the system draws one for
                // mice, never for fingers).
                realMouseCursor = false
                // v0.2.86: if a mouse gesture was mid-press when this finger
                // landed (its UP went elsewhere or never came), its button is
                // still down in the server and would swallow this whole
                // gesture. Release before starting finger state.
                releaseMouseButtonsIfHeld()
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
                leftHeld = false; dtHoldArmed = false
                twoFingerMoved = false
                removeCallbacks(longPressRunnable)
                removeCallbacks(dtHoldRunnable)
                // v0.2.3: no X server behind a frameless pane - a long-press
                // right-click there injects into nothing, so don't arm it.
                if (!frameless) {
                    postDelayed(longPressRunnable, LONG_PRESS_MS)
                    dtHoldArmed = downTime - lastTapUpMs < DOUBLE_TAP_MS &&
                        abs(event.x - lastTapUpX) < TAP_SLOP &&
                        abs(event.y - lastTapUpY) < TAP_SLOP
                    if (dtHoldArmed) postDelayed(dtHoldRunnable, DT_HOLD_MS)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                panning = true
                removeCallbacks(longPressRunnable)
                // A second finger ends any right-button hold, otherwise the
                // button stays down with nothing left to release it.
                releaseRightButton(event)
                // v0.2.79: same for a left-drag in progress - the gesture is now
                // a pan/zoom, and button 1 must not stay down through it.
                removeCallbacks(dtHoldRunnable)
                releaseLeftDrag(event)
                // v0.2.86: ...and same for a MOUSE press in progress: the
                // stream just became multi-pointer, the onTouchEvent intercept
                // no longer matches, and the mouse path will never see an UP.
                releaseMouseButtonsIfHeld()
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
                            // v0.2.131 NATURAL TOUCH SCROLL: fingers travel DOWN
                            // (dy>0) => the content follows the fingers (moves
                            // down, like every touchscreen) => wheel up = button
                            // 4. The old hard-coded mapping was mouse-wheel
                            // semantics and felt backwards on the pad; it now
                            // follows the same natural-scroll setting as every
                            // other wheel path (classic setting = old mapping).
                            val touchDir = if (mouseNaturalScroll()) 1f else -1f
                            desktop?.injectScroll(x, y, if (n * touchDir > 0) 4 else 5, abs(n))
                        }
                        n = (scrollAccumX / SCROLL_STEP_PX).toInt()
                        if (n != 0) {
                            scrollAccumX -= n * SCROLL_STEP_PX
                            desktop?.injectScroll(x, y, if (n * touchDir > 0) 6 else 7, abs(n))
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
                    removeCallbacks(dtHoldRunnable)
                    // v0.2.79 THE DRAG FIX. A drag is ButtonPress, MotionNotify…,
                    // ButtonRelease. This branch used to set `dragging` and send
                    // ONLY motion - injectMotion() carries no button - then
                    // release button 1 on lift without ever having pressed it.
                    // The server therefore saw a hover plus a stray release, and
                    // nothing could be dragged: no rubber-band select on the
                    // desktop, no window move by the titlebar, no slider. (A
                    // right-drag worked only because long-press really does press
                    // button 3 first.) The press goes where the finger LANDED, not
                    // where it is now, so the 18 px of travel is part of the drag.
                    // Skipped while button 3 is held - that is a right-drag.
                    if (!leftHeld && !rightHeld && !longPressFired && !frameless) {
                        val (px, py) = mapToX(downX, downY)
                        desktop?.injectClick(px, py, button = 1, press = true)
                        leftHeld = true
                    }
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
                removeCallbacks(dtHoldRunnable)
                // v0.2.86: classification flicker guard - if the DOWN of THIS
                // gesture went to the mouse path (toolType/source said mouse)
                // but its UP arrived classified as finger, the mouse path
                // still holds the button. Release it here; a no-op otherwise.
                releaseMouseButtonsIfHeld()
                if (rightHeld) {
                    releaseRightButton(event)
                    releaseLeftDrag(event)
                    dragging = false; panning = false
                    return true
                }
                if (overlayDown) {
                    releaseLeftDrag(event)
                    overlayDown = false; dragging = false; panning = false; return true
                }
                // v0.2.66 FIX (input-dead audit): a double-tap MUST reach X as a
                // real double-click. pcmanfm desktop icons, GTK "Execute File"
                // dialogs and file managers all activate on the SECOND
                // ButtonPress; v0.2.37-v0.2.65 consumed the second tap here for
                // the fit<->fill view toggle, so the desktop only ever saw ONE
                // click per gesture - i.e. "the desktop shows but nothing
                // opens", no matter how healthy the transport was. The view
                // toggle lived on the FIT chip until v0.2.89 removed the
                // helper bar; the view now stays fit-to-screen.
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
                    if (leftHeld) {
                        // v0.2.79: end the drag where the finger lifted - motion to
                        // the final point, then ButtonRelease(1) there. `leftHeld`,
                        // not `dragging`, is the test: a double-tap-and-hold that
                        // never moved still has a real button down to release.
                        releaseLeftDrag(event)
                    } else if (dragging) {
                        // travelled past the threshold but never pressed (a
                        // right-drag that already released above, or a frameless
                        // scroll): nothing to release, and it must not become a
                        // click at the end of a drag either.
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
                removeCallbacks(dtHoldRunnable)
                releaseRightButton(event)
                // v0.2.79: a cancelled gesture (parent stole the touch, screen
                // off, palm rejection) still has to let go of button 1.
                releaseLeftDrag(event)
                // v0.2.86: ...and of anything the mouse path holds.
                releaseMouseButtonsIfHeld()
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

    /** v0.2.79: double-tap-and-HOLD grabs with button 1 down, so a slow drag
     *  after it is a left-drag even if the finger never travelled the 18 px
     *  that arms the plain drag. */
    private val dtHoldRunnable = Runnable {
        if (!dragging && !panning && !rightHeld && !leftHeld && !frameless) {
            val (x, y) = mapToX(downX, downY)
            desktop?.injectClick(x, y, button = 1, press = true)
            leftHeld = true
            dragging = true            // motions from here on are drag motions
            removeCallbacks(longPressRunnable)
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }

    /** Release button 1 if this gesture pressed it. Idempotent, and cheap enough
     *  to call from every exit path - the alternative is a stuck button. */
    private fun releaseLeftDrag(e: MotionEvent) {
        if (!leftHeld) return
        leftHeld = false
        val (x, y) = mapToX(e.x, e.y)
        desktop?.fakeButtonReleaseOnly(x, y)
    }

    private fun releaseRightButton(e: MotionEvent) {
        if (!rightHeld) return
        rightHeld = false
        val (x, y) = mapToX(e.x, e.y)
        desktop?.injectClick(x, y, button = 3, press = false)
    }

    // ---------------- on-screen key helpers (v0.2.89: the chip bar is gone;
    // the keysym plumbing below stays) ----------------

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

    /** Overlay hit-test. Returns true when the touch was consumed by a control.
     *  v0.2.89: the "keys" toggle and the helper key bar are gone (field
     *  request), so no overlay control is tappable any more. Kept as a stub
     *  because both touch paths still call it. */
    private fun onOverlayTouch(sx: Float, sy: Float): Boolean {
        return false
    }

    /** v0.2.111: diagnostic badge on/off (Settings, read live; default off). */
    private fun showHud(): Boolean = settingsPrefs?.getBoolean("desktop_hud", false) ?: false

    // v0.2.112: the hot input paths ask at most once a second.
    private var hudCache = false
    private var hudCacheAtMs = 0L
    private fun hudCached(): Boolean {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - hudCacheAtMs > 1_000L) { hudCache = showHud(); hudCacheAtMs = now }
        return hudCache
    }

    // v0.2.112 BOTTOM-EDGE REACH (field: "I couldn't open the taskbar"). The
    // dock auto-hides and comes back when the X pointer touches the bottom
    // edge - but the pane does not end at the bottom of the screen (the
    // status line sits under it), so a trackpad pointer swept downwards
    // leaves the view a few pixels SHORT of the edge and X never sees the
    // last row. Leaving the pane downwards near its bottom now parks the X
    // pointer on the bottom row. Deferred 60 ms and cancelled by any touch
    // stream, because Android also sends HOVER_EXIT right before a click.
    private var edgeSnap: Runnable? = null
    private var lastHoverViewY = -1f

    private fun cancelEdgeSnap() { edgeSnap?.let { removeCallbacks(it) }; edgeSnap = null }

    private fun drawOverlay(canvas: Canvas) {
        // v0.2.89: the top-right "keys" toggle and the 40dp helper key bar are
        // removed (field request: the row duplicated the removed terminal
        // extra-keys strip and covered tint2's panel area). Only the sticky-
        // modifier hint remains, and nothing sets sticky modifiers from the
        // UI any more - kept for a future input path.
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
        // v0.2.66 gesture contract (double-tap is a real double-click).
        // v0.2.89: the FIT chip is gone with the helper bar - the view stays
        // fit-to-screen, so the legend drops the FIT=fill mention.
        val hp = Paint().apply { color = Color.argb(0x78, 0xFF, 0xFF, 0xFF); textSize = sp(9f) }
        // Two lines, not three: sp(72f) belongs to the sticky-modifier
        // "next key:" hint and a third legend line overprints it.
        canvas.drawText("tap=click  drag=left-drag(held)  2tap+HOLD=grab  double-tap=2xclick",
            16f, sp(52f), hp)
        canvas.drawText("long-press=right-drag  2-finger=middle/scroll  pinch=zoom",
            16f, sp(62f), hp)
    }

    /**
     * v0.2.61: the one-shot "how do I drive this" card.
     *
     * Drawn over the LIVE desktop (the frameless pane already has its own
     * cheat-sheet), faded out over the last third of [HINT_MS], dismissed by
     * any touch.
     */
    private fun drawIntroCard(canvas: Canvas) {
        val now = System.currentTimeMillis()
        if (hintUntilMs <= now) return
        val remain = hintUntilMs - now
        val fadeMs = HINT_MS / 3
        val a = if (remain < fadeMs) (255f * remain / fadeMs).toInt().coerceIn(0, 255) else 255
        val lines = arrayOf(
            "tap  =  left click   \u00b7   double-tap  =  double click",
            "drag  =  left-drag: button held, so you can select, move",
            "            windows, and drag sliders  (v0.2.79)",
            "double-tap-and-HOLD  =  grab first, then drag",
            "long-press  =  right click (hold + drag to right-drag)",
            "two-finger tap  =  middle click (paste in a terminal)",
            "two-finger drag  =  scroll wheel  \u00b7  pans when zoomed",
            // v0.2.79: this said "double-tap = fit / fill", which was wrong
            // since v0.2.66 made double-tap a real double-click. The FIT chip
            // that replaced it is gone too - v0.2.89 removed the helper key
            // bar, so the view stays fit-to-screen.
            "pinch  =  zoom",
        )
        val pad = dp(10f)
        val lh = sp(12.5f)
        val titleH = sp(16f)
        val cardW = width - dp(52f)
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
        // v0.2.85: mouse hover injection budget - at most ~30 X motions per
        // second, with a trailing flush so the final position always lands.
        // v0.2.96: 16 ms (~60/s) - was 33: over the XTEST pump pending
        // motions coalesce to the newest anyway, so the extra rate only
        // sharpens the pointer; the trailing flush still guarantees the
        // final position lands.
        const val HOVER_MIN_INTERVAL_MS = 16L
        // v0.2.86: synthetic-cursor suppression EXPIRES this long after the
        // last real mouse/trackpad event. Long enough that an active system
        // cursor is never doubled by ours (the v0.2.85 field complaint);
        // short enough that an idle, disconnected, or merely misclassified
        // pointer can never leave the pane permanently cursor-less (the
        // v0.2.86 "no virtual mouse" complaint).
        const val REAL_MOUSE_GRACE_MS = 2_500L
        // v0.2.79 double-tap-and-hold: the window in which a second tap counts,
        // how far it may wander, and how long the hold must last before it means
        // "grab" rather than "double-click". DT_HOLD_MS sits well under
        // LONG_PRESS_MS so the two holds never race for the same finger.
        const val DOUBLE_TAP_MS = 300L
        const val TAP_SLOP = 40f
        const val DT_HOLD_MS = 170L
        const val TWO_FINGER_TAP_MS = 260L
        const val TWO_FINGER_TAP_SLOP = 26f
        const val SCROLL_STEP_PX = 44f
        // v0.2.96 trackpad contact synthesis: how long a buttonless contact
        // may last to count as a tap, and how near a new contact must follow
        // a previous tap to arm the drag-lock idiom.
        const val MOUSE_TAP_MS = 250L
        const val MOUSE_DRAG_LOCK_MS = 350L
        // v0.2.98: a generic-motion BUTTON_PRESS waits this long before it
        // becomes a real X press - hover motion inside the window means the
        // "press" was the start of a two-finger scroll and is cancelled.
        const val DEFER_PRESS_MS = 90L
        // v0.2.61 intro card: long enough to read seven lines on a phone,
        // short enough that it is gone before the user wants the screen back.
        const val HINT_MS = 11_000L
        const val FADE_TICK_MS = 200L
        const val KEY_INTRO_SEEN = "gesture_intro_seen"
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
