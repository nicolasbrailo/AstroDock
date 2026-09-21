package com.nicobrailo.astrodock.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class WeatherConditionTest {
    @Test
    fun `clear sky and cloud cover`() {
        assertEquals(WeatherCondition.CLEAR, conditionOf(0))
        assertEquals(WeatherCondition.PARTLY_CLOUDY, conditionOf(1))
        assertEquals(WeatherCondition.PARTLY_CLOUDY, conditionOf(2))
        assertEquals(WeatherCondition.CLOUDY, conditionOf(3))
    }

    @Test
    fun `everything that falls out of a cloud`() {
        assertEquals(WeatherCondition.FOG, conditionOf(45))
        assertEquals(WeatherCondition.FOG, conditionOf(48))
        // Drizzle, freezing drizzle, rain, freezing rain and rain showers all
        // mean the same thing to someone glancing at the corner of a photo
        for (code in listOf(51, 53, 55, 56, 57, 61, 63, 65, 66, 67, 80, 81, 82)) {
            assertEquals("code $code", WeatherCondition.RAIN, conditionOf(code))
        }
        for (code in listOf(71, 73, 75, 77, 85, 86)) {
            assertEquals("code $code", WeatherCondition.SNOW, conditionOf(code))
        }
        // 96 and 99 are a thunderstorm with hail
        for (code in listOf(95, 96, 99)) {
            assertEquals("code $code", WeatherCondition.THUNDERSTORM, conditionOf(code))
        }
    }

    @Test
    fun `a code we don't know is drawn as a cloud`() {
        assertEquals(WeatherCondition.CLOUDY, conditionOf(4))
        assertEquals(WeatherCondition.CLOUDY, conditionOf(100))
        assertEquals(WeatherCondition.CLOUDY, conditionOf(-1))
    }

    @Test
    fun `temperature is shown as whole degrees`() {
        assertEquals("12°", Weather(12.4, WeatherCondition.CLEAR).temperatureText)
        assertEquals("13°", Weather(12.5, WeatherCondition.CLEAR).temperatureText)
        assertEquals("0°", Weather(0.0, WeatherCondition.CLEAR).temperatureText)
        assertEquals("-5°", Weather(-4.6, WeatherCondition.SNOW).temperatureText)
    }
}

class MillisToNextHourTest {
    private val utc = ZoneId.of("UTC")

    private fun at(time: String, zone: ZoneId = utc) =
        millisToNextHour(Instant.parse(time), zone)

    @Test
    fun `counts to the end of the hour`() {
        assertEquals(60 * 60_000L, at("2026-09-20T15:00:00Z"))
        assertEquals(45 * 60_000L, at("2026-09-20T15:15:00Z"))
        assertEquals(1_000L, at("2026-09-20T15:59:59Z"))
    }

    @Test
    fun `crosses midnight`() {
        assertEquals(60_000L, at("2026-09-20T23:59:00Z"))
    }

    @Test
    fun `a zone offset by half an hour lands on its own clock`() {
        // Kolkata is UTC+05:30, so its full hours fall on UTC's half hours;
        // counting on the epoch instead would be half an hour out here.
        val kolkata = ZoneId.of("Asia/Kolkata")
        assertEquals(60 * 60_000L, at("2026-09-20T15:30:00Z", kolkata))
        assertEquals(30 * 60_000L, at("2026-09-20T15:00:00Z", kolkata))
    }

    @Test
    fun `a zone offset by three quarters of an hour does too`() {
        // Kathmandu is UTC+05:45
        val kathmandu = ZoneId.of("Asia/Kathmandu")
        assertEquals(60 * 60_000L, at("2026-09-20T15:15:00Z", kathmandu))
        assertEquals(15 * 60_000L, at("2026-09-20T15:00:00Z", kathmandu))
    }
}

class ParseCoordinatesTest {
    @Test
    fun `latitude and longitude, with or without spaces`() {
        assertEquals(Place(52.37, 4.89, "52.37, 4.89"), parseCoordinates("52.37, 4.89"))
        assertEquals(Place(52.37, 4.89, "52.37, 4.89"), parseCoordinates("52.37,4.89"))
        assertEquals(Place(52.37, 4.89, "52.37, 4.89"), parseCoordinates("  52.37 ,  4.89 "))
    }

    @Test
    fun `south and west are negative`() {
        assertEquals(Place(-54.8, -68.3, "-54.8, -68.3"), parseCoordinates("-54.8, -68.3"))
    }

    @Test
    fun `the ends of the ranges are allowed`() {
        assertEquals(90.0, parseCoordinates("90, 180")?.latitude)
        assertEquals(-180.0, parseCoordinates("-90, -180")?.longitude)
    }

    @Test
    fun `out of range is a name, not a place`() {
        assertNull(parseCoordinates("90.1, 0"))
        assertNull(parseCoordinates("0, 180.1"))
        assertNull(parseCoordinates("NaN, NaN"))
    }

    @Test
    fun `a place name with a comma is left for the geocoder`() {
        assertNull(parseCoordinates("Paris, US"))
        assertNull(parseCoordinates("Springfield, Illinois"))
        assertNull(parseCoordinates("52.37, Amsterdam"))
    }

    @Test
    fun `anything but exactly two numbers is a name`() {
        assertNull(parseCoordinates("Amsterdam"))
        assertNull(parseCoordinates("52.37"))
        assertNull(parseCoordinates("52.37, 4.89, 11"))
        assertNull(parseCoordinates(""))
    }
}

class PlaceLabelTest {
    @Test
    fun `name, region and country`() {
        assertEquals(
            "Springfield, Illinois, United States",
            placeLabel("Springfield", "Illinois", "United States"),
        )
    }

    @Test
    fun `missing parts are left out`() {
        assertEquals("Amsterdam, Netherlands", placeLabel("Amsterdam", "", "Netherlands"))
        assertEquals("Amsterdam", placeLabel("Amsterdam", " ", ""))
    }

    @Test
    fun `a city that is its own region is named once`() {
        assertEquals("Berlin, Germany", placeLabel("Berlin", "Berlin", "Germany"))
        // A city that is its own country, with no region between them
        assertEquals("Luxembourg", placeLabel("Luxembourg", "", "Luxembourg"))
    }
}
