package com.nicobrailo.astrodock

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log

// What the app does to the screen, which is as little as possible: the Portal
// decides when the screensaver starts and when the screen goes off, from its
// own timers and its camera's presence detection. The app only switches the
// screen off for the night rule and the MQTT force_off command, and holds it
// on for an install in progress and for force_on.
//
// Earlier versions wrote `sleep_timeout` and `screen_off_timeout` themselves,
// fighting the Portal, which kept putting its own values back.
// tools/setup-device.sh restores the Portal's values on a device that still
// has ours.
object ScreenControl {
    private const val TAG = "ScreenControl"
    const val FORCE_ON = "force_on"

    // Whether `hour` falls in the night window, which usually wraps past
    // midnight (0 to 6 doesn't, 22 to 6 does). An empty window is never night.
    fun isNight(hour: Int, startHour: Int, endHour: Int): Boolean = when {
        startHour == endHour -> false
        startHour < endHour -> hour in startHour until endHour
        else -> hour >= startHour || hour < endHour
    }

    // True once adb has granted it (see tools/setup-device.sh); only high
    // contrast text (TextContrast) needs it
    fun canWriteSecureSettings(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    // Keeps the screen on while something the user is waiting for runs in
    // another app, such as the system installer: a keep-screen-on flag on our
    // own window stops working the moment that window is hidden, but a wake
    // lock doesn't. Always released again by the caller; the timeout is only a
    // backstop for a caller that dies first.
    //
    // `wakeUp` also switches the screen on if it is off, and ends a
    // screensaver that is running, ours or the Portal's: an incoming call
    // needs both, since an activity started under a screensaver stays hidden
    // behind it.
    @Suppress("DEPRECATION") // No replacement that works while another app is in front
    fun keepScreenOn(
        context: Context,
        reason: String,
        timeoutMillis: Long,
        wakeUp: Boolean = false,
    ): PowerManager.WakeLock? {
        val power = context.getSystemService(PowerManager::class.java) ?: return null
        val flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or (if (wakeUp) PowerManager.ACQUIRE_CAUSES_WAKEUP else 0)
        return try {
            power.newWakeLock(flags, "astrodock:$reason").apply {
                setReferenceCounted(false)
                acquire(timeoutMillis)
                Log.i(TAG, "Keeping the screen on: $reason")
                noteHold(this, reason, timeoutMillis)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Can't keep the screen on", e)
            null
        }
    }

    fun release(lock: PowerManager.WakeLock?) {
        if (lock?.isHeld == true) lock.release()
        if (lock != null && holds.remove(lock) != null) onHoldChanged?.invoke()
    }

    // Why the app is holding the screen on, and until when (elapsedRealtime),
    // for the MQTT state report. A forced one wins, being what was asked for.
    data class Hold(val reason: String, val until: Long)

    fun hold(): Hold? {
        // A lock that timed out lets go without telling anyone
        holds.keys.removeAll { !it.isHeld }
        return holds.values.firstOrNull { it.reason == FORCE_ON } ?: holds.values.firstOrNull()
    }

    // Called on the main thread whenever a hold starts or is released, but not
    // when one times out, which is what Hold.until is for
    var onHoldChanged: (() -> Unit)? = null

    private val holds = mutableMapOf<PowerManager.WakeLock, Hold>()

    private fun noteHold(lock: PowerManager.WakeLock, reason: String, timeoutMillis: Long) {
        holds[lock] = Hold(reason, SystemClock.elapsedRealtime() + timeoutMillis)
        onHoldChanged?.invoke()
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
                "astrodock:$FORCE_ON",
            ).apply {
                setReferenceCounted(false)
                acquire(timeoutMillis)
                Log.i(TAG, "Screen forced on")
                noteHold(this, FORCE_ON, timeoutMillis)
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
