package com.emux

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat

/**
 * v0.1.6 CRASH FIX #3 (the one Robolectric could never see):
 * AppCompatActivity.onApplyThemeResource() reads `themeResId` from the
 * activity's OWN <activity> manifest entry. Our manifest declared no theme for
 * SettingsActivity, so themeResId == 0 → AppCompat threw
 * IllegalArgumentException("You need to use a Theme.AppCompat theme (or
 * descendant) with this activity.") inside setContentView()/fragment inflation.
 * That exception escaped AFTER our onCreate try/catch had finished (it fires
 * during the FIRST actual theme resolution, not during setTheme()), so every
 * guard we added in 0.1.4/0.1.5 was useless on real devices — opening Settings
 * still force-closed. JVM tests pass because Robolectric's resource layer
 * tolerates themeResId==0; phones do not. Fix: apply the AppCompat-based prefs
 * theme at attachBaseContext time, when AppCompat's delegate records it.
 */
class SettingsActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        try {
            super.attachBaseContext(newBase)
            setTheme(R.style.EmuX_Prefs)   // AppCompat delegates resolve theme lazily → OK here
        } catch (t: Throwable) {
            com.emux.EmuXLog.e("attachBaseContext theme failed", t)
        }
    }

    // Theme hardening: PreferenceFragmentCompat requires the host theme to define
    // preferenceTheme, applied BEFORE fragment inflation. setTheme() at the very top
    // of onCreate (before super) is the reliable place; attachBaseContext() ordering
    // vs AppCompat's own theme resolution was the suspected crash source in v0.1.1.
    override fun onCreate(savedInstanceState: Bundle?) {
        // v0.1.5 CRASH FIX — two verified defects in the previous code:
        //
        // (1) The commit()/catch ordering was wrong. FragmentTransaction.commit()
        //     is ASYNCHRONOUS: it only schedules the transaction on the main
        //     looper, so it never throws here and the try/catch below could not
        //     protect anything. The actual inflation of preferences.xml happens
        //     later on the looper — where no handler existed — so ANY resource /
        //     theme problem propagated straight out and killed the process the
        //     instant Settings opened ("app keeps stopping"). Now we execute the
        //     transaction synchronously inside the try block, so failures land
        //     in the readable fallback screen instead of crashing.
        //
        // (2) Material3 + preferenceTheme@PreferenceThemeOverlay (a Material2
        //     overlay) is a known attribute-resolution crash combo on some
        //     devices. We now host the prefs in a plain layout container with an
        //     explicit id (android.R.id.content can also collide with restored
        //     fragments), and fall back to a programmatic PreferenceScreen built
        //     entirely in code if XML inflation fails for any reason.
        setTheme(R.style.EmuX_Prefs)
        super.onCreate(savedInstanceState)
        val containerId = 0x5E000001
        setContentView(android.widget.FrameLayout(this).apply {
            id = containerId
            setBackgroundColor(0xFF101216.toInt())
        })
        var err: Throwable? = null
        if (savedInstanceState == null) {
            try {
                supportFragmentManager.beginTransaction()
                    .replace(containerId, Prefs())
                    .commitNow()          // synchronous → exception lands in our catch
            } catch (t: Throwable) {
                err = t
                com.emux.EmuXLog.e("Prefs XML inflation failed", t)
            }
        }
        if (err != null || savedInstanceState != null && findFragment(containerId) == null) {
            try {
                supportFragmentManager.beginTransaction()
                    .replace(containerId, CodePrefs())
                    .commitNow()
            } catch (t2: Throwable) {
                err = t2
                com.emux.EmuXLog.e("CodePrefs also failed", t2)
            }
        }
        // Absolute last resort: PreferenceFragmentCompat itself needs a valid
        // preferenceTheme; if even that resolution fails on some ROM, render the
        // four settings with plain widgets — opening Settings can NEVER crash now.
        if (err != null && findFragment(containerId) == null) {
            setContentView(android.widget.ScrollView(this).apply {
                addView(android.widget.LinearLayout(this@SettingsActivity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(48, 48, 48, 48)
                    fun row(label: String, value: String) {
                        addView(android.widget.TextView(this@SettingsActivity).apply {
                            text = "$label\n$value"
                            setTextColor(0xFFE8E8E8.toInt()); textSize = 16f
                            setPadding(0, 24, 0, 24)
                        })
                    }
                    val sp = androidx.preference.PreferenceManager
                        .getDefaultSharedPreferences(this@SettingsActivity)
                    row("Window manager", sp.getString("wm", "openbox") ?: "openbox")
                    row("Root acceleration", if (sp.getBoolean("root_accel", true)) "ON" else "OFF")
                    row("Keep-awake burst", if (sp.getBoolean("keepawake_burst", true)) "ON" else "OFF")
                    row("Mirror", sp.getString("mirror", com.emux.core.BootstrapManager.MIRROR) ?: "")
                    addView(android.widget.TextView(this@SettingsActivity).apply {
                        text = "\n(fallback view — detail error: ${err?.javaClass?.name}: ${err?.message})"
                        setTextColor(0xFF8A93A0.toInt()); setTextIsSelectable(true)
                    })
                })
            })
        }
    }

    private fun findFragment(id: Int) = supportFragmentManager.findFragmentById(id)

    class Prefs : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
        }
    }

    /** Same four settings, built programmatically — cannot fail on resources. */
    class CodePrefs : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()
            val screen = androidx.preference.PreferenceManager(ctx)
                .createPreferenceScreen(ctx)
            screen.addPreference(androidx.preference.ListPreference(ctx).apply {
                key = "wm"; title = "Window manager"
                entries = arrayOf("Openbox", "IceWM", "Fluxbox", "Awesome")
                entryValues = arrayOf("openbox", "icewm", "fluxbox", "awesome")
                setDefaultValue("openbox")
                summaryProvider = androidx.preference.ListPreference.SimpleSummaryProvider.getInstance()
            })
            screen.addPreference(androidx.preference.SwitchPreferenceCompat(ctx).apply {
                key = "root_accel"; title = "Root acceleration (chroot)"
                summary = "Use su/Magisk for real chroot when available"
                setDefaultValue(true)
            })
            screen.addPreference(androidx.preference.SwitchPreferenceCompat(ctx).apply {
                key = "keepawake_burst"; title = "Keep-awake during activity"
                summary = "Hold wake lock briefly after typing; auto-expire when idle"
                setDefaultValue(true)
            })
            screen.addPreference(androidx.preference.EditTextPreference(ctx).apply {
                key = "mirror"; title = "Alpine mirror base URL"
                setDefaultValue(com.emux.core.BootstrapManager.MIRROR)
                // dialogTitle must be set explicitly: EditTextPreference's default
                // title resolution walks the preference *parent* chain, and a
                // programmatically created root PreferenceScreen has no context
                // theme attached — leaving it null used to crash the dialog open.
                dialogTitle = "Alpine mirror base URL"
                text = preferenceManager.sharedPreferences
                    ?.getString("mirror", com.emux.core.BootstrapManager.MIRROR) ?: com.emux.core.BootstrapManager.MIRROR
            })
            preferenceScreen = screen
        }
    }
}
