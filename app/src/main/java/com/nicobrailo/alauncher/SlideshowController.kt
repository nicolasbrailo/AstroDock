package com.nicobrailo.alauncher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import coil3.SingletonImageLoader
import coil3.asDrawable
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.size.ViewSizeResolver
import com.nicobrailo.alauncher.immich.AlbumPicture
import com.nicobrailo.alauncher.immich.ImmichClient
import com.nicobrailo.alauncher.immich.ImmichException
import com.nicobrailo.alauncher.immich.ImmichPictureInfo
import com.nicobrailo.alauncher.immich.ImmichPictureSize
import com.nicobrailo.alauncher.immich.RandomAlbumPicker
import com.nicobrailo.alauncher.media.NowPlaying
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

// Drives the slideshow in R.layout.slideshow: picks pictures, loads them, moves
// between them and shows the overlays. Used by SlideshowActivity (the home
// screen) and by SlideshowDreamService (the screensaver).
//
// - Every Settings.slideSeconds it slides to the next picture.
// - When interactive, the picture follows the finger while swiping. Swiping
//   left (finger moving right to left) moves forward; swiping right moves back
//   through the last HISTORY_SIZE pictures, stopping at the oldest (see
//   PictureHistory). Swiping restarts the timer, which then keeps moving
//   forward from wherever the user left off.
// - The bottom left corner shows the time and a line about the picture (year
//   and place). Tapping that line expands it with more details. Tapping the
//   clock shows what the Portal is doing (see PortalState).
// - The bottom right corner shows what another app is playing, with controls,
//   so the device can play music while the pictures keep going (see
//   NowPlaying). It needs notification access, and stays hidden without it.
//
// The picture on screen (cur) and its neighbours (prev, next) each have their
// own view, kept one screen width to either side, so a neighbour can slide in
// with the finger. The next picture is picked and loaded ahead of time
// (`upcoming`), so moving forward doesn't wait for the network.
//
// Everything here runs on the main thread. The host calls start() when the
// slideshow becomes visible and stop() when it isn't, and cancels `scope` when
// it's done.
class SlideshowController(
    private val context: Context,
    private val root: View,
    private val scope: CoroutineScope,
    // The screensaver isn't interactive: any touch wakes the device instead
    private val interactive: Boolean,
    // Called on a tap, when interactive
    private val onTap: () -> Unit,
) {
    // One of the three picture views, the picture it shows and its metadata
    private class Slot(val view: ImageView) {
        var picture: AlbumPicture? = null
        var loaded = false
        var job: Job? = null
        var info: ImmichPictureInfo? = null // Null until fetched, or if that failed
        var infoJob: Job? = null
        val id: String? get() = picture?.id
        val loading: Boolean get() = job?.isActive == true
        val ready: Boolean get() = picture != null && loaded
    }

    private val status: TextView = root.findViewById(R.id.status)
    private val pictureInfo: TextView = root.findViewById(R.id.picture_info)
    private val clock: View = root.findViewById(R.id.clock)
    private val debug: TextView = root.findViewById(R.id.debug)
    private var infoExpanded = false

    private var prev = Slot(root.findViewById(R.id.picture_a))
    private var cur = Slot(root.findViewById(R.id.picture_b))
    private var next = Slot(root.findViewById(R.id.picture_c))

    // Rebuilt in start() whenever the settings change
    private var settings: Settings? = null
    private var client: ImmichClient? = null
    private var picker: RandomAlbumPicker? = null

    private val history = PictureHistory<AlbumPicture>(HISTORY_SIZE)
    // Picked to follow the newest picture in history, but not shown yet
    private var upcoming: AlbumPicture? = null
    private var pickJob: Job? = null
    // True while pickJob runs. Not pickJob.isActive: the job must be able to
    // start the next pick when it finishes, while it's still active.
    private var picking = false
    private var timerJob: Job? = null

    // What another app is playing. Only watched while the slideshow is visible.
    private val nowPlayingPanel: View = root.findViewById(R.id.now_playing)
    private val nowPlayingArt: ImageView = root.findViewById(R.id.now_playing_art)
    private val nowPlayingTitle: TextView = root.findViewById(R.id.now_playing_title)
    private val nowPlayingArtist: TextView = root.findViewById(R.id.now_playing_artist)
    private val nowPlayingPlay: ImageButton = root.findViewById(R.id.now_playing_play)
    private val nowPlaying = NowPlaying(context) { updateNowPlaying() }
    private var nowPlayingJob: Job? = null

    // Shows what the Portal is doing, for debugging. Only while it's on screen.
    private val portalState = PortalState(context)
    private var portalStateJob: Job? = null

    // Swipe state
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var velocityTracker: VelocityTracker? = null
    private var pageAnimator: ValueAnimator? = null

    init {
        // The neighbours' positions depend on the screen width
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!dragging && pageAnimator == null) setOffset(0f)
        }
        if (interactive) {
            nowPlayingPlay.setOnClickListener { nowPlaying.playPause() }
            root.findViewById<View>(R.id.now_playing_next).setOnClickListener { nowPlaying.next() }
            root.findViewById<View>(R.id.now_playing_previous).setOnClickListener { nowPlaying.previous() }
            pictureInfo.setOnClickListener {
                infoExpanded = !infoExpanded
                updatePictureInfo()
            }
            clock.setOnClickListener { toggleDebug() }
            setUpTouch()
        }
    }

    // Called when the slideshow becomes visible
    fun start() {
        val newSettings = Settings.load(context)
        if (newSettings != settings) {
            applySettings(newSettings)
        } else {
            // Pick up albums added on the server since the list was fetched
            picker?.refresh()
        }

        nowPlaying.start()
        // Sound stopping isn't reported, so the panel is re-checked now and then
        nowPlayingJob?.cancel()
        nowPlayingJob = scope.launch {
            while (true) {
                updateNowPlaying()
                delay(NOW_PLAYING_POLL_MILLIS)
            }
        }

        if (picker == null) return
        if (history.current == null) ensurePick()
        restartTimer()
        if (debug.visibility == View.VISIBLE) startDebugUpdates()
    }

    // Called when it isn't visible any more. The pictures are kept, so coming
    // back shows the same one.
    fun stop() {
        nowPlayingJob?.cancel()
        nowPlaying.stop()
        timerJob?.cancel()
        portalStateJob?.cancel()
        portalState.stop()
    }

    private fun applySettings(s: Settings) {
        settings = s
        pickJob?.cancel()
        picking = false
        timerJob?.cancel()
        pageAnimator?.cancel()
        history.clear()
        upcoming = null
        for (slot in listOf(prev, cur, next)) bind(slot, null)
        setOffset(0f)
        updatePictureInfo()

        if (!s.isConfigured) {
            client = null
            picker = null
            showStatus(context.getString(R.string.slideshow_not_configured))
            return
        }
        val c = ImmichClient(s.serverUrl, s.apiKey)
        client = c
        picker = RandomAlbumPicker(c, s.maxPicturesPerAlbum, s.percentOfAlbum)
        showStatus(context.getString(R.string.slideshow_loading))
    }

    // ---- Pictures ----------------------------------------------------------

    // Picks a new picture in the background, unless a pick is already running.
    // The first one goes straight on screen; later ones become `upcoming`.
    private fun ensurePick() {
        val p = picker ?: return
        if (picking) return
        picking = true
        pickJob = scope.launch {
            val picture = try {
                p.next()
            } catch (e: ImmichException) {
                Log.w(TAG, "Can't pick a picture", e)
                showStatus(context.getString(R.string.slideshow_error, e.message))
                return@launch
            } finally {
                if (p === picker) picking = false
            }
            if (p !== picker) return@launch // The settings changed meanwhile
            if (history.current == null) {
                history.add(picture)
                bind(cur, picture)
                updatePictureInfo()
            } else {
                upcoming = picture
            }
            refreshNeighbours()
        }
    }

    // Loads the neighbours of the picture on screen, and picks the next picture
    // ahead of time if the newest one is on screen
    private fun refreshNeighbours() {
        bind(prev, history.peekBack())
        bind(next, history.peekForward() ?: upcoming)
        if (history.current != null && history.atNewest && upcoming == null) ensurePick()
    }

    // Makes slot show picture (nothing if null), loading it and its metadata in
    // the background. Does nothing if it already shows (or is loading) that
    // picture.
    private fun bind(slot: Slot, picture: AlbumPicture?) {
        val id = picture?.id
        if (slot.id == id && (slot.loaded || slot.loading)) return
        slot.job?.cancel()
        slot.infoJob?.cancel()
        slot.picture = picture
        slot.loaded = false
        slot.info = null
        slot.view.setImageDrawable(null)
        val c = client ?: return
        if (id == null) return

        slot.infoJob = scope.launch {
            val info = try {
                c.getPictureMetadata(id)
            } catch (e: ImmichException) {
                Log.w(TAG, "Can't get metadata of picture $id", e)
                return@launch
            }
            if (slot.id != id) return@launch
            slot.info = info
            if (slot === cur) updatePictureInfo()
        }

        val request = ImageRequest.Builder(context)
            .data(c.pictureUrl(id, ImmichPictureSize.PREVIEW))
            .httpHeaders(NetworkHeaders.Builder().set(ImmichClient.API_KEY_HEADER, c.apiKey).build())
            .size(ViewSizeResolver(slot.view))
            .build()
        slot.job = scope.launch {
            val result = SingletonImageLoader.get(context).execute(request)
            if (slot.id != id) return@launch
            when (result) {
                is SuccessResult -> {
                    slot.view.setImageDrawable(result.image.asDrawable(context.resources))
                    slot.loaded = true
                    if (slot === cur) status.visibility = View.GONE
                }
                is ErrorResult -> {
                    // onTimer() retries
                    Log.w(TAG, "Can't load picture $id", result.throwable)
                    if (slot === cur) {
                        showStatus(context.getString(R.string.slideshow_error, result.throwable.message))
                    }
                }
            }
        }
    }

    // Puts the next picture on screen, once it has slid in. Requires next.ready.
    private fun pageForward() {
        val picture = next.picture ?: return
        if (history.atNewest) {
            history.add(picture)
            upcoming = null
        } else {
            history.forward()
        }
        val old = prev
        prev = cur
        cur = next
        next = old
        afterPaging()
    }

    // Puts the previous picture on screen, once it has slid in. Requires prev.ready.
    private fun pageBack() {
        history.back() ?: return
        val old = next
        next = cur
        cur = prev
        prev = old
        afterPaging()
    }

    private fun afterPaging() {
        setOffset(0f)
        if (cur.loaded) status.visibility = View.GONE
        updatePictureInfo()
        refreshNeighbours()
    }

    // Shows the metadata of the picture on screen, hiding the text while it
    // isn't known
    private fun updatePictureInfo() {
        val picture = cur.picture
        val info = cur.info
        val text = when {
            picture == null || info == null -> ""
            infoExpanded -> pictureDetails(picture, info)
            else -> pictureSummary(info)
        }
        pictureInfo.text = text
        pictureInfo.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        pictureInfo.setBackgroundResource(if (infoExpanded) R.drawable.status_background else 0)
    }

    // The panel is only there while something is playing (or paused, so it can
    // be resumed). The screensaver shows it but has no working buttons: a touch
    // ends the screensaver instead.
    private fun updateNowPlaying() {
        val title = nowPlaying.title
        if (!nowPlaying.hasActiveMedia || title == null) {
            nowPlayingPanel.visibility = View.GONE
            return
        }
        nowPlayingPanel.visibility = View.VISIBLE
        nowPlayingTitle.text = title
        val artist = listOfNotNull(nowPlaying.artist, nowPlaying.album).joinToString(" - ")
        nowPlayingArtist.text = artist
        nowPlayingArtist.visibility = if (artist.isEmpty()) View.GONE else View.VISIBLE
        nowPlayingArt.setImageBitmap(nowPlaying.artwork)
        nowPlayingArt.visibility = if (nowPlaying.artwork == null) View.GONE else View.VISIBLE
        nowPlayingPlay.setImageResource(if (nowPlaying.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
        nowPlayingPlay.visibility = if (interactive) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.now_playing_next).visibility = if (interactive) View.VISIBLE else View.GONE
        root.findViewById<View>(R.id.now_playing_previous).visibility = if (interactive) View.VISIBLE else View.GONE
    }

    private fun showStatus(message: String) {
        status.text = message
        status.visibility = View.VISIBLE
    }

    // ---- Portal state overlay ----------------------------------------------

    private fun toggleDebug() {
        if (debug.visibility == View.VISIBLE) {
            debug.visibility = View.GONE
            portalStateJob?.cancel()
            portalState.stop()
        } else {
            debug.visibility = View.VISIBLE
            startDebugUpdates()
        }
    }

    private fun startDebugUpdates() {
        portalStateJob?.cancel()
        portalState.start()
        portalStateJob = scope.launch {
            while (true) {
                debug.text = portalState.describe(slideshowLine())
                delay(1000)
            }
        }
    }

    private fun slideshowLine(): String {
        val album = cur.picture?.album?.name ?: "none"
        val seconds = settings?.slideSeconds ?: 0
        return "Slideshow: ${if (interactive) "home" else "screensaver"}, album $album, ${seconds}s per picture"
    }

    // ---- Timer -------------------------------------------------------------

    // Moves forward every slideSeconds while the slideshow is visible. Restarted
    // after every swipe, so the user gets a full slide of time.
    private fun restartTimer() {
        timerJob?.cancel()
        val seconds = settings?.slideSeconds ?: return
        if (picker == null) return
        timerJob = scope.launch {
            while (true) {
                delay(seconds * 1000L)
                onTimer()
            }
        }
    }

    private fun onTimer() {
        if (dragging || pageAnimator != null) return
        if (next.ready) {
            animateOffset(-root.width.toFloat()) { pageForward() }
            return
        }

        // The next picture isn't there: picking or loading it failed (or is
        // still running). Retry.
        if (next.loading || picking) return
        if (history.current == null) {
            ensurePick()
            return
        }
        if (history.atNewest) upcoming = null
        bind(next, null)
        refreshNeighbours()
    }

    // ---- Swiping and tapping -----------------------------------------------

    // Places the pictures: the one on screen shifted by offset pixels, and its
    // neighbours a screen width to either side of it
    private fun setOffset(offset: Float) {
        val width = root.width.toFloat()
        prev.view.translationX = offset - width
        cur.view.translationX = offset
        next.view.translationX = offset + width
    }

    // Slides the pictures from where they are to offset, then calls onDone
    // (unless cancelled)
    private fun animateOffset(offset: Float, onDone: () -> Unit) {
        pageAnimator?.cancel()
        val from = cur.view.translationX
        val width = root.width.toFloat().coerceAtLeast(1f)
        pageAnimator = ValueAnimator.ofFloat(from, offset).apply {
            // Shorter when there is less left to move
            duration = (PAGE_ANIMATION_MS * (abs(offset - from) / width)).toLong().coerceIn(80, PAGE_ANIMATION_MS)
            interpolator = DecelerateInterpolator()
            addUpdateListener { setOffset(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (pageAnimator === animation) pageAnimator = null
                    if (!cancelled) onDone()
                }
            })
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility") // Swipes and taps on the whole screen
    private fun setUpTouch() {
        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onTap()
                return true
            }
        })
        root.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            onSwipeTouch(event)
            true
        }
    }

    private fun onSwipeTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                dragging = false
                velocityTracker?.recycle()
                velocityTracker = VelocityTracker.obtain().also { it.addMovement(e) }
            }

            MotionEvent.ACTION_MOVE -> {
                velocityTracker?.addMovement(e)
                var dx = e.x - downX
                if (!dragging) {
                    val slop = ViewConfiguration.get(context).scaledTouchSlop
                    val horizontal = abs(dx) > slop && abs(dx) > abs(e.y - downY)
                    if (!horizontal || picker == null || pageAnimator != null) return
                    dragging = true
                    // Start moving from here, so the picture doesn't jump by the slop
                    downX += slop * sign(dx)
                    dx = e.x - downX
                }
                // Resist when there is nothing to move to
                val target = if (dx < 0) next else prev
                setOffset(if (target.ready) dx else dx / OVERSCROLL_RESISTANCE)
            }

            MotionEvent.ACTION_UP -> {
                if (!dragging) return
                dragging = false
                val tracker = velocityTracker ?: return
                tracker.addMovement(e)
                tracker.computeCurrentVelocity(1000)
                finishSwipe(e.x - downX, tracker.xVelocity)
            }

            MotionEvent.ACTION_CANCEL -> {
                if (!dragging) return
                dragging = false
                animateOffset(0f) {}
            }
        }
    }

    // Decides where a swipe that moved dx pixels and ended at velocity vx
    // (pixels per second) goes: to a neighbour, or back to the picture on screen
    private fun finishSwipe(dx: Float, vx: Float) {
        val width = root.width.toFloat()
        val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * FLING_VELOCITY_FACTOR
        val far = abs(dx) > width * PAGE_DISTANCE_FRACTION
        val flung = abs(vx) > minFling && sign(vx) == sign(dx)
        val forward = dx < 0
        val target = if (forward) next else prev

        if ((far || flung) && target.ready) {
            Log.d(TAG, "Swipe ${if (forward) "forward" else "back"}")
            if (forward) {
                animateOffset(-width) { pageForward() }
            } else {
                animateOffset(width) { pageBack() }
            }
        } else {
            animateOffset(0f) {}
        }
        restartTimer()
    }

    private companion object {
        const val TAG = "SlideshowController"

        // How many pictures the user can swipe back through
        const val HISTORY_SIZE = 20

        // A swipe moves to the neighbour if it went this fraction of the screen
        // width, or was a fling
        const val PAGE_DISTANCE_FRACTION = 0.25f
        const val FLING_VELOCITY_FACTOR = 4
        const val PAGE_ANIMATION_MS = 300L
        const val NOW_PLAYING_POLL_MILLIS = 5000L
        // How much harder it is to drag when there is no picture to move to
        const val OVERSCROLL_RESISTANCE = 3f
    }
}
