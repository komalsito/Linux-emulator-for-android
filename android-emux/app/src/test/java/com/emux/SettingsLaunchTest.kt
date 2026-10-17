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
 * Regression tests for the user-reported crashes (v0.1.4 field report):
 *  1. opening Settings killed the app
 *  2. tapping "Install Linux" killed the app
 * Robolectric runs the REAL activities on the JVM; any Throwable escaping
 * onCreate/onStart fails the test exactly like a device crash would.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SettingsLaunchTest {

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
