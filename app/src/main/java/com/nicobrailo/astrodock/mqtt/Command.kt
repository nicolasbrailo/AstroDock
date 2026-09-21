package com.nicobrailo.astrodock.mqtt

import com.nicobrailo.astrodock.immich.AlbumFilter

// The commands from the homeboard bridge's spec that mean something here.
//
// Its other commands (set_svg_overlay, set_render_config, set_embed_qr,
// set_target_size) belong to the homeboard's own renderer and are dropped.
// One of these is not from the spec: set_album_filter, which chooses the albums
// the slideshow takes pictures from (see AlbumFilter). The homeboard reads its
// pictures from disk, so it has nothing to say about Immich albums.
sealed interface Command {
    object Next : Command
    object Previous : Command
    object ForceOn : Command
    object ForceOff : Command
    data class TransitionSeconds(val seconds: Int) : Command
    // timeoutSeconds 0 means it stays until something replaces it; an empty
    // message clears whatever is on screen. owner is for whoever needs to end
    // it later (see EndAnnouncement); plain announcements have none.
    data class Announce(val message: String, val timeoutSeconds: Int, val owner: Any? = null) : Command
    // Takes the announcement down afterSeconds from now, but only if it is
    // still the one owner put up: anything shown since has taken its place
    data class EndAnnouncement(val owner: Any, val afterSeconds: Int) : Command
    // An audio file to fetch and play. volumePercent is the media volume to
    // play it at, 0 to 100; message is the text shown while it plays, null
    // for none given.
    data class AnnounceAudio(val uri: String, val message: String?, val volumePercent: Int) : Command
    // The whole filter, so whatever the payload leaves out is cleared
    data class SetAlbumFilter(val filter: AlbumFilter) : Command
}

// Which command a topic asks for. The payload is parsed separately, because
// parsing JSON needs Android's org.json.
enum class CommandKind {
    NEXT,
    PREVIOUS,
    FORCE_ON,
    FORCE_OFF,
    TRANSITION_SECONDS,
    ANNOUNCE,
    ANNOUNCE_AUDIO,
    ALBUM_FILTER,
}

object Commands {
    // Everything below the prefix; the broker filters, we recognise
    const val TOPIC_FILTER = "cmd/#"

    fun kind(topic: String, topicPrefix: String): CommandKind? {
        if (!topic.startsWith(topicPrefix)) return null
        return when (topic.removePrefix(topicPrefix)) {
            "cmd/ambience/next" -> CommandKind.NEXT
            "cmd/ambience/prev" -> CommandKind.PREVIOUS
            "cmd/presence/force_on" -> CommandKind.FORCE_ON
            "cmd/presence/force_off" -> CommandKind.FORCE_OFF
            "cmd/ambience/set_transition_time_secs" -> CommandKind.TRANSITION_SECONDS
            "cmd/ambience/announce" -> CommandKind.ANNOUNCE
            "cmd/ambience/announce_audio" -> CommandKind.ANNOUNCE_AUDIO
            "cmd/ambience/set_album_filter" -> CommandKind.ALBUM_FILTER
            else -> null
        }
    }
}
