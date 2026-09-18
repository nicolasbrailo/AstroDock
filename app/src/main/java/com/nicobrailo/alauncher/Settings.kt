package com.nicobrailo.alauncher

import android.content.Context
import androidx.preference.PreferenceManager

// User settings, stored in the default SharedPreferences and edited in
// SettingsActivity (res/xml/preferences.xml). The keys below must match the
// ones in that XML. Numbers are stored as strings because EditTextPreference
// only stores strings.
data class Settings(
    val serverUrl: String,
    val apiKey: String,
    val maxPicturesPerAlbum: Int, // 0: no limit
    val percentOfAlbum: Int,      // 0: all of it
    val slideSeconds: Int,
    // How long the Portal waits before starting the screensaver. 0 leaves the
    // system's own value alone.
    val screensaverMinutes: Int,
    // Turn the screen off during the night hours below (needs the device admin)
    val nightScreenOff: Boolean,
    val nightStartHour: Int,
    val nightEndHour: Int,
) {
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && apiKey.isNotBlank()

    companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_MAX_PICTURES = "max_pictures_per_album"
        const val KEY_PERCENT = "percent_of_album"
        const val KEY_SLIDE_SECONDS = "slide_seconds"
        const val KEY_SCREENSAVER_MINUTES = "screensaver_minutes"
        const val KEY_NIGHT_SCREEN_OFF = "night_screen_off"
        const val KEY_NIGHT_START_HOUR = "night_start_hour"
        const val KEY_NIGHT_END_HOUR = "night_end_hour"

        const val DEFAULT_MAX_PICTURES = 20
        const val DEFAULT_PERCENT = 0
        const val DEFAULT_SLIDE_SECONDS = 30
        const val DEFAULT_SCREENSAVER_MINUTES = 0
        const val DEFAULT_NIGHT_START_HOUR = 0
        const val DEFAULT_NIGHT_END_HOUR = 6

        // Valid values of each numeric setting; SettingsActivity rejects the rest
        val MAX_PICTURES_RANGE = 0..100_000
        val PERCENT_RANGE = 0..100
        val SLIDE_SECONDS_RANGE = 1..24 * 60 * 60
        val SCREENSAVER_MINUTES_RANGE = 0..240
        val HOUR_RANGE = 0..23

        fun load(context: Context): Settings {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            fun int(key: String, default: Int, range: IntRange): Int =
                prefs.getString(key, null)?.trim()?.toIntOrNull()?.takeIf { it in range } ?: default
            return Settings(
                serverUrl = prefs.getString(KEY_SERVER_URL, null)?.trim().orEmpty(),
                apiKey = prefs.getString(KEY_API_KEY, null)?.trim().orEmpty(),
                maxPicturesPerAlbum = int(KEY_MAX_PICTURES, DEFAULT_MAX_PICTURES, MAX_PICTURES_RANGE),
                percentOfAlbum = int(KEY_PERCENT, DEFAULT_PERCENT, PERCENT_RANGE),
                slideSeconds = int(KEY_SLIDE_SECONDS, DEFAULT_SLIDE_SECONDS, SLIDE_SECONDS_RANGE),
                screensaverMinutes = int(
                    KEY_SCREENSAVER_MINUTES, DEFAULT_SCREENSAVER_MINUTES, SCREENSAVER_MINUTES_RANGE
                ),
                nightScreenOff = prefs.getBoolean(KEY_NIGHT_SCREEN_OFF, false),
                nightStartHour = int(KEY_NIGHT_START_HOUR, DEFAULT_NIGHT_START_HOUR, HOUR_RANGE),
                nightEndHour = int(KEY_NIGHT_END_HOUR, DEFAULT_NIGHT_END_HOUR, HOUR_RANGE),
            )
        }
    }
}
