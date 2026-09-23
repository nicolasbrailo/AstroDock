package com.nicobrailo.astrodock

import android.content.Context
import android.provider.Settings as AndroidSettings
import android.util.Log

// High contrast text: the accessibility setting that draws every string with a
// contrasting outline.
//
// The Portal's theme for the framework (the RRO com.facebook.aloha.rro.niu.android)
// paints the system installer's text in the colour of whatever is behind it, so
// confirming an install -- including the app updating itself from the Apps tab
// -- means tapping a blank white page. Dropping that overlay isn't an option,
// since the keyboard needs it (see tools/setup-device.sh). The outline is
// the fix: it can't make the colours right, but no colour can hide text once
// it is on.
//
// It is a secure setting, so it needs the same adb grant as sleep_timeout, and
// it applies to every app on the device. That is why it is a switch the user
// decides in the System tab rather than something turned on quietly.
object TextContrast {
    private const val TAG = "TextContrast"

    // Hidden framework constant (Settings.Secure.HIGH_TEXT_CONTRAST_ENABLED)
    private const val HIGH_TEXT_CONTRAST = "high_text_contrast_enabled"

    fun isEnabled(context: Context): Boolean =
        AndroidSettings.Secure.getInt(context.contentResolver, HIGH_TEXT_CONTRAST, 0) == 1

    // True if the setting was written. Needs the same grant as the screen off
    // delay, which the System tab shows right above this one.
    fun setEnabled(context: Context, enabled: Boolean): Boolean {
        if (!ScreenControl.canWriteSecureSettings(context)) return false
        return try {
            AndroidSettings.Secure.putInt(
                context.contentResolver,
                HIGH_TEXT_CONTRAST,
                if (enabled) 1 else 0,
            )
            Log.i(TAG, "High contrast text ${if (enabled) "on" else "off"}")
            true
        } catch (e: SecurityException) {
            // The grant was taken away since it was last checked
            Log.w(TAG, "Can't write $HIGH_TEXT_CONTRAST", e)
            false
        }
    }
}
