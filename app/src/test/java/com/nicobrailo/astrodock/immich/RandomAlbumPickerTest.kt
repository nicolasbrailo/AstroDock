package com.nicobrailo.astrodock.immich

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID
import kotlin.random.Random

class RandomAlbumPickerTest {
    private class FakeSource(val albums: Map<ImmichAlbum, List<String>>) : AlbumSource {
        var albumListFetches = 0
        var failAlbumList = false

        override suspend fun listAlbums(): List<ImmichAlbum> {
            albumListFetches++
            if (failAlbumList) throw ImmichException("offline")
            return albums.keys.toList()
        }

        override suspend fun listAlbumPictures(albumId: String) =
            albums.entries.first { it.key.id == albumId }.value.map { ImmichPicture(it, "", "") }
    }

    private fun album(pictures: Int, assetCount: Int = pictures): Pair<ImmichAlbum, List<String>> {
        val id = UUID.randomUUID().toString()
        return ImmichAlbum(id, "album $id", assetCount) to List(pictures) { UUID.randomUUID().toString() }
    }

    @Test
    fun sampleSizeAppliesPercentThenCap() {
        val source = FakeSource(emptyMap())
        val both = RandomAlbumPicker(source, maxPictures = 20, percent = 50)
        assertEquals(15, both.sampleSize(30))
        assertEquals(20, both.sampleSize(100))
        assertEquals(1, both.sampleSize(1)) // Rounds to 0, but at least 1
        assertEquals(0, both.sampleSize(0))
        assertEquals(7, RandomAlbumPicker(source, 0, 0).sampleSize(7))
        assertEquals(2, RandomAlbumPicker(source, 0, 25).sampleSize(7)) // 1.75 rounds to 2
    }

    @Test
    fun picksWholeAlbumsInOrderAndVisitsEveryAlbumBeforeRepeating() = runTest {
        val albums = mapOf(album(5), album(3), album(4), album(0, assetCount = 0), album(0, assetCount = 2))
        val source = FakeSource(albums)
        val picker = RandomAlbumPicker(source, maxPictures = 2, percent = 0, random = Random(42))
        val byPicture = albums.flatMap { (a, pics) -> pics.map { it to a } }.toMap()
        val nonEmpty = albums.filterValues { it.isNotEmpty() }

        repeat(3) { // Rounds
            val seen = mutableSetOf<ImmichAlbum>()
            repeat(nonEmpty.size) {
                val first = picker.next().id
                val second = picker.next().id
                val a = byPicture.getValue(first)
                assertEquals(a, byPicture.getValue(second))
                // The sample keeps album order
                assertTrue(albums.getValue(a).indexOf(first) < albums.getValue(a).indexOf(second))
                assertTrue("Album repeated within a round", seen.add(a))
            }
        }
        assertEquals(1, source.albumListFetches)
    }

    @Test
    fun keepsOldAlbumListIfRefreshFails() = runTest {
        val source = FakeSource(mapOf(album(1)))
        val picker = RandomAlbumPicker(source, 0, 0)
        picker.next()
        source.failAlbumList = true
        picker.refresh()
        picker.next()
        picker.next()
        assertEquals(3, source.albumListFetches) // Retried at every album while failing
    }

    @Test(expected = ImmichException::class)
    fun failsIfNoAlbumHasPictures() = runTest {
        RandomAlbumPicker(FakeSource(mapOf(album(0, assetCount = 3))), 0, 0).next()
    }
}
