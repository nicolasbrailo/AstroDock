package com.nicobrailo.astrodock.weather

import android.content.Context

// Where the place typed in the settings was last found, so the geocoder is
// asked once per name rather than every hour, and the weather carries on when
// the geocoder is down. One entry: the name it was found for, and where.
//
// In its own preferences file, like the folders, rather than next to the
// settings: it is derived from one, not a setting, and push-config.sh --show
// should print what the user chose, not what the device worked out from it.
// Changing the name, from the screen or from push-config.sh, is noticed because
// the entry no longer matches it.
class PlaceCache(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // Coordinates are used as typed, a name that was found before comes from
    // here, and anything else is asked of the geocoder and kept if it was
    // found. Null when the geocoder knows no such place; a WeatherException when
    // it couldn't be asked, so the caller can tell "wrong name" from "try again".
    suspend fun resolve(text: String, client: WeatherClient): Place? {
        val name = text.trim()
        if (name.isEmpty()) return null
        parseCoordinates(name)?.let { return it }
        cached(name)?.let { return it }
        return client.geocode(name)?.also { put(name, it) }
    }

    // What resolve() would answer without going to the network, or null if it
    // would have to. The settings screen uses it to fill in the summary at once.
    fun known(text: String): Place? {
        val name = text.trim()
        return parseCoordinates(name) ?: cached(name)
    }

    private fun cached(name: String): Place? {
        if (prefs.getString(KEY_NAME, null) != name) return null
        // Stored as strings, so they read back exactly as the geocoder gave
        // them: SharedPreferences has no double, only float
        val latitude = prefs.getString(KEY_LATITUDE, null)?.toDoubleOrNull() ?: return null
        val longitude = prefs.getString(KEY_LONGITUDE, null)?.toDoubleOrNull() ?: return null
        return Place(latitude, longitude, prefs.getString(KEY_LABEL, null) ?: name)
    }

    private fun put(name: String, place: Place) {
        prefs.edit()
            .putString(KEY_NAME, name)
            .putString(KEY_LATITUDE, place.latitude.toString())
            .putString(KEY_LONGITUDE, place.longitude.toString())
            .putString(KEY_LABEL, place.label)
            .apply()
    }

    companion object {
        private const val FILE = "weather_place"
        private const val KEY_NAME = "name"
        private const val KEY_LATITUDE = "latitude"
        private const val KEY_LONGITUDE = "longitude"
        private const val KEY_LABEL = "label"
    }
}
