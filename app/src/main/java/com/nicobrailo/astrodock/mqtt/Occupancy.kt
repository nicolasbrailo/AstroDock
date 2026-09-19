package com.nicobrailo.astrodock.mqtt

// Whether someone is in the room, as well as this device can tell.
//
// The Portal's own camera presence detection is off limits to us (see the
// Portal notes in AGENTS.md), but its effect is visible: it keeps the screen
// awake while it sees someone, and the device sleeps once it hasn't for a
// while. So the screen being on is the signal, and a screen still on past the
// screensaver delay means the Portal is actively holding it on for somebody.
//
// There is no mmWave sensor here, so the spec's distance_cm is never published.
object Occupancy {
    data class Guess(val occupied: Boolean, val source: String)

    fun guess(screenOn: Boolean, screenOnForMillis: Long, screensaverAfterMillis: Long): Guess = when {
        !screenOn -> Guess(false, "screen_off")
        screensaverAfterMillis > 0 && screenOnForMillis > screensaverAfterMillis ->
            // Past the point where an untouched screen would have slept
            Guess(true, "presence")
        else -> Guess(true, "screen_on")
    }
}
