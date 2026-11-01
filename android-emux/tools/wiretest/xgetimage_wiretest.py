#!/usr/bin/env python3
"""Wire-level replica of X11Client.grabRootArgb() against a REAL Xvfb.
Sends the exact bytes the Kotlin builds (opcode 73, 24-byte ZPixmap body),
parses the reply exactly like readPacket does, and times it. Proves or
kills the 'multi-MB GetImage reply never arrives' hypothesis from field
v0.2.71 (src=native, capage climbing, control plane fine)."""
import socket, struct, sys, time

SOCK = sys.argv[1] if len(sys.argv) > 1 else "/tmp/.X11-unix/X99"
W, H = (int(sys.argv[2]), int(sys.argv[3])) if len(sys.argv) > 3 else (1280, 694)

s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.settimeout(10)
s.connect(SOCK)
s.sendall(struct.pack("<BBHHHHH", 0x6c, 0, 11, 0, 0, 0, 0))   # exactly 12 bytes, as Kotlin does

def recv_exact(n):
    buf = b""
    while len(buf) < n:
        c = s.recv(min(65536, n - len(buf)))
        if not c:
            raise EOFError("eof at %d/%d" % (len(buf), n))
        buf += c
    return buf

pre = recv_exact(8)                      # status, pad, addl units u16, release u32
if pre[0] != 1:
    print("SETUP FAILED reason=%d" % pre[0]); sys.exit(1)
units, = struct.unpack("<H", pre[6:8])   # success reply: major@2 minor@4 length@6
rest = recv_exact(units * 4)             # absolute offset 8 onward
# absolute: ridbase@8 ridmask@12 motion@16 vendor_len@20 maxreq@22
#           nScreens@24 nFormats@25 imageOrder@26 ... vendor@36
vendor_len, maxreq = struct.unpack("<HH", rest[16:20])
n_screens, n_formats = rest[20], rest[21]
order = rest[22]
root = w = h = rdepth = None
for o in range(0, len(rest) - 40, 4):
    rw, = struct.unpack("<I", rest[o:o+4])
    ww, hh = struct.unpack("<HH", rest[o+20:o+24])
    if ww == W and hh == H and rw not in (0, 0xFFFFFFFF):
        root, w, h, rdepth = rw, ww, hh, rest[o+38]
        break
if root is None:
    print("screen struct not found"); sys.exit(1)
print("setup ok: root=0x%x %dx%d depth=%d order=%d maxreq=%d units screens=%d fmts=%d"
      % (root, w, h, rdepth, order, maxreq, n_screens, n_formats))

def getimage(width, height, x=0, y=0, timeout=25):
    body = struct.pack("<IhhHHI", root, x, y, width, height, 0xFFFFFFFF)
    req = struct.pack("<BBH", 73, 2, (4 + len(body)) // 4) + body   # format=ZPixmap in header byte 1, 6 units
    s.settimeout(timeout)
    t0 = time.time()
    s.sendall(req)
    ph = recv_exact(32)
    if ph[0] != 1:
        return None, ("ERROR code=%d res=0x%x" % (ph[1], struct.unpack("<I", ph[4:8])[0]),
                      time.time() - t0)
    units, = struct.unpack("<I", ph[4:8])
    payload = recv_exact(units * 4)
    return (ph[1], payload), time.time() - t0

r, dt = getimage(W, H)
if r is None:
    print("FULL GRAB FAILED:", dt); sys.exit(1)
depth_r, payload = r
print("full grab %dx%d: depth=%d payload=%d bytes (expect %d) in %.3fs"
      % (W, H, depth_r, len(payload), W * H * 4, dt))
print("derived stride=%d (w*4=%d)" % (len(payload) // H, W * 4))
print("first pixel bytes:", payload[0:4].hex())
for i in range(5):
    r, dt = getimage(W, H)
    print("full grab #%d: %.3fs %s" % (i + 1, dt, "ok" if r else r))
band = (H + 5) // 6
for i in range(6):
    bh = min(band, H - i * band)
    r, dt = getimage(W, bh, 0, i * band)
    print("band y=%-4d h=%-3d: %.3fs %6d bytes" % (i * band, bh, dt, len(r[1]) if r else -1))
s.close()
print("WIRETEST PASS")
