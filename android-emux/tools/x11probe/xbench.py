#!/usr/bin/env python3
"""xbench.py - measure X GetImage throughput against a live Xvfb.

Reproduces exactly what com.emux.x11.X11Client.grabRegionArgb() does on the
wire (GetImage, ZPixmap, plane-mask all, read the 32-byte reply header then
length*4 bytes of pixel data) so the numbers here are the numbers the app sees.

Usage: DISPLAY=:5 python3 xbench.py [label]
"""
import os, socket, struct, sys, time

OP_GET_GEOMETRY = 14
OP_QUERY_EXTENSION = 98
OP_GET_INPUT_FOCUS = 43
OP_GET_IMAGE = 73


class C:
    def __init__(self, host="127.0.0.1", port=6000, timeout=60.0, rcvbuf=256 * 1024):
        self.s = socket.create_connection((host, port), timeout=10)
        self.s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self.s.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, rcvbuf)
        self.s.settimeout(timeout)
        self.seq = 0
        self.f = self.s.makefile("rb", 1 << 17)
        self.events = 0
        self._handshake()

    def _recvn(self, n):
        b = self.f.read(n)
        if not b or len(b) != n:
            raise EOFError("short read %d/%d" % (len(b or b""), n))
        return b

    def _send(self, op, minor, body=b""):
        self.seq = (self.seq + 1) & 0xFFFF
        self.s.sendall(struct.pack("<BBH", op, minor, (4 + len(body)) // 4) + body)
        return self.seq

    def _pump(self, want):
        while True:
            h = self._recvn(32)
            k = h[0]
            if k == 1:
                ln = struct.unpack_from("<I", h, 4)[0]
                if ln:
                    tail = self._recvn(ln * 4)
                else:
                    tail = b""
                sq = struct.unpack_from("<H", h, 2)[0]
                if want is None or sq == want:
                    return h, tail
                continue
            if k == 0:
                raise RuntimeError("X error code=%d major=%d seq=%d" %
                                   (h[1], struct.unpack_from("<H", h, 10)[0],
                                    struct.unpack_from("<H", h, 2)[0]))
            self.events += 1

    def _handshake(self):
        req = bytearray(12); req[0] = ord('l')
        struct.pack_into("<HHHH", req, 2, 11, 0, 0, 0)
        self.s.sendall(bytes(req))
        h = self._recvn(8)
        assert h[0] == 1, "server refused"
        extra = struct.unpack_from("<H", h, 6)[0]
        rest = self._recvn(extra * 4)
        self.res_base = struct.unpack_from("<I", rest, 4)[0]
        self.res_mask = struct.unpack_from("<I", rest, 8)[0]
        vlen = struct.unpack_from("<H", rest, 16)[0]
        foff = (32 + vlen + 3) & ~3
        soff = foff + rest[21] * 8
        self.root = struct.unpack_from("<I", rest, soff)[0]
        self.width, self.height = struct.unpack_from("<HH", rest, soff + 20)
        r, _ = self._pump(self._send(OP_GET_GEOMETRY, 0, struct.pack("<I", self.root)))
        self.depth = r[1]
        gw, gh = struct.unpack_from("<HH", r, 16)
        if gw:
            self.width, self.height = gw, gh

    def get_image(self, x, y, w, h):
        body = struct.pack("<IhhHHI", self.root, x, y, w, h, 0xFFFFFFFF)
        t0 = time.perf_counter()
        r, tail = self._pump(self._send(OP_GET_IMAGE, 2, body))
        dt = time.perf_counter() - t0
        return dt, len(tail), r[1]

    def sync(self):
        self._pump(self._send(OP_GET_INPUT_FOCUS, 0))


def bench(label):
    disp = os.environ.get("DISPLAY", ":5")
    port = 6000 + int(disp.split(":")[1].split(".")[0])
    c = C(port=port)
    print("== %s: Xvfb %s  %dx%d depth%d (tcp:%d) ==" %
          (label, c.root and hex(c.root), c.width, c.height, c.depth, port))
    bpp = 2 if c.depth == 16 else 4
    W, H = c.width, c.height

    # latency-only round trip
    ts = []
    for _ in range(20):
        t0 = time.perf_counter(); c.sync(); ts.append(time.perf_counter() - t0)
    ts.sort()
    print("  sync RTT          : median %.2f ms  p90 %.2f ms" % (ts[10] * 1e3, ts[18] * 1e3))

    def run(name, w, h, n):
        best = None; rows = []
        for _ in range(n):
            dt, nbytes, d = c.get_image(0, 0, w, h)
            rows.append(dt)
            best = nbytes
        rows.sort()
        med = rows[len(rows) // 2]
        kbs = (best / 1024.0) / med if med > 0 else 0
        print("  %-18s: %7d B  med %8.2f ms  %9.1f KB/s  (%d runs, min %.2f ms)"
              % (name, best, med * 1e3, kbs, n, rows[0] * 1e3))
        return kbs

    run("probe %dx16" % W, W, 16, 8)
    run("band %dx64" % W, W, 64, 8)
    run("band %dx128" % W, W, 128, 6)
    run("band %dx256" % W, W, 256, 5)
    run("half %dx%d" % (W, H // 2), W, H // 2, 3)
    run("FULL %dx%d" % (W, H), W, H, 3)
    # small drag-sized rectangles
    run("win 400x300", min(400, W), 300, 6)
    run("win 200x150", min(200, W), 150, 8)
    run("strip %dx40" % W, W, 40, 8)


if __name__ == "__main__":
    bench(sys.argv[1] if len(sys.argv) > 1 else "bench")
