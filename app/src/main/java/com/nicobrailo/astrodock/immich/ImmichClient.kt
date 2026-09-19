package com.nicobrailo.astrodock.immich

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener
import java.io.IOException
import java.util.concurrent.TimeUnit

// Port of libimmich-random's client.c and browse.c (albums, album pictures and
// picture metadata).
// Every failure (transport, HTTP status, unexpected JSON) is an ImmichException.

class ImmichException(message: String, cause: Throwable? = null) : IOException(message, cause)

// What RandomAlbumPicker needs from the server. An interface so tests can fake it.
interface AlbumSource {
    suspend fun listAlbums(): List<ImmichAlbum>
    suspend fun listAlbumPictures(albumId: String): List<ImmichPicture>
}

data class ImmichAlbum(val id: String, val name: String, val assetCount: Int)

data class ImmichPicture(
    val id: String,
    val taken: String, // localDateTime: when it was taken, local time, ISO 8601
    val fileName: String,
)

// Metadata for one picture. Strings are "" and numbers null if unknown.
data class ImmichPictureInfo(
    val id: String,
    val fileName: String,
    val originalPath: String, // Where the original is stored on the server
    val mimeType: String,
    val type: String,     // "IMAGE", "VIDEO", ...
    val taken: String,    // localDateTime: when it was taken, local time, ISO 8601
    val takenUtc: String, // fileCreatedAt: when it was taken, UTC, ISO 8601
    val width: Int?,
    val height: Int?,
    val isFavorite: Boolean,

    // From EXIF
    val description: String,
    val city: String,
    val state: String,
    val country: String,
    val latitude: Double?,  // Both set or both null
    val longitude: Double?,
    val cameraMake: String,
    val cameraModel: String,
    val lens: String,
    val fNumber: Double?,
    val focalLength: Double?, // mm
    val exposureTime: String, // e.g. "1/250"
    val iso: Int?,
    val rating: Int?,

    val people: List<String>, // Names of recognised people; unnamed faces are skipped
)

enum class ImmichPictureSize {
    THUMBNAIL, // Small thumbnail (~250px)
    PREVIEW,   // Display size (~1440px), JPEG or WebP
    ORIGINAL,  // As uploaded (may be HEIC, RAW, ...). Needs asset.download on the API key
}

// host is the server's base URL, e.g. "http://bati.casa:2222" (a trailing '/'
// is fine). The API key needs album.read, asset.read and asset.view.
// Safe to use from several coroutines at once.
class ImmichClient(host: String, val apiKey: String) : AlbumSource {
    val baseUrl = host.trimEnd('/') + "/api"

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // Abort transfers that stall for 30s, without capping how long a large
        // download may take
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Fetches every album visible to the API key
    override suspend fun listAlbums(): List<ImmichAlbum> {
        val albums = getJson("/albums") as? JSONArray
            ?: throw ImmichException("Unexpected /albums response: not an array")
        return (0 until albums.length()).map { i ->
            val album = albums.getJSONObject(i)
            ImmichAlbum(
                id = album.str("id"),
                name = album.str("albumName"),
                assetCount = album.optInt("assetCount", 0),
            )
        }
    }

    // Fetches every image (no videos) in an album, oldest first, following
    // pagination
    override suspend fun listAlbumPictures(albumId: String): List<ImmichPicture> {
        val req = JSONObject()
            .put("size", 1000)
            .put(
                "filter", JSONObject()
                    .put("albumIds", JSONObject().put("any", JSONArray().put(albumId)))
                    .put("type", JSONObject().put("eq", "IMAGE"))
            )
            // Oldest first: the default is newest first. fileCreatedAt is when the
            // picture was taken, in UTC, so this is chronological across time zones.
            .put("orderBy", JSONObject().put("field", "fileCreatedAt").put("direction", "asc"))

        val out = ArrayList<ImmichPicture>()
        while (true) {
            val resp = postJson("/search/metadata", req) as? JSONObject
            val assets = resp?.optJSONObject("assets")
            val items = assets?.optJSONArray("items")
                ?: throw ImmichException("Unexpected /search/metadata response: no assets.items")
            for (i in 0 until items.length()) {
                val asset = items.getJSONObject(i)
                out += ImmichPicture(
                    id = asset.str("id"),
                    taken = asset.str("localDateTime"),
                    fileName = asset.str("originalFileName"),
                )
            }
            // nextCursor is null on the last page. Otherwise send it back as "cursor".
            val next = assets.opt("nextCursor") as? String ?: return out
            req.put("cursor", next)
        }
    }

    // Fetches the metadata of one picture
    suspend fun getPictureMetadata(pictureId: String): ImmichPictureInfo {
        // The ID goes into the URL path, so only accept a UUID
        if (!isValidId(pictureId)) throw ImmichException("Invalid picture ID: $pictureId")
        val path = "/assets/$pictureId"
        val asset = getJson(path) as? JSONObject
            ?: throw ImmichException("Unexpected $path response: not an object")

        // Missing if there is no EXIF; then every field is unknown
        val exif = asset.optJSONObject("exifInfo") ?: JSONObject()
        val lat = exif.double("latitude")
        val lon = exif.double("longitude")
        val hasLocation = lat != null && lon != null
        val people = asset.optJSONArray("people")
        return ImmichPictureInfo(
            id = asset.str("id"),
            fileName = asset.str("originalFileName"),
            originalPath = asset.str("originalPath"),
            mimeType = asset.str("originalMimeType"),
            type = asset.str("type"),
            taken = asset.str("localDateTime"),
            takenUtc = asset.str("fileCreatedAt"),
            width = asset.int("width"),
            height = asset.int("height"),
            isFavorite = asset.optBoolean("isFavorite", false),
            description = exif.str("description"),
            city = exif.str("city"),
            state = exif.str("state"),
            country = exif.str("country"),
            latitude = if (hasLocation) lat else null,
            longitude = if (hasLocation) lon else null,
            cameraMake = exif.str("make"),
            cameraModel = exif.str("model"),
            lens = exif.str("lensModel"),
            fNumber = exif.double("fNumber"),
            focalLength = exif.double("focalLength"),
            exposureTime = exif.str("exposureTime"),
            iso = exif.int("iso"),
            rating = exif.int("rating"),
            people = (0 until (people?.length() ?: 0))
                .map { people!!.optJSONObject(it)?.str("name").orEmpty() }
                .filter { it.isNotEmpty() },
        )
    }

    // URL that serves the picture at the given size. Requests to it need the
    // x-api-key header (see API_KEY_HEADER).
    fun pictureUrl(pictureId: String, size: ImmichPictureSize): String {
        // The ID goes into the URL path, so only accept a UUID
        if (!isValidId(pictureId)) throw ImmichException("Invalid picture ID: $pictureId")
        return when (size) {
            ImmichPictureSize.THUMBNAIL -> "$baseUrl/assets/$pictureId/thumbnail?size=thumbnail"
            ImmichPictureSize.PREVIEW -> "$baseUrl/assets/$pictureId/thumbnail?size=preview"
            ImmichPictureSize.ORIGINAL -> "$baseUrl/assets/$pictureId/original"
        }
    }

    private suspend fun getJson(path: String): Any = request(path, null)

    private suspend fun postJson(path: String, body: JSONObject): Any = request(path, body)

    private suspend fun request(path: String, body: JSONObject?): Any = withContext(Dispatchers.IO) {
        val method = if (body == null) "GET" else "POST"
        val url = baseUrl + path
        val builder = try {
            Request.Builder().url(url)
        } catch (e: IllegalArgumentException) {
            throw ImmichException("Invalid server URL: $url", e)
        }
        builder.header(API_KEY_HEADER, apiKey).header("Accept", "application/json")
        if (body != null) {
            builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        }

        try {
            http.newCall(builder.build()).execute().use { resp ->
                val text = resp.body.string()
                if (!resp.isSuccessful) {
                    throw ImmichException("$method $url: HTTP ${resp.code}: $text")
                }
                try {
                    JSONTokener(text).nextValue()
                } catch (e: JSONException) {
                    throw ImmichException("$method $url: invalid JSON response: ${e.message}", e)
                }
            }
        } catch (e: ImmichException) {
            throw e
        } catch (e: IOException) {
            throw ImmichException("$method $url failed: ${e.message}", e)
        }
    }

    companion object {
        const val API_KEY_HEADER = "x-api-key"

        // Size of an Immich ID (a UUID): 32 hex digits and 4 hyphens
        private const val ID_LENGTH = 36

        // True if id looks like an Immich ID (a UUID). Check IDs before putting
        // them into a URL path.
        fun isValidId(id: String): Boolean =
            id.length == ID_LENGTH && id.all { it == '-' || it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }
}

// obj[key] as a string, "" if missing or null (org.json's optString turns null into "null")
private fun JSONObject.str(key: String): String = if (isNull(key)) "" else optString(key)

// obj[key] as a number, null if missing, null or not a number
private fun JSONObject.double(key: String): Double? =
    if (isNull(key)) null else optDouble(key).takeUnless { it.isNaN() }

private fun JSONObject.int(key: String): Int? = double(key)?.toInt()
