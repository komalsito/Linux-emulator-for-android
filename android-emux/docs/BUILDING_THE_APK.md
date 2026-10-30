# Building and verifying the APK

The sandbox this project is developed in has **1 GB of RAM**, and
`./gradlew assembleDebug` is OOM-killed (exit 137) long before an APK exists.
So the repo's root `emux-debug.apk` has repeatedly gone stale: it was still
**v0.2.58** while the source had reached **v0.2.61**, which means two releases of
desktop fixes — including the fix for "I can't see the desktop" — were committed,
pushed, and *not installable by anyone*.

This document is the build recipe plus the check that catches that. Section 4
covers building the APK **without Gradle at all** — attempted, abandoned, and then
solved: `tools/build-apk/no-gradle-build.sh` now produces the committed
`emux-debug.apk`, validated against a Gradle-built reference APK.

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
against the stale v0.2.58 APK it reports `passed=41 failed=10`:

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
| **every `META-INF/*.version` marker matches `deps.lock`** | **a dependency resolved to the wrong version.** Added in v0.2.62 after a no-Gradle build shipped `androidx.sqlite` 2.4.0 / `tracing` 1.2.0 / `localbroadcastmanager` 1.1.0 where AGP resolves 2.3.0 / 1.0.0 / 1.0.0 and *every other check passed*. Class names are identical across those versions, so a class-set comparison cannot see it — only method signatures differ, and those are invisible without running the code. This is the only check here that tests the dependencies rather than the app's own code and assets. KMP naming needs care: an artifact publishes as `lifecycle-runtime-android` / `kotlinx-coroutines-core-jvm` but its marker is named for the base module, and without aliasing both spellings `core` 1.13.1 gets blamed for `coroutines-core` 1.8.1. One marker (`lifecycle-runtime-ktx`) is *generated* by AGP rather than shipped in any artifact, so it is counted and not failed |

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
git add ../emux-debug.apk && git commit -m "chore: refresh root emux-debug.apk to v0.2.62 (vc162)"
```

---

## 4. Building it without Gradle (attempted, abandoned, then solved)

`tools/build-apk/no-gradle-build.sh` builds and signs the APK with **no Gradle at
all**, in ~4 minutes on the 1 GB box. `tools/build-apk/README.md` is the reference
for it; this section records why the first attempt was abandoned, because two of the
three blockers were misdiagnosed at the time and the misdiagnosis is worth keeping.

The first attempt died on `aapt2 link`:

```
resource 'styleable/SearchView' has a conflicting value
```

`appcompat-1.7.0` and `material-1.12.0` both declare `styleable/SearchView` with
different attribute lists, and the conclusion drawn was that Gradle resolves the
conflict by dependency priority during its own resource merge, so reproducing it
would mean reimplementing the merger. **That was wrong.** Dumping the reference APK's
own `R$styleable` fields shows what Gradle actually does: it emits the **union** —
appcompat's 17 attributes plus material's 12, 29 in total, and *both* packages' R
classes get all 29. One `<declare-styleable>` in the app's own resource space, not a
priority pick. That is a two-line edit to a library `values.xml`, not a merger.

The second blocker was real but tractable: `android.nonTransitiveRClass=true` means
each library's bytecode reads its *own* R, and `aapt2 link --java` only ever produces
one R for the app package. So 59 R classes have to be generated. The rule that makes
it exact:

- field **sets** come from each AAR's own `R.txt` (which is why `loader`'s R contains
  core's `ColorStateListItem` — old AARs published transitive symbol tables, and AGP
  honours them);
- field **values** come from the final linked table;
- styleable arrays *and their index constants* must be derived from the **final**
  array, not the AAR's. The reference APK's `constraintlayout` R carries
  `AppCompatTheme_actionModeTheme` — an attribute that did not exist when
  constraintlayout 2.0.1 was published, which is only explicable if AGP regenerates
  from the merged table.

Validated against the reference APK: **179 of its library R classes match
field-for-field**, and `com.emux.R` matches exactly across all 10 types (73 fields).

The third blocker was the honest one and still stands: **nothing here can be run.**
No emulator (needs KVM and gigabytes), no device. So instead of runtime testing, the
build was closed statically against the Gradle-built APK:

| check | result |
|---|---|
| every class the reference APK defines | 8,100 total → 7,079 from locked jars + 251 generated R + 705 D8 synthetics + 69 app sources = **0 unaccounted** |
| library R classes vs the reference's | **179 exact**, 0 partial, 0 absent |
| `com.emux.R` vs the reference's | exact, all 10 types |
| `tools/verify_apk.sh` | **50 PASS / 0 FAIL** |
| APK entry set vs the reference | 969 vs 970 (dex split 2 vs 8 — cosmetic; and AGP's `app-metadata.properties` is deliberately not fabricated) |
| signature digest | equals `debug-keystore/emux-debug.jks`, so `adb install -r` upgrades in place |

Getting the class closure to zero is what uncovered the real risk, and it was not R
classes — it was **dependencies**. A POM walk gets three versions wrong, and each
error is invisible until runtime:

- `room` must be **2.5.0** (2.6.1 adds 9 classes the reference APK lacks);
- `loader` must be **1.0.0** (1.1.0 dropped `ModernAsyncTask$AsyncTaskResult` and
  two siblings that the reference APK has);
- `collection` must be **1.1.0** (1.4.0 adds 122 classes and drops `MapCollections`);
- `lifecycle-runtime` / `lifecycle-viewmodel` 2.8.6 are **empty KMP anchors** — the
  code is in the `-android` variants, selected via Gradle module metadata that no POM
  walk can see. Fetch the anchors and you ship an APK with no `ViewModel` class;
- `androidx.window`, `slidingpanelayout`, `concurrent-futures`,
  `constraintlayout-solver`, `profileinstaller`, `viewbinding` and
  `customview-poolingcontainer` are all transitive and all absent from
  `app/build.gradle.kts`.

Two more traps, both silent:

- **build-tools' own d8 cannot dex appcompat 1.7.0.** `d8` 8.2.2 dies with an
  internal `NullPointerException` on `ActionBarDrawerToggle$1`, reproducible on that
  class in isolation. AGP never hits it because it uses the D8 bundled in the plugin.
  Use `com.android.tools:r8` ≥ 8.5.
- **`aapt2 link --version-code` does not override the manifest.** With
  `android:versionCode` already present it is ignored, so the first successful link
  here produced a perfectly valid, correctly signed APK that reported **v0.2.58** —
  the exact failure mode this document exists to prevent, reproduced by the fix for
  it. The version now comes from `app/build.gradle.kts`.

`manual-build.sh` (the Gradle-warm-up hybrid) remains the reference path and should
be preferred wherever Gradle can run. The zero-Gradle path exists so that the APK can
never again lag the source that fixes the desktop.
