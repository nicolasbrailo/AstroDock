package com.nicobrailo.astrodock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightHoursTest {
    @Test
    fun everyHourHasAPlaceOnTheScale() {
        for (hour in 0..23) {
            val position = NightHours.position(hour)
            assertTrue("$hour", position in NightHours.AXIS)
            assertEquals(hour, NightHours.hour(position))
        }
    }

    @Test
    fun windowsAcrossMidnightFit() {
        assertTrue(NightHours.fits(0, 6))
        assertTrue(NightHours.fits(22, 6))
        assertTrue(NightHours.fits(12, 11))
        assertTrue(NightHours.fits(23, 0))
    }

    @Test
    fun windowsIncludingNoonOrEmptyDoNot() {
        assertFalse(NightHours.fits(6, 22))
        assertFalse(NightHours.fits(11, 12))
        assertFalse(NightHours.fits(3, 3))
    }

    @Test
    fun formatsAsAClock() {
        assertEquals("00:00", NightHours.format(0))
        assertEquals("22:00", NightHours.format(22))
    }
}
