package com.nicobrailo.astrodock.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnouncementTest {
    private val owner = Any()

    @Test
    fun anEmptyMessageClears() {
        assertNull(Announcement.of("  ", 30, null, 1000))
    }

    @Test
    fun aTimeoutEndsIt() {
        val shown = Announcement.of("Hello", 30, null, 1000)!!
        assertTrue(shown.visibleAt(30_999))
        assertFalse(shown.visibleAt(31_000))
    }

    @Test
    fun noTimeoutStaysUp() {
        val shown = Announcement.of("Hello", 0, null, 1000)!!
        assertNull(shown.until)
        assertTrue(shown.visibleAt(Long.MAX_VALUE))
    }

    @Test
    fun theOwnerStartsTheCountdown() {
        val shown = Announcement.of("Ringing", 0, owner, 1000)
        val ending = Announcement.ending(shown, owner, 10, 5000)!!
        assertEquals("Ringing", ending.message)
        assertEquals(15_000L, ending.until)
    }

    @Test
    fun endingWorksWithNothingOnScreen() {
        // The doorbell's case: the end comes while another app is in front,
        // and the slideshow coming back has to find it ended
        val shown = Announcement.of("Ringing", 0, owner, 1000)
        val ending = Announcement.ending(shown, owner, 10, 5000)!!
        assertFalse(ending.visibleAt(60_000))
    }

    @Test
    fun aNewerAnnouncementStays() {
        val newer = Announcement.of("Dinner", 0, null, 2000)
        assertSame(newer, Announcement.ending(newer, owner, 10, 5000))
        val otherOwners = Announcement.of("Alarm", 0, Any(), 2000)
        assertSame(otherOwners, Announcement.ending(otherOwners, owner, 10, 5000))
    }

    @Test
    fun anExpiredOneIsNotExtended() {
        val shown = Announcement(message = "Old", owner = owner, until = 2000)
        assertSame(shown, Announcement.ending(shown, owner, 10, 5000))
    }

    @Test
    fun endingNothingIsNothing() {
        assertNull(Announcement.ending(null, owner, 10, 5000))
    }
}
