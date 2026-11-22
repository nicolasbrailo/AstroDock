package com.nicobrailo.astrodock.wakeword

// Why the microphone is closed, or null when it may listen.
//
// It only listens while somebody is in view and AstroDock's pictures are on
// screen. The first keeps it off in an empty room, which is most of the day.
// The second is what keeps it out of the way of every other app: up to
// Android 9 only one app can record at a time, and the next one to try simply
// fails, so a WhatsApp call, BatiDoorLink talking to the door or AstroDock's
// own call would lose its microphone to us. Each of them comes to the front
// before it records, which takes the pictures off the screen.
enum class Refusal {
    DISABLED,
    NO_MICROPHONE_PERMISSION,
    // Nothing heard from AstroDock yet: not installed, or not running
    NO_STATE,
    CALL,
    NIGHT,
    // Another app in front, the screen off, or the night cover up
    NOT_ON_SCREEN,
    // The camera can't see (privacy mode mutes the microphone too)
    BLIND,
    NOBODY,
}

object ListenGate {
    // While its own settings screen is open it listens whatever AstroDock
    // says, and whether or not it is enabled, so it can be tried out: that
    // screen is in front, so no other app is about to record.
    fun refusal(
        enabled: Boolean, micGranted: Boolean, state: AstroDockState?, onOwnScreen: Boolean = false,
    ): Refusal? = when {
        onOwnScreen -> if (micGranted) null else Refusal.NO_MICROPHONE_PERMISSION
        !enabled -> Refusal.DISABLED
        !micGranted -> Refusal.NO_MICROPHONE_PERMISSION
        state == null -> Refusal.NO_STATE
        state.callState != "idle" -> Refusal.CALL
        state.nightApplies -> Refusal.NIGHT
        !state.slideshowActive -> Refusal.NOT_ON_SCREEN
        state.occupied == null -> Refusal.BLIND
        !state.occupied -> Refusal.NOBODY
        else -> null
    }
}
