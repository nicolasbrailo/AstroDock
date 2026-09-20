package com.nicobrailo.astrodock

import android.content.Context
import androidx.preference.PreferenceManager
import com.nicobrailo.astrodock.immich.AlbumFilter

// User settings, stored in the default SharedPreferences and edited in
// SettingsActivity (res/xml/preferences.xml). The keys below must match the
// ones in that XML. Numbers are stored as strings because EditTextPreference
// only stores strings.
data class Settings(
    val serverUrl: String,
    val apiKey: String,
    val maxPicturesPerAlbum: Int, // 0: no limit
    val percentOfAlbum: Int,      // 0: all of it
    // Which of the server's albums the pictures come from; empty means all
    val albumFilter: AlbumFilter,
    val slideSeconds: Int,
    // How long the Portal waits, after it last saw someone, before switching
    // the screen off. 0 leaves the system's own value alone.
    val screenOffMinutes: Int,
    // Turn the screen off during the night hours below (needs the device admin)
    val nightScreenOff: Boolean,
    val nightStartHour: Int,
    val nightEndHour: Int,
    // Current temperature and sky over the clock, from Open-Meteo. It needs no
    // account, only somewhere to report on, so there is no key to store.
    val weatherEnabled: Boolean,
    val weatherLatitude: Double,
    val weatherLongitude: Double,
) {
    // Somewhere on Earth, and not the Gulf of Guinea, which is where an
    // unfilled pair of coordinates points
    val hasWeatherLocation: Boolean
        get() = weatherLatitude in LATITUDE_RANGE && weatherLongitude in LONGITUDE_RANGE &&
            !(weatherLatitude == 0.0 && weatherLongitude == 0.0)
    val showWeather: Boolean get() = weatherEnabled && hasWeatherLocation

    val isConfigured: Boolean get() = serverUrl.isNotBlank() && apiKey.isNotBlank()

    companion object {
        const val KEY_SERVER_URL = "server_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_MAX_PICTURES = "max_pictures_per_album"
        const val KEY_PERCENT = "percent_of_album"
        const val KEY_ALBUM_INCLUDE = "album_name_include"
        const val KEY_ALBUM_EXCLUDE = "album_name_exclude"
        const val KEY_ALBUM_FROM_YEAR = "album_from_year"
        const val KEY_ALBUM_TO_YEAR = "album_to_year"
        const val KEY_SLIDE_SECONDS = "slide_seconds"
        const val KEY_SCREEN_OFF_MINUTES = "screen_off_minutes"
        const val KEY_NIGHT_SCREEN_OFF = "night_screen_off"
        const val KEY_NIGHT_START_HOUR = "night_start_hour"
        const val KEY_NIGHT_END_HOUR = "night_end_hour"
        const val KEY_WEATHER_ENABLED = "weather_enabled"
        const val KEY_WEATHER_LATITUDE = "weather_latitude"
        const val KEY_WEATHER_LONGITUDE = "weather_longitude"

        const val DEFAULT_MAX_PICTURES = 20
        const val DEFAULT_PERCENT = 0
        const val DEFAULT_SLIDE_SECONDS = 30
        const val DEFAULT_SCREEN_OFF_MINUTES = 0
        const val DEFAULT_NIGHT_START_HOUR = 0
        const val DEFAULT_NIGHT_END_HOUR = 6

        // Valid values of each numeric setting; SettingsActivity rejects the rest
        val MAX_PICTURES_RANGE = 0..100_000
        val PERCENT_RANGE = 0..100
        // 0 means that end of the album filter's range is open
        val YEAR_RANGE = 0..9999
        // The sliders' ranges; see res/xml/preferences.xml for their steps
        val SLIDE_SECONDS_RANGE = 5..300
        val SCREEN_OFF_MINUTES_RANGE = 0..30
        val HOUR_RANGE = 0..23
        val LATITUDE_RANGE = -90.0..90.0
        val LONGITUDE_RANGE = -180.0..180.0

        fun load(context: Context): Settings {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)

            // Text inputs store their number as a string
            fun int(key: String, default: Int, range: IntRange): Int =
                prefs.getString(key, null)?.trim()?.toIntOrNull()?.takeIf { it in range } ?: default

            // Coordinates are typed, so they are stored as strings too. Out
            // of range or unparseable reads as "not set", which hides the panel
            // rather than asking Open-Meteo about a place that can't exist.
            fun degrees(key: String, range: ClosedFloatingPointRange<Double>): Double =
                prefs.getString(key, null)?.trim()?.toDoubleOrNull()?.takeIf { it in range } ?: 0.0

            // Sliders store it as an int. Values written by the text inputs
            // these replaced are converted the first time they're read.
            fun slider(key: String, default: Int, range: IntRange): Int {
                val value = try {
                    prefs.getInt(key, Int.MIN_VALUE)
                } catch (e: ClassCastException) {
                    val text = prefs.getString(key, null)?.trim()?.toIntOrNull()
                    if (text != null) prefs.edit().putInt(key, text).apply()
                    text ?: Int.MIN_VALUE
                }
                return value.takeIf { it in range } ?: default
            }
            return Settings(
                serverUrl = prefs.getString(KEY_SERVER_URL, null)?.trim().orEmpty(),
                apiKey = prefs.getString(KEY_API_KEY, null)?.trim().orEmpty(),
                maxPicturesPerAlbum = int(KEY_MAX_PICTURES, DEFAULT_MAX_PICTURES, MAX_PICTURES_RANGE),
                percentOfAlbum = int(KEY_PERCENT, DEFAULT_PERCENT, PERCENT_RANGE),
                albumFilter = AlbumFilter(
                    include = prefs.getString(KEY_ALBUM_INCLUDE, null)?.trim().orEmpty(),
                    exclude = prefs.getString(KEY_ALBUM_EXCLUDE, null)?.trim().orEmpty(),
                    fromYear = int(KEY_ALBUM_FROM_YEAR, 0, YEAR_RANGE),
                    toYear = int(KEY_ALBUM_TO_YEAR, 0, YEAR_RANGE),
                ),
                slideSeconds = slider(KEY_SLIDE_SECONDS, DEFAULT_SLIDE_SECONDS, SLIDE_SECONDS_RANGE),
                screenOffMinutes = slider(
                    KEY_SCREEN_OFF_MINUTES, DEFAULT_SCREEN_OFF_MINUTES, SCREEN_OFF_MINUTES_RANGE
                ),
                nightScreenOff = prefs.getBoolean(KEY_NIGHT_SCREEN_OFF, false),
                nightStartHour = int(KEY_NIGHT_START_HOUR, DEFAULT_NIGHT_START_HOUR, HOUR_RANGE),
                nightEndHour = int(KEY_NIGHT_END_HOUR, DEFAULT_NIGHT_END_HOUR, HOUR_RANGE),
                weatherEnabled = prefs.getBoolean(KEY_WEATHER_ENABLED, false),
                weatherLatitude = degrees(KEY_WEATHER_LATITUDE, LATITUDE_RANGE),
                weatherLongitude = degrees(KEY_WEATHER_LONGITUDE, LONGITUDE_RANGE),
            )
        }
    }
}
