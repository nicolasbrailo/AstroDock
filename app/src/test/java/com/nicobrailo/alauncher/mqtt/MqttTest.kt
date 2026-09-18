package com.nicobrailo.alauncher.mqtt

import org.junit.Assert.assertEquals
import org.junit.Test

class MqttTest {
    @Test
    fun prefixAlwaysEndsWithOneSlash() {
        val default = "portalgo/"
        assertEquals("home/portal/", MqttSettings.normalizePrefix("home/portal/", default))
        assertEquals("home/portal/", MqttSettings.normalizePrefix("home/portal", default))
        assertEquals("home/portal/", MqttSettings.normalizePrefix("  /home/portal  ", default))
        assertEquals(default, MqttSettings.normalizePrefix("", default))
        assertEquals(default, MqttSettings.normalizePrefix(null, default))
    }

    @Test
    fun deviceNamesBecomeUsableTopicNames() {
        assertEquals("portalgo", MqttSettings.topicName("PortalGo"))
        assertEquals("nico-s-portal", MqttSettings.topicName("Nico's Portal"))
        assertEquals("kitchen.portal", MqttSettings.topicName("  Kitchen.Portal  "))
        assertEquals("portal", MqttSettings.topicName("#/+"))
    }

    @Test
    fun occupancyFollowsTheScreen() {
        val screensaverAfter = 60_000L
        assertEquals(
            Occupancy.Guess(false, "screen_off"),
            Occupancy.guess(screenOn = false, screenOnForMillis = 5_000, screensaverAfterMillis = screensaverAfter)
        )
        // On, but not yet long enough to prove the Portal is holding it on
        assertEquals(
            Occupancy.Guess(true, "screen_on"),
            Occupancy.guess(screenOn = true, screenOnForMillis = 5_000, screensaverAfterMillis = screensaverAfter)
        )
        assertEquals(
            Occupancy.Guess(true, "presence"),
            Occupancy.guess(screenOn = true, screenOnForMillis = 90_000, screensaverAfterMillis = screensaverAfter)
        )
        // Unknown screensaver delay: no claim beyond "the screen is on"
        assertEquals(
            Occupancy.Guess(true, "screen_on"),
            Occupancy.guess(screenOn = true, screenOnForMillis = 90_000, screensaverAfterMillis = -1)
        )
    }
}
