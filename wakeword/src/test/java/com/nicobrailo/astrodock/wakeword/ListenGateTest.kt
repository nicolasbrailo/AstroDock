package com.nicobrailo.astrodock.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListenGateTest {
    // Somebody in view of the home screen, in the day, with no call
    private val listening = AstroDockState(
        occupied = true, blindReason = null, slideshowActive = true, callState = "idle", nightApplies = false,
    )

    private fun refusal(state: AstroDockState?, enabled: Boolean = true, mic: Boolean = true) =
        ListenGate.refusal(enabled, mic, state)

    @Test
    fun listensWithSomebodyInViewOfThePictures() {
        assertNull(refusal(listening))
    }

    @Test
    fun needsToBeOnAndAllowedTheMicrophone() {
        assertEquals(Refusal.DISABLED, refusal(listening, enabled = false))
        assertEquals(Refusal.NO_MICROPHONE_PERMISSION, refusal(listening, mic = false))
        // Off comes first: that is what the user did on purpose
        assertEquals(Refusal.DISABLED, refusal(listening, enabled = false, mic = false))
    }

    @Test
    fun listensWhateverAstroDockSaysWhileItsScreenIsOpen() {
        for (state in listOf(null, listening.copy(occupied = false, slideshowActive = false, nightApplies = true))) {
            assertNull(ListenGate.refusal(enabled = false, micGranted = true, state = state, onOwnScreen = true))
        }
        assertEquals(
            Refusal.NO_MICROPHONE_PERMISSION,
            ListenGate.refusal(enabled = true, micGranted = false, state = listening, onOwnScreen = true),
        )
    }

    @Test
    fun waitsForAstroDock() {
        assertEquals(Refusal.NO_STATE, refusal(null))
    }

    @Test
    fun leavesTheMicrophoneToAnotherAppInFront() {
        assertEquals(Refusal.NOT_ON_SCREEN, refusal(listening.copy(slideshowActive = false)))
        // A call says so even before its screen is up
        for (call in listOf("outgoing", "incoming", "in_call")) {
            assertEquals(Refusal.CALL, refusal(listening.copy(callState = call)))
        }
    }

    @Test
    fun staysOffAtNight() {
        assertEquals(Refusal.NIGHT, refusal(listening.copy(nightApplies = true)))
    }

    @Test
    fun onlyListensWithSomebodySeen() {
        assertEquals(Refusal.NOBODY, refusal(listening.copy(occupied = false)))
        // A camera that can't see isn't somebody seen
        assertEquals(Refusal.BLIND, refusal(listening.copy(occupied = null, blindReason = "lens_covered")))
        assertEquals(Refusal.BLIND, refusal(listening.copy(occupied = null, blindReason = "privacy")))
    }
}
