package com.nicobrailo.alauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureHistoryTest {
    @Test
    fun emptyHistoryHasNothingToShow() {
        val h = PictureHistory<Int>(3)
        assertNull(h.current)
        assertTrue(h.atNewest)
        assertNull(h.back())
        assertNull(h.forward())
    }

    @Test
    fun backStopsAtOldestKeptPicture() {
        val h = PictureHistory<Int>(3)
        (1..5).forEach(h::add) // Keeps 3, 4, 5
        assertEquals(4, h.back())
        assertEquals(3, h.back())
        assertNull(h.back())
        assertEquals(3, h.current)
    }

    @Test
    fun forwardWalksKeptPicturesThenAsksForANewOne() {
        val h = PictureHistory<Int>(3)
        (1..3).forEach(h::add)
        h.back()
        h.back()
        assertEquals(2, h.forward())
        assertEquals(3, h.forward())
        assertNull(h.forward())
        assertTrue(h.atNewest)
        h.add(4)
        assertEquals(4, h.current)
        assertEquals(3, h.back())
        assertEquals(2, h.back())
        assertNull(h.back())
    }

    @Test
    fun peekDoesNotMove() {
        val h = PictureHistory<Int>(3)
        assertNull(h.peekBack())
        assertNull(h.peekForward())
        (1..3).forEach(h::add)
        assertEquals(2, h.peekBack())
        assertNull(h.peekForward())
        h.back()
        assertEquals(1, h.peekBack())
        assertEquals(3, h.peekForward())
        assertEquals(2, h.current)
        h.back()
        assertNull(h.peekBack())
    }

    @Test(expected = IllegalStateException::class)
    fun addWhileBrowsingBackFails() {
        val h = PictureHistory<Int>(3)
        h.add(1)
        h.add(2)
        h.back()
        h.add(3)
    }
}
