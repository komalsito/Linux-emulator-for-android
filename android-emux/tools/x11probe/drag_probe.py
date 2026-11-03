#!/usr/bin/env python3
"""drag_probe.py - prove that a left-drag IS a drag, at the wire and on screen.

The v0.2.79 field report was "I can't drag items inside the desktop (I am able
to click and it works)". The cause was in the gesture, not the transport: the
drag path sent MotionNotify ONLY - injectMotion() carries no button - and then
sent a ButtonRelease(1) on finger-lift for a button that had never been pressed.
The server saw a hover plus a stray release, which no toolkit can read as a drag.

Two different things have to be true for a drag to work, so this measures both,
each against a real Xvfb + Openbox and a window the WM frames like an app's:

  PHASE A - the wire, inside the CLIENT window (what an application sees when
  you drag an icon, a slider, a scrollbar, a selection):
      OLD  motion, motion, ..., release(no press)
      NEW  press, motion, motion, ..., release
  A drag is MotionNotify *with Button1Mask set in the event's state field*. That
  mask comes from the server's own record of which buttons are down, so it is
  exactly what a motion-only gesture can never produce.

  PHASE B - the window manager, by the TITLEBAR (what moving a window needs):
  the shipped rc.xml binds Titlebar/Left/Drag -> Move, so a real drag there must
  physically relocate the frame. Note what the client sees here: NOTHING. The
  press lands on the frame window (Openbox's), and Openbox then takes an active
  pointer grab for the interactive move, so the events belong to the grabber.
  Phase B is therefore judged by where the window ends up, not by events.

  python3 tools/x11probe/drag_probe.py 6089 [--steps N] [--dx N] [--dy N] [--keep]

Exit status: 0 = every expectation met (the fix works and the old behaviour is
reproduced), 1 = something did not hold, 2 = could not run.
"""
import os
import socket
import struct
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from button_probe import (  # noqa: E402  same wire layouts, one source of truth
    Probe, OP_CREATE_WINDOW, OP_MAP_WINDOW, OP_UNMAP_WINDOW, OP_DESTROY_WINDOW,
    CW_BACK_PIXEL, CW_EVENT_MASK, MASK_BUTTON_PRESS, MASK_BUTTON_RELEASE,
    MASK_POINTER_MOTION, MASK_EXPOSURE, MASK_STRUCTURE_NOTIFY,
    KIND_BTNPRESS, KIND_BTNRELEASE, KIND_MOTION, EV_PRESS, EV_RELEASE, EV_MOTION,
)

BUTTON1_MASK = 1 << 8          # X's Button1Mask, in an event's `state` field
STATE_OFFSET = 28              # xEvent: state is a CARD16 at byte 28

# A window big enough to have an obvious titlebar and room to be dragged.
WIN_X, WIN_Y, WIN_W, WIN_H, WIN_PIXEL = 120, 120, 320, 220, 0x2266AA


class DragProbe(Probe):
    """Probe plus an event reader that keeps each event's state mask."""

    def read_seq(self, deadline):
        """Pump until the deadline; return [(kind, detail, state), ...] for
        pointer events. Same packet pump as button_probe.read_events - a Reply
        carries a tail and an Error does not, so dispatch on byte 0."""
        out = []
        self.s.settimeout(0.2)
        while time.time() < deadline:
            try:
                h = self._recvn(32)
            except (socket.timeout, TimeoutError):
                continue
            except EOFError:
                break
            k = h[0]
            if k in (1, 35):                 # Reply / GenericEvent: has a tail
                extra = struct.unpack_from("<I", h, 4)[0] * 4
                if extra:
                    try:
                        self._recvn(extra)
                    except EOFError:
                        break
                continue
            if k in (EV_PRESS, EV_RELEASE, EV_MOTION):
                out.append((k, h[1], struct.unpack_from("<H", h, STATE_OFFSET)[0]))
        return out

    def frame_of(self, win):
        """The WM's frame window for `win` (its parent), or None if unframed."""
        p = self.parent(win)
        return p if p not in (0, self.root) else None

    def root_pos(self, win):
        """Root coordinates of a window, by accumulating ancestor offsets."""
        x, y, _, _ = self.geometry(win)
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
        return x, y


def seq_name(kind):
    return {EV_PRESS: "ButtonPress", EV_RELEASE: "ButtonRelease",
            EV_MOTION: "MotionNotify"}.get(kind, "ev%d" % kind)


def summarise(ev):
    """Compact form: ButtonPress(1) MotionNotify(0)[b1]x7 ButtonRelease(1)"""
    out, i = [], 0
    while i < len(ev):
        k, d, st = ev[i]
        n = 1
        while i + n < len(ev) and ev[i + n][0] == k and ev[i + n][1] == d:
            n += 1
        tag = "[b1]" if k == EV_MOTION and (st & BUTTON1_MASK) else ""
        out.append("%s(%d)%s%s" % (seq_name(k), d, tag, ("x%d" % n) if n > 1 else ""))
        i += n
    return " ".join(out) if out else "(no pointer events reached this window)"


def run_sequence(c, at, steps, dx, dy, press_first):
    """Inject one drag at root coordinates `at`; return the events our own
    connection saw. Both variants end with a release - that is what the old code
    did, and releasing a button that is not down is precisely the stray event
    that made the old gesture unreadable to a toolkit."""
    x, y = at
    c.fake_input(KIND_MOTION, 0, x, y)
    time.sleep(0.15)
    if press_first:
        c.fake_input(KIND_BTNPRESS, 1, x, y)
        time.sleep(0.05)
    for i in range(1, steps + 1):
        c.fake_input(KIND_MOTION, 0, x + dx * i // steps, y + dy * i // steps)
        time.sleep(0.03)
    c.fake_input(KIND_BTNRELEASE, 1, x + dx, y + dy)
    return c.read_seq(time.time() + 0.9)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    port = int(args[0]) if args else 6089
    keep = "--keep" in sys.argv

    def opt(name, default):
        return int(sys.argv[sys.argv.index(name) + 1]) if name in sys.argv else default

    steps, dx, dy = opt("--steps", 8), opt("--dx", 150), opt("--dy", 100)

    try:
        c = DragProbe(port=port)
    except Exception as e:
        print("CONNECT-FAILED %s" % e)
        return 2

    so = c.screens_off
    root_visual = struct.unpack_from("<I", c.setup, so + 32)[0]
    root_depth = c.setup[so + 38]

    wid = c.new_id()
    mask = (MASK_BUTTON_PRESS | MASK_BUTTON_RELEASE | MASK_POINTER_MOTION |
            MASK_EXPOSURE | MASK_STRUCTURE_NOTIFY)
    body = struct.pack("<IIhhhhHHII", wid, c.root, WIN_X, WIN_Y, WIN_W, WIN_H,
                       0, 1, root_visual, CW_BACK_PIXEL | CW_EVENT_MASK)
    body += struct.pack("<II", WIN_PIXEL, mask)
    try:
        c.request_noreply(OP_CREATE_WINDOW, root_depth, body)
        c.request_noreply(OP_MAP_WINDOW, 0, struct.pack("<I", wid))
    except Exception as e:
        print("CREATE-FAILED %s" % e)
        return 2

    # Give the WM time to frame it. An UNFRAMED window has no titlebar, so phase
    # B would be meaningless - say so rather than report a false failure.
    c.read_seq(time.time() + 1.2)
    frame = c.frame_of(wid)
    if not frame:
        print("NOT-FRAMED win=0x%x - no window manager is managing this window, so "
              "there is no titlebar to drag (is Openbox running?)" % wid)
        if not keep:
            c.request_noreply(OP_DESTROY_WINDOW, 0, struct.pack("<I", wid))
        return 2

    # The titlebar is the strip of frame ABOVE the client: the client's y offset
    # inside its parent is exactly the titlebar height.
    cx_off, cy_off, _, _ = c.geometry(wid)
    title_h = max(2, cy_off)
    print("window 0x%x framed by 0x%x, titlebar %dpx, %dx%d client"
          % (wid, frame, title_h, WIN_W, WIN_H))

    checks = []          # (label, passed, detail)

    # ---------------- PHASE A: the wire, inside the client window -----------
    print("\nPHASE A - events the CLIENT window receives (icon/slider/selection drags)")
    fx, fy = c.root_pos(frame)
    centre = (fx + cx_off + WIN_W // 2, fy + cy_off + WIN_H // 2)
    a_old = run_sequence(c, centre, steps, dx, dy, press_first=False)
    a_new = run_sequence(c, centre, steps, dx, dy, press_first=True)

    def has(ev, kind):
        return any(k == kind for k, _d, _s in ev)

    def mot_b1(ev):
        return sum(1 for k, _d, st in ev if k == EV_MOTION and (st & BUTTON1_MASK))

    def mot_any(ev):
        return sum(1 for k, _d, _s in ev if k == EV_MOTION)

    print("  OLD  motion-only + unreferenced release: %s" % summarise(a_old))
    print("  NEW  press, motion, release:             %s" % summarise(a_new))
    checks.append(("A/OLD has no ButtonPress (nothing to drag with)",
                   not has(a_old, EV_PRESS), "%d press(es)" % sum(1 for k, _d, _s in a_old if k == EV_PRESS)))
    checks.append(("A/OLD motions carry NO Button1Mask (a hover, not a drag)",
                   mot_any(a_old) > 0 and mot_b1(a_old) == 0,
                   "%d motions, %d with b1" % (mot_any(a_old), mot_b1(a_old))))
    checks.append(("A/NEW delivers ButtonPress(1)", has(a_new, EV_PRESS), ""))
    checks.append(("A/NEW motions carry Button1Mask (THIS is a drag)",
                   mot_b1(a_new) > 0, "%d of %d motions" % (mot_b1(a_new), mot_any(a_new))))
    checks.append(("A/NEW delivers ButtonRelease(1)", has(a_new, EV_RELEASE), ""))

    # ---------------- PHASE B: does a window actually MOVE? -----------------
    print("\nPHASE B - dragging the TITLEBAR (rc.xml binds Titlebar/Left/Drag -> Move)")
    results = {}
    for label, press_first in (("OLD", False), ("NEW", True)):
        fx, fy = c.root_pos(frame)
        before = (fx, fy)
        grab = (fx + 40, fy + title_h // 2)
        ev = run_sequence(c, grab, steps, dx, dy, press_first)
        after = c.root_pos(frame)
        results[label] = (before, after, ev)
        print("  %s  frame %d,%d -> %d,%d  moved=(%+d,%+d)   client saw: %s"
              % (label, before[0], before[1], after[0], after[1],
                 after[0] - before[0], after[1] - before[1], summarise(ev)))

    for label, expect_move in (("OLD", False), ("NEW", True)):
        before, after, _ = results[label]
        moved = abs(after[0] - before[0]) > 8 or abs(after[1] - before[1]) > 8
        if expect_move:
            checks.append(("B/NEW the window MOVED with the finger",
                           moved, "moved %+d,%+d (asked %+d,%+d)"
                           % (after[0] - before[0], after[1] - before[1], dx, dy)))
        else:
            checks.append(("B/OLD the window did NOT move (reproduces the report)",
                           not moved, "moved %+d,%+d"
                           % (after[0] - before[0], after[1] - before[1])))

    if c.errors:
        print("\n  X protocol errors: %s" % (c.errors[:3],))

    if not keep:
        c.request_noreply(OP_UNMAP_WINDOW, 0, struct.pack("<I", wid))
        c.request_noreply(OP_DESTROY_WINDOW, 0, struct.pack("<I", wid))
    c.close()

    print()
    bad = 0
    for label, ok, detail in checks:
        print("  %-4s %s%s" % ("ok" if ok else "FAIL", label,
                               ("  [%s]" % detail) if detail else ""))
        bad += 0 if ok else 1
    print("\n%d/%d checks passed" % (len(checks) - bad, len(checks)))
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
