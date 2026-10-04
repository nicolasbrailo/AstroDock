package com.nicobrailo.astrodock.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.nicobrailo.astrodock.R
import com.nicobrailo.astrodock.ScreenControl
import com.nicobrailo.astrodock.SlideshowState
import com.nicobrailo.astrodock.audio.AnnouncementPlayer
import com.nicobrailo.astrodock.mqtt.StateReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Rings an alarm: wakes the screen, sets the volume and starts the media app
// on what the alarm asks for (MediaStarter). An alarm that wakes nobody is
// the one failure that matters, so if nothing can be heard a little later
// (no network, an app that was logged out or uninstalled), the device's own
// alarm sound plays instead, and the screen says why. That sound is also what
// an alarm without an app plays. It goes on until the slideshow is touched,
// or for FALLBACK_MILLIS.
//
// The music itself is the app's to stop, from the media panel like any
// other; the alarm lets go of it once it has started.
//
// One alarm at a time, on the main thread, in the main process: StateReporter
// is what puts text on screen.
object AlarmRinger {
    private const val TAG = "AlarmRinger"
    // What ScreenControl, and so the MQTT state's `wanted_reason`, calls it
    const val SCREEN_REASON = "alarm"
    // Long enough to see the screen while waking up; the Portal's own timers
    // take over after it
    private const val SCREEN_MILLIS = 5 * 60 * 1000L
    // How long the app has to be heard before the alarm sound takes over. The
    // app may need to start its process, then its session, then buffer.
    private const val CHECK_AFTER_MILLIS = 30_000L
    private const val POLL_MILLIS = 500L
    private const val REWAKE_MILLIS = 1_500L
    private const val MAX_REWAKES = 4
    private const val HOME_AFTER_MILLIS = 2_000L
    // How long the music takes to rise to the alarm's volume
    private const val RAMP_MILLIS = 60_000L
    private const val FALLBACK_MILLIS = 10 * 60 * 1000L
    // How long the alarm's line stays on screen when all went well
    private const val ANNOUNCE_SECONDS = 60

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private var fallback: MediaPlayer? = null
    // The announcement on screen while the alarm sound plays
    private var fallbackNote: Any? = null
    private val stopFallback = Runnable { silence() }

    fun ring(context: Context, alarm: Alarm) {
        val app = context.applicationContext
        appContext = app
        Log.i(TAG, "Ringing ${alarm.time} (${alarm.id}): ${alarm.app ?: "alarm sound"} ${alarm.play}")
        job?.cancel()
        silence()
        SlideshowState.shared.noteAlarm()
        job = scope.launch {
            wakeUp(app)
            StateReporter.get(app).refresh()
            val packageName = alarm.app
            if (packageName == null) {
                playFallback(app, app.getString(R.string.alarm_ringing_sound, alarm.time))
                return@launch
            }
            val label = MediaStarter.label(app, packageName)
            StateReporter.get(app).announce(app.getString(R.string.alarm_ringing, alarm.time, label), ANNOUNCE_SECONDS)
            val starter = MediaStarter(app)
            // Music already playing here is somebody listening, at the volume
            // they chose, so it is left alone. Otherwise it starts quiet and
            // rises to the alarm's volume once it plays (rampVolume).
            val target = if (starter.isPlaying(packageName)) null else mediaIndex(app, alarm.volume)
            // Put back if the music doesn't play here after all, rather than
            // leaving the device at the ramp's first step
            val before = app.getSystemService(AudioManager::class.java)?.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (target != null) setMediaIndex(app, VolumeRamp.start(target))
            fun restoreVolume() {
                if (target != null && before != null) setMediaIndex(app, before)
            }
            val result = starter.start(packageName, PlayRequest.parse(alarm.play), alarm.shuffle)
            if (result is MediaStarter.Result.Elsewhere) {
                restoreVolume()
                playFallback(app, app.getString(R.string.alarm_app_elsewhere, alarm.time, label))
                return@launch
            }
            if (result is MediaStarter.Result.Failed) {
                Log.w(TAG, "Alarm ${alarm.time}: ${result.reason}")
                restoreVolume()
                // The app may have come to the front before it failed, and
                // the message has to be seen
                goHome(app)
                playFallback(app, app.getString(R.string.alarm_app_failed, alarm.time, label))
                return@launch
            }
            // Polled rather than checked once at the end, so an app that was
            // brought to the front can be sent back as soon as it plays
            val giveUpAt = SystemClock.elapsedRealtime() + CHECK_AFTER_MILLIS
            while (!starter.isPlaying(packageName) && SystemClock.elapsedRealtime() < giveUpAt) delay(POLL_MILLIS)
            val playing = starter.isPlaying(packageName)
            if ((result as MediaStarter.Result.Asked).openedApp) {
                // A moment more, for the app to finish what it does on screen
                if (playing) delay(HOME_AFTER_MILLIS)
                goHome(app)
            }
            if (playing) {
                Log.i(TAG, "Alarm ${alarm.time}: $packageName is playing")
                // A child of this job, so another alarm ringing stops it
                if (target != null) launch { rampVolume(app, target) }
            } else {
                Log.w(TAG, "Alarm ${alarm.time}: $packageName isn't playing, sounding the alarm")
                restoreVolume()
                playFallback(app, app.getString(R.string.alarm_app_silent, alarm.time, label))
            }
        }
    }

    // Wakes the screen and holds it on for a while, and makes sure no
    // screensaver is left covering it: an app opened on a link under one never
    // gets as far as playing (measured: Spotify stayed paused under the
    // Portal's). A screen that was off wakes into a screensaver the system
    // starts as it comes on, and only a second wake ends that one, as for an
    // incoming call (CallRouter); a screensaver already running ends on the
    // first. The screensaver starting is watched for from before the first
    // wake, since nothing says whether one is running.
    //
    // The hold is never released early: it is only there for the waking, and
    // the Portal's own timers take over when it expires.
    private suspend fun wakeUp(context: Context) {
        var dreaming = StateReporter.get(context).isDreaming
        val watch = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                dreaming = intent.action == Intent.ACTION_DREAMING_STARTED
            }
        }
        val filter = IntentFilter(Intent.ACTION_DREAMING_STARTED).apply { addAction(Intent.ACTION_DREAMING_STOPPED) }
        ContextCompat.registerReceiver(context, watch, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            var lock = ScreenControl.keepScreenOn(context, SCREEN_REASON, SCREEN_MILLIS, wakeUp = true)
            for (i in 1..MAX_REWAKES) {
                delay(REWAKE_MILLIS)
                if (!dreaming) break
                Log.i(TAG, "A screensaver is covering the screen, waking again")
                ScreenControl.release(lock)
                lock = ScreenControl.keepScreenOn(context, SCREEN_REASON, SCREEN_MILLIS, wakeUp = true)
            }
        } finally {
            context.unregisterReceiver(watch)
        }
    }

    // Opening a link brings the app to the front, over the pictures and the
    // alarm's line, so the home screen comes back once the music plays. Only
    // when that is us: on a device with another home screen the app stays.
    private fun goHome(context: Context) {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        if (resolved?.activityInfo?.packageName != context.packageName) return
        try {
            context.startActivity(home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Can't go back to the home screen", e)
        }
    }

    // Stops the alarm sound, if it is playing. The media app is left alone.
    fun silence() {
        main.removeCallbacks(stopFallback)
        val player = fallback ?: return
        fallback = null
        Log.i(TAG, "Alarm sound stopped")
        try {
            player.stop()
        } catch (e: IllegalStateException) {
            // Never got as far as playing
        }
        player.release()
        fallbackNote?.let { owner -> appContext?.let { StateReporter.get(it).endAnnouncement(owner, 0) } }
        fallbackNote = null
    }

    // Set by the first ring(): silence() is called from places that have none
    private var appContext: Context? = null

    // On the alarm stream, at whatever volume the device has for alarms: this
    // is the sound for when the gentler plan failed, so it shouldn't be the
    // music's quiet morning volume. Looped, since one beep wakes nobody.
    private fun playFallback(context: Context, message: String) {
        silence()
        val uri = RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val player = MediaPlayer()
        try {
            player.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            player.setDataSource(context, uri)
            player.isLooping = true
            // The screen may go off before it is stopped
            player.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK)
            player.prepare()
            player.start()
        } catch (e: Exception) {
            // IOException for a file it can't read, IllegalStateException and
            // the like for a player that wouldn't start. The message is still
            // worth showing.
            Log.e(TAG, "Can't play the alarm sound $uri", e)
            player.release()
            StateReporter.get(context).announce(message, 0)
            return
        }
        Log.i(TAG, "Alarm sound playing: $uri")
        fallback = player
        main.postDelayed(stopFallback, FALLBACK_MILLIS)
        val owner = Any()
        fallbackNote = owner
        StateReporter.get(context).announce(message + "\n" + context.getString(R.string.alarm_touch_to_stop), 0, owner)
    }

    // Raises the music from where it started to the alarm's volume over
    // RAMP_MILLIS. Whoever touches the volume meanwhile has the last word: the
    // ramp stops at the first step it finds it changed.
    private suspend fun rampVolume(context: Context, target: Int) {
        val audio = context.getSystemService(AudioManager::class.java) ?: return
        var expected = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        var elapsed = 0L
        for ((at, index) in VolumeRamp.steps(expected, target, RAMP_MILLIS)) {
            delay(at - elapsed)
            elapsed = at
            if (audio.getStreamVolume(AudioManager.STREAM_MUSIC) != expected) {
                Log.i(TAG, "The volume was changed, leaving it there")
                return
            }
            if (!setMediaIndex(context, index)) return
            expected = index
        }
    }

    // The media volume step for a percentage, or null where the volume can't
    // be set (fixed by the device)
    private fun mediaIndex(context: Context, percent: Int): Int? {
        val audio = context.getSystemService(AudioManager::class.java) ?: return null
        if (audio.isVolumeFixed) return null
        return AnnouncementPlayer.volumeIndex(percent, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC))
    }

    // The music's volume stays where the alarm puts it: it is what the music
    // goes on playing at, unlike an announcement's, which is put back
    private fun setMediaIndex(context: Context, index: Int): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        return try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
            true
        } catch (e: SecurityException) {
            // Do Not Disturb forbids it
            Log.w(TAG, "Can't set the volume", e)
            false
        }
    }
}
