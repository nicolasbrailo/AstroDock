package com.nicobrailo.astrodock.wakeword

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.vosk.Recognizer
import java.io.IOException
import kotlin.math.log10
import kotlin.math.sqrt

// Reads the microphone on a thread of its own and feeds it to the recogniser.
// It does what Vosk's SpeechService does, and also measures how loud the
// sound is, which is the quickest way to tell a microphone that gives us
// silence from a phrase that isn't recognised.
//
// Everything it reports arrives on the main thread. The recogniser is only
// used on the reader's thread, and is the caller's to close after stop().
class MicReader(
    private val recognizer: Recognizer,
    private val sampleRate: Int,
    // A MediaRecorder.AudioSource (see WakewordSettings.source)
    private val source: Int,
    private val listener: Listener,
) {
    interface Listener {
        // The sound's level in dBFS, a few times a second
        fun onLevel(dbfs: Double)
        fun onPartial(hypothesis: String)
        fun onResult(hypothesis: String)
        fun onError(e: Exception)
    }

    private val main = Handler(Looper.getMainLooper())
    private val record: AudioRecord
    private var thread: Thread? = null
    @Volatile
    private var running = false

    init {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) throw IOException("No recording at $sampleRate Hz")
        record = newRecord(maxOf(minBuffer, sampleRate * 2 * BUFFER_SECONDS))
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IOException("Can't set up the microphone")
        }
    }

    // RECORD_AUDIO is checked before ListenerService gets this far
    @SuppressLint("MissingPermission")
    private fun newRecord(bufferBytes: Int) = AudioRecord(
        source, sampleRate,
        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferBytes,
    )

    fun start() {
        if (running) return
        running = true
        thread = Thread({ read() }, "MicReader").also { it.start() }
    }

    // Waits for the thread, so the recogniser is free once this returns
    fun stop() {
        running = false
        thread?.join()
        thread = null
        record.release()
    }

    private fun read() {
        record.startRecording()
        // Up to Android 9 this is what another app holding the microphone
        // looks like
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            running = false
            main.post { listener.onError(IOException("The microphone didn't start; another app may have it")) }
            return
        }
        val buffer = ShortArray(sampleRate / CHUNKS_PER_SECOND)
        var lastPartial: String? = null
        var sumSquares = 0.0
        var counted = 0
        try {
            while (running) {
                val read = record.read(buffer, 0, buffer.size)
                if (read < 0) throw IOException("Reading the microphone failed: $read")
                for (i in 0 until read) {
                    val sample = buffer[i].toDouble()
                    sumSquares += sample * sample
                }
                counted += read
                if (counted >= sampleRate / LEVELS_PER_SECOND) {
                    val level = dbfs(sumSquares, counted)
                    main.post { listener.onLevel(level) }
                    sumSquares = 0.0
                    counted = 0
                }
                if (recognizer.acceptWaveForm(buffer, read)) {
                    val result = recognizer.result
                    lastPartial = null
                    main.post { listener.onResult(result) }
                } else {
                    val partial = recognizer.partialResult
                    if (partial != lastPartial) {
                        lastPartial = partial
                        main.post { listener.onPartial(partial) }
                    }
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Microphone failed", e)
            running = false
            main.post { listener.onError(e) }
        } finally {
            record.stop()
        }
    }

    companion object {
        private const val TAG = "MicReader"
        private const val BUFFER_SECONDS = 1
        private const val CHUNKS_PER_SECOND = 10
        private const val LEVELS_PER_SECOND = 5
        // What silence reads as, rather than minus infinity
        const val SILENCE_DBFS = -96.0

        // The root mean square of 16 bit samples, in decibels below full scale
        fun dbfs(sumSquares: Double, count: Int): Double {
            if (count == 0 || sumSquares <= 0.0) return SILENCE_DBFS
            val rms = sqrt(sumSquares / count) / Short.MAX_VALUE
            return maxOf(SILENCE_DBFS, 20 * log10(rms))
        }
    }
}
