package com.nicobrailo.astrodock.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WarmupTest {
    // A device nobody is using: screen off, nothing playing, app not running
    private fun skip(
        callActive: Boolean = false,
        musicActive: Boolean = false,
        hasSession: Boolean = false,
        screenOn: Boolean = false,
        slideshowShowing: Boolean = false,
        sinceTouchMillis: Long? = null,
    ) = Warmup.skipReason(callActive, musicActive, hasSession, screenOn, slideshowShowing, sinceTouchMillis)

    @Test
    fun warmsWithTheScreenOff() {
        assertNull(skip())
    }

    @Test
    fun warmsOverAnUntouchedSlideshow() {
        assertNull(skip(screenOn = true, slideshowShowing = true))
        assertNull(skip(screenOn = true, slideshowShowing = true, sinceTouchMillis = Warmup.TOUCH_MILLIS))
    }

    @Test
    fun anotherAppInFrontIsSomebodyUsingIt() {
        assertEquals("in_use", skip(screenOn = true, slideshowShowing = false))
    }

    @Test
    fun aRecentTouchIsSomebodyUsingIt() {
        assertEquals("in_use", skip(screenOn = true, slideshowShowing = true, sinceTouchMillis = 30_000))
    }

    @Test
    fun anOldTouchDoesntMatterWithTheScreenOff() {
        assertNull(skip(sinceTouchMillis = 30_000))
    }

    @Test
    fun aCallOrMusicIsLeftAlone() {
        assertEquals("call", skip(callActive = true, screenOn = true))
        assertEquals("music_playing", skip(musicActive = true))
    }

    @Test
    fun anAppAlreadyRunningNeedsNothing() {
        assertEquals("already_running", skip(hasSession = true))
    }
}
