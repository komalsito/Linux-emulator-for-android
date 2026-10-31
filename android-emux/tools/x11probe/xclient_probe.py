#!/usr/bin/env python3
"""
Byte-exact replica of com.emux.x11.X11Client's handshake + XTEST path,
written from the Kotlin source so we can ask the SAME questions of a real
X server that the Android app asks -- and see whether the server honours
FakeInput.

Usage: xclient_probe.py <unix-socket-path-or-tcp-host> <display> [label]
"""
import socket, struct, sys, time

ORDER = "<"          # little-endian, what ByteOrder.nativeOrder() gives on ARM
OP_GET_GEOMETRY = 14
OP_QUERY_POINTER = 38
OP_GET_INPUT_FOCUS = 43
OP_QUERY_EXTENSION = 98
OP_GET_KEYBOARD_MAPPING = 101
XTEST_GET_VERSION = 0
XTEST_FAKE_INPUT = 2
FAKE_MOTION = 6
FAKE_BUTTON_PRESS = 4
FAKE_BUTTON_RELEASE = 5
FAKE_KEY_PRESS = 2
FAKE_KEY_RELEASE = 3


class C:
    def __init__(self, target, display=0, label=""):
        self.label = label
        self.seq = 0
        self.errors = []
        if target.startswith("/"):
            self.s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            self.s.connect(target)
            self.transport = "unix"
        else:
            self.s = socket.create_connection((target, 6000 + display))
            self.transport = "tcp"
        self.s.settimeout(8)
        self.buf = b""
        self.rootWindow = 0
        self.w = self.h = 0
        self.xtestOpcode = -1
        self.minKC, self.maxKC = 8, 255
        self.keysymKey = {}
        self.shiftKeycode = 50

    # ---- plumbing -------------------------------------------------------
    def _w(self, b):
        self.s.sendall(b)

    def _read(self, n):
        while len(self.buf) < n:
            d = self.s.recv(65536)
            if not d:
                raise EOFError("server closed")
            self.buf += d
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def build(self, opcode, minor, body):
        total = 4 + len(body)
        n = (total + 3) // 4
        return struct.pack(ORDER + "BBH", opcode & 0xFF, minor & 0xFF, n) + body

    def send_no_reply(self, opcode, minor, body):
        self.seq = (self.seq + 1) & 0xFFFF
        self._w(self.build(opcode, minor, body))

    def read_packet(self, timeout=3.0):
        self.s.settimeout(timeout)
        try:
            head = self._read(32)
        except socket.timeout:
            return None
        kind = head[0]
        if kind in (1, 35):                      # reply / generic event
            units = struct.unpack_from(ORDER + "i", head, 4)[0]
            if units > 0:
                head = head + self._read(units * 4)
        elif kind == 0:                          # error
            code = head[1]
            seqno = struct.unpack_from(ORDER + "H", head, 2)[0]
            res = struct.unpack_from(ORDER + "i", head, 4)[0]
            minor = struct.unpack_from(ORDER + "H", head, 8)[0]
            major = head[10]                     # SINGLE BYTE (as in Kotlin)
            self.errors.append((code, major, minor, res, seqno))
        return head

    def request_reply(self, opcode, minor, body, timeout=3.0):
        self.seq = (self.seq + 1) & 0xFFFF
        my = self.seq
        self._w(self.build(opcode, minor, body))
        end = time.time() + timeout
        while time.time() < end:
            p = self.read_packet(max(0.05, end - time.time()))
            if p is None:
                return None
            if p[0] == 1 and struct.unpack_from(ORDER + "H", p, 2)[0] == my:
                return p
            if p[0] == 0 and struct.unpack_from(ORDER + "H", p, 2)[0] == my:
                return None
        return None

    # ---- connect --------------------------------------------------------
    def connect(self):
        self._w(struct.pack(ORDER + "BBHHHHH", ord("l"), 0, 11, 0, 0, 0, 0))
        head = self._read(8)
        if head[0] != 1:
            units = struct.unpack_from(ORDER + "H", head, 6)[0]
            reason = self._read(units * 4) if units else b""
            raise RuntimeError("server refused: %r" % reason)
        units = struct.unpack_from(ORDER + "H", head, 6)[0]
        rest = self._read(units * 4)
        vendor_len = struct.unpack_from(ORDER + "H", rest, 16)[0]
        nscreens = rest[20]
        nformats = rest[21]
        self.minKC = rest[26]
        self.maxKC = rest[27]
        self.vendor = rest[32:32 + vendor_len].decode("latin1")
        pad = lambda n: ((n + 3) // 4) * 4
        fmt_start = pad(32 + vendor_len)
        scr = fmt_start + nformats * 8
        self.rootWindow = struct.unpack_from(ORDER + "I", rest, scr)[0]
        self.w = struct.unpack_from(ORDER + "H", rest, scr + 20)[0]
        self.h = struct.unpack_from(ORDER + "H", rest, scr + 22)[0]
        print("[%s] transport=%s vendor=%r nscreens=%d root=0x%x %dx%d minKC=%d maxKC=%d"
              % (self.label, self.transport, self.vendor, nscreens,
                 self.rootWindow, self.w, self.h, self.minKC, self.maxKC))

        self.query_geometry()
        self.discover_xtest()
        self.build_keymap()
        return self

    def query_geometry(self):
        if not self.rootWindow:
            return
        r = self.request_reply(OP_GET_GEOMETRY, 0, struct.pack(ORDER + "I", self.rootWindow))
        if r and r[0] == 1:
            w = struct.unpack_from(ORDER + "H", r, 16)[0]
            h = struct.unpack_from(ORDER + "H", r, 18)[0]
            if w: self.w = w
            if h: self.h = h
        print("[%s] GetGeometry -> %dx%d depth=%d" % (self.label, self.w, self.h, r[1] if r else -1))

    def discover_xtest(self):
        nb = b"XTEST"
        body = struct.pack(ORDER + "HH", len(nb), 0) + nb + b"\0" * (((len(nb) + 3) // 4) * 4 - len(nb))
        r = self.request_reply(OP_QUERY_EXTENSION, 0, body)
        if r and r[0] == 1:
            present = r[8]
            major = r[9]
            first_ev = r[10]
            print("[%s] QueryExtension(XTEST): present=%d major=%d first-event=%d"
                  % (self.label, present, major, first_ev))
            if present:
                self.xtestOpcode = major
        # Kotlin: if (xtestOpcode in 0..127) { xtestOpcode = -1; return }
        if 0 <= self.xtestOpcode <= 127:
            self.xtestOpcode = -1
            return
        body = struct.pack(ORDER + "HH", 2, 2)
        r2 = self.request_reply(self.xtestOpcode, XTEST_GET_VERSION, body)
        if r2 and r2[0] == 1:
            print("[%s] XTEST GetVersion reply: major=%d minor=%d"
                  % (self.label, r2[8], struct.unpack_from(ORDER + "H", r2, 4)[0]))
        else:
            print("[%s] XTEST GetVersion -> NO REPLY (err=%s)" % (self.label, self.errors[-1:]))

    def build_keymap(self):
        count = max(1, min(248, self.maxKC - self.minKC + 1))
        body = struct.pack(ORDER + "BBBB", self.minKC, count, 0, 0)
        r = self.request_reply(OP_GET_KEYBOARD_MAPPING, 0, body)
        if not r or r[0] != 1:
            print("[%s] GetKeyboardMapping FAILED" % self.label)
            return
        ks_per = r[1]
        for i in range(count):
            kc = self.minKC + i
            for j in range(ks_per):
                off = 32 + (i * ks_per + j) * 4
                if off + 4 > len(r):
                    break
                sym = struct.unpack_from(ORDER + "I", r, off)[0]
                if sym == 0xFFE1:
                    self.shiftKeycode = kc
                if sym and sym not in self.keysymKey:
                    self.keysymKey[sym] = (kc, j)
        print("[%s] keymap: %d keysyms, ksPer=%d shiftKeycode=%d"
              % (self.label, len(self.keysymKey), ks_per, self.shiftKeycode))

    # ---- injection (byte-exact xtestFake) -------------------------------
    def xtest_fake(self, kind, detail, x, y):
        if self.xtestOpcode < 0:
            raise RuntimeError("XTEST unavailable")
        body = bytearray(32)                       # FAKE_INPUT_BODY = 32
        body[0] = kind & 0xFF                      # FI_TYPE
        body[1] = detail & 0xFF                    # FI_DETAIL
        struct.pack_into(ORDER + "i", body, 4, 0)  # FI_TIME = CurrentTime
        struct.pack_into(ORDER + "I", body, 8, self.rootWindow)   # FI_ROOT
        struct.pack_into(ORDER + "h", body, 20, x)  # FI_ROOT_X -> req 24
        struct.pack_into(ORDER + "h", body, 22, y)  # FI_ROOT_Y -> req 26
        struct.pack_into(ORDER + "I", body, 24, 0)  # FI_DEVICEID -> req 28
        self.send_no_reply(self.xtestOpcode, XTEST_FAKE_INPUT, bytes(body))

    def fake_motion(self, x, y):
        self.xtest_fake(FAKE_MOTION, 0, x, y)

    def fake_button(self, button, x, y, press):
        self.fake_motion(x, y)
        self.xtest_fake(FAKE_BUTTON_PRESS if press else FAKE_BUTTON_RELEASE, button, x, y)

    def fake_key(self, keycode, press):
        self.xtest_fake(FAKE_KEY_PRESS if press else FAKE_KEY_RELEASE, keycode, 0, 0)

    def query_pointer(self):
        r = self.request_reply(OP_QUERY_POINTER, 0, struct.pack(ORDER + "I", self.rootWindow))
        if not r or r[0] != 1:
            return None
        return (struct.unpack_from(ORDER + "h", r, 16)[0],
                struct.unpack_from(ORDER + "h", r, 18)[0])

    # ---- the v0.2.65 proof, verbatim ------------------------------------
    def run_proof(self):
        p0 = self.query_pointer()
        if p0 is None:
            return "no-ptr-reply", None, None
        txp = max(4, min(p0[0] + 48, max(8, self.w - 4)))
        typ = max(4, min(p0[1] + 48, max(8, self.h - 4)))
        self.fake_motion(txp, typ)
        time.sleep(0.25)
        p1 = self.query_pointer()
        errs = self.errors
        if p1 and abs(p1[0] - txp) <= 4 and abs(p1[1] - typ) <= 4:
            return "ok", p0, (txp, typ, p1)
        return ("rejected:" + str(errs[-1]) if errs else "ignored"), p0, (txp, typ, p1)


def desc_err(e):
    names = {1: "BadRequest", 2: "BadValue", 3: "BadWindow", 4: "BadPixmap", 5: "BadAtom",
             6: "BadCursor", 7: "BadFont", 8: "BadMatch", 9: "BadDrawable", 10: "BadAccess",
             11: "BadAlloc", 12: "BadColor", 13: "BadGC", 14: "BadIDChoice", 15: "BadName",
             16: "BadLength", 17: "BadImplementation"}
    return "%s(major=%d minor=%d res=0x%x seq=%d)" % (names.get(e[0], "code%d" % e[0]), e[1], e[2], e[3], e[4])


if __name__ == "__main__":
    target = sys.argv[1]
    disp = int(sys.argv[2]) if len(sys.argv) > 2 else 0
    label = sys.argv[3] if len(sys.argv) > 3 else target
    c = C(target, disp, label).connect()
    print()
    verdict, p0, info = c.run_proof()
    print("[%s] POINTER before proof: %s" % (label, p0))
    print("[%s] PROOF -> %s   wanted=(%s,%s) got=%s" % (label, verdict, info[0], info[1], info[2]) if info else "[%s] PROOF -> %s" % (label, verdict))
    if c.errors:
        for e in c.errors[:8]:
            print("[%s] X error: %s" % (label, desc_err(e)))
    print("[%s] total X errors: %d" % (label, len(c.errors)))
