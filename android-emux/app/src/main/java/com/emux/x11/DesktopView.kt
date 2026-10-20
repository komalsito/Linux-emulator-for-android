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

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var lastFrameId = -1

    private val srcRect = Rect()
    private val dstRect = Rect()

    init { keepScreenOn = false /* activity decides; view never holds locks */ }

    // v0.1.25 DIAGNOSTIC: one-shot red flash so a permanently black pane can
    // be split into "view never draws" (no red at all -> layout/z-order/size)
    // vs "frames never arrive" (red appears, then dark background).
    @Volatile private var debugRedUntilMs = 0L
    fun debugFlashRed() {
        debugRedUntilMs = System.currentTimeMillis() + 1200
        postInvalidateOnAnimation()
        postDelayed({ postInvalidateOnAnimation() }, 1300)
    }

    fun onNewFrame(frameId: Int) {
        if (frameId != lastFrameId) { lastFrameId = frameId; postInvalidateOnAnimation() }
    }

    override fun onDraw(canvas: Canvas) {
        if (debugRedUntilMs > System.currentTimeMillis()) {
            canvas.drawColor(Color.RED); return
        }
        canvas.drawColor(Color.rgb(0x10, 0x14, 0x1A))
        val f = desktop?.currentFrame ?: run {
            // v0.1.25 DIAGNOSTIC: no frames yet — say so ON the pane instead of
            // showing an unexplained uniform dark rectangle.
            val p = Paint().apply { color = Color.argb(0xB0, 0xFF, 0x8A, 0x65); textSize = 28f }
            canvas.drawText("waiting for X frames…", 24f, height / 2f, p)
            // v0.1.41: paint the failure tail ON the pane too — the status
            // TextView is easy to miss off-screen, and these lines are the only
            // field-visible proof of WHY X/capture died (Xvfb log, apk, nc…).
            // v0.1.45: while waiting, also show the live capture/X log tail so
            // the pane is never silent (errorText only lands on terminal events).
            val err = desktop?.errorText?.value
                ?: desktop?.diagTail()?.takeIf { it.isNotBlank() }
            if (!err.isNullOrBlank()) {
                val pe = Paint().apply { color = Color.argb(0xD0, 0xFF, 0x8A, 0x65); textSize = 18f }
                var y = height / 2f + 36f
                for (line in err.lineSequence().filter { it.isNotBlank() }.take(10)) {
                    canvas.drawText(line.take(150), 24f, y, pe)
                    y += 24f
                }
            }
            // keep repainting while frameless so late errorText appears
            postInvalidateOnAnimation()
            return
        }
        // fit once, then honor user zoom/pan
        val fitScale = min(width.toFloat() / f.width, height.toFloat() / f.height)
        val s = max(fitScale, fitScale * scale.coerceIn(1f, 6f))
        val w = f.width * s; val h = f.height * s
        val left = (width - w) / 2 + tx
        val top = (height - h) / 2 + ty
        srcRect.set(0, 0, f.width, f.height)
        dstRect.set(left.toInt(), top.toInt(), (left + w).toInt(), (top + h).toInt())
        canvas.drawBitmap(f, srcRect, dstRect, bmpPaint)
    }

    /** screen px → X px (clamped into server geometry). */
    private fun mapToX(sx: Float, sy: Float): Pair<Int, Int> {
        val f = desktop?.currentFrame ?: return 0 to 0
        val fitScale = min(width.toFloat() / f.width, height.toFloat() / f.height)
        val s = max(fitScale, fitScale * scale.coerceIn(1f, 6f))
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
                dragging = false; panning = false; longPressFired = false
                removeCallbacks(longPressRunnable)
                postDelayed(longPressRunnable, 450)
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
                val (x, y) = mapToX(event.x, event.y)
                if (!longPressFired && !panning) {
                    if (dragging) {
                        // releasing after drag: send button release at final pos
                        desktop?.fakeButtonReleaseOnly(x, y)
                    } else {
                        desktop?.injectClick(x, y, button = 1, press = true)
                        postDelayed({ desktop?.injectClick(x, y, button = 1, press = false) }, 40)
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

    private val longPressRunnable = Runnable {
        if (!dragging && !panning) {
            longPressFired = true
            val (x, y) = mapToX(downX, downY)
            desktop?.injectClick(x, y, button = 3, press = true)
            postDelayed({ desktop?.injectClick(x, y, button = 3, press = false) }, 60)
        }
    }
}
