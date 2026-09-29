package com.nicobrailo.astrodock.mqtt

import org.junit.Assert.assertEquals
import org.junit.Test

class RetainedTopicsTest {
    @Test
    fun clearsLeftoversButNotWhatWeRepublish() {
        val found = setOf(
            "portaloft/state",
            "portaloft/state/bridge",
            "portaloft/state/occupancy",
            "portaloft/state/slideshow_active",
            "portaloft/cmd/ambience/next",
        )
        assertEquals(
            setOf("portaloft/state/occupancy", "portaloft/state/slideshow_active", "portaloft/cmd/ambience/next"),
            RetainedTopics.toClear(found, "portaloft/", keep = RetainedTopics.ours("portaloft/"))
        )
    }

    @Test
    fun leavesOtherDevicesUnderTheSamePrefixAlone() {
        val found = setOf("home/state/occupancy", "home/kitchen/state/bridge", "home/doctor")
        assertEquals(setOf("home/state/occupancy"), RetainedTopics.toClear(found, "home/"))
    }

    @Test
    fun anOldPrefixLosesEverythingOfOursButNotTheNewOne() {
        // Moving from "portal/" to "portal/office/", which sits inside it
        val found = setOf("portal/state", "portal/state/bridge", "portal/office/state/bridge")
        assertEquals(
            setOf("portal/state", "portal/state/bridge"),
            RetainedTopics.toClear(found, "portal/", skipPrefix = "portal/office/")
        )
    }
}
