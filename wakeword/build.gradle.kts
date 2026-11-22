import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

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

// The speech model, which is 40 MB and so isn't in git: downloaded once into
// Gradle's own cache, checked against the hash it had when it was picked, and
// unpacked into the APK's assets as Vosk's StorageService expects them, with a
// uuid file that tells it when the copy on the device is out of date.
abstract class VoskModelTask : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:Input
    abstract val assetName: Property<String>

    @get:Internal
    abstract val cacheDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun unpack() {
        val zip = cacheDir.file("${sha256.get()}.zip").get().asFile
        if (!zip.exists() || hash(zip) != sha256.get()) {
            zip.parentFile.mkdirs()
            val part = File(zip.path + ".part")
            logger.lifecycle("Downloading ${url.get()}")
            URI(url.get()).toURL().openStream().use { input -> part.outputStream().use { input.copyTo(it) } }
            val got = hash(part)
            if (got != sha256.get()) {
                part.delete()
                throw GradleException("${url.get()} has sha256 $got, expected ${sha256.get()}")
            }
            part.renameTo(zip)
        }

        val target = outputDir.dir(assetName.get()).get().asFile
        target.deleteRecursively()
        target.mkdirs()
        ZipInputStream(zip.inputStream().buffered()).use { entries ->
            while (true) {
                val entry = entries.nextEntry ?: break
                // The archive has everything under one top level directory
                val path = entry.name.substringAfter('/', "")
                if (path.isEmpty() || entry.isDirectory) continue
                if (path.split('/').any { it == ".." }) throw GradleException("Bad path in model: ${entry.name}")
                val file = File(target, path)
                file.parentFile.mkdirs()
                file.outputStream().use { entries.copyTo(it) }
            }
        }
        File(target, "uuid").writeText(sha256.get())
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
}

val voskModel = tasks.register<VoskModelTask>("voskModel") {
    // The small US English one, made for phones. Its vocabulary has both
    // "hey" and "portal".
    url.set("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip")
    sha256.set("30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498")
    // WakeWord.MODEL_ASSET
    assetName.set("model-en-us")
    cacheDir.set(File(gradle.gradleUserHomeDir, "caches/astrodock-vosk"))
    outputDir.set(layout.buildDirectory.dir("generated/vosk"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(voskModel, VoskModelTask::outputDir)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.preference.ktx)
    implementation(libs.vosk.android)
    testImplementation(libs.junit)
}
