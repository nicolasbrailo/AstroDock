package com.nicobrailo.astrodock

import com.nicobrailo.astrodock.immich.AlbumPicture
import com.nicobrailo.astrodock.immich.ImmichAlbum
import com.nicobrailo.astrodock.immich.ImmichPictureInfo
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class PictureDescriptionTest {
    private val unknown = ImmichPictureInfo(
        id = "", fileName = "", originalPath = "", mimeType = "", type = "", taken = "", takenUtc = "",
        width = null, height = null, isFavorite = false, description = "", city = "", state = "",
        country = "", latitude = null, longitude = null, cameraMake = "", cameraModel = "", lens = "",
        fNumber = null, focalLength = null, exposureTime = "", iso = null, rating = null, people = emptyList(),
    )
    private val album = AlbumPicture("id", ImmichAlbum("album-id", "Holidays", 3))

    @Test
    fun summaryIsYearAndMostSpecificPlace() {
        val info = unknown.copy(taken = "2024-05-03T14:22:11.000Z", city = "Amsterdam", country = "Netherlands")
        assertEquals("2024 - Amsterdam", pictureSummary(info))
        assertEquals("2024 - Netherlands", pictureSummary(info.copy(city = "")))
        assertEquals("2024", pictureSummary(info.copy(city = "", country = "")))
        assertEquals("Amsterdam", pictureSummary(info.copy(taken = "")))
        assertEquals("", pictureSummary(unknown))
    }

    @Test
    fun detailsListOnlyKnownFields() {
        Locale.setDefault(Locale.UK)
        val info = unknown.copy(
            taken = "2024-05-03T14:22:11.000Z",
            city = "Amsterdam", state = "North Holland", country = "Netherlands",
            latitude = 52.3740312, longitude = 4.8896901,
            cameraMake = "Canon", cameraModel = "Canon EOS 5D",
            fNumber = 1.8, exposureTime = "1/250", iso = 100, focalLength = 50.0,
            people = listOf("Ana", "Bob"),
            fileName = "IMG_1.jpg",
        )
        assertEquals(
            """
            Album: Holidays
            Taken: 3 May 2024, 14:22
            Place: Amsterdam, North Holland, Netherlands
            Coordinates: 52.37403, 4.88969
            Camera: Canon EOS 5D
            Exposure: f/1.8 · 1/250 s · ISO 100 · 50 mm
            People: Ana, Bob
            File: IMG_1.jpg
            """.trimIndent(),
            pictureDetails(album, info),
        )
        assertEquals("Album: Holidays", pictureDetails(album, unknown))
    }
}
