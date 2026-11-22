package com.nicobrailo.astrodock.wakeword

import android.os.Handler
import android.os.Looper

// What the listener is doing, for the settings screen, which runs in the
// same process. Used on the main thread.
object ListenerStatus {
    var listening = false
        private set
    // Why it isn't, while it isn't; null while listening or loading the model
    var refusal: Refusal? = Refusal.DISABLED
        private set
    // What went wrong with the model or the microphone, null while nothing has
    var problem: String? = null
        private set
    // Whether the settings screen is in front, which has the listener listen
    // whatever AstroDock says (ListenGate)
    var onScreen = false
    // The microphone's level in dBFS, null while it is closed
    var level: Double? = null
        private set
    // The latest score, and the highest in the last PEAK_MILLIS with when it
    // was, which is what shows how near a phrase came
    var score: Float? = null
        private set
    var peak: Float? = null
        private set
    // Whether the room is quiet enough that nothing is being scored
    var skipping = false
        private set
    private var peakAt = 0L
    // Wall clock of the last time the phrase was heard, and its score
    var lastHeardAt: Long? = null
        private set
    var lastHeardScore: Float? = null
        private set
    // The last recording after the wake word: how long, why it ended
    // (Utterance.End, in lower case) and when
    var lastRecordingMillis: Long? = null
        private set
    var lastRecordingEnd: String? = null
        private set
    var lastRecordingAt: Long? = null
        private set
    private const val PEAK_MILLIS = 10_000L

    private val listeners = mutableSetOf<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun set(listening: Boolean, refusal: Refusal?, problem: String?) {
        if (listening == this.listening && refusal == this.refusal && problem == this.problem) return
        this.listening = listening
        this.refusal = refusal
        this.problem = problem
        changed()
    }

    fun level(dbfs: Double?) {
        level = dbfs
        if (dbfs == null) {
            score = null
            peak = null
            skipping = false
        }
        changed()
    }

    fun skipping(value: Boolean) {
        skipping = value
        changed()
    }

    fun score(value: Float) {
        score = value
        val now = System.currentTimeMillis()
        val current = peak
        if (current == null || value >= current || now - peakAt > PEAK_MILLIS) {
            peak = value
            peakAt = now
        }
        changed()
    }

    fun heard(score: Float) {
        lastHeardAt = System.currentTimeMillis()
        lastHeardScore = score
        changed()
    }

    fun recorded(millis: Long, end: String) {
        lastRecordingMillis = millis
        lastRecordingEnd = end
        lastRecordingAt = System.currentTimeMillis()
        changed()
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun changed() {
        // A copy, since a listener may remove itself
        main.post { listeners.toList().forEach { it() } }
    }
}
