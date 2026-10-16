package com.emux

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat

class SettingsActivity : AppCompatActivity() {

    // Theme hardening: PreferenceFragmentCompat requires the host theme to define
    // preferenceTheme, applied BEFORE fragment inflation. setTheme() at the very top
    // of onCreate (before super) is the reliable place; attachBaseContext() ordering
    // vs AppCompat's own theme resolution was the suspected crash source in v0.1.1.
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.EmuX_Prefs)
        super.onCreate(savedInstanceState)
        // IMPORTANT: PreferenceFragmentCompat MUST be committed via the *support*
        // fragment manager; the framework one throws IllegalArgumentException and
        // crashed the moment Settings opened. Guarded anyway so any residual OEM
        // inflation failure shows a readable message instead of crashing.
        try {
            if (savedInstanceState == null) {
                supportFragmentManager.beginTransaction()
                    .replace(android.R.id.content, Prefs())
                    .commit()
            }
        } catch (e: Throwable) {
            setContentView(android.widget.TextView(this).apply {
                text = "Settings failed to load:\n${e.javaClass.name}: ${e.message}"
                setPadding(48, 48, 48, 48)
                setTextColor(0xFFE8E8E8.toInt())
            })
        }
    }

    class Prefs : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
        }
    }
}
