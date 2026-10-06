package com.nicobrailo.astrodock.doorbell

// The doorbell ring (cmd/doorbell/ring from zmw_homeboard) carries the door
// camera's streams by name, as the camera service calls them: "main" (2560x1920
// on the Reolink) and "sub" (640x480). BatiDoorLink plays one of them.
object Doorbell {
    const val VIEWER_PACKAGE = "com.nicobrailo.batidoorlink"

    // Best first. The sub stream, because it is what BatiDoorLink was tested
    // with on a Portal, and its keyframe is a fraction of the main one's; the
    // main stream also played on the Portal+, but no Go has decoded it yet.
    private val STREAM_PREFERENCE = listOf("sub", "main")

    // The stream to show, or null for none worth trying. A name we don't know
    // still beats nothing.
    fun pickStream(urls: Map<String, String>): String? {
        val usable = urls.filterValues { it.startsWith("rtsp://", ignoreCase = true) }
        return STREAM_PREFERENCE.firstNotNullOfOrNull { usable[it] } ?: usable.values.firstOrNull()
    }
}
