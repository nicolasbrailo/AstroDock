package com.nicobrailo.astrodock.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class AlarmTest {
    private val zone = ZoneId.of("Europe/Amsterdam")

    // 2026-10-03 is a Saturday
    private fun at(text: String): ZonedDateTime = ZonedDateTime.of(LocalDateTime.parse(text), zone)

    private fun alarm(hour: Int, minute: Int, vararg days: java.time.DayOfWeek, enabled: Boolean = true) =
        Alarm(id = "$hour:$minute:${days.toList()}", enabled = enabled, hour = hour, minute = minute, days = days.toSet())

    @Test
    fun onceRingsTodayIfStillAhead() {
        assertEquals(at("2026-10-03T08:00"), AlarmSchedule.next(alarm(8, 0), at("2026-10-03T07:59")))
    }

    @Test
    fun onceRingsTomorrowIfPast() {
        assertEquals(at("2026-10-04T08:00"), AlarmSchedule.next(alarm(8, 0), at("2026-10-03T08:00")))
    }

    @Test
    fun weekdaysSkipTheWeekend() {
        val weekdays = alarm(8, 0, MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)
        assertEquals(at("2026-10-05T08:00"), AlarmSchedule.next(weekdays, at("2026-10-03T07:00")))
    }

    @Test
    fun sameDayNextWeekWhenTodayIsPast() {
        val saturdays = alarm(8, 0, SATURDAY)
        assertEquals(at("2026-10-10T08:00"), AlarmSchedule.next(saturdays, at("2026-10-03T09:00")))
    }

    @Test
    fun disabledNeverRings() {
        assertNull(AlarmSchedule.next(alarm(8, 0, enabled = false), at("2026-10-03T07:00")))
    }

    @Test
    fun keepsTheWallClockTimeAcrossSummerTime() {
        // Summer time ends on 2026-10-25 at 03:00 in Amsterdam
        val daily = alarm(8, 0, *java.time.DayOfWeek.entries.toTypedArray())
        assertEquals(at("2026-10-25T08:00"), AlarmSchedule.next(daily, at("2026-10-24T09:00")))
        assertEquals(8, AlarmSchedule.next(daily, at("2026-10-24T09:00"))!!.hour)
    }

    @Test
    fun aTimeThatDoesNotExistRingsAnHourLater() {
        // Summer time starts on 2026-03-29: 02:30 doesn't happen
        assertEquals(at("2026-03-29T03:30"), AlarmSchedule.next(alarm(2, 30), at("2026-03-29T01:00")))
    }

    @Test
    fun soonestPicksTheEarliestAndTheFirstOnATie() {
        val now = at("2026-10-03T06:00").toInstant().toEpochMilli()
        val a = alarm(9, 0)
        val b = alarm(7, 0, SUNDAY)
        val c = alarm(7, 30)
        val d = alarm(7, 30).copy(id = "d")
        assertEquals(c, AlarmSchedule.soonest(listOf(a, b, c, d), now, zone)!!.first)
        assertNull(AlarmSchedule.soonest(listOf(alarm(7, 0, enabled = false)), now, zone))
    }

    @Test
    fun dueFindsEveryAlarmForThatMoment() {
        val eight = at("2026-10-03T08:00").toInstant().toEpochMilli()
        val a = alarm(8, 0)
        val b = alarm(8, 0, SATURDAY)
        val c = alarm(8, 0, SUNDAY)
        val d = alarm(8, 1)
        assertEquals(listOf(a, b), AlarmSchedule.due(listOf(a, b, c, d), eight, zone))
    }

    @Test
    fun daysKinds() {
        assertEquals(AlarmSchedule.Days.ONCE, AlarmSchedule.kind(emptySet()))
        assertEquals(AlarmSchedule.Days.WEEKDAYS, AlarmSchedule.kind(setOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY)))
        assertEquals(AlarmSchedule.Days.WEEKENDS, AlarmSchedule.kind(setOf(SATURDAY, SUNDAY)))
        assertEquals(AlarmSchedule.Days.EVERY_DAY, AlarmSchedule.kind(java.time.DayOfWeek.entries.toSet()))
        assertEquals(AlarmSchedule.Days.SOME, AlarmSchedule.kind(setOf(MONDAY, FRIDAY)))
    }

    @Test
    fun playRequests() {
        assertEquals(PlayRequest.Resume, PlayRequest.parse("  "))
        assertEquals(
            PlayRequest.Uri("spotify:playlist:37i9dQZF1DXcBWIGoYBM5M"),
            PlayRequest.parse("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc123"),
        )
        assertEquals(
            PlayRequest.Uri("spotify:album:4aawyAB9vmqN3uQ7FjRGTy"),
            PlayRequest.parse("https://open.spotify.com/intl-es/album/4aawyAB9vmqN3uQ7FjRGTy"),
        )
        assertEquals(PlayRequest.Uri("spotify:collection:tracks"), PlayRequest.parse("spotify:collection:tracks"))
        assertEquals(PlayRequest.Uri("http://radio.local/stream.mp3"), PlayRequest.parse("http://radio.local/stream.mp3"))
        assertEquals(PlayRequest.Search("morning jazz"), PlayRequest.parse("morning jazz"))
        assertEquals(
            "spotify:playlist:37i9dQZF1DXcBWIGoYBM5M:play",
            (PlayRequest.parse("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M") as PlayRequest.Uri).viewUri,
        )
        assertEquals("spotify:album:4aawyAB9vmqN3uQ7FjRGTy:play", PlayRequest.Uri("spotify:album:4aawyAB9vmqN3uQ7FjRGTy").viewUri)
        assertEquals("spotify:playlist:x:play", PlayRequest.Uri("spotify:playlist:x:play").viewUri)
        assertEquals("spotify:track:abc", PlayRequest.Uri("spotify:track:abc").viewUri)
        assertEquals("http://radio.local/stream.mp3", PlayRequest.Uri("http://radio.local/stream.mp3").viewUri)
        // A colon followed by a space is a sentence, not a URI
        assertEquals(PlayRequest.Search("Radiohead: OK Computer"), PlayRequest.parse("Radiohead: OK Computer"))
    }

    @Test
    fun recentPlaylistsKeepTheNewestFirstOnce() {
        fun p(uri: String, title: String = uri) = RecentPlaylists.Playlist("com.spotify.music", uri, title)
        val list = listOf(p("spotify:playlist:a"), p("spotify:playlist:b"))
        assertEquals(
            listOf(p("spotify:playlist:b", "B renamed"), p("spotify:playlist:a")),
            RecentPlaylists.merge(list, p("spotify:playlist:b", "B renamed")),
        )
        // The same link in another app is another entry
        val other = RecentPlaylists.Playlist("org.example", "spotify:playlist:a", "a")
        assertEquals(3, RecentPlaylists.merge(list, other).size)
        val full = (1..RecentPlaylists.MAX).map { p("spotify:playlist:x$it") }
        val merged = RecentPlaylists.merge(full, p("spotify:playlist:new"))
        assertEquals(RecentPlaylists.MAX, merged.size)
        assertEquals("spotify:playlist:new", merged.first().uri)
        assertEquals("spotify:playlist:x${RecentPlaylists.MAX - 1}", merged.last().uri)
    }

    @Test
    fun onlyWhatPlaysWhenOpenedIsNoted() {
        val app = "com.spotify.music"
        assertEquals(
            RecentPlaylists.Playlist(app, "spotify:playlist:37i9dQZF1DWZeKCadgRdKQ", "Deep Focus"),
            RecentPlaylists.playlist(app, "spotify:playlist:37i9dQZF1DWZeKCadgRdKQ", " Deep Focus "),
        )
        assertNull(RecentPlaylists.playlist(app, "spotify:playlist:37i9dQZF1DWZeKCadgRdKQ", ""))
        assertNull(RecentPlaylists.playlist(app, null, "Deep Focus"))
        assertNull(RecentPlaylists.playlist(app, "spotify:user:someone:collection", "Liked Songs"))
        assertNull(RecentPlaylists.playlist(app, "spotify:track:7IdeZbJRDzOfrrPIbeYCMB", "Seeing"))
    }

    @Test
    fun volumeRampsEvenlyToTheTarget() {
        assertEquals(1, VolumeRamp.start(5))
        assertEquals(0, VolumeRamp.start(0))
        assertEquals(
            listOf(15_000L to 2, 30_000L to 3, 45_000L to 4, 60_000L to 5),
            VolumeRamp.steps(1, 5, 60_000),
        )
        assertEquals(listOf(60_000L to 2), VolumeRamp.steps(1, 2, 60_000))
        // Nothing to raise: already there, or the alarm is quieter than the start
        assertEquals(emptyList<Pair<Long, Int>>(), VolumeRamp.steps(1, 1, 60_000))
        assertEquals(emptyList<Pair<Long, Int>>(), VolumeRamp.steps(3, 1, 60_000))
    }
}
