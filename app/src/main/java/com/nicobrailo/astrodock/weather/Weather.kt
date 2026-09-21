package com.nicobrailo.astrodock.weather

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// What the sky is doing, in as few cases as a single icon can usefully tell
// apart. Open-Meteo reports the WMO 4677 code, which splits hail from rain and
// light drizzle from heavy, and none of that survives being drawn at 36dp.
enum class WeatherCondition {
    CLEAR,
    PARTLY_CLOUDY,
    CLOUDY,
    FOG,
    RAIN,
    SNOW,
    THUNDERSTORM,
}

// The current weather, as much of it as the slideshow shows.
data class Weather(
    val temperatureC: Double,
    val condition: WeatherCondition,
) {
    // Rounded to a whole degree: the corner of a photo frame is no place for
    // decimals, and Open-Meteo's own accuracy doesn't earn them either.
    val temperatureText: String get() = "${Math.round(temperatureC)}°"
}

// The WMO 4677 codes Open-Meteo reports, folded onto the icons above. The
// groups are the ones its own documentation lists; freezing rain counts as
// rain and freezing drizzle as rain too, since what the icon is really saying
// is "take a coat", and hail arrives with a thunderstorm.
fun conditionOf(code: Int): WeatherCondition = when (code) {
    0 -> WeatherCondition.CLEAR
    1, 2 -> WeatherCondition.PARTLY_CLOUDY
    3 -> WeatherCondition.CLOUDY
    45, 48 -> WeatherCondition.FOG
    51, 53, 55, 56, 57 -> WeatherCondition.RAIN // drizzle, freezing or not
    61, 63, 65, 66, 67 -> WeatherCondition.RAIN
    71, 73, 75, 77 -> WeatherCondition.SNOW // snow fall and snow grains
    80, 81, 82 -> WeatherCondition.RAIN // rain showers
    85, 86 -> WeatherCondition.SNOW // snow showers
    95, 96, 99 -> WeatherCondition.THUNDERSTORM // the last two bring hail
    // Open-Meteo may add codes, and an unknown sky is most likely a cloudy one
    else -> WeatherCondition.CLOUDY
}

// Somewhere to report the weather for, and what to call it back to the user.
data class Place(
    val latitude: Double,
    val longitude: Double,
    val label: String,
)

val LATITUDE_RANGE = -90.0..90.0
val LONGITUDE_RANGE = -180.0..180.0

// Coordinates typed into the place field ("52.37, 4.89"), for somewhere the
// geocoder has no name for, like a house outside any town. Anything else is a
// name to look up. "Paris, US" has a comma too, which is why both halves have
// to be numbers, and in range, before this takes it.
fun parseCoordinates(text: String): Place? {
    val parts = text.split(',').map { it.trim() }
    if (parts.size != 2) return null
    val latitude = parts[0].toDoubleOrNull() ?: return null
    val longitude = parts[1].toDoubleOrNull() ?: return null
    // NaN fails these too, so "NaN, NaN" is looked up as a name, and not found
    if (latitude !in LATITUDE_RANGE || longitude !in LONGITUDE_RANGE) return null
    return Place(latitude, longitude, "${parts[0]}, ${parts[1]}")
}

// "Springfield, Illinois, United States": enough to tell apart the places
// that share a name, which is what the settings screen shows it for. A part
// that repeats the one before it is dropped, so a city that is its own region
// (Berlin, Berlin, Germany) is only named once.
fun placeLabel(name: String, region: String, country: String): String =
    listOf(name, region, country)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .fold(listOf<String>()) { kept, part -> if (kept.lastOrNull() == part) kept else kept + part }
        .joinToString(", ")

// How long until the next full hour, so the weather is refreshed on the hour
// rather than an hour after the slideshow happened to start. Done in the local
// zone rather than on the epoch, because a few zones are offset by half an hour
// or three quarters of one, and there the two don't agree.
fun millisToNextHour(now: Instant, zone: ZoneId): Long {
    val local = now.atZone(zone)
    val next = local.truncatedTo(ChronoUnit.HOURS).plusHours(1)
    return Duration.between(local, next).toMillis()
}
