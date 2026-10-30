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

---

## Addendum: v0.2.62 did not work, and why that was predictable

The field answer came back: *"still nothing from the app, it's an instant crash."*
No report file, no report screen. That is not a mystery — v0.2.62 installed the
handler in `Application.onCreate`, and Android's startup order is:

```
Application()  ->  attachBaseContext()  ->  [EVERY ContentProvider.onCreate]  ->  Application.onCreate()  ->  Activities
```

The app's only ContentProvider is `androidx.startup.InitializationProvider`, running
`EmojiCompatInitializer`, `ProcessLifecycleInitializer` and
`ProfileInstallerInitializer` in the window **before** `onCreate`. If the throw is
there, the handler did not exist yet, so nothing could be recorded. The v0.2.62
design could not have caught the very case it was written for, and the comment in
that commit even listed "an androidx.startup provider" as a covered case. It was
not.

### v0.2.63

- **`CrashReport.install` moved to `attachBaseContext`**, which runs before any
  provider. That is the only position from which a process-wide handler covers the
  whole startup path. Still installed in `onCreate` too (idempotent), so a future
  reordering cannot silently undo it.
- **`CrashReportActivity` runs in `android:process=":crash"`.** The crashing process
  is dying and would take an in-process Activity with it; a second process is started
  by `system_server` and outlives the failure. It uses a **framework** theme
  (`@android:style/Theme.DeviceDefault.NoActionBar`) and builds its views in code,
  because if app resource inflation is what broke, this screen still has to work.
  `EmuXApplication.onCreate` returns early in that process so a screen whose only job
  is to display a stack trace does not also schedule bootstraps.
- **`SafeStartupProvider` was written and then deleted.** Subclassing
  `InitializationProvider` to catch its initializers is impossible:
  `javap -p androidx.startup.InitializationProvider` shows
  `public final boolean onCreate()`. All five of its methods are final. The net had to
  go earlier in the lifecycle instead — which is what `attachBaseContext` provides.

### Build-pipeline defects found while rebuilding

The sandbox had lost its entire toolchain (`java`, `/opt`, `/tmp/stubs`), so the build
had to be reconstructed from the committed scripts. Four things broke, and three of
them were silent:

1. **The two-pass compile depended on four stub files that were never committed.**
   `$STUBS/{ActivityMainBinding,SubsetStubs,SubsetStubs2,BootstrapManagerStub}.kt`
   existed only in one machine's `/tmp`. A fresh checkout could not build at all, and
   the failure was quiet: empty stub files still compile, the damage appears as
   unresolved references. The split existed only because a single `kotlinc` over
   ~12.6k lines was believed to OOM at 1 GB. **It does not** — one pass with
   `-Xmx700m` finishes in about a minute, and `kotlinc` resolves `.java` sources given
   on the command line, which is what removes the cycle. `compile_classes.sh` is now a
   single pass with no stubs, so nothing can leak a throwing placeholder body into the
   dex.
2. **The driver masked compile failures.** Stage 7 was
   `sh compile_classes.sh | grep -E '...'`. Under `set -e` a pipeline's status is its
   LAST command's, so `grep` succeeding hid `exit 1` — and a build with unresolved
   references was dexed, packaged, aligned and signed. `verify_apk.sh` caught it
   (9 PASS / 3 FAIL), but the driver must not produce it. Stage 7 and stage 1 now
   capture output and check the status explicitly.
3. **The `SearchView` styleable union was a hand edit, not a pipeline stage.**
   `appcompat` declares it with 17 attrs and `material` with 12; `aapt2 link` alone
   rejects the pair. AGP never sees this because its resource *merger* unions them
   first. The previous fix was an edit to an extracted `values.xml` under `/opt`, so it
   vanished with the sandbox. `fix_styleable_union.py` is now stage 3/10: it finds
   every styleable declared by more than one library, rewrites the first occurrence to
   the union and removes the duplicates. Idempotent, and it reports
   `appcompat:17, material:12 -> union of 29`, matching the reference APK's
   `R$styleable.SearchView` field count exactly.
4. **The compiled-resource cache never invalidated.** Stage 4 was
   `[ -f flat/$n.zip ] || aapt2 compile`, so after stage 3 rewrote `values.xml` the
   stale archive was reused and `aapt2 link` failed on a conflict that had already been
   fixed. It now recompiles when any file under a library's `res/` is newer than its
   archive — the same class of silent staleness as a stale committed APK, one level
   down.

Also in `verify_apk.sh`: sections 3 and 4 shelled out to `unzip` and `strings`. Neither
exists on a lean machine, and when they were missing the 34 asset checks silently
SKIPped while the dex checks emitted three **false FAILs** claiming a current APK
"predates v0.2.60". Both sections now use `python3`/`zipfile`, which the pipeline
already requires. A verifier that cannot run must not look like one that found a
problem.
