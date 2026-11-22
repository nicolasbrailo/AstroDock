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
    // What the recogniser last made of the sound, finished or not
    var hearing: String? = null
        private set
    // Wall clock of the last time the phrase was heard, and what was heard
    var lastHeardAt: Long? = null
        private set
    var lastHeard: String? = null
        private set

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
        changed()
    }

    fun hearing(text: String) {
        if (text == hearing) return
        hearing = text
        changed()
    }

    fun heard(text: String) {
        lastHeardAt = System.currentTimeMillis()
        lastHeard = text
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
