# v0.2.78 — the click-swallow: a window manager that grabs buttons, and a proof that could not see it

**Field report.** *"The desktop is visible but I can't interact with it."* Motion
and keys were never affected. Killing Openbox made `xdotool click 1` produce
`ButtonPress=1` in `xev`. Every badge said the input path was healthy:
`proof=ok touch=11 inj=32 fail=0 ctrl=:1 cap=:1 src=native`.

That report was correct on all three counts, and the reason it survived ~25
releases of input work is that **none of those releases could measure a button**.

---

## 1. What was actually measured

`tools/x11probe/button_delivery_test.sh` runs the field experiment as a matrix:
real Xvfb, real Openbox 3.6.1, a real application window, an injected left click
through XTEST (the same path `X11Client` uses), and a check for the ButtonPress
on the application's own connection.

| | configuration | result |
|---|---|---|
| A | no window manager at all | **DELIVERED** `ButtonPress=1 MotionNotify=1` |
| B | Openbox + the distro default `/etc/xdg/openbox/rc.xml` | **DELIVERED** |
| C | Openbox + **EmuX's own rc.xml, v0.2.51…v0.2.77** | **SWALLOWED** `ButtonPress=0 MotionNotify=1` |
| D | Openbox + EmuX's rc.xml **v0.2.78** | **DELIVERED** |
| E | C with the `Frame` context deleted | **DELIVERED** |
| F | C, measured by the in-app probe | **SWALLOWED** (agrees with xev) |
| G | C + XTEST `GrabControl(impervious)` | **SWALLOWED** (request accepted — it does not help) |
| H | D, measured by the in-app probe | **DELIVERED** |

Row C is the bug, and it is **not** a stale file: it is the configuration this
project has been shipping since v0.2.51. Row E names the single fatal line.
Row G is a negative result that changed the fix (see §5).

`MotionNotify=1` with `ButtonPress=0` in row C is the whole story in two numbers:
the transport works, the server honours our fake input, the pointer moves — and a
passive button grab takes the press somewhere else.

## 2. Why Openbox, and why only buttons

Openbox turns *some* mouse bindings into `XGrabButton()` calls and handles the
rest through ordinary event selection. From its source:

```c
/* openbox/frame.h */
#define FRAME_CONTEXT(co, cl)  ((cl && cl->type != OB_CLIENT_TYPE_DESKTOP) ? \
                                 co == OB_FRAME_CONTEXT_FRAME : FALSE)
#define CLIENT_CONTEXT(co, cl) ((cl && cl->type == OB_CLIENT_TYPE_DESKTOP) ? \
                                 co == OB_FRAME_CONTEXT_DESKTOP : \
                                 co == OB_FRAME_CONTEXT_CLIENT)

/* openbox/mouse.c  mouse_grab_for_client() */
if (FRAME_CONTEXT(i, client)) {
    win  = client->frame->window;   mode = GrabModeAsync;
    mask = ButtonPressMask | ButtonMotionMask | ButtonReleaseMask;
} else if (CLIENT_CONTEXT(i, client)) {
    win  = client->window;          mode = GrabModeSync;
    mask = ButtonPressMask;
} else continue;
grab_button_full(b->button, b->state, win, mask, mode, OB_CURSOR_NONE);

/* openbox/grab.c  grab_button_full() */
XGrabButton(dpy, button, state|mods, win, False /* owner_events */, ...);
```

So exactly three contexts produce a grab — `Frame` (on the **frame window**),
`Client` and `Desktop` (on the **client window**, `GrabModeSync`). Every other
context (`Titlebar`, `Top/Bottom/Left/Right`, corners, `Close`, `Maximize`,
`Iconify`, …) is dispatched from Openbox's own selection on the frame window by
coordinate, and `Root` from its root-window selection
(`screen.c ROOT_EVENTMASK` includes `ButtonPressMask`). **Neither creates a
grab, so neither can steal an event.**

Two properties of the `Frame` grab make it fatal:

1. **The frame window is the *parent* of the client window**, and xorg checks
   passive grabs on every window from the root down to the one under the pointer:

   ```c
   /* xorg dix/events.c  CheckDeviceGrabs() */
   for (; i < device->spriteInfo->sprite->spriteTraceGood; i++) {
       pWin = device->spriteInfo->sprite->spriteTrace[i];
       if (CheckPassiveGrabsOnWindow(pWin, device, ievent, sendCore, TRUE)) {
           ret = TRUE; goto out;          /* the event now belongs to the grabber */
       }
   }
   ```
   With `owner_events = False`, the application never sees the press.

2. **Openbox only replays what it grabbed for a *client* context.** In
   `mouse.c`, `replay_pointer_needed` is set inside
   `if (CLIENT_CONTEXT(context, client))`, and `mouse_replay_pointer()` is what
   calls `XAllowEvents(ReplayPointer, …)`. A `Frame`-context grab therefore
   matches the click, fires `Focus`, and gives nothing back.

   This is also why row B survives: the distro default binds `Frame` **only with
   modifiers** (`A-Left`, `A-Right`, `A-Middle`, `C-A-Up`, …), which a plain
   click does not match — and its `Client`/`Desktop` binds are `GrabModeSync`
   *with* the replay path, so they do hand the press back. Openbox's own default
   is careful here; EmuX's hand-written one was not.

And why motion and keys were never affected: the masks are `ButtonPressMask`
(+ `ButtonMotionMask` *during* a press) — plain `MotionNotify` and all keyboard
events are never grabbed. A motion-only proof is structurally incapable of seeing
this.

**Scope, precisely.** `FRAME_CONTEXT` is false for desktop-type clients, so the
grab never applies to `pcmanfm --desktop`. Clicks on the wallpaper and on desktop
icons worked; clicks **inside any framed application window** — the terminal, the
editor, the calculator, every dialog — did not. That is exactly the confusing
field history: v0.2.66 "fixed" double-click and icons started opening things,
but the windows they opened were dead on arrival.

## 3. Why upgrades never helped

Two independent reasons, both real:

1. **The shipped file was the broken one.** Row C above is byte-for-byte the
   rc.xml that `seed_desktop_config` has written since v0.2.51 (`git show
   HEAD:…/emux-x11`, the `<context name="Frame">` block). There was no good
   version to upgrade *to*.
2. **`if [ ! -e rc.xml ]` — write once, never again.** Even a correct file could
   never reach a device that already had one: an older EmuX file, one dropped by
   `obconf`, or a distro default copied into `$HOME`. And when the write *did*
   fail (unwritable `HOME`), Openbox silently fell back to
   `/etc/xdg/openbox/rc.xml` and nothing anywhere reported which file was in
   force.

## 4. The fix

**Guest (`assets/bin/emux-x11`)**

- `write_rc_xml()` — the file now carries `EMUX_RCXML_STAMP="EmuX rc v0.2.78"`
  and is rewritten whenever the stamp does not match. A *foreign* file is backed
  up to `rc.xml.emux-bak` once (an older EmuX stamp is replaced in place so a
  user's backup is never clobbered — the same rule `autostart` already used).
  Recognising "our own older file" needs **two** tests, not one: v0.2.78 files
  carry the stamp, but v0.2.51…v0.2.77 files carry **no stamp at all** — they
  are identifiable only by `<theme><name>Emux</name>`. Matching on the stamp
  alone made every upgrading device back up our own broken config and print
  "we replaced a foreign rc.xml", which is wrong, and then never say it again
  because a backup now existed. `ladder_test.sh` pins both branches.
  Written via `tmp` + `mv`, never truncated in place. If it cannot be written,
  that is said out loud, because the consequence is now known.
- The new rc.xml binds **no `Frame`, `Client` or `Desktop` context** — with the
  reasoning and the source citations inline, so the next person who wants to add
  `<action name="Focus"/>` back reads why they must not. Focus still follows the
  finger via `<focus><followMouse>yes</followMouse>` (Openbox focuses on
  `EnterNotify`, `config_focus_follow` in `event.c`), and EmuX injects a motion
  before every press, so focus arrives first. Titlebar move/resize/close/max/
  iconify are kept: those contexts produce no grab.
- `rc_xml_effective()` + `rc_xml_grab_contexts()` — audit whichever file Openbox
  will *actually* read, and publish it to `/tmp/emux_rcxml_file` and
  `/tmp/emux_rcxml_grabs`. If the effective file is the distro default and it is
  writable, the three fatal contexts are stripped from it in place (with a
  backup), because that file is what Openbox reads whenever ours is missing.
- `btn_watch()` + `wm_fallback()` — the escalation ladder's guest half (§6).
- `emux-x11 doctor` gained a **`clicks (button delivery)`** section: WM mode,
  whether the fallback fired, which rc.xml is in force, its stamp, its grab
  contexts, the app's verdict and what the ladder did about it. Still read-only.

**App**

- `X11Client.probeButtonDelivery()` — the `xev` experiment in-app: `CreateWindow`
  with the screen's own visual and depth (v0.2.74 made the desktop depth 16, so
  neither is assumed), `MapWindow`, wait for the WM to frame it, walk
  `QueryTree`/`GetGeometry` up to the root for real coordinates, aim, inject
  `ButtonPress`/`ButtonRelease` through XTEST, and read **our own connection** for
  the press. A motion is injected *after* the click so a failure is classifiable:
  `motion=N` arriving with `ButtonPress=0` is a grab, `motion=0` is a dead
  transport. No button event is injected unless `QueryPointer` first confirms the
  pointer is inside the probe window — a missed aim would click the user's
  terminal. The window is 72×72, bright green, and destroyed within ~1.5 s.
- `DesktopService.runInputProof()` now has two legs and a ladder; `inputProof`
  reads `motion-ok` while the button leg runs, `ok` only when both pass, and
  `motion-only` when buttons are swallowed. New pane fields `btn=` and `wm=`.
- **The badge no longer stays green while clicks are dead**: `buttonDelivered ==
  false` turns the input line red and reads `INPUT: buttons SWALLOWED (motion
  ok)`. This is audit item 6 of v0.2.66 one level deeper — there, a
  connect-time flag lied; here, a *true* transport fact lied by omission.
- The guest `xdotool` agent is deliberately **not** used as a fallback for this
  failure. It injects through the same XTEST extension, so the same grab swallows
  it identically. Saying "input fell back to the guest agent" here would be a
  lie that costs another release cycle; that fallback remains for servers that
  ignore `FakeInput` entirely.

## 5. What was tried and did **not** work

`XTEST GrabControl(impervious)` (minor opcode 3 → `MakeClientGrabImpervious`)
is the documented way for a client's fake input to ignore another client's grab,
and it is the obvious one-request fix. **Measured: it does not help here.** Case
G above sends it, the server accepts it without error, and the click is still
swallowed. The primitive is kept in `X11Client.xtestGrabControl()` and in
`button_probe.py --impervious` — it remains the right tool for an *active* grab,
and the negative result is recorded so nobody re-ships it as a fix. It is also
not free: while impervious, a window manager mid-drag stops receiving our motion,
so titlebar dragging degrades.

The ladder therefore escalates to what case A proves works: **remove the
grabber**.

## 6. The ladder

Ordered cheapest-and-most-correct first, driven by the app's measurement and
executed by whichever side can act on it:

| stage | who | action | evidence |
|---|---|---|---|
| 0 | app | probe as-is | case C/F: `SWALLOWED` |
| 1 | guest | rewrite the stamped rc.xml, `openbox --reconfigure` | case D: `DELIVERED` |
| 2 | guest | switch WM (`twm`/`icewm`/`jwm`/`marco`/`metacity`/`fluxbox`/`xfwm4` if installed), else run with none | case A: `DELIVERED` |

Protocol: the app writes `<epoch> <state> <detail>` to `<rootfs>/tmp/emux_btn_verdict`
(`dead` / `ok` / `recovered`); `btn_watch()` in the launcher polls it every 2 s,
acts, records what it did in `/tmp/emux_btn_action`, republishes
`/tmp/emux_wm_mode`, and **deletes the verdict** so the app re-probes and either
reports recovery or escalates. Stage counting lives in `/tmp/emux_btn_stages`;
`wm_fallback` runs at most once per session. All of these markers are cleared at
launcher start — a stale `dead` verdict would otherwise fire the ladder before
anything had been measured, and a stale `emux_wm_fellback` would forbid the one
action known to restore clicks.

"No window manager" is a real fallback, not a defeat: case A measures that with
no WM at all an injected click reaches the application, and every window is
still shown — unmanaged, so without titlebars or stacking order.

What is **not** claimed is keyboard focus. With no WM nothing moves focus when a
window is clicked, and `SetInputFocus(PointerRoot)` is not the fix for that:
Xvfb's input focus already reports PointerRoot when no WM is running
(`XGetInputFocus` returns 1 — the value xdotool complains about as "the focused
window of 1"), and libX11 defines PointerRoot as "the root window of whatever
screen the pointer is on at each keyboard event", which on a single-screen
display is the root, not the window under the finger. `X11Client` keeps
`setInputFocusPointerRoot()` as a documented primitive that nothing calls, and
`emux-x11 doctor` prints `WM mode: none`, so this rung is visible in the field
rather than guessed at. A desktop that takes every click still beats one that
takes none.

## 7. Also fixed: stray Xvfb on `:0`

Reported alongside the input bug: the launcher could start Xvfb servers on `:0`
before settling on `:1`. Two causes, both fixed.

- **Host side.** The display was picked with a *bind* test
  (`ServerSocket(6000+n)`), which is dishonest in two directions: a bind failure
  is not evidence of occupation (an SELinux denial on `xserver_port` makes every
  candidate look taken, `firstOrNull` returns null, and `?: 0` silently sends the
  session to `:0`), and a stale `X$n` **socket file** whose server died looks
  occupied forever, pushing the session to `:1` while the guest ladder starts
  something else on the way. v0.2.17 had already established that *connect* is
  the honest test — it just had never been applied to the pick. It now uses
  `portAnswering()` plus a new `unixAnswering()`.
- **Guest side.** `start_xvfb()` never retired its own previous attempt: the
  v0.2.13 "adopt, don't fight" fix removed the previous-attempt kill because it
  had murdered a *serving* server, but it was implemented as "never kill
  anything", so every ladder step left a process behind. `retire_own_xvfb()` now
  kills our own pid, and only when it is provably not serving — no unix socket
  newer than the session marker *and* nothing answering on its tcp port, which is
  the same evidence `verify_x` trusts. Adoption is untouched: a verified server
  is still never killed and never replaced.

## 8. What the field should show now

Healthy: `INPUT: XTEST (control plane)  btn=pervious wm=openbox` in green, and
`proof=ok touch=… inj=…`.

If a device is still broken, the pane now says which stage it reached, and
`emux-x11 doctor` prints the file Openbox is reading, its stamp, its grab
contexts, and what the ladder did. `btn=DEAD` in red with
`proof=motion-only` is the signature this document explains.

## 9. Reproducing

```sh
sh tools/x11probe/button_delivery_test.sh   # the 8-row matrix, ~40 s
sh tools/x11probe/ladder_test.sh            # the fix itself, 46 checks, ~22 s
python3 tools/x11probe/button_probe.py 6087 # one probe against a running display
```

`button_delivery_test.sh` reproduces the **bug**: real Xvfb, real Openbox, the
v0.2.77 config as a checked-in fixture, and both instruments (`xev` and the
probe) so the app's measurement can be compared against an independent one.

`ladder_test.sh` tests the **fix**, and it extracts its functions out of
`assets/bin/emux-x11` by marker rather than copying them, so editing the launcher
cannot leave the test green against stale logic. Its middle suite is the whole
argument in one run: bring up a live Openbox session with the broken config,
confirm the click is swallowed, publish the app's `dead` verdict, and confirm
that stage 1 (`rc.xml` rewrite + `openbox --reconfigure`) makes a real
`ButtonPress` arrive **in that running session, with no restart and no lost
windows** — then break it again and confirm stage 2 does too. The third suite
covers `retire_own_xvfb` both ways: an alive-but-not-serving attempt is retired,
a serving one is kept, because v0.2.13 removed the kill for a good reason and
this must not resurrect that bug.

`button_probe.py` is the wire-level twin of `X11Client.probeButtonDelivery()`:
same requests, same byte offsets, run against a real server. It exists because
`CreateWindow`, `QueryTree`, `GrabControl` and `SetInputFocus` are layouts no
compiler checks and no JVM unit test can exercise — the same argument
`tools/x11probe/xclient.py` was written for. `rc_v0.2.77.xml` is the broken
configuration, kept as a fixture so the regression test outlives the fix.
