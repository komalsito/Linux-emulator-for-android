// Drives the REAL compiled com.emux.x11.X11Client on a plain JVM against a live
// Xvfb, to prove the XTEST input path works end to end - not a Python
// reimplementation of it, the actual shipped Kotlin class.
//
// X11Client only touches android.net.LocalSocket when a unixSocketPath is given
// AND exists; with null it takes the java.net.Socket path, and the LocalSocket
// branch is wrapped in catch(Exception) anyway, so android.jar's "Stub!" bodies
// are never reached.
import com.emux.x11.X11Client

fun main(args: Array<String>) {
    val display = if (args.isNotEmpty()) args[0].toInt() else 94
    val text = if (args.size > 1) args[1] else "kotlin XTEST works"

    val c = X11Client(host = "127.0.0.1", display = display, unixSocketPath = null)
    c.connect()
    println("HANDSHAKE  transport=${c.transport}  xtestOpcode=${c.xtestOpcode}")
    println("           keymapSize=${c.keymapSize()} keysyms  geometry=${c.screenWidth}x${c.screenHeight}")
    println("           WM running=${c.isWindowManagerRunning()}")
    check(c.xtestOpcode > 127) { "XTEST opcode ${c.xtestOpcode} is not a valid extension opcode" }
    check(c.keymapSize() > 50) { "keymap only has ${c.keymapSize()} keysyms - GetKeyboardMapping parse is wrong" }

    // Click where the harness put the xterm, so the keystrokes have a focus.
    c.fakeMotion(240, 260)
    c.fakeButton(1, 240, 260, press = true)
    c.fakeButton(1, 240, 260, press = false)
    Thread.sleep(400)

    val ok = c.typeString(text)
    println("TYPE       typeString(\"$text\") -> $ok")
    val ret = c.typeKeysymTap(0xFF0D)          // XK_Return
    println("RETURN     typeKeysymTap(0xFF0D) -> $ret")

    // BackSpace handling: type, erase, retype - proves keysym->keycode mapping
    // for a non-printable key, not just Latin-1 characters.
    c.typeString("abcd")
    repeat(4) { c.typeKeysymTap(0xFF08) }      // XK_BackSpace
    c.typeString("XY")
    c.typeKeysymTap(0xFF0D)

    // Uppercase must go through the shift level from the server's own keymap.
    c.typeString("MiXeD CaSe 123 !@#")
    c.typeKeysymTap(0xFF0D)

    val errs = c.takeErrors()
    println("ERRORS     ${errs.size} X errors${if (errs.isEmpty()) "" else ": " + errs.joinToString { it.describe() }}")
    c.close()
    println(if (errs.isEmpty() && ok && ret) "RESULT     PASS" else "RESULT     FAIL")
}
