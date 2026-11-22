package com.nicobrailo.astrodock.wakeword

import org.junit.Assert.assertEquals
import org.junit.Test

class LevelTest {
    @Test
    fun measuresFullScaleAndSilence() {
        // A full scale square wave is 0 dBFS
        val full = Short.MAX_VALUE.toDouble()
        assertEquals(0.0, MicReader.dbfs(full * full * 100, 100), 0.01)
        // Half the amplitude is 6 dB down
        assertEquals(-6.02, MicReader.dbfs(full * full / 4 * 100, 100), 0.01)
        // Nothing at all doesn't come out as minus infinity
        assertEquals(MicReader.SILENCE_DBFS, MicReader.dbfs(0.0, 100), 0.0)
        assertEquals(MicReader.SILENCE_DBFS, MicReader.dbfs(0.0, 0), 0.0)
    }

    @Test
    fun drawsTheLevelAsABar() {
        assertEquals("░".repeat(20), levelBar(-96.0))
        assertEquals("█".repeat(20), levelBar(0.0))
        assertEquals("█".repeat(10) + "░".repeat(10), levelBar(-45.0))
        assertEquals(20, levelBar(-30.0).length)
    }
}
