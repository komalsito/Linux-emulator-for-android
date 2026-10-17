package com.emux.terminal

import android.content.Context
import android.util.AttributeSet
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Termux-style extra-keys row: ESC / TAB / CTRL / ALT / arrows / PIPE etc.
 * Implemented as plain TextViews (no WebView, no image assets) → trivial draw cost.
 * CTRL/ALT latch for exactly one keypress, like Termux.
 */
class ExtraKeysView(ctx: Context, attrs: AttributeSet? = null) : HorizontalScrollView(ctx, attrs) {

    interface Listener { fun onKey(code: ExtraKey) }

    enum class ExtraKey(val label: String, val bytes: ByteArray) {
        ESC("esc", byteArrayOf(0x1B)),
        TAB("tab", byteArrayOf('\t'.code.toByte())),
        CTRL("ctrl", byteArrayOf()),       // latch modifier
        ALT("alt", byteArrayOf()),         // latch modifier
        UP("↑", byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte())),
        DOWN("↓", byteArrayOf(0x1B, '['.code.toByte(), 'B'.code.toByte())),
        LEFT("←", byteArrayOf(0x1B, '['.code.toByte(), 'D'.code.toByte())),
        RIGHT("→", byteArrayOf(0x1B, '['.code.toByte(), 'C'.code.toByte())),
        HOME("home", byteArrayOf(0x1B, '['.code.toByte(), 'H'.code.toByte())),
        END("end", byteArrayOf(0x1B, '['.code.toByte(), 'F'.code.toByte())),
        PIPE("|", "|".toByteArray()),
        DASH("-", "-".toByteArray()),
        CARET("^", "^".toByteArray()),
        SEMI(";", ";".toByteArray()),
    }

    var listener: Listener? = null
    private var ctrlDown = false
    private var altDown = false

    private val keyViews = mutableListOf<TextView>()

    init {
        isHorizontalScrollBarEnabled = false
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        val order = listOf(
            ExtraKey.ESC, ExtraKey.TAB, ExtraKey.CTRL, ExtraKey.ALT,
            ExtraKey.UP, ExtraKey.DOWN, ExtraKey.LEFT, ExtraKey.RIGHT,
            ExtraKey.HOME, ExtraKey.END, ExtraKey.PIPE, ExtraKey.DASH,
        )
        for (k in order) {
            val tv = TextView(context).apply {
                text = k.label
                setPadding(dp(14), dp(8), dp(14), dp(8))
                textSize = 13f
                setTextColor(0xFFCCCCCC.toInt())
                setBackgroundColor(0xFF16191D.toInt())
                alpha = if (k == ExtraKey.CTRL || k == ExtraKey.ALT) 0.7f else 1f
            }
            tv.setOnClickListener { handle(k, tv) }
            row.addView(tv)
            keyViews += tv
        }
        addView(row)
    }

    private fun handle(k: ExtraKey, tv: TextView) {
        when (k) {
            ExtraKey.CTRL -> { ctrlDown = !ctrlDown; refreshLatches() }
            ExtraKey.ALT -> { altDown = !altDown; refreshLatches() }
            else -> {
                val out = when {
                    ctrlDown && k.bytes.isNotEmpty() -> ctrlTransform(k)
                    altDown && k.bytes.isNotEmpty() -> byteArrayOf(0x1B) + k.bytes
                    else -> k.bytes
                }
                listener?.onKey(k)
                if (out.isNotEmpty()) currentSessionSink?.invoke(out)
                if (ctrlDown || altDown) { ctrlDown = false; altDown = false; refreshLatches() }
            }
        }
    }

    /** Bytes the Activity wires this view to (decouples widget from session model). */
    var currentSessionSink: ((ByteArray) -> Unit)? = null

    private fun ctrlTransform(k: ExtraKey): ByteArray {
        // map visible keys to control codes where meaningful
        return when (k) {
            ExtraKey.CARET -> byteArrayOf(0x1E)   // ^^ = REFS (search-backward in readline)
            else -> {
                val first = k.bytes.firstOrNull()?.toInt() ?: 0
                if (first in 'a'.code..'z'.code) byteArrayOf((first - 'a'.code + 1).toByte())
                else k.bytes
            }
        }
    }

    private fun refreshLatches() {
        for (tv in keyViews) {
            val l = tv.text.toString()
            tv.alpha = when (l) {
                "ctrl" -> if (ctrlDown) 1f else 0.7f
                "alt" -> if (altDown) 1f else 0.7f
                else -> 1f
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
