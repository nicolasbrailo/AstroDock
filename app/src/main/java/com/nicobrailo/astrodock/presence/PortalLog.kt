package com.nicobrailo.astrodock.presence

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale

// Follows the system log for the lines the Portal's presence detection writes
// (Presence.md) and keeps `presence` up to date with them. Meta keeps the
// detection itself behind signature permissions, but it logs every step, and
// READ_LOGS, which only adb can grant (tools/setup-device.sh), lets us read
// that.
//
// One for the process, started by StateReporter, which lives as long as the
// process does. Like the reporter, nothing in the :call process may use it.
// `presence` and the listeners are used on the main thread; the reading
// happens on a thread of its own.
object PortalLog {
    private const val TAG = "PortalLog"

    // How far back to start: long enough to know whether the camera was
    // covered or privacy mode was on when the app started, as those are
    // logged only when they change
    private const val BACKLOG_MILLIS = 60 * 60 * 1000L
    private const val RESPAWN_MILLIS = 5_000L

    private val main = Handler(Looper.getMainLooper())
    private val listeners = mutableListOf<() -> Unit>()

    @Volatile
    private var reader: Thread? = null

    var presence = PortalPresence(System.currentTimeMillis())
        private set

    // Whether the lines are being read, so the verdict means something
    val isReading: Boolean get() = reader != null

    fun canRead(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_LOGS) == PackageManager.PERMISSION_GRANTED

    // Starts reading if it isn't already and the grant is there. Called every
    // time a slideshow appears, so a grant given over adb while the app runs
    // is picked up without a restart: a logcat started before the grant would
    // only ever see this app's own lines, so none is started without it.
    fun start(context: Context) {
        if (reader != null || !canRead(context)) return
        val startedAt = System.currentTimeMillis()
        presence = PortalPresence(startedAt)
        reader = Thread({ follow(startedAt - BACKLOG_MILLIS) }, "PortalLog").apply {
            isDaemon = true
            start()
        }
        Log.i(TAG, "Reading the Portal's presence from the log")
    }

    // Called on the main thread whenever a line changed something, or might have
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    // logcat for the tags PortalPresence knows, carrying on from the last line
    // it had whenever logcat has to be started again
    private fun follow(from: Long) {
        var after = from
        while (true) {
            try {
                val since = String.format(Locale.US, "%d.%03d", after / 1000, after % 1000)
                val command = listOf("logcat", "-v", "epoch", "-T", since, "-s") +
                    PortalPresence.TAGS.map { "$it:V" }
                val process = ProcessBuilder(command).redirectErrorStream(true).start()
                process.inputStream.bufferedReader().use { lines ->
                    while (true) {
                        val line = PortalPresence.parse(lines.readLine() ?: break) ?: continue
                        // A restarted logcat repeats the lines of the last millisecond
                        if (line.at < after) continue
                        after = line.at
                        main.post { onLine(line) }
                    }
                }
                process.destroy()
            } catch (e: Exception) {
                Log.w(TAG, "logcat failed", e)
            }
            Thread.sleep(RESPAWN_MILLIS)
        }
    }

    // Somebody touched the screen: evidence for PortalPresence.stuck. Called
    // on the main thread.
    fun noteSomebody() {
        if (!isReading) return
        presence.noteSomebody(System.currentTimeMillis())
        listeners.forEach { it() }
    }

    private fun onLine(line: LogLine) {
        presence.onLine(line)
        listeners.forEach { it() }
    }
}
