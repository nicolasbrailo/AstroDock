package com.nicobrailo.astrodock.mqtt

// Whether someone is in the room, when the Portal's own camera presence
// detection can't be read: PortalLog reads it off the log, which needs the
// READ_LOGS grant, and without that this guesses from the screen instead.
//
// The detection's effect is visible: it keeps the screen on while it sees
// someone, and the device sleeps once it hasn't for a while. So the screen
// being on is the signal, and a screen still on past the screensaver delay
// means the Portal is holding it on for somebody. It can't tell a camera that
// is covered from an empty room.
//
// There is no mmWave sensor here, so the spec's distance_cm is never published.
object Occupancy {
    // The source when the verdict comes from the camera, through PortalLog
    const val CAMERA = "camera"

    data class Guess(val occupied: Boolean, val source: String)

    fun guess(screenOn: Boolean, screenOnForMillis: Long, screensaverAfterMillis: Long): Guess = when {
        !screenOn -> Guess(false, "screen_off")
        screensaverAfterMillis > 0 && screenOnForMillis > screensaverAfterMillis ->
            // Past the point where an untouched screen would have slept
            Guess(true, "presence")
        else -> Guess(true, "screen_on")
    }
}
