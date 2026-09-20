package com.nicobrailo.astrodock.weather

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.concurrent.TimeUnit

class WeatherException(message: String, cause: Throwable? = null) : IOException(message, cause)

// The current weather from Open-Meteo (https://open-meteo.com), which needs no
// account and no API key, so the only settings are where the device is.
// Every failure is a WeatherException; the caller hides the panel rather than
// saying so on screen, since the pictures are the point.
class WeatherClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun current(latitude: Double, longitude: Double): Weather = withContext(Dispatchers.IO) {
        // Celsius is the default, so no unit is asked for. `timezone=auto` only
        // decides how the reply dates its readings, which nothing here reads,
        // but without it "current" is dated in UTC and looks wrong in the log.
        val url = "$BASE_URL?latitude=$latitude&longitude=$longitude" +
            "&current=temperature_2m,weather_code&timezone=auto"

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

        val current = try {
            (JSONTokener(body).nextValue() as? JSONObject)?.optJSONObject("current")
        } catch (e: JSONException) {
            throw WeatherException("GET $url: invalid JSON response: ${e.message}", e)
        } ?: throw WeatherException("GET $url: no \"current\" in the response")

        // A reading that is missing rather than wrong still has to be caught:
        // opt* would turn it into 0, which reads as a plausible winter day.
        if (!current.has(FIELD_TEMPERATURE) || !current.has(FIELD_CODE)) {
            throw WeatherException("GET $url: no temperature or weather code in the response")
        }
        Weather(
            temperatureC = current.getDouble(FIELD_TEMPERATURE),
            condition = conditionOf(current.getInt(FIELD_CODE)),
        )
    }

    companion object {
        private const val BASE_URL = "https://api.open-meteo.com/v1/forecast"
        private const val FIELD_TEMPERATURE = "temperature_2m"
        private const val FIELD_CODE = "weather_code"
    }
}
