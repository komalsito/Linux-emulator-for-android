#!/usr/bin/env python3
"""
Reference X11/XTEST client with a CORRECT packet pump.

This is the byte-level contract the fixed X11Client.kt must satisfy.  Every
offset here was verified against a live Xvfb 1.21 + Openbox 3.6.1.

Protocol facts established empirically (see probe/*.py output):
  * setup reply length-of-additional-data is NATIVE (little-endian) order;
    reading it big-endian asks for 85028 bytes and blocks forever.
  * QueryExtension is core opcode 98. Opcode 15 is QueryTree, and sending the
    QueryExtension body to QueryTree returns BadLength, so XTEST is never
    discovered and every fake-input call throws "XTEST unavailable".
  * QueryExtensionReply: present@8, major-opcode@9, first-event@10, first-error@11.
  * xXTestFakeInputReq is 36 bytes (length 9 units). 28- and 32-byte variants
    are rejected with BadLength. rootX@24 rootY@26 (body offsets 20/22),
    deviceid@28.
  * There is no core "Sync" request; Xlib's XSync round-trips GetInputFocus(43).
    Core opcode 36 is GrabServer, which freezes EVERY other client on the
    display (window manager, panel, and the frame-capture pipeline included).
  * Replies, errors and events share the same 32-byte header, so a reader must
    dispatch on byte 0 (0=Error, 1=Reply, >=2 Event) instead of assuming the
    next packet is the reply it asked for.
"""
import socket, struct, sys, collections

OP_GET_GEOMETRY        = 14
OP_INTERN_ATOM         = 16
OP_GET_PROPERTY        = 20
OP_GET_INPUT_FOCUS     = 43   # the real XSync round-trip
OP_QUERY_EXTENSION     = 98   # NOT 15
OP_LIST_EXTENSIONS     = 99
OP_GET_KEYBOARD_MAPPING= 101

XTEST_MINOR_FAKE_INPUT = 2
KIND_KEYPRESS, KIND_KEYRELEASE   = 2, 3
KIND_BTNPRESS, KIND_BTNRELEASE   = 4, 5
KIND_MOTION                      = 6

ERRNAMES = {1:"BadRequest",2:"BadValue",3:"BadWindow",4:"BadPixmap",5:"BadAtom",
            6:"BadCursor",7:"BadFont",8:"BadMatch",9:"BadDrawable",10:"BadAccess",
            11:"BadAlloc",12:"BadColor",13:"BadGC",14:"BadIDChoice",15:"BadName",
            16:"BadLength",17:"BadImplementation"}


class XError(Exception):
    def __init__(self, code, major, minor, bad):
        self.code, self.major, self.minor, self.bad = code, major, minor, bad
        super().__init__("X error %s(%d) on major=%d minor=%d badvalue=0x%x"
                         % (ERRNAMES.get(code, "?"), code, major, minor, bad))


class XClient:
    def __init__(self, host="127.0.0.1", port=6000, timeout=5.0):
        self.s = socket.create_connection((host, port), timeout=timeout)
        self.s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.s.settimeout(timeout)
        self.seq = 0
        self.events = collections.deque()
        self.errors = []
        self._handshake()

    # ---------------------------------------------------------------- wire
    def _recvn(self, n):
        b = b""
        while len(b) < n:
            c = self.s.recv(n - len(b))
            if not c:
                raise EOFError("X server closed the connection")
            b += c
        return b

    def _send(self, op, minor, body=b""):
        assert (4 + len(body)) % 4 == 0, "request must be 4-byte aligned (got %d)" % (4+len(body))
        self.seq = (self.seq + 1) & 0xFFFF
        self.s.sendall(struct.pack("<BBH", op, minor, (4 + len(body)) // 4) + body)
        return self.seq

    def _pump(self, want_seq=None):
        """Read packets until the reply for want_seq arrives. Events queue, errors raise.

        Only REPLY packets carry a variable-length tail: bytes 4-7 of an Error
        packet are the offending resource id and bytes 4-7 of an Event are
        event-specific, so treating them as a length blocks forever.
        """
        while True:
            h = self._recvn(32)
            kind = h[0]
            if kind == 1:                                     # Reply
                ln = struct.unpack_from("<I", h, 4)[0]
                pkt = h + (self._recvn(ln * 4) if ln else b"")
                sq = struct.unpack_from("<H", h, 2)[0]
                if want_seq is None or sq == want_seq:
                    return pkt
                continue
            if kind == 0:                                     # Error (always 32 B)
                code = h[1]
                sq = struct.unpack_from("<H", h, 2)[0]
                bad = struct.unpack_from("<I", h, 4)[0]
                minor = struct.unpack_from("<H", h, 8)[0]
                major = struct.unpack_from("<H", h, 10)[0]
                self.errors.append((code, major, minor, bad, sq))
                if want_seq is None or sq == want_seq:
                    raise XError(code, major, minor, bad)
                continue
            pkt = h                                           # Event (32 B core)
            if kind == 35:                                    # GenericEvent has a tail
                ln = struct.unpack_from("<I", h, 4)[0]
                pkt = h + (self._recvn(ln * 4) if ln else b"")
            self.events.append(pkt)

    def request(self, op, minor, body=b""):
        return self._pump(self._send(op, minor, body))

    def request_noreply(self, op, minor, body=b""):
        self._send(op, minor, body)

    def sync(self):
        """XSync: round-trip GetInputFocus and surface any pending error.

        NOTE: no non-blocking drain here. TCP is a stream, so a recv(32) in
        non-blocking mode can return a PARTIAL packet; discarding it desyncs
        every later read. _pump() already dispatches correctly on byte 0 and
        records errors whose sequence does not match the awaited reply.
        """
        self.request(OP_GET_INPUT_FOCUS, 0)
        return self.errors[-1] if self.errors else None

    # ------------------------------------------------------------- setup
    def _handshake(self):
        req = bytearray(12); req[0] = ord('l')
        struct.pack_into("<HHHH", req, 2, 11, 0, 0, 0)
        self.s.sendall(bytes(req))
        h = self._recvn(8)
        if h[0] != 1:
            rlen = struct.unpack_from("<H", h, 6)[0]
            raise XError(0, 0, 0, 0) if False else RuntimeError(
                "server refused: %r" % self._recvn(rlen * 4))
        extra = struct.unpack_from("<H", h, 6)[0]        # NATIVE order!
        self.setup = rest = self._recvn(extra * 4)
        self.release     = struct.unpack_from("<I", rest, 0)[0]
        self.res_base    = struct.unpack_from("<I", rest, 4)[0]
        self.res_mask    = struct.unpack_from("<I", rest, 8)[0]
        self.motion_buf  = struct.unpack_from("<I", rest, 12)[0]
        vlen             = struct.unpack_from("<H", rest, 16)[0]
        self.max_req_len = struct.unpack_from("<H", rest, 18)[0]
        self.n_screens   = rest[20]
        self.n_formats   = rest[21]
        self.min_keycode = rest[26]
        self.max_keycode = rest[27]
        self.vendor      = rest[32:32+vlen].decode("latin-1")
        self.formats_off = (32 + vlen + 3) & ~3
        self.screens_off = self.formats_off + self.n_formats * 8
        self.root        = struct.unpack_from("<I", rest, self.screens_off)[0]
        self.width, self.height = struct.unpack_from("<HH", rest, self.screens_off + 20)
        # authoritative depth via GetGeometry
        r = self.request(OP_GET_GEOMETRY, 0, struct.pack("<I", self.root))
        self.depth = r[1]
        gw, gh = struct.unpack_from("<HH", r, 16)        # width@16 height@18
        if gw: self.width, self.height = gw, gh
        # extensions
        self.xtest_op, self.xtest_event, self.xtest_error = self.query_extension("XTEST")
        self.keymap, self.shift_keycode = self.get_keyboard_mapping()

    def query_extension(self, name):
        nb = name.encode()
        r = self.request(OP_QUERY_EXTENSION, 0,
                         struct.pack("<Hxx", len(nb)) + nb + b"\x00" * ((-len(nb)) % 4))
        if r[8] == 0:
            return (-1, -1, -1)
        return (r[9], r[10], r[11])

    def get_keyboard_mapping(self):
        count = self.max_keycode - self.min_keycode + 1
        r = self.request(OP_GET_KEYBOARD_MAPPING, 0,
                         struct.pack("<BBxx", self.min_keycode, count))
        ksper = r[1]
        table, shift = {}, 50
        if ksper:
            for i in range(count):
                for j in range(ksper):
                    sym = struct.unpack_from("<I", r, 32 + (i*ksper + j)*4)[0]
                    if sym == 0xFFE1: shift = self.min_keycode + i
                    if sym and sym not in table: table[sym] = (self.min_keycode + i, j)
        return table, shift

    def intern_atom(self, name, only_if_exists=False):
        nb = name.encode()
        body = struct.pack("<BxxxHxx", 1 if only_if_exists else 0, len(nb)) + nb \
               + b"\x00" * ((-len(nb)) % 4)
        r = self.request(OP_INTERN_ATOM, 0, body)
        return struct.unpack_from("<I", r, 8)[0]

    def get_property(self, window, prop, ptype=0, offset=0, length=1024):
        # delete-after(BYTE) + 3 pad + window + property + type + offset + length
        # = 24 bytes.  X11Client.kt allocates 20 and OMITS `window`, so the
        # server reads the property atom as a window id and returns BadWindow.
        body = struct.pack("<BxxxIIIII", 0, window, prop, ptype, offset, length)
        assert len(body) == 24
        r = self.request(OP_GET_PROPERTY, 0, body)
        fmt = r[1]
        atype = struct.unpack_from("<I", r, 8)[0]
        after = struct.unpack_from("<I", r, 12)[0]
        nitems = struct.unpack_from("<I", r, 16)[0]
        vals = []
        if fmt == 32:
            vals = [struct.unpack_from("<I", r, 32 + i*4)[0] for i in range(nitems)]
        elif fmt == 16:
            vals = [struct.unpack_from("<H", r, 32 + i*2)[0] for i in range(nitems)]
        elif fmt == 8:
            vals = list(r[32:32+nitems])
        return dict(format=fmt, type=atype, after=after, n_items=nitems, values=vals)

    # ------------------------------------------------------------- XTEST
    def fake_input(self, kind, detail=0, x=0, y=0, root=None, time_ms=0, deviceid=0):
        if self.xtest_op < 0:
            raise RuntimeError("XTEST extension not present")
        b = bytearray(32)                    # 36-byte request => 32-byte body
        b[0] = kind
        b[1] = detail
        struct.pack_into("<I", b, 4, time_ms)
        struct.pack_into("<I", b, 8, self.root if root is None else root)
        struct.pack_into("<hh", b, 20, x, y)
        struct.pack_into("<I", b, 24, deviceid)
        self.request_noreply(self.xtest_op, XTEST_MINOR_FAKE_INPUT, bytes(b))

    def motion(self, x, y):        self.fake_input(KIND_MOTION, 0, x, y)
    def button(self, n, x, y, press):
        self.motion(x, y)
        self.fake_input(KIND_BTNPRESS if press else KIND_BTNRELEASE, n, x, y)
    def key(self, keycode, press): self.fake_input(KIND_KEYPRESS if press else KIND_KEYRELEASE, keycode)

    def type_keysym(self, ks):
        e = self.keymap.get(ks)
        if not e: return False
        kc, lvl = e
        if lvl == 1: self.key(self.shift_keycode, True)
        self.key(kc, True); self.key(kc, False)
        if lvl == 1: self.key(self.shift_keycode, False)
        return True

    def type_text(self, text):
        ok = []
        for ch in text:
            ks = ord(ch) if ord(ch) < 0x100 else 0x01000000 + ord(ch)
            ok.append(self.type_keysym(ks))
        return all(ok)

    def close(self):
        try: self.s.close()
        except Exception: pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 6099
    c = XClient(port=port)
    print("connected: %s release=%d root=0x%x %dx%d depth=%d" %
          (c.vendor, c.release, c.root, c.width, c.height, c.depth))
    print("XTEST opcode=%d  keymap syms=%d  Shift_L=%d" %
          (c.xtest_op, len(c.keymap), c.shift_keycode))
    wm = c.intern_atom("_NET_SUPPORTING_WM_CHECK")
    p = c.get_property(c.root, wm)
    print("_NET_SUPPORTING_WM_CHECK on root: %s -> %s" %
          ("[0x%x]" % p["values"][0] if p["values"] else "absent (no WM running)"))
    c.close()
