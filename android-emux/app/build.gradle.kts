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
        targetSdk = 34
        versionCode = 27
        versionName = "0.1.27"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
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
