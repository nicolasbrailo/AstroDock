package com.nicobrailo.alauncher

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

// Full screen slideshow of random Immich pictures (see RandomAlbumPicker).
//
// - Every Settings.slideSeconds it slides to the next picture.
// - The picture follows the finger while swiping. Swiping left (finger moving
//   right to left) moves forward; swiping right moves back through the last
//   HISTORY_SIZE pictures, stopping at the oldest (see PictureHistory). Swiping
//   restarts the timer, which then keeps moving forward from wherever the user
//   left off.
// - The bottom left corner shows the time and a line about the picture (year
//   and place). Tapping that line expands it with more details, until tapped
//   again.
// - Tapping opens AppListActivity, which launches apps and has the settings
//   button. (Not long-press: it fires when a swipe starts slowly.)
//
// The picture on screen (cur) and its neighbours (prev, next) each have their
// own view, kept one screen width to either side, so a neighbour can slide in
// with the finger. The next picture is picked and loaded ahead of time
// (`upcoming`), so moving forward doesn't wait for the network.
//
// Everything here runs on the main thread.
class SlideshowActivity : AppCompatActivity() {
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

    private lateinit var root: View
    private lateinit var status: TextView
    private lateinit var pictureInfo: TextView
    private var infoExpanded = false
    private lateinit var prev: Slot
    private lateinit var cur: Slot
    private lateinit var next: Slot

    // Rebuilt in onStart() whenever the settings change
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

    // Swipe state
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var velocityTracker: VelocityTracker? = null
    private var pageAnimator: ValueAnimator? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_slideshow)
        root = findViewById(R.id.root)
        status = findViewById(R.id.status)
        pictureInfo = findViewById(R.id.picture_info)
        pictureInfo.setOnClickListener {
            infoExpanded = !infoExpanded
            updatePictureInfo()
        }
        prev = Slot(findViewById(R.id.picture_a))
        cur = Slot(findViewById(R.id.picture_b))
        next = Slot(findViewById(R.id.picture_c))

        WindowCompat.getInsetsController(window, root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        // The neighbours' positions depend on the screen width
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (!dragging && pageAnimator == null) setOffset(0f)
        }
        setUpTouch()
    }

    override fun onStart() {
        super.onStart()
        val newSettings = Settings.load(this)
        if (newSettings != settings) {
            applySettings(newSettings)
        } else {
            // Pick up albums added on the server since the list was fetched
            picker?.refresh()
        }

        if (picker == null) return
        if (history.current == null) ensurePick()
        restartTimer()
    }

    override fun onDestroy() {
        velocityTracker?.recycle()
        super.onDestroy()
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
            showStatus(getString(R.string.slideshow_not_configured))
            return
        }
        val c = ImmichClient(s.serverUrl, s.apiKey)
        client = c
        picker = RandomAlbumPicker(c, s.maxPicturesPerAlbum, s.percentOfAlbum)
        showStatus(getString(R.string.slideshow_loading))
    }

    // ---- Pictures ----------------------------------------------------------

    // Picks a new picture in the background, unless a pick is already running.
    // The first one goes straight on screen; later ones become `upcoming`.
    private fun ensurePick() {
        val p = picker ?: return
        if (picking) return
        picking = true
        pickJob = lifecycleScope.launch {
            val picture = try {
                p.next()
            } catch (e: ImmichException) {
                Log.w(TAG, "Can't pick a picture", e)
                showStatus(getString(R.string.slideshow_error, e.message))
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

        slot.infoJob = lifecycleScope.launch {
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

        val request = ImageRequest.Builder(this)
            .data(c.pictureUrl(id, ImmichPictureSize.PREVIEW))
            .httpHeaders(NetworkHeaders.Builder().set(ImmichClient.API_KEY_HEADER, c.apiKey).build())
            .size(ViewSizeResolver(slot.view))
            .build()
        slot.job = lifecycleScope.launch {
            val result = SingletonImageLoader.get(this@SlideshowActivity).execute(request)
            if (slot.id != id) return@launch
            when (result) {
                is SuccessResult -> {
                    slot.view.setImageDrawable(result.image.asDrawable(resources))
                    slot.loaded = true
                    if (slot === cur) status.visibility = View.GONE
                }
                is ErrorResult -> {
                    // onTimer() retries
                    Log.w(TAG, "Can't load picture $id", result.throwable)
                    if (slot === cur) showStatus(getString(R.string.slideshow_error, result.throwable.message))
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

    private fun showStatus(message: String) {
        status.text = message
        status.visibility = View.VISIBLE
    }

    // ---- Timer -------------------------------------------------------------

    // Moves forward every slideSeconds while the activity is visible. Restarted
    // after every swipe, so the user gets a full slide of time.
    private fun restartTimer() {
        timerJob?.cancel()
        val seconds = settings?.slideSeconds ?: return
        if (picker == null) return
        timerJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    delay(seconds * 1000L)
                    onTimer()
                }
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
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                startActivity(Intent(this@SlideshowActivity, AppListActivity::class.java))
                @Suppress("DEPRECATION") // The replacement needs API 34
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
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
                    val slop = ViewConfiguration.get(this).scaledTouchSlop
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
        val minFling = ViewConfiguration.get(this).scaledMinimumFlingVelocity * FLING_VELOCITY_FACTOR
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
        const val TAG = "SlideshowActivity"

        // How many pictures the user can swipe back through
        const val HISTORY_SIZE = 20

        // A swipe moves to the neighbour if it went this fraction of the screen
        // width, or was a fling
        const val PAGE_DISTANCE_FRACTION = 0.25f
        const val FLING_VELOCITY_FACTOR = 4
        const val PAGE_ANIMATION_MS = 300L
        // How much harder it is to drag when there is no picture to move to
        const val OVERSCROLL_RESISTANCE = 3f
    }
}
