# `tools/x11probe` — reproducible evidence for the X11 desktop

Every claim in `docs/DESKTOP_FIX_v0.2.59.md` is reproduced by a script here.
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
sh tools/x11probe/desktop_visible_ab.sh     # needs xvfb, openbox, tint2, x11-apps, netpbm
sh tools/x11probe/agent_test.sh             # needs xvfb, openbox, xdotool, xterm
sh tools/x11probe/sweep_test.sh             # needs nothing but /proc

# these two need the compiled classes, so run compile_all.sh first
sh tools/x11probe/protocol_audit.sh         # 17 assertions against a live Xvfb
sh tools/x11probe/kotlin_xtest_e2e.sh       # real Kotlin client types into an xterm
```

Type-check (no Gradle, ~2 min, needs ~800 MB RAM and a JDK 17):

```sh
python3 tools/x11probe/fetch_libs.py        # -> /opt/libs/*.jar      (once)
python3 tools/x11probe/gen_stubs.py         # -> /tmp/stubs/*.kt      (once)
sh      tools/x11probe/compile_all.sh       # -> 0 errors, 261 classes
```

Override with `ANDROID_JAR=`, `LIBS_DIR=`, `KOTLINC=`, `HEAP=`, `STUBS=`.

## Two traps these scripts encode

Both cost a false result before they were fixed, so they are written into the
harnesses rather than left as tribal knowledge:

1. **`pgrep -x` and `kill -0` both match zombies.** This container's PID 1 does
   not reap, so a `tint2` killed by an earlier run still looks "RUNNING". Read
   the state field from `/proc/<pid>/stat` (`sed 's/.*) //' | cut -d' ' -f1`,
   because `comm` may contain spaces and parentheses) and skip `Z`.
2. **Never put a match pattern in a shell payload's argv.** `proot`'s own argv
   contains the payload, `proot` binds the host `/proc`, so a `case` arm like
   `*Xvfb*` matches the tracer itself. Pass patterns through the environment.
   `sweep_test.sh` demonstrates the failure and the fix side by side.
