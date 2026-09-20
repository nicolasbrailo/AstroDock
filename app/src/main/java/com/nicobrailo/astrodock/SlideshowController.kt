package com.nicobrailo.astrodock

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
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
import com.nicobrailo.astrodock.immich.AlbumFilter
import com.nicobrailo.astrodock.immich.AlbumPicture
import com.nicobrailo.astrodock.immich.ImmichClient
import com.nicobrailo.astrodock.immich.ImmichPictureInfo
import com.nicobrailo.astrodock.immich.ImmichPictureSize
import com.nicobrailo.astrodock.media.NowPlaying
import androidx.preference.PreferenceManager
import com.nicobrailo.astrodock.mqtt.Command
import com.nicobrailo.astrodock.mqtt.StateReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
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
// - If the user asked for it, the screen is switched off during the night
//   hours (see ScreenControl), a little after they last touched it.
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

    // What is being shown, shared with the screensaver (or the home screen)
    private val state = SlideshowState.shared

    private val status: TextView = root.findViewById(R.id.status)
    private val pictureInfo: TextView = root.findViewById(R.id.picture_info)
    private val clock: View = root.findViewById(R.id.clock)
    private val debug: TextView = root.findViewById(R.id.debug)

    private var prev = Slot(root.findViewById(R.id.picture_a))
    private var cur = Slot(root.findViewById(R.id.picture_b))
    private var next = Slot(root.findViewById(R.id.picture_c))

    private var pickJob: Job? = null
    private var timerJob: Job? = null
    private var nightJob: Job? = null

    // What another app is playing. Only watched while the slideshow is visible.
    private val nowPlayingPanel: View = root.findViewById(R.id.now_playing)
    private val nowPlayingArt: ImageView = root.findViewById(R.id.now_playing_art)
    private val nowPlayingTitle: TextView = root.findViewById(R.id.now_playing_title)
    private val nowPlayingArtist: TextView = root.findViewById(R.id.now_playing_artist)
    private val nowPlayingPlay: ImageButton = root.findViewById(R.id.now_playing_play)
    private val nowPlaying = NowPlaying(context) { updateNowPlaying() }
    private var nowPlayingJob: Job? = null

    // Publishes what this device is doing to an MQTT broker, when that's set up
    private val reporter = StateReporter.get(context)
    private val reporterSource = if (interactive) "home" else "screensaver"
    private val announcement: TextView = root.findViewById(R.id.announcement)
    private var announcementJob: Job? = null
    private val onCommand: (Command) -> Unit = { carryOut(it) }
    // What was last reported, so the same picture isn't republished every time
    // the overlay is redrawn
    private var reportedPhoto: String? = null

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
                state.infoExpanded = !state.infoExpanded
                updatePictureInfo()
            }
            clock.setOnClickListener { toggleDebug() }
            setUpTouch()
        }
    }

    // Called when the slideshow becomes visible
    fun start() {
        // The settings may have changed while this wasn't on screen, and the
        // other slideshow may have moved on to another picture
        if (state.reloadSettings(context)) {
            pageAnimator?.cancel()
            for (slot in listOf(prev, cur, next)) bind(slot, null)
            setOffset(0f)
        }

        // Settings may have been edited in the MQTT tab meanwhile
        reporter.applySettings()
        reporter.onSlideshowVisible(reporterSource, true)
        // Commands go to whichever slideshow is on screen
        reporter.setCommandListener(onCommand)

        nowPlaying.start()
        // Sound stopping isn't reported, so the panel is re-checked now and then
        nowPlayingJob?.cancel()
        nowPlayingJob = scope.launch {
            while (true) {
                updateNowPlaying()
                delay(NOW_PLAYING_POLL_MILLIS)
            }
        }

        startNightWatch()

        if (!state.isConfigured) {
            showStatus(context.getString(R.string.slideshow_not_configured))
            bindFromState()
            return
        }
        if (state.current == null) showStatus(context.getString(R.string.slideshow_loading))
        syncPictures()
        restartTimer()
        if (debug.visibility == View.VISIBLE) startDebugUpdates()
    }

    // Called when it isn't visible any more. The pictures are kept, so coming
    // back shows the same one.
    fun stop() {
        reporter.onSlideshowVisible(reporterSource, false)
        reporter.clearCommandListener(onCommand)
        nightJob?.cancel()
        nowPlayingJob?.cancel()
        nowPlaying.stop()
        timerJob?.cancel()
        portalStateJob?.cancel()
        portalState.stop()
    }

    // ---- Pictures ----------------------------------------------------------

    // Shows the pictures the shared state holds, and picks whatever is still
    // missing (the first picture, and the one after the newest)
    private fun syncPictures() {
        bindFromState()
        ensurePick()
    }

    private fun bindFromState() {
        bind(cur, state.current)
        bind(prev, state.previous)
        bind(next, state.next)
        updatePictureInfo()
    }

    // Keeps picking until there's nothing left to pick. The shared state lets
    // only one pick run at a time, however many slideshows ask.
    private fun ensurePick() {
        if (pickJob?.isActive == true) return
        pickJob = scope.launch {
            while (true) {
                when (val result = state.pickAhead()) {
                    SlideshowState.PickResult.Picked -> bindFromState()
                    SlideshowState.PickResult.Nothing -> return@launch
                    is SlideshowState.PickResult.Failed -> {
                        // onTimer() retries
                        showStatus(context.getString(R.string.slideshow_error, result.message))
                        return@launch
                    }
                }
            }
        }
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
        val c = state.client ?: return
        if (id == null) return

        slot.infoJob = scope.launch {
            val info = state.metadata(id) ?: return@launch
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
        if (!state.goForward()) return
        val old = prev
        prev = cur
        cur = next
        next = old
        afterPaging()
    }

    // Puts the previous picture on screen, once it has slid in. Requires prev.ready.
    private fun pageBack() {
        if (!state.goBack()) return
        val old = next
        next = cur
        cur = prev
        prev = old
        afterPaging()
    }

    private fun afterPaging() {
        setOffset(0f)
        if (cur.loaded) status.visibility = View.GONE
        syncPictures()
    }

    // Shows the metadata of the picture on screen, hiding the text while it
    // isn't known
    private fun updatePictureInfo() {
        val picture = cur.picture
        val info = cur.info
        reportPhoto(picture, info)
        val text = when {
            picture == null || info == null -> ""
            state.infoExpanded -> pictureDetails(picture, info)
            else -> pictureSummary(info)
        }
        pictureInfo.text = text
        pictureInfo.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        pictureInfo.setBackgroundResource(if (state.infoExpanded) R.drawable.status_background else 0)
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

    // ---- Commands from MQTT -----------------------------------------------

    // Runs on the main thread. The screen commands are handled by the reporter;
    // these are the ones that belong to the slideshow.
    private fun carryOut(command: Command) {
        when (command) {
            Command.Next -> {
                onTimer()
                restartTimer()
            }
            Command.Previous -> {
                if (prev.ready) animateOffset(root.width.toFloat()) { pageBack() }
                restartTimer()
            }
            is Command.TransitionSeconds -> setTransitionSeconds(command.seconds)
            is Command.SetAlbumFilter -> setAlbumFilter(command.filter)
            is Command.Announce -> announce(command.message, command.timeoutSeconds)
            else -> Unit // Screen commands: the reporter deals with those
        }
    }

    // Changes the setting, so it sticks and the settings screen agrees
    private fun setTransitionSeconds(seconds: Int) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(Settings.KEY_SLIDE_SECONDS, seconds.toString())
            .apply()
        state.reloadSettings(context)
        restartTimer()
        Log.i(TAG, "Now ${seconds}s per picture")
    }

    // Chooses the albums the pictures come from. Like the transition time this
    // goes through the settings, so it sticks and the settings screen agrees.
    // Whatever is on screen may come from an album the new filter leaves out,
    // so the slideshow starts again from a freshly picked picture.
    private fun setAlbumFilter(filter: AlbumFilter) {
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString(Settings.KEY_ALBUM_INCLUDE, filter.include)
            .putString(Settings.KEY_ALBUM_EXCLUDE, filter.exclude)
            .putString(Settings.KEY_ALBUM_FROM_YEAR, filter.fromYear.toString())
            .putString(Settings.KEY_ALBUM_TO_YEAR, filter.toYear.toString())
            .apply()
        if (state.reloadSettings(context)) {
            pageAnimator?.cancel()
            for (slot in listOf(prev, cur, next)) bind(slot, null)
            setOffset(0f)
            showStatus(context.getString(R.string.slideshow_loading))
        }
        syncPictures()
        restartTimer()
        Log.i(TAG, "Album filter: $filter")
    }

    // Shows a message over the pictures. An empty message clears it, and a
    // timeout of 0 leaves it up until something else replaces it.
    private fun announce(message: String, timeoutSeconds: Int) {
        announcementJob?.cancel()
        if (message.isBlank()) {
            announcement.visibility = View.GONE
            return
        }
        announcement.text = message
        announcement.visibility = View.VISIBLE
        if (timeoutSeconds > 0) {
            announcementJob = scope.launch {
                delay(timeoutSeconds * 1000L)
                announcement.visibility = View.GONE
            }
        }
    }

    // Tells the reporter which picture is on screen, once when it appears and
    // again when its metadata arrives
    private fun reportPhoto(picture: AlbumPicture?, info: ImmichPictureInfo?) {
        if (picture == null) return
        val key = "${picture.id}:${info != null}"
        if (key == reportedPhoto) return
        reportedPhoto = key
        // Needs the API key to fetch, but it says which picture this is
        val url = runCatching { state.client?.pictureUrl(picture.id, ImmichPictureSize.PREVIEW) }.getOrNull()
        reporter.onPhotoShown(picture.id, picture.album.name, url, info)
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
        val seconds = state.slideSeconds
        return "Slideshow: ${if (interactive) "home" else "screensaver"}, album $album, ${seconds}s per picture"
    }

    // ---- Screen ------------------------------------------------------------

    // Switches the screen off during the night hours. The Portal's presence
    // detection wakes the screen again when it sees someone, and the next check
    // switches it off again, so the screen stays dark at night unless the user
    // actually touches it.
    private fun startNightWatch() {
        nightJob?.cancel()
        nightJob = scope.launch {
            // Not straight away: someone who just walked in should have time to
            // touch the screen before it goes dark again
            delay(NIGHT_FIRST_CHECK_MILLIS)
            while (true) {
                // The Portal resets the screen-off delay on its own, so it's
                // written again rather than only once at startup
                state.currentSettings?.let { ScreenControl.applyScreenOffDelay(context, it) }
                checkNight()
                delay(NIGHT_CHECK_MILLIS)
            }
        }
    }

    private fun checkNight() {
        val settings = state.currentSettings ?: return
        if (!settings.nightScreenOff) return
        if (!ScreenControl.isNight(LocalTime.now().hour, settings.nightStartHour, settings.nightEndHour)) return
        // Someone is using the device: leave it alone for a while
        if (SystemClock.elapsedRealtime() - state.lastTouchAt < NIGHT_TOUCH_GRACE_MILLIS) return
        if (!ScreenControl.canTurnScreenOff(context)) return

        Log.i(TAG, "Night hours: turning the screen off")
        ScreenControl.turnScreenOff(context)
    }

    // ---- Timer -------------------------------------------------------------

    // Moves forward every slideSeconds while the slideshow is visible. Restarted
    // after every swipe, so the user gets a full slide of time.
    private fun restartTimer() {
        timerJob?.cancel()
        if (!state.isConfigured) return
        val seconds = state.slideSeconds
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
        // still running). Forget it and try again.
        if (next.loading || pickJob?.isActive == true) return
        state.dropUpcoming()
        bind(next, null)
        syncPictures()
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
                state.noteTouch()
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
                    if (!horizontal || !state.isConfigured || pageAnimator != null) return
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

        // A swipe moves to the neighbour if it went this fraction of the screen
        // width, or was a fling
        const val PAGE_DISTANCE_FRACTION = 0.25f
        const val FLING_VELOCITY_FACTOR = 4
        const val PAGE_ANIMATION_MS = 300L
        const val NOW_PLAYING_POLL_MILLIS = 5000L

        const val NIGHT_CHECK_MILLIS = 30_000L
        const val NIGHT_FIRST_CHECK_MILLIS = 20_000L
        // How long a touch keeps the screen on during the night hours
        const val NIGHT_TOUCH_GRACE_MILLIS = 5 * 60 * 1000L
        // How much harder it is to drag when there is no picture to move to
        const val OVERSCROLL_RESISTANCE = 3f
    }
}
