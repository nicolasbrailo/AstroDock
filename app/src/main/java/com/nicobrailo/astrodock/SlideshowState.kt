package com.nicobrailo.astrodock

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.nicobrailo.astrodock.immich.AlbumPicture
import com.nicobrailo.astrodock.immich.ImmichClient
import com.nicobrailo.astrodock.immich.ImmichException
import com.nicobrailo.astrodock.immich.ImmichPictureInfo
import com.nicobrailo.astrodock.immich.RandomAlbumPicker
import kotlinx.coroutines.sync.Mutex

// Which picture the slideshow is showing, shared by the home screen and the
// screensaver.
//
// Both run the same slideshow (SlideshowController) but never at the same time:
// the screensaver covers the home screen, and touching it brings the home
// screen back. They used to keep their own picker and history, so every switch
// jumped to an unrelated picture and the history was thrown away. Keeping that
// here, in one object for the whole process, means the slideshow simply carries
// on across the switch, in both directions.
//
// Only the picture bookkeeping lives here. The views, the timer and the
// gestures belong to each SlideshowController.
//
// Used from the main thread, except that picking waits on the network.
class SlideshowState private constructor() {
    // Read by the views for the settings that don't decide which picture is
    // next, such as the weather panel's
    var settings: Settings? = null
        private set
    private var picker: RandomAlbumPicker? = null
    private val history = PictureHistory<AlbumPicture>(HISTORY_SIZE)
    // Picked to follow the newest picture in history, but not shown yet
    private var upcoming: AlbumPicture? = null
    // Held while picking, so the home screen and the screensaver don't both ask
    private val picking = Mutex()
    // Shared so the picture's details stay open (or closed) across the switch
    var infoExpanded = false

    var client: ImmichClient? = null
        private set

    val isConfigured: Boolean get() = settings?.isConfigured == true
    val slideSeconds: Int get() = settings?.slideSeconds ?: Settings.DEFAULT_SLIDE_SECONDS

    // The settings as last loaded, for the parts of the app that need more than
    // the pictures (the night rule)
    val currentSettings: Settings? get() = settings

    // When the user last touched the slideshow, so the night rule can hold off
    // for a while after someone has used the device
    var lastTouchAt: Long = 0
        private set

    fun noteTouch() {
        lastTouchAt = SystemClock.elapsedRealtime()
    }

    // The picture on screen and its neighbours
    val current: AlbumPicture? get() = history.current
    val previous: AlbumPicture? get() = history.peekBack()
    val next: AlbumPicture? get() = history.peekForward() ?: upcoming

    // Metadata of the pictures shown lately, so the switch doesn't re-fetch what
    // the other one already knows
    private val metadataCache = object : LinkedHashMap<String, ImmichPictureInfo>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImmichPictureInfo>) =
            size > METADATA_CACHE_SIZE
    }

    // Picks up settings changed since the last look. Returns true if the
    // pictures were thrown away, because the server or the sampling changed.
    fun reloadSettings(context: Context): Boolean {
        val newSettings = Settings.load(context)
        val old = settings
        settings = newSettings
        ScreenControl.applyScreenOffDelay(context, newSettings)

        // Only the settings that decide which pictures are shown throw the
        // current ones away; changing the clock or the night hours shouldn't
        val samePictures = old != null &&
            old.serverUrl == newSettings.serverUrl &&
            old.apiKey == newSettings.apiKey &&
            old.maxPicturesPerAlbum == newSettings.maxPicturesPerAlbum &&
            old.percentOfAlbum == newSettings.percentOfAlbum &&
            old.albumFilter == newSettings.albumFilter
        if (samePictures) {
            // Pick up albums added on the server since the list was fetched
            picker?.refresh()
            return false
        }

        history.clear()
        upcoming = null
        metadataCache.clear()
        if (!newSettings.isConfigured) {
            client = null
            picker = null
            return true
        }
        val c = ImmichClient(newSettings.serverUrl, newSettings.apiKey)
        client = c
        picker = RandomAlbumPicker(
            c, newSettings.maxPicturesPerAlbum, newSettings.percentOfAlbum, newSettings.albumFilter
        )
        return true
    }

    // Makes sure there's a picture on screen and one ready after it. Returns
    // what happened, so the caller can show the pictures or the error.
    suspend fun pickAhead(): PickResult {
        val p = picker ?: return PickResult.Nothing
        // Someone else is already picking; they'll report the result
        if (!picking.tryLock()) return PickResult.Nothing
        try {
            val needed = current == null || (history.atNewest && upcoming == null)
            if (!needed) return PickResult.Nothing

            val picture = p.next()
            // The settings changed while we waited on the network
            if (p !== picker) return PickResult.Nothing
            if (current == null) history.add(picture) else upcoming = picture
            return PickResult.Picked
        } catch (e: ImmichException) {
            Log.w(TAG, "Can't pick a picture", e)
            return PickResult.Failed(e.message.orEmpty())
        } finally {
            picking.unlock()
        }
    }

    // Moves to the next picture, which must already be known (see `next`)
    fun goForward(): Boolean {
        if (!history.atNewest) return history.forward() != null
        val picture = upcoming ?: return false
        history.add(picture)
        upcoming = null
        return true
    }

    fun goBack(): Boolean = history.back() != null

    // Forgets the picture picked for later, after it turned out not to load, so
    // the next pick chooses another one
    fun dropUpcoming() {
        upcoming = null
    }

    // The picture's metadata, fetched once and then remembered. Null if it
    // can't be fetched; the overlay then shows nothing for that picture.
    suspend fun metadata(id: String): ImmichPictureInfo? {
        metadataCache[id]?.let { return it }
        val c = client ?: return null
        return try {
            c.getPictureMetadata(id).also { metadataCache[id] = it }
        } catch (e: ImmichException) {
            Log.w(TAG, "Can't get metadata of picture $id", e)
            null
        }
    }

    sealed interface PickResult {
        // Nothing needed picking, or someone else is picking
        data object Nothing : PickResult
        data object Picked : PickResult
        data class Failed(val message: String) : PickResult
    }

    companion object {
        private const val TAG = "SlideshowState"

        // How many pictures the user can swipe back through
        private const val HISTORY_SIZE = 20
        private const val METADATA_CACHE_SIZE = 60

        // The home screen and the screensaver are in the same process, so one
        // object is all it takes to share the slideshow between them
        val shared: SlideshowState by lazy { SlideshowState() }
    }
}
