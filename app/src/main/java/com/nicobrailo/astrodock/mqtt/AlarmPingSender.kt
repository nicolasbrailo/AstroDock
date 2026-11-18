package com.nicobrailo.astrodock.mqtt

import android.app.AlarmManager
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttPingSender
import org.eclipse.paho.client.mqttv3.internal.ClientComms

// Sends the MQTT keepalive from an alarm instead of Paho's own Timer. The
// Portal Go lets its CPU suspend while the screen is off, even on mains power,
// and a Java Timer doesn't run in suspend: no ping went out, the broker gave up
// on the client after one and a half keepalives and published its last will,
// and whatever woke the CPU next reconnected it. Measured 2026-11-18: about
// once a minute on both Gos, all night, and only while the screen was off.
//
// A wakeup alarm wakes the CPU, and a partial wake lock keeps it awake until
// the broker answers the ping, or else it would suspend again with the ping
// still on its way. Paho itself decides when the next ping is due (after any
// other traffic it is later) and asks for it through schedule().
//
// Unplugged and in Doze, the alarm waits for a maintenance window, but then
// the network is cut too, so the ping couldn't go out anyway.
class AlarmPingSender(context: Context) : MqttPingSender {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val handler = Handler(thread.looper)
    private var comms: ClientComms? = null
    private var tag = TAG
    @Volatile private var running = false

    private val onAlarm = AlarmManager.OnAlarmListener { ping() }

    override fun init(comms: ClientComms) {
        this.comms = comms
        tag = "$TAG:${comms.client.clientId}"
    }

    override fun start() {
        running = true
        comms?.let { schedule(it.keepAlive) }
    }

    override fun stop() {
        running = false
        alarms.cancel(onAlarm)
    }

    override fun schedule(delayInMilliseconds: Long) {
        if (!running) return
        // Replaces the alarm already set, since the listener is the same one
        alarms.setExact(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + delayInMilliseconds,
            tag,
            onAlarm,
            handler,
        )
    }

    private fun ping() {
        val comms = comms ?: return
        if (!running) return
        val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag).apply {
            setReferenceCounted(false)
            // A backstop: the broker should answer within a second or so
            acquire(WAKE_LOCK_MILLIS)
        }
        val token = try {
            comms.checkForActivity(object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) = lock.release()

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.w(TAG, "Keepalive failed", exception)
                    lock.release()
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "Can't send the keepalive", e)
            null
        }
        // Null when nothing had to be sent: there was other traffic recently,
        // and Paho has already asked for the next alarm
        if (token == null) lock.release()
    }

    companion object {
        private const val TAG = "AlarmPingSender"
        private const val WAKE_LOCK_MILLIS = 30_000L

        // Alarms land here rather than on the main thread, which the
        // slideshow keeps busy and which Paho's locks shouldn't hold up
        private val thread by lazy { HandlerThread("mqtt-ping").apply { start() } }
    }
}
