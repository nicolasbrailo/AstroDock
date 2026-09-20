package com.nicobrailo.astrodock.immich

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumFilterTest {
    private fun album(name: String, from: String = "", to: String = from) =
        ImmichAlbum("id-$name", name, assetCount = 1, startDate = from, endDate = to)

    private val albums = listOf(
        album("portalgo-kids", "2019-04-02T10:00:00.000Z", "2019-08-01T10:00:00.000Z"),
        album("portalgo-pets", "2021-01-02T10:00:00.000Z"),
        album("Holidays 2015", "2015-07-02T10:00:00.000Z", "2015-07-30T10:00:00.000Z"),
        album("Screenshots", "2024-01-02T10:00:00.000Z"),
        album("Unsorted"), // The server gave no dates for this one
    )

    private fun names(filter: AlbumFilter) = filter.apply(albums).map { it.name }

    @Test
    fun anEmptyFilterKeepsEverything() {
        val filter = AlbumFilter()
        assertTrue(filter.isEmpty)
        assertEquals(albums, filter.apply(albums))
        // Blanks and stray commas are still nothing to filter by
        assertTrue(AlbumFilter(include = " , ,").isEmpty)
    }

    @Test
    fun matchesNamesAsGlobs() {
        assertEquals(
            listOf("portalgo-kids", "portalgo-pets"),
            names(AlbumFilter(include = "portalgo-*")),
        )
        // A pattern without a wildcard is the whole name, not part of it
        assertEquals(emptyList<String>(), names(AlbumFilter(include = "portalgo")))
        assertEquals(listOf("Unsorted"), names(AlbumFilter(include = "Unsorted")))
        // Several patterns, and the case doesn't matter
        assertEquals(
            listOf("portalgo-pets", "Screenshots"),
            names(AlbumFilter(include = "PORTALGO-PETS, screen*")),
        )
        assertEquals(listOf("Holidays 2015"), names(AlbumFilter(include = "Holidays ????")))
    }

    @Test
    fun treatsTheRestOfThePatternLiterally() {
        val tricky = listOf(album("Trip (2019)"), album("Trip 2019"))
        assertEquals(
            listOf("Trip (2019)"),
            AlbumFilter(include = "Trip (2019)").apply(tricky).map { it.name },
        )
    }

    @Test
    fun excludeWinsOverInclude() {
        assertEquals(
            listOf("portalgo-kids", "portalgo-pets", "Holidays 2015", "Unsorted"),
            names(AlbumFilter(exclude = "Screenshots")),
        )
        assertEquals(
            listOf("portalgo-kids"),
            names(AlbumFilter(include = "portalgo-*", exclude = "*-pets")),
        )
    }

    @Test
    fun keepsAlbumsOverlappingTheYears() {
        assertEquals(
            listOf("portalgo-kids", "portalgo-pets", "Screenshots"),
            names(AlbumFilter(fromYear = 2019)),
        )
        assertEquals(
            listOf("portalgo-kids", "Holidays 2015"),
            names(AlbumFilter(toYear = 2019)),
        )
        assertEquals(
            listOf("portalgo-kids", "portalgo-pets"),
            names(AlbumFilter(fromYear = 2019, toYear = 2021)),
        )
        // An album spanning the whole range counts, even if neither end is in it
        val spanning = listOf(album("Everything", "2010-01-01T00:00:00.000Z", "2026-01-01T00:00:00.000Z"))
        assertEquals(1, AlbumFilter(fromYear = 2019, toYear = 2021).apply(spanning).size)
    }

    @Test
    fun dropsAlbumsWithoutDatesOnlyWhenAYearIsAsked() {
        assertTrue(AlbumFilter(include = "Unsorted").keeps(album("Unsorted")))
        assertFalse(AlbumFilter(fromYear = 2019).keeps(album("Unsorted")))
        assertFalse(AlbumFilter(toYear = 2019).keeps(album("Unsorted")))
        // One end is enough to place it
        assertTrue(AlbumFilter(fromYear = 2019).keeps(album("Half", to = "2020-01-01T00:00:00.000Z")))
    }
}
