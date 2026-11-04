package com.emux.x11.jvmwire

import com.emux.x11.X11Client
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.exitProcess

/**
 * JvmWireProof — runs the APK's OWN com.emux.x11.X11Client against a REAL X
 * server on a desktop JVM, and fails loudly when the bytes it puts on the wire
 * are not the bytes the protocol asks for.
 *
 * ---------------------------------------------------------------------------
 * WHY THIS EXISTS
 *
 * Every hand-rolled request in this project has eventually been wrong in a way
 * only a real server notices, and the project's answer has always been a probe
 * (xgetimage_wiretest.py, damage_probe.py, drag_probe.py, button_probe.py).
 * Those probes are written in Python and *re-implement* the request. That is
 * exactly how the worst bug in the desktop's history shipped: the Python
 * wiretest packed a correct 20-byte xGetImageReq, the Kotlin it was supposed
 * to mirror packed a 24-byte one, the wiretest passed, and
 * `grabRegionArgb()` answered BadLength(16) on every single call from v0.2.71
 * to v0.2.82. Native capture never produced one frame on any device; the app
 * silently fell back to the guest xwd pipelines, and six releases of "the pipe
 * is 720 KB/s, so 0.5 fps is honest physics" were written about a transport
 * that had never once succeeded.
 *
 * A probe that re-implements the request tests the probe. This one links the
 * real class, so the two cannot drift.
 *
 * WHAT IT PROVES  (each line is a pass/fail verdict; exit status is the count)
 *   1  handshake, geometry and depth against the server's own setup reply
 *   2  a full-screen GetImage returns the exact pixels that were put there
 *      (request length, offsets, stride and the RGB565 decode all at once)
 *   3  a REGION GetImage at a non-zero offset returns that region, not the
 *      screen — the damage path's whole premise
 *   4  DAMAGE subscribes and reports the rectangle a client drew
 *   5  events that arrive WHILE a reply is being read are QUEUED, not
 *      dropped — the bug that made a drag repaint only at the ~5 s healer
 *   6  XTEST motion actually moves the server's pointer (round trip)
 *   7  the button-delivery probe completes against a bare server
 *   8  what the fixed pipeline costs, measured, on this machine
 *
 * Usage:  DISPLAY=:5 sh run_jvm_wire_proof.sh
 */

private var pass = 0
private var fail = 0

/**
 * Read a member that only exists on the current client. The proof is meant to
 * be runnable against ANY revision of X11Client.kt — `EMUX_X11CLIENT_SRC=$(git
 * show HEAD~1:...X11Client.kt) sh run_jvm_wire_proof.sh` must compile and then
 * FAIL, which is the only honest way to show a regression test catches the
 * regression. Members this revision does not have read as null / absent rather
 * than breaking the build.
 */
private fun callBool(o: Any, name: String): Boolean? = runCatching {
    o.javaClass.getMethod(name).invoke(o) as Boolean
}.getOrNull()

private fun callLong(o: Any, name: String): Long? = runCatching {
    (o.javaClass.getMethod(name).invoke(o) as Number).toLong()
}.getOrNull()

private val TRACE = System.getenv("EMUX_WIRE_TRACE") != null
private fun trace(m: String) { if (TRACE) { System.err.println("   [trace] $m"); System.err.flush() } }

private fun ck(ok: Boolean, name: String, detail: String = "") {
    if (ok) { pass++; println("  ok    $name${if (detail.isEmpty()) "" else "   [$detail]"}") }
    else { fail++; println("  FAIL  $name${if (detail.isEmpty()) "" else "   [$detail]"}") }
}

/** 8-bit components -> the server's own RGB565 word. */
private fun to565(r: Int, g: Int, b: Int): Int =
    ((r shr 3) shl 11) or ((g shr 2) shl 5) or (b shr 3)

/** RGB565 word -> the packed ARGB the app's decoder must produce. */
private fun from565(v: Int): Int =
    (0xFF shl 24) or
        (((v ushr 11) and 0x1F) * 255 / 31 shl 16) or
        (((v ushr 5) and 0x3F) * 255 / 63 shl 8) or
        ((v and 0x1F) * 255 / 31)

// =====================================================================
// A minimal raw X client whose only job is to PUT known pixels on the
// root window, so the app's GetImage has a ground truth to be judged
// against. Nothing here is shared with X11Client on purpose: an
// independent implementation is what makes the comparison a test.
// =====================================================================
private class Painter(host: String, port: Int) {
    val s = Socket().apply {
        connect(InetSocketAddress(host, port), 10_000)
        tcpNoDelay = true
        receiveBufferSize = 1 shl 20
        sendBufferSize = 1 shl 20
    }
    private val ins = DataInputStream(s.inputStream.buffered(1 shl 18))
    private var seq = 0
    private val order = ByteOrder.LITTLE_ENDIAN
    var root = 0; private set
    var rootVisual = 0; private set
    var width = 0; private set
    var height = 0; private set
    var depth = 0; private set
    var maxReqUnits = 4096; private set
    /** Every X error packet seen on this connection, human-named. */
    val errs = ArrayList<String>()
    private var resBase = 0
    private var resMask = 0
    private var resCount = 0
    private var gc = 0

    init {
        trace("painter: connecting")
        val bb = ByteBuffer.allocate(12).order(order)
        bb.put('l'.code.toByte()); bb.put(0)
        bb.putShort(11); bb.putShort(0); bb.putShort(0); bb.putShort(0); bb.putShort(0)
        s.getOutputStream().write(bb.array()); s.getOutputStream().flush()
        trace("painter: reading setup prefix")
        val head = ByteArray(8); ins.readFully(head)
        require(head[0].toInt() == 1) { "server refused the connection" }
        val units = ByteBuffer.wrap(head).order(order).getShort(6).toInt() and 0xFFFF
        s.soTimeout = 10_000
        trace("painter: setup additional data = $units units")
        val rest = ByteArray(units * 4); ins.readFully(rest)
        val g = ByteBuffer.wrap(rest).order(order)
        resBase = g.getInt(4)
        resMask = g.getInt(8)
        val vlen = g.getShort(16).toInt() and 0xFFFF
        maxReqUnits = g.getShort(18).toInt() and 0xFFFF
        val nFormats = rest[21].toInt() and 0xFF
        val foff = ((32 + vlen + 3) / 4) * 4
        val soff = foff + nFormats * 8
        root = g.getInt(soff)
        rootVisual = g.getInt(soff + 32)
        width = g.getShort(soff + 20).toInt() and 0xFFFF
        height = g.getShort(soff + 22).toInt() and 0xFFFF
        depth = rest[soff + 38].toInt() and 0xFF
        // authoritative depth via GetGeometry
        trace("painter: GetGeometry")
        val r = req(14, 0, ByteBuffer.allocate(4).order(order).putInt(root).array())
        if (r != null) {
            depth = r[1].toInt() and 0xFF
            val gw = ByteBuffer.wrap(r).order(order).getShort(16).toInt() and 0xFFFF
            val gh = ByteBuffer.wrap(r).order(order).getShort(18).toInt() and 0xFFFF
            if (gw > 0) width = gw
            if (gh > 0) height = gh
        }
        trace("painter: CreateGC")
        println("  painter setup: resBase=0x${Integer.toHexString(resBase)} resMask=0x${Integer.toHexString(resMask)} maxReq=$maxReqUnits")
        gc = newId()
        // CreateGC(gc, root, value-mask = 0): 4+4+4 = 12-byte body, 4 units.
        val cgb = ByteBuffer.allocate(12).order(order)
        cgb.putInt(gc); cgb.putInt(root); cgb.putInt(0)
        sendNoReply(55, 0, cgb.array())
        sync()
    }

    private fun newId(): Int {
        resCount++
        return (resBase + resCount) or (resBase and resMask.inv())
    }

    /**
     * Send a request that has NO reply. In X11 most mutating requests are
     * reply-less — CreateGC, PutImage, MapWindow, ConfigureWindow and the
     * whole XTEST FakeInput family among them — and waiting for a reply that
     * the protocol never defines is a hang, not an error. (This harness hung
     * for exactly that reason on its first run; the app's own X11Client gets
     * it right via sendNoReply.) Callers that need to know the server has
     * finished use [sync].
     */
    private fun sendNoReply(op: Int, minor: Int, body: ByteArray) {
        val total = 4 + body.size
        val units = (total + 3) / 4
        val out = ByteArray(units * 4)
        val bb = ByteBuffer.wrap(out).order(order)
        bb.put(op.toByte()); bb.put(minor.toByte()); bb.putShort(units.toShort()); bb.put(body)
        seq = (seq + 1) and 0xFFFF
        s.getOutputStream().write(out); s.getOutputStream().flush()
        trace("noreply op=$op minor=$minor seq=$seq units=$units body=${body.size}")
    }

    private fun req(op: Int, minor: Int, body: ByteArray): ByteArray? {
        s.soTimeout = 10_000
        val mine = ++seq and 0xFFFF
        val total = 4 + body.size
        val out = ByteArray(((total + 3) / 4) * 4)
        val bb = ByteBuffer.wrap(out).order(order)
        bb.put(op.toByte()); bb.put(minor.toByte()); bb.putShort(((total + 3) / 4).toShort())
        bb.put(body)
        if (body.size % 4 != 0) repeat(4 - body.size % 4) { bb.put(0) }
        s.getOutputStream().write(out); s.getOutputStream().flush()
        trace("req op=$op minor=$minor seq=$mine units=${out.size / 4} body=${body.size}")
        while (true) {
            val h = ByteArray(32); ins.readFully(h)
            trace("  pkt kind=${h[0].toInt() and 0xFF} seq=${ByteBuffer.wrap(h).order(order).getShort(2).toInt() and 0xFFFF} len=${ByteBuffer.wrap(h).order(order).getInt(4)} b1=${h[1].toInt() and 0xFF}")
            when (h[0].toInt()) {
                1 -> {
                    val u = ByteBuffer.wrap(h).order(order).getInt(4)
                    val sq = ByteBuffer.wrap(h).order(order).getShort(2).toInt() and 0xFFFF
                    val tail = if (u > 0) { val t = ByteArray(u * 4); ins.readFully(t); t } else ByteArray(0)
                    if (sq == mine) return h + tail
                }
                0 -> {
                    val sq = ByteBuffer.wrap(h).order(order).getShort(2).toInt() and 0xFFFF
                    noteError(h, sq)
                    if (sq == mine) throw RuntimeException(errs.last())
                }
                35 -> {
                    val u = ByteBuffer.wrap(h).order(order).getInt(4)
                    if (u > 0) ins.readFully(ByteArray(u * 4))
                }
            }
        }
    }

    /**
     * Paint [x,y,w,h] of the root window a solid colour.
     *
     * Deliberately CreateGC(GCForeground) + PolyFillRectangle rather than
     * PutImage: PutImage's accepted data length depends on the drawable's
     * bitsPerPixel through PixmapBytePerLine(), which this Xvfb rejects with
     * BadLength(16) for the naive `width*height*bpp` that both this harness
     * and xgetimage_wiretest.py would send. PolyFillRectangle has no such
     * coupling, is two requests for any size, and gives the same ground truth:
     * exact known pixels at exact known offsets.
     */
    fun fill(x: Int, y: Int, w: Int, h: Int, rgb565: Int) {
        require(depth == 16) { "this proof paints RGB565; server is depth $depth" }
        val g = newId()
        // GCForeground = 1<<2; one CARD32 value follows the mask.
        val gcb = ByteBuffer.allocate(16).order(order)
        gcb.putInt(g); gcb.putInt(root); gcb.putInt(1 shl 2); gcb.putInt(rgb565)
        sendNoReply(55, 0, gcb.array())
        val r = ByteBuffer.allocate(16).order(order)
        r.putInt(root); r.putInt(g)
        r.putShort(x.toShort()); r.putShort(y.toShort())
        r.putShort(w.toShort()); r.putShort(h.toShort())
        sendNoReply(70, 0, r.array())
        // FreeGC. MEASURED QUIRK, worth writing down because it cost a round:
        // xResourceReq is 8 bytes and that is what MapWindow / UnmapWindow /
        // DestroyWindow accept (the app's send4() relies on it, and check 7
        // below exercises MapWindow). FreeGC on this Xvfb answers BadLength(16)
        // to 8 bytes and only accepts 12 - matching the protocol reference
        // table, which lists FreeGC's request size as 3 units. So pad it.
        val fg = ByteBuffer.allocate(8).order(order)
        fg.putInt(g); fg.putInt(0)
        sendNoReply(56, 0, fg.array())
    }

    /**
     * v0.2.85: a mapped window that LISTENS for button/motion events, so an
     * injected burst can be proven by what actually arrives - the same trick
     * X11Client.probeButtonDelivery plays, but on the harness's own connection
     * (events cross connections fine: delivery is by pointer position). No WM
     * runs on this display, so the window sits exactly where it is created.
     * CreateWindow encoding mirrors X11Client's probe window (InputOutput,
     * root visual at this depth, CWBackPixel|CWEventMask = 1<<1 | 1<<11).
     */
    fun listenWindow(x: Int, y: Int, w: Int, h: Int, mask: Int): Int {
        val wid = newId()
        val body = ByteBuffer.allocate(36).order(order)
        body.putInt(wid)
        body.putInt(root)                      // parent
        body.putShort(x.toShort()); body.putShort(y.toShort())
        body.putShort(w.toShort()); body.putShort(h.toShort())
        body.putShort(0)                       // border-width
        body.putShort(if (rootVisual == 0) 0 else 1)   // CopyFromParent / InputOutput
        body.putInt(rootVisual)                // visual (0 = CopyFromParent)
        body.putInt((1 shl 1) or (1 shl 11))   // CWBackPixel | CWEventMask
        body.putInt(0x1020f6)                  // back pixel
        body.putInt(mask)
        sendNoReply(1, if (rootVisual == 0) 0 else depth, body.array())
        sendNoReply(8, 0, ByteBuffer.allocate(4).order(order).putInt(wid).array())  // MapWindow
        return wid
    }

    /**
     * Read packets for up to [ms] and count button/motion events:
     * returns [press4, release4, press7, release7, motion]. Quiet periods end
     * on the read timeout; the loop keeps going until the deadline so a burst
     * that the server delivers in several writes is still counted whole.
     */
    fun drainButtonEvents(ms: Int): IntArray {
        val n = IntArray(5)
        val deadline = System.currentTimeMillis() + ms
        s.soTimeout = 100
        try {
            while (System.currentTimeMillis() < deadline) {
                val h = ByteArray(32)
                try { ins.readFully(h) } catch (_: java.net.SocketTimeoutException) { continue }
                val t = h[0].toInt() and 0xFF
                val detail = h[1].toInt() and 0xFF
                when (t) {
                    4 -> if (detail == 4) n[0]++ else if (detail == 7) n[2]++
                    5 -> if (detail == 4) n[1]++ else if (detail == 7) n[3]++
                    6 -> n[4]++
                    0 -> noteError(h, ByteBuffer.wrap(h).order(order).getShort(2).toInt() and 0xFFFF)
                    1, 35 -> {
                        val u = ByteBuffer.wrap(h).order(order).getInt(4)
                        if (u > 0) ins.readFully(ByteArray(u * 4))
                    }
                }
            }
        } catch (_: Exception) {
        } finally { s.soTimeout = 10_000 }
        return n
    }

    private fun noteError(h: ByteArray, sq: Int) {
        val code = h[1].toInt() and 0xFF
        val major = h[10].toInt() and 0xFF
        val minor = ByteBuffer.wrap(h).order(order).getShort(8).toInt() and 0xFFFF
        val res = ByteBuffer.wrap(h).order(order).getInt(4)
        val cn = arrayOf("?", "BadRequest", "BadValue", "BadWindow", "BadPixmap", "BadAtom",
            "BadCursor", "BadFont", "BadMatch", "BadDrawable", "BadAccess", "BadAlloc",
            "BadColor", "BadGC", "BadIDChoice", "BadName", "BadLength", "BadImplementation")
            .getOrElse(code) { "code$code" }
        // Sparse on purpose: a dense array indexed by opcode is exactly the
        // shape that silently drifts, and an out-of-range index inside an
        // error-reporter is worse than no name at all (it threw here once and
        // a BadLength read as "no answer").
        val names = mapOf(
            1 to "CreateWindow", 2 to "ChangeWindowAttributes", 3 to "GetWindowAttributes",
            4 to "DestroyWindow", 8 to "MapWindow", 10 to "UnmapWindow",
            12 to "ConfigureWindow", 14 to "GetGeometry", 15 to "QueryTree",
            16 to "InternAtom", 18 to "ChangeProperty", 20 to "GetProperty",
            25 to "SendEvent", 38 to "QueryPointer", 42 to "SetInputFocus",
            43 to "GetInputFocus", 53 to "CreatePixmap", 54 to "FreePixmap",
            55 to "CreateGC", 56 to "FreeGC", 57 to "CopyGC", 62 to "CopyArea",
            63 to "CopyPlane", 64 to "PolyPoint", 65 to "PolyLine",
            66 to "PolySegment", 67 to "PolyRectangle", 68 to "PolyArc",
            69 to "FillPoly", 70 to "PolyFillRectangle", 71 to "PolyFillArc",
            72 to "PutImage", 73 to "GetImage", 98 to "QueryExtension")
        val mn = names[major] ?: "opcode$major"
        errs.add("$cn on $mn(minor=$minor) seq=$sq res=0x${Integer.toHexString(res)}")
    }

    /** Read whatever the server has already sent, without blocking long. */
    fun drainErrors(ms: Int = 400) {
        s.soTimeout = ms
        try {
            while (true) {
                val h = ByteArray(32)
                try { ins.readFully(h) } catch (_: Exception) { return }
                if (h[0].toInt() == 0) {
                    noteError(h, ByteBuffer.wrap(h).order(order).getShort(2).toInt() and 0xFFFF)
                }
                val u = if (h[0].toInt() == 1 || h[0].toInt() == 35)
                    ByteBuffer.wrap(h).order(order).getInt(4) else 0
                if (u > 0) ins.readFully(ByteArray(u * 4))
            }
        } finally { s.soTimeout = 10_000 }
    }

    /**
     * Send GetImage with [exactLength] = true for the protocol's own 20-byte
     * request, false for the 24-byte one v0.2.71..v0.2.82 shipped, and report
     * what the server said. Returns "REPLY" or the named X error.
     */
    fun getImageStatus(x: Int, y: Int, w: Int, h: Int, exactLength: Boolean): String {
        // 20 allocated, 16 written: the exact body is the first 16 bytes, the
        // legacy one is the same 16 plus the 4 bytes of zero padding that
        // ByteBuffer.allocate(20) silently contributed in the shipping code.
        val core = ByteBuffer.allocate(20).order(order)
        core.putInt(root); core.putShort(x.toShort()); core.putShort(y.toShort())
        core.putShort(w.toShort()); core.putShort(h.toShort()); core.putInt(-1)
        val body = if (exactLength) core.array().copyOf(16) else core.array()
        val mine = ++seq and 0xFFFF
        val units = (4 + body.size) / 4
        val out = ByteArray(units * 4)
        val bb = ByteBuffer.wrap(out).order(order)
        bb.put(73.toByte()); bb.put(2); bb.putShort(units.toShort()); bb.put(body)
        s.getOutputStream().write(out); s.getOutputStream().flush()
        trace("getImageStatus exact=$exactLength seq=$mine units=$units bytes=${out.size} -> ${out.take(8).joinToString(" "){ "%02x".format(it) }}")
        s.soTimeout = 8_000
        try {
            while (true) {
                val hd = ByteArray(32)
                try { ins.readFully(hd) } catch (e: Exception) {
                    trace("  getImageStatus: read ended with ${e.javaClass.simpleName}")
                    throw e
                }
                val k = hd[0].toInt()
                trace("  pkt kind=$k seq=${ByteBuffer.wrap(hd).order(order).getShort(2).toInt() and 0xFFFF} len=${ByteBuffer.wrap(hd).order(order).getInt(4)} b1=${hd[1].toInt() and 0xFF} major=${hd[10].toInt() and 0xFF}")
                val u = ByteBuffer.wrap(hd).order(order).getInt(4)
                val sq = ByteBuffer.wrap(hd).order(order).getShort(2).toInt() and 0xFFFF
                if (u > 0 && (k == 1 || k == 35)) ins.readFully(ByteArray(u * 4))
                if (sq != mine) { if (k == 0) noteError(hd, sq); continue }
                if (k == 1) return "REPLY"
                if (k == 0) {
                    noteError(hd, sq)
                    return errs.lastOrNull() ?: "error ${hd[1].toInt() and 0xFF}"
                }
            }
        } catch (_: Exception) {
            return "no answer (timeout/eof)"
        } finally { s.soTimeout = 10_000 }
    }

    /** XSync: GetInputFocus round trip. */
    fun sync() { req(43, 0, ByteArray(0)); drainErrors(80) }

    fun close() = runCatching { s.close() }
}

// =====================================================================

private fun unionOf(rects: List<IntArray>): IntArray? {
    if (rects.isEmpty()) return null
    var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var x1 = 0; var y1 = 0
    for (r in rects) {
        x0 = minOf(x0, r[0]); y0 = minOf(y0, r[1])
        x1 = maxOf(x1, r[0] + r[2]); y1 = maxOf(y1, r[1] + r[3])
    }
    return if (x1 > x0 && y1 > y0) intArrayOf(x0, y0, x1 - x0, y1 - y0) else null
}

fun main(args: Array<String>) {
    val disp = System.getenv("DISPLAY")?.takeIf { it.isNotBlank() } ?: ":5"
    val n = disp.removePrefix(":").substringBefore('.').toIntOrNull() ?: 5
    val host = System.getenv("XHOST") ?: "127.0.0.1"
    val port = 6000 + n
    println("== EmuX JVM wire proof: the APK's own X11Client.kt vs a real X server on $host:$port ==")

    trace("main: constructing painter")
    val p = Painter(host, port)
    trace("main: painter ready")
    println("  server: ${p.width}x${p.height} depth ${p.depth}, root 0x${Integer.toHexString(p.root)}, " +
        "max-request ${p.maxReqUnits} units")
    val W = p.width; val H = p.height
    if (p.depth != 16) {
        println("  SKIP: this proof paints and checks RGB565; the server is depth ${p.depth}.")
        println("        Start Xvfb with -screen 0 ${W}x${H}x16 (the app's own capture depth).")
        exitProcess(2)
    }

    trace("main: connecting the app's X11Client")
    val c = X11Client(host = host, display = n)
    c.connect(10_000)
    trace("main: X11Client connected")

    // ---- 0. the protocol fact the v0.2.71..v0.2.82 client got wrong ------
    run {
        val q = Painter(host, port)
        val exact = q.getImageStatus(0, 0, 64, 64, exactLength = true)
        val padded = q.getImageStatus(0, 0, 64, 64, exactLength = false)
        ck(exact == "REPLY", "a 20-byte / 5-unit xGetImageReq is accepted", exact)
        ck(padded.startsWith("BadLength"),
            "the SAME request with 4 pad bytes (24 B / 6 units) is BadLength",
            padded + " <- this is what grabRegionArgb() sent from v0.2.71 to v0.2.82")
        q.close()
    }

    // ---- 1. handshake -------------------------------------------------
    ck(c.connected, "handshake completes on the app's own connect()")
    ck(c.screenWidth == W && c.screenHeight == H,
        "geometry agrees with the server", "${c.screenWidth}x${c.screenHeight} vs ${W}x$H")
    ck(c.depth == 16, "depth parsed from GetGeometry", "depth=${c.depth}")
    ck(c.xtestOpcode > 127, "XTEST discovered", "opcode=${c.xtestOpcode}")
    ck(c.keymapSize() > 0, "keyboard mapping built", "${c.keymapSize()} keysyms")

    if (p.errs.isNotEmpty()) {
        println("  painter errors before the first check: ${p.errs.take(5).joinToString("; ")}")
    }
    // ---- 2. full-screen GetImage returns exactly what was painted -----
    val RED565 = to565(0xC0, 0x20, 0x20)
    val RED = from565(RED565)
    val tp = System.nanoTime()
    p.fill(0, 0, W, H, RED565)
    p.sync()
    val paintMs = (System.nanoTime() - tp) / 1_000_000.0
    val t1 = System.nanoTime()
    val px = c.grabRegionArgb(0, 0, W, H, 20_000, null)
    val fullMs = (System.nanoTime() - t1) / 1_000_000.0
    ck(px != null, "FULL-SCREEN GetImage returns a reply at all",
        if (px == null) "lastGrabError=${c.lastGrabError} lastXErr=${c.lastXErr}" else "${W * H} px")
    if (px != null) {
        var bad = 0; var badAt = -1
        for (i in px.indices) if (px[i] != RED) { if (bad == 0) badAt = i; bad++ }
        ck(bad == 0, "every pixel of the full grab equals the painted colour",
            if (bad == 0) "${px.size} px of 0x${Integer.toHexString(RED)}"
            else "$bad wrong; first at $badAt = 0x${Integer.toHexString(px[badAt])} want 0x${Integer.toHexString(RED)}")
        println("        full ${W}x${H} grab: %.2f ms (%.0f KB/s); server painted it in %.1f ms"
            .format(fullMs, (W.toLong() * H * 2 / 1024) / (fullMs / 1000.0), paintMs))
    }

    // ---- 3. region GetImage at a non-zero offset ----------------------
    val BLUE565 = to565(0x10, 0x20, 0xF0)
    val BLUE = from565(BLUE565)
    p.fill(64, 200, 120, 90, BLUE565)
    p.sync()
    val reg = c.grabRegionArgb(64, 200, 120, 90, 20_000, null)
    ck(reg != null && reg.size == 120 * 90, "REGION GetImage returns a reply of exactly w*h",
        if (reg == null) "err=${c.lastGrabError}" else "${reg.size} px")
    if (reg != null) {
        var bad = 0
        for (i in reg.indices) if (reg[i] != BLUE) bad++
        ck(bad == 0, "the region holds ONLY the painted rectangle - x/y offset and stride are right",
            if (bad == 0) "all ${reg.size} px = 0x${Integer.toHexString(BLUE)}" else "$bad wrong")
    }
    val around = c.grabRegionArgb(60, 196, 128, 98, 20_000, null)
    ck(around != null && around[0] == RED && around[4 * 128 + 4] == BLUE,
        "a region that straddles the boundary returns both sides",
        if (around == null) "err=${c.lastGrabError}"
        else "outer=0x${Integer.toHexString(around[0])} inner=0x${Integer.toHexString(around[4 * 128 + 4])}")

    // ---- 4. DAMAGE reports what a client drew -------------------------
    val dmg = runCatching { c.initDamageTracking() }.getOrDefault(false)
    ck(dmg, "DAMAGE Create on the root is accepted", c.lastXErr)
    if (dmg) {
        c.drainDamageRects(200)                     // discard our own painting
        val GREEN = from565(to565(0x00, 0xFF, 0x00))
        p.fill(300, 500, 80, 60, to565(0x00, 0xFF, 0x00))
        p.sync()
        val rects = c.drainDamageRects(3_000)
        val u = unionOf(rects)
        ck(rects.isNotEmpty(), "a drawing client produces DamageNotify events", "${rects.size} rect(s)")
        ck(u != null && u[0] <= 300 && u[1] <= 500 && u[0] + u[2] >= 380 && u[1] + u[3] >= 560,
            "the notify geometry covers the drawn rectangle",
            if (u == null) "none" else "union ${u[0]},${u[1]} ${u[2]}x${u[3]}")
        val g = c.grabRegionArgb(300, 500, 80, 60, 20_000, null)
        ck(g != null && g[0] == GREEN, "grabbing just the damaged rectangle returns the new pixels",
            if (g == null) "err=${c.lastGrabError}" else "0x${Integer.toHexString(g[0])}")

        // ---- 4b. v0.2.84: raw-565 grab + public LUT (the band-diff painter) --
        // Reflection on purpose: the harness must still COMPILE against
        // pre-v0.2.84 clients, where both checks then fail as intended.
        run {
            val G565 = to565(0x00, 0xFF, 0x00)
            val m = runCatching {
                c.javaClass.getMethod("grabRegion565",
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType, ByteArray::class.java)
            }.getOrNull()
            if (m == null) {
                ck(false, "grabRegion565 (v0.2.84 raw-byte grab) exists",
                    "no such method on this client")
            } else {
                val buf = ByteArray(80 * 60 * 2)
                val n = runCatching {
                    m.invoke(c, 300, 500, 80, 60, 20_000, buf) as Int
                }.getOrDefault(-1)
                var all = n == buf.size
                var i = 0
                while (all && i < buf.size) {
                    val v = (buf[i].toInt() and 0xFF) or ((buf[i + 1].toInt() and 0xFF) shl 8)
                    if (v != G565) all = false
                    i += 2
                }
                ck(all, "grabRegion565 returns the painted rect as raw RGB565 LE, stride-free",
                    if (all) "80x60, every 16-bit unit = 0x${Integer.toHexString(G565)}"
                    else "n=$n err=${c.lastGrabError}")
            }
            val lut = runCatching {
                c.javaClass.getField("RGB565_TO_ARGB").get(null) as IntArray
            }.getOrNull()
            if (lut == null) {
                ck(false, "RGB565_TO_ARGB LUT is public (v0.2.84)",
                    "no such field on this client")
            } else {
                // full sweep: all 65536 entries against the component math
                // grabRegionArgb uses (r*255/31, g*255/63, b*255/31).
                var bad = -1
                for (v in 0 until 65536) {
                    val exp = -0x1000000 or
                        (((v ushr 11) and 0x1F) * 255 / 31 shl 16) or
                        (((v ushr 5) and 0x3F) * 255 / 63 shl 8) or
                        ((v and 0x1F) * 255 / 31)
                    if (lut[v] != exp) { bad = v; break }
                }
                ck(bad < 0 && (g == null || lut[G565] == g[0]),
                    "the LUT is exact for all 65536 codes and agrees with grabRegionArgb",
                    if (bad >= 0) "lut[0x${Integer.toHexString(bad)}]=0x${Integer.toHexString(lut[bad])}"
                    else "lut[0x${Integer.toHexString(G565)}]=0x${Integer.toHexString(lut[G565])}")
            }
        }
    }

    // ---- 5. events during a reply are queued, not dropped -------------
    if (dmg) {
        c.drainDamageRects(300)                     // start clean
        ck(callBool(c, "hasQueuedEvent") != true, "the event queue starts empty")
        // Draw, then SYNC ON THE SERVER, so the DamageNotify is certainly
        // written to our socket before the reply to our next request is.
        // Any request we then issue must read the notify first — the old
        // requestReply() discarded it at exactly this point, and the
        // rectangle it described was never fetched: the pane stayed stale
        // until the ~5 s healer sweep redrew the whole screen.
        p.fill(10, 10, 40, 40, to565(0xFF, 0xFF, 0xFF))
        p.sync()
        Thread.sleep(250)                           // let the server flush it
        val before = callLong(c, "getEventsQueued")
        ck(c.sync(5_000), "a request issued while a notify is pending completes")
        val parked = callBool(c, "hasQueuedEvent") == true ||
            (before != null && (callLong(c, "getEventsQueued") ?: 0L) > before)
        ck(parked,
            "the notify that arrived during that request was PARKED, not thrown away",
            "eventsQueued=${callLong(c, "getEventsQueued")} dropped=${callLong(c, "getEventsDropped")}")
        val late = c.drainDamageRects(500)
        ck(late.isNotEmpty(), "and drainDamageRects() then serves it from the queue",
            "${late.size} rect(s) ${unionOf(late)?.let { "${it[0]},${it[1]} ${it[2]}x${it[3]}" } ?: ""}")
        ck((callLong(c, "getEventsDropped") ?: 0L) == 0L,
            "nothing had to be dropped to stay inside the queue bound")
    }

    // ---- 6. XTEST motion really moves the server's pointer ------------
    c.fakeMotion(123, 456)
    c.sync(3_000)
    Thread.sleep(120)
    val after = c.queryPointerRoot()
    ck(after != null && after.first == 123 && after.second == 456,
        "fakeMotion moves the server's pointer (QueryPointer round trip)",
        "${after?.first},${after?.second}")

    // ---- 7. the button probe completes --------------------------------
    val probe = runCatching { c.probeButtonDelivery(1, 400, 900) }.getOrElse {
        println("        probe threw: $it"); X11Client.ButtonProbe(false, 0, 0, 0, "threw $it")
    }
    ck(probe.presses >= 1, "probeButtonDelivery sees its own injected ButtonPress",
        probe.detail.take(110))

    // ---- 7b. v0.2.85: the batched wheel burst (mouse/trackpad scroll) ---
    // One fakeScrollBurst call must deliver EVERY notch of BOTH axes to a
    // real listening window - presses AND releases - with the burst's own
    // leading motion aiming the pointer into it. Reflection on purpose: the
    // harness must still compile against pre-v0.2.85 clients.
    run {
        val m = runCatching {
            c.javaClass.getMethod("fakeScrollBurst",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        }.getOrNull()
        if (m == null) {
            ck(false, "fakeScrollBurst (v0.2.85 batched wheel) exists",
                "no such method on this client")
        } else {
            val wx = 300; val wy = 700; val ww = 140; val wh = 100
            // ButtonPress | ButtonRelease | PointerMotion | Exposure | StructureNotify
            p.listenWindow(wx, wy, ww, wh,
                (1 shl 2) or (1 shl 3) or (1 shl 6) or (1 shl 15) or (1 shl 17))
            p.sync()
            Thread.sleep(300)
            p.drainButtonEvents(150)              // discard the map/expose flood
            m.invoke(c, wx + ww / 2, wy + wh / 2, 4, 2, 7, 1)
            val n = p.drainButtonEvents(1_500)
            ck(n[0] == 2 && n[1] == 2 && n[2] == 1 && n[3] == 1,
                "one fakeScrollBurst delivers every notch of both axes (2x button4 + 1x button7, press AND release)",
                "press4=${n[0]} rel4=${n[1]} press7=${n[2]} rel7=${n[3]} motion=${n[4]}")
            val q = c.queryPointerRoot()
            ck(q != null && q.first in wx..(wx + ww) && q.second in wy..(wy + wh),
                "the burst's leading motion aims the pointer into the scrolled window",
                "${q?.first},${q?.second}")
        }
    }

    // ---- 8. what the fixed pipeline costs -----------------------------
    println("  -- measured cost of one update (this machine, no proot) --")
    for ((name, w, h) in listOf(
        Triple("full screen ${W}x$H", W, H),
        Triple("typical window 480x360", minOf(480, W), minOf(360, H)),
        Triple("drag sliver ${W}x40", W, minOf(40, H)),
        Triple("small damage 120x90", minOf(120, W), minOf(90, H)),
    )) {
        if (h > H || w > W) continue
        val buf = IntArray(w * h)
        val runs = ArrayList<Double>()
        repeat(7) {
            val a = System.nanoTime()
            val r = c.grabRegionArgb(0, 0, w, h, 20_000, buf)
            if (r != null) runs.add((System.nanoTime() - a) / 1_000_000.0)
        }
        runs.sort()
        val med = if (runs.isEmpty()) -1.0 else runs[runs.size / 2]
        val min = if (runs.isEmpty()) -1.0 else runs[0]
        println("     %-22s %8d B  med %7.2f ms  min %7.2f ms  -> %6.1f fps"
            .format(name, w * h * 2, med, min, if (med > 0) 1000.0 / med else 0.0))
    }

    ck(p.errs.isEmpty(), "the independent painter saw no X errors",
        if (p.errs.isEmpty()) "CreateGC/PutImage/sync all clean" else p.errs.take(6).joinToString("; "))
    p.close(); c.close()
    println("== jvm wire proof: $pass passed, $fail failed ==")
    if (fail > 0) exitProcess(1)
}
