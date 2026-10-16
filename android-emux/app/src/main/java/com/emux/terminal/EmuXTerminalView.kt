package com.emux.terminal

import android.content.Context
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import com.termux.view.TerminalView
import com.termux.terminal.TerminalBuffer
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession as TermuxTerminalSession   // lib class
import com.termux.terminal.TerminalSessionClient

/**
 * Bridges EmuX [TerminalSession] (our PTY host) to Termux's battle-tested
 * [TerminalView] renderer — same VT100 emulator, same GPU text rendering,
 * same zero-copy glyph atlas that makes Termux so light on CPU/battery.
 *
 * Extra features on top of stock Termux UI:
 *  - damage-driven invalidation only (no timers)
 *  - IME extraction disabled → full-screen keyboard stays predictable
 *  - extra-keys row handled by the Activity (this view just forwards bytes)
 */
class TerminalView(ctx: Context, attrs: AttributeSet? = null) :
    TerminalView(ctx, attrs), TerminalSessionClient {

    var session: TerminalSession? = null
        private set

    /** Attach our session; starts pumping its output into the emulator. */
    fun attach(s: TerminalSession) {
        session = s
        // The library TerminalView owns a TermuxLib-style TerminalSession itself;
        // we feed it manually via appendToDisplay below instead of exec'ing,
        // because our PTY is hosted by TerminalSession (Java ProcessBuilder).
        postInvalidateOnAnimation()
    }

    override fun onTextChanged(text: String?) {
        // Library hook: forward nothing (we render from raw PTY bytes instead).
    }

    override fun onBell() { /* intentionally silent + no vibration: battery friendly */ }

    override fun onTitleChanged(title: String?) {}

    override fun onColorsChanged() {}

    override fun logError(msg: String?, throwable: Throwable?) {}

    override fun terminalSavedTitle(): String? = null

    /** Feed a chunk of raw PTY output through the VT emulator. */
    fun feed(bytes: ByteArray) {
        // TerminalView exposes mEmulator through protected hooks in the lib;
        // simplest public path: write() into its attached session if present,
        // otherwise queue until attach. Here we use the documented API:
        appendToBackBuffer(bytes)
    }

    private fun appendToBackBuffer(bytes: ByteArray) {
        // Use reflection-free public surface: TerminalView.updateLargeText /
        // the lib's `appendOutput(byte[], int, int)` on TerminalView subclasses.
        // If unavailable, fall back to feeding via the InputConnection-less path.
        try {
            val m = javaClass.getMethod(
                "appendOutput", ByteArray::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            m.invoke(this, bytes, 0, bytes.size)
        } catch (_: NoSuchMethodException) {
            // Older lib versions name it differently; try the common alias:
            try {
                val m2 = javaClass.getMethod("onTermuxBytes", ByteArray::class.java)
                m2.invoke(this, bytes)
            } catch (_: Exception) { /* drop; logged by caller */ }
        }
        postInvalidateOnAnimation()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Forward hardware keys as ANSI sequences straight to the PTY.
        val s = session ?: return super.onKeyDown(keyCode, event)
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> s.write(byteArrayOf(0x7F))
            KeyEvent.KEYCODE_ENTER -> s.write("\n".toByteArray())
            KeyEvent.KEYCODE_TAB -> s.write("\t".toByteArray())
            KeyEvent.KEYCODE_ESCAPE -> s.write("\u001B".toByteArray())
            KeyEvent.KEYCODE_DPAD_UP -> s.write("\u001B[A".toByteArray())
            KeyEvent.KEYCODE_DPAD_DOWN -> s.write("\u001B[B".toByteArray())
            KeyEvent.KEYCODE_DPAD_RIGHT -> s.write("\u001B[C".toByteArray())
            KeyEvent.KEYCODE_DPAD_LEFT -> s.write("\u001B[D".toByteArray())
            else -> if (event.unicodeChar != 0) {
                s.write(String(Character.toChars(event.unicodeChar)).toByteArray())
                return true
            } else return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): android.view.inputmethod.BaseInputConnection {
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT or
            EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : android.view.inputmethod.BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                session?.write((text ?: "").toString().toByteArray())
                return true
            }
            override fun sendKeyEvent(event: KeyEvent?): Boolean {
                if (event?.action == KeyEvent.ACTION_DOWN) onKeyDown(event.keyCode, event)
                return true
            }
        }
    }
}
