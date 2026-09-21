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
import android.view.Window
import android.view.WindowManager
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
import com.nicobrailo.astrodock.weather.PlaceCache
import com.nicobrailo.astrodock.weather.WeatherClient
import com.nicobrailo.astrodock.weather.WeatherCondition
import com.nicobrailo.astrodock.weather.WeatherException
import com.nicobrailo.astrodock.weather.millisToNextHour
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
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
// - If the user asked for it, the screen goes dark during the night hours
//   (see checkNight), a little after they last touched it.
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
    // The host's window, whose backlight is turned down during the night hours
    private val window: Window,
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
    // Covers the pictures at night, while the screen is on but shouldn't be
    private val nightCover: View = root.findViewById(R.id.night_cover)
    private var dark = false
    // Between start() and stop(), which is when going dark and back is news for
    // the broker; the two of them report the rest
    private var started = false

    // What another app is playing. Only watched while the slideshow is visible.
    private val nowPlayingPanel: View = root.findViewById(R.id.now_playing)
    private val nowPlayingArt: ImageView = root.findViewById(R.id.now_playing_art)
    private val nowPlayingTitle: TextView = root.findViewById(R.id.now_playing_title)
    private val nowPlayingArtist: TextView = root.findViewById(R.id.now_playing_artist)
    private val nowPlayingPlay: ImageButton = root.findViewById(R.id.now_playing_play)
    private val nowPlaying = NowPlaying(context) { updateNowPlaying() }
    private var nowPlayingJob: Job? = null

    // Current temperature and sky, over the clock. Refreshed on the hour.
    private val weatherPanel: View = root.findViewById(R.id.weather)
    private val weatherIcon: ImageView = root.findViewById(R.id.weather_icon)
    private val weatherTemperature: TextView = root.findViewById(R.id.weather_temperature)
    private val weatherClient = WeatherClient()
    private val weatherPlaces = PlaceCache(context)
    private var weatherJob: Job? = null

    // Publishes what this device is doing to an MQTT broker, when that's set up
    private val reporter = StateReporter.get(context)
    private val reporterSource = if (interactive) "home" else "screensaver"
    private val announcement: TextView = root.findViewById(R.id.announcement)
    private var announcementJob: Job? = null
    // Who put up the announcement on screen, so they can take down their own
    // without taking down whatever replaced it
    private var announcementOwner: Any? = null
    private val onCommand: (Command) -> Unit = { carryOut(it) }
    // Whatever is wrong with the broker, in a corner of the home screen
    private val alert: TextView = root.findViewById(R.id.alert)
    private val onAlert: (String?) -> Unit = { showAlert(it) }
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
            // A touch in the dark only brings the pictures back, rather than
            // also opening the app list the user can't see yet
            nightCover.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    state.noteTouch()
                    setDark(false)
                }
                true
            }
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

        // Before anything is reported, so a screen the Portal wakes at night
        // is reported as the dark screen it is, without a moment of "active"
        startNightWatch()

        // Settings may have been edited in the MQTT tab meanwhile
        reporter.applySettings()
        reporter.onSlideshowVisible(reporterSource, !dark)
        started = true
        // Commands go to whichever slideshow is on screen
        reporter.setCommandListener(onCommand)
        // The screensaver is what runs all night, with nobody looking, so it
        // isn't the place to complain about the broker
        if (interactive) reporter.setAlertListener(onAlert)

        nowPlaying.start()
        // Sound stopping isn't reported, so the panel is re-checked now and then
        nowPlayingJob?.cancel()
        nowPlayingJob = scope.launch {
            while (true) {
                updateNowPlaying()
                delay(NOW_PLAYING_POLL_MILLIS)
            }
        }

        startWeather()

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
        started = false
        reporter.onSlideshowVisible(reporterSource, false)
        reporter.clearCommandListener(onCommand)
        reporter.clearAlertListener(onAlert)
        nightJob?.cancel()
        setDark(false)
        nowPlayingJob?.cancel()
        nowPlaying.stop()
        weatherJob?.cancel()
        timerJob?.cancel()
        portalStateJob?.cancel()
        portalState.stop()
    }

    // ---- Weather -----------------------------------------------------------

    // Fetches the weather now and then again on every hour, for as long as the
    // slideshow is on screen. Open-Meteo asks for no key, so the only reason to
    // skip it is the user turning it off or not having said where the device is.
    private fun startWeather() {
        weatherJob?.cancel()
        val settings = state.settings
        if (settings == null || !settings.showWeather) {
            weatherPanel.visibility = View.GONE
            return
        }
        weatherJob = scope.launch {
            while (true) {
                updateWeather(settings.weatherPlace)
                delay(millisToNextHour(Instant.now(), ZoneId.systemDefault()))
            }
        }
    }

    // The place is resolved on every round rather than once, which costs
    // nothing once it is cached and means a geocoder that was down when the
    // slideshow started is asked again an hour later, instead of never.
    private suspend fun updateWeather(placeName: String) {
        val weather = try {
            val place = weatherPlaces.resolve(placeName, weatherClient)
            if (place == null) {
                // A name the geocoder doesn't know won't be found next hour
                // either; the settings screen says so under the field
                Log.w(TAG, "Weather: found no place called \"$placeName\"")
                weatherPanel.visibility = View.GONE
                return
            }
            weatherClient.current(place)
        } catch (e: WeatherException) {
            // Nothing on screen depends on this, and the pictures are the
            // point, so a broker-style alert would be more noise than it is
            // worth: the panel just stays as it was, or stays hidden.
            Log.w(TAG, "Weather: ${e.message}")
            return
        }
        weatherTemperature.text = weather.temperatureText
        weatherIcon.setImageResource(iconOf(weather.condition))
        weatherPanel.visibility = View.VISIBLE
    }

    private fun iconOf(condition: WeatherCondition): Int = when (condition) {
        WeatherCondition.CLEAR -> R.drawable.ic_weather_clear
        WeatherCondition.PARTLY_CLOUDY -> R.drawable.ic_weather_partly_cloudy
        WeatherCondition.CLOUDY -> R.drawable.ic_weather_cloudy
        WeatherCondition.FOG -> R.drawable.ic_weather_fog
        WeatherCondition.RAIN -> R.drawable.ic_weather_rain
        WeatherCondition.SNOW -> R.drawable.ic_weather_snow
        WeatherCondition.THUNDERSTORM -> R.drawable.ic_weather_thunder
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
            is Command.Announce -> announce(command.message, command.timeoutSeconds, command.owner)
            is Command.EndAnnouncement -> endAnnouncement(command.owner, command.afterSeconds)
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
    private fun announce(message: String, timeoutSeconds: Int, owner: Any? = null) {
        announcementJob?.cancel()
        announcementOwner = owner
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

    // Starts the countdown on an announcement that was put up with none, if it
    // is still on screen. Whatever was announced since is newer, so it stays.
    private fun endAnnouncement(owner: Any, afterSeconds: Int) {
        if (owner !== announcementOwner || announcement.visibility != View.VISIBLE) return
        announcementJob?.cancel()
        announcementJob = scope.launch {
            delay(afterSeconds * 1000L)
            announcement.visibility = View.GONE
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

    // Nothing on screen depends on MQTT, so a broker that can't be reached would
    // otherwise be invisible until someone read the log
    private fun showAlert(message: String?) {
        if (message.isNullOrBlank()) {
            alert.visibility = View.GONE
            return
        }
        alert.text = message
        alert.visibility = View.VISIBLE
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

    // Keeps the screen dark during the night hours. Switching it off isn't
    // enough on its own: while the Portal's camera sees someone, its presence
    // detection wakes the screen about every 30s and starts the screensaver,
    // and nothing an app can reach stops that. Locking again on every wake made
    // the pictures blink on and off all night. So at night the slideshow is
    // covered in black with the backlight at its lowest, from the moment it
    // appears, and the screen is only switched off when that is likely to
    // stick: the first time, and then again once NIGHT_RELOCK_MILLIS have
    // passed, by when whoever woke it may have gone. If they haven't, the wake
    // that follows is from black to black.
    private fun startNightWatch() {
        nightJob?.cancel()
        // Straight away, so a screen the Portal has just woken never shows a
        // picture; switching it off can wait for the first check
        checkNight(mayLock = false)
        nightJob = scope.launch {
            // Not straight away: someone who just walked in should have time to
            // touch the screen before it goes off again
            delay(NIGHT_FIRST_CHECK_MILLIS)
            while (true) {
                // The Portal resets the screen-off delay on its own, so it's
                // written again rather than only once at startup
                state.currentSettings?.let { ScreenControl.applyScreenOffDelay(context, it) }
                checkNight(mayLock = true)
                delay(NIGHT_CHECK_MILLIS)
            }
        }
    }

    private fun checkNight(mayLock: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val settings = state.currentSettings
        val night = settings != null && settings.nightScreenOff &&
            ScreenControl.isNight(LocalTime.now().hour, settings.nightStartHour, settings.nightEndHour) &&
            // Someone is using the device: leave it alone for a while
            now - state.lastTouchAt >= NIGHT_TOUCH_GRACE_MILLIS &&
            // The settings screen greys the rule out without the admin, so it
            // does nothing at all rather than half of it
            ScreenControl.canTurnScreenOff(context)
        setDark(night)
        if (!night || !mayLock) return

        val lockedAt = state.lastNightLockAt
        if (lockedAt != null && now - lockedAt < NIGHT_RELOCK_MILLIS) return
        state.lastNightLockAt = now
        Log.i(TAG, "Night hours: turning the screen off")
        ScreenControl.turnScreenOff(context)
    }

    // Covers the pictures in black and turns the backlight down to its lowest
    // level, which on the Portal is dim but not off
    private fun setDark(on: Boolean) {
        if (on == dark) return
        dark = on
        Log.i(TAG, if (on) "Night hours: dark" else "Night hours: showing pictures")
        nightCover.visibility = if (on) View.VISIBLE else View.GONE
        // Nobody can see a slideshow under the cover, so it isn't active
        if (started) reporter.onSlideshowVisible(reporterSource, !on)
        window.attributes = window.attributes.apply {
            screenBrightness = if (on) NIGHT_BRIGHTNESS else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
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
        // Nobody would see it, and it would only fetch pictures
        if (dark) return
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
        // How long after switching the screen off at night it is left on if
        // the Portal wakes it again, which means its camera still sees someone
        const val NIGHT_RELOCK_MILLIS = 10 * 60 * 1000L
        // The lowest backlight level there is (1 of 255)
        const val NIGHT_BRIGHTNESS = 1f / 255
        // How much harder it is to drag when there is no picture to move to
        const val OVERSCROLL_RESISTANCE = 3f
    }
}
