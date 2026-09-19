package com.nicobrailo.astrodock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenControlTest {
    @Test
    fun nightWrapsPastMidnight() {
        // The default: midnight to 6am
        assertTrue(ScreenControl.isNight(0, 0, 6))
        assertTrue(ScreenControl.isNight(5, 0, 6))
        assertFalse(ScreenControl.isNight(6, 0, 6)) // The end hour is already day
        assertFalse(ScreenControl.isNight(23, 0, 6))

        // A window that crosses midnight
        assertTrue(ScreenControl.isNight(23, 22, 6))
        assertTrue(ScreenControl.isNight(0, 22, 6))
        assertTrue(ScreenControl.isNight(5, 22, 6))
        assertFalse(ScreenControl.isNight(21, 22, 6))
        assertFalse(ScreenControl.isNight(6, 22, 6))
    }

    @Test
    fun anEmptyWindowIsNeverNight() {
        for (hour in 0..23) assertFalse(ScreenControl.isNight(hour, 3, 3))
    }
}
