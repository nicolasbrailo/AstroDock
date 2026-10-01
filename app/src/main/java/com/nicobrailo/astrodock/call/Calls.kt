package com.nicobrailo.astrodock.call

import kotlin.math.abs

// The rules of calling between devices (see CALLING.md), as pure functions so
// they can be unit tested. CallRouter applies them; the JSON is parsed in
// StateReporter, since org.json is a stub in unit tests.

// What one device can say to another about a call. Each is a command published
// to the recipient's prefix, <prefix>cmd/call/<topic>, so a device only ever
// listens to its own cmd/# (Commands.kind recognises them).
enum class CallVerb(val topic: String) {
    OFFER("offer"),
    ANSWER("answer"),
    REJECT("reject"),
    HANGUP("hangup"),
}

// Why a call wasn't taken, as sent in cmd/call/reject, so the caller can say so
enum class RejectReason(val wire: String) {
    // Calling is off on the callee
    DISABLED("disabled"),
    // The caller isn't on the callee's list
    NOT_ALLOWED("not_allowed"),
    // The callee can't use its camera or microphone
    UNAVAILABLE("unavailable"),
    // The callee's night rule applies
    NIGHT("night"),
    // The callee is in another call
    BUSY("busy"),
    // Someone at the callee turned the call down while it rang
    DECLINED("declined");

    companion object {
        fun ofWire(wire: String): RejectReason? = entries.firstOrNull { it.wire == wire }
    }
}

// Where a call is at, as the state record reports it
enum class CallPhase(val wire: String) {
    // We called and are waiting for the other side's media
    OUTGOING("outgoing"),
    // We were called, and are ringing or setting up our side
    INCOMING("incoming"),
    // Media is flowing
    IN_CALL("in_call"),
}

// Another device on the broker, from its retained availability record
data class CallPeer(
    val prefix: String,
    val online: Boolean,
    val acceptsCalls: Boolean,
) {
    val name: String get() = Calls.displayName(prefix)
}

object Calls {
    const val TOPIC_PREFIX = "cmd/call/"

    // An offer older than this is dropped: nothing should turn a camera on for
    // a call nobody is making any more. The devices' clocks are set over the
    // network, so they agree to well within this.
    const val OFFER_MAX_AGE_SECONDS = 15L

    // What a device is called on screen: its topic prefix, which is the name
    // it was given at setup, made fit for a topic. The availability record's
    // hostname is the Bluetooth name, which on a Portal+ is the model.
    fun displayName(prefix: String): String = prefix.trimEnd('/')

    // Where to send `verb` for the device under `prefix`
    fun topic(prefix: String, verb: CallVerb): String = prefix + TOPIC_PREFIX + verb.topic

    // Stale either way: an offer from the future means a clock is wrong, and
    // then its age says nothing
    fun isStale(sentAtSeconds: Long, nowSeconds: Long): Boolean =
        abs(nowSeconds - sentAtSeconds) > OFFER_MAX_AGE_SECONDS

    // Why an offer can't be taken, or null if it can. The order is what the
    // caller is told when several apply: the ones that won't change by calling
    // again later come first.
    fun refusal(
        enabled: Boolean,
        allowed: Boolean,
        canCapture: Boolean,
        night: Boolean,
        busy: Boolean,
    ): RejectReason? = when {
        !enabled -> RejectReason.DISABLED
        !allowed -> RejectReason.NOT_ALLOWED
        !canCapture -> RejectReason.UNAVAILABLE
        night -> RejectReason.NIGHT
        busy -> RejectReason.BUSY
        else -> null
    }

    // The callers a device takes calls from, as typed in the settings: comma
    // separated topic prefixes, with or without their trailing slash, any
    // case. Empty means any device on the broker.
    fun parseAllowList(raw: String): Set<String> =
        raw.split(',').map { normalize(it) }.filter { it.isNotEmpty() }.toSet()

    fun isAllowed(allowList: Set<String>, callerPrefix: String): Boolean =
        allowList.isEmpty() || normalize(callerPrefix) in allowList

    private fun normalize(prefix: String): String = prefix.trim().trim('/').lowercase()

    // The prefix an availability topic belongs to, for the one-level prefixes
    // `+/availability` finds, or null for anything else
    fun peerPrefix(topic: String, availability: String): String? {
        if (!topic.endsWith("/$availability")) return null
        val prefix = topic.removeSuffix(availability)
        // One level: "kitchen/" and not "home/kitchen/"
        if (prefix.length < 2 || prefix.dropLast(1).contains('/')) return null
        return prefix
    }

    // The devices this one can call: online, taking calls, and not itself,
    // by name
    fun callable(peers: Collection<CallPeer>, ownPrefix: String?): List<CallPeer> =
        peers.filter { it.online && it.acceptsCalls && it.prefix != ownPrefix }
            .sortedBy { it.name.lowercase() }
}
