package com.nicobrailo.astrodock.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallsTest {
    @Test
    fun sendsToTheRecipientsPrefix() {
        assertEquals("kitchen-portal/cmd/call/offer", Calls.topic("kitchen-portal/", CallVerb.OFFER))
        assertEquals("kitchen-portal/cmd/call/hangup", Calls.topic("kitchen-portal/", CallVerb.HANGUP))
    }

    @Test
    fun anOfferIsStaleAfterFifteenSecondsEitherWay() {
        assertFalse(Calls.isStale(1000, 1000))
        assertFalse(Calls.isStale(1000, 1015))
        assertTrue(Calls.isStale(1000, 1016))
        // A clock that is ahead is as wrong as one that is behind
        assertFalse(Calls.isStale(1015, 1000))
        assertTrue(Calls.isStale(1016, 1000))
        // No timestamp at all
        assertTrue(Calls.isStale(0, 1_790_000_000))
    }

    @Test
    fun takesAnOfferWhenNothingStandsInTheWay() {
        assertNull(Calls.refusal(enabled = true, allowed = true, canCapture = true, night = false, busy = false))
    }

    @Test
    fun givesTheReasonThatWontGoAwayFirst() {
        assertEquals(
            RejectReason.DISABLED,
            Calls.refusal(enabled = false, allowed = false, canCapture = false, night = true, busy = true),
        )
        assertEquals(
            RejectReason.NOT_ALLOWED,
            Calls.refusal(enabled = true, allowed = false, canCapture = false, night = true, busy = true),
        )
        assertEquals(
            RejectReason.UNAVAILABLE,
            Calls.refusal(enabled = true, allowed = true, canCapture = false, night = true, busy = true),
        )
        assertEquals(
            RejectReason.NIGHT,
            Calls.refusal(enabled = true, allowed = true, canCapture = true, night = true, busy = true),
        )
        assertEquals(
            RejectReason.BUSY,
            Calls.refusal(enabled = true, allowed = true, canCapture = true, night = false, busy = true),
        )
    }

    @Test
    fun refusesWhileThePortalCantSee() {
        assertEquals(
            RejectReason.PRIVACY,
            Calls.refusal(
                enabled = true, allowed = true, canCapture = false, night = true, busy = true,
                privacy = true, lensCovered = true,
            ),
        )
        assertEquals(
            RejectReason.UNAVAILABLE,
            Calls.refusal(enabled = true, allowed = true, canCapture = true, night = true, busy = true, lensCovered = true),
        )
        // Privacy mode is the Portal's, not the device's settings
        assertEquals(
            RejectReason.NOT_ALLOWED,
            Calls.refusal(enabled = true, allowed = false, canCapture = true, night = false, busy = false, privacy = true),
        )
    }

    @Test
    fun rejectReasonsRoundTrip() {
        for (reason in RejectReason.entries) assertEquals(reason, RejectReason.ofWire(reason.wire))
        assertNull(RejectReason.ofWire("nonsense"))
    }

    @Test
    fun anEmptyAllowListLetsAnyoneCall() {
        val allowList = Calls.parseAllowList("  ")
        assertTrue(allowList.isEmpty())
        assertTrue(Calls.isAllowed(allowList, "anyone/"))
    }

    @Test
    fun theAllowListIgnoresSlashesCaseAndSpaces() {
        val allowList = Calls.parseAllowList(" Kitchen-Portal , portaloft-portal/,,")
        assertEquals(setOf("kitchen-portal", "portaloft-portal"), allowList)
        assertTrue(Calls.isAllowed(allowList, "kitchen-portal/"))
        assertTrue(Calls.isAllowed(allowList, "PORTALOFT-PORTAL/"))
        assertFalse(Calls.isAllowed(allowList, "garage-portal/"))
    }

    @Test
    fun findsTheDeviceAnAvailabilityRecordBelongsTo() {
        assertEquals("kitchen-portal/", Calls.peerPrefix("kitchen-portal/availability", "availability"))
        // Deeper prefixes aren't what +/availability finds
        assertNull(Calls.peerPrefix("home/kitchen/availability", "availability"))
        assertNull(Calls.peerPrefix("kitchen-portal/state", "availability"))
        assertNull(Calls.peerPrefix("/availability", "availability"))
        assertNull(Calls.peerPrefix("kitchen-portal/xavailability", "availability"))
    }

    @Test
    fun aDeviceIsCalledByItsTopicPrefix() {
        assertEquals("portaloficina", CallPeer("portaloficina/", online = true, acceptsCalls = true).name)
        assertEquals("portaloficina", Calls.displayName("portaloficina/"))
    }

    @Test
    fun callsOnlineDevicesThatTakeCallsButNotItself() {
        val peers = listOf(
            CallPeer("me/", online = true, acceptsCalls = true),
            CallPeer("kitchen/", online = true, acceptsCalls = true),
            CallPeer("attic/", online = true, acceptsCalls = true),
            CallPeer("garage/", online = false, acceptsCalls = true),
            CallPeer("hall/", online = true, acceptsCalls = false),
        )
        assertEquals(listOf("attic/", "kitchen/"), Calls.callable(peers, "me/").map { it.prefix })
        // Before we know our own prefix, nobody is left out for being us
        assertEquals(3, Calls.callable(peers, null).size)
    }
}
