package com.nicobrailo.astrodock.immich

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

// A picture chosen by RandomAlbumPicker, and the album it was chosen from
data class AlbumPicture(val id: String, val album: ImmichAlbum)

// Port of libimmich-random's random.c: picks pictures album by album.
//
// From each album it samples:
//  - maxPictures: at most this many pictures. 0 means no limit.
//  - percent: this percentage of its pictures (rounded, but at least 1). 0
//    means 100.
// If both are set, the percentage is applied first and then capped: with
// maxPictures=20 and percent=50, an album of 30 pictures yields 15 and one of
// 100 yields 20.
//
// Synchronisation: `lock` serialises next(), which is the only function that
// touches the state below it (and the network). refresh() only sets `stale`, an
// atomic, so it never waits for a next() that is blocked on the network.
class RandomAlbumPicker(
    private val source: AlbumSource,
    private val maxPictures: Int,
    percent: Int,
    private val random: Random = Random.Default,
) {
    private val percent: Int = if (percent == 0) 100 else percent

    init {
        require(maxPictures >= 0) { "Invalid max pictures: $maxPictures" }
        require(percent in 0..100) { "Invalid percentage: $percent" }
    }

    private val stale = AtomicBoolean(true) // The album list needs to be (re-)fetched
    private val lock = Mutex()

    // Protected by lock
    private var albums: List<ImmichAlbum>? = null
    // Albums with assets, in the random order they are visited this round.
    // orderPos is the next one to visit.
    private var order: MutableList<ImmichAlbum> = mutableListOf()
    private var orderPos = 0
    private var lastAlbumId = ""
    // Pictures sampled from the current album. samplePos is the next one to return.
    private var sample: List<AlbumPicture> = emptyList()
    private var samplePos = 0

    // Returns the next picture to show. Its ID is for ImmichClient.pictureUrl().
    // Pictures come album by album: an album is picked at random and sampled,
    // its sampled pictures are returned in album order (oldest first), then the
    // next album is picked. Every album is visited once, in random order, before
    // any album repeats. The album list is fetched on the first call and cached;
    // the pictures of an album are fetched when it's picked.
    //
    // Throws ImmichException if no picture could be picked (e.g. the server is
    // unreachable, or no album has any pictures). Calls are serialised.
    suspend fun next(): AlbumPicture = lock.withLock {
        if (samplePos == sample.size) {
            nextAlbum()
        }
        sample[samplePos++]
    }

    // Marks the cached album list as stale. It is re-fetched when the next album
    // is picked; pictures already sampled from the current album are still
    // returned first. If the re-fetch fails, the old list is kept and the fetch
    // is retried at the next album. Never blocks; callable from any thread.
    fun refresh() {
        stale.set(true)
    }

    // Fetches the album list if it's stale (always the case on first use). If a
    // re-fetch fails, keeps the old list and leaves it stale so the fetch is
    // retried next time. Throws only if there is no list at all.
    private suspend fun updateAlbums() {
        if (!stale.getAndSet(false)) return

        val fetched = try {
            source.listAlbums()
        } catch (e: Exception) {
            // Also on cancellation, or the list would never be fetched
            stale.set(true)
            if (e !is ImmichException || albums == null) throw e
            Log.w(TAG, "Using the previous album list: ${e.message}")
            return
        }

        albums = fetched
        order = fetched.filter { it.assetCount > 0 }.toMutableList()
        orderPos = order.size // Makes the next pick start a new round
    }

    // Starts a new round over all albums, in a random order that doesn't begin
    // with the album that was just shown
    private fun shuffleAlbums() {
        order.shuffle(random)
        if (order.size > 1 && order[0].id == lastAlbumId) {
            order[0] = order[1].also { order[1] = order[0] }
        }
        orderPos = 0
    }

    // How many of an album's n pictures to show. The percentage is applied first
    // (rounded, but at least 1 so small albums aren't skipped), then the cap.
    internal fun sampleSize(n: Int): Int {
        var k = n
        if (percent < 100) {
            k = (n * percent + 50) / 100
            if (k == 0 && n > 0) k = 1
        }
        if (maxPictures > 0 && k > maxPictures) k = maxPictures
        return k
    }

    // Replaces the current sample with a new one from album. The sample may be
    // empty if the album has no images.
    private suspend fun sampleAlbum(album: ImmichAlbum) {
        val pics = source.listAlbumPictures(album.id)
        val k = sampleSize(pics.size)

        // Selection sampling (Knuth's Algorithm S): walk the album once and keep
        // each picture with probability (still needed) / (still left). Every
        // subset of k pictures is equally likely, and the sample keeps the
        // album's order. Once still needed == still left the probability is 1,
        // so it always ends with exactly k pictures.
        val selected = ArrayList<AlbumPicture>(k)
        for ((i, pic) in pics.withIndex()) {
            if (selected.size == k) break
            if (random.nextInt(pics.size - i) >= k - selected.size) continue

            if (!ImmichClient.isValidId(pic.id)) {
                throw ImmichException(
                    "Album ${album.id}: picture has an unexpected ID \"${pic.id}\" " +
                        "(expected a UUID). Has the Immich ID format changed?"
                )
            }
            selected += AlbumPicture(pic.id, album)
        }

        sample = selected
        samplePos = 0
    }

    // Moves on to the next album that has images and samples it
    private suspend fun nextAlbum() {
        updateAlbums()

        // Albums with only videos yield no images. Try each album at most once,
        // so this gives up instead of looping forever if none has any.
        repeat(order.size) {
            if (orderPos == order.size) shuffleAlbums()
            val album = order[orderPos++]
            if (!ImmichClient.isValidId(album.id)) {
                throw ImmichException(
                    "Album \"${album.name}\" has an unexpected ID \"${album.id}\" " +
                        "(expected a UUID). Has the Immich ID format changed?"
                )
            }
            lastAlbumId = album.id
            Log.i(TAG, "Showing album \"${album.name}\"")
            sampleAlbum(album)
            if (sample.isNotEmpty()) return
        }

        throw ImmichException("No album has any pictures")
    }

    private companion object {
        const val TAG = "RandomAlbumPicker"
    }
}
