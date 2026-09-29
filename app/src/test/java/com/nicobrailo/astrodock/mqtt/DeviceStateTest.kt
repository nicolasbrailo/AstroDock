package com.nicobrailo.astrodock.mqtt

import com.nicobrailo.astrodock.ScreenControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceStateTest {
    @Test
    fun noOpinionLeavesTheScreenToThePortal() {
        assertEquals(DeviceState.NO_WISH, DeviceState.screenWish(hold = null, forcedOff = false, night = false))
    }

    @Test
    fun commandsWinOverEverythingElse() {
        assertEquals(
            DeviceState.ScreenWish("on", "force_on"),
            DeviceState.screenWish(hold = ScreenControl.FORCE_ON, forcedOff = false, night = true)
        )
        assertEquals(
            DeviceState.ScreenWish("off", "force_off"),
            DeviceState.screenWish(hold = "install", forcedOff = true, night = false)
        )
    }

    @Test
    fun anInstallHoldsOffTheNightRule() {
        assertEquals(
            DeviceState.ScreenWish("on", "install"),
            DeviceState.screenWish(hold = "install", forcedOff = false, night = true)
        )
        assertEquals(
            DeviceState.ScreenWish("off", "night"),
            DeviceState.screenWish(hold = null, forcedOff = false, night = true)
        )
    }

    @Test
    fun smallMovesAreHeldBack() {
        // Wi-Fi: 5 dBm either way
        assertFalse(DeviceState.significant(-60f, -63f, absolute = 5f))
        assertTrue(DeviceState.significant(-60f, -65f, absolute = 5f))
        assertTrue(DeviceState.significant(-60f, -55f, absolute = 5f))

        // Light: both the absolute and the relative step, so a dark room
        // doesn't publish every lux and a bright one every hundred
        assertFalse(DeviceState.significant(0f, 3f, absolute = 5f, relative = 0.25f))
        assertTrue(DeviceState.significant(0f, 5f, absolute = 5f, relative = 0.25f))
        assertFalse(DeviceState.significant(500f, 600f, absolute = 5f, relative = 0.25f))
        assertTrue(DeviceState.significant(500f, 630f, absolute = 5f, relative = 0.25f))
    }

    @Test
    fun unknownAlwaysCounts() {
        assertTrue(DeviceState.significant(null, -60f, absolute = 5f))
        assertTrue(DeviceState.significant(-60f, null, absolute = 5f))
        assertFalse(DeviceState.significant(null, null, absolute = 5f))
    }
}
