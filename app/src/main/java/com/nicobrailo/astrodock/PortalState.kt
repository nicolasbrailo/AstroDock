package com.nicobrailo.astrodock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings as AndroidSettings

// What the Portal is doing, for the debug overlay in the slideshow.
//
// The Portal's presence detection itself is off limits: its broadcasts and
// state databases need signature or privileged permissions (see the Portal
// notes in AGENTS.md). What we can see is what it does with them, which is
// enough to tell what's going on:
//  - It wakes the screen when its camera sees someone, and reports activity to
//    the power manager every ~30s for as long as it keeps seeing them.
//  - So a screen that stays on past the screensaver timeout means someone is
//    there; the device sleeping means nobody has been seen for sleep_timeout.
// Presence is therefore a guess, and the overlay says so.
//
// start() and stop() must be called from the main thread.
class PortalState(private val context: Context) {
    private val powerManager = context.getSystemService(PowerManager::class.java)
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val lightSensor: Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_LIGHT)

    private var screenOn = powerManager?.isInteractive != false
    private var screenChangedAt = SystemClock.elapsedRealtime()
    private var dreaming = false
    private var dreamChangedAt = SystemClock.elapsedRealtime()
    private var lux: Float? = null
    private var running = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> setScreenOn(true)
                Intent.ACTION_SCREEN_OFF -> setScreenOn(false)
                Intent.ACTION_DREAMING_STARTED -> setDreaming(true)
                Intent.ACTION_DREAMING_STOPPED -> setDreaming(false)
            }
        }
    }

    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            lux = event.values.firstOrNull()
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    fun start() {
        if (running) return
        running = true
        // These are only delivered to receivers registered in code
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_DREAMING_STARTED)
            addAction(Intent.ACTION_DREAMING_STOPPED)
        }
        context.registerReceiver(receiver, filter)
        lightSensor?.let { sensorManager?.registerListener(lightListener, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    fun stop() {
        if (!running) return
        running = false
        context.unregisterReceiver(receiver)
        sensorManager?.unregisterListener(lightListener)
    }

    // One line per thing worth knowing. extraLine is put at the end, for the
    // caller's own state.
    fun describe(extraLine: String): String {
        val lines = mutableListOf<String>()
        val screenFor = SystemClock.elapsedRealtime() - screenChangedAt
        lines += "Screen: ${if (screenOn) "on" else "off"} for ${duration(screenFor)}"
        lines += "Screensaver: ${if (dreaming) "running" else "not running"}" +
            " for ${duration(SystemClock.elapsedRealtime() - dreamChangedAt)}"
        lines += "Light: ${lux?.let { "%.0f lx".format(it) } ?: "unknown"}"

        val screensaverAfter = AndroidSettings.System.getInt(
            context.contentResolver, AndroidSettings.System.SCREEN_OFF_TIMEOUT, -1
        )
        val sleepAfter = AndroidSettings.Secure.getInt(context.contentResolver, SLEEP_TIMEOUT, -1)
        lines += "Timeouts: screensaver ${duration(screensaverAfter.toLong())}," +
            " sleep ${duration(sleepAfter.toLong())}"
        lines += "Presence (guess): ${presence(screenOn, screenFor, screensaverAfter.toLong())}"
        lines += "We are: home ${yesNo(isHome())}, screensaver ${yesNo(isScreensaver())}"
        lines += extraLine
        return lines.joinToString("\n")
    }

    // The Portal keeps the screen awake while its camera sees someone, so a
    // screen still on past the screensaver timeout means somebody is there
    private fun presence(screenOn: Boolean, screenFor: Long, screensaverAfter: Long): String = when {
        !screenOn -> "nobody seen for at least ${duration(screenFor)}"
        screensaverAfter > 0 && screenFor > screensaverAfter -> "someone is here (Portal is keeping the screen on)"
        else -> "unknown (screen woke ${duration(screenFor)} ago)"
    }

    private fun isHome(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = context.packageManager.resolveActivity(home, 0)
        return resolved?.activityInfo?.packageName == context.packageName
    }

    private fun isScreensaver(): Boolean {
        val components = AndroidSettings.Secure.getString(context.contentResolver, SCREENSAVER_COMPONENTS)
        return components?.contains(context.packageName) == true
    }

    private fun yesNo(value: Boolean) = if (value) "yes" else "no"

    // "3m 20s", or "?" if the value is unknown (a negative number)
    private fun duration(ms: Long): String {
        if (ms < 0) return "?"
        val seconds = ms / 1000
        return if (seconds >= 60) "${seconds / 60}m ${seconds % 60}s" else "${seconds}s"
    }

    private fun setScreenOn(on: Boolean) {
        if (screenOn == on) return
        screenOn = on
        screenChangedAt = SystemClock.elapsedRealtime()
    }

    private fun setDreaming(value: Boolean) {
        if (dreaming == value) return
        dreaming = value
        dreamChangedAt = SystemClock.elapsedRealtime()
    }

    private companion object {
        // Both are hidden constants in the framework
        const val SLEEP_TIMEOUT = "sleep_timeout"
        const val SCREENSAVER_COMPONENTS = "screensaver_components"
    }
}
