package com.nicobrailo.astrodock.alarm

import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.MediaStore
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import com.nicobrailo.astrodock.media.MediaListenerService
import kotlinx.coroutines.delay

// Starts another app playing, through its media session where that works,
// which is how the slideshow's media panel controls it too.
//
// Spotify, measured on a Portal+ (2026-10-03, Spotify 9.1), sets the rules:
// - Its session takes play, pause, skip and shuffle from any app, but ignores
//   play-from-URI and play-from-search, although it lists both among its
//   actions: it keeps those for the partners it knows.
// - Its MEDIA_PLAY_FROM_SEARCH activity only shows the results.
// - A spotify:playlist:<id>:play link (VIEW) opens the playlist and plays it.
//   It brings Spotify to the front, so AlarmRinger brings the home screen
//   back once the music has started.
// - With no session (its process gone), a Play key sent to its own media
//   button receiver brings it back, playing what it played last.
// - Following another device over Connect, its session is remote. Playing
//   there means somebody is listening, so the alarm leaves it alone
//   (Result.Elsewhere); paused there, Play from here moves it here
//   (pullHere). Only playing here counts as the alarm working (isPlaying).
// So a URI goes to the app's activity when the app has one for it, and to its
// session otherwise; a search goes to the session, for the apps that honour
// it. Either way shuffle goes through the session.
//
// Shuffle isn't in the framework's TransportControls, only in the compat
// library's, which sends it as a custom action that sessions made with
// MediaSessionCompat or Media3 (Spotify's, and most others) understand.
//
// Reading another app's session needs notification access (see
// MediaListenerService). Without it the app can be woken, and a link opened,
// but shuffle and the other requests are lost.
class MediaStarter(context: Context) {
    private val context = context.applicationContext
    private val sessionManager = this.context.getSystemService(MediaSessionManager::class.java)
    private val audioManager = this.context.getSystemService(AudioManager::class.java)
    private val listener = ComponentName(this.context, MediaListenerService::class.java)

    sealed interface Result {
        // Asked to play; whether it does is for the caller to check.
        // `openedApp` says the app was brought to the front to do it.
        data class Asked(val openedApp: Boolean = false) : Result
        data class Failed(val reason: String) : Result
        // Playing on another device (Spotify Connect, casting): somebody is
        // listening there, so the alarm leaves it alone
        object Elsewhere : Result
    }

    // Runs on the main thread, and takes up to SESSION_WAIT_MILLIS
    suspend fun start(packageName: String, request: PlayRequest, shuffle: Boolean): Result {
        // Somebody listening here is left alone; otherwise the song the alarm
        // resumes starts from the beginning rather than where it was paused
        val wasPlayingHere = isPlaying(packageName)
        session(packageName)?.takeIf { it.isRemote }?.let { remote ->
            if (remote.playbackState?.state == PlaybackState.STATE_PLAYING) {
                Log.i(TAG, "$packageName is playing on another device, leaving it")
                return Result.Elsewhere
            }
            // Nobody is listening there, so the alarm may take it
            Log.i(TAG, "$packageName is paused on another device, bringing it here")
            pullHere(packageName, remote)
        }
        val view = (request as? PlayRequest.Uri)?.let { viewIntent(packageName, it) }
        if (view != null) return startFromLink(packageName, view, shuffle)

        var controller = session(packageName)
        if (controller == null) {
            Log.i(TAG, "$packageName has no media session, waking it")
            val opened = when {
                sendPlayKey(packageName) -> false
                playFromSearchActivity(packageName, request) -> true
                else -> return Result.Failed("$packageName can't be started from the background")
            }
            if (!canReadSessions(context)) {
                Log.w(TAG, "No notification access: $packageName resumes what it played last")
                return Result.Asked(opened)
            }
            controller = waitForSession(packageName)
                ?: return Result.Failed("$packageName didn't start its player")
        }
        if (shuffle) setShuffle(controller)
        val transport = controller.transportControls
        when (request) {
            PlayRequest.Resume -> {
                Log.i(TAG, "Asking $packageName to play")
                if (!wasPlayingHere) transport.seekTo(0)
                if (controller.playbackState?.state != PlaybackState.STATE_PLAYING) transport.play()
            }
            is PlayRequest.Uri -> {
                Log.i(TAG, "Asking $packageName to play ${request.uri}")
                transport.playFromUri(Uri.parse(request.uri), Bundle())
            }
            is PlayRequest.Search -> {
                Log.i(TAG, "Asking $packageName to play \"${request.query}\"")
                transport.playFromSearch(request.query, Bundle())
            }
        }
        return Result.Asked()
    }

    private suspend fun startFromLink(packageName: String, view: Intent, shuffle: Boolean): Result {
        Log.i(TAG, "Opening ${view.data} in $packageName")
        try {
            // Starting an activity from the background needs the "display
            // over other apps" grant from Android 10
            context.startActivity(view)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Can't open ${view.data}", e)
            return Result.Failed("$packageName can't open ${view.data}")
        }
        if (!shuffle || !canReadSessions(context)) return Result.Asked(openedApp = true)
        val controller = waitForSession(packageName)
            ?: return Result.Failed("$packageName didn't start its player")
        // Spotify keeps shuffle per playlist, and sets the new one's while it
        // loads it, undoing a change made in the meantime (measured: shuffle
        // set a second after the session appeared was off again once it
        // played). So it waits for the music. A playlist starts at its first
        // track, which shuffle then leaves playing, so it moves on once to make
        // the first track heard a random one, as on a shuffled alarm it should.
        val giveUpAt = SystemClock.elapsedRealtime() + PLAYING_WAIT_MILLIS
        while (!isPlaying(packageName) && SystemClock.elapsedRealtime() < giveUpAt) delay(POLL_MILLIS)
        if (setShuffle(controller)) {
            Log.i(TAG, "Skipping the playlist's first track")
            controller.transportControls.skipToNext()
        }
        return Result.Asked(openedApp = true)
    }

    // Whether the app is playing here, as far as the speakers can tell: a
    // session can claim to be playing long after it stopped (see NowPlaying),
    // and nothing else is likely to be making sound on the music stream while
    // the alarm rings. Playing on another device doesn't count, unlike in the
    // media panel: nobody in this room hears it, so it wakes nobody.
    fun isPlaying(packageName: String): Boolean {
        val controller = session(packageName)
        if (controller == null) return !canReadSessions(context) && audioManager?.isMusicActive == true
        if (controller.playbackState?.state != PlaybackState.STATE_PLAYING) return false
        return !controller.isRemote && audioManager?.isMusicActive == true
    }

    private val MediaController.isRemote: Boolean
        get() = playbackInfo?.playbackType == MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE

    // Moves a session that is paused on another device to this one. Spotify
    // does that by itself when told to play from here: measured on a Portal+,
    // 2026-10-04, with its Connect session paused on another device for a
    // minute or two, Play through its session switched it to local playback
    // (playbackType remote to local) and it played on the Portal's speaker.
    // Waits until it says so, so that whatever start() asks next is asked of
    // the local player; if it doesn't, the check at the end says it isn't
    // playing here and the alarm sound takes over.
    private suspend fun pullHere(packageName: String, remote: MediaController) {
        remote.transportControls.play()
        val giveUpAt = SystemClock.elapsedRealtime() + PULL_WAIT_MILLIS
        while (SystemClock.elapsedRealtime() < giveUpAt) {
            delay(POLL_MILLIS)
            if (session(packageName)?.isRemote == false) {
                Log.i(TAG, "$packageName is playing here now")
                return
            }
        }
        Log.w(TAG, "$packageName stayed on the other device")
    }

    private suspend fun waitForSession(packageName: String): MediaController? {
        val giveUpAt = SystemClock.elapsedRealtime() + SESSION_WAIT_MILLIS
        while (SystemClock.elapsedRealtime() < giveUpAt) {
            session(packageName)?.let { return it }
            delay(POLL_MILLIS)
        }
        return null
    }

    // True if shuffle was off, and so has been turned on just now
    private fun setShuffle(controller: MediaController): Boolean = try {
        val compat = MediaControllerCompat(context, MediaSessionCompat.Token.fromToken(controller.sessionToken))
        if (compat.shuffleMode == PlaybackStateCompat.SHUFFLE_MODE_ALL) {
            false
        } else {
            Log.i(TAG, "Turning shuffle on in ${controller.packageName}")
            compat.transportControls.setShuffleMode(PlaybackStateCompat.SHUFFLE_MODE_ALL)
            true
        }
    } catch (e: RuntimeException) {
        Log.w(TAG, "Can't turn shuffle on in ${controller.packageName}", e)
        false
    }

    // The app's own activity for the link, if it has one
    private fun viewIntent(packageName: String, request: PlayRequest.Uri): Intent? {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(request.viewUri))
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return intent.takeIf {
            context.packageManager.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY) != null
        }
    }

    // A KeyEvent pair, as a button press is, sent to the app's own receiver
    // rather than through the system, which would send it to whichever app
    // played last
    private fun sendPlayKey(packageName: String): Boolean {
        val receiver = context.packageManager
            .queryBroadcastReceivers(Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(packageName), 0)
            .firstOrNull()?.activityInfo ?: return false
        val component = ComponentName(receiver.packageName, receiver.name)
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            context.sendBroadcast(
                Intent(Intent.ACTION_MEDIA_BUTTON)
                    .setComponent(component)
                    .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(action, KeyEvent.KEYCODE_MEDIA_PLAY))
            )
        }
        return true
    }

    // For an app with no media button receiver: what voice assistants use. It
    // opens the app, which an alarm can live with.
    private fun playFromSearchActivity(packageName: String, request: PlayRequest): Boolean {
        val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(SearchManager.QUERY, (request as? PlayRequest.Search)?.query.orEmpty())
        if (context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) == null) return false
        return try {
            context.startActivity(intent)
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "Can't open $packageName to play", e)
            false
        }
    }

    private fun session(packageName: String): MediaController? = try {
        sessionManager?.getActiveSessions(listener)?.firstOrNull { it.packageName == packageName }
    } catch (e: SecurityException) {
        null
    }

    companion object {
        private const val TAG = "MediaStarter"
        private const val POLL_MILLIS = 500L
        // Spotify took about 2s from a dead process; this allows for a slow
        // start after a night of not running
        private const val SESSION_WAIT_MILLIS = 20_000L
        // Spotify moved to local playback within 3s
        private const val PULL_WAIT_MILLIS = 10_000L
        // How long a playlist opened on a link has to start before shuffle is
        // set anyway
        private const val PLAYING_WAIT_MILLIS = 15_000L

        // The apps an alarm can start: anything with a media button receiver
        // (what headphones talk to) or that takes a play-from-search request.
        // Package names, with their labels, sorted by label.
        fun candidates(context: Context): List<Pair<String, String>> {
            val pm = context.packageManager
            val packages = pm.queryBroadcastReceivers(Intent(Intent.ACTION_MEDIA_BUTTON), 0)
                .map { it.activityInfo.packageName } +
                pm.queryIntentActivities(Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH), 0)
                    .map { it.activityInfo.packageName }
            return packages.toSet()
                .filter { it != context.packageName && pm.getLaunchIntentForPackage(it) != null }
                .map { it to label(context, it) }
                .sortedBy { it.second.lowercase() }
        }

        // Whether notification access was granted, without which the sessions
        // can't be read (see MediaListenerService)
        fun canReadSessions(context: Context): Boolean = try {
            context.getSystemService(MediaSessionManager::class.java)
                ?.getActiveSessions(ComponentName(context, MediaListenerService::class.java)) != null
        } catch (e: SecurityException) {
            false
        }

        fun label(context: Context, packageName: String): String = try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }
    }
}
