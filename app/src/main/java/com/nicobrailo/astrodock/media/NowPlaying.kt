package com.nicobrailo.astrodock.media

import android.content.ComponentName
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log

// What another app is playing (Jellyfin, a browser, anything with a media
// session), so the slideshow can show it and control it while it keeps showing
// pictures.
//
// Reading this needs notification access, which the user grants in the System
// tab (see MediaListenerService). Without it nothing is shown and the slideshow
// carries on as before.
class NowPlaying(private val context: Context, private val onChanged: () -> Unit) {
    private val sessionManager = context.getSystemService(MediaSessionManager::class.java)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val listenerComponent = ComponentName(context, MediaListenerService::class.java)

    private var controller: MediaController? = null
    private var running = false

    // Null when nothing is playing, or when we have no notification access
    val title: String? get() = metadataText(MediaMetadata.METADATA_KEY_TITLE)
    val artist: String?
        get() = metadataText(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadataText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
    val album: String? get() = metadataText(MediaMetadata.METADATA_KEY_ALBUM)
    // Apps can leave a session behind that still says it's playing: Jellyfin
    // does, and the stale session ignores even the system's own pause. So a
    // session that claims to play is only believed while sound is actually
    // coming out, unless it plays to another device (casting), where there's
    // nothing to hear locally.
    val isPlaying: Boolean
        get() {
            val state = controller?.playbackState?.state ?: return false
            if (state != PlaybackState.STATE_PLAYING) return false
            return isRemotePlayback || audioManager?.isMusicActive != false
        }

    // Whether there's anything worth showing: something playing now, or
    // something paused a short while ago that the user may want to resume. A
    // session that claims to be playing with nothing audible is never shown,
    // however recent it says it is.
    val hasActiveMedia: Boolean
        get() = when (controller?.playbackState?.state) {
            PlaybackState.STATE_PLAYING -> isPlaying
            PlaybackState.STATE_PAUSED, PlaybackState.STATE_BUFFERING -> ageMillis() < RECENT_MILLIS
            else -> false
        }

    private val isRemotePlayback: Boolean
        get() = controller?.playbackInfo?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE

    // How long ago this session last said anything about its playback
    private fun ageMillis(): Long {
        val updated = controller?.playbackState?.lastPositionUpdateTime ?: return Long.MAX_VALUE
        if (updated <= 0) return Long.MAX_VALUE
        return SystemClock.elapsedRealtime() - updated
    }

    val artwork: Bitmap?
        get() {
            val metadata = controller?.metadata ?: return null
            return metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { sessions ->
        use(sessions.orEmpty())
        onChanged()
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = onChanged()
        override fun onMetadataChanged(metadata: MediaMetadata?) = onChanged()
        override fun onSessionDestroyed() {
            use(activeSessions())
            onChanged()
        }
    }

    fun start() {
        if (running || sessionManager == null) return
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent)
        } catch (e: SecurityException) {
            // No notification access yet
            Log.i(TAG, "Not watching media sessions: ${e.message}")
            return
        }
        running = true
        use(activeSessions())
        onChanged()
    }

    fun stop() {
        if (!running) return
        running = false
        sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener)
        use(emptyList())
    }

    fun playPause() {
        val transport = controller?.transportControls ?: return
        Log.i(TAG, "${if (isPlaying) "Pausing" else "Playing"} ${controller?.packageName}")
        if (isPlaying) transport.pause() else transport.play()
    }

    fun next() {
        Log.i(TAG, "Next track on ${controller?.packageName}")
        controller?.transportControls?.skipToNext()
    }

    fun previous() {
        Log.i(TAG, "Previous track on ${controller?.packageName}")
        controller?.transportControls?.skipToPrevious()
    }

    // Brings the app that's playing to the front. The session's own activity
    // is preferred, since it is usually the player screen rather than the
    // app's start page. Returns the package opened, or null if nothing was.
    fun openApp(): String? {
        val controller = controller ?: return null
        val packageName = controller.packageName
        try {
            controller.sessionActivity?.let {
                Log.i(TAG, "Opening the player of $packageName")
                it.send()
                return packageName
            }
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "Player activity of $packageName is gone, opening the app instead", e)
        }
        val launch = context.packageManager.getLaunchIntentForPackage(packageName) ?: run {
            Log.w(TAG, "$packageName has nothing to launch")
            return null
        }
        Log.i(TAG, "Opening $packageName")
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return packageName
    }

    private fun activeSessions(): List<MediaController> = try {
        sessionManager?.getActiveSessions(listenerComponent).orEmpty()
    } catch (e: SecurityException) {
        Log.i(TAG, "Can't read media sessions: ${e.message}")
        emptyList()
    }

    // Follows whatever is playing; if nothing is, whatever played last, so
    // paused music can still be resumed from the slideshow. Among several
    // playing sessions the one that reported most recently wins.
    //
    // An app can leave a session behind that still claims to be playing
    // (Jellyfin does), and that session is then shown. The system treats it the
    // same way: its own media keys go to that session too.
    private fun use(sessions: List<MediaController>) {
        val best = sessions.filter { it.isBelievable }.maxWithOrNull(
            compareBy({ it.playbackState?.state == PlaybackState.STATE_PLAYING }, { it.updatedAt })
        )
        if (best?.sessionToken == controller?.sessionToken) return
        controller?.unregisterCallback(controllerCallback)
        controller = best
        best?.registerCallback(controllerCallback)
    }

    // When this session last said anything about its playback
    private val MediaController.updatedAt: Long
        get() = playbackState?.lastPositionUpdateTime ?: 0

    // A session that says it's playing while the speakers are silent is a
    // leftover, and would otherwise hide a real one behind it
    private val MediaController.isBelievable: Boolean
        get() {
            if (playbackState?.state != PlaybackState.STATE_PLAYING) return true
            val remote = playbackInfo?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE
            return remote || audioManager?.isMusicActive != false
        }

    private fun metadataText(key: String): String? =
        controller?.metadata?.getString(key)?.takeIf { it.isNotBlank() }

    private companion object {
        const val TAG = "NowPlaying"

        // How long after it was paused a session is still worth showing
        const val RECENT_MILLIS = 15 * 60 * 1000L
    }
}
