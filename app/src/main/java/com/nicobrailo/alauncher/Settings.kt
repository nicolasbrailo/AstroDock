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
) {
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && apiKey.isNotBlank()

    companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_MAX_PICTURES = "max_pictures_per_album"
        const val KEY_PERCENT = "percent_of_album"
        const val KEY_SLIDE_SECONDS = "slide_seconds"

        const val DEFAULT_MAX_PICTURES = 20
        const val DEFAULT_PERCENT = 0
        const val DEFAULT_SLIDE_SECONDS = 30

        // Valid values of each numeric setting; SettingsActivity rejects the rest
        val MAX_PICTURES_RANGE = 0..100_000
        val PERCENT_RANGE = 0..100
        val SLIDE_SECONDS_RANGE = 1..24 * 60 * 60

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
            )
        }
    }
}
