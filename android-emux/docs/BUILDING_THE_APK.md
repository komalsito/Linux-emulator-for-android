# Building and verifying the APK

The sandbox this project is developed in has **1 GB of RAM**, and
`./gradlew assembleDebug` is OOM-killed (exit 137) long before an APK exists.
So the repo's root `emux-debug.apk` has repeatedly gone stale: it was still
**v0.2.58** while the source had reached **v0.2.61**, which means two releases of
desktop fixes — including the fix for "I can't see the desktop" — were committed,
pushed, and *not installable by anyone*.

This document is the build recipe plus the check that catches that, and it ends
with why building the APK **without** Gradle was attempted and abandoned.

---

## 1. Build (any machine with ~4 GB free RAM)

```sh
git clone https://github.com/komalsito/Termux2 && cd Termux2/android-emux
# point Gradle at your SDK, or export ANDROID_HOME
./gradlew :app:assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

JDK 17+, Android SDK platform 34 and build-tools 34. `local.properties`
(`sdk.dir=…`) is machine-specific and deliberately not committed.

The debug signing key **is** committed (`debug-keystore/emux-debug.jks`, alias
`emux`, storepass/keypass `emuxdebug`) and `app/build.gradle.kts` uses it for the
`debug` build type, so every build from every machine produces the *same*
signature. That is what makes this an in-place upgrade rather than a reinstall:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

A different signature would fail with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, and
the only way past that is `adb uninstall com.emux` — which **deletes the
downloaded Linux rootfs** and forces a full re-bootstrap. Don't sign builds with
an ad-hoc `~/.android/debug.keystore`.

## 2. Verify before shipping or installing

```sh
sh tools/verify_apk.sh                                    # checks ../../emux-debug.apk
sh tools/verify_apk.sh app/build/outputs/apk/debug/app-debug.apk
```

It compares the APK against the **working tree** (so it also catches a build made
from an edited or uncommitted tree) and exits non-zero on any mismatch. Run
against the stale v0.2.58 APK it reports `passed=40 failed=10`:

| check | what a failure means |
|---|---|
| `versionCode`/`versionName` match `app/build.gradle.kts` | the APK is from another release — **the usual cause of "the fix didn't work"** |
| `minSdkVersion`/`targetSdkVersion`/package/launchable activity | wrong build variant or module |
| signature digest == `debug-keystore/emux-debug.jks` | installing it would need an uninstall and wipe the rootfs |
| `apksigner verify` | corrupt or unsigned APK |
| every `app/src/main/assets/**` file byte-identical | the guest half of the app is stale. **This matters more than the dex**: the desktop fixes live in `assets/bin/emux-x11`, and a device only re-seeds it when `ENV_VERSION` rises |
| APK's `emux-x11` carries the current rice + autostart stamps | the guest script predates the pcmanfm background fix / the autostart chaining fix |
| APK's `emux-x11` handles `desktop-items-0.conf` | pre-v0.2.60 — desktop still goes black on any install that ran pcmanfm once |
| APK's `emux-x11` has `doctor()` | pre-v0.2.61 — no field diagnostic |
| dex contains `injectScroll`, `injectKeysymCombo`, `maybeShowIntro` | the host half is stale (these exist only from v0.2.60/v0.2.61 on) |
| multidex | wrong packaging |

`ENV_VERSION` itself is a Kotlin `const val`, so it is **inlined into the dex as a
bare integer** and cannot be grepped out — hence the method-name checks above as
the proxy for "the dex is current". The summary prints the source `ENV_VERSION`
because that number, not the version name, is what decides whether an existing
install re-seeds the guest scripts.

## 3. Refreshing the committed APK

The repo convention is a separate commit so the binary churn is easy to skip when
reviewing:

```sh
cp app/build/outputs/apk/debug/app-debug.apk ../emux-debug.apk
sh tools/verify_apk.sh            # must be all PASS
git add ../emux-debug.apk && git commit -m "chore: refresh root emux-debug.apk to v0.2.61 (vc161)"
```

---

## 4. Why not build it without Gradle? (attempted, abandoned — measured)

Worth recording because it looks easy from the outside: the module is 23 Kotlin
files, `isMinifyEnabled=false` for debug (no R8), there are no `jniLibs`, the
assets are 1.6 MB, and build-tools r34 (`aapt2`, `d8`, `zipalign`, `apksigner`)
is a 61 MB download. Most of the pipeline *did* work:

| step | result |
|---|---|
| build-tools r34 from `dl.google.com` | fine (`chmod +x` — Python's `zipfile.extractall` drops exec bits) |
| 61 dependencies re-fetched **as AARs** (the type-check classpath keeps only `classes.jar`, discarding `res/`) | 60/61; `androidx.collection:collection-ktx:1.4.0` is a 261-byte placeholder jar, not an AAR |
| 48 `classes.jar` + 36 `res/` + emoji2's inner `libs/repackaged.jar` | fine |
| `aapt2 compile` of 36 library res dirs + the app's | 37/37, 0 failures |
| merged `AndroidManifest.xml` | **solved**: reconstructed from the shipped APK with `aapt2 dump xmltree` + `aapt2 dump resources`, resolving the 13 numeric `@0x7f…` references back to symbolic names (a fresh link reassigns IDs, so numeric refs copied from the old APK would point at the wrong resource). Result: well-formed XML, structure identical to Gradle's merger — 10 `uses-permission`, 9 `receiver`, 6 `service`, 3 `activity`, 1 `provider`, 2 `uses-library`, including WorkManager's constraint proxies, the `androidx.startup` provider, `profileinstaller`, `room` and the `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` |
| `aapt2 link` | **blocked** |

Two blockers, both structural rather than fixable by tweaking flags:

1. **Gradle pre-merges library resources; `aapt2 link` alone cannot.** Linking all
   37 compiled resource sets fails on
   `resource 'styleable/SearchView' has a conflicting value` — declared by both
   `appcompat-1.7.0` and `material-1.12.0` with *different* attribute lists. Only
   1 of 132 styleables collides, but Gradle resolves it by dependency priority
   during its own merge, so the conflict never reaches aapt2 in a real build.
   Reproducing that means implementing the merger.
2. **`android.nonTransitiveRClass=true`** (in `gradle.properties`). Gradle emits
   **per-library** R classes: `androidx.appcompat.R.styleable.SearchView` and
   `com.google.android.material.R.styleable.SearchView` hold *different* arrays,
   and each library's compiled code reads its own. A hand build would have to
   regenerate ~40 R classes with per-library styleable arrays and per-library
   resource attribution (recoverable from each AAR's own `R.txt`, but it is real
   work). Getting it wrong does not fail at build time — it makes one library
   read another's `int[]` at runtime, in whatever widget uses it.

And the decisive one: **there is no way to run the result here.** No emulator
(needs KVM and gigabytes), no device. Everything above is static validation; the
failure mode that non-transitive R produces is a runtime misbehaviour in a widget,
which is precisely what static checks cannot see. For an app whose user cannot
easily debug it, a plausibly-built APK that is subtly wrong is worse than no APK
— it converts "please rebuild this" into a fresh round of unverifiable field
reports.

What is reusable if anyone picks this up: the manifest reconstruction technique
(it is exact, and it is the only way to see what Gradle's merger produced), the
AAR-with-resources fetch, and `tools/verify_apk.sh`, which needs nothing but
build-tools and validates a Gradle-built APK completely.
