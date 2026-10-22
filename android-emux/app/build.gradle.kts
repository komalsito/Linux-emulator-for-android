plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.emux"
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
        versionCode = 75
        versionName = "0.1.75"

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
    // Android 7-15 ROMs is inconsistent. Host tooling is still tried first;
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
