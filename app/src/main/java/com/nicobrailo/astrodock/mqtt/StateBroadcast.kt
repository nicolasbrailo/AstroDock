package com.nicobrailo.astrodock.mqtt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONObject

// The MQTT state record, broadcast on the device as well, for what works
// alongside AstroDock without being part of its main process: the wake word
// listener (wakeword/, in the process ":wakeword"), and any app signed with
// the same key. They get the whole record every time rather than events, so
// one that starts late, or misses a broadcast, is never left believing
// something stale: it asks for the record as it starts (StateRequestReceiver)
// and the next change brings it a new one. It goes out whether or not MQTT is
// set up, since nothing about it needs the broker.
//
// Both directions need PERMISSION, which only an app signed with AstroDock's
// key can hold: whether somebody is in the room is nobody else's business.
// The listener has its own copy of these names; keep them in step with
// wakeword/src/main/java/com/nicobrailo/astrodock/wakeword/AstroDockState.kt.
object StateBroadcast {
    const val ACTION_STATE = "com.nicobrailo.astrodock.action.STATE"
    const val ACTION_REQUEST_STATE = "com.nicobrailo.astrodock.action.REQUEST_STATE"
    // The record as JSON text, exactly as <prefix>state carries it
    const val EXTRA_STATE = "state"
    const val PERMISSION = "com.nicobrailo.astrodock.permission.STATE"

    fun send(context: Context, state: JSONObject) {
        val intent = Intent(ACTION_STATE).putExtra(EXTRA_STATE, state.toString())
        context.sendBroadcast(intent, PERMISSION)
    }
}

// Answers an app that has just started and wants the current record. The
// manifest only lets in senders holding StateBroadcast.PERMISSION.
class StateRequestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != StateBroadcast.ACTION_REQUEST_STATE) return
        StateReporter.get(context).broadcastState()
    }
}
