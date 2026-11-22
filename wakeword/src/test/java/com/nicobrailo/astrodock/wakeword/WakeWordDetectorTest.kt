package com.nicobrailo.astrodock.wakeword

import com.nicobrailo.astrodock.wakeword.WakeWordDetector.Companion.CHUNK
import com.nicobrailo.astrodock.wakeword.WakeWordDetector.Companion.EMBEDDING_FRAMES
import com.nicobrailo.astrodock.wakeword.WakeWordDetector.Companion.PHRASE_EMBEDDINGS
import com.nicobrailo.astrodock.wakeword.WakeWordDetector.Companion.SPECTROGRAM_BANDS
import com.nicobrailo.astrodock.wakeword.WakeWordDetector.Companion.SPECTROGRAM_OVERLAP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordDetectorTest {
    // Records what the detector hands each model. Each chunk gives 8 frames,
    // as openWakeWord's spectrogram model does for 1760 samples, holding the
    // chunk's first sample, so where frames came from can be told.
    private class FakeModels : WakeWordDetector.Models {
        val spectrogramInputs = mutableListOf<FloatArray>()
        val embeddingInputs = mutableListOf<List<FloatArray>>()
        val scoreInputs = mutableListOf<List<FloatArray>>()

        override fun melspectrogram(samples: FloatArray): Array<FloatArray> {
            spectrogramInputs += samples
            val first = samples[SPECTROGRAM_OVERLAP]
            return Array(8) { FloatArray(SPECTROGRAM_BANDS) { first } }
        }

        override fun embedding(frames: List<FloatArray>): FloatArray {
            embeddingInputs += frames
            return floatArrayOf(frames.last()[0])
        }

        override fun score(embeddings: List<FloatArray>): Float {
            scoreInputs += embeddings
            return embeddings.last()[0]
        }
    }

    // Chunk n's samples all have the value n
    private fun chunks(count: Int, from: Int = 1) =
        ShortArray(count * CHUNK) { (from + it / CHUNK).toShort() }

    @Test
    fun scoresEachChunkOnceThereAreEmbeddingsEnough() {
        val models = FakeModels()
        val detector = WakeWordDetector(models, skipQuiet = false)
        val first = detector.accept(chunks(PHRASE_EMBEDDINGS - 1), (PHRASE_EMBEDDINGS - 1) * CHUNK)
        assertTrue(first.isEmpty())
        val next = detector.accept(chunks(3, from = PHRASE_EMBEDDINGS), 3 * CHUNK)
        assertEquals(3, next.size)
        assertEquals(PHRASE_EMBEDDINGS, models.scoreInputs.last().size)
    }

    @Test
    fun takesSamplesInAnySizeOfRead() {
        val whole = FakeModels()
        WakeWordDetector(whole, skipQuiet = false).accept(chunks(20), 20 * CHUNK)
        val pieces = FakeModels()
        val detector = WakeWordDetector(pieces, skipQuiet = false)
        val samples = chunks(20)
        // 100ms reads, as MicReader does, and an odd one
        var i = 0
        for (size in generateSequence(1600) { 1600 }.take(15) + 1999) {
            val n = minOf(size, samples.size - i)
            detector.accept(samples.copyOfRange(i, i + n), n)
            i += n
        }
        assertEquals(whole.spectrogramInputs.size, pieces.spectrogramInputs.size)
        for ((a, b) in whole.spectrogramInputs.zip(pieces.spectrogramInputs)) assertTrue(a.contentEquals(b))
    }

    @Test
    fun givesTheSpectrogramTheChunkAndTheSamplesBeforeIt() {
        val models = FakeModels()
        WakeWordDetector(models, skipQuiet = false).accept(chunks(2), 2 * CHUNK)
        val (first, second) = models.spectrogramInputs
        assertEquals(SPECTROGRAM_OVERLAP + CHUNK, first.size)
        // Silence before the first chunk, unscaled samples after
        assertTrue((0 until SPECTROGRAM_OVERLAP).all { first[it] == 0f })
        assertEquals(1f, first[SPECTROGRAM_OVERLAP])
        // The second overlaps with the end of the first
        assertTrue((0 until SPECTROGRAM_OVERLAP).all { second[it] == 1f })
        assertTrue((SPECTROGRAM_OVERLAP until second.size).all { second[it] == 2f })
    }

    @Test
    fun embedsTheLatestFramesScaled() {
        val models = FakeModels()
        WakeWordDetector(models, skipQuiet = false).accept(chunks(12), 12 * CHUNK)
        // Ones before any sound, as openWakeWord starts
        val firstWindow = models.embeddingInputs.first()
        assertEquals(EMBEDDING_FRAMES, firstWindow.size)
        assertEquals(1f, firstWindow.first()[0])
        // Then the frames, scaled by x / 10 + 2, newest last
        val window = models.embeddingInputs.last()
        assertEquals(EMBEDDING_FRAMES, window.size)
        assertEquals(WakeWordDetector.scaleSpectrogram(12f), window.last()[0])
        assertEquals(3.2f, window.last()[0], 1e-6f)
    }

    // Models whose every output depends on all of its input, so a window
    // that isn't the same shows up in the score
    private class HistoryModels : WakeWordDetector.Models {
        var calls = 0

        override fun melspectrogram(samples: FloatArray): Array<FloatArray> {
            calls++
            return Array(8) { f -> FloatArray(SPECTROGRAM_BANDS) { samples[f * 160 + 7] / 1000f } }
        }

        override fun embedding(frames: List<FloatArray>): FloatArray =
            floatArrayOf(frames.withIndex().sumOf { (i, frame) -> (i + 1.0) * frame[0] }.toFloat())

        override fun score(embeddings: List<FloatArray>): Float =
            embeddings.withIndex().sumOf { (i, e) -> (i + 1.0) * e[0] }.toFloat()
    }

    // Faint noise, then a loud stretch, chunk by chunk, each a little
    // different so the windows can be told apart
    private fun room(quietChunks: Int, loudChunks: Int): ShortArray {
        val random = java.util.Random(7)
        return ShortArray((quietChunks + loudChunks) * CHUNK) {
            val amplitude = if (it / CHUNK < quietChunks) 20 else 3000
            (random.nextInt(2 * amplitude) - amplitude).toShort()
        }
    }

    @Test
    fun runsNoModelWhileItIsQuiet() {
        val models = HistoryModels()
        val detector = WakeWordDetector(models)
        val scores = detector.accept(room(100, 0), 100 * CHUNK)
        assertTrue(scores.isEmpty())
        assertEquals(0, models.calls)
        assertEquals(100L, detector.chunksIn)
        assertEquals(0L, detector.chunksProcessed)
    }

    @Test
    fun scoresAfterTheQuietAsIfNothingWasSkipped() {
        val samples = room(100, 5)
        val always = WakeWordDetector(HistoryModels(), skipQuiet = false).accept(samples, samples.size)
        val skipping = WakeWordDetector(HistoryModels())
        val scores = skipping.accept(samples, samples.size)
        // Only the loud chunks are scored, and exactly as the detector that
        // processed everything scored them
        assertEquals(5, scores.size)
        assertEquals(always.takeLast(5), scores)
        // The loud chunks and the backlog before them, and nothing else
        assertEquals(5L + WakeWordDetector.BACKLOG_CHUNKS, skipping.chunksProcessed)
    }

    @Test
    fun carriesOnUntilTheLoudChunkHasLeftTheWindow() {
        val samples = room(100, 1)
        val quietAfter = room(60, 0)
        val detector = WakeWordDetector(HistoryModels())
        detector.accept(samples, samples.size)
        val scores = detector.accept(quietAfter, quietAfter.size)
        assertEquals(WakeWordDetector.BACKLOG_CHUNKS - 1, scores.size)
    }

    @Test
    fun hearsWhatStandsOutFromTheRoom() {
        val quiet = Quiet()
        // A steady hum becomes the floor
        assertFalse(quiet.isLoud(-60.0))
        repeat(50) { assertFalse(quiet.isLoud(-60.0)) }
        // Typing or a voice stands out
        assertTrue(quiet.isLoud(-45.0))
        // The floor follows a quieter room down at once
        assertFalse(quiet.isLoud(-75.0))
        assertTrue(quiet.isLoud(-60.0))
    }

    @Test
    fun getsUsedToALongNoise() {
        val quiet = Quiet()
        quiet.isLoud(-70.0)
        // A noise that goes on rises the floor until it is the floor
        val loud = (1..200).map { quiet.isLoud(-50.0) }
        assertTrue(loud.first())
        assertFalse(loud.last())
    }

    @Test
    fun triggersOnceAPhrase() {
        val trigger = WakeWordTrigger(refractoryChunks = 3)
        val scores = listOf(0.1f, 0.6f, 0.9f, 0.8f, 0.7f, 0.2f, 0.6f)
        val heard = scores.map { trigger.offer(it, threshold = 0.5f) }
        // The phrase's first chunk over the threshold, and not the rest of it
        assertEquals(listOf(false, true, false, false, false, false, true), heard)
    }

    @Test
    fun triggersOnlyAtTheThreshold() {
        val trigger = WakeWordTrigger()
        assertFalse(trigger.offer(0.49f, threshold = 0.5f))
        assertTrue(trigger.offer(0.5f, threshold = 0.5f))
    }
}
