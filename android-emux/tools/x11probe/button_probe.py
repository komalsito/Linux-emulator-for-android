#!/usr/bin/env python3
"""button_probe.py - the wire-level twin of X11Client.probeButtonDelivery().

Creates a REAL window (so the window manager manages and frames it exactly like
an application), maps it, aims the pointer at its centre, injects
ButtonPress/ButtonRelease through XTEST, and reports whether the ButtonPress came
back on our OWN connection.

Why this exists next to the Kotlin: the button probe is the v0.2.78 fix for "the
desktop is visible but I cannot interact with it" - a window manager holding a
passive button grab, which a motion-only proof cannot see (motion is not grabbed,
which is exactly why motion and keys were never affected). Its requests -
CreateWindow, MapWindow, QueryTree, XTEST GrabControl, SetInputFocus - are byte
layouts that no compiler checks and no unit test on a JVM can exercise. This
script runs the SAME layouts against a real Xvfb + Openbox and prints what the
server actually did, so the encoding is measured rather than assumed.

  python3 tools/x11probe/button_probe.py 6083 [--impervious] [--pointer-root]
                                          [--keep] [--button N]

Exit status: 0 = a ButtonPress was delivered, 1 = it was not, 2 = could not run.
See button_delivery_test.sh for the four-configuration matrix that uses it.
"""
import os
import socket
import struct
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from xclient import (  # noqa: E402  (the reference packet pump)
    XClient, OP_GET_GEOMETRY, KIND_BTNPRESS as _KP, KIND_BTNRELEASE as _KR,
    KIND_MOTION as _KM,
)

OP_CREATE_WINDOW = 1
OP_QUERY_TREE = 15

OP_MAP_WINDOW = 8
OP_UNMAP_WINDOW = 10
OP_DESTROY_WINDOW = 4
OP_QUERY_POINTER = 38
OP_SET_INPUT_FOCUS = 42

XTEST_MINOR_GRAB_CONTROL = 3

CW_BACK_PIXEL = 1 << 1
CW_EVENT_MASK = 1 << 11
MASK_BUTTON_PRESS = 1 << 2
MASK_BUTTON_RELEASE = 1 << 3
MASK_POINTER_MOTION = 1 << 6
MASK_EXPOSURE = 1 << 15
MASK_STRUCTURE_NOTIFY = 1 << 17

KIND_BTNPRESS, KIND_BTNRELEASE, KIND_MOTION = 4, 5, 6
EV_PRESS, EV_RELEASE, EV_MOTION, EV_MAP = 4, 5, 6, 19

PROBE_X, PROBE_Y, PROBE_SIZE, PROBE_PIXEL = 64, 64, 72, 0x00FF00


class Probe(XClient):
    """XClient plus the five requests the probe needs."""

    def new_id(self):
        self._ids = getattr(self, "_ids", 0) + 1
        # Xlib's arithmetic: id = (base + n) | (base & ~mask)
        return (self.res_base + self._ids) | (self.res_base & (~self.res_mask & 0xFFFFFFFF))

    def geometry(self, win):
        r = self.request(OP_GET_GEOMETRY, 0, struct.pack("<I", win))
        x, y = struct.unpack_from("<hh", r, 12)
        w, h = struct.unpack_from("<HH", r, 16)
        return x, y, w, h

    def parent(self, win):
        r = self.request(OP_QUERY_TREE, 0, struct.pack("<I", win))
        return struct.unpack_from("<I", r, 12)[0]          # root@8, parent@12

    def locate(self, win):
        """Root coordinates of win's own area, by accumulating ancestors' offsets.
        The last value is the number of hops, i.e. whether the WM framed it."""
        x, y, w, h = self.geometry(win)
        cur, hops = win, 0
        while hops < 8:
            p = self.parent(cur)
            if p in (0, self.root):
                break
            px, py, _, _ = self.geometry(p)
            x += px
            y += py
            cur = p
            hops += 1
        return x, y, w, h, hops

    def pointer(self):
        r = self.request(OP_QUERY_POINTER, 0, struct.pack("<I", self.root))
        x, y = struct.unpack_from("<hh", r, 16)
        return x, y, struct.unpack_from("<I", r, 12)[0]

    def grab_control(self, impervious):
        """XTEST GrabControl: minor 3, an 8-byte request whose only field is
        `impervious` (CARD8). xorg's ProcXTestGrabControl is
        REQUEST_SIZE_MATCH(xXTestGrabControlReq) and calls
        MakeClientGrabImpervious()/MakeClientGrabPervious()."""
        self.request_noreply(self.xtest_op, XTEST_MINOR_GRAB_CONTROL,
                             bytes([1 if impervious else 0, 0, 0, 0]))
        return self.sync()

    def input_focus_pointer_root(self):
        """SetInputFocus(focus=PointerRoot=1, revert-to=PointerRoot=1, CurrentTime).
        The revert-to travels in the request's minor byte."""
        self.request_noreply(OP_SET_INPUT_FOCUS, 1, struct.pack("<II", 1, 0))
        return self.sync()

    def read_events(self, deadline, want_button=None):
        """Pump packets until the deadline; count what arrived. A Reply carries a
        tail, an Error never does, and both share the 32-byte header - dispatch on
        byte 0 (see xclient.py's _pump note)."""
        press = rel = mot = 0
        got = False
        self.s.settimeout(0.2)
        while time.time() < deadline:
            try:
                h = self._recvn(32)
            except (socket.timeout, TimeoutError):
                continue
            except EOFError:
                break
            k = h[0]
            if k in (1, 35):
                ln = struct.unpack_from("<I", h, 4)[0]
                if ln:
                    self._recvn(ln * 4)
            elif k == 0:
                self.errors.append((h[1], h[10], struct.unpack_from("<H", h, 8)[0],
                                    struct.unpack_from("<I", h, 4)[0],
                                    struct.unpack_from("<H", h, 2)[0]))
            else:
                t = k & 0x7F
                if t == EV_PRESS:
                    press += 1
                    if want_button is None or h[1] == want_button:
                        got = True
                elif t == EV_RELEASE:
                    rel += 1
                elif t == EV_MOTION:
                    mot += 1
                elif t == EV_MAP:
                    pass
            if got:
                break
        return got, press, rel, mot


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    port = int(args[0]) if args else 6083
    imp = "--impervious" in sys.argv
    proot = "--pointer-root" in sys.argv
    keep = "--keep" in sys.argv
    button = 1
    if "--button" in sys.argv:
        button = int(sys.argv[sys.argv.index("--button") + 1])

    try:
        c = Probe(port=port)
    except Exception as e:
        print("CONNECT-FAILED %s" % e)
        return 2
    so = c.screens_off
    root_visual = struct.unpack_from("<I", c.setup, so + 32)[0]   # root-visual VISUALID
    root_depth = c.setup[so + 38]                                 # root-depth CARD8

    wid = c.new_id()
    mask = (MASK_BUTTON_PRESS | MASK_BUTTON_RELEASE | MASK_POINTER_MOTION |
            MASK_EXPOSURE | MASK_STRUCTURE_NOTIFY)
    body = struct.pack("<IIhhhhHHII", wid, c.root, PROBE_X, PROBE_Y,
                       PROBE_SIZE, PROBE_SIZE, 0, 1, root_visual,
                       CW_BACK_PIXEL | CW_EVENT_MASK)
    body += struct.pack("<II", PROBE_PIXEL, mask)       # values, ordered by mask bit
    try:
        c.request_noreply(OP_CREATE_WINDOW, root_depth, body)
        c.request_noreply(OP_MAP_WINDOW, 0, struct.pack("<I", wid))
    except Exception as e:
        print("CREATE-FAILED %s" % e)
        return 2

    # Let the WM see it: framing happens after MapNotify, and a framed window is
    # the case that matters (the grab, if any, is on the frame).
    c.read_events(time.time() + 0.9)
    x, y, w, h, hops = c.locate(wid)

    # Aim, and verify with QueryPointer before pressing anything: a missed aim
    # would inject a click into whatever else is on screen.
    inside = False
    for _ in range(2):
        cx, cy = x + w // 2, y + h // 2
        c.fake_input(KIND_MOTION, 0, cx, cy)
        time.sleep(0.18)
        px, py, _child = c.pointer()
        if x <= px <= x + w and y <= py <= y + h:
            cx, cy, inside = px, py, True
            break
        x, y, w, h, hops = c.locate(wid)
    if not inside:
        print("AIM-MISS rect=%d,%d %dx%d framed=%s - no click injected"
              % (x, y, w, h, hops > 0))
        if not keep:
            c.request_noreply(OP_DESTROY_WINDOW, 0, struct.pack("<I", wid))
        return 2

    extra = ""
    if imp:
        err = c.grab_control(True)
        extra += " GrabControl(impervious)=%s" % ("ok" if err is None else "ERR:%s" % (err,))
    if proot:
        err = c.input_focus_pointer_root()
        extra += " SetInputFocus(PointerRoot)=%s" % ("ok" if err is None else "ERR:%s" % (err,))

    c.errors = []
    c.fake_input(KIND_BTNPRESS, button, cx, cy)
    time.sleep(0.1)
    c.fake_input(KIND_BTNRELEASE, button, cx, cy)
    # A motion AFTER the click, so the same read window can tell "the transport
    # is dead" apart from "a grab ate the button" (the field's xev signature).
    c.fake_input(KIND_MOTION, 0, min(cx + 1, c.width - 2), cy)
    got, press, rel, mot = c.read_events(time.time() + 1.5, want_button=button)

    verdict = "DELIVERED" if got else "SWALLOWED"
    print("%s ButtonPress=%d ButtonRelease=%d MotionNotify=%d win=0x%x framed=%s aim=%d,%d%s%s"
          % (verdict, press, rel, mot, wid, hops > 0, cx, cy, extra,
             (" errors=%s" % (c.errors[:2],)) if c.errors else ""))
    if not keep:
        c.request_noreply(OP_UNMAP_WINDOW, 0, struct.pack("<I", wid))
        c.request_noreply(OP_DESTROY_WINDOW, 0, struct.pack("<I", wid))
    c.close()
    return 0 if got else 1


if __name__ == "__main__":
    sys.exit(main())
