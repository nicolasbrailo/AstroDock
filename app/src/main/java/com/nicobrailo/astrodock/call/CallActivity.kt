package com.nicobrailo.astrodock.call

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.PowerManager
import android.os.RemoteException
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.nicobrailo.astrodock.R
import com.nicobrailo.astrodock.hideSystemBars
import org.webrtc.SurfaceViewRenderer

// A call, on screen: the other device's camera full screen, ours in a corner,
// and a button to hang up. Runs in its own process (:call, see the manifest),
// so that a crash in WebRTC's native code can't take the home screen with it:
// Android takes the default home app away from one whose process crashes.
//
// It does the media and nothing else. Everything that has to reach the other
// device goes through CallSignalService in the main process, which holds the
// only MQTT connection (see CallRouter). So this must not touch StateReporter,
// SlideshowState or any other of the main process's singletons: here they
// would be second copies, and a second StateReporter would be a second MQTT
// client with our client id.
//
// An incoming call rings first, saying who is calling, with a button to answer
// it and the hang up button to decline it. Unless the Portalcom app says to
// wait for someone to answer, it counts down a few seconds and then connects by
// itself. The countdown only runs while it can be seen, so the warning is never
// cut short by whatever covered the screen as the call came in.
//
// A call only lasts while this is in front. Leaving it, by Home or by the
// screen being switched off, hangs up, so the camera is never on with nobody
// able to see that it is. Not at once, though: an incoming call on a screen
// that was off comes up under the Portal's screensaver for a moment, until
// CallRouter wakes the device out of it (see there).
class CallActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var callId: String
    private lateinit var peerName: String
    private lateinit var status: TextView
    private lateinit var hangUpButton: View
    private lateinit var answerButton: View
    private var media: CallMedia? = null
    private var service: Messenger? = null
    private var bound = false
    // What we had to tell the service before it was bound
    private val pending = mutableListOf<Message>()
    // Hung up, or told the call is over; nothing more is sent after that
    private var over = false
    // endWith has run
    private var closing = false
    // Media has flowed at least once, which makes losing it a disconnection
    // rather than a call that never got going
    private var everConnected = false
    // An incoming call is ringing: the camera and microphone aren't on yet, and
    // the button declines it
    private var ringing = false
    // The countdown is running, which it only does while the screen can be seen
    private var counting = false
    // How long an incoming call rings before answering by itself, null for
    // until someone answers it
    private var answerAfter: Int? = null
    // Seconds of ringing left, when it answers by itself
    private var ringLeft = 0
    // Between onResume and onPause
    private var resumed = false
    private var ringTone: ToneGenerator? = null
    // The caller's offer, for an incoming call
    private var offer: String? = null
    private val incoming: Boolean get() = offer != null

    // Once a second while ringing: a ring, and the countdown, and when that's
    // over, the call. Waiting for someone to answer, it rings until CallRouter
    // gives up on the call.
    private val ring = object : Runnable {
        override fun run() {
            if (answerAfter == null) {
                status.text = getString(R.string.call_incoming_answer, peerName)
            } else {
                if (ringLeft == 0) {
                    answer()
                    return
                }
                status.text = resources.getQuantityString(R.plurals.call_incoming_countdown, ringLeft, peerName, ringLeft)
                ringLeft--
            }
            ringTone?.startTone(ToneGenerator.TONE_PROP_BEEP2, TONE_MILLIS)
            main.postDelayed(this, RING_STEP_MILLIS)
        }
    }

    // "Call connected" stays up for a moment, and then the status is just
    // who the call is with
    private val showPeerName = Runnable { status.text = peerName }

    private val hangUpIfStillAway = Runnable {
        Log.i(TAG, "Left the call")
        hangUp()
    }

    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        onReply(message)
        true
    })

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
            val messenger = Messenger(binder)
            service = messenger
            send(message(CallIpc.MSG_ATTACH).apply { replyTo = replies })
            pending.forEach { send(it) }
            pending.clear()
        }

        // The main process died, and the call's messages with it
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            Log.w(TAG, "Lost the main process")
            endWith(getString(R.string.call_ended))
        }
    }

    private val mediaListener = object : CallMedia.Listener {
        override fun onLocalSdp(sdp: String) {
            tell(CallIpc.MSG_LOCAL_SDP, CallIpc.KEY_SDP, sdp)
        }

        override fun onConnected() {
            everConnected = true
            showStatus(getString(R.string.call_connected))
            main.postDelayed(showPeerName, CONNECTED_TEXT_MILLIS)
            tell(CallIpc.MSG_CONNECTED)
        }

        override fun onInterrupted() {
            showStatus(getString(R.string.call_reconnecting))
        }

        override fun onFailed(why: String) {
            Log.w(TAG, "Call failed: $why")
            if (everConnected) tell(CallIpc.MSG_ENDED)
            else tell(CallIpc.MSG_ENDED, CallIpc.KEY_END_REASON, CallIpc.END_NO_CONNECTION)
            endWith(
                if (everConnected) getString(R.string.call_disconnected)
                else getString(R.string.call_no_connection, peerName)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // An incoming call arrives on whatever the screen was doing, locked
        // by the night rule or force_off included. It stays on for as long as
        // the call lasts: the window flag counts as a screen wake lock, which
        // holds off both the screensaver and sleep, and goes with the window
        // if this process dies.
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_call)
        hideSystemBars()

        callId = intent.getStringExtra(EXTRA_CALL_ID) ?: run {
            finish()
            return
        }
        peerName = intent.getStringExtra(EXTRA_PEER_NAME).orEmpty()
        offer = intent.getStringExtra(EXTRA_OFFER)
        answerAfter = intent.getIntExtra(EXTRA_ANSWER_AFTER, ANSWER_BY_HAND).takeIf { it >= 0 }
        status = findViewById(R.id.call_status)
        status.text = getString(if (incoming) R.string.call_incoming else R.string.call_calling, peerName)
        hangUpButton = findViewById(R.id.call_hang_up)
        hangUpButton.setOnClickListener { if (ringing) decline() else hangUp() }
        answerButton = findViewById(R.id.call_answer)
        answerButton.setOnClickListener { if (ringing) answer() }

        bound = bindService(Intent(this, CallSignalService::class.java), connection, BIND_AUTO_CREATE)

        if (!CallSettings.canCapture(this)) {
            tell(CallIpc.MSG_ENDED)
            endWith(getString(R.string.call_no_permission))
            return
        }
        if (incoming) startRinging() else startMedia()
    }

    private fun startMedia() {
        media = CallMedia(this, findViewById(R.id.call_local), findViewById<SurfaceViewRenderer>(R.id.call_remote), mediaListener)
            .also { it.start(offer) }
    }

    // The camera and the microphone may be about to go on without anyone here
    // having asked for it, so it shouldn't happen silently, nor without a
    // chance to say no
    private fun startRinging() {
        ringing = true
        ringLeft = answerAfter ?: 0
        ringTone = try {
            ToneGenerator(AudioManager.STREAM_VOICE_CALL, TONE_VOLUME)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Can't play the call tone", e)
            null
        }
        hangUpButton.contentDescription = getString(R.string.call_decline)
        answerButton.visibility = View.VISIBLE
        updateCountdown()
    }

    // Runs the countdown while the call can be seen: in front, with the focus,
    // and the screen on. An incoming call can come up under a screensaver the
    // device woke into, or on a screen that is still off (see CallRouter), and
    // the Control Center can cover it. Each time it comes back into view, the
    // countdown starts over.
    private fun updateCountdown() {
        if (!ringing) return
        val visible = resumed && hasWindowFocus() && getSystemService(PowerManager::class.java)?.isInteractive != false
        if (visible == counting) return
        counting = visible
        main.removeCallbacks(ring)
        ringLeft = answerAfter ?: 0
        if (visible) {
            Log.i(TAG, "The call can be seen, counting down")
            ring.run()
        } else {
            status.text = getString(R.string.call_incoming, peerName)
        }
    }

    private fun stopRinging() {
        if (!ringing) return
        ringing = false
        counting = false
        main.removeCallbacks(ring)
        ringTone?.release()
        ringTone = null
        hangUpButton.contentDescription = getString(R.string.call_hang_up)
        answerButton.visibility = View.GONE
    }

    private fun answer() {
        stopRinging()
        if (over) return
        Log.i(TAG, "Answering the call from $peerName")
        tell(CallIpc.MSG_ANSWERING)
        status.text = getString(R.string.call_incoming, peerName)
        startMedia()
    }

    private fun decline() {
        if (over) return
        Log.i(TAG, "Declined the call from $peerName")
        tell(CallIpc.MSG_ENDED, CallIpc.KEY_END_REASON, RejectReason.DECLINED.wire)
        endWith(null)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
        updateCountdown()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        updateCountdown()
    }

    override fun onPause() {
        resumed = false
        updateCountdown()
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        main.removeCallbacks(hangUpIfStillAway)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !over) main.postDelayed(hangUpIfStillAway, AWAY_MILLIS)
    }

    override fun onDestroy() {
        if (!over) {
            tell(CallIpc.MSG_ENDED)
            over = true
        }
        stopRinging()
        media?.release()
        media = null
        main.removeCallbacksAndMessages(null)
        if (bound) unbindService(connection)
        bound = false
        super.onDestroy()
    }

    private fun hangUp() {
        if (over) return
        tell(CallIpc.MSG_ENDED)
        endWith(null)
    }

    private fun onReply(message: Message) {
        when (message.what) {
            CallIpc.MSG_REMOTE_RINGING -> showStatus(getString(R.string.call_ringing, peerName))
            CallIpc.MSG_REMOTE_ANSWER -> message.data.getString(CallIpc.KEY_SDP)?.let {
                showStatus(getString(R.string.call_connecting, peerName))
                media?.setRemoteAnswer(it)
            }
            CallIpc.MSG_END -> endWith(endText(message.data.getString(CallIpc.KEY_END_REASON)))
        }
    }

    private fun endText(reason: String?): String {
        RejectReason.ofWire(reason.orEmpty())?.let { rejection ->
            return getString(
                when (rejection) {
                    RejectReason.DISABLED -> R.string.call_rejected_disabled
                    RejectReason.NOT_ALLOWED -> R.string.call_rejected_not_allowed
                    RejectReason.UNAVAILABLE -> R.string.call_rejected_unavailable
                    RejectReason.PRIVACY -> R.string.call_rejected_privacy
                    RejectReason.NIGHT -> R.string.call_rejected_night
                    RejectReason.BUSY -> R.string.call_rejected_busy
                    RejectReason.DECLINED -> R.string.call_rejected_declined
                    // Sent by the callee, and told to it by CallRouter
                    RejectReason.NO_ANSWER -> if (incoming) R.string.call_missed else R.string.call_no_answer
                },
                peerName,
            )
        }
        return when (reason) {
            CallIpc.END_HANGUP -> getString(R.string.call_hung_up, peerName)
            CallIpc.END_NO_ANSWER -> getString(R.string.call_no_answer, peerName)
            CallIpc.END_NO_CONNECTION -> getString(R.string.call_no_connection, peerName)
            CallIpc.END_UNREACHABLE -> getString(R.string.call_unreachable)
            else -> getString(R.string.call_ended)
        }
    }

    // Stops the media at once, and leaves `text` up for a moment before
    // closing, or closes straight away without it
    private fun endWith(text: String?) {
        if (closing) return
        closing = true
        over = true
        stopRinging()
        media?.release()
        media = null
        if (text == null || isFinishing) {
            finish()
            return
        }
        showStatus(text)
        hangUpButton.visibility = View.GONE
        answerButton.visibility = View.GONE
        main.postDelayed({ finish() }, END_TEXT_MILLIS)
    }

    // Replaces whatever the status said, including a "Call connected" that
    // was about to give way to the name
    private fun showStatus(text: String) {
        main.removeCallbacks(showPeerName)
        status.text = text
    }

    private fun message(what: Int): Message =
        Message.obtain(null, what).apply { data = Bundle().apply { putString(CallIpc.KEY_CALL_ID, callId) } }

    private fun tell(what: Int, key: String? = null, value: String? = null) {
        if (over) return
        val message = message(what)
        if (key != null) message.data.putString(key, value)
        if (service == null) pending += message else send(message)
    }

    private fun send(message: Message) {
        try {
            service?.send(message)
        } catch (e: RemoteException) {
            Log.w(TAG, "Can't reach the main process", e)
        }
    }

    companion object {
        private const val TAG = "CallActivity"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_PEER_NAME = "peer_name"
        // The caller's offer, for an incoming call; none when we are calling
        const val EXTRA_OFFER = "offer"
        // For an incoming call: seconds of ringing before it answers by
        // itself, or ANSWER_BY_HAND to ring until someone answers it. Read
        // by CallRouter from CallSettings, since this process's copy of the
        // preferences could be stale.
        const val EXTRA_ANSWER_AFTER = "answer_after"
        const val ANSWER_BY_HAND = -1
        private const val END_TEXT_MILLIS = 2_500L
        private const val CONNECTED_TEXT_MILLIS = 3_000L
        // Longer than CallRouter takes to wake the device out of a screensaver
        private const val AWAY_MILLIS = 3_000L
        private const val TONE_VOLUME = 80
        private const val TONE_MILLIS = 400
        private const val RING_STEP_MILLIS = 1_000L
    }
}
