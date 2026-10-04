package com.nicobrailo.astrodock

import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.nicobrailo.astrodock.mqtt.MqttSettings
import com.nicobrailo.astrodock.mqtt.RetainedTopics

// Where to publish this device's state (see mqtt/StateReporter.kt). The
// slideshow picks up changes when it next comes to the foreground.
class MqttSettingsFragment : PreferenceFragmentCompat() {
    // The topic list depends on the prefix, which is edited on this screen
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == MqttSettings.KEY_TOPIC_PREFIX) showTopics()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.mqtt_preferences, rootKey)

        findPreference<EditTextPreference>(MqttSettings.KEY_HOST)?.setOnBindEditTextListener {
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }

        findPreference<EditTextPreference>(MqttSettings.KEY_PORT)?.apply {
            setOnBindEditTextListener { it.inputType = InputType.TYPE_CLASS_NUMBER }
            setOnPreferenceChangeListener { _, value ->
                val ok = (value as String).trim().toIntOrNull()?.let { it in MqttSettings.PORT_RANGE } == true
                if (!ok) {
                    val message = getString(
                        R.string.settings_invalid_number,
                        MqttSettings.PORT_RANGE.first,
                        MqttSettings.PORT_RANGE.last,
                    )
                    Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
                }
                ok
            }
        }

        findPreference<EditTextPreference>(MqttSettings.KEY_PASSWORD)?.apply {
            setOnBindEditTextListener {
                it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            }
            setSummaryProvider { pref ->
                val set = !(pref as EditTextPreference).text.isNullOrBlank()
                getString(if (set) R.string.settings_api_key_set else R.string.settings_api_key_not_set)
            }
        }

        showTopics()
    }

    override fun onResume() {
        super.onResume()
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(prefsListener)
        // It may have been pushed over adb meanwhile
        showTopics()
    }

    override fun onPause() {
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(prefsListener)
        super.onPause()
    }

    // What the broker will see, with the prefix as it will really be used
    private fun showTopics() {
        val preference = findPreference<Preference>("mqtt_topics") ?: return
        val settings = MqttSettings.load(requireContext())
        preference.summary = RetainedTopics.OURS.joinToString("\n") { settings.topicPrefix + it }
    }
}
