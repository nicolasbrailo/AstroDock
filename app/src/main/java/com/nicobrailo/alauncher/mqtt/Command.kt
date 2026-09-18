package com.nicobrailo.alauncher.mqtt

// The commands from the homeboard bridge's spec that mean something here.
//
// Its other commands (set_svg_overlay, set_render_config, set_embed_qr,
// set_target_size) belong to the homeboard's own renderer and are dropped.
sealed interface Command {
    object Next : Command
    object Previous : Command
    object ForceOn : Command
    object ForceOff : Command
    data class TransitionSeconds(val seconds: Int) : Command
    // timeoutSeconds 0 means it stays until something replaces it; an empty
    // message clears whatever is on screen
    data class Announce(val message: String, val timeoutSeconds: Int) : Command
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
            else -> null
        }
    }
}
