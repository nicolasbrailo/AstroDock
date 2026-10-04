package com.nicobrailo.astrodock.alarm

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.util.Log
import com.nicobrailo.astrodock.media.MediaListenerService
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

// The playlists (and albums, artists, shows) that apps were seen playing, so
// the alarm editor can offer them by name instead of asking for a link, which
// is no way to pick music on a Portal.
//
// Spotify names what it plays in its session's metadata, next to the track:
// com.spotify.music.extra.CONTEXT_URI (spotify:playlist:<id>) and
// CONTEXT_TITLE ("Deep Focus"), measured on a Portal+ on 2026-10-03. Its media
// browser, which could have listed the daily mixes and the library, refuses
// any app it doesn't know. So the list only knows what has been played here,
// which is noted by NowPlaying whenever the media panel looks at a session,
// and by the alarm editor as it opens.
//
// Kept as JSON in their own preferences file, most recent first:
// [{"app": "com.spotify.music", "uri": "spotify:playlist:...", "title": "Deep Focus"}]
class RecentPlaylists(context: Context) {
    private val context = context.applicationContext
    private val prefs = this.context.getSharedPreferences("recent_playlists", Context.MODE_PRIVATE)

    data class Playlist(val app: String, val uri: String, val title: String)

    fun load(): List<Playlist> {
        val text = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(text)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                Playlist(obj.getString("app"), obj.getString("uri"), obj.getString("title"))
            }
        } catch (e: JSONException) {
            Log.w(TAG, "Ignoring unreadable playlists", e)
            emptyList()
        }
    }

    fun forApp(app: String): List<Playlist> = load().filter { it.app == app }

    // Notes what this session is playing from, if it says. Called on every
    // track change, so it only writes when the playlist is a new one.
    fun note(controller: MediaController) {
        val playlist = of(controller) ?: return
        if (playlist == lastNoted) return
        lastNoted = playlist
        val list = merge(load(), playlist)
        val array = JSONArray()
        for (p in list) array.put(JSONObject().put("app", p.app).put("uri", p.uri).put("title", p.title))
        prefs.edit().putString(KEY, array.toString()).apply()
        Log.i(TAG, "Noted ${playlist.title} (${playlist.uri}) from ${playlist.app}")
    }

    // What `app` is playing from now, noting it on the way, or null if it
    // isn't, or doesn't say, or there's no notification access to ask
    fun nowPlaying(app: String): Playlist? {
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return null
        val controller = try {
            manager.getActiveSessions(ComponentName(context, MediaListenerService::class.java))
                .firstOrNull { it.packageName == app }
        } catch (e: SecurityException) {
            null
        } ?: return null
        note(controller)
        return of(controller)
    }

    companion object {
        private const val TAG = "RecentPlaylists"
        private const val KEY = "playlists"
        const val MAX = 30
        private const val SPOTIFY_CONTEXT_URI = "com.spotify.music.extra.CONTEXT_URI"
        private const val SPOTIFY_CONTEXT_TITLE = "com.spotify.music.extra.CONTEXT_TITLE"

        // One per process: the home screen and the screensaver each have a
        // NowPlaying, watching the same session
        @Volatile
        private var lastNoted: Playlist? = null

        private fun of(controller: MediaController): Playlist? {
            val metadata = controller.metadata ?: return null
            return playlist(
                controller.packageName,
                metadata.getString(SPOTIFY_CONTEXT_URI),
                metadata.getString(SPOTIFY_CONTEXT_TITLE),
            )
        }

        // Only what an alarm can play again: a link Spotify starts playing
        // when it's opened (PlayRequest.viewUri), with a name to show. A
        // queue of single tracks, a radio or the liked songs aren't, or not
        // that anyone has measured.
        fun playlist(app: String, uri: String?, title: String?): Playlist? {
            if (uri == null || title.isNullOrBlank()) return null
            if (!PlayRequest.playsWhenOpened(uri)) return null
            return Playlist(app, uri, title.trim())
        }

        // Puts `playlist` first, dropping an older entry for the same link
        // (whose name may have changed since) and the oldest beyond MAX
        fun merge(list: List<Playlist>, playlist: Playlist): List<Playlist> =
            (listOf(playlist) + list.filter { it.app != playlist.app || it.uri != playlist.uri }).take(MAX)
    }
}
