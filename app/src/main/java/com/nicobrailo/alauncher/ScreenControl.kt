package com.nicobrailo.alauncher

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.util.Log

// The two bits of screen behaviour the app is allowed to control.
//
// The Portal reports "someone is here" as an ambient-mode poke: it keeps a
// running screensaver alive, but it does not hold an awake screen on. So the
// screensaver stays enabled, and what decides when the screen goes dark is the
// secure `sleep_timeout`, counted from the last of those pokes.
//
// Only adb can grant writing secure settings (tools/setup-device.sh does it),
// and the Portal resets `sleep_timeout` on its own, so the app writes it again
// whenever the slideshow starts and every half minute after that. Without the
// grant nothing happens and the Portal's own 20 minutes apply.
//
// `screen_off_timeout` (a system setting, granted in the System tab) only
// decides when the screensaver starts, and is kept below the screen-off delay
// so the slideshow reaches ambient mode before the screen goes off.
object ScreenControl {
    private const val TAG = "ScreenControl"

    // Hidden framework constant
    private const val SLEEP_TIMEOUT = "sleep_timeout"

    // How long before the slideshow becomes the screensaver. Short, so ambient
    // mode (where presence keeps the screen on) is reached quickly.
    private const val SCREENSAVER_AFTER_MILLIS = 60_000

    // Whether `hour` falls in the night window, which usually wraps past
    // midnight (0 to 6 doesn't, 22 to 6 does). An empty window is never night.
    fun isNight(hour: Int, startHour: Int, endHour: Int): Boolean = when {
        startHour == endHour -> false
        startHour < endHour -> hour in startHour until endHour
        else -> hour >= startHour || hour < endHour
    }

    // Tells the Portal how long to wait, after it last saw someone, before
    // switching the screen off. Does nothing if the setting is 0, and only does
    // as much as the granted permissions allow.
    fun applyScreenOffDelay(context: Context, settings: Settings) {
        if (settings.screenOffMinutes <= 0) return
        val millis = settings.screenOffMinutes * 60_000

        if (canWriteSecureSettings(context)) {
            writeIfDifferent(context, secure = true, SLEEP_TIMEOUT, millis) {
                Log.i(TAG, "Screen now switches off ${settings.screenOffMinutes} min after the last person is seen")
            }
        }
        // The screensaver has to be running by then, or presence never keeps
        // the screen on in the first place
        if (AndroidSettings.System.canWrite(context)) {
            val screensaverAfter = minOf(millis, SCREENSAVER_AFTER_MILLIS)
            writeIfDifferent(context, secure = false, AndroidSettings.System.SCREEN_OFF_TIMEOUT, screensaverAfter) {
                Log.i(TAG, "Screensaver now starts after ${screensaverAfter / 1000}s")
            }
        }
    }

    // True once adb has granted it (see tools/setup-device.sh); without it the
    // Portal's own screen-off delay applies
    fun canWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    private fun writeIfDifferent(
        context: Context,
        secure: Boolean,
        key: String,
        value: Int,
        onWritten: () -> Unit,
    ) {
        val resolver = context.contentResolver
        val current =
            if (secure) AndroidSettings.Secure.getInt(resolver, key, -1)
            else AndroidSettings.System.getInt(resolver, key, -1)
        if (current == value) return
        try {
            if (secure) AndroidSettings.Secure.putInt(resolver, key, value)
            else AndroidSettings.System.putInt(resolver, key, value)
            onWritten()
        } catch (e: SecurityException) {
            Log.w(TAG, "Can't write $key", e)
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
