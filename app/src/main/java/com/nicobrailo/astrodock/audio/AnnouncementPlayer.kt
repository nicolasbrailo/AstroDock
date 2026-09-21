package com.nicobrailo.astrodock.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.PowerManager
import android.util.Log
import com.nicobrailo.astrodock.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

// Plays the audio files the MQTT announce_audio command points at, such as a
// TTS message rendered by the homeboard. Each one is downloaded in full before
// it plays: a slow server would otherwise make it stutter, and a failed
// download is then a clear error rather than half a sentence.
//
// Announcements play one at a time, in the order they arrived. Every callback
// runs on the main thread: onError gets a message for the screen when one can't
// be fetched or played, and play()'s own ones say when the sound starts and
// when it has finished.
class AnnouncementPlayer(context: Context, private val onError: (String) -> Unit) {
    private val context = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val queue = Mutex()
    private val audioManager = this.context.getSystemService(AudioManager::class.java)
    private val powerManager = this.context.getSystemService(PowerManager::class.java)

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun play(uri: String, volumePercent: Int, onStarted: () -> Unit, onFinished: () -> Unit) {
        scope.launch { queue.withLock { fetchAndPlay(uri, volumePercent, onStarted, onFinished) } }
    }

    private suspend fun fetchAndPlay(
        uri: String,
        volumePercent: Int,
        onStarted: () -> Unit,
        onFinished: () -> Unit,
    ) {
        // The screen may well be off; without this the device can go back to
        // sleep in the middle of the download or of the sentence
        val awake = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "astrodock:announce")?.apply {
            setReferenceCounted(false)
            acquire(WAKE_LOCK_MILLIS)
        }
        val file = File(File(context.cacheDir, "announcements").apply { mkdirs() }, "announcement")
        try {
            try {
                download(uri, file)
            } catch (e: IOException) {
                Log.w(TAG, "Can't fetch $uri", e)
                onError(context.getString(R.string.announce_audio_fetch_failed, uri, reason(e)))
                return
            }
            try {
                withTimeout(PLAY_TIMEOUT_MILLIS) { playFile(file, volumePercent, onStarted) }
                Log.i(TAG, "Played $uri")
                onFinished()
            } catch (e: Exception) {
                // IOException for a file MediaPlayer can't read, a timeout for
                // one that never finishes; either way it shouldn't hold up the
                // announcements behind it
                Log.w(TAG, "Can't play $uri", e)
                onError(context.getString(R.string.announce_audio_play_failed, uri, reason(e)))
            }
        } finally {
            file.delete()
            if (awake?.isHeld == true) awake.release()
        }
    }

    private suspend fun download(uri: String, file: File) = withContext(Dispatchers.IO) {
        val url = uri.toHttpUrlOrNull() ?: throw IOException("Not an http:// or https:// URL")
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body
            if (body.contentLength() > MAX_BYTES) {
                throw IOException("Too big: ${body.contentLength()} bytes")
            }
            // Content-Length is optional, so the cap is also enforced as it
            // arrives
            var total = 0L
            file.outputStream().use { out ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_BYTES) throw IOException("Too big: over $MAX_BYTES bytes")
                        out.write(buffer, 0, read)
                    }
                }
            }
            if (total == 0L) throw IOException("The server sent an empty file")
        }
    }

    // Runs on the main thread, so MediaPlayer's callbacks arrive there too
    private suspend fun playFile(file: File, volumePercent: Int, onStarted: () -> Unit) {
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        // Transient, so whatever was playing pauses and resumes afterwards.
        // Ducking would leave the music playing at the announcement's volume,
        // since both are on the media stream.
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .build()
        if (audioManager?.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // Played anyway: an announcement nobody hears is worse than one
            // spoken over the music
            Log.w(TAG, "No audio focus, playing anyway")
        }
        val restoreVolume = setVolume(volumePercent)
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(attributes)
            player.setDataSource(file.path)
            suspendCancellableCoroutine { done ->
                player.setOnPreparedListener {
                    it.start()
                    onStarted()
                }
                player.setOnCompletionListener { if (done.isActive) done.resume(Unit) }
                player.setOnErrorListener { _, what, extra ->
                    if (done.isActive) done.resumeWithException(IOException("Can't decode it (error $what, $extra)"))
                    true
                }
                player.prepareAsync()
            }
        } finally {
            player.release()
            restoreVolume()
            audioManager?.abandonAudioFocusRequest(focus)
        }
    }

    // Sets the media volume and returns what puts it back. The requested volume
    // is of the device, not of this one file: MediaPlayer's own volume can only
    // make it quieter than the stream, so a quiet device would stay quiet.
    private fun setVolume(percent: Int): () -> Unit {
        val audio = audioManager ?: return {}
        if (audio.isVolumeFixed) return {}
        val stream = AudioManager.STREAM_MUSIC
        val before = audio.getStreamVolume(stream)
        val wanted = volumeIndex(percent, audio.getStreamMaxVolume(stream))
        try {
            audio.setStreamVolume(stream, wanted, 0)
        } catch (e: SecurityException) {
            // Thrown when Do Not Disturb forbids the change
            Log.w(TAG, "Can't set the volume", e)
            return {}
        }
        return {
            // Someone who changed the volume while it played meant it
            if (audio.getStreamVolume(stream) == wanted) audio.setStreamVolume(stream, before, 0)
        }
    }

    private fun reason(e: Exception): String =
        e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName

    companion object {
        private const val TAG = "AnnouncementPlayer"
        // A spoken message is a few hundred kB; this is only there so a wrong
        // URL can't fill the cache
        private const val MAX_BYTES = 20L * 1024 * 1024
        private const val PLAY_TIMEOUT_MILLIS = 10 * 60 * 1000L
        // Download and playback together, as a backstop; released as soon as
        // it's over
        private const val WAKE_LOCK_MILLIS = 12 * 60 * 1000L

        // The stream's volume step for a percentage. Any volume above 0 gets at
        // least the lowest step: whoever asked for 3% still wants it heard.
        fun volumeIndex(percent: Int, maxIndex: Int): Int {
            val p = percent.coerceIn(0, 100)
            val index = (p * maxIndex + 50) / 100
            return if (p > 0) index.coerceAtLeast(1) else 0
        }
    }
}
