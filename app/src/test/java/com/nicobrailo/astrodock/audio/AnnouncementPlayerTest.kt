package com.nicobrailo.astrodock.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AnnouncementPlayerTest {
    @Test
    fun scalesThePercentageToTheStreamsSteps() {
        assertEquals(0, AnnouncementPlayer.volumeIndex(0, 15))
        assertEquals(6, AnnouncementPlayer.volumeIndex(40, 15))
        assertEquals(8, AnnouncementPlayer.volumeIndex(50, 15))
        assertEquals(15, AnnouncementPlayer.volumeIndex(100, 15))
    }

    @Test
    fun anyVolumeAboveZeroIsAudible() {
        assertEquals(1, AnnouncementPlayer.volumeIndex(1, 15))
        assertEquals(1, AnnouncementPlayer.volumeIndex(3, 15))
    }

    @Test
    fun clampsOutOfRangePercentages() {
        assertEquals(0, AnnouncementPlayer.volumeIndex(-10, 15))
        assertEquals(15, AnnouncementPlayer.volumeIndex(150, 15))
    }
}
