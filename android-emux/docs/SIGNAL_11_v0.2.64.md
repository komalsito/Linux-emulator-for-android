# v0.2.64 — `proot warning: signal 11` on every command, and the gate that was missing from the terminal path

## The report

v0.2.63 fixed the launch crash ("It opens now"). The next thing the device did
was type a command into the terminal and get:

```
proot warning: signal 11
```

…repeatedly, for every command. The shell was unusable.

## What `signal 11` means here, and what it does not

`proot` runs the guest under `ptrace`. When a **tracee** dies of `SIGSEGV`, proot
reports it as `warning: signal 11`. So this message is never about proot being
broken — it is proot faithfully reporting that *the program it just exec'd for you
segfaulted*. The interesting question is always: why did the guest binary die?

This app has known the answer since **v0.2.41**, and wrote it down in
`BootstrapManager.dpkgLibcBroken`:

> libc-bin not fully installed => guest userspace SIGSEGVs (field v0.2.40:
> "proot warning: signal 11" where the shell prompt should be; terminal unusable;
> every new X client dies into the half-swapped library set while old processes
> keep running). … anything but `install ok installed` means the rootfs **must be
> healed BEFORE any shell or client launches**.

Three failure modes produce it, all documented in-tree:

| Cause | Where it is written down | Symptom |
|---|---|---|
| `libc-bin` not fully installed | `BootstrapManager.dpkgLibcBroken` (v0.2.41) | **every** execve dies; terminal permanently unusable |
| apt/dpkg transaction in flight while new guests exec | `BootstrapManager` v0.2.57 note; `emux-x11:1159` | SIGSEGV *wave*; "terminal works when I don't open the desktop tab" |
| kill-sweep matching proot's own cmdline | `DesktopService:1243` — **fixed in v0.2.59** | tracer killed, tracee reports signal 11 |

The third was already fixed. The device in question had just been through four
consecutive install/crash/force-stop cycles (v0.2.61 → v0.2.63), which is
precisely how an apt/dpkg transaction gets interrupted mid-swap and leaves
`libc-bin` half-installed. So the first cause is the likely one, and the second is
plausible alongside it.

## The actual bug: the invariant was enforced on two paths out of three

The v0.2.41 comment states a hard invariant — *heal before any shell launches*.
Grepping for the detectors that enforce it:

| Launch path | Pre-launch health gate? |
|---|---|
| Bootstrap / desktop pre-repair (`BootstrapManager.desktopPreRepair`) | **Yes** — `aptJournalDirty \|\| dpkgHalfConfigured \|\| dpkgLibcBroken \|\| !stamp` → `runRepairProot` |
| Desktop cascade (`DesktopService:399`) | **Yes** — same detectors, same repair |
| **Terminal session (`TerminalService.startSession`)** | **No** |

The terminal path checked exactly one thing: `FsLayout.prootBin(ctx).isFile` —
*does the proot binary exist*. Never *is the guest's libc sane*.

So on a damaged rootfs the terminal path did the following, in order:

1. `prootBin` exists → spawn the shell. ✅
2. proot execs the guest shell → **SIGSEGV** → `signal 11`. ❌
3. Session goes `DEAD` with a nonzero exit → the v0.1.18 auto-respawn watcher
   fires → back to step 1.
4. After `MAX_AUTO_RESTARTS` (2) the budget is exhausted and the terminal stays
   **permanently dead** until an app restart.

Nothing on that path could detect the damage, repair it, or even explain it. The
desktop tab would have healed the very same rootfs on open — which is why this
class of report historically reads as "the desktop works but the terminal
doesn't."

## The fix

**1. `BootstrapManager.ensureGuestHealthy(tag, note)` — the missing gate, factored
out so every launch path shares one implementation.**

Reuses the three existing detectors and the existing `runRepairProot` (which
already takes the apt lock). Deliberate properties:

- **Cheap when healthy**: one `listFiles()` on `var/lib/dpkg/updates` plus two
  *streamed* reads of `var/lib/dpkg/status`. Milliseconds — safe before every
  session start, so the healthy path is behaviourally unchanged.
- **Fail-open**: any throw is swallowed and reported `HEALTHY`. A damaged terminal
  still beats no terminal.
- **Damage-only trigger**: unlike `desktopPreRepair`, a missing
  `emux-repair.stamp` does *not* force a repair. That stamp is a
  desktop-completeness concern; keying on it would put a ~1 min apt run in front
  of the very first shell on an otherwise-pristine rootfs.
- **Re-verifies after repair**: apt can exit 0 and still leave `libc-bin` short —
  the entire reason `dpkgLibcBroken` exists separately from `dpkgHalfConfigured`.
- **Distinguishes `LOCK_BUSY`** from failure, because the remedy is the opposite:
  wait, don't spawn.

**2. `TerminalService` runs the gate before `s.start()`.**

The session object is constructed and registered first (so the UI has something to
attach to and the banners have a screen to land on), then `gateGuestHealth` runs on
`Dispatchers.IO`, then the child is spawned on `Main`. On `LOCK_BUSY` it polls for
up to 90 s — the same budget the desktop cascade uses for that lock — because
spawning into a live dpkg library swap is the documented SIGSEGV wave.

**3. The user is told what is happening, in the terminal.**

`banner()` pushes host-side plain text into the session's own `scrollback`
channel. A gate that silently blocks for 90 s is indistinguishable from the app
being broken again, and this device has no adb — the terminal *is* the diagnostic
surface. Text is deliberately plain, no ANSI, so `VtTerminalView` can never
half-parse an escape sequence into a prompt.

Detection reuses the existing `streamTail` ring rather than adding a second
collector: `scrollback` is a `Channel` with exactly one consumer (the terminal
view), and tapping it from the service would **steal bytes off the user's screen**.
The signature is pure printable ASCII, so the `<xx>` escaping never splits it. It
is counted on *transition* only — the string persists inside the 600-char ring
across many reads, so matching per-read would inflate `segvCount` into the
thousands for a single broken libc.

**4. Respawn carries the evidence forward.**

`newSession(command, priorSegv)` — the auto-respawn path now passes the dead
session's `segvCount`, so the replacement is gated and healed rather than
respawned straight back into the same wall. When dpkg nonetheless reports clean
but the guest still segfaulted, the terminal says so and names the remaining
suspects (ABI/loader mismatch) instead of looping silently.

## Scope notes

- **`ENV_VERSION` stays 70.** This change is Kotlin-only; no helper script or asset
  changed. Bumping it would trigger a device-wide helper re-seed for no reason —
  and re-seeding while a shell is live is itself a suspected SIGSEGV contributor.
- **The desktop's own sessions are not double-gated.** `DesktopService` builds
  `TerminalSession(9001+n, …)` directly and already pre-repairs at line 399; the
  respawn guard's `s.id < 9000` keeps those out of this path.
- **Both user-facing entry points are covered**: `MainActivity:557` and
  `EmuXExecuteService:21` both go through `TerminalService.startSession` →
  `ACTION_NEW_SESSION` → `newSession`.

## Verification

| Check | Result |
|---|---|
| kotlinc single pass | `exit=0 errors=0`, 25 kt sources → 254 classes |
| Class audit | 504 classes, all traceable to a real source or generated file |
| `verify_apk.sh` | **51 passed / 0 failed / 0 skipped** |
| Version stamp | `com.emux` versionCode **164**, versionName **0.2.64** |
| Signature | committed keystore, SHA-256 `5e3ef254…a1fa` |
| New code present in dex | `ensureGuestHealthy`, `GuestHealth`, `segvDetected`, `terminal-banner`, `libc-bin not fully installed`, `half-swapped library set` — all FOUND |
| APK | `emux-debug.apk`, 6,852,445 B, sha256 `88a0dc13…4998` |

## What to expect on the device

Install v0.2.64 and open a terminal. One of three things happens:

1. **Healthy rootfs** → the shell starts immediately, exactly as before. The gate
   cost a few file reads.
2. **Damaged rootfs** → the terminal prints
   `terminal: repairing guest rootfs (libc-bin not fully installed) - usually
   under a minute`, runs one dpkg repair, prints `terminal: guest rootfs
   repaired`, and *then* gives a working prompt.
3. **Damaged and unrepairable** → the terminal says so and points at the log tail
   under Settings → About, which is readable without a computer.

If instead the shell still prints `signal 11` after a *successful* repair, that
rules out the dpkg causes entirely and the banner will say so — at which point the
remaining suspects are an ABI/loader mismatch or a stray kill-sweep, and the log
tail is the next thing to read.
