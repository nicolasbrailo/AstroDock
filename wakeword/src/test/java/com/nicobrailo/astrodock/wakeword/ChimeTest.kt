package com.nicobrailo.astrodock.wakeword

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ChimeTest {
    private val samples = Chime.samples(Chime.SAMPLE_RATE)

    @Test
    fun lastsUnderASecond() {
        assertTrue(samples.size in Chime.SAMPLE_RATE / 2 until Chime.SAMPLE_RATE)
    }

    @Test
    fun startsAndEndsQuietly() {
        // No click at either end
        assertEquals(0, samples.first().toInt())
        assertTrue(samples.takeLast(100).all { abs(it.toInt()) < 1000 })
    }

    @Test
    fun isAudibleButNotClipped() {
        val peak = samples.maxOf { abs(it.toInt()) }
        assertTrue("peak $peak", peak > Short.MAX_VALUE / 8)
        assertTrue("peak $peak", peak < Short.MAX_VALUE)
    }
}
