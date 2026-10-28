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
 * EmuX X11 — a real, self-contained X Window System Protocol Version 11 client
 * implemented from scratch in Kotlin: no native code, no VNC, no third-party
 * X libraries. It talks the raw wire protocol to an X server running INSIDE the
 * bootstrapped Linux distro (started by the `emux-x11` launcher on loopback TCP
 * display :0 → port 6000).
 *
 * Wire format (X Protocol Spec):
 *  - Byte order is negotiated once at setup; we request our NATIVE order so no
 *    per-field swapping is ever needed (zero-cost marshalling on ARM/x86).
 *  - Request : opcode(1) misc(1) length-in-4-byte-units(CARD16) body...
 *  - Reply   : kind(1)=1 data(1) sequence(CARD16) replyLength-in-units(CARD32)
 *              then 24 fixed bytes + variable data.
 *  - Event   : type(1) detail(1) sequence(CARD16) + 28 payload bytes = 32 total.
 *
 * Control-plane requests implemented (authoritative xproto opcodes):
 * GetGeometry=14, QueryExtension=15, InternAtom=16, GetProperty=20, Sync=36.
 * Input injection uses the XTEST extension's FakeInput request discovered at
 * runtime via QueryExtension("XTEST") — no hardcoded extension opcode.
 * Framebuffer capture deliberately avoids GetImage: the in-distro capture
 * helper streams raw RGB frames to [DesktopService] over a local socket, which
 * moves fewer bytes with fewer round-trips than poll-based GetImage would.
 */
class X11Client(
    private val host: String = "127.0.0.1",
    private val display: Int = 0,
    // v0.2.29 FIELD FIX: absolute path of the rootfs unix socket
    // (<rootfs>/tmp/.X11-unix/XN). The field ROM's Xvfb binds unix but
    // never tcp (28 rounds of tcp gates failing against a healthy
    // server; xwd over unix :1 returned a valid XWD header rc=0), so
    // the control plane must speak unix first. tcp stays as fallback.
    private val unixSocketPath: String? = null,
) {
    companion object {
        const val PORT_BASE = 6000

        // Authoritative core opcodes (X Consortium Protocol Version 11, Ch.11-16)
        const val OP_CREATE_WINDOW = 1
        const val OP_CHANGE_WINDOW_ATTRS = 2
        const val OP_GET_WINDOW_ATTRS = 3
        const val OP_DESTROY_WINDOW = 4
        const val OP_MAP_WINDOW = 8
        const val OP_UNMAP_WINDOW = 10
        const val OP_CONFIGURE_WINDOW = 12
        const val OP_GET_GEOMETRY = 14
        const val OP_QUERY_EXTENSION = 15
        const val OP_INTERN_ATOM = 16
        const val OP_GET_PROPERTY = 20
        const val OP_SEND_EVENT = 25
        const val OP_SYNC = 36

        const val EV_EXPOSE = 12
        const val EV_GEOMETRY_NOTIFY = 22
        const val EV_MASK_BIT = 0x80

        const val XTEST_FAKE_INPUT = 0
    }

    class XProtocolException(msg: String) : IOException(msg)

    var rootWindow = 0; private set
    var screenWidth = 0; private set
    var screenHeight = 0; private set
    var depth = 0; private set
    var vendor = ""; private set
    var xtestOpcode = -1; private set

    @Volatile var connected = false; private set

    private var sock: Socket? = null
    private var lsock: android.net.LocalSocket? = null
    private var inp: DataInputStream? = null
    private var outp: BufferedOutputStream? = null
    private var order: ByteOrder = ByteOrder.LITTLE_ENDIAN
    private val writeLock = Any()
    private val readLock = Any()

    private fun pad(n: Int) = ceil(n / 4.0).toInt() * 4

    // ============================================================ connect/setup
    fun connect(timeoutMs: Int = 5_000) {
        var viaUnix = false
        val usp = unixSocketPath
        if (usp != null && java.io.File(usp).exists()) {
            try {
                val ls = android.net.LocalSocket()
                ls.connect(android.net.LocalSocketAddress(
                    usp, android.net.LocalSocketAddress.Namespace.FILESYSTEM))
                ls.receiveBufferSize = 256 * 1024
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
            sock = s
            inp = DataInputStream(s.inputStream.buffered(128 * 1024))
            outp = BufferedOutputStream(s.outputStream, 64 * 1024)
        }
        order = ByteOrder.nativeOrder()

        // Client handshake header = exactly 8 bytes (+ padded auth fields):
        // BYTE byte-order, BYTE unused, CARD16 major=11, CARD16 minor=0,
        // CARD16 authNameLen=0, CARD16 authDataLen=0. Loopback-only server ⇒ no cookie.
        val bb = ByteBuffer.allocate(8).order(order)
        bb.put(if (order == ByteOrder.LITTLE_ENDIAN) 'l'.code.toByte() else 'B'.code.toByte())
        bb.put(0)
        bb.putShort(11); bb.putShort(0); bb.putShort(0); bb.putShort(0)
        synchronized(writeLock) { outp!!.write(bb.array()); outp!!.flush() }

        parseSetupReply()
        // Discover XTEST (our input injection path).
        runCatching { queryExtension("XTEST") }
        connected = true
    }

    /**
     * Setup reply layout (spec §15.1 "Connection Setup"):
     *  success BYTE, unused BYTE, reasonLen CARD16, extraLen CARD16 (in units),
     *  release CARD32, resourceBase CARD32, resourceMask CARD32,
     *  motionBuf CARD32, vendorLen CARD16, nPixmaps BYTE, nRoots BYTE,
     *  byteOrder BYTE, visualType BYTE, scanlinePad BYTE, bitmapPad BYTE,
     *  bitmapUnit BYTE, unused BYTE[3]
     *  → formats[nPixmaps] (8 bytes each)
     *  → roots[nRoots]: WINDOW root, CARD32 x,y,w,h, CARD16 minW,minH,maxW,maxH,
     *     CARD32 currentDepthVisual…, etc. (we only need root id + w/h which sit
     *     at fixed offsets before anything version-dependent).
     */
    private fun parseSetupReply() {
        val ins = inp!!
        val head = ByteArray(8)
        ins.readFully(head)
        val h = ByteBuffer.wrap(head).order(order)
        val success = h.get().toInt()
        if (success != 1) {
            val reasonLen = (h.get(6).toInt() and 0xFF) or ((h.get(7).toInt() and 0xFF) shl 8)
            val reason = ByteArray(reasonLen.coerceAtMost(4096))
            if (reason.isNotEmpty()) ins.readFully(reason)
            throw XProtocolException("X server refused: ${String(reason)}")
        }
        // On success spec puts vendor-release info in the FIRST 8 bytes differently:
        // success(1) unused(1) releaseMajor? Actually: after success byte comes
        // unused(1), then protocolMajorVersion CARD16, protocolMinorVersion CARD16,
        // release CARD32... but the fixed header we already consumed contains those.
        // The remaining variable part length is at bytes 6-7 as extraLen-in-units:
        val extraUnits = ((h.get(6).toInt() and 0xFF) shl 8) or (h.get(7).toInt() and 0xFF)
        val rest = ByteArray(extraUnits * 4)
        ins.readFully(rest)
        val b = ByteBuffer.wrap(rest).order(order)

        val release = b.int
        b.int; b.int                     // resourceIdBase, resourceIdMask
        b.int                            // motionBufferSize
        val vendorLen = b.short.toInt() and 0xFFFF
        val nFormats = b.get().toInt() and 0xFF
        val nScreens = b.get().toInt() and 0xFF
        // server byte order should match ours since we requested native
        b.get(); b.get(); b.get(); b.get(); b.get(); b.get() // bo,vt,sp,bp,bu,unused
        b.position(b.position() + nFormats * 8)              // skip pixmap formats
        check(nScreens >= 1) { "server reported 0 screens" }

        // First screen record — stable prefix fields:
        rootWindow = b.int
        b.int; b.int                     // x, y origins
        screenWidth = b.int
        screenHeight = b.int
        b.short; b.short                 // minWidth, minHeight
        b.short; b.short                 // maxWidth, maxHeight
        // Next comes currentWidth/currentHeight? In xproto Screen_t after max sizes:
        // CARD32 rootVisual? No: rootVisual is VISUALID right after mm dims in some
        // versions; we obtain depth authoritatively via GetGeometry instead.
        vendor = runCatching {
            val v = ByteArray(vendorLen.coerceAtMost(maxOf(0, rest.size - b.position())))
            System.arraycopy(rest, b.position(), v, 0, v.size)
            String(v)
        }.getOrDefault("unknown")

        queryGeometry()                  // fills depth + re-confirms w/h
        if (depth == 0) depth = 24
    }

    // ======================================================== request plumbing
    private fun send(bytes: ByteArray) {
        synchronized(writeLock) { outp!!.write(bytes); outp!!.flush() }
    }

    private fun buildRequest(opcode: Int, misc: Int, body: ByteArray): ByteArray {
        val lenUnits = (4 + body.size) / 4
        val bb = ByteBuffer.allocate(4 + body.size).order(order)
        bb.put(opcode.toByte()); bb.put(misc.toByte()); bb.putShort(lenUnits.toShort())
        bb.put(body)
        return bb.array()
    }

    /** Read one reply packet (fixed 32B + variable). Null on timeout/EOF. */
    fun readReply(timeoutMs: Int = 3_000): ByteArray? {
        sock?.soTimeout = timeoutMs
        runCatching { lsock?.soTimeout = timeoutMs }
        return try {
            synchronized(readLock) {
                val head = ByteArray(32)
                inp!!.readFully(head)
                val units = ByteBuffer.wrap(head, 4, 4).order(order).int
                if (units > 0) {
                    val extra = ByteArray(units * 4)
                    inp!!.readFully(extra)
                    head + extra
                } else head
            }
        } catch (_: SocketTimeoutException) { null }
          catch (_: IOException) { null }
    }

    fun sync(): Boolean {
        send(buildRequest(OP_SYNC, 0, ByteArray(0)))
        return readReply() != null
    }

    /** GetGeometry(root) → authoritative depth + pixel dimensions. */
    private fun queryGeometry() {
        val req = ByteBuffer.allocate(8).order(order)
        req.put(OP_GET_GEOMETRY.toByte()); req.put(0)
        req.putShort(2)
        req.putInt(rootWindow)
        send(req.array())
        val r = readReply() ?: return
        if (r.isEmpty() || (r[0].toInt() and 0xFF) != 1) return
        val rb = ByteBuffer.wrap(r).order(order)
        depth = rb.get(1).toInt() and 0xFF
        screenWidth = rb.getInt(12)   // reply: drawable(4) x(4) y(4) w(4) h(4)...
        screenHeight = rb.getInt(16)  // offsets per xproto GetGeometryReply
    }

    fun queryExtension(name: String): Triple<Boolean, Int, Int> {
        val nb = name.toByteArray()
        val body = ByteBuffer.allocate(4 + pad(nb.size)).order(order)
        body.put(nb.size.toByte()); body.put(0); body.put(0); body.put(0)
        body.put(nb); repeat(pad(nb.size) - nb.size) { body.put(0) }
        send(buildRequest(OP_QUERY_EXTENSION, 0, body.array()))
        val r = readReply() ?: return Triple(false, -1, -1)
        val rb = ByteBuffer.wrap(r).order(order)
        val present = rb.get(1).toInt() == 1
        val major = rb.get(2).toInt() and 0xFF
        val firstEv = rb.get(3).toInt() and 0xFF
        if (name == "XTEST" && present) xtestOpcode = major
        return Triple(present, major, firstEv)
    }

    fun internAtom(name: String, onlyIfExists: Boolean = false): Int {
        val nb = name.toByteArray()
        val body = ByteBuffer.allocate(4 + pad(nb.size)).order(order)
        body.put(nb.size.toByte()); body.put(if (onlyIfExists) 1 else 0)
        body.put(0); body.put(0)
        body.put(nb); repeat(pad(nb.size) - nb.size) { body.put(0) }
        send(buildRequest(OP_INTERN_ATOM, 0, body.array()))
        val r = readReply() ?: return 0
        return ByteBuffer.wrap(r).order(order).getInt(8)
    }

    /** GetProperty (CARD32 arrays e.g. _NET_SUPPORTING_WM_CHECK). */
    fun getProperty(prop: Int, type: Int, longOffset: Int = 0, longLength: Int = 1024): IntArray {
        val body = ByteBuffer.allocate(24).order(order)
        body.put(0); body.put(0); body.put(0); body.put(0)   // delete-after BYTE + pad
        body.putInt(prop); body.putInt(type)
        body.putInt(longOffset); body.putInt(longLength)
        send(buildRequest(OP_GET_PROPERTY, 0, body.array()))
        val r = readReply() ?: return IntArray(0)
        val rb = ByteBuffer.wrap(r).order(order)
        val nItems = rb.getInt(4)                            // reply: nItems CARD32
        val out = IntArray(minOf(nItems, longLength))
        for (i in out.indices) {
            val off = 32 + i * 4
            if (off + 4 <= r.size) out[i] = rb.getInt(off)
        }
        return out
    }

    // =============================================== XTEST fake input (keyboard/mouse)
    /**
     * xtest/FakeInput request (xtestproto):
     *   opcode(ext-major), request=0(FakeInput), length(2), type(1: press=0? no:
     *   Kind: 0=KeyPress? XTEST defines: X_XTestFakeInput with "type": 
     *   2=KeyPress,3=KeyRelease,4=ButtonPress,5=ButtonRelease,6=Motion),
     *   detail(BYTE keycode/button), pad(2), time(CARD32), root(WINDOW),
     *   rootX(INT16), rootY(INT16), pad(4). Total 24 bytes.
     */
    private fun xtestFake(kind: Int, detail: Int, x: Int, y: Int) {
        check(xtestOpcode >= 0) { "XTEST unavailable" }
        val body = ByteBuffer.allocate(20).order(order)
        body.put(XTEST_FAKE_INPUT.toByte())   // ext request number
        body.put(0); body.put(0)              // pad
        body.put(kind.toByte())
        body.put(detail.toByte())
        body.put(0); body.put(0); body.put(0) // pad
        body.putInt(0)                        // time = CurrentTime
        body.putInt(rootWindow)
        body.putShort(x.toShort()); body.putShort(y.toShort())
        body.putInt(0)                        // pad
        send(buildRequest(xtestOpcode, 0, body.array()))
    }

    fun fakeKey(keycode: Int, press: Boolean) =
        xtestFake(if (press) 2 else 3, keycode, 0, 0)

    fun fakeButton(button: Int, x: Int, y: Int, press: Boolean) {
        // Motion first so the click lands under the finger, then press/release.
        xtestFake(6, 0, x, y)
        xtestFake(if (press) 4 else 5, button, x, y)
    }

    fun fakeMotion(x: Int, y: Int) = xtestFake(6, 0, x, y)

    // ============================================================ event pump
    /** Blocking 32-byte event read; null on timeout. */
    fun readEvent(timeoutMs: Int = 100): ByteBuffer? {
        sock?.soTimeout = timeoutMs
        runCatching { lsock?.soTimeout = timeoutMs }
        return try {
            val ev = ByteArray(32)
            synchronized(readLock) { inp!!.readFully(ev) }
            ByteBuffer.wrap(ev).order(order)
        } catch (_: SocketTimeoutException) { null }
          catch (_: IOException) { null }
    }

    fun close() {
        connected = false
        runCatching { sock?.close() }
        runCatching { lsock?.close() }
        sock = null; lsock = null; inp = null; outp = null
    }
}
