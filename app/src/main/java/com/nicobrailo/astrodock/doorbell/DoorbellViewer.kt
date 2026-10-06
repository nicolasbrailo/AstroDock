package com.nicobrailo.astrodock.doorbell

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.nicobrailo.astrodock.ScreenControl
import com.nicobrailo.astrodock.mqtt.StateReporter

// Shows the door when the doorbell rings: wakes the screen and starts
// BatiDoorLink on the camera's stream. BatiDoorLink keeps the screen on itself
// while it plays, so the wake lock here only has to last until it is up.
//
// Everything here runs on the main thread.
class DoorbellViewer private constructor(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    // The same trouble as an incoming call's (see CallRouter): a screensaver
    // that is running, or that the screen wakes into, covers the viewer and
    // stops it. Waking again ends the screensaver, and BatiDoorLink connects
    // once it is back on screen.
    private var rewakes = 0
    private val rewake = object : Runnable {
        override fun run() {
            if (StateReporter.get(context).isDreaming) {
                Log.i(TAG, "A screensaver is covering the door, waking again")
                wake()
            }
            if (++rewakes < MAX_REWAKES) main.postDelayed(this, REWAKE_MILLIS)
        }
    }
    private val releaseScreen = Runnable { releaseWakeLock() }

    // False if BatiDoorLink isn't installed. A ring that comes while it is
    // already showing the door just hands it the stream again.
    fun show(rtspUrl: String): Boolean {
        val uri = Uri.parse(rtspUrl)
        // Started from the background, which Android 10 allows because we
        // hold SYSTEM_ALERT_WINDOW (the home button overlay's permission)
        val intent = Intent(Intent.ACTION_VIEW, uri)
            .setPackage(Doorbell.VIEWER_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        wake()
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "Can't show the door: ${Doorbell.VIEWER_PACKAGE} isn't installed")
            stop()
            return false
        }
        // The URL carries the camera's password, so only its host is logged
        Log.i(TAG, "Showing the door from ${uri.host}")
        main.removeCallbacks(rewake)
        main.removeCallbacks(releaseScreen)
        rewakes = 0
        main.postDelayed(rewake, REWAKE_MILLIS)
        main.postDelayed(releaseScreen, WAKE_MILLIS)
        return true
    }

    private fun wake() {
        releaseWakeLock()
        wakeLock = ScreenControl.keepScreenOn(context, SCREEN_REASON, WAKE_MILLIS, wakeUp = true)
    }

    private fun stop() {
        main.removeCallbacks(rewake)
        main.removeCallbacks(releaseScreen)
        releaseWakeLock()
    }

    private fun releaseWakeLock() {
        ScreenControl.release(wakeLock)
        wakeLock = null
    }

    companion object {
        private const val TAG = "DoorbellViewer"
        // What the state record gives as the reason the screen is held on
        private const val SCREEN_REASON = "doorbell"
        // Long enough for BatiDoorLink to start and hold the screen itself
        private const val WAKE_MILLIS = 15_000L
        // As for calls: how often, and how many times, to wake again while a
        // screensaver covers the door
        private const val REWAKE_MILLIS = 1_000L
        private const val MAX_REWAKES = 5

        @Volatile
        private var instance: DoorbellViewer? = null

        fun get(context: Context): DoorbellViewer = instance ?: synchronized(this) {
            instance ?: DoorbellViewer(context.applicationContext).also { instance = it }
        }
    }
}
