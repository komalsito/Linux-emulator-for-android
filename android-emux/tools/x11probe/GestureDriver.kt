// Drives the exact X11Client call sequences that DesktopService's new gesture
// layer uses, so the wire behaviour is measured rather than assumed:
//
//   injectScroll()        -> fakeButton(4|5|6|7, press) + fakeButton(..., release)
//   middle click          -> fakeButton(2, press) + fakeButton(2, release)
//   injectKeysymCombo()   -> fakeKey(modKeycode, true); typeKeysymTap(ks); fakeKey(modKeycode, false)
//   right-drag            -> fakeButton(3, press); fakeMotion(...); fakeButton(3, release)
//
// `xev -event button -event keyboard` is the witness: it prints what the SERVER delivered,
// including the modifier state mask, so a missing Ctrl or a wrong button number
// is visible rather than silently accepted.
import com.emux.x11.X11Client

fun main(args: Array<String>) {
    val display = args[0].toInt()
    val x = args[1].toInt()
    val y = args[2].toInt()
    val c = X11Client(host = "127.0.0.1", display = display, unixSocketPath = null)
    c.connect()
    println("connected: xtest=${c.xtestOpcode} keymap=${c.keymapSize()} ${c.screenWidth}x${c.screenHeight}")

    // Focus the witness window first: X delivers key events to the focus, not
    // to the pointer, so without this the keycode assertions would be empty.
    c.fakeButton(1, x, y, press = true)
    c.fakeButton(1, x, y, press = false)
    Thread.sleep(400)

    println("MARK middle-click")
    c.fakeButton(2, x, y, press = true)
    c.fakeButton(2, x, y, press = false)
    Thread.sleep(250)

    println("MARK wheel-up x3")
    repeat(3) { c.fakeButton(4, x, y, press = true); c.fakeButton(4, x, y, press = false) }
    Thread.sleep(250)

    println("MARK wheel-down x2")
    repeat(2) { c.fakeButton(5, x, y, press = true); c.fakeButton(5, x, y, press = false) }
    Thread.sleep(250)

    println("MARK wheel-left / wheel-right")
    c.fakeButton(6, x, y, press = true); c.fakeButton(6, x, y, press = false)
    c.fakeButton(7, x, y, press = true); c.fakeButton(7, x, y, press = false)
    Thread.sleep(250)

    // Ctrl+C exactly as injectKeysymCombo() composes it.
    val ctrl = c.keysymKeycode(0xFFE3)          // Control_L
    val alt = c.keysymKeycode(0xFFE9)           // Alt_L
    println("MARK ctrl+c (Control_L keycode=$ctrl)")
    check(ctrl > 0) { "Control_L is not in the server keymap" }
    c.fakeKey(ctrl, true)
    c.typeKeysymTap(0x63)                       // 'c'
    c.fakeKey(ctrl, false)
    Thread.sleep(250)

    println("MARK alt+tab (Alt_L keycode=$alt, Tab keysym 0xFF09)")
    if (alt > 0) {
        c.fakeKey(alt, true)
        c.typeKeysymTap(0xFF09)
        c.fakeKey(alt, false)
    }
    Thread.sleep(250)

    // Ctrl+Shift+T from a SINGLE ctrl flag: the caller does not hold Shift.
    // typeKeysymTap() presses the shift level it read out of the server's own
    // keymap, so the server sees state 0x5 (ShiftMask|ControlMask) on keycode
    // 28 -> keysym 0x54 'T'. That is the whole point of building the keymap
    // instead of hardcoding keycodes.
    println("MARK ctrl+T (uppercase via keymap shift level, no explicit Shift hold)")
    c.fakeKey(ctrl, true)
    c.typeKeysymTap(0x54)                       // 'T'
    c.fakeKey(ctrl, false)
    Thread.sleep(250)

    println("MARK right-drag (button 3 held across motion)")
    c.fakeButton(3, x, y, press = true)
    for (i in 1..6) { c.fakeMotion(x + i * 8, y + i * 4); Thread.sleep(25) }
    c.fakeButton(3, x + 48, y + 24, press = false)
    Thread.sleep(300)

    val errs = c.takeErrors()
    println("X errors: ${errs.size}${if (errs.isEmpty()) "" else " -> " + errs.joinToString { it.describe() }}")
    c.close()
    println(if (errs.isEmpty()) "DRIVER OK" else "DRIVER FAIL")
}
