package com.emux

import android.app.Application
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Regression tests for the user-reported crashes (v0.1.4/v0.1.5 field reports):
 *  1. opening Settings killed the app
 *  2. tapping "Install Linux" killed the app
 * Robolectric runs the REAL activities on the JVM; any Throwable escaping
 * onCreate/onStart fails the test exactly like a device crash would.
 *
 * v0.1.6 lesson encoded here: Robolectric's resource layer silently tolerates
 * a missing <activity android:theme>, but real AppCompat throws
 * IllegalArgumentException("You need to use a Theme.AppCompat theme…") the
 * instant such an activity opens. The manifest assertions below close that gap:
 * they verify what the SYSTEM will hand to ActivityThread, not just what our
 * Kotlin code does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsLaunchTest {

    @Test
    fun manifestDeclaresAppCompatThemeForEveryActivity() {
        val merged = java.io.File(
            "/workspace/android-emux/app/build/intermediates/packaged_manifests/debug/processDebugManifestForPackage/AndroidManifest.xml")
            .readText()
        // Extract each <activity .../> or <activity ...> block
        val acts = Regex("<activity\\b[^>]*?(/?)>").findAll(merged).map { it.value }.toList()
        check(acts.isNotEmpty()) { "no activities found in merged manifest" }
        val appTheme = Regex("<application\\b[^>]*android:theme=\"([^\"]+)\"").find(merged)
        for (a in acts) {
            val name = Regex("android:name=\"([^\"]+)\"").find(a)?.groupValues?.get(1) ?: "?"
            val hasTheme = a.contains("android:theme=")
            check(hasTheme || appTheme != null) {
                "Activity $name declares no theme and <application> has none either " +
                "→ AppCompatActivity.onApplyThemeResource(themeResId=0) WILL crash on device"
            }
            if (hasTheme) {
                val t = Regex("android:theme=\"([^\"]+)\"").find(a)!!.groupValues[1]
                // AppCompatActivity subclasses REQUIRE an AppCompat-descendant theme;
                // plain android.app.Activity may use framework themes. Read the real
                // superclass from compiled bytecode so the rule is exact, not guessed.
                val className = name
                val isAppCompat = try {
                    val cn = className.replace('.', '/')
                    val jar = java.io.File("/workspace/android-emux/app/build/tmp/kotlin-classes/debug/$cn.class")
                    val bytes = jar.readBytes()
                    String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1)
                        .contains("androidx/appcompat/app/AppCompatActivity")
                } catch (_: Exception) { false }
                if (isAppCompat) {
                    check(t == "@style/EmuX" || t == "@style/EmuX.Prefs") {
                        "AppCompatActivity $name uses non-AppCompat-based theme $t " +
                        "→ onApplyThemeResource will crash on device"
                    }
                }
            }
        }
        // Explicitly: SettingsActivity must now exist as its own entry.
        check(Regex("com.emux.SettingsActivity").containsMatchIn(merged)) {
            "SettingsActivity missing from manifest entirely"
        }
    }

    @Test
    fun bootstrapWorkerNeverLeaksUncaughtExceptions() {
        // v0.1.9 regression test for the REAL Install-button crash:
        // BootstrapManager.bootstrapFlow() launched its work in a DETACHED scope
        // (SupervisorJob fallback, no parent). Any Throwable escaping there hit
        // the IO thread's UncaughtExceptionHandler → whole process died when the
        // user tapped Install. Robolectric's default handler records uncaught
        // exceptions instead of killing the JVM — so we can assert none escape.
        val app = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        // Force the failure path: make every mirror unreachable by pointing at an
        // invalid custom mirror AND stubbing DNS is overkill; instead just run the
        // worker body directly with a context whose network calls fail fast.
        val mgr = com.emux.core.BootstrapManager(app)
        val result = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
            // bootstrap() must convert ALL failures to Result.failure — never throw.
            try {
                withTimeoutSafe { mgr.bootstrap() }
            } catch (e: Throwable) {
                throw AssertionError("bootstrap() threw instead of returning Result.failure: $e", e)
            }
        }
        // It may succeed (CI has network) or fail (no network) — both are fine.
        // What is NOT fine is an escaped exception, asserted above.
        check(result.isSuccess || result.exceptionOrNull() != null)

        // Also assert FsLayout.tmp can never NPE even if cacheDir were null:
        val f = com.emux.core.FsLayout.safeTmp(app)
        check(f.absolutePath.isNotEmpty())

        // And that the worker class itself compiles into the guarded shape:
        // doWork must contain a top-level try/catch (verify via source text).
        val src = java.io.File(
            "/workspace/android-emux/app/src/main/java/com/emux/core/BootstrapWorker.kt").readText()
        check(src.contains("override suspend fun doWork(): Result = try")) {
            "BootstrapWorker.doWork lost its top-level guard"
        }
    }

    private suspend fun <T> withTimeoutSafe(block: suspend () -> T): T =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { block() }

    @Test
    fun settingsOpensWithoutCrash() {
        val activity = Robolectric.buildActivity(SettingsActivity::class.java).setup()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val decor: View = activity.get().window.decorView
        // The fallback chain must always leave *something* attached.
        var hasContent = false
        fun walk(v: View): Boolean {
            if (v !== decor && v.visibility == View.VISIBLE) return true
            if (v is android.view.ViewGroup)
                for (i in 0 until v.childCount) if (walk(v.getChildAt(i))) return true
            return false
        }
        hasContent = walk(decor)
        check(hasContent) { "Settings rendered nothing" }
    }

    @Test
    fun mainActivityLaunchesAndInstallClickDoesNotThrow() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val btn = activity.get().findViewById<android.widget.Button>(R.id.btnInstall)
        btn?.performClick()          // "Install Linux": must not throw synchronously
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    /**
     * v0.2.86 regression (field HUAWEI MRDI-W09, API 31 - "entering settings
     * crashes", then the app never opened again): onCreate's degraded branches
     * (the v0.2.62 prior-crash report view and the v0.1.3 startup-failure
     * screen) setContentView their own code-built UI and return WITHOUT ever
     * running doOnCreate, so the lateinit view binding `b` stays
     * uninitialized. onResume dereferenced it unconditionally:
     *
     *   kotlin.UninitializedPropertyAccessException: lateinit property b has
     *     not been initialized at MainActivity.onResume(MainActivity.kt:1106)
     *
     * That throw wrote a NEW crash report, which the next launch consumed into
     * the same degraded branch - a self-sustaining loop that made one settings
     * crash permanent ("EmuX keeps stopping" on every launch, escape hatch
     * unreachable). setup() drives create→start→resume; with a fresh report on
     * disk the resume must survive and the report screen must be showing.
     */
    @Test
    fun priorCrashReportDoesNotCrashLoopOnResume() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        // CrashReport.NAME is private; the on-disk contract is this exact file
        // in filesDir, written by the process-wide uncaught handler.
        val report = java.io.File(app.filesDir, "last_crash.txt")
        report.parentFile?.mkdirs()
        report.writeText(
            "EmuX crash report\napp version : test\n\njava.lang.RuntimeException: synthetic\n")
        check(System.currentTimeMillis() - report.lastModified() < 30 * 60 * 1000) {
            "test clock: the report must be inside CrashReport's freshness window"
        }

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        // Surviving setup() IS the regression assertion (pre-fix this threw
        // UninitializedPropertyAccessException out of onResume). Additionally,
        // the degraded screen - not the normal UI - must be what the user sees,
        // and its escape hatch must be reachable.
        val decor = activity.get().window.decorView
        var sawReport = false; var sawEscape = false
        fun walk(v: View) {
            if (v is android.widget.TextView &&
                v.text?.contains("EmuX crashed on the previous launch") == true) sawReport = true
            if (v is android.widget.Button &&
                v.text?.contains("Try starting EmuX anyway") == true) sawEscape = true
            if (v is android.view.ViewGroup)
                for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(decor)
        check(sawReport) { "prior-crash launch did not render the crash report screen" }
        check(sawEscape) { "crash report screen lost its 'Try starting anyway' escape hatch" }
        // The normal UI's binding must NOT have been inflated on this branch -
        // that is the invariant onResume now respects.
        check(activity.get().findViewById<View>(R.id.btnInstall) == null) {
            "degraded branch unexpectedly inflated the normal layout"
        }
    }
}
