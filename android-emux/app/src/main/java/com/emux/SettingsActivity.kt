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
// v0.2.80: turn the pipe the device ACTUALLY measured (persisted by the capture
// loop every 25 refreshes) into a per-option fps prediction, so "which quality
// should I pick?" stops being a guess. rate = KB/s / bytes-per-frame, and bytes
// per frame = long * short * 2 at depth 16 with the pane's own aspect.
private fun qualityPrediction(ctx: android.content.Context): String {
    val sp = androidx.preference.PreferenceManager.getDefaultSharedPreferences(ctx)
    val kb = sp.getString("capture_kbps", null)?.toLongOrNull() ?: return ""
    val geom = sp.getString("capture_geom", null) ?: return ""
    val parts = geom.split("x")
    val w = parts.getOrNull(0)?.toIntOrNull() ?: return ""
    val h = parts.getOrNull(1)?.toIntOrNull() ?: return ""
    if (kb <= 0 || w <= 0 || h <= 0) return ""
    val a = w.toFloat() / h.toFloat()
    val sb = StringBuilder("\nYour pipe measured ${kb} KB/s: ")
    for (L in listOf(1280, 960, 720, 540)) {
        val short = ((if (a >= 1f) L / a else L * a).toInt() / 2 * 2).coerceAtLeast(2)
        sb.append("${L}px\u2248%.1ffps ".format(kb * 1024f / (L * short * 2f)))
    }
    return sb.toString()
}

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
                    row("Linux distribution", sp.getString("distro", "alpine") ?: "alpine")
                    row("Window manager", sp.getString("wm", "openbox") ?: "openbox")
                    row("Root acceleration", if (sp.getBoolean("root_accel", true)) "ON" else "OFF")
                    row("Desktop capture quality",
                        (sp.getString("capture_quality", "1280") ?: "1280") + " px long side" +
                            qualityPrediction(this@SettingsActivity))
                    row("Keep-awake burst", if (sp.getBoolean("keepawake_burst", true)) "ON" else "OFF")
                    row("Mouse / trackpad scroll speed",
                        (sp.getString("mouse_scroll_scale", "3") ?: "3") + " lines per notch")
                    row("Natural scrolling (mouse)",
                        if (sp.getBoolean("mouse_natural_scroll", false)) "ON" else "OFF")
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
            // v0.1.53: manual dpkg/apt repair (same pass as app-start hygiene).
            findPreference<androidx.preference.Preference>("repair_apt")
                ?.setOnPreferenceClickListener {
                    val app = requireContext().applicationContext
                    android.widget.Toast.makeText(app, R.string.repair_started, android.widget.Toast.LENGTH_LONG).show()
                    com.emux.core.BootstrapManager(app).startupRepairAsync(force = true)
                    true
                }
            // v0.2.79: capture quality only takes effect when Xvfb starts, so
            // give the user the restart instead of making them hunt for one.
            // The flag is consumed by MainActivity.onResume - Settings must not
            // tear down a session it does not own.
            // v0.2.80: the XML tier's SimpleSummaryProvider only echoes the chosen
            // entry; append what each option would do on THIS device's pipe.
            findPreference<androidx.preference.ListPreference>("capture_quality")
                ?.let { it.summary = (it.summary?.toString() ?: "") + qualityPrediction(requireContext()) }
            findPreference<androidx.preference.Preference>("restart_desktop")
                ?.setOnPreferenceClickListener {
                    com.emux.x11.DesktopService.restartDesktopRequested = true
                    android.widget.Toast.makeText(requireContext().applicationContext,
                        "Restarting the desktop\u2026 (open windows will close)",
                        android.widget.Toast.LENGTH_LONG).show()
                    activity?.onBackPressedDispatcher?.onBackPressed()
                    true
                }
        }
    }

    /** Same four settings, built programmatically — cannot fail on resources. */
    class CodePrefs : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            val ctx = requireContext()
            val screen = androidx.preference.PreferenceManager(ctx)
                .createPreferenceScreen(ctx)
            // v0.1.47: distro selector (mirrors res/xml/preferences.xml)
            screen.addPreference(androidx.preference.ListPreference(ctx).apply {
                key = "distro"; title = "Linux distribution"
                summary = "Distro to install/run; they coexist on disk and the selected one is active"
                entries = arrayOf(
                    "Alpine 3.20 — tiny & fast (~4 MB, apk)",
                    "Debian 12 bookworm — full-fledged (~40 MB, apt)",
                    "Ubuntu 24.04 LTS — most software (~61 MB, apt)",
                    "Arch Linux ARM — rolling release (~138 MB, pacman)",
                    "Fedora 40 — bleeding-edge (~30 MB, dnf)")
                entryValues = arrayOf("alpine", "debian", "ubuntu", "arch", "fedora")
                setDefaultValue("alpine")
            })
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
            // v0.1.83: TERMINAL-ONLY physical keyboard layout. ROM shortcuts
            // that switch an external keyboard's layout never reach this app
            // on several devices (Huawei-class), so the terminal gets its own
            // positional ES/EN symbol table + dead-key composition. The
            // Desktop tab is deliberately untouched (real-laptop feel).
            screen.addPreference(androidx.preference.ListPreference(ctx).apply {
                key = "term_keylayout"; title = "Terminal physical keyboard layout"
                summary = "Terminal tab only. Auto = use what Android reports; " +
                    "Spanish (ISO) maps the symbol keys positionally (ñ ´ ¨ ¡ ¿ º ç ...) " +
                    "and composes dead-key accents; English = US as reported."
                entries = arrayOf("Auto (Android layout)", "Spanish (ISO)", "English (US)")
                entryValues = arrayOf("auto", "es", "en")
                setDefaultValue("auto")
                summaryProvider = androidx.preference.ListPreference.SimpleSummaryProvider.getInstance()
            })
            // v0.2.79: capture quality + the restart that applies it. Mirrors
            // res/xml/preferences.xml, which is the tier that normally runs.
            screen.addPreference(androidx.preference.ListPreference(ctx).apply {
                key = "capture_quality"; title = "Desktop capture quality (frame rate)"
                summary = "Lower resolution = fewer bytes per frame = a higher frame rate, at " +
                    "the cost of softer text. The desktop pane shows the live rate so you can " +
                    "tune it against your own device. Applies when the desktop next starts." +
                    qualityPrediction(ctx)
                entries = arrayOf(
                    "Sharp \u2014 1280 px (default, unchanged; ~1.5 MB per frame)",
                    "Balanced \u2014 960 px (~1.8\u00d7 the frame rate, ~0.8 MB)",
                    "Fast \u2014 720 px (~3.2\u00d7 the frame rate, ~0.5 MB)",
                    "Smooth \u2014 540 px (~5.6\u00d7 the frame rate, ~0.26 MB; the desktop gets small)")
                entryValues = arrayOf("1280", "960", "720", "540")
                setDefaultValue(com.emux.x11.DesktopService.DEFAULT_CAPTURE_LONG_SIDE.toString())
            })
            screen.addPreference(androidx.preference.Preference(ctx).apply {
                key = "restart_desktop"; title = "Restart desktop now"
                summary = "Applies the capture quality immediately. Restarting ends the X " +
                    "session, so open windows are lost - the Linux terminal is not affected."
                setOnPreferenceClickListener {
                    com.emux.x11.DesktopService.restartDesktopRequested = true
                    android.widget.Toast.makeText(ctx.applicationContext,
                        "Restarting the desktop\u2026 (open windows will close)",
                        android.widget.Toast.LENGTH_LONG).show()
                    activity?.onBackPressedDispatcher?.onBackPressed()
                    true
                }
            })
            screen.addPreference(androidx.preference.SwitchPreferenceCompat(ctx).apply {
                key = "keepawake_burst"; title = "Keep-awake during activity"
                summary = "Hold wake lock briefly after typing; auto-expire when idle"
                setDefaultValue(true)
            })
            // v0.2.85: external mouse / trackpad (mirrors res/xml/preferences.xml).
            screen.addPreference(androidx.preference.ListPreference(ctx).apply {
                key = "mouse_scroll_scale"; title = "Mouse / trackpad scroll speed"
                summary = "How far one wheel notch or two-finger flick scrolls. " +
                    "Trackpad deltas are fractional, so the pane accumulates the " +
                    "remainder and emits whole scroll steps."
                entries = arrayOf(
                    "Precise \u2014 1 line per notch",
                    "Normal \u2014 3 lines (default)",
                    "Fast \u2014 5 lines",
                    "Very fast \u2014 9 lines")
                entryValues = arrayOf("1", "3", "5", "9")
                setDefaultValue("3")
                summaryProvider = androidx.preference.ListPreference.SimpleSummaryProvider.getInstance()
            })
            screen.addPreference(androidx.preference.SwitchPreferenceCompat(ctx).apply {
                key = "mouse_natural_scroll"; title = "Natural scrolling (mouse / trackpad)"
                summary = "On: content follows the two fingers / wheel like a touchscreen. " +
                    "Off: classic wheel direction. External pointer only - the on-screen " +
                    "two-finger gesture is unchanged."
                setDefaultValue(false)
            })
            // v0.1.53: manual dpkg/apt repair (programmatic screen mirror).
            screen.addPreference(androidx.preference.Preference(ctx).apply {
                key = "repair_apt"; title = "Repair package manager (dpkg/apt)"
                summary = "Fixes interrupted-dpkg / status-old backup errors inside the active distro; runs in the background"
                setOnPreferenceClickListener {
                    val app = ctx.applicationContext
                    android.widget.Toast.makeText(app, "Repair started in the background — retry your apt command in the terminal", android.widget.Toast.LENGTH_LONG).show()
                    com.emux.core.BootstrapManager(app).startupRepairAsync(force = true)
                    true
                }
            })
            screen.addPreference(androidx.preference.EditTextPreference(ctx).apply {
                key = "mirror"; title = "Mirror / custom rootfs URL"
                setDefaultValue(com.emux.core.BootstrapManager.MIRROR)
                // dialogTitle must be set explicitly: EditTextPreference's default
                // title resolution walks the preference *parent* chain, and a
                // programmatically created root PreferenceScreen has no context
                // theme attached — leaving it null used to crash the dialog open.
                dialogTitle = "Mirror / custom rootfs URL"
                // v0.2.82 CRASH FIX (field HUAWEI MRDI-W09, API 31): this read
                // `preferenceManager.sharedPreferences`, and PreferenceFragment-
                // Compat's manager is NULL while onCreatePreferences runs on this
                // androidx version - the NPE report names getSharedPreferences()
                // on a null PreferenceManager. CodePrefs is the FALLBACK tier:
                // it only runs when the XML tier already failed on the device,
                // so a landmine here turns "settings look plain" into "app keeps
                // stopping", and it did. The static default-prefs call has no
                // such receiver and returns the same file.
                text = androidx.preference.PreferenceManager
                    .getDefaultSharedPreferences(ctx)
                    .getString("mirror", com.emux.core.BootstrapManager.MIRROR)
                    ?: com.emux.core.BootstrapManager.MIRROR
            })
            preferenceScreen = screen
        }
    }
}
