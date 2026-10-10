package com.nicobrailo.astrodock.mqtt

// The text over the pictures, kept by the reporter rather than by the
// slideshow showing it. Another app can be in front when an announcement
// comes or goes (the doorbell's camera opens while its ring plays), and a
// slideshow that isn't on screen hears nothing, so it would come back to an
// announcement whose end it never heard of. Each slideshow shows this one as
// it appears instead. Times are SystemClock.elapsedRealtime().
class Announcement(
    val message: String,
    // Whoever put it up, so they can take down their own without taking down
    // whatever replaced it. Null for plain announcements.
    val owner: Any?,
    // When it goes away; null keeps it up until something replaces it
    val until: Long?,
) {
    fun visibleAt(now: Long) = until == null || now < until

    companion object {
        // What Command.Announce leaves up: an empty message clears it, and a
        // timeout of 0 leaves it up until something else replaces it
        fun of(message: String, timeoutSeconds: Int, owner: Any?, now: Long): Announcement? {
            if (message.isBlank()) return null
            return Announcement(message, owner, if (timeoutSeconds > 0) now + timeoutSeconds * 1000L else null)
        }

        // What Command.EndAnnouncement leaves up: the countdown starts only on
        // owner's own announcement, still up. Whatever was announced since is
        // newer, so it stays.
        fun ending(current: Announcement?, owner: Any, afterSeconds: Int, now: Long): Announcement? {
            if (current == null || current.owner !== owner || !current.visibleAt(now)) return current
            return Announcement(current.message, owner, now + afterSeconds * 1000L)
        }
    }
}
