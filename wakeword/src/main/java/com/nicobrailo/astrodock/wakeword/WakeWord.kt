package com.nicobrailo.astrodock.wakeword

// The phrase and how a recognised one is told apart. Vosk is a full speech
// recogniser, so it is given a grammar of just the phrase and "[unk]", which
// soaks up everything else said; whatever comes back holding the phrase's
// words in order counts as hearing it.
object WakeWord {
    const val PHRASE = "hey portal"
    // Where build.gradle.kts unpacks the model in the APK's assets, and
    // where StorageService copies it to on the device
    const val MODEL_ASSET = "model-en-us"
    const val MODEL_DIR = "model"
    // What Vosk's models are trained on
    const val SAMPLE_RATE = 16000f

    // A JSON list of what may be recognised. Written out rather than built
    // with org.json, which is only a stub in unit tests.
    val GRAMMAR = """["$PHRASE", "[unk]"]"""

    private val WORDS = PHRASE.split(' ')

    // Whether a recognised utterance ("[unk] hey portal") holds the phrase
    fun heard(text: String): Boolean {
        val words = text.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return words.windowed(WORDS.size).any { it == WORDS }
    }
}
