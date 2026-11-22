package com.nicobrailo.astrodock.wakeword

import android.Manifest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import java.util.Date
import kotlin.math.roundToInt

// The Wake Word app's only screen, which looks like an app of its own in the
// app list, as the alarms and the calls do: whether to listen in the
// background, and, since it listens whatever AstroDock says while this is
// open, a live view of the microphone and of what it hears, for trying it
// out. Further settings go in res/xml/wakeword_preferences.xml.
class WakewordActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(android.R.id.content, WakewordFragment())
                .commit()
        }
    }
}

class WakewordFragment : PreferenceFragmentCompat() {
    private var chime: Chime? = null
    private val main = Handler(Looper.getMainLooper())
    private val refresh = { showStatus() }
    // Takes the "heard" line back down once it has had its moment
    private val refreshLater = Runnable { showStatus() }

    // Whatever the answer, the listener looks again: it says what is missing
    private val askForMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        WakewordSettings.apply(requireContext())
        showStatus()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // Not AstroDock's default preferences, where these keys could clash
        preferenceManager.sharedPreferencesName = WakewordSettings.PREFS
        setPreferencesFromResource(R.xml.wakeword_preferences, rootKey)

        findPreference<SwitchPreferenceCompat>(WakewordSettings.KEY_ENABLED)?.setOnPreferenceChangeListener { _, on ->
            if (on == true && !WakewordSettings.micGranted(requireContext())) {
                askForMic.launch(Manifest.permission.RECORD_AUDIO)
            }
            applyAfterSaving()
            true
        }
        findPreference<Preference>(WakewordSettings.KEY_SOURCE)?.setOnPreferenceChangeListener { _, _ ->
            applyAfterSaving()
            true
        }
        findPreference<Preference>(KEY_MICROPHONE)?.setOnPreferenceClickListener {
            askForMic.launch(Manifest.permission.RECORD_AUDIO)
            true
        }
        findPreference<Preference>(KEY_CHIME)?.setOnPreferenceClickListener {
            (chime ?: Chime().also { chime = it }).play()
            true
        }
    }

    // A change listener runs before the value is stored, and the listener
    // reads it from the preferences
    private fun applyAfterSaving() {
        main.post {
            context?.let { WakewordSettings.apply(it) }
            showStatus()
        }
    }

    override fun onResume() {
        super.onResume()
        ListenerStatus.addListener(refresh)
        ListenerStatus.onScreen = true
        WakewordSettings.apply(requireContext())
        showStatus()
    }

    override fun onPause() {
        ListenerStatus.removeListener(refresh)
        ListenerStatus.onScreen = false
        // Back to what AstroDock says, or stopped if not enabled
        WakewordSettings.apply(requireContext())
        main.removeCallbacks(refreshLater)
        super.onPause()
    }

    override fun onDestroy() {
        chime?.release()
        chime = null
        super.onDestroy()
    }

    private fun showStatus() {
        val context = context ?: return
        findPreference<Preference>(KEY_MICROPHONE)?.isVisible = !WakewordSettings.micGranted(context)

        val status = ListenerStatus
        val refusal = status.refusal
        findPreference<Preference>(KEY_STATUS)?.summary = when {
            status.listening -> getString(R.string.status_listening)
            status.problem != null -> status.problem
            refusal != null -> getString(R.string.status_not_listening, getString(ListenerService.refusalText(refusal)))
            else -> getString(R.string.status_loading)
        }

        val level = status.level
        findPreference<Preference>(KEY_LEVEL)?.summary = if (level == null) {
            getString(R.string.level_closed)
        } else {
            getString(R.string.level_value, levelBar(level), level)
        }

        findPreference<Preference>(KEY_HEARING)?.summary =
            status.hearing?.let { "\"$it\"" } ?: getString(R.string.hearing_nothing)

        val heardAt = status.lastHeardAt
        val heard = findPreference<Preference>(KEY_HEARD)
        heard?.summary = when {
            heardAt == null -> getString(R.string.status_never_heard)
            System.currentTimeMillis() - heardAt < HEARD_SHOWN_MILLIS -> {
                main.removeCallbacks(refreshLater)
                main.postDelayed(refreshLater, HEARD_SHOWN_MILLIS)
                getString(R.string.heard_now, status.lastHeard)
            }
            else -> {
                val time = DateFormat.getTimeFormat(context).format(Date(heardAt))
                getString(R.string.status_last_heard, time, status.lastHeard)
            }
        }
    }

    private companion object {
        const val KEY_STATUS = "status"
        const val KEY_LEVEL = "level"
        const val KEY_HEARING = "hearing"
        const val KEY_HEARD = "heard"
        const val KEY_MICROPHONE = "microphone"
        const val KEY_CHIME = "chime"
        const val HEARD_SHOWN_MILLIS = 3_000L
    }
}

// A bar for a level in dBFS: empty at -80 dB, which a quiet room is above,
// full at -10, which only shouting into it reaches
fun levelBar(dbfs: Double, width: Int = 20): String {
    val fraction = ((dbfs - LEVEL_BAR_FLOOR) / (LEVEL_BAR_CEILING - LEVEL_BAR_FLOOR)).coerceIn(0.0, 1.0)
    val filled = (fraction * width).roundToInt()
    return "█".repeat(filled) + "░".repeat(width - filled)
}

private const val LEVEL_BAR_FLOOR = -80.0
private const val LEVEL_BAR_CEILING = -10.0
