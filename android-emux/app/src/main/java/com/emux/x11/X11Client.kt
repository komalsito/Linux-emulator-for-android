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
 * Framebuffer capture: v0.2.72 reads the root window IN-APP with GetImage
 * (ZPixmap) over a dedicated connection — the x11vnc/x0vncserver model, wire
 * format verified against a real Xvfb 21.1.7 by tools/xgetimage_wiretest.py.
 * The in-distro helper (emux-capture -> [DesktopService] over a local socket)
 * remains only as an automatic fallback.
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
        const val OP_GET_IMAGE = 73               // v0.2.71: native framebuffer grab
        const val OP_INTERN_ATOM = 16
        const val OP_GET_PROPERTY = 20
        const val OP_SEND_EVENT = 25
        const val OP_QUERY_POINTER = 38            // v0.2.65: the input self-test
        const val OP_SET_INPUT_FOCUS = 42          // v0.2.78: focus follows the probe
        const val OP_GET_INPUT_FOCUS = 43          // the real XSync round-trip
        const val OP_QUERY_EXTENSION = 98
        const val OP_LIST_EXTENSIONS = 99
        const val OP_GET_KEYBOARD_MAPPING = 101

        const val EV_BUTTON_PRESS = 4              // v0.2.78: the button proof
        const val EV_BUTTON_RELEASE = 5
        const val EV_MOTION_NOTIFY = 6
        const val EV_EXPOSE = 12
        const val EV_MAP_NOTIFY = 19
        const val EV_GEOMETRY_NOTIFY = 22
        const val EV_MASK_BIT = 0x80

        // Event masks (X.h) for the probe window's own selection.
        const val MASK_BUTTON_PRESS = 1 shl 2
        const val MASK_BUTTON_RELEASE = 1 shl 3
        const val MASK_POINTER_MOTION = 1 shl 6
        const val MASK_EXPOSURE = 1 shl 15
        const val MASK_STRUCTURE_NOTIFY = 1 shl 17
        // ChangeWindowAttributes value-mask bits (X.h).
        const val CW_BACK_PIXEL = 1 shl 1
        const val CW_EVENT_MASK = 1 shl 11

        // XTEST minor opcodes (xtestproto.h).
        const val XTEST_GET_VERSION = 0
        const val XTEST_FAKE_INPUT = 2
        // v0.2.78: X_XTestGrabControl. xorg's ProcXTestGrabControl is
        // REQUEST_SIZE_MATCH(xXTestGrabControlReq) -> an 8-byte request whose
        // only field is `impervious` (CARD8), and it calls
        // MakeClientGrabImpervious(client) / MakeClientGrabPervious(client).
        // Impervious means this client's fake events are delivered even while
        // another client holds a grab - the documented escape hatch from a
        // window manager that XGrabButton()s the pointer (see emux-x11's
        // write_rc_xml comment for the Openbox/xorg source evidence).
        const val XTEST_GRAB_CONTROL = 3

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

        /** v0.2.83: bound on the parked-event queue (see [pendingEvents]). */
        private const val MAX_QUEUED_EVENTS = 8_192

        /** v0.2.83: rectangles taken per drain. A burst bigger than this is
         *  simply continued on the next tick — the pending set in
         *  DesktopService carries it, so nothing is lost by stopping here. */
        const val MAX_RECTS_PER_DRAIN = 256

        /**
         * v0.2.84: RGB565 little-endian value -> opaque ARGB8888, as ONE
         * 64K-entry table. Public because DesktopService's band-diff painter
         * converts ONLY the bands that actually changed (and the -fbdir
         * mailbox files carry the same 565 format), so the conversion moved
         * out of the grab and into the painter. 256 KB of heap, built once,
         * and it makes the per-pixel work a single array load.
         */
        @JvmField
        val RGB565_TO_ARGB: IntArray = buildRgb565Lut()

        /**
         * Explicit while-loop build, NOT `IntArray(65536) { ... }`: kotlinc
         * 1.9.24 at -jvm-target 17 miscompiles the inlined array factory
         * inside <clinit> — the loop counter lands in a local slot that is
         * read before any store ("Unexpected read of missing local at index
         * 2"), the class file fails verification, and d8 drops X11Client
         * from the dex entirely. A plain function with a plain loop
         * compiles to bytecode every tool agrees on. Same table, same math.
         */
        private fun buildRgb565Lut(): IntArray {
            val lut = IntArray(65536)
            var v = 0
            while (v < 65536) {
                lut[v] = -0x1000000 or
                    (((v ushr 11) and 0x1F) * 255 / 31 shl 16) or
                    (((v ushr 5) and 0x3F) * 255 / 63 shl 8) or
                    ((v and 0x1F) * 255 / 31)
                v++
            }
            return lut
        }

        // v0.2.78: the button-delivery probe window. Small, and painted bright
        // green so that a field screenshot which happens to catch its ~1 s
        // lifetime is self-explanatory rather than mysterious.
        private const val PROBE_X = 64
        private const val PROBE_Y = 64
        private const val PROBE_SIZE = 72
        private const val PROBE_PIXEL = 0x00ff00
    }

    class XProtocolException(msg: String) : IOException(msg)

    /** v0.2.72: why the last grabRootArgb() failed ("" = ok). Painted on the
     *  STALE pane so a field screenshot names the rejection (BadLength=16,
     *  BadDrawable=9, timeout...) instead of just saying "no frames". */
    var lastGrabError: String = ""
    /** v0.2.73: why the last readPacket failed: "timeout" (server too slow to
     *  finish the reply — the proot pipe) vs "eof" (connection died). The
     *  v0.2.72 badge conflated them; the field answer was timeout. */
    var lastReadErr: String = ""
    /** v0.2.74: code of the last X error packet that matched a request of
     *  ours (BadLength=16, BadDrawable=9, BadMatch=8...). v0.2.73's field
     *  round showed `nat=no-reply` — an error packet whose CODE the
     *  telemetry discarded. Never again. */
    var lastXErrCode: Int = -1
    /** v0.2.75: full forensics of that error: code + major/minor opcode the
     *  server was parsing + resource id. `maj` tells us WHICH request the
     *  server considered malformed — desync vs. a genuinely rejected
     *  GetImage are different fixes and the pane now says which. */
    var lastXErr: String = ""

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
    // v0.2.78: the probe window has to be created with the screen's own visual
    // and depth - and since v0.2.74 the desktop may be depth 16, so neither can
    // be assumed. Resource ids come from the connection setup's base+mask.
    var rootVisual = 0; private set
    var rootDepth = 0; private set
    private var resBase = 0
    private var resMask = 0
    private var resCount = 0
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

        // xConnSetup: release-number@0, resource-id-base@4, resource-id-mask@8.
        resBase = u32(4)
        resMask = u32(8)
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
            rootVisual = u32(screenStart + 32)      // root-visual VISUALID
            rootDepth = g(screenStart + 38)         // root-depth CARD8
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

    /**
     * v0.2.83: the read timeout is a setsockopt per packet otherwise — one
     * extra syscall on every one of the thousands of packets a full-screen
     * sweep produces. Only touch it when it actually changes.
     */
    private var appliedTimeout = -1
    private fun applyTimeout(ms: Int) {
        if (ms == appliedTimeout) return
        appliedTimeout = ms
        runCatching { sock?.soTimeout = ms }
        runCatching { lsock?.soTimeout = ms }
    }

    /**
     * Read one packet.
     *
     * v0.2.83 [tailBuf]: a GetImage reply for one 576x1280 depth-16 screen is
     * 1.47 MB. The old path allocated that as a fresh ByteArray and then built
     * a SECOND array with `head + e` — 3 MB of allocation and a 1.47 MB copy
     * per full-screen grab, every refresh, on a phone. With a caller-supplied
     * buffer the payload lands where the decoder will read it and only the
     * 32-byte header is allocated. [lastTailLen] says how much of it is valid.
     */
    @Volatile var lastTailLen: Int = 0; private set

    private fun readPacket(timeoutMs: Int, tailBuf: ByteArray? = null): ByteArray? {
        applyTimeout(timeoutMs)
        return try {
            synchronized(readLock) {
                val head = ByteArray(32)
                inp!!.readFully(head)
                lastTailLen = 0
                when (head[0].toInt()) {
                    // Only REPLY packets carry a variable-length tail: in an
                    // error packet bytes 4-7 are the offending resource id, and
                    // in an event they are event-specific. Reading them as a
                    // length blocks forever and loses the stream.
                    PKT_REPLY -> {
                        val units = ByteBuffer.wrap(head, 4, 4).order(order).int
                        if (units <= 0) head
                        else if (tailBuf != null && tailBuf.size >= units * 4) {
                            inp!!.readFully(tailBuf, 0, units * 4)
                            lastTailLen = units * 4
                            head
                        } else { val e = ByteArray(units * 4); inp!!.readFully(e); head + e }
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
        } catch (_: SocketTimeoutException) { lastReadErr = "timeout"; null }
          catch (_: IOException) { lastReadErr = "eof"; null }
    }

    /**
     * v0.2.83 — EVENTS ARRIVING DURING A REQUEST ARE QUEUED, NOT DROPPED.
     *
     * This was the second half of why the desktop felt dead. The capture
     * connection subscribes to DAMAGE (v0.2.81), so while a GetImage reply is
     * in flight the server is also pushing xDamageNotify packets for whatever
     * the desktop drew meanwhile. The old loop's last branch was literally
     * "events and errors for other requests: keep looping" — every one of them
     * was read off the wire and thrown away.
     *
     * Consequence, exactly as fielded: a grab that takes 500 ms blindfolds the
     * damage stream for 500 ms. A dragged window damages continuously, so most
     * of its notifies landed inside a grab, were discarded, and the pixels they
     * described were never fetched. The pane then sat on stale content until
     * the ~5 s healer sweep redrew everything at once — "it waits for the input
     * to stop and after a while appears on the other side".
     *
     * Events are now parked in [pendingEvents] and served to drainDamageRects /
     * readEvent in arrival order, which is what Xlib's own queue does.
     */
    private val pendingEvents = ArrayDeque<ByteArray>()
    private fun queueEvent(pkt: ByteArray) {
        synchronized(pendingEvents) {
            pendingEvents.addLast(pkt)
            eventsQueued++
            // Bounded: a video playing inside a slow grab could otherwise grow
            // this without limit. Dropping the OLDEST is the right loss — the
            // healer sweep re-reads the screen, and the newest damage is the
            // damage that describes what is on screen now.
            while (pendingEvents.size > MAX_QUEUED_EVENTS) {
                pendingEvents.removeFirst()
                eventsDropped++
            }
        }
    }

    /** Telemetry: how many events were parked / lost to the bound above. */
    @Volatile var eventsQueued: Long = 0; private set
    @Volatile var eventsDropped: Long = 0; private set

    /** A reply plus, when [readPacket] was given a buffer, where its tail went. */
    private class Reply(val pkt: ByteArray, val tail: ByteArray?, val tailLen: Int)

    /**
     * Send a request and read until ITS reply arrives, queueing events and
     * recording errors. Returns null on timeout/EOF.
     */
    private fun requestReply(
        opcode: Int, minor: Int, body: ByteArray, timeoutMs: Int = 3_000,
        tailBuf: ByteArray? = null,
    ): ByteArray? = requestReplyFull(opcode, minor, body, timeoutMs, tailBuf)?.pkt

    private fun requestReplyFull(
        opcode: Int, minor: Int, body: ByteArray, timeoutMs: Int = 3_000,
        tailBuf: ByteArray? = null,
    ): Reply? {
        val mySeq = synchronized(writeLock) { sequence = (sequence + 1) and 0xFFFF; sequence }
        send(buildRequest(opcode, minor, body))
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val pkt = readPacket(
                (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(50), tailBuf)
                ?: return null
            val b = ByteBuffer.wrap(pkt).order(order)
            val kind = pkt[0].toInt()
            if (kind == PKT_REPLY && (b.getShort(2).toInt() and 0xFFFF) == mySeq) {
                return if (tailBuf != null && lastTailLen > 0) Reply(pkt, tailBuf, lastTailLen)
                else Reply(pkt, null, 0)
            }
            if (kind == PKT_ERROR && (b.getShort(2).toInt() and 0xFFFF) == mySeq) {
                lastXErrCode = pkt[1].toInt() and 0xFF
                lastXErr = "xerr=$lastXErrCode:maj=${pkt[10].toInt() and 0xFF}" +
                    ":min=${b.getShort(8).toInt() and 0xFFFF}:res=0x${Integer.toHexString(b.getInt(4))}"
                return null
            }
            // An event, or an error belonging to some other request: keep it —
            // a dropped DamageNotify is a rectangle of the desktop that never
            // gets repainted (see pendingEvents).
            if (kind != PKT_ERROR) queueEvent(pkt)
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

    // =====================================================================
    // v0.2.81 DAMAGE-DRIVEN PARTIAL CAPTURE (the x11vnc model, completed).
    //
    // Every release so far POLLED the screen: a full sweep of GetImage bands per
    // refresh, whether or not anything changed. On a thin proot pipe every
    // visual response therefore waits behind megabytes of pixels that were
    // identical to the previous frame - the "everything is laggy" that survives
    // every pacing and quality fix. DAMAGE inverts it: the server tells us the
    // rectangle a client just drew to, and we ship only that.
    //
    // MEASURED, not assumed (damage_probe.py, and the corpses of the first two
    // designs): QueryVersion on DAMAGE/XFIXES takes CARD32+CARD32 (the CARD16
    // form is BadLength); and on this Xvfb EVERY XFIXES region request
    // (CreateRegion/Subtract-with-parts/FetchRegion) is rejected outright, so
    // the region-polling design died on the sandbox. What works, everywhere, is
    // the original one: DamageNotify EVENTS. Create with level RawRectangles and
    // the server pushes xDamageNotify per drawn rectangle - no second socket,
    // no event loop, just the packets already arriving on this connection.
    //
    // xDamageNotify, measured byte by byte on Xvfb:
    //   0 type(=first_event)  1 level  2 sequence  4 time  8 drawable
    //   12 damage  16 geometry x,y INT16 + w,h CARD16  24 area (same shape)
    // =====================================================================
    @Volatile var damageOpcode = -1; private set
    @Volatile var damageEvent = -1; private set
    @Volatile var damageOn = false; private set
    private var damageId = 0

    /** Create a DAMAGE object on the root window; events then arrive here. */
    fun initDamageTracking(): Boolean {
        if (!connected || rootWindow == 0 || resMask == 0) return false
        val d = queryExtension("DAMAGE")
        if (!d.first) return false
        damageOpcode = d.second
        damageEvent = d.third
        // QueryVersion: CARD32 major + CARD32 minor - eight bytes. The 4-byte
        // CARD16 form is BadLength(16) on this extension (measured).
        val dv = ByteBuffer.allocate(8).order(order)
        dv.putInt(1); dv.putInt(0)
        requestReply(damageOpcode, 0, dv.array()) ?: return false
        damageId = newResourceId()
        // DamageCreate (minor 1): damage(4) drawable(4) level(BYTE)+3 pad.
        // level=RawRectangles(0): per-rectangle geometry in every notify.
        // Damage on the root includes drawing to inferior windows - that is the
        // whole point (client windows, WM frame moves, panel clocks).
        val c = ByteBuffer.allocate(12).order(order)
        c.putInt(damageId); c.putInt(rootWindow); c.put(0); c.put(0); c.put(0); c.put(0)
        sendNoReply(damageOpcode, 1, c.array())
        // Prove it took: a Sync round trip surfaces a rejected Create instead of
        // leaving us "damage-enabled" on a server that ignored us. (The first
        // design learned this the hard way: noreply + an unchecked sync made
        // every failure look like success.)
        if (!sync(2_000)) return false
        damageOn = true
        return true
    }

    /**
     * DamageNotify rectangles that arrived within [waitMs], as [x, y, w, h].
     * An EMPTY list means the desktop genuinely did not change - the capture
     * loop sleeps instead of shipping a screenful of identical pixels.
     *
     * v0.2.83 — TWO LATENCY FIXES.
     *
     * 1. Events parked by [requestReply] while a grab was in flight come FIRST
     *    and cost nothing: they are already in memory. Before this, a damage
     *    notify that arrived during a 400 ms grab was discarded outright, so
     *    the change it described was never fetched.
     * 2. [waitMs] is a ceiling on WAITING FOR THE FIRST RECT, not a fixed
     *    settle time. The old loop always burned the whole window — 33 ms of
     *    pure latency added to every single update, ~2 fps of it on its own.
     *    Now: block until something changes (an idle desktop pays no CPU),
     *    then keep reading for [coalesceMs] so a burst that belongs to one
     *    logical change (a window mapping draws its frame, its client area and
     *    the panel's task button) is fetched as one grab instead of three.
     */
    fun drainDamageRects(waitMs: Long = 33, coalesceMs: Long = 10): List<IntArray> {
        if (!damageOn) return emptyList()
        val out = ArrayList<IntArray>(4)
        synchronized(pendingEvents) {
            while (pendingEvents.isNotEmpty() && out.size < MAX_RECTS_PER_DRAIN) {
                val pkt = pendingEvents.removeFirst()
                damageRectOf(pkt)?.let { out.add(it) }
            }
        }
        if (out.isNotEmpty()) {
            // Already have work: only a short coalesce, never the full wait.
            collectDamage(out, coalesceMs)
            return out
        }
        // Nothing parked. Block for the first notify (this is the idle path —
        // parked on a socket read, no polling), then coalesce briefly.
        val first = readPacket(waitMs.toInt().coerceIn(1, 60_000))
        if (first == null) return out
        damageRectOf(first)?.let { out.add(it) }
        collectDamage(out, coalesceMs)
        return out
    }

    private fun collectDamage(out: ArrayList<IntArray>, coalesceMs: Long) {
        val deadline = System.currentTimeMillis() + coalesceMs
        while (out.size < MAX_RECTS_PER_DRAIN) {
            synchronized(pendingEvents) {
                while (pendingEvents.isNotEmpty() && out.size < MAX_RECTS_PER_DRAIN) {
                    damageRectOf(pendingEvents.removeFirst())?.let { out.add(it) }
                }
            }
            if (out.size >= MAX_RECTS_PER_DRAIN) return
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) return
            val pkt = readPacket(left.toInt().coerceIn(1, 50)) ?: return
            damageRectOf(pkt)?.let { out.add(it) }
        }
    }

    /** xDamageNotify -> [x, y, w, h], or null for anything that is not one. */
    private fun damageRectOf(pkt: ByteArray): IntArray? {
        if ((pkt[0].toInt() and 0x7F) != (damageEvent and 0x7F)) return null
        if (pkt.size < 24) return null
        val b = ByteBuffer.wrap(pkt).order(order)
        val x = b.getShort(16).toInt()
        val y = b.getShort(18).toInt()
        val w = b.getShort(20).toInt() and 0xFFFF
        val h = b.getShort(22).toInt() and 0xFFFF
        return if (w > 0 && h > 0) intArrayOf(x, y, w, h) else null
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
     * v0.2.85: one whole wheel gesture as a SINGLE buffered write - a leading
     * motion (the wheel lands under the pointer, wherever the gesture is
     * aimed), then [vClicks] press/release pairs on [vButton] and [hClicks]
     * pairs on [hButton]. Mouse and trackpad scrolling arrives as multi-notch
     * bursts; sending the notches one write at a time costs a syscall each and
     * lets a queued drag motion interleave mid-gesture. X encodes the wheel as
     * buttons: 4 up, 5 down, 6 left, 7 right. Pass 0 clicks to skip an axis.
     * Proven end-to-end by the JVM wire harness (a listening window counts
     * every notch of both axes arriving from one call).
     */
    fun fakeScrollBurst(x: Int, y: Int, vButton: Int, vClicks: Int,
                        hButton: Int, hClicks: Int) {
        beginBatch()
        try {
            xtestFake(FAKE_MOTION, 0, x, y)
            repeat(vClicks.coerceIn(0, 24)) {
                xtestFake(FAKE_BUTTON_PRESS, vButton, x, y)
                xtestFake(FAKE_BUTTON_RELEASE, vButton, x, y)
            }
            repeat(hClicks.coerceIn(0, 24)) {
                xtestFake(FAKE_BUTTON_PRESS, hButton, x, y)
                xtestFake(FAKE_BUTTON_RELEASE, hButton, x, y)
            }
        } finally { endBatch() }
    }

    // ============================== v0.2.78: windows, focus and the button proof
    /** Next resource id for this connection (Xlib's base+mask arithmetic). */
    private fun newResourceId(): Int {
        resCount++
        return (resBase + resCount) or (resBase and resMask.inv())
    }

    private fun send4(opcode: Int, win: Int) =
        sendNoReply(opcode, 0, ByteBuffer.allocate(4).order(order).putInt(win).array())

    /** Read and discard packets for a bounded time so the socket never fills with
     *  events nobody asked for (the probe window selects several masks). */
    private fun drainEvents(ms: Long) {
        val dl = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < dl) { readEvent(60) ?: continue }
    }

    /** GetGeometry -> [x, y, width, height]; x/y are relative to the PARENT. */
    private fun geometryOf(win: Int): IntArray? {
        val r = requestReply(OP_GET_GEOMETRY, 0,
            ByteBuffer.allocate(4).order(order).putInt(win).array()) ?: return null
        if (r[0].toInt() != PKT_REPLY) return null
        val b = ByteBuffer.wrap(r).order(order)
        // GetGeometryReply: depth@1, drawable@8, x@12 INT16, y@14 INT16,
        // width@16 CARD16, height@18 CARD16, border-width@20 CARD16.
        return intArrayOf(b.getShort(12).toInt(), b.getShort(14).toInt(),
            b.getShort(16).toInt() and 0xFFFF, b.getShort(18).toInt() and 0xFFFF)
    }

    /** QueryTree -> the parent window id (0 when there is none / on failure). */
    private fun parentOf(win: Int): Int {
        val r = requestReply(OP_QUERY_TREE, 0,
            ByteBuffer.allocate(4).order(order).putInt(win).array()) ?: return 0
        if (r[0].toInt() != PKT_REPLY) return 0
        // QueryTreeReply: root@8, parent@12, children-count@16, children@32.
        return ByteBuffer.wrap(r).order(order).getInt(12)
    }

    /** QueryPointer(root) -> [rootX, rootY, child] (child = top-level child). */
    fun queryPointerFull(): IntArray? {
        if (rootWindow == 0) return null
        val r = requestReply(OP_QUERY_POINTER, 0,
            ByteBuffer.allocate(4).order(order).putInt(rootWindow).array()) ?: return null
        if (r[0].toInt() != PKT_REPLY) return null
        val b = ByteBuffer.wrap(r).order(order)
        return intArrayOf(b.getShort(16).toInt(), b.getShort(18).toInt(), b.getInt(12))
    }

    /**
     * XTEST GrabControl: make this connection's fake input ignore other
     * clients' grabs. Returns "" on success, otherwise the reason.
     *
     * This is the *documented* escape hatch when a window manager holds a button
     * grab we cannot configure away, so it was the obvious one-request fix for
     * the v0.2.78 click swallow - and it was tried first. MEASURED RESULT: it
     * does not work for that bug. Against Openbox's passive Frame-context grab
     * the server accepts the request with no error and the click is STILL
     * swallowed (case G of tools/x11probe/button_delivery_test.sh; the same
     * negative result is recorded in docs/CLICK_SWALLOW_v0.2.78.md §5).
     *
     * So the app does NOT call this: nothing in the ladder depends on it, and
     * `btnMode` never becomes "impervious". It stays as a primitive because it
     * is the right tool for an ACTIVE grab (which is what the extension is for),
     * and because its wire layout - minor opcode 3, body [impervious,0,0,0] - is
     * exercised by the Python twin, button_probe.py --impervious. Do not re-add
     * it as a fallback without re-measuring.
     *
     * Trade-off, stated plainly: while impervious, an ACTIVE grab (a window
     * manager mid-drag) no longer receives our motion either, so titlebar
     * dragging degrades. That cost is a second reason it is not a free win.
     */
    fun xtestGrabControl(impervious: Boolean): String {
        if (xtestOpcode < 0) return "no-xtest"
        takeErrors()
        sendNoReply(xtestOpcode, XTEST_GRAB_CONTROL,
            byteArrayOf((if (impervious) 1 else 0).toByte(), 0, 0, 0))
        if (!sync(2_000)) return "no-reply"
        val e = takeErrors()
        return if (e.isEmpty()) "" else e.first().describe()
    }

    /** SetInputFocus(focus=PointerRoot, revert-to=PointerRoot, CurrentTime).
     *
     *  NOT called anywhere in the app — it was written for a fallback that
     *  measurement removed, and it is kept as a documented primitive (the wire
     *  layout stays exercised by `button_probe.py --pointer-root`).
     *
     *  Two things measurement settled about it:
     *   - it is redundant with no window manager: Xvfb's input focus ALREADY
     *     reports PointerRoot in that state (`XGetInputFocus` returns 1, which
     *     is what xdotool complains about as "the focused window of 1"), so
     *     setting it changes nothing;
     *   - PointerRoot is NOT per-window focus-follows-pointer. libX11 defines
     *     the focus window as "the root window of whatever screen the pointer
     *     is on at each keyboard event", which on a single-screen display is the
     *     root. Do not reach for this to make keys follow the finger. */
    fun setInputFocusPointerRoot(): Boolean {
        val body = ByteBuffer.allocate(8).order(order)
        body.putInt(1)          // focus = PointerRoot
        body.putInt(0)          // time = CurrentTime
        // revert-to travels in the request's minor byte: RevertToPointerRoot = 1
        sendNoReply(OP_SET_INPUT_FOCUS, 1, body.array())
        return sync(2_000)
    }

    /** What [probeButtonDelivery] measured. */
    class ButtonProbe(
        val delivered: Boolean,
        val presses: Int,
        val releases: Int,
        val motions: Int,
        val detail: String,
    )

    /**
     * v0.2.78 — THE OTHER HALF OF THE INPUT PROOF.
     *
     * The v0.2.65 proof injects a MOTION and reads the pointer back with
     * QueryPointer. That is a real round trip and it caught a server that
     * ignores FakeInput entirely - but it can never see a window manager that
     * grabs buttons, because a button grab does not touch motion at all. The
     * field result was `proof=ok touch=11 inj=32 fail=0` on a desktop where
     * nothing could be opened: motion honoured, every click stolen.
     *
     * This is the `xev` experiment the field ran by hand, done in-app: create a
     * real window (so the WM manages and frames it, exactly like any
     * application), map it, aim the pointer at its centre, inject a
     * ButtonPress/ButtonRelease through XTEST, and read our OWN connection for
     * the ButtonPress. If a passive grab is holding buttons, the event never
     * arrives - while the MotionNotify events we also selected DO arrive, which
     * is the signature that separates "buttons swallowed" from "input dead".
     *
     * Safety: no button event is injected unless QueryPointer first confirms the
     * pointer is inside the probe window's own rectangle. A missed aim would
     * otherwise click whatever the user has open.
     */
    fun probeButtonDelivery(button: Int = 1, settleMs: Long = 700, waitMs: Long = 1_200): ButtonProbe {
        if (!connected || rootWindow == 0) return ButtonProbe(false, 0, 0, 0, "not connected")
        if (xtestOpcode < 0) return ButtonProbe(false, 0, 0, 0, "no XTEST")
        if (resMask == 0) return ButtonProbe(false, 0, 0, 0, "no resource-id mask")
        var wid = 0
        var press = 0; var rel = 0; var mot = 0
        return try {
            wid = newResourceId()
            val mask = MASK_BUTTON_PRESS or MASK_BUTTON_RELEASE or MASK_POINTER_MOTION or
                MASK_EXPOSURE or MASK_STRUCTURE_NOTIFY
            val cfp = rootVisual == 0            // CopyFromParent, as XCreateSimpleWindow
            val body = ByteBuffer.allocate(36).order(order)
            body.putInt(wid)
            body.putInt(rootWindow)              // parent
            body.putShort(PROBE_X.toShort()); body.putShort(PROBE_Y.toShort())
            body.putShort(PROBE_SIZE.toShort()); body.putShort(PROBE_SIZE.toShort())
            body.putShort(0)                     // border-width
            body.putShort(if (cfp) 0 else 1)     // class: CopyFromParent / InputOutput
            body.putInt(if (cfp) 0 else rootVisual)
            body.putInt(CW_BACK_PIXEL or CW_EVENT_MASK)
            body.putInt(PROBE_PIXEL)             // values ordered by mask bit
            body.putInt(mask)
            sendNoReply(OP_CREATE_WINDOW, if (cfp) 0 else rootDepth, body.array())
            send4(OP_MAP_WINDOW, wid)

            // Let the WM see it: reparenting/framing happens after MapNotify, and
            // a framed window is the realistic case we must test against.
            var mapped = false
            var dl = System.currentTimeMillis() + settleMs
            while (System.currentTimeMillis() < dl) {
                val ev = readEvent(120) ?: continue
                if ((ev.get(0).toInt() and 0x7F) == EV_MAP_NOTIFY) mapped = true
            }

            // Root coordinates of the client area, by walking up to the root and
            // accumulating each ancestor's offset (the WM frame is in the path).
            fun locate(): IntArray? {
                val g = geometryOf(wid) ?: return null
                var x = g[0]; var y = g[1]; var cur = wid; var hops = 0
                while (hops < 8) {
                    val p = parentOf(cur)
                    if (p == 0 || p == rootWindow) break
                    val pg = geometryOf(p) ?: break
                    x += pg[0]; y += pg[1]; cur = p; hops++
                }
                return intArrayOf(x, y, g[2], g[3], hops)
            }
            val l0 = locate()
            if (l0 == null) return ButtonProbe(false, press, rel, mot,
                "probe window created (mapped=$mapped) but GetGeometry never answered")

            // Aim, then verify by QueryPointer before pressing anything.
            var aim = intArrayOf(l0[0] + l0[2] / 2, l0[1] + l0[3] / 2)
            var inside = false
            var tries = 0
            while (tries < 2 && !inside) {
                tries++
                fakeMotion(aim[0], aim[1])
                Thread.sleep(150)
                val l = locate() ?: l0
                val pq = queryPointerFull()
                if (pq != null && pq[0] >= l[0] && pq[0] <= l[0] + l[2] &&
                    pq[1] >= l[1] && pq[1] <= l[1] + l[3]) {
                    aim = intArrayOf(pq[0], pq[1]); inside = true
                } else {
                    aim = intArrayOf(l[0] + l[2] / 2, l[1] + l[3] / 2)
                }
            }
            if (!inside) return ButtonProbe(false, press, rel, mot,
                "aim-miss: the pointer never landed inside the probe window " +
                    "(rect ${l0[0]},${l0[1]} ${l0[2]}x${l0[3]}, framed=${l0[4] > 0}) - no click injected")

            val framed = l0[4] > 0
            takeErrors()
            xtestFake(FAKE_BUTTON_PRESS, button, aim[0], aim[1])
            Thread.sleep(90)
            xtestFake(FAKE_BUTTON_RELEASE, button, aim[0], aim[1])
            // One more motion AFTER the click, inside the same read window. Without
            // it a failure cannot be classified: "no ButtonPress" alone is also what
            // a dead transport looks like. "MotionNotify arrived, ButtonPress did
            // not" is the passive-grab signature, measured on the same connection at
            // the same moment (and it is exactly what the field's xev showed).
            xtestFake(FAKE_MOTION, 0, (aim[0] + 1).coerceAtMost(screenWidth - 2), aim[1])
            var got = false
            dl = System.currentTimeMillis() + waitMs
            while (System.currentTimeMillis() < dl) {
                val ev = readEvent(160) ?: continue
                when (ev.get(0).toInt() and 0x7F) {
                    EV_BUTTON_PRESS -> {
                        press++
                        if ((ev.get(1).toInt() and 0xFF) == button) got = true
                    }
                    EV_BUTTON_RELEASE -> rel++
                    EV_MOTION_NOTIFY -> mot++
                }
                if (got) break
            }
            val errs = takeErrors()
            val hexw = "0x" + Integer.toHexString(wid)
            val detail = if (got)
                "ButtonPress b=$button arrived at ${aim[0]},${aim[1]} on $hexw " +
                    "(framed=$framed, motion=$mot, release=$rel)"
            else
                "NO ButtonPress in ${waitMs}ms on $hexw (framed=$framed) while " +
                    "motion=$mot events DID arrive" +
                    if (errs.isNotEmpty()) "; " + errs.first().describe() else ""
            ButtonProbe(got, press, rel, mot, detail)
        } catch (e: Exception) {
            ButtonProbe(false, press, rel, mot, "probe threw ${e.javaClass.simpleName}: ${e.message?.take(80)}")
        } finally {
            if (wid != 0) runCatching {
                send4(OP_UNMAP_WINDOW, wid)
                send4(OP_DESTROY_WINDOW, wid)
                sync(800)
                drainEvents(120)
            }
        }
    }

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
    /** Read one packet (event, reply or error); null on timeout/EOF.
     *
     *  v0.2.83: parked events go first. probeButtonDelivery(), drainEvents()
     *  and the button proof all read through here, and every one of them used
     *  to miss any event that arrived while an earlier request's reply was
     *  being read — the probe counted "no ButtonPress came back" for a press
     *  that had in fact arrived and been discarded. */
    fun readEvent(timeoutMs: Int = 100): ByteBuffer? {
        synchronized(pendingEvents) {
            if (pendingEvents.isNotEmpty()) {
                return ByteBuffer.wrap(pendingEvents.removeFirst()).order(order)
            }
        }
        val pkt = readPacket(timeoutMs) ?: return null
        return ByteBuffer.wrap(pkt).order(order)
    }

    /** True when events are parked and would be served without blocking. */
    fun hasQueuedEvent(): Boolean = synchronized(pendingEvents) { pendingEvents.isNotEmpty() }

    /**
     * v0.2.71 — THE CAPTURE FIX. XGetImage(root, ZPixmap): the exact mechanism
     * every proven X remoting project falls back to when MIT-SHM is
     * unavailable (x11vnc's `-noxshm` path, TigerVNC's x0vncserver DumbFrame
     * backend). One persistent connection, zero guest binaries per frame — the
     * per-frame `xwd | xwdtopnm | pnmtoplainpnm | nc` chain it replaces was
     * the component that kept dying/wedging on the field rootfs (whose package
     * manager cannot run), freezing the desktop pane while input kept working.
     *
     * Wire format (xGetImageReq, 28 bytes = 7 units):
     *   drawable CARD32, x INT16, y INT16, width CARD16, height CARD16,
     *   plane-mask CARD32, format CARD8 (2 = ZPixmap) + 3 pad bytes.
     * Reply: 32-byte header with depth@1 and visual@8, then
     * length*4 bytes of ZPixmap data. For depth-24 TrueColor each pixel is
     * 4 bytes in BGRx order (red_mask=0xFF0000), row-strided to a multiple
     * of 4 bytes; the reply's scanlines are returned back-to-back from offset
     * 32 and the stride is derived from the payload size (never assumed).
     *
     * Returns packed 0xFFRRGGBB ARGB ints (w*h) ready for Bitmap.setPixels,
     * or null on any protocol failure so the caller can back off/reconnect.
     */
    /** Full-root convenience wrapper (kept for callers/tests). */
    fun grabRootArgb(): IntArray? =
        grabRegionArgb(0, 0, screenWidth, screenHeight, 6_000, null)

    // v0.2.83: RGB565 -> ARGB8888 component tables. The old decode called
    // android.graphics.Color.rgb() per pixel (three multiplies/divides and a
    // virtual call inside it) — 737,280 times for one full-screen grab, on the
    // phone's UI-critical path. Three 32/64/32-entry tables stay in L1 and the
    // per-pixel work becomes shifts and ORs. Identical output: Color.rgb() is
    // (0xFF<<24)|(r<<16)|(g<<8)|b, and (v*255)/31 == (v*255)/31 for v in 0..31.
    private val lutR5 = IntArray(32) { it * 255 / 31 }
    private val lutG6 = IntArray(64) { it * 255 / 63 }
    private val lutB5 = IntArray(32) { it * 255 / 31 }

    /** Reusable payload buffer for [grabRegionArgb]; grows to the biggest grab. */
    private var tailBuf: ByteArray? = null

    /**
     * v0.2.73 — BANDED CAPTURE. Field v0.2.72 proved the wire fix worked and
     * then named the NEXT wall: `nat=no-reply(timeout/eof)` — one 3.5 MB
     * full-screen reply cannot cross the proot-translated socket inside any
     * sane timeout on this device, while small replies pass. x11vnc solves
     * exactly this the same way: never request one giant image, request
     * REGIONS sized to the measured pipe. Each band is decoded into [reuse]
     * (or a fresh array) as w*rows packed ARGB ints, ready for
     * Bitmap.setPixels at (0, y).
     *
     * ---------------------------------------------------------------------
     * v0.2.83 — THE REQUEST WAS THE WRONG LENGTH, AND NOTHING EVER CAME BACK.
     *
     * xGetImageReq is exactly 20 bytes / 5 length-units:
     *
     *     CARD8  reqType(73)  CARD8 format(2=ZPixmap)  CARD16 length(5)
     *     DRAWABLE drawable     4
     *     INT16 x  INT16 y  CARD16 width  CARD16 height     8
     *     CARD32 plane-mask     4
     *
     * The body was built with `ByteBuffer.allocate(20)`, of which only 16
     * bytes were ever written — so `req.array()` handed buildRequest() a
     * 20-byte body, a 24-byte request and length=6. xorg's ProcGetImage is
     * REQUEST_SIZE_MATCH(xGetImageReq), i.e. exact, and answered
     * **BadLength(16) to every single grab, on every build since v0.2.71
     * introduced this method.** Measured here against a real Xvfb 1.21:
     *
     *     20B/5u exact    -> REPLY depth 16, payload 1474560 B
     *     24B/6u app-form -> ERROR code 16 (BadLength) major 73 (GetImage)
     *
     * That is why "native capture" never produced a frame on any device: the
     * probe failed (`native probe FAILED ... minimal 8-row bands`), every band
     * failed, three misses threw, the connection recycled with backoff forever,
     * and the app fell through to the guest xwd pipelines — which is what the
     * field was actually running, and what made the desktop slow enough to feel
     * broken. Everything downstream of that (0.5 fps, "the pipe is 720 KB/s",
     * capture-quality settings, adaptive bands) was measured against a
     * transport that had never once succeeded.
     *
     * The wire-test comment claimed "24 bytes / 6 units" and the wire test
     * itself (`tools/wiretest/xgetimage_wiretest.py`) packs a 16-byte body —
     * the test was right and the Kotlin it was supposed to mirror was not.
     * `tools/x11probe/jvmwire/run_jvm_wire_proof.sh` now runs THIS class
     * against a real Xvfb so the two cannot drift again.
     */
    fun grabRegionArgb(
        x: Int, y: Int, w: Int, h: Int, timeoutMs: Int, reuse: IntArray?,
    ): IntArray? {
        if (rootWindow == 0) return null
        if (w <= 0 || h <= 0) return null
        // 16 bytes: drawable + x + y + width + height + plane-mask. Exactly
        // xGetImageReq minus its 4-byte header. NOT 20 — see the note above.
        val req = ByteBuffer.allocate(16).order(order)
        req.putInt(rootWindow)
        req.putShort(x.toShort())
        req.putShort(y.toShort())
        req.putShort(w.toShort())
        req.putShort(h.toShort())
        req.putInt(-1)                  // plane-mask: all planes
        lastXErr = ""; lastXErrCode = -1
        // Payload lands in a reused buffer: no 1.5 MB allocation + copy per
        // full-screen grab.
        val need = w * h * 4            // worst case is depth 24/32
        var tb = tailBuf
        if (tb == null || tb.size < need) { tb = ByteArray(need); tailBuf = tb }
        val rep = requestReplyFull(OP_GET_IMAGE, 2, req.array(), timeoutMs, tb)
        if (rep == null) {
            lastGrabError = lastReadErr.ifBlank { lastXErr.ifBlank { "no-reply" } }
            return null
        }
        val r = rep.pkt
        if (r[0].toInt() != PKT_REPLY) {
            lastGrabError = "pkt=${r[0]} err=${r[1].toInt() and 0xFF}"
            return null
        }
        val replyDepth = r[1].toInt() and 0xFF
        // v0.2.74: depth 16 is the primary field path now (half the wire);
        // its ZPixmap layout IS RGB565 little-endian, the format this app's
        // frame path has always spoken.
        if (replyDepth != 24 && replyDepth != 32 && replyDepth != 16) {
            lastGrabError = "depth=$replyDepth"
            return null
        }
        val bpp = if (replyDepth == 16) 2 else 4
        val data = rep.tail
        val have = rep.tailLen
        if (data == null || have <= 0) { lastGrabError = "no-payload"; return null }
        if (have < w * h * bpp) { lastGrabError = "short=$have"; return null }
        val stride = have / h           // actual bytes per scanline
        if (stride < w * bpp) { lastGrabError = "stride=$stride"; return null }
        val out = if (reuse != null && reuse.size >= w * h) reuse else IntArray(w * h)
        var src = 0
        var dst = 0
        if (bpp == 2) {
            for (row in 0 until h) {
                var o = src
                for (cx in 0 until w) {
                    val v = (data[o].toInt() and 0xFF) or ((data[o + 1].toInt() and 0xFF) shl 8)
                    o += 2
                    out[dst + cx] = -0x1000000 or
                        (lutR5[(v ushr 11) and 0x1F] shl 16) or
                        (lutG6[(v ushr 5) and 0x3F] shl 8) or
                        lutB5[v and 0x1F]
                }
                src += stride
                dst += w
            }
        } else {
            for (row in 0 until h) {
                var o = src
                for (cx in 0 until w) {
                    out[dst + cx] = -0x1000000 or
                        ((data[o + 2].toInt() and 0xFF) shl 16) or
                        ((data[o + 1].toInt() and 0xFF) shl 8) or
                        (data[o].toInt() and 0xFF)
                    o += 4
                }
                src += stride
                dst += w
            }
        }
        lastGrabError = ""
        return out
    }

    /**
     * v0.2.84: the same grab as [grabRegionArgb], but the payload stays RAW
     * RGB565 little-endian — byte-for-byte the format of the RGB_565 bitmap
     * AND of Xvfb's -fbdir screen file. Zero conversion here: the caller
     * (DesktopService's band-diff painter) compares the bytes against the
     * previous frame and converts ONLY the 32-row bands that actually
     * changed, so an idle healer sweep costs a grab plus a memcmp and
     * uploads nothing, and a mailbox frame skips the screen entirely when
     * the desktop did not change.
     *
     * @param out receives w*h*2 bytes, row-major, no stride padding.
     * @return the number of valid bytes (w*h*2), or -1 on failure with the
     *         reason in [lastGrabError] (same error contract as grabRegionArgb).
     */
    fun grabRegion565(
        x: Int, y: Int, w: Int, h: Int, timeoutMs: Int, out: ByteArray,
    ): Int {
        if (rootWindow == 0 || w <= 0 || h <= 0) return -1
        val n = w * h * 2
        if (out.size < n) return -1
        // 16-byte body / 5-unit request — the v0.2.83 wire fix, unchanged.
        val req = ByteBuffer.allocate(16).order(order)
        req.putInt(rootWindow)
        req.putShort(x.toShort())
        req.putShort(y.toShort())
        req.putShort(w.toShort())
        req.putShort(h.toShort())
        req.putInt(-1)                  // plane-mask: all planes
        lastXErr = ""; lastXErrCode = -1
        val need = w * h * 4            // worst case is depth 24/32
        var tb = tailBuf
        if (tb == null || tb.size < need) { tb = ByteArray(need); tailBuf = tb }
        val rep = requestReplyFull(OP_GET_IMAGE, 2, req.array(), timeoutMs, tb)
        if (rep == null) {
            lastGrabError = lastReadErr.ifBlank { lastXErr.ifBlank { "no-reply" } }
            return -1
        }
        val r = rep.pkt
        if (r[0].toInt() != PKT_REPLY) {
            lastGrabError = "pkt=${r[0]} err=${r[1].toInt() and 0xFF}"
            return -1
        }
        val replyDepth = r[1].toInt() and 0xFF
        if (replyDepth != 16 && replyDepth != 24 && replyDepth != 32) {
            lastGrabError = "depth=$replyDepth"
            return -1
        }
        val bpp = if (replyDepth == 16) 2 else 4
        val data = rep.tail
        val have = rep.tailLen
        if (data == null || have <= 0) { lastGrabError = "no-payload"; return -1 }
        if (have < w * h * bpp) { lastGrabError = "short=$have"; return -1 }
        val stride = have / h
        if (stride < w * bpp) { lastGrabError = "stride=$stride"; return -1 }
        if (bpp == 2 && stride == w * 2) {
            System.arraycopy(data, 0, out, 0, n)     // the field-fast path: pure copy
        } else {
            // Strided 565, or a 24/32-bit server: repack to contiguous 565.
            var src = 0
            var dst = 0
            for (row in 0 until h) {
                if (bpp == 2) {
                    System.arraycopy(data, src, out, dst, w * 2)
                } else {
                    var o = src
                    for (cx in 0 until w) {
                        val rr = data[o + 2].toInt() and 0xFF
                        val gg = data[o + 1].toInt() and 0xFF
                        val bb = data[o].toInt() and 0xFF
                        val v = ((rr ushr 3) shl 11) or ((gg ushr 2) shl 5) or (bb ushr 3)
                        out[dst + cx * 2] = (v and 0xFF).toByte()
                        out[dst + cx * 2 + 1] = ((v ushr 8) and 0xFF).toByte()
                        o += 4
                    }
                }
                src += stride
                dst += w * 2
            }
        }
        lastGrabError = ""
        return n
    }

    fun close() {
        connected = false
        runCatching { pending = null }
        runCatching { sock?.close() }
        runCatching { lsock?.close() }
        sock = null; lsock = null; inp = null; outp = null
    }
}
