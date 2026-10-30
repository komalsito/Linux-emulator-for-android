# v0.2.62 — "keeps crashing after I updated", and why we could not see it

## The report

A device updated from the repo's `emux-debug.apk` to v0.2.61 and then crashed on
**every** launch, with the system **"EmuX keeps stopping"** dialog, before any UI
appeared. No adb was available, so there was no logcat.

Two facts shaped the whole investigation:

1. The system dialog — not the app's own error screen — means the throw escaped
   every guard this app has. `MainActivity.onCreate` wraps its entire startup path
   in `catch (Throwable)` and degrades to a readable error screen (v0.1.3);
   `EmuXApplication.onCreate` guards each of its statements (v0.1.6); `postSafely`
   guards `View.post` bodies (v0.1.8). A crash that reaches the *system* dialog is
   one that happens somewhere none of those can see.
2. v0.2.61 was the **first build in four releases that actually contained the code
   it claimed to**. `8e80f81` (v0.2.59), `639bacc` (v0.2.60), `34a06dc` (v0.2.61)
   and `09b0106` all shipped a **v0.2.58** APK. So ~1,889 changed lines across
   `emux-x11`, `DesktopService.kt`, `DesktopView.kt` and `X11Client.kt` reached a
   device for the first time in this install. The crash was at least as likely to
   be a genuine bug in never-executed code as in the build that packaged it.

## What was ruled out, and how

The v0.2.61 APK was built without Gradle (`tools/build-apk/no-gradle-build.sh`),
so "did the hand-built packaging break something?" had to be answered before
anything else. Each check below compares against the **AGP-built v0.2.58 APK**
pulled from git history (`c7cc03a`) — a known-good artifact from the same source
tree.

| Suspect | Test | Result |
|---|---|---|
| Missing classes | class-def set from all dex files, both APKs | 4 missing: 3 empty KMP-anchor `R` classes + `DesktopService$stop$3` (lambda renumbered to `$stop$5` by the source changes). **The 3 R classes are referenced nowhere in the dex**, so nothing can throw `NoClassDefFoundError` for them |
| Native libs mis-packaged (`extractNativeLibs=false` needs STORED + page-aligned) | per-entry `compress_type` | Not applicable: **there is no `lib/` directory at all**. All 8 `.so` files live under `assets/` and are payload for the guest rootfs |
| Compressed assets breaking `AssetManager.openFd()` | grep for `openFd`/`AssetFileDescriptor` | Zero uses. Everything reads via `ctx.assets.open(...)`, which streams and handles DEFLATED fine |
| Stale merged manifest (components added in v0.2.59–61) | `aapt2 dump xmltree` on both, full diff | **Structurally identical** — 232 lines each, differing only in `versionCode`/`versionName` (correctly 161/0.2.61) and source line-number metadata |
| Resource table drift | `aapt2 dump resources` on both | 5,217 resources each, **0 IDs differing, 0 names differing** |
| Duplicate class definitions across the 2 dex files | per-dex class-def walk with a counter (a set union hides these) | 8,107 defs, 8,107 unique, **0 duplicates** |
| Malformed dex | `dexdump -f` | both parse cleanly |
| **Stub bodies leaking into the dex** | `UnsupportedOperationException` string count + per-class method counts | **Ruled out.** 4 occurrences vs the reference's 5. All 288 shared `com/emux` classes have *identical* method counts except the three files that legitimately grew (`X11Client` 37→50, `DesktopService` 92→109, `DesktopView` 24→51) |
| Signature | `apksigner verify --print-certs` | Same cert as every previously shipped APK (`5e3ef254…a1fa`), so `install -r` upgrades in place |

That is a strong result and an uncomfortable one: **the hand-built APK is
structurally equivalent to a known-good AGP build.** Packaging was not the answer.

## What was actually found

`META-INF/*.version` — a file every AAR ships naming its own version. Diffing the
two APKs' markers against each other compares the *resolved dependency graph*,
which no class-name comparison can see:

| artifact | AGP resolved | v0.2.61 build shipped |
|---|---|---|
| `androidx.sqlite:sqlite` | **2.3.0** | 2.4.0 |
| `androidx.sqlite:sqlite-framework` | **2.3.0** | 2.4.0 |
| `androidx.tracing:tracing` | **1.0.0** | 1.2.0 |
| `androidx.localbroadcastmanager` | **1.0.0** | 1.1.0 |

`javap` on both versions of each showed the drift was **benign**: tracing's changes
are purely additive, localbroadcastmanager is byte-identical in API, and
sqlite-framework differs only by one **private** field on `FrameworkSQLiteDatabase`
(unreachable from Room 2.5.0). Real defects, wrong to ship — but not the crash.

So, honestly: **the cause of the crash was not identified.** Static analysis ran
out of things to say, and without a device log the remaining hypotheses cannot be
separated. What v0.2.62 does instead is make the next occurrence answer the
question itself.

## What v0.2.62 changes

**1. `com.emux.CrashReport` — a process-wide reporter that makes crashes legible
without adb.** Installed as the first statement of `Application.onCreate`, before
anything else can throw, so it is in place before any provider or Activity runs. It
covers exactly the escapes listed above: androidx.startup's three initializers
(`EmojiCompatInitializer`, `ProcessLifecycleInitializer`,
`ProfileInstallerInitializer`), Runnables posted after `onCreate` returned,
uncaught Throwables in coroutines, View callbacks, and class initialization. It
writes version / ENV_VERSION / API level / ABI / thread / full stack trace /
`EmuXLog` tail to `filesDir/last_crash.txt` **and** `getExternalFilesDir(null)` —
the latter is reachable with a file manager and no root. It then delegates to the
previous handler: **the crash still crashes.** Nothing is swallowed.

`MainActivity.onCreate` shows a fresh report (≤30 min) on screen with a Copy button
and a "Try starting EmuX anyway" escape hatch, built in code rather than XML
because it must work when inflation is what broke.

**2. The one unguarded statement in `Application.onCreate` is now guarded.**
`TtyWrap.cacheFile = File(filesDir, "ttywrap.probe")` initializes the `TtyWrap`
object, whose `<clinit>` reads `TerminalSession.CANDIDATES` — so a failure there
was an `ExceptionInInitializerError` *before* any try block, i.e. an unopenable app
with no explanation. It is a cache; losing it costs one re-probe.

**3. `DesktopView.onDraw` can no longer kill the process.** A View callback runs
outside every Activity-level guard, and this pane gained real drawing code in
v0.2.60/61 (intro card, helper bar, `Chip`, `fadeTick`) that had never executed on
a device. It now degrades to a dark pane with a one-line note and stops the fade
ticker, because a blank desktop is recoverable and a dead process is not.

**4. `deps.lock` corrected** to the four versions above, plus
`lifecycle-livedata-core-ktx:2.8.6` — a KMP anchor with zero classes whose
`classes.jar` nonetheless carries a marker AGP emits. Marker parity with the AGP
build went 55/61 → 60/61; the remaining one (`lifecycle-runtime-ktx`) is generated
by AGP rather than harvested from any artifact, so reproducing it would mean
fabricating a file.

**5. `tools/verify_apk.sh` gained the check that was missing.** It had 50
assertions and passed all of them on an APK with four wrong dependencies. Section 5
now diffs every packaged `.version` marker against `deps.lock` and fails on drift.
It is the only check in that script that tests the *dependencies* rather than the
app's own code and assets. Matching has to account for KMP naming: an artifact
publishes as `lifecycle-runtime-android` / `kotlinx-coroutines-core-jvm` but its
marker is named for the base module, and without aliasing those, `core` 1.13.1 gets
blamed for `coroutines-core` 1.8.1.

Run against the v0.2.61 APK the new check reports exactly the four real drifts and
nothing else. v0.2.62 passes 51/0.

## If it crashes again

The next launch will show the stack trace on screen. Copy it or photograph it. That
single artifact is enough to finish this; everything above is what can be determined
without one.
