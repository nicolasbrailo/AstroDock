package com.nicobrailo.astrodock.alarm

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

// One alarm, as the Alarms app edits it and AlarmStore keeps it. `app` is the
// package of the media app to start, or null for the alarm sound alone;
// `play` is what to ask it for (see PlayRequest), empty to carry on with
// whatever it played last. `days` empty means once, after which the alarm
// switches itself off. `volume` is the media volume to set, in percent.
data class Alarm(
    val id: String,
    val enabled: Boolean = true,
    val hour: Int = 8,
    val minute: Int = 0,
    val days: Set<DayOfWeek> = emptySet(),
    val app: String? = null,
    val play: String = "",
    // What `play` is called, to show it by name ("Deep Focus"); empty when it
    // was typed rather than picked (see RecentPlaylists)
    val playTitle: String = "",
    val shuffle: Boolean = true,
    val volume: Int = DEFAULT_VOLUME,
) {
    val time: String get() = "%02d:%02d".format(hour, minute)
    val once: Boolean get() = days.isEmpty()

    companion object {
        const val DEFAULT_VOLUME = 30
        val VOLUME_RANGE = 0..100
    }
}

// What the alarm asks the media app to play, from the text typed in the
// editor. A Spotify share link is turned into the spotify: URI it stands for,
// since that is what Spotify's media session takes (its links only mean
// something to its activity); any other URI goes to the app as it is, and
// anything else is a search, as when asking a voice assistant for it.
sealed interface PlayRequest {
    // Whatever the app played last
    object Resume : PlayRequest
    data class Uri(val uri: String) : PlayRequest {
        // What to open in the app's own activity. Spotify only shows a
        // playlist, album, artist or show opened as it is; with ":play" on the
        // end it plays it too (MediaStarter).
        val viewUri: String get() = if (SPOTIFY_CONTEXT.matches(uri)) "$uri:play" else uri
    }
    data class Search(val query: String) : PlayRequest

    companion object {
        private val SPOTIFY_LINK = Regex(
            """^https?://open\.spotify\.com/(?:intl-[a-zA-Z-]+/)?""" +
                """(track|album|playlist|artist|show|episode)/([A-Za-z0-9]+)(?:[/?#].*)?$"""
        )
        private val SPOTIFY_CONTEXT = Regex("""^spotify:(album|playlist|artist|show):[A-Za-z0-9]+$""")
        // A scheme, as RFC 3986 spells it, then something after it
        private val URI = Regex("""^[a-zA-Z][a-zA-Z0-9+.-]*:\S+$""")

        // Whether opening this URI in its app plays it rather than only
        // showing it, so that an alarm can be set to it
        fun playsWhenOpened(uri: String): Boolean = SPOTIFY_CONTEXT.matches(uri)

        fun parse(text: String): PlayRequest {
            val t = text.trim()
            if (t.isEmpty()) return Resume
            SPOTIFY_LINK.matchEntire(t)?.let { return Uri("spotify:${it.groupValues[1]}:${it.groupValues[2]}") }
            return if (URI.matches(t)) Uri(t) else Search(t)
        }
    }
}

// How the music's volume rises when an alarm starts it: from the quietest step
// that can be heard up to the alarm's own, evenly over `durationMillis`. The
// stream only has a few steps (18 on a Portal+, so 30% is 5 of them), which
// makes it a staircase, one step every 15s or so over a minute. As pure
// functions, unit tested.
object VolumeRamp {
    // Where it starts: the lowest step that makes a sound, unless the alarm
    // asks for silence
    fun start(target: Int): Int = minOf(1, target)

    // Each step after the start, with when to set it, in milliseconds from the
    // start of the ramp; the last one is the target, at durationMillis. Empty
    // when there is nothing to raise.
    fun steps(start: Int, target: Int, durationMillis: Long): List<Pair<Long, Int>> {
        if (target <= start) return emptyList()
        val count = target - start
        return (1..count).map { i -> durationMillis * i / count to start + i }
    }
}

// When alarms ring, as pure functions so they can be unit tested. Everything
// is worked out in the device's own zone, so an alarm stays at 08:00 across a
// change to or from summer time.
object AlarmSchedule {
    // The first time strictly after `after` that the alarm rings, or null if
    // it never will. On the night a clock goes forward, a time that doesn't
    // exist rings at the same distance into the new hour (02:30 at 03:30),
    // which is what ZonedDateTime does with it.
    fun next(alarm: Alarm, after: ZonedDateTime): ZonedDateTime? {
        if (!alarm.enabled) return null
        val time = LocalTime.of(alarm.hour, alarm.minute)
        // A week and a day: today's time may be past already
        for (i in 0..7L) {
            val date = after.toLocalDate().plusDays(i)
            if (!alarm.once && date.dayOfWeek !in alarm.days) continue
            val at = ZonedDateTime.of(date, time, after.zone)
            if (at.isAfter(after)) return at
        }
        return null
    }

    // The alarm to schedule next and when, in milliseconds since the epoch:
    // the soonest of them, and the first in the list on a tie
    fun soonest(alarms: List<Alarm>, nowMillis: Long, zone: ZoneId): Pair<Alarm, Long>? {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMillis), zone)
        return alarms
            .mapNotNull { alarm -> next(alarm, now)?.let { alarm to it.toInstant().toEpochMilli() } }
            .minByOrNull { it.second }
    }

    // The alarms set for `atMillis`, which is when the system woke us. Only
    // one rings, but every one of them has had its turn, so the ones set to
    // ring once are switched off too, or they'd ring again tomorrow.
    fun due(alarms: List<Alarm>, atMillis: Long, zone: ZoneId): List<Alarm> {
        val before = ZonedDateTime.ofInstant(Instant.ofEpochMilli(atMillis - 1), zone)
        return alarms.filter { next(it, before)?.toInstant()?.toEpochMilli() == atMillis }
    }

    // Which kind of week the days make, for the summary in the Alarms app
    enum class Days { ONCE, EVERY_DAY, WEEKDAYS, WEEKENDS, SOME }

    private val WEEKEND = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)

    fun kind(days: Set<DayOfWeek>): Days = when {
        days.isEmpty() -> Days.ONCE
        days.size == 7 -> Days.EVERY_DAY
        days == DayOfWeek.entries.toSet() - WEEKEND -> Days.WEEKDAYS
        days == WEEKEND -> Days.WEEKENDS
        else -> Days.SOME
    }
}
