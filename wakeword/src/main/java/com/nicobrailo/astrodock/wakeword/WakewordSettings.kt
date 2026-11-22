package com.nicobrailo.astrodock.wakeword

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import androidx.core.content.ContextCompat

// The settings, in a preferences file of their own, which is what the
// settings screen (res/xml/wakeword_preferences.xml, whose keys must match)
// writes. Only the ":wakeword" process reads them: SharedPreferences are
// cached per process, so another would see stale values.
object WakewordSettings {
    const val PREFS = "wakeword"
    const val KEY_ENABLED = "enabled"
    const val KEY_SOURCE = "source"
    const val KEY_THRESHOLD = "threshold"
    // The score, as a percentage, that counts as hearing the phrase:
    // openWakeWord's default. Lower hears it more readily, and other sounds
    // too.
    const val DEFAULT_THRESHOLD = 50

    // Which of the device's audio inputs to record. Which one gives a usable
    // signal is up to the device's audio setup, hence a setting; the values
    // are the names in res/values/arrays.xml.
    private val SOURCES = mapOf(
        "voice_recognition" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
        "voice_communication" to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        "mic" to MediaRecorder.AudioSource.MIC,
        "camcorder" to MediaRecorder.AudioSource.CAMCORDER,
        "unprocessed" to MediaRecorder.AudioSource.UNPROCESSED,
    )
    private const val DEFAULT_SOURCE = "voice_recognition"

    // Off until asked for: it opens the microphone
    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun source(context: Context): Int {
        val name = prefs(context).getString(KEY_SOURCE, DEFAULT_SOURCE)
        return SOURCES[name] ?: SOURCES.getValue(DEFAULT_SOURCE)
    }

    fun threshold(context: Context): Int = prefs(context).getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun micGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    // Starts the listener if it should run (enabled, or its screen open), or
    // has it look at its settings again if it is running; it stops itself
    // once it shouldn't
    fun apply(context: Context) {
        if (!enabled(context) && !ListenerStatus.onScreen) {
            context.stopService(ListenerService.intent(context))
            ListenerStatus.set(listening = false, refusal = Refusal.DISABLED, problem = null)
            return
        }
        ContextCompat.startForegroundService(context, ListenerService.intent(context))
    }
}
