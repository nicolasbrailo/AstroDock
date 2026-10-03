package com.nicobrailo.astrodock.mqtt

import com.nicobrailo.astrodock.ScreenControl
import kotlin.math.abs

// The decisions behind the <prefix>state report that don't need a device, so
// they can be unit tested. StateReporter gathers the inputs and builds the JSON.
object DeviceState {
    // What the app wants the screen to be, and why. Both null when it has no
    // opinion, which is most of the time: the Portal decides.
    data class ScreenWish(val wanted: String?, val reason: String?)

    val NO_WISH = ScreenWish(null, null)

    // `hold` is the reason the app holds the screen on (ScreenControl.Hold),
    // `forcedOff` whether force_off switched it off and it hasn't come back on
    // since, and `night` whether the night rule applies.
    //
    // force_on and force_off undo each other, so at most one of them is set; an
    // explicit command wins over an install in progress, and anything that
    // holds the screen on wins over the night rule, which stays away from a
    // screen that is being used anyway.
    fun screenWish(hold: String?, forcedOff: Boolean, night: Boolean): ScreenWish = when {
        hold == ScreenControl.FORCE_ON -> ScreenWish("on", ScreenControl.FORCE_ON)
        forcedOff -> ScreenWish("off", ScreenControl.FORCE_OFF)
        hold != null -> ScreenWish("on", hold)
        night -> ScreenWish("off", ScreenControl.NIGHT)
        else -> NO_WISH
    }

    // Whether a reading moved far enough from the one last published to be
    // worth publishing again. The light sensor and the Wi-Fi signal report
    // every flicker, and the state is only published when it changes, so small
    // moves are held back until they add up. A change to or from unknown
    // always counts.
    fun significant(published: Float?, reading: Float?, absolute: Float, relative: Float = 0f): Boolean {
        if (published == null || reading == null) return published != reading
        val delta = abs(reading - published)
        return delta >= absolute && delta >= relative * abs(published)
    }
}
