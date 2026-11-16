package com.nicobrailo.astrodock

import com.nicobrailo.astrodock.SlideshowState.Companion.PAUSE_ALARM
import com.nicobrailo.astrodock.SlideshowState.Companion.PAUSE_TOUCH
import com.nicobrailo.astrodock.SlideshowState.NightPause
import com.nicobrailo.astrodock.SlideshowState.NightRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NightPauseTest {
    private fun rule(
        touchGrace: Boolean = false,
        alarmGrace: Boolean = false,
        inHours: Boolean = true,
        enabled: Boolean = true,
        admin: Boolean = true,
        settingsLoaded: Boolean = true,
    ) = NightRule(settingsLoaded, enabled, inHours, touchGrace, alarmGrace, admin)

    @Test
    fun nothingPausedWithoutAGrace() {
        assertNull(SlideshowState.nightPause(rule(), 1_000, 2_000))
    }

    @Test
    fun touchGrace() {
        assertEquals(NightPause(PAUSE_TOUCH, 1_000), SlideshowState.nightPause(rule(touchGrace = true), 1_000, null))
    }

    @Test
    fun alarmGrace() {
        assertEquals(NightPause(PAUSE_ALARM, 2_000), SlideshowState.nightPause(rule(alarmGrace = true), 0, 2_000))
    }

    @Test
    fun bothNameTheOneThatLastsLonger() {
        val both = rule(touchGrace = true, alarmGrace = true)
        assertEquals(NightPause(PAUSE_ALARM, 2_000), SlideshowState.nightPause(both, 1_000, 2_000))
        assertEquals(NightPause(PAUSE_TOUCH, 3_000), SlideshowState.nightPause(both, 3_000, 2_000))
    }

    @Test
    fun nothingPausedWhenTheRuleWouldNotApplyAnyway() {
        assertNull(SlideshowState.nightPause(rule(touchGrace = true, inHours = false), 1_000, null))
        assertNull(SlideshowState.nightPause(rule(touchGrace = true, enabled = false), 1_000, null))
        assertNull(SlideshowState.nightPause(rule(touchGrace = true, admin = false), 1_000, null))
        assertNull(SlideshowState.nightPause(rule(alarmGrace = true, settingsLoaded = false), 0, 2_000))
    }
}
