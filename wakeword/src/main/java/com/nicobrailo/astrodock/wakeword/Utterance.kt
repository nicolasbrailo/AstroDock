package com.nicobrailo.astrodock.wakeword

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

// What is said after the wake word: recorded from the moment it is heard
// until the speaker stops, judged by the level against the room's noise
// floor (the detector's Quiet), chunk by chunk:
// - speech is a chunk SPEECH_DB over the floor;
// - it ends after END_SILENCE_CHUNKS without speech, once there was some;
// - or after NO_SPEECH_CHUNKS if nobody says anything;
// - or at MAX_CHUNKS whatever happens, since a noisy room may never be quiet.
// The first IGNORED_CHUNKS are the chime, which is recorded but is neither
// speech nor silence.
//
// Used on the microphone's thread.
class Utterance(private val floorDb: Double) {
    enum class End { SILENCE, NOTHING_SAID, TOO_LONG }

    private val audio = ByteArrayOutputStream()
    private val chunk = ShortArray(WakeWordDetector.CHUNK)
    private var filled = 0
    private var chunks = 0
    private var spoke = false
    private var silentChunks = 0

    // Why it ended, null while it hasn't
    var end: End? = null
        private set

    // Feeds samples, of any number; returns true once it has ended. Samples
    // after the end are not kept.
    fun accept(samples: ShortArray, count: Int): Boolean {
        var i = 0
        while (i < count && end == null) {
            val n = minOf(count - i, WakeWordDetector.CHUNK - filled)
            System.arraycopy(samples, i, chunk, filled, n)
            filled += n
            i += n
            if (filled == WakeWordDetector.CHUNK) {
                filled = 0
                onChunk()
            }
        }
        return end != null
    }

    private fun onChunk() {
        val bytes = ByteBuffer.allocate(chunk.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        var sumSquares = 0.0
        for (sample in chunk) {
            bytes.putShort(sample)
            sumSquares += sample.toDouble() * sample
        }
        audio.write(bytes.array())
        chunks++

        if (chunks > IGNORED_CHUNKS) {
            if (MicReader.dbfs(sumSquares, chunk.size) > floorDb + SPEECH_DB) {
                spoke = true
                silentChunks = 0
            } else {
                silentChunks++
            }
        }
        end = when {
            spoke && silentChunks >= END_SILENCE_CHUNKS -> End.SILENCE
            !spoke && chunks >= NO_SPEECH_CHUNKS -> End.NOTHING_SAID
            chunks >= MAX_CHUNKS -> End.TOO_LONG
            else -> null
        }
    }

    val durationMillis: Long get() = chunks * 1000L * WakeWordDetector.CHUNK / WakeWordDetector.SAMPLE_RATE

    // 16 bit mono PCM, little endian, at SAMPLE_RATE
    fun pcm(): ByteArray = audio.toByteArray()

    companion object {
        const val SPEECH_DB = 10.0
        // 0.8s, a pause that ends a sentence rather than one within it
        const val END_SILENCE_CHUNKS = 10
        // 3s to start talking
        const val NO_SPEECH_CHUNKS = 38
        // 8s
        const val MAX_CHUNKS = 100
        // The chime, 0.7s
        const val IGNORED_CHUNKS = 9

        // A WAV file of 16 bit mono PCM
        fun wav(pcm: ByteArray, sampleRate: Int): ByteArray {
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVE".toByteArray())
            header.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                .putInt(sampleRate).putInt(sampleRate * 2).putShort(2).putShort(16)
            header.put("data".toByteArray()).putInt(pcm.size)
            return header.array() + pcm
        }
    }
}
