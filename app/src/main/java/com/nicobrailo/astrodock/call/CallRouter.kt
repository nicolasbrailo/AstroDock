package com.nicobrailo.astrodock.call

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.os.RemoteException
import android.util.Log
import com.nicobrailo.astrodock.ScreenControl
import com.nicobrailo.astrodock.SlideshowState
import com.nicobrailo.astrodock.mqtt.Command
import com.nicobrailo.astrodock.mqtt.StateReporter
import com.nicobrailo.astrodock.presence.PortalLog
import org.json.JSONObject
import java.util.UUID

// The main process's side of a call (see CALLING.md): it decides whether an
// offer is taken, wakes the screen for it, starts CallActivity in the :call
// process, and carries the call's messages between that activity and the
// other device, over StateReporter's connection. There is one call at a time.
//
// The activity does the media and nothing else. It can't have an MQTT
// connection of its own: StateReporter is one per process, and a second one
// with the same client id would kick ours off the broker.
//
// Everything here runs on the main thread.
class CallRouter private constructor(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val reporter: StateReporter get() = StateReporter.get(context)

    private class Call(
        val id: String,
        val peer: String,
        val outgoing: Boolean,
        var phase: CallPhase,
        // The answer is on its way: the other side answered our offer, or we
        // answered theirs
        var answered: Boolean = false,
        // Wall clock, in seconds, for the state report
        val since: Long = System.currentTimeMillis() / 1000,
    )

    private var call: Call? = null
    // CallActivity, once it has attached
    private var session: Messenger? = null
    // Wakes the screen for an incoming call, until its window holds it on
    private var wakeLock: PowerManager.WakeLock? = null

    // Waking a screen that lockNow() switched off doesn't reach the call: the
    // system started the screensaver while the screen was off, and the Portal
    // wakes into it, so the call's window comes up under it and is stopped at
    // once (measured on the Portal+: "Waking up from screen off, start
    // dreaming"). Waking again from there ends the screensaver, as it does for
    // one that was already running with the screen on.
    private var rewakes = 0
    private val rewake = object : Runnable {
        override fun run() {
            val current = call ?: return
            if (current.outgoing || current.phase != CallPhase.INCOMING) return
            if (reporter.isDreaming) {
                Log.i(TAG, "A screensaver is covering the call, waking again")
                releaseWakeLock()
                wakeLock = ScreenControl.keepScreenOn(context, SCREEN_REASON, WAKE_MILLIS, wakeUp = true)
            }
            if (++rewakes < MAX_REWAKES) main.postDelayed(this, REWAKE_MILLIS)
        }
    }

    // A call that hasn't connected by now isn't going to: the other device is
    // gone, or its side failed to start. Timed in steps (see CALLING.md): for
    // the caller, until the callee says it's ringing, then for as long as it
    // may ring, then from its answer; for the callee, from its answer, the
    // ringing being timed by noAnswer instead.
    private val giveUp = Runnable {
        val current = call ?: return@Runnable
        if (current.phase == CallPhase.IN_CALL) return@Runnable
        Log.i(TAG, "Call ${current.id} didn't connect, giving up")
        val reason = if (current.outgoing && !current.answered) CallIpc.END_NO_ANSWER else CallIpc.END_NO_CONNECTION
        end(reason, notifyPeer = true)
    }

    // An incoming call rang for as long as it may, and nobody answered it
    private val noAnswer = Runnable {
        val current = call ?: return@Runnable
        if (current.outgoing || current.answered) return@Runnable
        Log.i(TAG, "Nobody answered the call from ${current.peer}")
        val reason = RejectReason.NO_ANSWER.wire
        reporter.sendCall(current.peer, CallVerb.REJECT, JSONObject().put("call_id", current.id).put("reason", reason))
        end(reason, notifyPeer = false)
    }

    // Whether a call is under way, which holds the screen on
    val active: Boolean get() = call != null

    // For the state record: {"state":"in_call","with":"kitchen-portal/","since":...}
    fun stateJson(): JSONObject {
        val current = call
        return JSONObject()
            .put("state", current?.phase?.wire ?: "idle")
            .put("with", current?.peer ?: JSONObject.NULL)
            .put("since", current?.since ?: JSONObject.NULL)
    }

    // Calls `peer`, from the app list. False if a call is already under way.
    fun placeCall(peer: CallPeer): Boolean {
        if (call != null) return false
        val id = UUID.randomUUID().toString()
        Log.i(TAG, "Calling ${peer.prefix} ($id)")
        call = Call(id, peer.prefix, outgoing = true, phase = CallPhase.OUTGOING)
        startSession(id, peer.name, offer = null, answerAfterSeconds = null)
        restartGiveUp(SETUP_TIMEOUT_MILLIS)
        return true
    }

    fun onSignal(signal: Command.CallSignal) {
        when (signal.verb) {
            CallVerb.OFFER -> onOffer(signal)
            CallVerb.ANSWER -> {
                val current = matching(signal) ?: return
                if (!current.outgoing || current.phase != CallPhase.OUTGOING) return
                current.answered = true
                // From here it's up to the network
                restartGiveUp(SETUP_TIMEOUT_MILLIS)
                send(CallIpc.MSG_REMOTE_ANSWER, CallIpc.KEY_SDP, signal.sdp)
            }
            // A callee from before there was a ringing never sends one, and
            // answers within the setup timeout by itself
            CallVerb.RINGING -> {
                val current = matching(signal) ?: return
                if (!current.outgoing || current.answered || current.phase != CallPhase.OUTGOING) return
                Log.i(TAG, "${current.peer} is ringing")
                // Waits for a person now, for as long as the callee rings, and
                // a little more so that its no_answer gets here first
                restartGiveUp(Calls.RING_TIMEOUT_SECONDS * 1000 + RING_GRACE_MILLIS)
                send(CallIpc.MSG_REMOTE_RINGING, CallIpc.KEY_CALL_ID, current.id)
            }
            CallVerb.REJECT -> {
                val current = matching(signal) ?: return
                if (!current.outgoing) return
                Log.i(TAG, "${current.peer} rejected the call: ${signal.reason}")
                end(signal.reason ?: CallIpc.END_HANGUP, notifyPeer = false)
            }
            CallVerb.HANGUP -> {
                val current = matching(signal) ?: return
                Log.i(TAG, "${current.peer} hung up: ${signal.reason}")
                val reason = if (signal.reason == CallIpc.END_NO_CONNECTION) CallIpc.END_NO_CONNECTION else CallIpc.END_HANGUP
                end(reason, notifyPeer = false)
            }
        }
    }

    private fun onOffer(offer: Command.CallSignal) {
        val from = offer.from ?: return
        val sdp = offer.sdp ?: return
        // QoS 1 can deliver a message twice
        if (call?.id == offer.callId) return
        if (Calls.isStale(offer.sentAt ?: 0, System.currentTimeMillis() / 1000)) {
            Log.w(TAG, "Dropping an offer from $from sent at ${offer.sentAt}: too old, or a clock is wrong")
            return
        }
        val settings = CallSettings.load(context)
        val refusal = Calls.refusal(
            enabled = settings.enabled,
            allowed = Calls.isAllowed(settings.allowList, from),
            canCapture = CallSettings.canCapture(context),
            night = SlideshowState.shared.nightRuleApplies(context),
            busy = call != null,
            privacy = PortalLog.isReading && PortalLog.presence.privacy,
            lensCovered = PortalLog.isReading && PortalLog.presence.lensCovered,
        )
        if (refusal != null) {
            Log.i(TAG, "Refusing a call from $from: ${refusal.wire}")
            reporter.sendCall(from, CallVerb.REJECT, JSONObject().put("call_id", offer.callId).put("reason", refusal.wire))
            return
        }

        Log.i(TAG, "Taking a call from $from (${offer.callId})")
        call = Call(offer.callId, from, outgoing = false, phase = CallPhase.INCOMING)
        // Whatever is on screen, or nothing: the screen may be off, or a
        // screensaver may be covering everything
        wakeLock = ScreenControl.keepScreenOn(context, SCREEN_REASON, WAKE_MILLIS, wakeUp = true)
        rewakes = 0
        main.postDelayed(rewake, REWAKE_MILLIS)
        main.postDelayed(noAnswer, Calls.RING_TIMEOUT_SECONDS * 1000)
        startSession(offer.callId, Calls.displayName(from), sdp, settings.answerAfterSeconds)
    }

    // answerAfterSeconds is for an incoming call: how long it rings before
    // answering by itself, null for until someone answers it
    private fun startSession(id: String, peerName: String, offer: String?, answerAfterSeconds: Int?) {
        session = null
        reporter.refresh()
        // Started from the background, which Android 10 allows because we
        // hold SYSTEM_ALERT_WINDOW (the home button overlay's permission)
        val intent = Intent(context, CallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(CallActivity.EXTRA_CALL_ID, id)
            .putExtra(CallActivity.EXTRA_PEER_NAME, peerName)
            .putExtra(CallActivity.EXTRA_OFFER, offer)
            .putExtra(CallActivity.EXTRA_ANSWER_AFTER, answerAfterSeconds ?: CallActivity.ANSWER_BY_HAND)
        context.startActivity(intent)
    }

    private fun restartGiveUp(millis: Long) {
        main.removeCallbacks(giveUp)
        main.postDelayed(giveUp, millis)
    }

    // From CallSignalService, when CallActivity is up
    fun onAttached(callId: String, messenger: Messenger) {
        if (call?.id != callId) {
            // It ended before the activity got here
            send(messenger, CallIpc.MSG_END, CallIpc.KEY_END_REASON, CallIpc.END_UNKNOWN)
            return
        }
        session = messenger
        // The call is in front of whoever is there now, which is what the
        // caller is waiting for
        val current = call ?: return
        if (!current.outgoing && !current.answered) {
            reporter.sendCall(current.peer, CallVerb.RINGING, JSONObject().put("call_id", current.id))
        }
    }

    // Answered, by hand or by itself: it no longer rings, and has as long to
    // gather its candidates and connect as the caller has once the answer
    // gets there
    fun onAnswering(callId: String) {
        val current = call?.takeIf { it.id == callId && !it.outgoing } ?: return
        current.answered = true
        main.removeCallbacks(noAnswer)
        restartGiveUp(SETUP_TIMEOUT_MILLIS)
    }

    // Our offer or answer, with all its candidates, ready to go
    fun onLocalSdp(callId: String, sdp: String) {
        val current = call?.takeIf { it.id == callId } ?: return
        val sent = if (current.outgoing) {
            val from = reporter.ownPrefix
            from != null && reporter.sendCall(
                current.peer,
                CallVerb.OFFER,
                JSONObject()
                    .put("call_id", current.id)
                    .put("from", from)
                    .put("ts", System.currentTimeMillis() / 1000)
                    .put("sdp", sdp),
            )
        } else {
            reporter.sendCall(current.peer, CallVerb.ANSWER, JSONObject().put("call_id", current.id).put("sdp", sdp))
        }
        if (!sent) {
            Log.w(TAG, "Can't reach the broker for call ${current.id}")
            end(CallIpc.END_UNREACHABLE, notifyPeer = false)
        }
    }

    fun onConnected(callId: String) {
        val current = call?.takeIf { it.id == callId } ?: return
        if (current.phase == CallPhase.IN_CALL) return
        Log.i(TAG, "Call ${current.id} with ${current.peer} connected")
        current.phase = CallPhase.IN_CALL
        main.removeCallbacks(giveUp)
        // The call's window holds the screen on from here
        releaseWakeLock()
        reporter.refresh()
    }

    // The activity hung up, or its connection failed (`reason`
    // END_NO_CONNECTION if it never connected), or it was declined while it
    // rang (`reason` DECLINED's wire name)
    fun onEnded(callId: String, reason: String?) {
        val current = call?.takeIf { it.id == callId } ?: return
        session = null
        // Nothing was answered yet, so to the caller it is a refusal like any
        // other, and it can say who turned it down
        if (reason == RejectReason.DECLINED.wire && !current.outgoing && current.phase == CallPhase.INCOMING) {
            Log.i(TAG, "Declined the call from ${current.peer}")
            reporter.sendCall(current.peer, CallVerb.REJECT, JSONObject().put("call_id", current.id).put("reason", reason))
            end(reason, notifyPeer = false)
            return
        }
        end(if (reason == CallIpc.END_NO_CONNECTION) reason else CallIpc.END_HANGUP, notifyPeer = true)
    }

    // The :call process died, so nothing will hang up for it
    fun onSessionDied(callId: String) {
        if (call?.id != callId) return
        Log.w(TAG, "The call's process died")
        session = null
        end(CallIpc.END_HANGUP, notifyPeer = true)
    }

    private fun end(reason: String, notifyPeer: Boolean) {
        val current = call ?: return
        call = null
        main.removeCallbacks(giveUp)
        main.removeCallbacks(noAnswer)
        main.removeCallbacks(rewake)
        releaseWakeLock()
        if (notifyPeer) {
            // A call that never connected failed on both sides, but WebRTC
            // gives up on each at its own time, and whichever side goes first
            // would otherwise look to the other like it hung up
            val hangup = JSONObject().put("call_id", current.id)
            if (reason == CallIpc.END_NO_CONNECTION) hangup.put("reason", reason)
            reporter.sendCall(current.peer, CallVerb.HANGUP, hangup)
        }
        send(CallIpc.MSG_END, CallIpc.KEY_END_REASON, reason)
        session = null
        reporter.refresh()
    }

    private fun releaseWakeLock() {
        ScreenControl.release(wakeLock)
        wakeLock = null
    }

    private fun matching(signal: Command.CallSignal): Call? = call?.takeIf { it.id == signal.callId }

    private fun send(what: Int, key: String, value: String?) {
        session?.let { send(it, what, key, value) }
    }

    private fun send(to: Messenger, what: Int, key: String, value: String?) {
        val message = Message.obtain(null, what).apply { data = Bundle().apply { putString(key, value) } }
        try {
            to.send(message)
        } catch (e: RemoteException) {
            // Its death is reported separately
            Log.w(TAG, "The call's activity is gone", e)
        }
    }

    companion object {
        private const val TAG = "CallRouter"
        // What the state record gives as the reason the screen is held on
        const val SCREEN_REASON = "call"
        // Long enough for the :call process to start and the other side to
        // say it's ringing, and, once answered, for the media to connect,
        // each of which is a few seconds on a Portal
        private const val SETUP_TIMEOUT_MILLIS = 30_000L
        // How much longer than the callee's ringing the caller waits
        private const val RING_GRACE_MILLIS = 10_000L
        // Holds the screen on until the call's window does; released as soon
        // as the call connects or ends
        private const val WAKE_MILLIS = Calls.RING_TIMEOUT_SECONDS * 1000 + SETUP_TIMEOUT_MILLIS + 5_000L
        // How often, and how many times, to wake again while a screensaver
        // covers a call that is setting up
        private const val REWAKE_MILLIS = 1_000L
        private const val MAX_REWAKES = 5

        @Volatile
        private var instance: CallRouter? = null

        fun get(context: Context): CallRouter = instance ?: synchronized(this) {
            instance ?: CallRouter(context.applicationContext).also { instance = it }
        }
    }
}
