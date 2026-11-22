package com.nicobrailo.astrodock.wakeword

// Turns the microphone's samples into a score for the wake word, the way
// openWakeWord does (openwakeword/utils.py, AudioFeatures, v0.5.1), which is
// what its models were trained on. Every CHUNK of samples (80ms) gives one
// score from 0 to 1:
// 1. a mel spectrogram of the chunk, plus SPECTROGRAM_OVERLAP samples before
//    it, scaled as openWakeWord scales it;
// 2. an embedding (96 numbers) of the last EMBEDDING_FRAMES frames of that;
// 3. the phrase's own model, on the last PHRASE_EMBEDDINGS embeddings.
// Only the models (Models) need Android; this is the buffering around them,
// which is unit tested with fake ones.
//
// The embedding is most of the cost (about 17ms of every 80 on a Portal+), so
// while the room is quiet nothing is run at all (skipQuiet): no phrase can be
// in a window of silence. The last BACKLOG_CHUNKS are kept, and the first
// loud one (Quiet) has them processed before it, which is as far back as a
// score looks, so the scores from then on are the same as if nothing had been
// skipped. Processing carries on until the last loud chunk has left that
// window, so a phrase's score dies away rather than stopping.
//
// Used on one thread, the microphone's.
class WakeWordDetector(private val models: Models, private val skipQuiet: Boolean = true) {
    interface Models {
        // A frame of SPECTROGRAM_BANDS for each 10ms of the samples, which are
        // 16 bit values as floats, unscaled
        fun melspectrogram(samples: FloatArray): Array<FloatArray>
        fun embedding(frames: List<FloatArray>): FloatArray
        fun score(embeddings: List<FloatArray>): Float
    }

    // The chunk being filled
    private val chunk = ShortArray(CHUNK)
    private var filled = 0
    // Chunks not processed yet, oldest first, while it is quiet
    private val backlog = ArrayDeque<ShortArray>()
    // How many more chunks to process after the last loud one
    private var activeFor = 0
    private val quiet = Quiet()
    // The samples before the next chunk to process, which its spectrogram
    // overlaps with
    private val previous = ShortArray(SPECTROGRAM_OVERLAP)
    // Starts as openWakeWord's does, all ones, until real frames push them out
    private val frames = ArrayDeque<FloatArray>().apply {
        repeat(EMBEDDING_FRAMES) { add(FloatArray(SPECTROGRAM_BANDS) { 1f }) }
    }
    private val embeddings = ArrayDeque<FloatArray>()

    // How many chunks have come in, and how many of them were run through
    // the models, to see what skipping the quiet saves
    var chunksIn = 0L
        private set
    var chunksProcessed = 0L
        private set

    // The room's background level in dBFS, null before the first chunk
    val noiseFloor: Double? get() = quiet.floor

    // Feeds samples, of any number. Returns a score for each chunk they
    // complete that was processed, and none until there are embeddings enough
    // for the phrase's model, a little over a second after starting.
    fun accept(samples: ShortArray, count: Int): List<Float> {
        val scores = mutableListOf<Float>()
        var i = 0
        while (i < count) {
            val n = minOf(count - i, CHUNK - filled)
            System.arraycopy(samples, i, chunk, filled, n)
            filled += n
            i += n
            if (filled == CHUNK) {
                filled = 0
                onChunk(chunk.copyOf())?.let { scores += it }
            }
        }
        return scores
    }

    private fun onChunk(samples: ShortArray): Float? {
        chunksIn++
        // Every chunk, so the floor is known whether or not quiet is skipped
        val loud = quiet.isLoud(samples)
        if (!skipQuiet) return process(samples, score = true)

        if (loud) activeFor = BACKLOG_CHUNKS
        if (activeFor == 0) {
            backlog.addLast(samples)
            // What falls off the back is never processed, but the next chunk
            // still overlaps with its end
            if (backlog.size > BACKLOG_CHUNKS) {
                val dropped = backlog.removeFirst()
                System.arraycopy(dropped, CHUNK - SPECTROGRAM_OVERLAP, previous, 0, SPECTROGRAM_OVERLAP)
            }
            return null
        }
        activeFor--
        // Their scores are of the quiet, and of a window that may still hold
        // frames from before the gap, so only this chunk's counts
        while (backlog.isNotEmpty()) process(backlog.removeFirst(), score = false)
        return process(samples, score = true)
    }

    private fun process(samples: ShortArray, score: Boolean): Float? {
        chunksProcessed++
        val input = FloatArray(SPECTROGRAM_OVERLAP + CHUNK)
        for (j in previous.indices) input[j] = previous[j].toFloat()
        for (j in samples.indices) input[SPECTROGRAM_OVERLAP + j] = samples[j].toFloat()
        System.arraycopy(samples, CHUNK - SPECTROGRAM_OVERLAP, previous, 0, SPECTROGRAM_OVERLAP)

        for (frame in models.melspectrogram(input)) {
            frames.addLast(FloatArray(frame.size) { scaleSpectrogram(frame[it]) })
        }
        while (frames.size > EMBEDDING_FRAMES) frames.removeFirst()

        embeddings.addLast(models.embedding(frames.toList()))
        if (embeddings.size > PHRASE_EMBEDDINGS) embeddings.removeFirst()
        if (!score || embeddings.size < PHRASE_EMBEDDINGS) return null
        return models.score(embeddings.toList())
    }

    companion object {
        const val SAMPLE_RATE = 16000
        // 80ms
        const val CHUNK = 1280
        // Three frames' worth, so the chunk's first frames have the sound
        // before them, as in openWakeWord
        const val SPECTROGRAM_OVERLAP = 160 * 3
        const val SPECTROGRAM_BANDS = 32
        const val EMBEDDING_FRAMES = 76
        const val EMBEDDING_SIZE = 96
        const val PHRASE_EMBEDDINGS = 16
        // How far back a score looks: the phrase model's embeddings, plus the
        // frames the oldest of them was made from (76 frames is 9.5 chunks).
        // About 2 seconds.
        const val BACKLOG_CHUNKS = PHRASE_EMBEDDINGS + 10

        // openWakeWord's, which brings its spectrogram model's output closer
        // to the TensorFlow one the embedding model was made with
        fun scaleSpectrogram(value: Float): Float = value / 10f + 2f
    }
}

// Tells a chunk with something in it from the room's background noise: loud
// is LOUDER_DB over the noise floor. The floor follows the level down at once
// and up only slowly, so a steady hum becomes the floor while a voice, a
// knock or typing stands out from it.
class Quiet {
    var floor: Double? = null
        private set

    fun isLoud(samples: ShortArray): Boolean {
        var sumSquares = 0.0
        for (sample in samples) sumSquares += sample.toDouble() * sample
        return isLoud(MicReader.dbfs(sumSquares, samples.size))
    }

    fun isLoud(dbfs: Double): Boolean {
        val current = floor
        if (current == null || dbfs < current) {
            floor = dbfs
            return false
        }
        floor = current + FLOOR_RISE_DB
        return dbfs > current + LOUDER_DB
    }

    companion object {
        const val LOUDER_DB = 10.0
        // Per 80ms chunk: 1.5 dB a second
        const val FLOOR_RISE_DB = 0.12
    }
}

// Decides when a score counts as hearing the phrase. A phrase scores high for
// several chunks in a row, so after one counts, the rest of it doesn't.
class WakeWordTrigger(private val refractoryChunks: Int = REFRACTORY_CHUNKS) {
    private var quietFor = 0

    fun offer(score: Float, threshold: Float): Boolean {
        if (quietFor > 0) {
            quietFor--
            return false
        }
        if (score < threshold) return false
        quietFor = refractoryChunks
        return true
    }

    companion object {
        // 2 seconds of chunks
        const val REFRACTORY_CHUNKS = 25
    }
}
