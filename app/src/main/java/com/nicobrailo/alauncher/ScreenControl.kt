package com.nicobrailo.alauncher

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.util.Log

// The two bits of screen behaviour the app is allowed to control.
//
// `screen_off_timeout` is a system setting, so the user can grant it in the
// System tab. With screensavers turned off (tools/setup-device.sh) it is what
// switches the screen off, counted from the last user activity, which on the
// Portal includes its presence detection reporting someone in the room.
//
// The secure `sleep_timeout` would otherwise decide that, but only adb can
// write it and the Portal resets it, which is why screensavers are off. The
// night rule below doesn't depend on either: it uses the device admin.
object ScreenControl {
    private const val TAG = "ScreenControl"

    // Whether `hour` falls in the night window, which usually wraps past
    // midnight (0 to 6 doesn't, 22 to 6 does). An empty window is never night.
    fun isNight(hour: Int, startHour: Int, endHour: Int): Boolean = when {
        startHour == endHour -> false
        startHour < endHour -> hour in startHour until endHour
        else -> hour >= startHour || hour < endHour
    }

    // Tells the Portal how long to wait, after it last saw someone, before
    // switching the screen off. Does nothing if the setting is 0 or the
    // permission wasn't granted.
    fun applyScreenOffDelay(context: Context, settings: Settings) {
        if (settings.screenOffMinutes <= 0) return
        if (!AndroidSettings.System.canWrite(context)) return
        val millis = settings.screenOffMinutes * 60_000
        val current = AndroidSettings.System.getInt(
            context.contentResolver, AndroidSettings.System.SCREEN_OFF_TIMEOUT, -1
        )
        if (current == millis) return
        try {
            AndroidSettings.System.putInt(
                context.contentResolver, AndroidSettings.System.SCREEN_OFF_TIMEOUT, millis
            )
            Log.i(TAG, "Screen now switches off after ${settings.screenOffMinutes} min")
        } catch (e: SecurityException) {
            Log.w(TAG, "Can't set the screensaver delay", e)
        }
    }

    // Keeps the screen on while something the user is waiting for runs in
    // another app, such as the system installer: a keep-screen-on flag on our
    // own window stops working the moment that window is hidden, but a wake
    // lock doesn't. Always released again by the caller; the timeout is only a
    // backstop for a caller that dies first.
    @Suppress("DEPRECATION") // No replacement that works while another app is in front
    fun keepScreenOn(context: Context, reason: String, timeoutMillis: Long): PowerManager.WakeLock? {
        val power = context.getSystemService(PowerManager::class.java) ?: return null
        return try {
            power.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "alauncher:$reason").apply {
                setReferenceCounted(false)
                acquire(timeoutMillis)
                Log.i(TAG, "Keeping the screen on: $reason")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Can't keep the screen on", e)
            null
        }
    }

    fun release(lock: PowerManager.WakeLock?) {
        if (lock?.isHeld == true) lock.release()
    }

    // Wakes the screen and holds it on, for the MQTT force_on command. The
    // Portal's own timeouts take over again when this is released or expires.
    @Suppress("DEPRECATION") // Deprecated, but it is how an app wakes the screen
    fun forceScreenOn(context: Context, timeoutMillis: Long) {
        val power = context.getSystemService(PowerManager::class.java) ?: return
        release(forcedOn)
        forcedOn = try {
            power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "alauncher:force_on",
            ).apply {
                setReferenceCounted(false)
                acquire(timeoutMillis)
                Log.i(TAG, "Screen forced on")
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Can't force the screen on", e)
            null
        }
    }

    fun releaseForcedOn() {
        release(forcedOn)
        forcedOn = null
    }

    // Held by forceScreenOn, so force_off can let go of it again
    private var forcedOn: PowerManager.WakeLock? = null

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
