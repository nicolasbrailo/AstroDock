package com.nicobrailo.alauncher

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.provider.Settings as AndroidSettings
import android.util.Log

// The two bits of screen behaviour the app is allowed to control.
//
// `screen_off_timeout` (when the screensaver starts) is a system setting, so
// the user can grant it in the System tab. `sleep_timeout` (when the Portal
// gives up on presence and switches the screen off) is a secure setting that
// only adb can write, and on the Portal it doesn't stick anyway, so the night
// rule below turns the screen off itself instead, through the device admin.
object ScreenControl {
    private const val TAG = "ScreenControl"

    // Whether `hour` falls in the night window, which usually wraps past
    // midnight (0 to 6 doesn't, 22 to 6 does). An empty window is never night.
    fun isNight(hour: Int, startHour: Int, endHour: Int): Boolean = when {
        startHour == endHour -> false
        startHour < endHour -> hour in startHour until endHour
        else -> hour >= startHour || hour < endHour
    }

    // Tells the Portal how long to wait before starting the screensaver.
    // Does nothing if the setting is 0 or the permission wasn't granted.
    fun applyScreensaverDelay(context: Context, settings: Settings) {
        if (settings.screensaverMinutes <= 0) return
        if (!AndroidSettings.System.canWrite(context)) return
        val millis = settings.screensaverMinutes * 60_000
        val current = AndroidSettings.System.getInt(
            context.contentResolver, AndroidSettings.System.SCREEN_OFF_TIMEOUT, -1
        )
        if (current == millis) return
        try {
            AndroidSettings.System.putInt(
                context.contentResolver, AndroidSettings.System.SCREEN_OFF_TIMEOUT, millis
            )
            Log.i(TAG, "Screensaver now starts after ${settings.screensaverMinutes} min")
        } catch (e: SecurityException) {
            Log.w(TAG, "Can't set the screensaver delay", e)
        }
    }

    // True once the user has activated the device admin in the System tab
    fun canTurnScreenOff(context: Context): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        return dpm?.isAdminActive(ScreenAdminReceiver.component(context)) == true
    }

    // Switches the screen off. The Portal's presence detection may well wake it
    // again, and the night check then switches it off once more.
    fun turnScreenOff(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        try {
            dpm.lockNow()
        } catch (e: SecurityException) {
            // The admin was deactivated since it was last checked
            Log.w(TAG, "Can't turn the screen off", e)
        }
    }
}
