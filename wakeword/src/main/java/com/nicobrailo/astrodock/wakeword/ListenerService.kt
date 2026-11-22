package com.nicobrailo.astrodock.wakeword

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONException
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.IOException

// Listens for the wake word whenever ListenGate allows, and chimes when it
// hears it. It runs while the wake word is enabled or its settings screen is
// open, whether or not the microphone is, since that is what receives
// AstroDock's state broadcasts (a receiver in the manifest wouldn't get
// them), and in the foreground, because up to Android 9 an app in the
// background records silence. That isn't enough on a Portal, which silences
// any app without an activity in front (see the platform notes in
// AGENTS.md), which is why this is a process of AstroDock's rather than an app
// of its own: the home screen being in front is what lets it hear.
//
// Everything here runs on the main thread: the broadcasts arrive on it, and
// MicReader reports on it.
class ListenerService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var chime: Chime
    private var state: AstroDockState? = null
    private var model: Model? = null
    private var modelProblem: String? = null
    private var recognizer: Recognizer? = null
    private var mic: MicReader? = null
    // The source the open microphone records from, to notice it changing
    private var micSource: Int? = null
    // After the microphone failed: when to try again, and why it failed
    private var retryAt = 0L
    private var micProblem: String? = null
    private var shownText: String? = null
    private val retry = Runnable { update() }
    private var levelLoggedAt = 0L

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != AstroDockState.ACTION_STATE) return
            state = AstroDockState.parse(intent.getStringExtra(AstroDockState.EXTRA_STATE))
            update()
        }
    }

    private val micListener = object : MicReader.Listener {
        override fun onLevel(dbfs: Double) {
            ListenerStatus.level(dbfs)
            // Now and then, which is enough to tell a working microphone from
            // one that only gives silence
            val now = SystemClock.elapsedRealtime()
            if (now - levelLoggedAt >= LEVEL_LOG_MILLIS) {
                levelLoggedAt = now
                Log.d(TAG, "Level %.0f dBFS".format(dbfs))
            }
        }

        override fun onPartial(hypothesis: String) {
            val text = textOf(hypothesis, "partial") ?: return
            if (text.isNotEmpty()) ListenerStatus.hearing(text)
        }

        override fun onResult(hypothesis: String) = onUtterance(hypothesis)
        override fun onError(e: Exception) = micFailed(e)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_MIN)
        )
        val text = getString(R.string.status_starting)
        // The microphone type only exists from Android 11, and is only
        // required from 14
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, notification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification(text))
        }
        shownText = text
        chime = Chime()

        // Only AstroDock, which holds the permission, can send it
        ContextCompat.registerReceiver(
            this, stateReceiver, IntentFilter(AstroDockState.ACTION_STATE),
            AstroDockState.PERMISSION, null, ContextCompat.RECEIVER_EXPORTED,
        )
        // Broadcasts only come on a change, so the current state is asked for
        sendBroadcast(
            Intent(AstroDockState.ACTION_REQUEST_STATE).setPackage(AstroDockState.ASTRODOCK_PACKAGE),
            AstroDockState.PERMISSION,
        )

        // Copied out of the APK the first time, and after an update that
        // brings a different model; a few seconds either way
        StorageService.unpack(this, WakeWord.MODEL_ASSET, WakeWord.MODEL_DIR, { loaded ->
            model = loaded
            Log.i(TAG, "Model loaded")
            update()
        }, { e ->
            Log.e(TAG, "Can't load the model", e)
            modelProblem = getString(R.string.problem_model, e.message)
            update()
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        update()
        // Started again by the system if it is killed, so it outlives that
        return START_STICKY
    }

    override fun onDestroy() {
        main.removeCallbacks(retry)
        unregisterReceiver(stateReceiver)
        closeMic()
        model?.close()
        model = null
        chime.release()
        ListenerStatus.set(listening = false, refusal = Refusal.DISABLED, problem = null)
        super.onDestroy()
    }

    // Opens or closes the microphone as the gate says. Called on every
    // change that could make a difference.
    private fun update() {
        val enabled = WakewordSettings.enabled(this)
        val onScreen = ListenerStatus.onScreen
        if (!enabled && !onScreen) {
            stopSelf()
            return
        }
        val refusal = ListenGate.refusal(enabled, WakewordSettings.micGranted(this), state, onScreen)
        val source = WakewordSettings.source(this)
        // A new source needs a new recording
        if (micSource != null && micSource != source) closeMic()
        val now = SystemClock.elapsedRealtime()
        when {
            refusal != null -> closeMic()
            model == null -> closeMic()
            now < retryAt -> closeMic()
            else -> openMic(source)
        }
        val listening = mic != null
        val problem = modelProblem ?: micProblem.takeIf { refusal == null }
        ListenerStatus.set(listening, refusal, problem)
        showStatus(
            when {
                listening -> getString(R.string.status_listening)
                problem != null -> problem
                refusal != null -> getString(R.string.status_not_listening, getString(refusalText(refusal)))
                else -> getString(R.string.status_loading)
            }
        )
    }

    private fun openMic(source: Int) {
        if (mic != null) return
        val model = model ?: return
        try {
            val newRecognizer = Recognizer(model, WakeWord.SAMPLE_RATE, WakeWord.GRAMMAR)
            // Each word's confidence, for the log, which is how a false
            // alarm can be told from a real one
            newRecognizer.setWords(true)
            recognizer = newRecognizer
            val newMic = MicReader(newRecognizer, WakeWord.SAMPLE_RATE.toInt(), source, micListener)
            mic = newMic
            micSource = source
            newMic.start()
            micProblem = null
            Log.i(TAG, "Listening on source $source")
        } catch (e: IOException) {
            micFailed(e)
        }
    }

    private fun closeMic() {
        val wasOpen = mic != null
        mic?.stop()
        mic = null
        micSource = null
        recognizer?.close()
        recognizer = null
        if (wasOpen) {
            ListenerStatus.level(null)
            Log.i(TAG, "Stopped listening")
        }
    }

    // Usually another app holding the microphone, which up to Android 9 makes
    // ours fail to start. Tried again after a while, if the gate still allows.
    private fun micFailed(e: Exception) {
        Log.w(TAG, "Microphone failed", e)
        closeMic()
        micProblem = getString(R.string.problem_microphone, e.message)
        retryAt = SystemClock.elapsedRealtime() + RETRY_MILLIS
        main.removeCallbacks(retry)
        main.postDelayed(retry, RETRY_MILLIS)
        update()
    }

    // An utterance, once Vosk decides it has ended: {"text": "...", "result":
    // [{"word": "hey", "conf": 1.0, ...}, ...]}
    private fun onUtterance(hypothesis: String) {
        val json = try {
            JSONObject(hypothesis)
        } catch (e: JSONException) {
            Log.w(TAG, "Can't read $hypothesis", e)
            return
        }
        val text = json.optString("text")
        if (text.isEmpty()) return
        // Everything, so a phrase that wasn't recognised shows what it was
        // taken for instead
        Log.d(TAG, "Utterance \"$text\": ${json.optJSONArray("result")}")
        ListenerStatus.hearing(text)
        if (!WakeWord.heard(text)) return
        Log.i(TAG, "Heard \"$text\"")
        chime.play()
        ListenerStatus.heard(text)
    }

    private fun textOf(hypothesis: String, key: String): String? = try {
        JSONObject(hypothesis).optString(key)
    } catch (e: JSONException) {
        null
    }

    private fun showStatus(text: String) {
        if (text == shownText) return
        shownText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, WakewordActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_wakeword_mic)
            .setContentTitle(getString(R.string.wakeword_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    companion object {
        private const val TAG = "WakewordListener"
        private const val CHANNEL = "listener"
        private const val NOTIFICATION_ID = 1
        private const val RETRY_MILLIS = 10_000L
        private const val LEVEL_LOG_MILLIS = 3_000L

        fun intent(context: Context) = Intent(context, ListenerService::class.java)

        fun refusalText(refusal: Refusal): Int = when (refusal) {
            Refusal.DISABLED -> R.string.refusal_disabled
            Refusal.NO_MICROPHONE_PERMISSION -> R.string.refusal_no_microphone
            Refusal.NO_STATE -> R.string.refusal_no_state
            Refusal.CALL -> R.string.refusal_call
            Refusal.NIGHT -> R.string.refusal_night
            Refusal.NOT_ON_SCREEN -> R.string.refusal_not_on_screen
            Refusal.BLIND -> R.string.refusal_blind
            Refusal.NOBODY -> R.string.refusal_nobody
        }
    }
}
