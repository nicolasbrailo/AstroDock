package com.nicobrailo.astrodock

import com.nicobrailo.astrodock.immich.AlbumPicture
import com.nicobrailo.astrodock.immich.ImmichPictureInfo
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

// Text about a picture, for the slideshow's overlay. Unknown fields are left out.

// One line, e.g. "2024 - Amsterdam"
fun pictureSummary(info: ImmichPictureInfo): String =
    listOf(year(info.taken), info.city.ifBlank { info.state }.ifBlank { info.country })
        .filter { it.isNotBlank() }
        .joinToString(" - ")

// One labelled line per known field: album, date, place, camera...
fun pictureDetails(picture: AlbumPicture, info: ImmichPictureInfo): String {
    val lines = mutableListOf<Pair<String, String>>()
    fun add(label: String, value: String) {
        if (value.isNotBlank()) lines += label to value
    }

    add("Album", picture.album.name)
    add("Taken", formatDate(info.taken))
    add("Place", listOf(info.city, info.state, info.country).filter { it.isNotBlank() }.distinct().joinToString(", "))
    if (info.latitude != null && info.longitude != null) {
        add("Coordinates", String.format(Locale.ROOT, "%.5f, %.5f", info.latitude, info.longitude))
    }
    // Models often repeat the make ("Canon" + "Canon EOS 5D")
    val camera = if (info.cameraModel.startsWith(info.cameraMake, ignoreCase = true)) {
        info.cameraModel
    } else {
        "${info.cameraMake} ${info.cameraModel}".trim()
    }
    add("Camera", camera)
    add("Lens", info.lens)
    add(
        "Exposure", listOfNotNull(
            info.fNumber?.let { "f/${formatNumber(it)}" },
            info.exposureTime.takeIf { it.isNotBlank() }?.let { "$it s" },
            info.iso?.let { "ISO $it" },
            info.focalLength?.let { "${formatNumber(it)} mm" },
        ).joinToString(" · ")
    )
    add("People", info.people.joinToString(", "))
    add("Description", info.description)
    add("File", info.fileName)
    return lines.joinToString("\n") { (label, value) -> "$label: $value" }
}

// "2024" from an ISO 8601 date, or "" if it doesn't start with a year
private fun year(isoDate: String): String =
    isoDate.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) }.orEmpty()

// "3 May 2024, 14:22" from an ISO 8601 date. localDateTime is the local time at
// the place the picture was taken, even though Immich marks it as UTC, so the
// time zone is ignored rather than converted.
private fun formatDate(isoDate: String): String = try {
    LocalDateTime.parse(isoDate.take(19)).format(DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.getDefault()))
} catch (e: DateTimeParseException) {
    isoDate
}

// 1.8 -> "1.8", 50.0 -> "50"
private fun formatNumber(n: Double): String =
    if (n == Math.rint(n)) n.toLong().toString() else String.format(Locale.ROOT, "%.1f", n)
