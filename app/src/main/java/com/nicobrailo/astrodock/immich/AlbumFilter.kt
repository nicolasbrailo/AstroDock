package com.nicobrailo.astrodock.immich

// Which of the server's albums the slideshow takes pictures from.
//
// Immich can't do any of this server side: GET /albums only filters by exact
// name or by ownership, so there is nothing to ask it for. The album list is a
// single call that already carries the name and the dates of every album, so
// the choice is made here, on what that call returned, and costs no extra
// requests.
//
// Everything is optional, and an empty filter keeps every album, which is what
// a device that was never configured gets.
data class AlbumFilter(
    // Comma separated glob patterns ('*' is any run of characters, '?' is one).
    // An album is kept if its name matches any of them; empty keeps every name.
    val include: String = "",
    // The same, for albums to leave out. Checked after `include`, so it wins.
    val exclude: String = "",
    // Only albums holding pictures from these years, both ends included.
    // 0 means that end is open.
    val fromYear: Int = 0,
    val toYear: Int = 0,
) {
    private val includePatterns = globs(include)
    private val excludePatterns = globs(exclude)

    val isEmpty: Boolean
        get() = includePatterns.isEmpty() && excludePatterns.isEmpty() && fromYear == 0 && toYear == 0

    fun keeps(album: ImmichAlbum): Boolean {
        val name = album.name
        if (includePatterns.isNotEmpty() && includePatterns.none { it.matches(name) }) return false
        if (excludePatterns.any { it.matches(name) }) return false
        return inYearRange(album)
    }

    fun apply(albums: List<ImmichAlbum>): List<ImmichAlbum> =
        if (isEmpty) albums else albums.filter { keeps(it) }

    // True if the album's pictures overlap the wanted years. An album whose
    // dates the server didn't give is dropped as soon as either end is set:
    // there is no way to tell whether it belongs, and guessing "yes" would let
    // exactly the albums we know nothing about through.
    private fun inYearRange(album: ImmichAlbum): Boolean {
        if (fromYear == 0 && toYear == 0) return true
        val first = year(album.startDate) ?: year(album.endDate) ?: return false
        val last = year(album.endDate) ?: first
        if (fromYear != 0 && last < fromYear) return false
        if (toYear != 0 && first > toYear) return false
        return true
    }

    private companion object {
        // The year of an ISO 8601 timestamp, e.g. "2024-05-01T10:11:12.000Z"
        fun year(date: String): Int? = date.take(4).toIntOrNull()

        // Turns the comma separated patterns into case insensitive regexes that
        // have to match the whole name. Everything but '*' and '?' is literal,
        // so an album named "Trip (2019)" can be listed as it is written.
        fun globs(patterns: String): List<Regex> = patterns.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { pattern ->
                val regex = pattern.map { c ->
                    when (c) {
                        '*' -> ".*"
                        '?' -> "."
                        else -> Regex.escape(c.toString())
                    }
                }.joinToString("")
                Regex(regex, RegexOption.IGNORE_CASE)
            }
    }
}
