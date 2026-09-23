package com.nicobrailo.astrodock.media

// What a media session last said about itself, reduced to what tells a real
// change from the same thing said again
data class SessionSnapshot(
    val state: Int,
    val position: Long,
    val activeItem: Long,
    val title: String?,
)

// When the session on show last changed, measured by us rather than taken from
// the session. An app's PlaybackState carries its own update time, but that is
// only when the app last published a state, and apps publish the same one again
// without anything having happened: Spotify's paused session was re-sent every
// few minutes for hours, and the panel, which hides a paused session a while
// after it last changed, never hid.
//
// There is one of these for the whole process, because the home screen and the
// screensaver each have their own NowPlaying and hand over to each other all the
// time; starting from scratch on every handover would bring a long paused
// session back for another full timeout.
class SessionActivity {
    private var packageName: String? = null
    private var snapshot: SessionSnapshot? = null
    private var changedAt = 0L

    // Records what the session says now and returns when it last changed. A
    // different app starts the clock again. So does anything different from the
    // same app; the same thing said again doesn't. `reportedAt` is the session's
    // own update time, only used for the first session seen after the process
    // starts, when we have nothing better, and never later than `now`.
    fun observe(packageName: String, snapshot: SessionSnapshot, now: Long, reportedAt: Long): Long {
        when {
            this.packageName == null -> {
                changedAt = if (reportedAt in 1..now) reportedAt else now
            }
            packageName != this.packageName || snapshot != this.snapshot -> changedAt = now
        }
        this.packageName = packageName
        this.snapshot = snapshot
        return changedAt
    }

    companion object {
        val shared = SessionActivity()
    }
}
