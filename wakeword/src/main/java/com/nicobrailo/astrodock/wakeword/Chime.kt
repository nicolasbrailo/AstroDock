package com.nicobrailo.astrodock.wakeword

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

// The sound that says the wake word was heard: two bell-like notes, a fifth
// apart, made here rather than shipped as a file. One AudioTrack holds it for
// the life of the owner, and replays it from the start each time.
class Chime {
    private val samples = samples(SAMPLE_RATE)
    private val track = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setTransferMode(AudioTrack.MODE_STATIC)
        .setBufferSizeInBytes(samples.size * 2)
        .build()
        .also { it.write(samples, 0, samples.size) }

    fun play() {
        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop()
        track.reloadStaticData()
        track.play()
    }

    fun release() = track.release()

    companion object {
        const val SAMPLE_RATE = 44100
        // A5 and E6
        private val NOTES = listOf(880.0 to 0.0, 1318.5 to 0.12)
        private const val LENGTH_SECONDS = 0.7
        // How fast each note dies away, per second
        private const val DECAY = 7.0
        // Each note's attack, so it starts without a click
        private const val ATTACK_SECONDS = 0.005
        private const val VOLUME = 0.35

        fun samples(sampleRate: Int): ShortArray {
            val count = (LENGTH_SECONDS * sampleRate).roundToInt()
            return ShortArray(count) { i ->
                val t = i.toDouble() / sampleRate
                var value = 0.0
                for ((frequency, start) in NOTES) {
                    val local = t - start
                    if (local < 0) continue
                    val attack = (local / ATTACK_SECONDS).coerceAtMost(1.0)
                    // A quiet octave above gives it something of a bell
                    val tone = sin(2 * PI * frequency * local) + 0.3 * sin(4 * PI * frequency * local)
                    value += attack * exp(-DECAY * local) * tone
                }
                (value * VOLUME / NOTES.size * Short.MAX_VALUE).roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
    }
}
