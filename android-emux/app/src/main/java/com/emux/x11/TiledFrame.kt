package com.emux.x11

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/**
 * v0.2.112 THE LAG FIX: the desktop picture as a grid of small bitmaps.
 *
 * The pane used to be ONE Bitmap. With hardware rendering, Android uploads a
 * bitmap to the GPU as a whole texture every time any of its pixels change -
 * there is no partial upload for a plain Bitmap. At Retina + 24-bit
 * (~2400x1356x4) that is ~13 MB per change: a blinking text cursor, a
 * hovered button or one keystroke cost a full-screen upload, many times per
 * second, which is the cursor/desktop lag of the v0.2.111 field report.
 *
 * Here every [TILE_W]x[TILE_H] tile is its own Bitmap: setPixels only touches
 * (and so only re-uploads) the tiles the change actually covers - a few
 * hundred KB for typical damage instead of the whole screen. Drawing is one
 * drawBitmap per tile, edges snapped to whole device pixels so the scaled
 * tiles meet without seams.
 */
class TiledFrame(val width: Int, val height: Int, val config: Bitmap.Config) {
    private val cols = (width + TILE_W - 1) / TILE_W
    private val rows = (height + TILE_H - 1) / TILE_H
    private val tiles: Array<Bitmap> = Array(cols * rows) { i ->
        val tx = i % cols; val ty = i / cols
        Bitmap.createBitmap(
            minOf(TILE_W, width - tx * TILE_W),
            minOf(TILE_H, height - ty * TILE_H),
            config)
    }
    private val src = Rect()
    private val dst = RectF()

    /** Same contract as [Bitmap.setPixels], for the whole picture. */
    fun setPixels(px: IntArray, offset: Int, stride: Int, x: Int, y: Int, w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val x1 = minOf(x + w, width); val y1 = minOf(y + h, height)
        var ty = y / TILE_H
        while (ty * TILE_H < y1) {
            val tileY0 = ty * TILE_H
            val ry0 = maxOf(y, tileY0); val ry1 = minOf(y1, tileY0 + TILE_H)
            var tx = x / TILE_W
            while (tx * TILE_W < x1) {
                val tileX0 = tx * TILE_W
                val rx0 = maxOf(x, tileX0); val rx1 = minOf(x1, tileX0 + TILE_W)
                if (rx1 > rx0 && ry1 > ry0) {
                    tiles[ty * cols + tx].setPixels(
                        px, offset + (ry0 - y) * stride + (rx0 - x), stride,
                        rx0 - tileX0, ry0 - tileY0, rx1 - rx0, ry1 - ry0)
                }
                tx++
            }
            ty++
        }
    }

    /** Draw the whole picture into [left],[top] .. +[w],[h] (view pixels). */
    fun draw(canvas: Canvas, left: Float, top: Float, w: Float, h: Float, paint: Paint?) {
        val sx = w / width; val sy = h / height
        for (ty in 0 until rows) {
            val y0 = ty * TILE_H
            val t = Math.round(top + y0 * sy).toFloat()
            val b = Math.round(top + minOf(y0 + TILE_H, height) * sy).toFloat()
            if (b < 0f || t > canvas.height) continue
            for (tx in 0 until cols) {
                val x0 = tx * TILE_W
                val l = Math.round(left + x0 * sx).toFloat()
                val r = Math.round(left + minOf(x0 + TILE_W, width) * sx).toFloat()
                if (r < 0f || l > canvas.width) continue
                val bmp = tiles[ty * cols + tx]
                src.set(0, 0, bmp.width, bmp.height)
                dst.set(l, t, r, b)
                canvas.drawBitmap(bmp, src, dst, paint)
            }
        }
    }

    companion object {
        // 256 x 128 x 4 B = 128 KB per tile upload at true color.
        const val TILE_W = 256
        const val TILE_H = 128
    }
}
