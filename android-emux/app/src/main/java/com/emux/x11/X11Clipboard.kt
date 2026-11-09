package com.emux.x11

import java.nio.ByteBuffer

/**
 * v0.2.113 CLIPBOARD BRIDGE, rebuilt on the X protocol itself.
 *
 * v0.2.111 polled `xclip -o` from a guest shell loop every 1-2 s and passed
 * text through files. Field: "copying from the desktop to outside is weird
 * and doesn't grab it correctly" - the copy only crossed on the next poll, so
 * switching apps right after Ctrl+C (when Android may freeze our process
 * tree) lost it, xterm pushed every accidental selection, and xclip had to be
 * installed first.
 *
 * Now the app holds its own X connection on a background thread:
 *  - Linux -> tablet: XFIXES tells us the instant CLIPBOARD changes owner; we
 *    convert it to UTF8_STRING (STRING fallback, INCR for big copies) and hand
 *    the text to [onText] - milliseconds after the copy, while EmuX is still
 *    in front.
 *  - tablet -> Linux: [offer] makes our window the owner of CLIPBOARD and
 *    PRIMARY and answers every paste request (TARGETS / UTF8_STRING / TEXT /
 *    STRING) from Linux apps, for as long as nobody copies something else.
 * Nothing is installed or polled in the guest.
 *
 * v0.2.117 RELIABILITY (field: "desktop to tablet doesn't work - the tablet
 * doesn't recognise the new copied items; tablet -> desktop sometimes pastes
 * stale text"). The XFIXES push is kept, but it is no longer the only road:
 *  - [resync] asks whoever owns CLIPBOARD right now for its text. When WE own
 *    the selection it loops back and deliver() suppresses it; when a Linux
 *    app owns it, the text reaches the tablet even if the XFIXES event was
 *    never delivered or Android froze us mid-delivery. DesktopService calls
 *    it on window focus and every 3 s while foreground, so both sides always
 *    converge.
 *  - [servedText] lets the push guard in DesktopService tell "the bridge is
 *    really serving this text" from "we just saw it once" (the stale-paste
 *    bug: clipLast matched while the bridge served something older/nothing).
 *  - every crossing is logged through [log], so the desktop status line
 *    names WHICH side stopped working in the next field report.
 */
class X11Clipboard(
    /** A CONNECTED client on the live desktop display, or null (retried). */
    private val connect: () -> X11Client?,
    /** Text copied inside Linux. Called on the bridge thread. */
    private val onText: (String) -> Unit,
    private val log: (String) -> Unit = {},
) {
    @Volatile private var running = false
    @Volatile private var client: X11Client? = null
    private var thread: Thread? = null
    private val pendingOffer = java.util.concurrent.atomic.AtomicReference<String?>(null)
    @Volatile private var served: ByteArray? = null
    @Volatile private var lastDelivered: String? = null
    @Volatile private var resyncRequested = false

    /** v0.2.117: the text the bridge is serving to Linux right now, or null
     *  when a Linux app owns the clipboard (or nothing was ever offered). */
    val servedText: String? get() = served?.toString(Charsets.UTF_8)

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "emux-clipboard").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { client?.close() }
        thread?.interrupt()
        thread = null
    }

    /** Tablet -> Linux: own CLIPBOARD + PRIMARY with [text]. */
    fun offer(text: String) {
        if (text.isEmpty()) return
        lastDelivered = text           // never bounce it back to the tablet
        pendingOffer.set(text)
    }

    /** v0.2.117 Linux -> tablet PULL: ask the CURRENT clipboard owner for its
     *  text now. Consumed by the serve loop (no-op while disconnected). */
    fun resync() { resyncRequested = true }

    private fun loop() {
        var announced = false
        while (running) {
            val c = runCatching { connect() }.getOrNull()
            if (c == null) { nap(2_000); continue }
            client = c
            try {
                serve(c, announced)
                announced = true
            } catch (t: Throwable) {
                if (running) log("clipboard bridge: ${t.javaClass.simpleName} ${t.message ?: ""} - reconnecting")
            } finally {
                runCatching { c.close() }
                client = null
            }
            nap(1_000)
        }
    }

    private fun nap(ms: Long) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }

    private fun serve(c: X11Client, quiet: Boolean) {
        val win = c.createSelectionWindow()
        val clipboard = c.internAtom("CLIPBOARD")
        val utf8 = c.internAtom("UTF8_STRING")
        val textAtom = c.internAtom("TEXT")
        val targets = c.internAtom("TARGETS")
        val incr = c.internAtom("INCR")
        val prop = c.internAtom("_EMUX_CLIP")
        if (clipboard == 0 || utf8 == 0 || targets == 0 || prop == 0)
            throw java.io.IOException("atoms unavailable")
        val evXfixes = c.watchSelections(win, intArrayOf(clipboard))
        if (evXfixes < 0) throw java.io.IOException("XFIXES unavailable")
        if (!quiet) log("clipboard bridge: watching CLIPBOARD on the desktop (XFIXES)")

        // A re-connect keeps serving what the tablet offered last.
        served?.let { pendingOffer.compareAndSet(null, String(it, Charsets.UTF_8)) }
        // v0.2.117: and pulls once, so whatever Linux copied while the
        // bridge was down reaches the tablet as soon as we are back.
        resyncRequested = true

        while (running && c.connected) {
            pendingOffer.getAndSet(null)?.let { txt ->
                // One X request carries at most ~256 KB; INCR sending is not
                // worth its complexity for a tablet paste - cap the payload.
                var b = txt.toByteArray(Charsets.UTF_8)
                if (b.size > MAX_SERVE) b = b.copyOf(MAX_SERVE)
                served = b
                c.setSelectionOwner(win, clipboard)
                c.setSelectionOwner(win, XA_PRIMARY)
                log("clipboard bridge: tablet -> linux (${b.size} bytes)")
            }
            if (resyncRequested) {
                resyncRequested = false
                c.convertSelection(win, clipboard, utf8, prop)
            }
            val ev = c.readEvent(200) ?: continue
            val type = ev.get(0).toInt() and 0x7F
            when (type) {
                evXfixes -> {
                    // XFixesSelectionNotify: subtype@1 window@4 owner@8 selection@12
                    // (v0.2.117: subtype 0 = SetSelectionOwner only - ignore
                    // WindowDestroy/ClientClose, whose owner field is not a
                    // live window to convert from.)
                    val subtype = ev.get(1).toInt()
                    val owner = ev.getInt(8)
                    if (subtype == 0 && ev.getInt(12) == clipboard && owner != 0 && owner != win)
                        c.convertSelection(win, clipboard, utf8, prop)
                }
                X11Client.EV_SELECTION_NOTIFY -> {
                    // time@4 requestor@8 selection@12 target@16 property@20
                    val target = ev.getInt(16)
                    if (ev.getInt(20) == 0) {
                        // owner refused UTF8_STRING: old clients speak STRING
                        if (target == utf8) c.convertSelection(win, clipboard, XA_STRING, prop)
                        continue
                    }
                    val r = c.getPropertyBytes(win, prop, delete = true) ?: continue
                    val bytes = if (r.first == incr) readIncr(c, win, prop) else r.third
                    deliver(bytes, if (r.first == incr) target else r.first)
                }
                X11Client.EV_SELECTION_REQUEST -> answer(c, ev, utf8, textAtom, targets)
                // SelectionClear: a Linux app copied - XFIXES reports the new owner.
            }
        }
    }

    /** ICCCM INCR: the owner sends chunks as PropertyNotify(NewValue) on [prop];
     *  each chunk is read with delete, a zero-length chunk ends the transfer. */
    private fun readIncr(c: X11Client, win: Int, prop: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && out.size() < MAX_RECEIVE) {
            val ev = c.readEvent(250) ?: continue
            if ((ev.get(0).toInt() and 0x7F) != X11Client.EV_PROPERTY_NOTIFY) continue
            // window@4 atom@8 time@12 state@16 (0 = NewValue)
            if (ev.getInt(4) != win || ev.getInt(8) != prop || ev.get(16).toInt() != 0) continue
            val r = c.getPropertyBytes(win, prop, delete = true) ?: break
            if (r.third.isEmpty()) break
            out.write(r.third)
        }
        return out.toByteArray()
    }

    private fun deliver(bytes: ByteArray, type: Int) {
        if (bytes.isEmpty()) return
        val text = (if (type == XA_STRING) String(bytes, Charsets.ISO_8859_1)
                    else String(bytes, Charsets.UTF_8)).trimEnd('\u0000')
        if (text.isEmpty() || text == lastDelivered) return
        lastDelivered = text
        log("clipboard bridge: linux -> tablet (${text.length} chars)")
        onText(text)
    }

    private fun answer(c: X11Client, ev: ByteBuffer, utf8: Int, textAtom: Int, targets: Int) {
        // time@4 owner@8 requestor@12 selection@16 target@20 property@24
        val time = ev.getInt(4)
        val requestor = ev.getInt(12)
        val selection = ev.getInt(16)
        val target = ev.getInt(20)
        var property = ev.getInt(24)
        if (property == 0) property = target          // obsolete requestors (ICCCM)
        val data = served
        when {
            data == null -> property = 0
            target == targets -> c.changeProperty(requestor, property, XA_ATOM, 32,
                c.int32s(targets, utf8, textAtom, XA_STRING))
            target == utf8 || target == textAtom -> c.changeProperty(requestor, property, utf8, 8, data)
            target == XA_STRING -> c.changeProperty(requestor, property, XA_STRING, 8,
                String(data, Charsets.UTF_8).toByteArray(Charsets.ISO_8859_1))
            else -> property = 0                        // refuse unknown targets
        }
        c.sendSelectionNotify(requestor, selection, target, property, time)
    }

    companion object {
        const val XA_PRIMARY = 1
        const val XA_ATOM = 4
        const val XA_STRING = 31
        const val MAX_SERVE = 240 * 1024
        const val MAX_RECEIVE = 4 * 1024 * 1024
    }
}
