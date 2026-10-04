package com.nicobrailo.astrodock.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.preference.PreferenceManager

// Whether this device takes part in calls, who may call it, and whether it
// answers by itself. Edited in the Portalcom app (CallsActivity), or by
// tools/push-config.sh, whose names for the keys must match these.
//
// Read as each call starts rather than kept, like the audio announcements
// switch: changing them has nothing to do with the connection. Only the main
// process reads them: the :call process would see its own cached copy of the
// preferences, so CallRouter hands CallActivity what it needs.
data class CallSettings(
    val enabled: Boolean,
    // Caller prefixes, normalised; empty means anyone (see Calls.parseAllowList)
    val allowList: Set<String>,
    // Answers by itself after ringing for autoAnswerSeconds, rather than
    // waiting for someone to tap Answer
    val autoAnswer: Boolean,
    val autoAnswerSeconds: Int,
) {
    // How long an incoming call rings before answering, null for until
    // someone answers it
    val answerAfterSeconds: Int? get() = Calls.answerAfterSeconds(autoAnswer, autoAnswerSeconds)

    companion object {
        const val KEY_ENABLED = "calls_enabled"
        const val KEY_ALLOWED = "calls_allowed"
        const val KEY_AUTO_ANSWER = "calls_auto_answer"
        // An int, from the slider
        const val KEY_AUTO_ANSWER_SECONDS = "calls_auto_answer_seconds"

        fun load(context: Context): CallSettings {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            return CallSettings(
                enabled = prefs.getBoolean(KEY_ENABLED, false),
                allowList = Calls.parseAllowList(prefs.getString(KEY_ALLOWED, null).orEmpty()),
                autoAnswer = prefs.getBoolean(KEY_AUTO_ANSWER, true),
                autoAnswerSeconds = prefs.getInt(KEY_AUTO_ANSWER_SECONDS, Calls.AUTO_ANSWER_DEFAULT_SECONDS)
                    .coerceIn(0, Calls.AUTO_ANSWER_MAX_SECONDS),
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
