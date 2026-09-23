package com.nicobrailo.astrodock

import android.os.Bundle
import android.text.InputType
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.nicobrailo.astrodock.weather.PlaceCache
import com.nicobrailo.astrodock.weather.WeatherClient
import com.nicobrailo.astrodock.weather.WeatherException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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
            numberPreference(Settings.KEY_ALBUM_FROM_YEAR, Settings.YEAR_RANGE)
            numberPreference(Settings.KEY_ALBUM_TO_YEAR, Settings.YEAR_RANGE)
            placePreference()
        }

        // The night rule can only turn the screen off with the device admin,
        // and without it would do nothing and say nothing, so it is greyed out
        // (its hours with it, through the dependency) and says why. Checked on
        // every resume, since the grant is made in the System tab and the
        // system's dialog, and both bring this tab back.
        override fun onResume() {
            super.onResume()
            findPreference<SwitchPreferenceCompat>(Settings.KEY_NIGHT_SCREEN_OFF)?.apply {
                val allowed = ScreenControl.canTurnScreenOff(requireContext())
                isEnabled = allowed
                summary = if (allowed) {
                    getString(R.string.settings_night_screen_off_hint)
                } else {
                    // A span, because the summary's own colour greys out with
                    // the rest of the disabled item
                    SpannableString(getString(R.string.settings_night_screen_off_no_admin)).apply {
                        val red = ContextCompat.getColor(requireContext(), R.color.error_text)
                        setSpan(ForegroundColorSpan(red), 0, length, 0)
                    }
                }
            }
        }

        // Shows a numeric keyboard and rejects values outside range. The
        // sliders (percent of each album, seconds per picture, the Screen
        // section's and NightHoursPreference) need none of this: they can
        // only be moved within their own range.
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

        // Says what the place was found as, rather than what was typed: a bare
        // "Springfield" is taken to be the biggest one and a typo finds nothing
        // at all, and otherwise the only sign of either would be the wrong
        // weather, or none. It is looked up again every time
        // the screen opens as well as when it changes, so a name pushed with
        // push-config.sh gets checked too; one found before comes from the
        // cache without the network. Every value is accepted, found or not:
        // the geocoder being down is no reason to refuse a name.
        private fun placePreference() {
            val pref = findPreference<EditTextPreference>(Settings.KEY_WEATHER_PLACE) ?: return
            val places = PlaceCache(requireContext())
            val client = WeatherClient()
            var lookup: Job? = null

            fun describe(text: String) {
                lookup?.cancel()
                val name = text.trim()
                if (name.isEmpty()) {
                    pref.summary = getString(R.string.settings_weather_place_not_set)
                    return
                }
                places.known(name)?.let {
                    pref.summary = it.label
                    return
                }
                pref.summary = getString(R.string.settings_weather_place_looking_up, name)
                lookup = lifecycleScope.launch {
                    pref.summary = try {
                        places.resolve(name, client)?.label
                            ?: getString(R.string.settings_weather_place_not_found, name)
                    } catch (e: WeatherException) {
                        getString(R.string.settings_weather_place_failed, name)
                    }
                }
            }

            describe(pref.text.orEmpty())
            pref.setOnPreferenceChangeListener { _, value ->
                describe(value as String)
                true
            }
        }
    }
}
