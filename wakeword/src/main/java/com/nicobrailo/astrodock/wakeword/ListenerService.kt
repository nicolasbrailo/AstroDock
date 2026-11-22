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
import java.io.File
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
// Everything here runs on the main thread (the broadcasts arrive on it, and
// MicReader reports on it), but for the samples, which are scored on the
// microphone's thread.
class ListenerService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var chime: Chime
    private var state: AstroDockState? = null
    private var models: WakeWordModels? = null
    private var modelProblem: String? = null
    private var mic: MicReader? = null
    // Read on the microphone's thread; set from the settings on every update
    @Volatile
    private var threshold = WakewordSettings.DEFAULT_THRESHOLD / 100f
    // The source the open microphone records from, to notice it changing
    private var micSource: Int? = null
    // After the microphone failed: when to try again, and why it failed
    private var retryAt = 0L
    private var micProblem: String? = null
    private var shownText: String? = null
    private val retry = Runnable { update() }
    private var levelLoggedAt = 0L
    // Between hearing the wake word and the end of what is said after it,
    // while AstroDock shows that it is listening
    private var recording = false

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

        // A few MB, read in well under a second
        try {
            models = WakeWordModels(this)
            Log.i(TAG, "Models loaded")
        } catch (e: Exception) {
            // IOException for a missing asset, IllegalArgumentException for
            // one TensorFlow Lite can't read
            Log.e(TAG, "Can't load the models", e)
            modelProblem = getString(R.string.problem_model, e.message)
        }
        update()
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
        models?.close()
        models = null
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
        threshold = WakewordSettings.threshold(this) / 100f
        // A new source needs a new recording
        if (micSource != null && micSource != source) closeMic()
        val now = SystemClock.elapsedRealtime()
        when {
            refusal != null -> closeMic()
            models == null -> closeMic()
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
        val models = models ?: return
        try {
            val pipeline = Pipeline(models)
            val newMic = MicReader(WakeWordDetector.SAMPLE_RATE, source, micListener, pipeline::accept)
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
        // A recording cut short: AstroDock is told it's over
        if (recording) recorded(null)
        if (wasOpen) {
            ListenerStatus.level(null)
            Log.i(TAG, "Stopped listening")
        }
    }

    // What the microphone's samples go through, on its thread: the detector
    // until the wake word is heard, then what is said after it, then the
    // detector again. Afresh with each opening of the microphone, so a phrase
    // can't be made of sound from before it last closed.
    private inner class Pipeline(private val models: WakeWordModels) {
        private var detector = WakeWordDetector(models)
        private var trigger = WakeWordTrigger()
        private var utterance: Utterance? = null
        private var reportedAt = SystemClock.elapsedRealtime()
        private var wasSkipping = false

        fun accept(samples: ShortArray, count: Int) {
            val current = utterance
            if (current != null) {
                if (current.accept(samples, count)) finish(current)
                return
            }
            val processed = detector.chunksProcessed
            val scores = detector.accept(samples, count)
            for (score in scores) {
                if (onScore(score)) {
                    utterance = Utterance(detector.noiseFloor ?: DEFAULT_FLOOR_DB)
                    // The rest of this read is lost, a tenth of a second at most
                    return
                }
            }
            // Nothing run through the models: the room is quiet
            val skipping = scores.isEmpty() && detector.chunksProcessed == processed
            if (skipping != wasSkipping) {
                wasSkipping = skipping
                main.post { ListenerStatus.skipping(skipping) }
            }
            val now = SystemClock.elapsedRealtime()
            if (now - reportedAt >= SKIP_LOG_MILLIS) {
                reportedAt = now
                Log.d(TAG, "Ran the models on %d of %d chunks so far".format(
                    detector.chunksProcessed, detector.chunksIn))
            }
        }

        // Whether this score is the wake word
        private fun onScore(score: Float): Boolean {
            val heard = trigger.offer(score, threshold)
            // The near misses too, which is what tuning the threshold goes by
            if (score >= LOG_SCORE) Log.d(TAG, "Score %.2f".format(score))
            main.post {
                ListenerStatus.score(score)
                if (heard) {
                    Log.i(TAG, "Heard the wake word, score %.2f".format(score))
                    chime.play()
                    ListenerStatus.heard(score)
                    recording = true
                    sendListening(true)
                }
            }
            return heard
        }

        private fun finish(done: Utterance) {
            utterance = null
            // The windows hold sound from before the recording
            detector = WakeWordDetector(models)
            trigger = WakeWordTrigger()
            val file = File(filesDir, LAST_RECORDING)
            try {
                file.writeBytes(Utterance.wav(done.pcm(), WakeWordDetector.SAMPLE_RATE))
            } catch (e: IOException) {
                Log.w(TAG, "Can't save the recording", e)
            }
            Log.i(TAG, "Recorded %d ms, ended by %s".format(done.durationMillis, done.end))
            main.post { recorded(done) }
        }
    }

    // A recording ended, null if cut short by the microphone closing
    private fun recorded(done: Utterance?) {
        if (!recording) return
        recording = false
        sendListening(false)
        if (done != null) ListenerStatus.recorded(done.durationMillis, done.end!!.name.lowercase())
    }

    // Tells AstroDock's slideshow to show, or stop showing, that it is listening
    private fun sendListening(listening: Boolean) {
        sendBroadcast(
            Intent(AstroDockState.ACTION_LISTENING)
                .setPackage(AstroDockState.ASTRODOCK_PACKAGE)
                .putExtra(AstroDockState.EXTRA_LISTENING, listening),
            AstroDockState.PERMISSION,
        )
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
        private const val SKIP_LOG_MILLIS = 30_000L
        // For a recording that starts before the floor is known
        private const val DEFAULT_FLOOR_DB = -70.0
        // In filesDir, played back by the Wake Word screen
        const val LAST_RECORDING = "last_recording.wav"
        // Scores below this aren't worth a line in the log
        private const val LOG_SCORE = 0.1f

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
