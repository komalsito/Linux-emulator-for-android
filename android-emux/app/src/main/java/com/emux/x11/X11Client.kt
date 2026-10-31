package com.emux.x11

import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/**
 * EmuX X11 — a self-contained X Window System Protocol Version 11 client in
 * Kotlin: no native code, no VNC, no third-party X libraries. It talks the raw
 * wire protocol to the X server running inside the bootstrapped distro.
 *
 * ---------------------------------------------------------------------------
 * v0.2.59 — THE INPUT FIX. This class had been dead on arrival since v0.1.x.
 * Every defect below was reproduced against a real Xvfb 1.21 + Openbox 3.6.1
 * (Debian 12) and each one is annotated with the bytes that prove it.
 *
 *  1. `parseSetupReply` read length-of-additional-data BIG-ENDIAN although the
 *     connection negotiated native (little-endian) order. For a 720x1280 screen
 *     the server says 2387 units (9548 bytes); the app computed 21257 units and
 *     called readFully(85028) with NO socket timeout, so connect() blocked
 *     forever. This is why 52 field rounds concluded "the host X11Client never
 *     completes a handshake on this ROM" — it was never the ROM.
 *
 *  2. `OP_QUERY_EXTENSION` was 15. Opcode 15 is QueryTree; QueryExtension is
 *     98 (0x62). Verified from Xlib: `xdpyinfo -ext XTEST` writes
 *     62 00 04 00 05 00 00 00 58 54 45 53 54 00 00 00. Sending the
 *     QueryExtension body to QueryTree returns BadLength(16), so XTEST was
 *     never discovered, `xtestOpcode` stayed -1, and every fakeKey/fakeButton
 *     threw "XTEST unavailable".
 *
 *  3. `queryExtension` read the reply at bytes 1/2/3. Authoritative
 *     xQueryExtensionReply: present@8, major-opcode@9, first-event@10,
 *     first-error@11. Byte 1 is unused and byte 2 is the sequence number.
 *
 *  4. `xtestFake` built a 24-byte body (request length 7 units). xXTestFakeInputReq
 *     is 36 bytes = 9 units; measured against a live server, both 28- and
 *     32-byte requests are rejected with BadLength(16). Worse, rootX/rootY live
 *     at request offsets 24/26 (body 20/22) — the code wrote them at body
 *     12/14, which is inside pad2[8].
 *
 *  5. `OP_SYNC` was 36. Opcode 36 is GrabServer, which freezes EVERY other
 *     client on the display — the window manager, the panel and the xwd frame
 *     capture included. Core X11 has no Sync request; Xlib's XSync()
 *     round-trips GetInputFocus (43).
 *
 *  6. `getProperty` built a 20-byte body and omitted the `window` field, so the
 *     server read the property atom as a window id and answered BadWindow(3).
 *     The reply's nItems is at offset 16; the code read offset 4, which is the
 *     reply length in 4-byte units.
 *
 *  7. `queryGeometry` read width via getInt(12) and height via getInt(16).
 *     GetGeometryReply has x@12 (INT16), y@14 (INT16), width@16 (CARD16),
 *     height@18 (CARD16). For a 720x1280 screen the old code produced
 *     screenWidth=0 and screenHeight=83886800.
 *
 *  8. The setup-reply walk skipped maximum-request-length (CARD16 at +18), so
 *     nFormats/nScreens were read from that field's two bytes (0xFF 0xFF on
 *     every server tested). The cursor then advanced 255*8 past the end of the
 *     buffer and rootWindow decoded as 64421888 instead of 0x50d.
 *
 *  9. `readReply` assumed the next 32-byte packet was the reply it asked for.
 *     Errors and events share the same header, and bytes 4-7 mean something
 *     different in each, so a single error packet desynchronised the stream
 *     permanently. Reads are now dispatched on byte 0.
 * ---------------------------------------------------------------------------
 *
 * Framebuffer capture deliberately avoids GetImage: the in-distro capture
 * helper streams raw RGB frames to [DesktopService] over a local socket.
 */
class X11Client(
    private val host: String = "127.0.0.1",
    private val display: Int = 0,
    // Absolute path of the rootfs unix socket (<rootfs>/tmp/.X11-unix/XN).
    // The field ROM's Xvfb binds unix but not always tcp, so the control plane
    // speaks unix first; tcp stays as the fallback.
    private val unixSocketPath: String? = null,
) {
    companion object {
        const val PORT_BASE = 6000

        // Authoritative core opcodes (X Protocol Reference, X11R7 / xproto).
        const val OP_CREATE_WINDOW = 1
        const val OP_CHANGE_WINDOW_ATTRS = 2
        const val OP_GET_WINDOW_ATTRS = 3
        const val OP_DESTROY_WINDOW = 4
        const val OP_MAP_WINDOW = 8
        const val OP_UNMAP_WINDOW = 10
        const val OP_CONFIGURE_WINDOW = 12
        const val OP_GET_GEOMETRY = 14
        const val OP_QUERY_TREE = 15              // <-- was mislabelled QueryExtension
        const val OP_INTERN_ATOM = 16
        const val OP_GET_PROPERTY = 20
        const val OP_SEND_EVENT = 25
        const val OP_QUERY_POINTER = 38            // v0.2.65: the input self-test
        const val OP_GET_INPUT_FOCUS = 43          // the real XSync round-trip
        const val OP_QUERY_EXTENSION = 98
        const val OP_LIST_EXTENSIONS = 99
        const val OP_GET_KEYBOARD_MAPPING = 101

        const val EV_EXPOSE = 12
        const val EV_GEOMETRY_NOTIFY = 22
        const val EV_MASK_BIT = 0x80

        // XTEST minor opcodes (xtestproto.h).
        const val XTEST_GET_VERSION = 0
        const val XTEST_FAKE_INPUT = 2

        // xXTestFakeInputReq body kinds.
        const val FAKE_KEY_PRESS = 2
        const val FAKE_KEY_RELEASE = 3
        const val FAKE_BUTTON_PRESS = 4
        const val FAKE_BUTTON_RELEASE = 5
        const val FAKE_MOTION = 6

        // xXTestFakeInputReq is 36 bytes total => a 32-byte body.
        private const val FAKE_INPUT_BODY = 32
        // Body offsets (request offset - 4).
        private const val FI_TYPE = 0
        private const val FI_DETAIL = 1
        private const val FI_TIME = 4
        private const val FI_ROOT = 8
        private const val FI_ROOT_X = 20
        private const val FI_ROOT_Y = 22
        private const val FI_DEVICEID = 24

        // Reply/error/event packet kinds.
        private const val PKT_ERROR = 0
        private const val PKT_REPLY = 1
        private const val PKT_GENERIC_EVENT = 35

        private const val HANDSHAKE_TIMEOUT_MS = 8_000
    }

    class XProtocolException(msg: String) : IOException(msg)

    /** An X error packet, kept for diagnostics instead of desyncing the stream. */
    class XErrorEvent(
        val code: Int, val majorOpcode: Int, val minorOpcode: Int,
        val badValue: Int, val sequence: Int,
    ) {
        fun describe(): String {
            // Names from Xproto.h. A field log that says "BadLength on
            // InternAtom" is actionable; "code=16 major=16" is not - and this
            // is exactly the diagnostic that exposed those two bugs.
            val cn = when (code) {
                1 -> "BadRequest"; 2 -> "BadValue"; 3 -> "BadWindow"; 4 -> "BadPixmap"
                5 -> "BadAtom"; 6 -> "BadCursor"; 7 -> "BadFont"; 8 -> "BadMatch"
                9 -> "BadDrawable"; 10 -> "BadAccess"; 11 -> "BadAlloc"; 12 -> "BadColor"
                13 -> "BadGC"; 14 -> "BadIDChoice"; 15 -> "BadName"; 16 -> "BadLength"
                17 -> "BadImplementation"; else -> "code$code"
            }
            val mn = when (majorOpcode) {
                14 -> "GetGeometry"; 15 -> "QueryTree"; 16 -> "InternAtom"
                17 -> "GetAtomName"; 18 -> "ChangeProperty"; 20 -> "GetProperty"
                43 -> "GetInputFocus"; 98 -> "QueryExtension"; 101 -> "GetKeyboardMapping"
                else -> "opcode$majorOpcode"
            }
            return "X error $cn on $mn (minor=$minorOpcode) " +
                "resource=0x${Integer.toHexString(badValue)} seq=$sequence"
        }
    }

    var rootWindow = 0; private set
    var screenWidth = 0; private set
    var screenHeight = 0; private set
    var depth = 0; private set
    var vendor = ""; private set
    var xtestOpcode = -1; private set
    var xtestFirstEvent = -1; private set

    @Volatile var connected = false; private set
    @Volatile var transport: String = "none"; private set

    /** Errors observed since the last [takeErrors] call — surfaced on the pane. */
    private val errors = java.util.ArrayDeque<XErrorEvent>()
    @Synchronized fun takeErrors(): List<XErrorEvent> {
        if (errors.isEmpty()) return emptyList()
        val out = errors.toList(); errors.clear(); return out
    }

    // Keyboard injection: keysym -> (keycode, shift level), built from the
    // server's own GetKeyboardMapping so layout differences never matter.
    private var minKeyCode = 8
    private var maxKeyCode = 255
    private val keysymKey = HashMap<Int, IntArray>()
    private var shiftKeycode = 50

    private var sock: Socket? = null
    private var lsock: android.net.LocalSocket? = null
    private var inp: DataInputStream? = null
    private var outp: BufferedOutputStream? = null
    private var order: ByteOrder = ByteOrder.LITTLE_ENDIAN
    private val writeLock = Any()
    private val readLock = Any()
    private var sequence = 0
    // Pending write buffer: FakeInput requests have no reply, so a click or a
    // word of text can be flushed as one write instead of one syscall each.
    private var pending: java.io.ByteArrayOutputStream? = null

    private fun pad(n: Int) = ceil(n / 4.0).toInt() * 4

    // ============================================================ connect/setup
    fun connect(timeoutMs: Int = HANDSHAKE_TIMEOUT_MS) {
        var viaUnix = false
        val usp = unixSocketPath
        if (usp != null && java.io.File(usp).exists()) {
            try {
                val ls = android.net.LocalSocket()
                ls.connect(android.net.LocalSocketAddress(
                    usp, android.net.LocalSocketAddress.Namespace.FILESYSTEM))
                ls.receiveBufferSize = 256 * 1024
                ls.soTimeout = timeoutMs
                lsock = ls
                inp = DataInputStream(ls.inputStream.buffered(128 * 1024))
                outp = BufferedOutputStream(ls.outputStream, 64 * 1024)
                viaUnix = true
            } catch (_: Exception) {
                runCatching { lsock?.close() }
                lsock = null
            }
        }
        if (!viaUnix) {
            val s = Socket()
            s.connect(InetSocketAddress(host, PORT_BASE + display), timeoutMs)
            s.tcpNoDelay = true
            s.receiveBufferSize = 256 * 1024
            s.sendBufferSize = 128 * 1024
            // v0.2.59: a timeout is mandatory. Without it the setup read blocks
            // forever and connectControlPlane()'s deadline never fires.
            s.soTimeout = timeoutMs
            sock = s
            inp = DataInputStream(s.inputStream.buffered(128 * 1024))
            outp = BufferedOutputStream(s.outputStream, 64 * 1024)
        }
        order = ByteOrder.nativeOrder()

        // Connection setup request — 12 bytes:
        //   byte-order(1) unused(1) protocol-major(2) protocol-minor(2)
        //   auth-name-len(2) auth-data-len(2) unused(2)
        val bb = ByteBuffer.allocate(12).order(order)
        bb.put(if (order == ByteOrder.LITTLE_ENDIAN) 'l'.code.toByte() else 'B'.code.toByte())
        bb.put(0)
        bb.putShort(11); bb.putShort(0); bb.putShort(0); bb.putShort(0)
        bb.putShort(0)
        rawWrite(bb.array())

        transport = if (viaUnix) "unix" else "tcp"
        parseSetupReply()
        // Discover XTEST (our input injection path) and build the keymap.
        runCatching { discoverXtest() }
            .onFailure { throw XProtocolException("XTEST discovery failed: $it") }
        if (xtestOpcode < 0) throw XProtocolException("server has no XTEST extension")
        buildKeymap()
        connected = true
    }

    /**
     * Setup reply (spec 8.1). The 8-byte prefix is:
     *   success(1) unused(1) protocol-major(2) protocol-minor(2)
     *   length-of-additional-data(2, in 4-byte units)
     * then the additional data:
     *   +0  release-number        CARD32
     *   +4  resource-id-base      CARD32
     *   +8  resource-id-mask      CARD32
     *   +12 motion-buffer-size    CARD32
     *   +16 length-of-vendor      CARD16
     *   +18 maximum-request-length CARD16   <-- the old walk skipped this
     *   +20 number-of-screens     BYTE
     *   +21 number-of-formats     BYTE
     *   +22 image-byte-order      BYTE
     *   +23 bitmap-bit-order      BYTE
     *   +24 scanline-unit         BYTE
     *   +25 scanline-pad          BYTE
     *   +26 min-keycode           KEYCODE
     *   +27 max-keycode           KEYCODE
     *   +28 unused                4 BYTE
     *   +32 vendor                STRING8, padded to 4
     *       pixmap-formats        8 bytes each
     *       screens
     */
    private fun parseSetupReply() {
        val ins = inp!!
        val head = ByteArray(8)
        ins.readFully(head)
        val h = ByteBuffer.wrap(head).order(order)
        val success = h.get().toInt()
        if (success != 1) {
            // Failed/authorize: reason-length-in-4-byte-units at bytes 6-7.
            val reasonUnits = h.getShort(6).toInt() and 0xFFFF
            val reason = ByteArray(reasonUnits * 4)
            if (reason.isNotEmpty()) ins.readFully(reason)
            throw XProtocolException("X server refused: ${String(reason).trim('\u0000')}")
        }
        // v0.2.59: read in the NEGOTIATED byte order, never big-endian.
        val extraUnits = h.getShort(6).toInt() and 0xFFFF
        if (extraUnits <= 0 || extraUnits > 65535) {
            throw XProtocolException("implausible setup length $extraUnits units")
        }
        val rest = ByteArray(extraUnits * 4)
        ins.readFully(rest)
        val g = { off: Int -> rest[off].toInt() and 0xFF }
        val u16 = { off: Int -> ((rest[off + 1].toInt() and 0xFF) shl 8) or (rest[off].toInt() and 0xFF) }
        val u32 = { off: Int ->
            ((rest[off + 3].toInt() and 0xFF) shl 24) or ((rest[off + 2].toInt() and 0xFF) shl 16) or
                ((rest[off + 1].toInt() and 0xFF) shl 8) or (rest[off].toInt() and 0xFF)
        }

        val vendorLen = u16(16)
        val nScreens = g(20)
        val nFormats = g(21)
        minKeyCode = g(26)
        maxKeyCode = g(27)
        if (minKeyCode <= 0 || maxKeyCode <= minKeyCode) { minKeyCode = 8; maxKeyCode = 255 }

        val vendorStart = 32
        vendor = runCatching {
            val n = vendorLen.coerceIn(0, maxOf(0, rest.size - vendorStart))
            String(rest, vendorStart, n)
        }.getOrDefault("unknown")

        val formatsStart = pad(vendorStart + vendorLen)
        val screenStart = formatsStart + nFormats * 8
        if (nScreens < 1) throw XProtocolException("server reported 0 screens")

        // Screen record:
        //   +0  root WINDOW           +4  default-colormap COLORMAP
        //   +8  white-pixel CARD32    +12 black-pixel CARD32
        //   +16 current-input-masks CARD32
        //   +20 width-in-pixels CARD16 +22 height-in-pixels CARD16
        //   +24 width-in-mm CARD16     +26 height-in-mm CARD16
        //   +28 min-installed-maps CARD16 +30 max-installed-maps CARD16
        //   +32 root-visual VISUALID   +36 backing-stores(1) save-unders(1)
        //       root-depth(1) allowed-depths-length(1)
        if (screenStart + 40 <= rest.size) {
            rootWindow = u32(screenStart)
            screenWidth = u16(screenStart + 20)
            screenHeight = u16(screenStart + 22)
        }

        queryGeometry()                  // authoritative depth + dimensions
        if (depth == 0) depth = 24
        if (screenWidth <= 0) screenWidth = 720
        if (screenHeight <= 0) screenHeight = 1280
    }

    // ======================================================== request plumbing
    private fun rawWrite(bytes: ByteArray) {
        synchronized(writeLock) { outp!!.write(bytes); outp!!.flush() }
    }

    private fun buildRequest(opcode: Int, minor: Int, body: ByteArray): ByteArray {
        val total = 4 + body.size
        val lenUnits = (total + 3) / 4               // ceil, never truncate
        val out = ByteArray(lenUnits * 4)
        val bb = ByteBuffer.wrap(out).order(order)
        bb.put(opcode.toByte()); bb.put(minor.toByte()); bb.putShort(lenUnits.toShort())
        bb.put(body)
        return out
    }

    /** Queue a request without flushing (for reply-less FakeInput bursts). */
    fun beginBatch() { pending = java.io.ByteArrayOutputStream(4096) }

    /** Flush a batch started with [beginBatch]. */
    fun endBatch() {
        val p = pending ?: return
        pending = null
        if (p.size() > 0) rawWrite(p.toByteArray())
    }

    private fun send(bytes: ByteArray) {
        val p = pending
        if (p != null) { synchronized(writeLock) { p.write(bytes) } } else rawWrite(bytes)
    }

    private fun readPacket(timeoutMs: Int): ByteArray? {
        sock?.soTimeout = timeoutMs
        runCatching { lsock?.soTimeout = timeoutMs }
        return try {
            synchronized(readLock) {
                val head = ByteArray(32)
                inp!!.readFully(head)
                when (head[0].toInt()) {
                    // Only REPLY packets carry a variable-length tail: in an
                    // error packet bytes 4-7 are the offending resource id, and
                    // in an event they are event-specific. Reading them as a
                    // length blocks forever and loses the stream.
                    PKT_REPLY -> {
                        val units = ByteBuffer.wrap(head, 4, 4).order(order).int
                        if (units > 0) { val e = ByteArray(units * 4); inp!!.readFully(e); head + e }
                        else head
                    }
                    PKT_GENERIC_EVENT -> {
                        val units = ByteBuffer.wrap(head, 4, 4).order(order).int
                        if (units > 0) { val e = ByteArray(units * 4); inp!!.readFully(e); head + e }
                        else head
                    }
                    PKT_ERROR -> {
                        val b = ByteBuffer.wrap(head).order(order)
                        synchronized(errors) {
                            errors.addLast(XErrorEvent(
                                head[1].toInt() and 0xFF,
                                // xError layout: code@1, sequenceNumber@2,
                                // resourceID@4, minorCode@8 (CARD16),
                                // majorCode@10 as a SINGLE BYTE, pad0@11.
                                // getShort(10) only happened to read the right
                                // value while pad0 was zero.
                                head[10].toInt() and 0xFF,
                                b.getShort(8).toInt() and 0xFFFF, b.getInt(4),
                                b.getShort(2).toInt() and 0xFFFF))
                            while (errors.size > 16) errors.removeFirst()
                        }
                        head
                    }
                    else -> head
                }
            }
        } catch (_: SocketTimeoutException) { null }
          catch (_: IOException) { null }
    }

    /**
     * Send a request and read until ITS reply arrives, queueing events and
     * recording errors. Returns null on timeout/EOF.
     */
    private fun requestReply(opcode: Int, minor: Int, body: ByteArray, timeoutMs: Int = 3_000): ByteArray? {
        val mySeq = synchronized(writeLock) { sequence = (sequence + 1) and 0xFFFF; sequence }
        send(buildRequest(opcode, minor, body))
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val pkt = readPacket((deadline - System.currentTimeMillis()).toInt().coerceAtLeast(50))
                ?: return null
            val b = ByteBuffer.wrap(pkt).order(order)
            val kind = pkt[0].toInt()
            if (kind == PKT_REPLY && (b.getShort(2).toInt() and 0xFFFF) == mySeq) return pkt
            if (kind == PKT_ERROR && (b.getShort(2).toInt() and 0xFFFF) == mySeq) return null
            // events and errors for other requests: keep looping
        }
        return null
    }

    private fun sendNoReply(opcode: Int, minor: Int, body: ByteArray) {
        synchronized(writeLock) { sequence = (sequence + 1) and 0xFFFF }
        send(buildRequest(opcode, minor, body))
    }

    /**
     * XSync: there is no core Sync request. Xlib round-trips GetInputFocus(43).
     * (Opcode 36 is GrabServer — calling it would freeze the WM, the panel and
     * the frame-capture pipeline until this connection closed.)
     */
    fun sync(timeoutMs: Int = 3_000): Boolean =
        requestReply(OP_GET_INPUT_FOCUS, 0, ByteArray(0), timeoutMs) != null

    /** GetGeometry(root) → authoritative depth + pixel dimensions. */
    private fun queryGeometry() {
        if (rootWindow == 0) return
        val req = ByteBuffer.allocate(4).order(order)
        req.putInt(rootWindow)
        val r = requestReply(OP_GET_GEOMETRY, 0, req.array()) ?: return
        if (r[0].toInt() != PKT_REPLY) return
        val rb = ByteBuffer.wrap(r).order(order)
        depth = rb.get(1).toInt() and 0xFF
        // GetGeometryReply: depth@1, drawable@8, x@12(INT16), y@14(INT16),
        // width@16(CARD16), height@18(CARD16), border-width@20(CARD16).
        val w = rb.getShort(16).toInt() and 0xFFFF
        val h = rb.getShort(18).toInt() and 0xFFFF
        if (w > 0) screenWidth = w
        if (h > 0) screenHeight = h
    }

    private fun discoverXtest() {
        // XTEST presence, then GetVersion so the extension is initialised.
        queryExtension("XTEST")
        if (xtestOpcode in 0..127) { xtestOpcode = -1; return }
        val body = ByteBuffer.allocate(4).order(order)
        body.putShort(2); body.putShort(2)           // want XTEST 2.2
        requestReply(xtestOpcode, XTEST_GET_VERSION, body.array())
    }

    fun queryExtension(name: String): Triple<Boolean, Int, Int> {
        val nb = name.toByteArray()
        // name-length CARD16, unused CARD16, name, pad.
        val body = ByteBuffer.allocate(4 + pad(nb.size)).order(order)
        // ByteBuffer.putShort takes a Short; nb.size is an Int, and Kotlin
        // only narrows integer LITERALS implicitly - a computed width must
        // be converted explicitly (caught by kotlinc, not by inspection).
        body.putShort(nb.size.toShort()); body.putShort(0)
        body.put(nb); repeat(pad(nb.size) - nb.size) { body.put(0) }
        val r = requestReply(OP_QUERY_EXTENSION, 0, body.array())
            ?: return Triple(false, -1, -1)
        if (r[0].toInt() != PKT_REPLY) return Triple(false, -1, -1)
        // xQueryExtensionReply: present@8 (BOOL), major-opcode@9 (CARD8),
        // first-event@10, first-error@11.
        val present = (r[8].toInt() and 0xFF) != 0
        val major = r[9].toInt() and 0xFF
        val firstEv = r[10].toInt() and 0xFF
        if (name == "XTEST" && present) {
            xtestOpcode = major
            xtestFirstEvent = firstEv
        }
        return Triple(present, major, firstEv)
    }

    fun internAtom(name: String, onlyIfExists: Boolean = false): Int {
        val nb = name.toByteArray()
        // xInternAtomReq: reqType, only-if-exists BOOL, length CARD16,
        // name-length CARD16, pad CARD16, name, pad-to-4.
        //
        // The BOOL is the request's SECOND BYTE - what buildRequest() calls
        // `minor` - and is NOT part of the body. Writing it into the body made
        // the request 4 bytes too long and pushed name-length into the pad
        // slot, so the server answered BadLength for EVERY atom (measured: even
        // "WM_NAME"), internAtom() always returned 0 and
        // isWindowManagerRunning() always reported false while Openbox was in
        // fact running. queryExtension() just above shows the correct shape.
        val body = ByteBuffer.allocate(4 + pad(nb.size)).order(order)
        // ByteBuffer.putShort takes a Short; nb.size is an Int, and Kotlin
        // only narrows integer LITERALS implicitly - a computed width must
        // be converted explicitly (caught by kotlinc, not by inspection).
        body.putShort(nb.size.toShort()); body.putShort(0)
        body.put(nb); repeat(pad(nb.size) - nb.size) { body.put(0) }
        val r = requestReply(OP_INTERN_ATOM, if (onlyIfExists) 1 else 0, body.array()) ?: return 0
        if (r[0].toInt() != PKT_REPLY) return 0
        return ByteBuffer.wrap(r).order(order).getInt(8)
    }

    /** GetProperty (CARD32 arrays e.g. _NET_SUPPORTING_WM_CHECK). */
    fun getProperty(window: Int, prop: Int, type: Int = 0, longOffset: Int = 0, longLength: Int = 1024): IntArray {
        // xGetPropertyReq: reqType, delete-after BOOL, length CARD16, window,
        // property, type, long-offset, long-length => the body is 20 bytes and
        // delete-after is the request's second byte (`minor`), not body padding.
        // Same bug class as internAtom(): a 24-byte body made every GetProperty
        // fail with BadLength. delete-after stays 0 - never delete on read.
        val body = ByteBuffer.allocate(20).order(order)
        body.putInt(window); body.putInt(prop); body.putInt(type)
        body.putInt(longOffset); body.putInt(longLength)
        val r = requestReply(OP_GET_PROPERTY, 0, body.array()) ?: return IntArray(0)
        if (r[0].toInt() != PKT_REPLY) return IntArray(0)
        val rb = ByteBuffer.wrap(r).order(order)
        val format = rb.get(1).toInt() and 0xFF
        // GetPropertyReply: type@8, bytes-after@12, nItems@16, data from @32.
        val nItems = rb.getInt(16)
        val count = nItems.coerceIn(0, longLength)
        val out = IntArray(count)
        for (i in 0 until count) {
            val off = 32 + when (format) { 32 -> i * 4; 16 -> i * 2; else -> i }
            if (off + 4 <= r.size) out[i] = when (format) {
                32 -> rb.getInt(off)
                16 -> rb.getShort(off).toInt() and 0xFFFF
                else -> r[off].toInt() and 0xFF
            }
        }
        return out
    }

    /** True when a window manager owns the screen (EWMH). */
    fun isWindowManagerRunning(): Boolean = runCatching {
        val a = internAtom("_NET_SUPPORTING_WM_CHECK", onlyIfExists = true)
        a != 0 && getProperty(rootWindow, a).isNotEmpty()
    }.getOrDefault(false)

    // =============================================== XTEST fake input
    /**
     * xXTestFakeInputReq — 36 bytes total, length 9 units:
     *   @0 reqType, @1 minor(2), @2 length,
     *   @4 type, @5 detail, @6/@7 pad,
     *   @8 time CARD32, @12 root WINDOW, @16 pad2[8],
     *   @24 rootX INT16, @26 rootY INT16, @28 deviceid CARD32
     *
     * Measured against a live server: 28-byte and 32-byte variants are both
     * rejected with BadLength(16); only 36 is accepted, and only with rootX/Y
     * at request offsets 24/26 does the pointer actually move.
     */
    private fun xtestFake(kind: Int, detail: Int, x: Int, y: Int) {
        if (xtestOpcode < 0) throw XProtocolException("XTEST unavailable")
        val body = ByteBuffer.allocate(FAKE_INPUT_BODY).order(order)
        body.put(FI_TYPE, kind.toByte())
        body.put(FI_DETAIL, detail.toByte())
        body.putInt(FI_TIME, 0)                      // CurrentTime
        body.putInt(FI_ROOT, rootWindow)
        body.putShort(FI_ROOT_X, x.toShort())
        body.putShort(FI_ROOT_Y, y.toShort())
        body.putInt(FI_DEVICEID, 0)                  // 0 = the core device
        sendNoReply(xtestOpcode, XTEST_FAKE_INPUT, body.array())
    }

    fun fakeKey(keycode: Int, press: Boolean) =
        xtestFake(if (press) FAKE_KEY_PRESS else FAKE_KEY_RELEASE, keycode, 0, 0)

    /** Server keyboard mapping -> keysym lookup table (GetKeyboardMapping = 101). */
    private fun buildKeymap() {
        keysymKey.clear()
        runCatching {
            val count = (maxKeyCode - minKeyCode + 1).coerceIn(1, 248)
            val body = ByteBuffer.allocate(4).order(order)
            body.put(minKeyCode.toByte()); body.put(count.toByte()); body.put(0); body.put(0)
            val r = requestReply(OP_GET_KEYBOARD_MAPPING, 0, body.array()) ?: return@runCatching
            if (r[0].toInt() != PKT_REPLY) return@runCatching
            val rb = ByteBuffer.wrap(r).order(order)
            val ksPer = rb.get(1).toInt() and 0xFF
            if (ksPer <= 0) return@runCatching
            for (i in 0 until count) {
                val kc = minKeyCode + i
                for (j in 0 until ksPer) {
                    val off = 32 + (i * ksPer + j) * 4
                    if (off + 4 > r.size) break
                    val sym = rb.getInt(off)
                    if (sym == 0xFFE1) shiftKeycode = kc           // Shift_L
                    if (sym != 0 && !keysymKey.containsKey(sym)) keysymKey[sym] = intArrayOf(kc, j)
                }
            }
        }
    }

    fun keysymKeycode(ks: Int): Int = keysymKey[ks]?.get(0) ?: -1
    fun keymapSize(): Int = keysymKey.size

    /** Tap a keysym (press+release), applying shift level 1 when needed. */
    fun typeKeysymTap(ks: Int): Boolean {
        val e = keysymKey[ks] ?: return false
        val kc = e[0]; val lvl = e[1]
        beginBatch()
        try {
            if (lvl == 1) fakeKey(shiftKeycode, true)
            fakeKey(kc, true); fakeKey(kc, false)
            if (lvl == 1) fakeKey(shiftKeycode, false)
        } finally { endBatch() }
        return true
    }

    /** Type one character (Latin-1 direct, else the Unicode keysym range). */
    /**
     * Type one character.
     *
     * Control characters must be mapped to their real keysyms: 0x0A is NOT an
     * assigned Latin-1 keysym, so a raw '\n' code finds no keycode in the
     * server keymap and the key silently does not type. XK_Return is 0xFF0D.
     */
    fun typeChar(c: Char): Boolean =
        typeKeysymTap(
            when (c) {
                '\n', '\r' -> 0xFF0D        // XK_Return
                '\t' -> 0xFF09              // XK_Tab
                '\b' -> 0xFF08              // XK_BackSpace
                '\u001B' -> 0xFF1B          // XK_Escape
                else -> if (c.code < 0x100) c.code else 0x01000000 + c.code
            })

    fun typeString(s: String): Boolean {
        var ok = true
        beginBatch()
        try { for (c in s) if (!typeChar(c)) ok = false } finally { endBatch() }
        return ok
    }

    fun fakeButton(button: Int, x: Int, y: Int, press: Boolean) {
        beginBatch()
        try {
            // Motion first so the click lands under the finger.
            xtestFake(FAKE_MOTION, 0, x, y)
            xtestFake(if (press) FAKE_BUTTON_PRESS else FAKE_BUTTON_RELEASE, button, x, y)
        } finally { endBatch() }
    }

    fun fakeMotion(x: Int, y: Int) = xtestFake(FAKE_MOTION, 0, x, y)

    /**
     * v0.2.65 INPUT SELF-TEST instrument: where does the server think the
     * pointer is, in root coordinates?
     *
     * XTEST FakeInput is fire-and-forget (sendNoReply), so a server that
     * silently ignores it is indistinguishable from one that honoured it —
     * which is exactly the field symptom ("INPUT: XTEST (control plane)" in
     * green while no click does anything). QueryPointer is the only way to
     * close that loop: inject a motion to a known point, read the pointer back,
     * compare.
     *
     * xQueryPointerReq  : reqType=38, pad, length=2, window CARD32
     * xQueryPointerReply: @1 same-screen, @8 root, @12 child,
     *                     @16 root-x INT16, @18 root-y INT16,
     *                     @20 win-x, @22 win-y, @24 mask
     */
    fun queryPointerRoot(): Pair<Int, Int>? {
        if (rootWindow == 0) return null
        val body = ByteBuffer.allocate(4).order(order)
        body.putInt(rootWindow)
        val r = requestReply(OP_QUERY_POINTER, 0, body.array()) ?: return null
        if (r[0].toInt() != PKT_REPLY) return null
        val rb = ByteBuffer.wrap(r).order(order)
        val x = rb.getShort(16).toInt()
        val y = rb.getShort(18).toInt()
        return x to y
    }

    // ============================================================ event pump
    /** Read one packet (event, reply or error); null on timeout/EOF. */
    fun readEvent(timeoutMs: Int = 100): ByteBuffer? {
        val pkt = readPacket(timeoutMs) ?: return null
        return ByteBuffer.wrap(pkt).order(order)
    }

    fun close() {
        connected = false
        runCatching { pending = null }
        runCatching { sock?.close() }
        runCatching { lsock?.close() }
        sock = null; lsock = null; inp = null; outp = null
    }
}
