package com.nicobrailo.astrodock.wakeword

import com.nicobrailo.astrodock.wakeword.Utterance.Companion.END_SILENCE_CHUNKS
import com.nicobrailo.astrodock.wakeword.Utterance.Companion.IGNORED_CHUNKS
import com.nicobrailo.astrodock.wakeword.Utterance.Companion.MAX_CHUNKS
import com.nicobrailo.astrodock.wakeword.Utterance.Companion.NO_SPEECH_CHUNKS
import com.nicobrailo.astrodock.wakeword.WakeWordDetector.Companion.CHUNK
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class UtteranceTest {
    // A room at -70 dBFS, so speech is anything over -60
    private val floor = -70.0
    private val quietChunk = ShortArray(CHUNK) { if (it % 2 == 0) 5 else -5 }
    private val loudChunk = ShortArray(CHUNK) { if (it % 2 == 0) 3000 else -3000 }

    private fun Utterance.feed(chunk: ShortArray, times: Int): Boolean {
        var ended = false
        repeat(times) { ended = accept(chunk, chunk.size) }
        return ended
    }

    @Test
    fun endsAPauseAfterTheSpeech() {
        val utterance = Utterance(floor)
        // The chime, loud but not speech
        assertFalse(utterance.feed(loudChunk, IGNORED_CHUNKS))
        assertFalse(utterance.feed(loudChunk, 20))
        // A shorter pause is within the sentence
        assertFalse(utterance.feed(quietChunk, END_SILENCE_CHUNKS - 1))
        assertFalse(utterance.feed(loudChunk, 5))
        assertTrue(utterance.feed(quietChunk, END_SILENCE_CHUNKS))
        assertEquals(Utterance.End.SILENCE, utterance.end)
        val chunks = IGNORED_CHUNKS + 20 + END_SILENCE_CHUNKS - 1 + 5 + END_SILENCE_CHUNKS
        assertEquals(chunks * CHUNK * 2, utterance.pcm().size)
        assertEquals(chunks * 80L, utterance.durationMillis)
    }

    @Test
    fun givesUpWhenNothingIsSaid() {
        val utterance = Utterance(floor)
        // The chime alone isn't speech, so the silence after it doesn't end it
        assertFalse(utterance.feed(loudChunk, IGNORED_CHUNKS))
        assertFalse(utterance.feed(quietChunk, NO_SPEECH_CHUNKS - IGNORED_CHUNKS - 1))
        assertNull(utterance.end)
        assertTrue(utterance.feed(quietChunk, 1))
        assertEquals(Utterance.End.NOTHING_SAID, utterance.end)
    }

    @Test
    fun stopsAtTheLongestItTakes() {
        val utterance = Utterance(floor)
        assertFalse(utterance.feed(loudChunk, MAX_CHUNKS - 1))
        assertTrue(utterance.feed(loudChunk, 1))
        assertEquals(Utterance.End.TOO_LONG, utterance.end)
        // Nothing after the end is kept
        utterance.feed(loudChunk, 5)
        assertEquals(MAX_CHUNKS * CHUNK * 2, utterance.pcm().size)
    }

    @Test
    fun takesSamplesInAnySizeOfRead() {
        val utterance = Utterance(floor)
        val samples = ShortArray(3 * CHUNK + 100) { it.toShort() }
        utterance.accept(samples, 1000)
        utterance.accept(samples.copyOfRange(1000, samples.size), samples.size - 1000)
        // Only whole chunks, in order
        val pcm = ByteBuffer.wrap(utterance.pcm()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        assertEquals(3 * CHUNK, pcm.remaining())
        assertEquals(1500.toShort(), pcm.get(1500))
    }

    @Test
    fun writesAWavHeader() {
        val wav = Utterance.wav(ByteArray(8), 16000)
        assertEquals(52, wav.size)
        assertArrayEquals("RIFF".toByteArray(), wav.copyOfRange(0, 4))
        val header = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(44, header.getInt(4))
        assertArrayEquals("WAVEfmt ".toByteArray(), wav.copyOfRange(8, 16))
        assertEquals(1.toShort(), header.getShort(22))
        assertEquals(16000, header.getInt(24))
        assertEquals(32000, header.getInt(28))
        assertEquals(16.toShort(), header.getShort(34))
        assertArrayEquals("data".toByteArray(), wav.copyOfRange(36, 40))
        assertEquals(8, header.getInt(40))
    }
}
