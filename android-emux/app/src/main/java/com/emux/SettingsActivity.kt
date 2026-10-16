package com.emux

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat

class SettingsActivity : AppCompatActivity() {

    // Theme hardening: PreferenceFragmentCompat requires the host theme to define
    // preferenceTheme. If a future refactor swaps in a non-Material3 parent (or the
    // OEM dark-mode overlay resolves differently), inflating preferences would throw
    // "Cannot resolve attribute preferenceTheme" and crash the moment Settings opens.
    // Setting it explicitly here makes that class of crash impossible.
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(newBase)
        setTheme(R.style.EmuX_Prefs)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // IMPORTANT: PreferenceFragmentCompat MUST be committed via the *support*
        // fragment manager (getSupportFragmentManager()), not the framework one —
        // using android.app.FragmentManager throws IllegalArgumentException and
        // crashed the app the moment Settings was opened.
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, Prefs())
            .commit()
    }

    class Prefs : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
        }
    }
}
