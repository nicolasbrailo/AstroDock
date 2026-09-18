package com.nicobrailo.alauncher

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator

// Three tabs: the slideshow settings (Settings.kt), what the app needs from the
// system (SystemSettingsFragment), and apps to install (InstallAppsFragment).
class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        title = getString(R.string.settings_title)

        val pager = findViewById<ViewPager2>(R.id.pager)
        pager.adapter = object : FragmentStateAdapter(this) {
            override fun getItemCount() = TABS.size

            override fun createFragment(position: Int): Fragment = when (position) {
                0 -> SlideshowSettingsFragment()
                1 -> SystemSettingsFragment()
                2 -> InstallAppsFragment()
                else -> MqttSettingsFragment()
            }
        }
        TabLayoutMediator(findViewById<TabLayout>(R.id.tabs), pager) { tab, position ->
            tab.text = getString(TABS[position])
        }.attach()
    }

    private companion object {
        val TABS = listOf(
            R.string.settings_tab_slideshow,
            R.string.settings_tab_system,
            R.string.settings_tab_apps,
            R.string.settings_tab_mqtt,
        )
    }

    // Edits the settings in Settings.kt. SlideshowActivity picks up changes when
    // it comes back to the foreground.
    class SlideshowSettingsFragment : PreferenceFragmentCompat() {
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
            numberPreference(Settings.KEY_SCREENSAVER_MINUTES, Settings.SCREENSAVER_MINUTES_RANGE)
            numberPreference(Settings.KEY_NIGHT_START_HOUR, Settings.HOUR_RANGE)
            numberPreference(Settings.KEY_NIGHT_END_HOUR, Settings.HOUR_RANGE)
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
