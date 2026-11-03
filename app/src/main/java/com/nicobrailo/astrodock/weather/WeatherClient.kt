package com.nicobrailo.astrodock.weather

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.concurrent.TimeUnit

class WeatherException(message: String, cause: Throwable? = null) : IOException(message, cause)

// What current() found, and, when the relay didn't answer and Open-Meteo did
// instead, why not: the weather is still right, but the relay is broken and
// someone should hear about it.
data class CurrentWeather(val weather: Weather, val relayError: String? = null)

// The relay's report for `place`, at <relay>/weather/<lat>/<lon>. Resolved
// against the setting rather than appended to it, so the relay's address works
// with or without a trailing slash, or as the address of its page
// (http://10.0.0.10:4198/index.html). Null if the setting isn't an http(s) URL.
// `client` names this device in the relay's list of recent clients.
fun relayWeatherUrl(relay: String, place: Place, client: String?): HttpUrl? {
    val base = relay.trim().toHttpUrlOrNull() ?: return null
    val url = base.resolve("/weather/${place.latitude}/${place.longitude}") ?: return null
    if (client.isNullOrBlank()) return url
    return url.newBuilder().addQueryParameter("client", client).build()
}

// The current weather, from the house's weather relay (zmw_meteo_relay, a cache
// in front of Open-Meteo that answers with Open-Meteo's own JSON) or straight
// from Open-Meteo (https://open-meteo.com), and the place names it is asked
// about, always from Open-Meteo's geocoder. Neither needs an account or an API
// key. Every failure is a WeatherException; the caller hides the panel rather
// than saying so on screen, since the pictures are the point.
class WeatherClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // From the relay at `relay` when there is one (blank: none), and from
    // Open-Meteo directly when there isn't, or when the relay fails and
    // `fallback` allows it. Without the fallback a broken relay means no
    // weather, which is the point of turning it off: the failure shows.
    suspend fun current(place: Place, relay: String, fallback: Boolean, client: String?): CurrentWeather {
        if (relay.isBlank()) return CurrentWeather(currentFrom(openMeteoUrl(place)))
        val relayError = try {
            val url = relayWeatherUrl(relay, place, client)
                ?: throw WeatherException("Weather relay \"$relay\" is not an http URL")
            return CurrentWeather(currentFrom(url))
        } catch (e: WeatherException) {
            if (!fallback) throw e
            e.message.orEmpty()
        }
        Log.w(TAG, "Weather relay failed, asking Open-Meteo instead: $relayError")
        return CurrentWeather(currentFrom(openMeteoUrl(place)), relayError)
    }

    // Celsius is the default, so no unit is asked for. `timezone=auto` only
    // decides how the reply dates its readings, which nothing here reads, but
    // without it "current" is dated in UTC and looks wrong in the log. The
    // relay asks for both fields too, among many others.
    private fun openMeteoUrl(place: Place): HttpUrl = FORECAST_URL.toHttpUrl().newBuilder()
        .addQueryParameter("latitude", place.latitude.toString())
        .addQueryParameter("longitude", place.longitude.toString())
        .addQueryParameter("current", "$FIELD_TEMPERATURE,$FIELD_CODE")
        .addQueryParameter("timezone", "auto")
        .build()

    // The relay answers with Open-Meteo's JSON, so both are read the same way
    private suspend fun currentFrom(url: HttpUrl): Weather {
        val current = get(url).optJSONObject("current")
            ?: throw WeatherException("GET $url: no \"current\" in the response")

        // A reading that is missing rather than wrong still has to be caught:
        // opt* would turn it into 0, which reads as a plausible winter day.
        if (!current.has(FIELD_TEMPERATURE) || !current.has(FIELD_CODE)) {
            throw WeatherException("GET $url: no temperature or weather code in the response")
        }
        return Weather(
            temperatureC = current.getDouble(FIELD_TEMPERATURE),
            condition = conditionOf(current.getInt(FIELD_CODE)),
        )
    }

    // The place a name stands for, or null if the geocoder knows none by it.
    // When several share the name it answers with the most populous, so a bare
    // "Springfield" is the one in Missouri. It does understand a comma and a
    // region or country after the name ("Springfield, Illinois", "Paris, US"),
    // though not the same without the comma, which finds nothing.
    suspend fun geocode(name: String): Place? {
        val url = GEOCODING_URL.toHttpUrl().newBuilder()
            .addQueryParameter("name", name)
            .addQueryParameter("count", "1")
            .addQueryParameter("language", "en")
            .addQueryParameter("format", "json")
            .build()
        // A name it doesn't know gets a reply with no "results" at all, not an
        // empty list and not an error status
        val found = get(url).optJSONArray("results")?.optJSONObject(0) ?: return null
        if (!found.has("latitude") || !found.has("longitude")) {
            throw WeatherException("GET $url: a result with no coordinates")
        }
        return Place(
            latitude = found.getDouble("latitude"),
            longitude = found.getDouble("longitude"),
            label = placeLabel(
                found.optString("name", name),
                found.optString("admin1"),
                found.optString("country"),
            ),
        )
    }

    private suspend fun get(url: HttpUrl): JSONObject = withContext(Dispatchers.IO) {
        val body = try {
            http.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
                .execute().use { resp ->
                    val text = resp.body.string()
                    if (!resp.isSuccessful) {
                        throw WeatherException("GET $url: HTTP ${resp.code}: $text")
                    }
                    text
                }
        } catch (e: WeatherException) {
            throw e
        } catch (e: IOException) {
            throw WeatherException("GET $url failed: ${e.message}", e)
        }
        try {
            JSONTokener(body).nextValue() as? JSONObject
        } catch (e: JSONException) {
            throw WeatherException("GET $url: invalid JSON response: ${e.message}", e)
        } ?: throw WeatherException("GET $url: the response is not a JSON object")
    }

    companion object {
        private const val TAG = "WeatherClient"
        private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
        private const val GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"
        private const val FIELD_TEMPERATURE = "temperature_2m"
        private const val FIELD_CODE = "weather_code"
    }
}
