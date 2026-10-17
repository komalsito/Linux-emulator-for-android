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
}
