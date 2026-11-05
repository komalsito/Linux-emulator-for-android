plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.emux"

    // v0.2.88 BUILD FIX: compileSdk was never specified in this repo's
    // build file, and AGP 8.5.2 hard-fails project configuration without it
    // ("compileSdkVersion is not specified", manual run #3). 34 is the
    // correct level: every dependency in use requires it (androidx.core
    // 1.13.x, appcompat 1.7.0, fragment 1.8.x) and AGP 8.5.x officially
    // supports compileSdk 34.
    compileSdk = 34

    // No NDK/C++ build here: EmuX runs a *pure-Android* terminal core
    // (com.termux:terminal-emulator + com.termux:view) — same engine Termux uses,
    // so PTY handling and rendering cost the same as Termux.
    defaultConfig {
        applicationId = "com.emux"
        minSdk = 24
        // v0.1.28 ARCHITECTURE FIX (the root cause behind "keyboard dead" and
        // "distro never runs"): Android 10+ denies execve()/mmap(PROT_EXEC) of
        // ANY file in app-private storage for apps targeting SDK 29+ (W^X —
        // SELinux untrusted_app domain; see developer.android.com/about/versions/
        // 10/behavior-changes-10 and termux-app issue #1072 — the exact reason
        // Termux ships targetSdk 28). With targetSdk 34 the whole Alpine rootfs
        // was inert ballast: every loader/busybox/apk/Xvfb exec died EACCES.
        // Targeting 28 restores exec of app-data files (untrusted_app_27
        // domain) on Android 7–15, making the musl-loader chain, apk, proot and
        // the X11 desktop actually runnable unrooted. Trade-off: not
        // distributable via Play Store — this app ships as a sideloaded APK
        // (adb install / GitHub Releases), same distribution model as Termux
        // on F-Droid.
        targetSdk = 28
        // v0.2.93 (vc193): ROUND-TRIP SERIALIZATION + REMOTE-ECHO PROBE.
        // Field (v0.2.92): proof stayed yellow, input felt SLOWER than the
        // previously fixed version, terminal typed double (2 glyphs visible,
        // 1 byte read by the shell).
        //  1. X11Client: requestReplyFull read packets until ITS sequence
        //     matched and queued everything else as events - two concurrent
        //     round-trips on ONE connection stole each other's replies. The
        //     capture-plane proof's QueryPointer raced the capture loop's
        //     ~10/s GetImage grabs on the native client and lost every time,
        //     so capInputOk was never set (proof=pending, input stuck on the
        //     per-event xdotool agent = the slowness). roundTripLock now
        //     serializes every socket read: one round-trip in flight, no
        //     cross-consumption. drain/collectDamage QUEUE non-damage packets
        //     instead of dropping them, so a probe's ButtonPress survives the
        //     capture drain.
        //  2. DesktopService: failed capture-plane proofs narrate themselves
        //     (read=... in the note history) and retry with exponential
        //     backoff (15 s .. 2 min) instead of every 15 s.
        //  3. VtTerminalView: the v0.2.80 bare-LF detector can flip
        //     noLineDiscipline on a REAL pty (local echo ON next to the tty's
        //     own echo = the double). New remote-echo probe: locally-echoed
        //     printable bytes are matched against incoming session bytes; two
        //     consecutive full matches prove the tty echoes, the matched copy
        //     is consumed and local echo turns off for the session.
        // No guest script change: ENV_VERSION stays 80, no re-seed needed.
        // v0.2.92 (vc192): CAPTURE-PLANE INPUT + RECONNECT VISIBILITY.
        // Field (18:15, v0.2.91): interaction worked for the first
        // seconds, then died - cursor moved (agent motion landed) but
        // no click reacted, while the badge read proof=pending
        // (yellow) forever: the control-plane reconnect kept failing
        // INVISIBLY, input rode the guest xdotool agent, and the
        // button-delivery proof + its rc.xml/WM-switch escalation
        // ladder (the proven click-swallow remedy) NEVER RAN - it only
        // ever ran over a control-plane connection. (1) X11Client now
        // reserves the reply sequence number and the write atomically
        // (a concurrent fake-input write could slip between
        // reservation and send and desync reply matching); (2) when
        // the control plane is down and frames flow, the app proves
        // XTEST over the CAPTURE client (the identity-guaranteed
        // server) and injects through it - and runs the button proof
        // + ladder there too; (3) every failed reconnect is counted
        // and named on the badge (rxfail=/ctrlErr=), proof=motion-ok
        // paints yellow (measuring) not red, and the INPUT line names
        // the capture plane when it carries input. No guest script
        // changed - ENV_VERSION stays 80; existing installs do not
        // re-seed.
        // v0.2.91 (vc191): SELF-AIMING PLANES. The v0.2.90 field round
        // showed the guest can be perfectly healthy (one Xvfb on :1,
        // openbox up, marker published) while the app sat on a dying :0
        // attempt forever: (1) a failed control-plane reconnect was
        // never retried, so input died for good; (2) the capture loop
        // only re-aimed when the 3 s rotation watcher moved displayNum;
        // (3) the 5 s display-mismatch check trusted /tmp/emux_cap_display,
        // hours stale with the legacy helper retired. Fixes: the native
        // capture loop re-reads /tmp/emux_display_num on EVERY reconnect;
        // the reconnect retries until connected; the 5 s watchdog re-aims
        // from the guest marker too (the loop proven to run - it ticks
        // the badge capage). With v0.2.90 identity guard above them,
        // every plane converges on the server the guest actually runs.
        // v0.2.90 (vc190): THE STALE-INPUT-SERVER FIX. Field (v0.2.89
        // tablet, force-stop + relaunch): the Xvfb attempt ladder leaked
        // live failed attempts on :0 (retire_own_xvfb "serving" keep-
        // checks preserve anything that listens), and the next session
        // input control plane connected to one of those zombies - green
        // badge, proof ok, fail=0, while every tap landed on a bare-root
        // server nobody streams; the real desktop on :1 never reacted
        // (pointer pinned at center, xdotool-verified). (1) emux-x11 now
        // retires a failed attempt unconditionally - a server the ladder
        // abandoned is a stray by definition; adoption before the ladder
        // is untouched. (2) X11Client exposes displayNum + resIdBase
        // (setup-reply resource-id-base, unique per server instance) and
        // DesktopService compares the input plane identity with the
        // capture plane every 5 s and inside every reconnect attempt -
        // a mismatch recycles the control plane onto the server the user
        // actually sees. ENV_VERSION 80 re-seeds the guest script.
        // v0.2.89 (vc189): (1) emux-x11 governor now self-heals a WM-less
        // desktop - it checks/installs openbox/xterm/x11-apps (was furniture-
        // only, single attempt) and relaunches a dead/never-started WM, so
        // windows regain decorations and tint2 lists tasks + a Terminal
        // launcher (field: fresh Ubuntu rootfs, HUAWEI MRDI-W09, no WM);
        // (2) the desktop helper key bar and its top-right "keys" toggle are
        // removed, and the terminal extra-keys row is removed app-wide
        // (field request; hardware-keyboard paths unchanged);
        // (3) verify_apk accepts aapt2's renamed minSdkVersion badging
        // field (the false FAIL on the v0.2.88 build).
        // ENV_VERSION 79 re-seeds the guest script on existing installs.
        // v0.2.88 (vc188): (1) emux-x11 furniture detection is argv-exact
        // now (running0) and tint2's launch is verified with a strike
        // fallback, so a first boot no longer eats its own taskbar to an
        // apt-install cmdline match; (2) DesktopView paints a "Linux desktop
        // is starting" card with the live diagnostic tail while frames
        // stream black (first-boot apt churn), dismissed by any touch.
        // ENV_VERSION 78 re-seeds the guest script on existing installs.
        // (3) Settings selector rows now carry explicit click listeners
        // opening the framework dialog directly - the v0.2.87
        // onDisplayPreferenceDialog override alone was still ripple-only on
        // the field ROM.
        // v0.2.87 (vc187): host-side fixes only - (1) TerminalService's manifest
        // foregroundServiceType now also declares dataSync, making the fallback
        // the FOREGROUND_SERVICE_DATA_SYNC permission has always promised legal
        // on API 29-33 (the v0.2.85 field log tail: "startForeground refused
        // type=1"); (2) Settings selectors open through a framework AlertDialog,
        // because the androidx DialogFragment + appcompat chain renders nothing
        // on the field HUAWEI ROM (click ripple, no selection UI). ENV_VERSION
        // stays 77: no guest script changed, so existing installs must not
        // re-seed.
        versionCode = 193
        versionName = "0.2.93"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    signingConfigs {
        // v0.1.47: COMMITTED debug keystore (ACTION_PLAN §6 pitfall: "if the
        // sandbox resets, the signature changes → devices need ONE adb
        // uninstall"). Every rebuild used to mint a fresh ephemeral
        // ~/.android/debug.keystore, so each release forced a reinstall on the
        // field device (wiping the rootfs). This is a DEBUG key, committed on
        // purpose — it grants nothing (sideloaded debug builds); keeping it in
        // the repo makes the APK signature stable across build environments.
        create("emuxDebug") {
            val ks = rootProject.file("debug-keystore/emux-debug.jks")
            if (ks.isFile) {
                storeFile = ks
                storePassword = "emuxdebug"
                keyAlias = "emux"
                keyPassword = "emuxdebug"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
            // Fall back to the auto-generated debug key when the committed
            // keystore is absent (fresh clone without debug-keystore/).
            val ks = rootProject.file("debug-keystore/emux-debug.jks")
            // v0.2.88 BUILD FIX: this read `signingConfig.getByName(...)` - the
            // BuildType's own Property<SigningConfig?> receiver, which has no
            // getByName. The named config lives in the signingConfigs CONTAINER
            // (the outer android{} receiver is in scope inside buildTypes{}).
            // The typo is pre-existing, but the v0.2.88 comment additions
            // changed the file hash and forced a fresh Kotlin-DSL compile,
            // which surfaced it (manual run #2: script compilation error,
            // 1m31s, no APK).
            if (ks.isFile) signingConfig = signingConfigs.getByName("emuxDebug")
        }
    }

    packaging {
        jniLibs {
            // Keep .so files compressed-in-APK but mmap'd; do NOT extract → faster boot, less storage churn.
            useLegacyPackaging = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.fragment:fragment-ktx:1.8.3")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-service:2.8.6")
    implementation("androidx.work:work-runtime-ktx:2.9.1")   // battery-aware deferred bootstrap work
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // v0.1.47 multi-distro: pure-Java XZ/LZMA2 decoder (0BSD) — the Debian and
    // Ubuntu catalog images are .tar.xz, and host xz(1)/tar -J availability on
    // Android 7–15 ROMs is inconsistent. Host tooling is still tried first;
    // this is the guaranteed fallback inside extractTarXz().
    implementation("org.tukaani:xz:1.10")

    // ---- Terminal engine: EmuX in-house VT100/ANSI parser + damage-only renderer
    // (see com.emux.terminal.VtTerminalView). Same PTY strategy as Termux
    // (Android's app_process + Linux pseudo-terminal driver) — zero native code,
    // zero extra deps, Termux-grade power efficiency.

    // Optional: root helper binary (see docs/ROOT.md). Ships nothing privileged itself.
    // implementation(files("libs/magisk-su-bridge.aar"))
}

configurations.all {
    resolutionStrategy.force("org.robolectric:android-all:14-robolectric-10818077")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.12.2")
}
