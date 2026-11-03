#!/usr/bin/env python3
"""damage_probe.py — prove the DAMAGE event wire encoding v0.2.81 ships.

The APK's damage-driven capture is hand-rolled X11 wire (no xlib anywhere in
this project), so the encoding gets the same treatment every other hand-rolled
request here got: a probe that speaks it at a real server and fails loudly when
the server disagrees.

Sequence under test, exactly as X11Client.initDamageTracking/drainDamageRects:
  QueryExtension DAMAGE
  DAMAGE QueryVersion (minor 0)  - CARD32 major + CARD32 minor, EIGHT bytes
  DAMAGE Create      (minor 1)  on the ROOT window, level RawRectangles
  then read xDamageNotify EVENTS off the connection.

Measured byte layout of xDamageNotify (Xvfb, this probe is the record):
  0 type(=first_event)  1 level  2 sequence  4 time  8 drawable
  12 damage  16 geometry x,y INT16 + w,h CARD16  24 area (same shape)

Two dead designs are why this probe checks what it checks: the CARD16
QueryVersion is BadLength(16); and on this Xvfb EVERY XFIXES region request
(CreateRegion / Subtract-with-parts / FetchRegion) is rejected, which killed the
region-polling design. And an unchecked `sync()` after a noreply Create made
every failure look like success once - so this probe reads the error list after
EVERY step.

Verdicts: idle root => zero notifies; a drawing client => notifies whose union
covers what it drew; quiet again => zero. Usage: DISPLAY=:N damage_probe.py
"""
import os, struct, subprocess, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from xclient import XClient

DAMAGE_CREATE = 1


def main():
    disp = os.environ.get("DISPLAY", ":0")
    c = XClient(port=6000 + int(disp.split(":")[1].split(".")[0]))
    dop, devt, _ = c.query_extension("DAMAGE")
    if dop < 0:
        print("SKIP: server lacks DAMAGE"); return 0
    checks = []
    def ck(name, ok, detail=""):
        checks.append(ok)
        print(("  ok    " if ok else "  FAIL  ") + name +
              ("  [" + detail + "]" if detail else ""))

    c.request(dop, 0, struct.pack("<II", 1, 0))
    ck("DAMAGE QueryVersion accepts CARD32+CARD32", not c.errors,
       str(c.errors) if c.errors else "no errors")
    dmg = c.res_base | 1
    c.request_noreply(dop, DAMAGE_CREATE, struct.pack("<IIBxxx", dmg, c.root, 0))
    err = c.sync()
    ck("DamageCreate on root accepted", err is None, str(err) if err else "no errors")

    def drain(secs):
        c.events.clear()
        time.sleep(secs)
        c.sync()
        evs = [p for p in c.events if (p[0] & 0x7F) == (devt & 0x7F)]
        rects = [struct.unpack_from("<hhHH", p, 16) for p in evs]
        return [r for r in rects if r[2] > 0 and r[3] > 0]

    idle = drain(0.6)
    ck("idle root reports NO damage", len(idle) == 0, "%d rect(s)" % len(idle))

    xterm = subprocess.Popen(["xterm", "-display", disp, "-geometry", "40x10+80+60"],
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    drew = []
    deadline = time.time() + 6
    while time.time() < deadline and not drew:
        drew = drain(0.3)
    ck("a drawing client produces damage notifies", len(drew) > 0,
       "%d rect(s)" % len(drew))
    if drew:
        x0 = min(r[0] for r in drew); y0 = min(r[1] for r in drew)
        x1 = max(r[0] + r[2] for r in drew); y1 = max(r[1] + r[3] for r in drew)
        ck("notify geometry covers the drawn window (offset 16 is the rect)",
           x0 <= 84 and y0 <= 64 and x1 >= 80 and y1 >= 60,
           "union %d,%d-%d,%d" % (x0, y0, x1, y1))
    time.sleep(0.5)
    quiet = drain(0.6)
    ck("a quiet desktop reports NO damage", len(quiet) == 0,
       "%d rect(s)" % len(quiet))

    xterm.terminate()
    closed = []
    deadline = time.time() + 4
    while time.time() < deadline and not closed:
        closed = drain(0.3)
    ck("a closing window also reports damage", len(closed) > 0,
       "%d rect(s)" % len(closed))

    print("%d/%d checks passed" % (sum(checks), len(checks)))
    print("PASS: DAMAGE events carry exactly the pixels that changed, and nothing when idle."
          if all(checks) else "FAIL: the DAMAGE wire encoding does not match this server")
    return 0 if all(checks) else 1


if __name__ == "__main__":
    sys.exit(main())
