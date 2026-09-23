package com.nicobrailo.astrodock.media

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionActivityTest {
    private val paused = SessionSnapshot(state = 2, position = 110_715, activeItem = 148, title = "Passage")

    @Test
    fun `the first session seen starts from its own update time`() {
        val activity = SessionActivity()
        assertEquals(500L, activity.observe("com.spotify.music", paused, now = 1_000, reportedAt = 500))
    }

    @Test
    fun `an update time that is missing or in the future counts as now`() {
        assertEquals(1_000L, SessionActivity().observe("a", paused, now = 1_000, reportedAt = 0))
        assertEquals(1_000L, SessionActivity().observe("a", paused, now = 1_000, reportedAt = 5_000))
    }

    @Test
    fun `the same state said again is not a change`() {
        // Spotify re-sends its paused state every few minutes with a fresh
        // update time, and that must not keep the panel up
        val activity = SessionActivity()
        activity.observe("com.spotify.music", paused, now = 1_000, reportedAt = 1_000)
        assertEquals(1_000L, activity.observe("com.spotify.music", paused, now = 600_000, reportedAt = 600_000))
        assertEquals(1_000L, activity.observe("com.spotify.music", paused, now = 1_200_000, reportedAt = 1_200_000))
    }

    @Test
    fun `anything different from the same app is a change`() {
        val activity = SessionActivity()
        activity.observe("a", paused, now = 1_000, reportedAt = 1_000)
        assertEquals(2_000L, activity.observe("a", paused.copy(state = 3), now = 2_000, reportedAt = 2_000))
        assertEquals(3_000L, activity.observe("a", paused.copy(state = 3, position = 5), now = 3_000, reportedAt = 0))
        assertEquals(4_000L, activity.observe("a", paused.copy(state = 3, position = 5, activeItem = 149), now = 4_000, reportedAt = 0))
        assertEquals(5_000L, activity.observe("a", paused.copy(state = 3, position = 5, activeItem = 149, title = "Other"), now = 5_000, reportedAt = 0))
    }

    @Test
    fun `another app starts the clock again`() {
        val activity = SessionActivity()
        activity.observe("com.spotify.music", paused, now = 1_000, reportedAt = 1_000)
        // Even with an identical snapshot and an older update time of its own
        assertEquals(9_000L, activity.observe("org.jellyfin.mobile", paused, now = 9_000, reportedAt = 100))
    }
}
