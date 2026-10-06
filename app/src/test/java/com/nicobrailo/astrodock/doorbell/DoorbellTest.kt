package com.nicobrailo.astrodock.doorbell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DoorbellTest {
    private val main = "rtsp://admin:@10.10.30.11:554/h264Preview_01_main"
    private val sub = "rtsp://admin:@10.10.30.11:554/h264Preview_01_sub"

    @Test
    fun prefersTheSubStream() {
        assertEquals(sub, Doorbell.pickStream(mapOf("main" to main, "sub" to sub)))
    }

    @Test
    fun takesWhateverThereIs() {
        assertEquals(main, Doorbell.pickStream(mapOf("main" to main)))
        assertEquals(main, Doorbell.pickStream(mapOf("fluent" to main)))
    }

    @Test
    fun onlyTakesRtsp() {
        assertEquals(main, Doorbell.pickStream(mapOf("sub" to "http://10.10.30.11/snap.jpg", "main" to main)))
        assertNull(Doorbell.pickStream(mapOf("sub" to "", "main" to "http://10.10.30.11/")))
        assertNull(Doorbell.pickStream(emptyMap()))
    }
}
