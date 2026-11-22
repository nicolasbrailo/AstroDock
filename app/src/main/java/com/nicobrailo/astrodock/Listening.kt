package com.nicobrailo.astrodock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.nicobrailo.astrodock.mqtt.StateBroadcast

// Whether the wake word listener (wakeword/, in the process ":wakeword") is
// recording what is said after the wake word, which whichever slideshow is
// on screen shows over the pictures. Kept here rather than in a slideshow,
// since the home screen and the screensaver hand over in the middle of it.
// The listener says when it is over; TIMEOUT_MILLIS is in case it never does,
// as when its process dies.
//
// Used on the main thread.
object Listening {
    var active = false
        private set

    private val listeners = mutableSetOf<(Boolean) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val timeout = Runnable { set(false) }

    fun set(value: Boolean) {
        if (!value && !active) return
        active = value
        main.removeCallbacks(timeout)
        if (value) main.postDelayed(timeout, TIMEOUT_MILLIS)
        // A copy, since a listener may remove itself
        listeners.toList().forEach { it(value) }
    }

    fun addListener(listener: (Boolean) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners -= listener
    }

    // The longest recording (8s) and then some
    private const val TIMEOUT_MILLIS = 12_000L
}

// Takes the listener's broadcast, which only this app's processes send
class ListeningReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != StateBroadcast.ACTION_LISTENING) return
        Listening.set(intent.getBooleanExtra(StateBroadcast.EXTRA_LISTENING, false))
    }
}
