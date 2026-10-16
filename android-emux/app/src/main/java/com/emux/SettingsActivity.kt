package com.emux

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceFragmentCompat

class SettingsActivity : AppCompatActivity() {
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
