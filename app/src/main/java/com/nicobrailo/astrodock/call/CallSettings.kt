package com.nicobrailo.astrodock.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.preference.PreferenceManager

// Whether this device takes part in calls, and who may call it. Edited in the
// MQTT tab (res/xml/mqtt_preferences.xml), since calls are set up over the
// broker; the keys below must match that XML.
//
// Read as each call starts rather than kept, like the audio announcements
// switch: changing them has nothing to do with the connection.
data class CallSettings(
    val enabled: Boolean,
    // Caller prefixes, normalised; empty means anyone (see Calls.parseAllowList)
    val allowList: Set<String>,
) {
    companion object {
        const val KEY_ENABLED = "calls_enabled"
        const val KEY_ALLOWED = "calls_allowed"

        fun load(context: Context): CallSettings {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            return CallSettings(
                enabled = prefs.getBoolean(KEY_ENABLED, false),
                allowList = Calls.parseAllowList(prefs.getString(KEY_ALLOWED, null).orEmpty()),
            )
        }

        // What a call needs from the user, granted in the System tab or by
        // tools/push-config.sh --calls-enabled true
        val PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

        fun canCapture(context: Context): Boolean = PERMISSIONS.all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
