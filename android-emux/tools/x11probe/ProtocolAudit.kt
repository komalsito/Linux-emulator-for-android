// Audits the X11 wire protocol implementation in the REAL compiled X11Client
// against ground truth. This is the harness that caught two silent bugs:
// internAtom() and getProperty() both wrote a BOOL into the request BODY
// instead of the header byte, inflating the request by 4 bytes, so the server
// answered BadLength for every atom - and isWindowManagerRunning() reported
// false forever while Openbox was running.
//
// Run with protocol_audit.sh; compare the printed values against xprop.
import com.emux.x11.X11Client

fun main(args: Array<String>) {
    val c = X11Client(host = "127.0.0.1", display = args[0].toInt(), unixSocketPath = null)
    c.connect()
    var fail = 0
    fun ck(label: String, ok: Boolean, detail: String) {
        println((if (ok) "  [PASS] " else "  [FAIL] ") + label.padEnd(46) + detail)
        if (!ok) fail++
    }

    println("HANDSHAKE")
    ck("transport negotiated", c.transport == "tcp" || c.transport == "unix", c.transport)
    ck("XTEST extension opcode > 127", c.xtestOpcode > 127, "opcode=${c.xtestOpcode}")
    ck("screen geometry plausible", c.screenWidth in 64..8192 && c.screenHeight in 64..8192,
        "${c.screenWidth}x${c.screenHeight}")
    ck("keymap built from server", c.keymapSize() > 50, "${c.keymapSize()} keysyms")
    ck("no errors during handshake", c.takeErrors().isEmpty(), "")

    println("ATOMS / PROPERTIES (compare with: xprop -root _NET_SUPPORTING_WM_CHECK)")
    val wmName = c.internAtom("WM_NAME")
    ck("internAtom(WM_NAME) == 39 (predefined)", wmName == 39, "got $wmName")
    val check = c.internAtom("_NET_SUPPORTING_WM_CHECK")
    ck("internAtom(_NET_SUPPORTING_WM_CHECK) != 0", check != 0, "atom=$check")
    val again = c.internAtom("_NET_SUPPORTING_WM_CHECK")
    ck("internAtom is idempotent", again == check, "second call -> $again")
    ck("no errors on atom requests", c.takeErrors().isEmpty(), "")

    if (check != 0) {
        val p = c.getProperty(c.rootWindow, check)
        ck("getProperty(root, WM_CHECK) returns 1 WINDOW",
            p.size == 1 && p[0] != 0, "${p.size} item(s): 0x${p.firstOrNull()?.let { Integer.toHexString(it) } ?: "0"}")
    }
    ck("no errors on GetProperty", c.takeErrors().isEmpty(), "")
    ck("isWindowManagerRunning()", c.isWindowManagerRunning(), "(false is OK if no WM is running)")

    println("KEYSYM -> KEYCODE (from the server's own keymap)")
    for ((ks, name) in listOf(0x61 to "a", 0x41 to "A", 0xFF0D to "Return",
                              0xFF08 to "BackSpace", 0xFF53 to "Right")) {
        val kc = c.keysymKeycode(ks)
        ck("keysym 0x${Integer.toHexString(ks)} ($name)", kc > 0, "keycode=$kc")
    }
    println(if (fail == 0) "RESULT  PASS (0 failures)" else "RESULT  FAIL ($fail failures)")
    c.close()
    if (fail != 0) kotlin.system.exitProcess(1)
}
