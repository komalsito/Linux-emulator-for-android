# Building the APK without Gradle

`./gradlew assembleDebug` needs ~2 GB and is OOM-killed (exit 137) on the 1 GB box
this project is developed on. Two pipelines here work around that, and they differ
in **how much of Gradle they still need**:

| | `manual-build.sh` | `no-gradle-build.sh` |
|---|---|---|
| Gradle needed | yes — a small-heap *warm-up* run | **none at all** |
| Gets `R.jar`, `ActivityMainBinding.java`, `resources-debug.ap_` from | Gradle | generated here |
| Use when | Gradle can run at all (it is the reference path) | Gradle cannot run, or you want a build whose every input is inspectable |

Both finish with the same steps (kotlinc, javac, d8, zipalign, apksigner with the
committed keystore) and both should be checked with `tools/verify_apk.sh`.

```sh
# zero-Gradle build; writes ../emux-debug.apk and verifies it
WORK=/tmp/emux-nogradle \
ANDROID_JAR=$SDK/platforms/android-34/android.jar \
BUILD_TOOLS=$SDK/build-tools/34.0.0 \
R8_JAR=/path/to/r8-8.5.35.jar \
KOTLINC=/path/to/kotlinc/bin/kotlinc \
STUBS=/tmp/stubs \
  sh tools/build-apk/no-gradle-build.sh
```

`STUBS` is `tools/x11probe/gen_stubs.py`'s output. Only `ActivityMainBinding.kt` and
the three placeholder classes are used, and only to break a compile-order cycle: the
real binding is Java and references Kotlin classes, so kotlinc compiles against a
stub and javac replaces it afterwards. `compile_classes.sh` deletes the stub classes
and `audit_classes.py` **fails the build** if any class in the dex is not traceable
to a real source or a generated file — a surviving stub would replace real behaviour
with an `UnsupportedOperationException` shell and still pass every other check.

## The nine stages

1. **`fetch_deps.py`** — downloads the 76 artifacts in `deps.lock` and lays out
   `classes.jar`, each AAR's `R.txt`, each AAR's `res/`, and any `libs/*.jar` an AAR
   embeds (`emoji2-1.3.0.aar` ships its repackaged guava-retrying that way, with no
   Maven coordinate of its own).
2. **merged manifest** — `merged-manifest.xml` is checked in because it carries the
   *library* components only Gradle's merger knows about: WorkManager's 3 services
   and 6 receivers, the `androidx.startup` provider, `profileinstaller`, `room`,
   `window`'s `uses-library`. `mk_manifest.py` re-derives it from any Gradle-built
   APK (`aapt2 dump xmltree` + `dump resources`); the driver only rewrites the two
   version attributes from `app/build.gradle.kts`.
3. **`aapt2 compile`** — the app's `res/` plus all 45 library `res/` trees.
4. **`aapt2 link`** — produces `base.apk` (manifest, resources, `resources.arsc`,
   assets) and `R.txt`, the final symbol table.
5. **R + binding generation** — see below; this is the part Gradle does not expose.
6. **`compile_classes.sh`** — javac R, kotlinc the app in two passes, javac the real
   binding, merge, audit.
7. **d8** — over every dependency jar plus the app classes.
8. **`package_apk.py`** — dex + harvested java resources into the linked base,
   preserving per-entry compression.
9. **align, sign, verify.**

## Five things that will bite you

**1. `styleable` collisions are merged, not resolved by priority.** `appcompat` and
`material` both declare `styleable/SearchView`, with 17 and 12 different attributes.
`aapt2 link` alone refuses this (`resource 'styleable/SearchView' has a conflicting
value`), which is what made an earlier attempt look impossible. Gradle's merger
emits the **union**: 29 attributes, and *both* packages' `R.styleable.SearchView`
get all 29 — confirmed by dumping the reference APK's own `R$styleable` fields.
`gen_r.py` reproduces that. Six of material's attributes (`searchPrefixText`,
`useDrawerArrowDrawable`, `animateNavigationIcon`, `animateMenuItems`,
`autoShowKeyboard`, `backHandlingEnabled`) are declared *only inside* that block, so
deleting the block to dodge the conflict silently removes them from the resource
table — material's `R$attr` comes out 6 fields short.

**2. `android.nonTransitiveRClass=true` means ~59 real R classes.** Each library's
bytecode reads its *own* R (`androidx.appcompat.R.styleable.AppCompatTextView`), and
`aapt2 link --java` only ever generates one R for the app package. Without the
per-library classes the app dies with `NoClassDefFoundError` on first inflate.
Field *sets* come from each AAR's `R.txt`; field *values* come from the final table —
and styleable arrays and their index constants must be derived from the **final**
array, not the AAR's. That is why the reference APK's `constraintlayout` R carries
`AppCompatTheme_actionModeTheme`, an attribute that did not exist when
constraintlayout 2.0.1 was published.

**3. `aapt2 link --version-code` does not override the manifest.** If the manifest
already carries `android:versionCode`, the flag is ignored and you silently ship a
stale version — which is how the root APK ended up three releases behind. The
driver rewrites both attributes from `app/build.gradle.kts`.

**4. build-tools' d8 cannot dex appcompat 1.7.0.** `d8` 8.2.2 (build-tools 34) dies
with an internal `NullPointerException` on `ActionBarDrawerToggle$1`, reproducible on
that class alone. AGP never hits it because it uses the D8 bundled in the plugin, not
build-tools'. Use an `com.android.tools:r8` jar ≥ 8.5 (the reference APK's
`app-metadata.properties` says AGP 8.5.2).

**5. Dependencies are not what a POM walk says.** `deps.lock` pins three versions
that Gradle's resolution and the published metadata disagree about, each confirmed by
matching an artifact's class set against the reference APK's dex:

- `room` **2.5.0** — `work-runtime:2.9.1` asks for 2.5.0; 2.6.1 adds 9 classes the
  reference APK does not contain.
- `loader` **1.0.0** — 1.1.0 dropped `ModernAsyncTask$AsyncTaskResult`,
  `$InternalHandler` and `$WorkerRunnable`, which the reference APK does contain.
- `collection` **1.1.0** — `fragment:1.8.3` is the highest declarer. 1.4.0 would add
  122 classes and drop `MapCollections`, which the reference APK contains.

Also: `lifecycle-runtime` and `lifecycle-viewmodel` 2.8.6 are **empty KMP anchors**;
their code is in `lifecycle-runtime-android` / `lifecycle-viewmodel-android`, chosen
via Gradle module metadata that a POM walk cannot see. Fetching the anchors gives you
a build with no `ViewModel` class in it.

And `META-INF/services/kotlinx.coroutines.internal.MainDispatcherFactory` must reach
the APK. It is a plain text file inside a dependency jar, so it is easy to drop when
harvesting "just the classes" — without it `Dispatchers.Main` throws
*"Module with the Main dispatcher is missing"* the first time anything touches a
`lifecycleScope`.

## How this build was validated (no device available)

There is no emulator or device in the sandbox, so nothing here has been *run*. What
was checked, all statically, against the Gradle-built reference APK:

- **Class closure** — all 8,100 classes the reference APK defines are accounted for:
  7,079 from the locked jars, 251 generated R classes, 705 D8 synthetics
  (`$$ExternalSyntheticLambda*`), 69 from the app's own Kotlin sources. **0
  unaccounted.**
- **R field sets** — of the reference APK's 191 R classes, **179 library R classes
  match field-for-field** and `com.emux.R` matches exactly across all 10 types
  (73 fields).
- **`verify_apk.sh`** — 50 PASS / 0 FAIL: identity, signature digest vs
  `debug-keystore/emux-debug.jks`, all 34 assets byte-identical to the working tree,
  guest-script rice/autostart stamps, the `pcmanfm` desktop-items repair, `doctor`,
  dex method proxies, multidex.
- **Entry set** — 969 entries vs the reference's 970. Differences: dex is split into
  2 files instead of 8 (cosmetic — min-api 24 has native multidex), and
  `META-INF/com/android/build/gradle/app-metadata.properties` is deliberately not
  fabricated.

A Gradle build remains the reference path. This one exists so the APK can never again
be three releases behind the source that fixes the desktop.
