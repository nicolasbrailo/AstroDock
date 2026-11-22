import java.net.URI
import java.security.MessageDigest

// The wake word listener: a part of AstroDock (app/ depends on it) that runs
// in a process of its own, ":wakeword", kept in a module of its own so that
// it only knows AstroDock by its state broadcast.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.nicobrailo.astrodock.wakeword"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 28
    }
    testOptions {
        // android.util.Log etc. do nothing in unit tests instead of throwing
        unitTests.isReturnDefaultValues = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// The wake word models (see WakeWordModels), which aren't in git: downloaded
// once into Gradle's own cache, checked against the hash they had when they
// were picked, and copied into the APK's assets under wakeword/.
abstract class ModelFilesTask : DefaultTask() {
    // Asset name to "<url> <sha256>"
    @get:Input
    abstract val files: MapProperty<String, String>

    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val target = outputDir.dir(WAKEWORD_ASSETS).get().asFile
        target.deleteRecursively()
        target.mkdirs()
        for ((name, source) in files.get()) {
            val (url, sha256) = source.split(' ')
            val cached = cacheDir.file(sha256).get().asFile
            if (!cached.exists() || hash(cached) != sha256) {
                cached.parentFile.mkdirs()
                val part = File(cached.path + ".part")
                logger.lifecycle("Downloading $url")
                URI(url).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
                val got = hash(part)
                if (got != sha256) {
                    part.delete()
                    throw GradleException("$url has sha256 $got, expected $sha256")
                }
                part.renameTo(cached)
            }
            cached.copyTo(File(target, name))
        }
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        // WakeWordModels.ASSETS
        const val WAKEWORD_ASSETS = "wakeword"
    }
}

val modelFiles = tasks.register<ModelFilesTask>("wakewordModels") {
    // openWakeWord's (https://github.com/dscripka/openWakeWord), from its
    // v0.5.1 release. The first two turn sound into features and are Apache
    // 2.0; the third is the phrase, "hey rhasspy", one of its pretrained ones,
    // which are CC BY-NC-SA 4.0: fine for AstroDock, not for selling it.
    val release = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1"
    files.put(
        "melspectrogram.tflite",
        "$release/melspectrogram.tflite 96fa0adccb6e8cf95cb14465409a1a2898ee4a96a85bb9ed3c7eb0e68bf163e8",
    )
    files.put(
        "embedding.tflite",
        "$release/embedding_model.tflite c0aea21eb84a4ce90a08c870da41b7a7173b45269e6a3207c71d67c40f3a59d8",
    )
    files.put(
        "phrase.tflite",
        "$release/hey_rhasspy_v0.1.tflite 01d2526b45068f565aa3849d6ec2b7abae099154fc1b496f9ef20de9ef241fe9",
    )
    cacheDir.set(File(gradle.gradleUserHomeDir, "caches/astrodock-wakeword"))
    outputDir.set(layout.buildDirectory.dir("generated/wakeword"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(modelFiles, ModelFilesTask::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.tensorflow.lite)
    testImplementation(libs.junit)
}
