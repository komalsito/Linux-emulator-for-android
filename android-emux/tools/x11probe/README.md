# `tools/x11probe` — reproducible evidence for the X11 desktop

Every claim in `docs/DESKTOP_FIX_v0.2.59.md` and `docs/INPUT_AND_PAINT_v0.2.60.md`
is reproduced by a script here.
These are **ground-truth harnesses**, not unit tests: they stand up a real
`Xvfb` + `Openbox` + `xdotool` + `xterm` stack and measure what actually
happens, because three rounds of this project "fixed" the desktop by reasoning
about code that was never executed.

Nothing here runs on an Android device. They run on any Debian/Ubuntu box (or
the same sandbox) with:

```sh
apt-get install -y xvfb openbox xdotool x11-apps netpbm x11-xserver-utils \
                   xterm tint2 pcmanfm fonts-dejavu-core
```

## The harnesses

| script | what it proves |
|---|---|
| `desktop_visible_ab.sh` | **A/B, pixel-level.** Bare `openbox` (v0.2.58) vs `openbox --sm-disable --startup <autostart>` (v0.2.59). Reports a marker file, live process list and an `xwd` root-window colour census. |
| `desktop_paint_test.sh` | **What colour is the desktop, really?** Pixel census of the root window for every `pcmanfm` state a real install can be in (pristine black `desktop-items-0.conf`, fresh HOME, missing `wall.png`, user-chosen colour, seeded twice). The "before" half runs the seed extracted from the newest commit that still contains `wallpaper_mode=6`, so it keeps reproducing the original bug after the fix is committed. |
| `gesture_e2e.sh` + `GestureDriver.kt` | **Input, witnessed at the wire.** The real compiled input path drives a live server while `xev` records what the server delivered: middle click, wheel 4/5/6/7 with exact click counts, held button 3 right-drag, and Ctrl/Alt/Super sticky modifiers by modifier mask. |
| `doctor_test.sh` | **`emux-x11 doctor` against three real states** — healthy desktop, pcmanfm's pristine black `desktop-items-0.conf`, and no X server at all — asserting the report text, that each process gets its OWN pid, and the read-only invariants (session markers survive, no grab file left behind, nothing on stderr). |
| `seed_autostart_test.sh` | The generated `~/.config/openbox/autostart` against four filesystem states: fresh, 4× (idempotency), user-written (backed up **and chained**), older EmuX stamp. Extracts the generation block by marker. |
| `agent_test.sh` | Runs the guest FIFO input agent **as embedded in `emux-x11`** against a live xterm and asserts the typed text arrives. Covers `M`/`D`/`U`/`T`/`N` and the legacy `K <keysym>` path. |
| `protocol_audit.sh` + `ProtocolAudit.kt` | Runs the **real compiled `X11Client`** on a plain JVM against a live server and audits handshake, XTEST discovery, atoms, properties and keysym→keycode mapping, cross-checked against `xprop`. This is what caught the `InternAtom`/`GetProperty` BadLength bugs. |
| `kotlin_xtest_e2e.sh` + `KotlinXtestDriver.kt` | End-to-end: the real Kotlin client clicks and types into a live `xterm`, and the harness asserts what the xterm's shell received. Covers shift level (`MiXeD CaSe 123 !@#`) and a 4×BackSpace edit. |
| `sweep_test.sh` | **A/B** for `killDesktopGuests()`: the old payload kills its own ptrace tracer, the new one does not, and both still kill the intended victims. |
| `compile_all.sh` | Two-chunk `kotlinc` type-check of all 23 Kotlin files against the real API 34 `android.jar`. |
| `xclient.py` | Minimal from-scratch X11 wire client (handshake, QueryExtension, GetKeyboardMapping, XTEST FakeInput, event pump). Used to establish the protocol facts the Kotlin client had wrong. |
| `extract_agent.py` | Pulls the nested `sh -c '...'` agent out of `emux-x11`, reverses the `'"'"'` escaping and `sh -n`s it. |
| `gen_stubs.py` | Generates `R` and `ActivityMainBinding` stubs from the real `res/` for `compile_all.sh`. |
| `fetch_libs.py` | Downloads the real AndroidX/Kotlin jars used as the compile classpath. |

## Running them

```sh
sh tools/x11probe/desktop_paint_test.sh     # needs xvfb, pcmanfm, xwd, netpbm  -> 20/20
sh tools/x11probe/desktop_visible_ab.sh     # needs xvfb, openbox, tint2, pcmanfm, netpbm -> 8/8
sh tools/x11probe/seed_autostart_test.sh    # needs nothing but sh             -> 23/23
sh tools/x11probe/doctor_test.sh            # needs xvfb, openbox, x11-utils, netpbm -> 20/20
sh tools/x11probe/agent_test.sh             # needs xvfb, openbox, xdotool, xterm -> 4/4
sh tools/x11probe/sweep_test.sh             # needs nothing but /proc

# these three need the compiled classes, so run compile_all.sh first
sh tools/x11probe/protocol_audit.sh         # 17 assertions against a live Xvfb
sh tools/x11probe/kotlin_xtest_e2e.sh       # real Kotlin client types into an xterm
sh tools/x11probe/gesture_e2e.sh            # needs xev (in x11-UTILS, not x11-apps) -> 10/10
```

Type-check (no Gradle, ~2 min, needs ~800 MB RAM and a JDK 17):

```sh
python3 tools/x11probe/fetch_libs.py        # -> /opt/libs/*.jar      (once)
python3 tools/x11probe/gen_stubs.py         # -> /tmp/stubs/*.kt      (once)
sh      tools/x11probe/compile_all.sh       # -> 0 errors, 263 classes
```

Override with `ANDROID_JAR=`, `LIBS_DIR=`, `KOTLINC=`, `HEAP=`, `STUBS=`.

## Traps these scripts encode

Each of these cost a false result before it was fixed, so they are written into
the harnesses rather than left as tribal knowledge:

1. **`pgrep -x` and `kill -0` both match zombies.** This container's PID 1 does
   not reap, so a `tint2` killed by an earlier run still looks "RUNNING". Read
   the state field from `/proc/<pid>/stat` (`sed 's/.*) //' | cut -d' ' -f1`,
   because `comm` may contain spaces and parentheses) and skip `Z`.
2. **Never put a match pattern in a shell payload's argv.** `proot`'s own argv
   contains the payload, `proot` binds the host `/proc`, so a `case` arm like
   `*Xvfb*` matches the tracer itself. Pass patterns through the environment.
   `sweep_test.sh` demonstrates the failure and the fix side by side.
3. **`xev -event button,key` is rejected** ("unrecognized event mask") and xev
   exits silently, which is indistinguishable from "the server delivered
   nothing". One mask per occurrence: `-event button -event keyboard`. And
   confirm the witness is up *before* starting the driver, or it records
   nothing and every assertion fails for the wrong reason. `xev` lives in
   **`x11-utils`**, not `x11-apps`.
4. **A bare `DISPLAY=:N` line in a subshell is not exported.** pcmanfm then dies
   with `Cannot open display:` and the census reports the untouched root. Use
   `export DISPLAY=…` or an assignment prefix on the command itself.
5. **Extract by marker, never by line number.** `sed -n '545,575p'` on
   `emux-x11` silently returns the wrong block after any edit; the harnesses use
   `awk '/^seed_desktop_config\(\) \{/{f=1} f{print} f&&/^\}/{exit}'` and assert
   the extraction succeeded.
6. **Pin the "before" revision — and pin it precisely.** A harness that reads
   the old behaviour from `HEAD` stops reproducing the bug the moment the fix is
   committed, so `desktop_paint_test.sh` scans history for the newest commit
   that still has the broken code, and skips (never fakes) the case if there is
   none. The scan must match the EMITTED code (`grep -F 'echo
   "wallpaper_mode=6"'`), not any mention of it: the fix commit documents the
   bug in a comment containing that same literal, so a plain grep selected the
   FIXED revision and the "before" case quietly started passing for the wrong
   reason.

