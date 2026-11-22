package com.nicobrailo.astrodock.wakeword

import android.util.Log
import org.json.JSONException
import org.json.JSONObject

// What AstroDock broadcasts about the device: its MQTT state record, sent on
// the device as well (app/.../mqtt/StateBroadcast.kt, whose names these must
// match). The listener is part of AstroDock, but in a process of its own, and
// this is all it knows of the rest. Only the parts it goes by are kept.
data class AstroDockState(
    // Whether somebody is in the room: from the Portal's camera, or a guess
    // from the screen on a device whose log AstroDock can't read. Null while
    // the camera can't see.
    val occupied: Boolean?,
    // privacy, lens_covered, sleep or unknown while the camera can't see
    val blindReason: String?,
    // AstroDock's pictures are on screen: the screen is on, no other app is
    // in front and the night cover is down
    val slideshowActive: Boolean,
    // idle, outgoing, incoming or in_call
    val callState: String,
    val nightApplies: Boolean,
) {
    companion object {
        const val ASTRODOCK_PACKAGE = "com.nicobrailo.astrodock"
        const val ACTION_STATE = "com.nicobrailo.astrodock.action.STATE"
        const val ACTION_REQUEST_STATE = "com.nicobrailo.astrodock.action.REQUEST_STATE"
        const val EXTRA_STATE = "state"
        // The other way: the wake word was heard and what follows is being
        // recorded (true), or that is over (false), for the slideshow to show
        const val ACTION_LISTENING = "com.nicobrailo.astrodock.action.LISTENING"
        const val EXTRA_LISTENING = "listening"
        // Signature level, declared by AstroDock
        const val PERMISSION = "com.nicobrailo.astrodock.permission.STATE"

        private const val TAG = "AstroDockState"

        // Null for a record that can't be read, which is then as good as none
        fun parse(text: String?): AstroDockState? {
            if (text == null) return null
            return try {
                val json = JSONObject(text)
                val occupancy = json.optJSONObject("occupancy")
                val slideshow = json.optJSONObject("slideshow")
                val call = json.optJSONObject("call")
                val night = json.optJSONObject("night")
                AstroDockState(
                    occupied = occupancy?.let { if (it.isNull("occupied")) null else it.optBoolean("occupied") },
                    blindReason = occupancy?.let { if (it.isNull("blind_reason")) null else it.optString("blind_reason") },
                    slideshowActive = slideshow?.optBoolean("active") ?: false,
                    callState = call?.optString("state", "idle") ?: "idle",
                    nightApplies = night?.optBoolean("applies") ?: false,
                )
            } catch (e: JSONException) {
                Log.w(TAG, "Can't read AstroDock's state", e)
                null
            }
        }
    }
}
