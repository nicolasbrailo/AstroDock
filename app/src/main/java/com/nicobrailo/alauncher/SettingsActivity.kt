package com.nicobrailo.alauncher

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat

// Edits the settings in Settings.kt. SlideshowActivity picks up changes when it
// comes back to the foreground.
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.settings_title)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)

            findPreference<EditTextPreference>(Settings.KEY_SERVER_URL)?.setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }

            findPreference<EditTextPreference>(Settings.KEY_API_KEY)?.apply {
                setOnBindEditTextListener {
                    it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                setSummaryProvider { pref ->
                    val set = !(pref as EditTextPreference).text.isNullOrBlank()
                    getString(if (set) R.string.settings_api_key_set else R.string.settings_api_key_not_set)
                }
            }

            numberPreference(Settings.KEY_MAX_PICTURES, Settings.MAX_PICTURES_RANGE)
            numberPreference(Settings.KEY_PERCENT, Settings.PERCENT_RANGE)
            numberPreference(Settings.KEY_SLIDE_SECONDS, Settings.SLIDE_SECONDS_RANGE)
        }

        // Shows a numeric keyboard and rejects values outside range
        private fun numberPreference(key: String, range: IntRange) {
            val pref = findPreference<EditTextPreference>(key) ?: return
            pref.setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER }
            pref.setOnPreferenceChangeListener { _, value ->
                val ok = (value as String).trim().toIntOrNull()?.let { it in range } == true
                if (!ok) {
                    val msg = getString(R.string.settings_invalid_number, range.first, range.last)
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                }
                ok
            }
        }
    }
}
