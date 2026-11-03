package com.nicobrailo.astrodock.alarm

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.nicobrailo.astrodock.SlideshowState
import com.nicobrailo.astrodock.call.CallRouter
import com.nicobrailo.astrodock.mqtt.StateReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Whether to open the alarm's app a minute before it rings, as pure functions
// so they can be unit tested.
//
// Spotify's process is usually gone by the morning (the system kills it as a
// cached process during the night), and waking it from the background with a
// Play key runs into Android's limits on starting services from there. Opened
// as an activity it starts properly, and a minute later the alarm finds its
// session ready. Opening it shows it on screen, so it is only done while
// nobody is using the device.
object Warmup {
    // The apps worth opening early. Spotify is the one seen failing to start
    // in time; another app might do something on being opened, such as play.
    val APPS = setOf("com.spotify.music")
    const val LEAD_MILLIS = 60_000L
    // A touch on the slideshow this recent is somebody using the device
    const val TOUCH_MILLIS = 2 * 60_000L

    // Why not to open the app now, or null to go ahead. `sinceTouchMillis` is
    // null if the slideshow hasn't been touched since the app started.
    fun skipReason(
        callActive: Boolean,
        musicActive: Boolean,
        hasSession: Boolean,
        screenOn: Boolean,
        slideshowShowing: Boolean,
        sinceTouchMillis: Long?,
    ): String? = when {
        callActive -> "call"
        // Somebody listening, to this app or another
        musicActive -> "music_playing"
        // Its session is there already, which is all the warm up is for
        hasSession -> "already_running"
        // Another app in front of a screen that is on: somebody using it
        screenOn && !slideshowShowing -> "in_use"
        screenOn && sinceTouchMillis != null && sinceTouchMillis < TOUCH_MILLIS -> "in_use"
        else -> null
    }
}

// Opens the app for an alarm about to ring (see Warmup), then goes back to
// the home screen once its session is up. What it did goes in the alarm's
// report (AlarmRinger.noteWarmup): "warmed", "no_session" (opened, but no
// session showed up in time, or the sessions can't be read), "failed", or the
// reason it was skipped.
object AlarmWarmup {
    private const val TAG = "AlarmWarmup"
    // Spotify took about 2s from a dead process; well within the minute
    private const val SESSION_WAIT_MILLIS = 20_000L
    // Without notification access there is no telling when it is up
    private const val BLIND_WAIT_MILLIS = 10_000L
    private const val WAKE_LOCK_MILLIS = SESSION_WAIT_MILLIS + 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Called on the main thread by AlarmReceiver
    fun warm(context: Context, alarm: Alarm) {
        val app = context.applicationContext
        val packageName = alarm.app?.takeIf { it in Warmup.APPS } ?: return
        val reporter = StateReporter.get(app)
        val starter = MediaStarter(app)
        val skip = Warmup.skipReason(
            callActive = CallRouter.get(app).active,
            musicActive = app.getSystemService(AudioManager::class.java)?.isMusicActive == true,
            hasSession = starter.hasSession(packageName),
            screenOn = reporter.isScreenOn,
            slideshowShowing = reporter.isSlideshowShowing,
            sinceTouchMillis = SlideshowState.shared.lastTouchAt.takeIf { it > 0 }
                ?.let { SystemClock.elapsedRealtime() - it },
        )
        if (skip != null) {
            Log.i(TAG, "Not opening $packageName for the ${alarm.time} alarm: $skip")
            AlarmRinger.noteWarmup(alarm, skip)
            return
        }
        val launch = app.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (launch == null) {
            AlarmRinger.noteWarmup(alarm, "failed")
            return
        }
        // The system's wake lock for the alarm ends with the receiver, and
        // the screen is usually off, so the CPU could sleep while the app
        // starts
        val awake = app.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "astrodock:alarm_warmup")
            ?.apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_MILLIS)
            }
        Log.i(TAG, "Opening $packageName for the ${alarm.time} alarm")
        try {
            // From the background, which needs the "display over other apps"
            // grant from Android 10, as for the alarm's own links
            app.startActivity(launch)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Can't open $packageName", e)
            AlarmRinger.noteWarmup(alarm, "failed")
            awake?.release()
            return
        }
        AlarmRinger.noteWarmup(alarm, "no_session")
        scope.launch {
            try {
                if (MediaStarter.canReadSessions(app)) {
                    if (starter.waitForSession(packageName, SESSION_WAIT_MILLIS) != null) {
                        Log.i(TAG, "$packageName is ready")
                        AlarmRinger.noteWarmup(alarm, "warmed")
                    } else {
                        Log.w(TAG, "$packageName didn't start its session")
                    }
                } else {
                    delay(BLIND_WAIT_MILLIS)
                }
                AlarmRinger.goHome(app)
            } finally {
                awake?.release()
            }
        }
    }
}
