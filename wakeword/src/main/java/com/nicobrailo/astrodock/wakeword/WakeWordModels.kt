package com.nicobrailo.astrodock.wakeword

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

// openWakeWord's three models, run with TensorFlow Lite (the standalone
// runtime, which needs no Google Play Services). build.gradle.kts downloads
// them into the assets. One thread each, since a few of them a second is
// all it takes.
class WakeWordModels(context: Context) : WakeWordDetector.Models, Closeable {
    private val melspectrogram = interpreter(context, "melspectrogram.tflite")
    private val embedding = interpreter(context, "embedding.tflite")
    private val phrase = interpreter(context, "phrase.tflite")

    // The spectrogram model takes any number of samples; it is only resized
    // when that changes, which it doesn't after the first chunk
    private var melspectrogramInput = 0
    private var melspectrogramFrames = 0

    // Reused from one chunk to the next. Direct buffers rather than nested
    // arrays, which TensorFlow Lite's Java side copies one innermost array
    // at a time: 2432 of them for each embedding, which made it slower than
    // the model itself.
    private val embeddingInput = floats(WakeWordDetector.EMBEDDING_FRAMES * WakeWordDetector.SPECTROGRAM_BANDS)
    private val embeddingOutput = floats(WakeWordDetector.EMBEDDING_SIZE)
    private val phraseInput = floats(WakeWordDetector.PHRASE_EMBEDDINGS * WakeWordDetector.EMBEDDING_SIZE)
    private val phraseOutput = floats(1)

    // How long each model takes, logged now and then, since this is all the
    // CPU the listener uses
    private val spent = LongArray(3)
    private var timed = 0

    private inline fun <T> timed(model: Int, block: () -> T): T {
        val start = SystemClock.elapsedRealtimeNanos()
        val result = block()
        spent[model] += SystemClock.elapsedRealtimeNanos() - start
        if (model == 2 && ++timed == TIMING_CHUNKS) {
            Log.d(TAG, "Per chunk: spectrogram %.2f ms, embedding %.2f ms, phrase %.2f ms".format(
                spent[0] / 1e6 / timed, spent[1] / 1e6 / timed, spent[2] / 1e6 / timed))
            spent.fill(0)
            timed = 0
        }
        return result
    }

    override fun melspectrogram(samples: FloatArray): Array<FloatArray> {
        if (samples.size != melspectrogramInput) {
            melspectrogram.resizeInput(0, intArrayOf(1, samples.size))
            melspectrogram.allocateTensors()
            melspectrogramInput = samples.size
            // [1, 1, frames, bands]
            melspectrogramFrames = melspectrogram.getOutputTensor(0).shape()[2]
        }
        val output = Array(1) { Array(1) { Array(melspectrogramFrames) { FloatArray(WakeWordDetector.SPECTROGRAM_BANDS) } } }
        timed(0) { melspectrogram.run(arrayOf(samples), output) }
        return output[0][0]
    }

    override fun embedding(frames: List<FloatArray>): FloatArray {
        embeddingInput.clear()
        for (frame in frames) embeddingInput.put(frame)
        embeddingInput.rewind()
        embeddingOutput.clear()
        timed(1) { embedding.run(embeddingInput, embeddingOutput) }
        embeddingOutput.rewind()
        return FloatArray(WakeWordDetector.EMBEDDING_SIZE).also { embeddingOutput.get(it) }
    }

    override fun score(embeddings: List<FloatArray>): Float {
        phraseInput.clear()
        for (values in embeddings) phraseInput.put(values)
        phraseInput.rewind()
        phraseOutput.clear()
        timed(2) { phrase.run(phraseInput, phraseOutput) }
        return phraseOutput.get(0)
    }

    override fun close() {
        melspectrogram.close()
        embedding.close()
        phrase.close()
    }

    companion object {
        // Where build.gradle.kts puts them in the assets
        const val ASSETS = "wakeword"
        private const val TAG = "WakeWordModels"
        // 10 seconds
        private const val TIMING_CHUNKS = 125

        private fun floats(count: Int): FloatBuffer =
            ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        // Read whole into memory: assets are compressed in the APK, so they
        // can't be mapped
        private fun interpreter(context: Context, name: String): Interpreter {
            val bytes = context.assets.open("$ASSETS/$name").use { it.readBytes() }
            val buffer = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
            buffer.put(bytes).rewind()
            return Interpreter(buffer, Interpreter.Options().setNumThreads(1))
        }
    }
}
