package com.nicobrailo.astrodock

import com.nicobrailo.astrodock.ScreenControl.NIGHT_BUTTON_GRACE_MILLIS
import com.nicobrailo.astrodock.ScreenControl.NIGHT_RELOCK_MILLIS
import com.nicobrailo.astrodock.presence.PortalPresence.Blind
import com.nicobrailo.astrodock.presence.PortalPresence.Verdict
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

    private val somebody = Verdict(true, null)
    private val nobody = Verdict(false, null)

    @Test
    fun withoutTheCameraTheNightRuleLocksEveryTenMinutes() {
        for (camera in listOf(null, Verdict(null, Blind.UNKNOWN))) {
            assertTrue(ScreenControl.nightLockNow(null, camera, null))
            assertFalse(ScreenControl.nightLockNow(NIGHT_RELOCK_MILLIS - 1, camera, null))
            assertTrue(ScreenControl.nightLockNow(NIGHT_RELOCK_MILLIS, camera, null))
        }
    }

    @Test
    fun withTheCameraItLocksOnceNobodyIsSeen() {
        // Somebody in view: the Portal would only wake it again
        assertFalse(ScreenControl.nightLockNow(null, somebody, null))
        assertFalse(ScreenControl.nightLockNow(NIGHT_RELOCK_MILLIS * 10, somebody, null))
        // Nobody: at once, however recently it last did
        assertTrue(ScreenControl.nightLockNow(1_000, nobody, null))
    }

    @Test
    fun aBlindCameraWontWakeIt() {
        for (blind in listOf(Blind.PRIVACY, Blind.LENS_COVERED, Blind.SLEEP)) {
            assertTrue(ScreenControl.nightLockNow(1_000, Verdict(null, blind), null))
        }
    }

    @Test
    fun aButtonHoldsItOffForAWhile() {
        assertFalse(ScreenControl.nightLockNow(null, nobody, 0))
        assertFalse(ScreenControl.nightLockNow(null, Verdict(null, Blind.LENS_COVERED), NIGHT_BUTTON_GRACE_MILLIS - 1))
        assertTrue(ScreenControl.nightLockNow(null, nobody, NIGHT_BUTTON_GRACE_MILLIS))
    }
}
