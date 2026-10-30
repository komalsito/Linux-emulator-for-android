# v0.2.59 — the desktop was never interactive, and why

Field report this round answers:

1. *"Openbox doesn't work well — I can't see the desktop."*
2. *"Sometimes I get a signal 11 error when I open the desktop and go back to the terminal."*
3. *"The desktop has never been interactive — no input from touch, mouse or keyboard."*

Every cause below was **measured on a live `Xvfb 1.21` + `Openbox 3.6.1` +
`xdotool` stack**, not inferred from reading code. The harnesses are in
[`tools/x11probe/`](../tools/x11probe/README.md) and each one reproduces the
failure and the fix side by side.

The short version: **no Openbox configuration could ever have fixed (3) or (2).**
Input has to travel through the app's own X11 client and the guest FIFO agent,
and both were broken independently of Openbox. (1) *was* an Openbox launch
problem, and the official documentation says so explicitly.

---

## 1. "I can't see the desktop" — bare `openbox` never runs autostart

From [openbox.org/help/Autostart](http://openbox.org/help/Autostart), verbatim:

> When you run the `openbox` command on its own, the autostart scripts will
> **not** run. They are run by `openbox-session` or when you log in graphically
> with the Openbox session type.

Every EmuX release up to v0.2.58 launched bare `openbox`. The seeded
`~/.config/openbox/autostart` (root colour, tint2 panel, pcmanfm desktop icons)
and `~/.config/openbox/environment` therefore **never executed**. The root
window stayed pure black, and the panel appeared only if the 5-second watchdog
happened to launch it — which is why field reports oscillated between "a
terminal", "a black screen" and "sometimes a panel".

`tools/x11probe/desktop_visible_ab.sh` measures it:

| | autostart ran? | tint2 | root window (`xwd` census) |
|---|---|---|---|
| **A** — bare `openbox` (v0.2.58) | **NO** (marker file never written) | absent | **100.0% pure black, 1 distinct colour** |
| **B** — `openbox --sm-disable --startup …` (v0.2.59) | **YES** | running | **97.7% `#20344d`, 102 distinct colours** |

### The fix

`start_wm()` in `assets/bin/emux-x11` now does what `openbox-session` does,
without depending on it:

```sh
# source the environment scripts first, exactly as openbox-session does
for E in /etc/xdg/openbox/environment "$HOME/.config/openbox/environment"; do
  [ -r "$E" ] && . "$E" 2>/dev/null
done
openbox --sm-disable --startup "/bin/sh $HOME/.config/openbox/autostart"
```

`openbox-session` itself ends in `exec /usr/bin/openbox --startup
"<libdir>/openbox-autostart OPENBOX"`, but it depends on an arch-specific
libdir and on PyXDG (which logs `openbox-xdg-autostart requires PyXDG`), so the
`--startup` mechanism is driven directly. `--sm-disable` skips the
session-manager connection; there is no SM here. All four WM launch sites in the
script now go through this one function.

Two supporting changes:

* **`paint_root()`** — an unconditional `xsetroot -solid '#20344d'` before any
  client starts. The old path relied on `pcmanfm --desktop` painting a
  wallpaper; when pcmanfm wedged (a known proot failure mode, already guarded by
  a watchdog) the root stayed black and "no desktop" was indistinguishable from
  "no frames". A black pane is now unambiguous.
* **picom is opt-in** (`touch /tmp/emux_want_picom`). A compositing manager
  redirects every client to an offscreen pixmap and re-composites onto the root
  — one extra full-screen render per frame to buy translucency that a ~10 fps
  `xwd` screenshot stream cannot display smoothly anyway. It is also a large
  long-lived process, i.e. a prime low-memory-killer target (see §2).
* **The autostart version gate was self-defeating**: it grepped for
  `EmuX desktop v0.2.45` while writing `EmuX desktop v0.2.54`, so the stamp
  never matched and the block was appended again on every run. Harmless while
  nothing executed the file — but it would have launched two panels and two icon
  layers the moment the `--startup` fix landed. Now stamped `v0.2.59`, rewritten
  in place for any EmuX-stamped file, and a user's own autostart is backed up to
  `autostart.emux-bak` once and never clobbered. Verified idempotent over 4 runs
  (1 tint2, 1 pcmanfm, 1 stamp).

---

## 2. "signal 11 when I go back to the terminal" — the app killed its own ptrace tracer

`killDesktopGuests()` builds a cleanup sweep as `proot … /bin/sh -c '<payload>'`
where the payload contained the literal match patterns:

```sh
case $c in *Xvfb*|*Xorg*|*emux-capture*|*xwd*) … kill -9 $p ;; esac
```

**proot's own argv contains that payload string**, and proot binds the host
`/proc`. So the loop walked straight into the tracer's cmdline, matched
`*Xvfb*`, and `kill -9`'d it. The `[ $p != $$ ]` guard excluded the shell, not
its proot parent. Killing a ptrace tracer is precisely how a tracee ends up
reporting `proot warning: signal 11` — the user's exact symptom — and it also
aborted the sweep before the X lock cleanup, leaving stale `/tmp/.X0-lock` files
that make the *next* session adopt a dead display ("can't see the desktop").

`tools/x11probe/sweep_test.sh` reproduces both halves with a wrapper process
whose argv contains the payload, exactly like proot:

| payload | intended victim killed? | tracer (wrapper) |
|---|---|---|
| old — patterns in argv | yes | **KILLED ← the bug** |
| new — patterns in env | yes | **survives** |

### The fix

Patterns now travel in the **environment** (`EMUX_SW1..EMUX_SW4`), which never
appears in any cmdline, and the shell skips itself *and* its parent, parsed from
`/proc/self/stat` (`$PPID` is not reliable in POSIX sh/dash):

```sh
PP=$(sed 's/.*) //' /proc/self/stat | cut -d' ' -f2)
```

A short pattern list degrades to an **unmatchable sentinel**, never to an empty
string: `*""*` matches every cmdline, which would `kill -9` the whole uid. The
harness confirms the empty pattern really does match an innocent process.

### The contributing cause: the v0.2.47 LMK bias never worked

The v0.2.47 comment claimed the terminal's proot was protected with
`oom_score_adj = -300`. Two problems, both measured:

1. **Writing a negative `oom_score_adj` requires `CAP_SYS_RESOURCE`**, which an
   unprivileged app does not have. The `-300` was silently swallowed by
   `runCatching` while the `+100` on the desktop guests succeeded. Net effect
   was the *opposite* of the intent.
2. **`if (!cmd.contains("proot")) return@forEach` skipped every real memory
   hog.** Xvfb, Openbox, tint2 and pcmanfm are proot *tracees* whose own cmdline
   contains no "proot", so the processes actually holding the framebuffer were
   never adjusted at all.

The terminal cannot be protected from userspace. What the code does now is make
the desktop stack the preferred sacrifice (950/900/850/800 by role), **verify
each write by reading it back**, and report the truth on the pane so a field log
says "bias DENIED, needs root" instead of implying success. Reducing pressure is
the only real lever available, which is why picom became opt-in.

---

## 3. "The desktop has never been interactive" — three independent breaks

### 3a. XTEST was dead from wire-protocol bugs in `X11Client`, not from the ROM

The v0.2.53 note concluded the host X11 client "never completed a handshake on
this ROM (52 rounds)" and fell back to the guest agent. The handshake was
failing because of protocol errors in the client:

| bug | effect |
|---|---|
| `QueryExtension` sent as opcode **15** | 15 is `QueryTree`; the real opcode is **98** → `BadLength` |
| setup reply parsed big-endian | root window decoded as 64421888 instead of `0x50d` |
| wrong screen-walk / reply offsets | geometry and root window wrong |
| `GetProperty` missing its window field | every property read failed |
| `XTestFakeInput` with a 24-byte body | server only accepts a **36-byte** request (`type/detail/time/rootX/rootY/root/deviceid`); 28 and 32 are both rejected `BadLength` |
| no socket timeout | a lost setup reply hung `connectControlPlane()` forever |
| `GrabServer` used as the sync round-trip | replaced with `GetInputFocus` (43) |

**Plus two more found this round by the audit harness itself** (§4): `internAtom`
and `getProperty` both wrote a BOOL (`only-if-exists`, `delete-after`) into the
request *body* instead of the header's second byte, inflating each request by 4
bytes. The server answered `BadLength` for **every** atom — even `WM_NAME` — so
`internAtom()` always returned 0 and `isWindowManagerRunning()` reported false
while Openbox was running. This is the same shape `queryExtension()` already got
right, which is what made it easy to miss.

Keysym→keycode now comes from the server's own `GetKeyboardMapping`, so the
shift level is always correct and layout differences cannot matter.

### 3b. The FIFO agent typed nothing: keysyms were being passed as keycodes

The host sent `K <keysym-integer>` and the agent ran `xdotool keydown <integer>`.
**xdotool treats a bare number as a KEYCODE, not a keysym.** Measured on a live
server: `xdotool keydown 97` injects keycode 97, while keysym 97 (`a`) is
keycode 38 — and `xdotool keydown a` correctly injects 38. So every character
went to the wrong key, or nowhere at all for the `>0xFF` Unicode keysym range
whose decimal form is far outside the 8..255 keycode window.

The protocol is now text-based, which xdotool resolves through
`XStringToKeysym` and the live keymap:

```
M <x> <y>   pointer move          D <button> / U <button>   press / release
T <text>    literal text  -> xdotool type --clearmodifiers -- <text>
N <name>    X11 key name  -> xdotool key  --clearmodifiers -- <name>
K <keysym>  legacy; translated to a name or ASCII char in the agent, never passed through
```

Two details that were measured, not assumed:

* The old `read -r t a b c` split the payload on spaces, so any text containing
  a space lost everything after the second field. The agent now reads the whole
  line and strips only the command token.
* `N` must pass its argument **unquoted**. `xdotool key` takes one key name per
  argument, so `"BackSpace BackSpace"` as a single quoted string is not a valid
  key name and xdotool exits 2. The harness asserts a 4×BackSpace edit sequence
  end to end.

`tools/x11probe/agent_test.sh` runs the agent **as embedded in `emux-x11`**
(extracted and unescaped by `extract_agent.py`) against a live xterm and asserts
what the xterm's shell received: `hello agent`, `xdotool works`, the
BackSpace-corrected `XY`, and the legacy `K 65293` path — 4/4.

### 3c. Input could block the UI thread, and events vanished into an unlinked FIFO

* `inputLine()` opened and wrote the FIFO **on the UI thread** from
  `onTouchEvent`. `open(2)` on a FIFO blocks until a reader appears and
  `write(2)` blocks once the pipe fills — an ANR that reads to the user as "the
  desktop ignores my finger". Writes now go to a daemon thread.
* The agent `rm`s and re-`mkfifo`s the FIFO on every session start, so a cached
  `FileOutputStream` pointed at an **unlinked inode** and every subsequent write
  silently vanished. The stream is now re-opened when the FIFO's `fileKey()`
  changes.
* The writer's wait condition was `while (inputPending.isEmpty() && x11?.connected != true)`,
  so the moment the XTEST control plane came up the loop stopped waiting,
  `pollFirst()` returned null and `?: continue` spun the thread at **100% CPU**
  for the whole session. It now waits whenever the queue is empty.
* A drag floods motion events far faster than a proot-spawned xdotool can drain
  them; consecutive motions are coalesced to the newest one.
* Each IME commit used to cost **one xdotool process per character** under proot.
  `commitText` now makes a single `injectText` call.
* `inputLock` was declared `Any()`, but Kotlin's `Any` does not expose
  `wait()`/`notifyAll()` — the module did not compile until it became
  `java.lang.Object()`.

The pane badge now names the transport in use instead of a bare "agent":
`INPUT: XTEST (control plane)` / `INPUT: guest xdotool agent (slow fallback)` /
`INPUT: down - no XTEST, no agent`.

---

## 4. Verification actually performed

| check | tool | result |
|---|---|---|
| Full module type-check, all 23 Kotlin files | `compile_all.sh` | **0 errors, 261 classes** |
| X11 wire protocol vs. live server | `protocol_audit.sh` | **17/17 PASS**, 0 X errors |
| Real Kotlin `X11Client` injects click + text into a live xterm | `kotlin_xtest_e2e.sh` | **PASS**, incl. `MiXeD CaSe 123 !@#` (shift level) and a 4×BackSpace edit |
| Guest FIFO agent types into a live xterm | `agent_test.sh` | **4/4 PASS** |
| Desktop visible A/B (pixel census) | `desktop_visible_ab.sh` | black → `#20344d` |
| Cleanup sweep no longer kills its tracer | `sweep_test.sh` | victim killed, tracer survives |
| Guest script syntax | `sh -n` on `emux-x11`, `emux-capture`, `emux-init.sh`, extracted agent | OK |
| Seeded autostart is valid shell + idempotent | extracted block, 4 runs | OK |

Ground-truth cross-check from the audit: `getProperty(root, _NET_SUPPORTING_WM_CHECK)`
returns `0x20020b`, byte-identical to `xprop -root`'s `window id # 0x20020b`;
`internAtom("WM_NAME")` returns 39, the correct predefined atom id; keysym
mappings are the standard XKB keycodes (`a`=38, `Return`=36, `BackSpace`=22,
`Right`=114).

### What was NOT verified here

* **No APK was produced.** The sandbox has 1 GB RAM: a single `kotlinc` over all
  23 files is OOM-killed (exit 137) and the Gradle daemon dies mid-build
  (`DaemonDisappearedException`) at `-Xmx620m`. `compile_all.sh` exists to get
  real type-checking anyway by compiling in two chunks. **Build with
  `./gradlew :app:assembleDebug` on a normal machine.**
* **Nothing was run on an Android device or under proot.** The guest-side proofs
  run on a native Debian X stack; proot adds latency and ptrace semantics but
  not different X protocol behaviour.
* The video path was **not** changed. It was verified byte-exact earlier
  (`xwd → xwdtopnm → pnmtoplainpnm → emux-rgb565`, stream header
  `45 4d 55 58 d0 02 00 05`, 1843200-byte payload for 720×1280 RGB565) and is
  not implicated in any of the three symptoms.

---

## 5. What a device should show after this build

`ENV_VERSION` was bumped **67 → 68**, which is what actually pushes the
corrected `emux-x11` into an existing rootfs — without it every guest-side fix
here stays dormant on already-installed devices (the v0.2.19 meta-bug).

Expected on the desktop pane:

```
control plane UP via tcp :N - XTEST opcode 132, 290 keysyms, 720x1280@24, WM running
INPUT: XTEST (control plane)
```

If it instead says `INPUT: guest xdotool agent (slow fallback)`, the host could
not reach the X socket and input still works, just slowly. If it says
`INPUT: down`, read `/tmp/emux_agent.log` in the guest — the agent now logs
every event with its xdotool exit code.

`LMK: … protecting the Terminal … was DENIED` is **expected and honest** on an
unrooted device; it is not a new failure.

---

## 6. Security note

A GitHub PAT was pasted into chat to clone this repo. Per this project's own
`ACTION_PLAN.md` §0, treat any chat-pasted token as compromised and **rotate
it**. The token is not stored in any file here and `.git/config` carries a plain
`https://` remote with no credentials.
