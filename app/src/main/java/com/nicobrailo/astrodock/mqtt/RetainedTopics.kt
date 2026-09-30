package com.nicobrailo.astrodock.mqtt

// Which retained topics the reporter clears. MQTT has no way to delete by
// wildcard: a retained message only goes when an empty one replaces it, topic
// by topic, so the reporter reads what is retained under a prefix and picks
// from that.
object RetainedTopics {
    // The online/offline record, which also says whose the prefix is. It
    // used to be state/bridge, which is under state/ and so goes with the
    // rest of an older version's leftovers.
    const val AVAILABILITY = "availability"

    // What we publish under a prefix
    val OURS = listOf("state", AVAILABILITY, "state/displayed_photo")

    // Of the retained topics found under `prefix`, the ones to clear: our
    // record's topics (including the old state/bridge), and retained
    // commands, which are only ever replayed.
    // Nothing else, because a prefix can hold other things: "home/" also
    // matches another device publishing under "home/kitchen/", which would
    // otherwise lose everything it had. `keep` is left alone, being about to
    // be published again, and so is anything under `skipPrefix`, the prefix
    // we are moving to when it sits inside the old one.
    fun toClear(found: Set<String>, prefix: String, keep: Set<String> = emptySet(), skipPrefix: String? = null): Set<String> =
        found.filter { topic ->
            if (!topic.startsWith(prefix) || topic in keep) return@filter false
            if (skipPrefix != null && topic.startsWith(skipPrefix)) return@filter false
            val suffix = topic.removePrefix(prefix)
            suffix == "state" || suffix == AVAILABILITY || suffix.startsWith("state/") || suffix.startsWith("cmd/")
        }.toSet()

    fun ours(prefix: String): Set<String> = OURS.map { prefix + it }.toSet()
}
