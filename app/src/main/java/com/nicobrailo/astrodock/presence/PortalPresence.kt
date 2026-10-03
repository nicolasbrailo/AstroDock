package com.nicobrailo.astrodock.presence

// One line of `logcat -v epoch`
data class LogLine(val at: Long, val tag: String, val message: String)

// What the Portal's own camera presence detection is doing, worked out from
// the lines it logs (see Presence.md). PortalLog feeds it every line it reads,
// in order, and the MQTT report asks it for a verdict. Pure, so it can be unit
// tested on lines copied from a device. Times are the log's own, wall-clock
// milliseconds, so a backlog read at startup is judged as it happened.
//
// Lines it doesn't recognise change nothing, so a firmware that words them
// differently leaves the verdict at "can't tell" or "nobody", never at a
// false "somebody".
//
// Used from the main thread only.
class PortalPresence(private val startedAt: Long) {
    // Why the camera can't see, when it can't, as the report names it
    enum class Blind(val wire: String) {
        // Meta's privacy mode: camera and microphone off
        PRIVACY("privacy"),

        // The physical cover over the lens: camera off, microphone on
        LENS_COVERED("lens_covered"),

        // The screen was turned off with the power button, which turns the
        // camera off with it, unlike a timeout
        SLEEP("sleep"),

        // Too early to say: nothing heard from the detection yet
        UNKNOWN("unknown"),
    }

    // occupied is null exactly when blind isn't
    data class Verdict(val occupied: Boolean?, val blind: Blind?)

    // A wake or a sleep: `cause` is one of the WAKE_* or SLEEP_* below,
    // `details` the power manager's own words for it
    data class ScreenChange(val cause: String, val at: Long, val details: String)

    // The last "Notify people presence", which comes every 30s while the
    // camera sees somebody
    var lastSeenAt: Long? = null
        private set

    // AMBIENT, STANDBY or SLEEP, as PresenceManager names it, null until it
    // changes while we're reading
    var portalState: String? = null
        private set
    // How the screen last came on and went off, from the power manager's own
    // lines, null until one is read
    var lastWake: ScreenChange? = null
        private set
    var lastSleep: ScreenChange? = null
        private set
    var lensCovered = false
        private set
    var privacy = false
        private set
    private var heard = false

    // Somebody demonstrably there (a touch, a button) with the camera able to
    // see and not reporting them: the first such moment since the last
    // report, and the latest
    private var unseenSince: Long? = null
    private var unseenLatest: Long? = null

    var verdict = Verdict(null, Blind.UNKNOWN)
        private set

    // When the verdict last changed, null until it first does
    var verdictSince: Long? = null
        private set

    fun onLine(line: LogLine) {
        // A gap in a backlog can hold an expiry, which happened before this line
        update(line.at)
        val message = line.message
        when (line.tag) {
            TAG_CAMERA -> if (message.contains(PEOPLE_SEEN)) {
                lastSeenAt = line.at
                heard = true
                unseenSince = null
            }
            TAG_PRESENCE -> {
                heard = true
                GLOBAL_STATE.find(message)?.let { portalState = it.groupValues[1] }
            }
            TAG_POWER -> {
                wake(message)?.let {
                    lastWake = ScreenChange(it.first, line.at, it.second)
                    if (it.first == WAKE_BUTTON) noteSomebody(line.at)
                }
                sleep(message)?.let { lastSleep = ScreenChange(it.first, line.at, it.second) }
            }
            TAG_SHUTTER -> SHUTTER.find(message)?.let { lensCovered = it.groupValues[1] != "0" }
            TAG_PRIVACY -> when {
                message.contains("[PRIVACY_ON]") || message.startsWith("enterPrivacyMode [true]") -> privacy = true
                message.contains("[PRIVACY_OFF]") || message.startsWith("exitPrivacyMode") -> privacy = false
            }
        }
        update(line.at)
    }

    // Brings the verdict up to `now`, for the reads that come with no line
    fun update(now: Long) {
        val next = verdictAt(now)
        if (next == verdict) return
        // The room went quiet when the last report expired, not when somebody
        // looked; anything else changed with the line that changed it
        verdictSince = if (verdict.occupied == true && next.occupied == false) {
            lastSeenAt?.plus(ABSENT_AFTER_MILLIS) ?: now
        } else {
            now
        }
        verdict = next
    }

    private fun verdictAt(now: Long): Verdict {
        val seen = lastSeenAt
        return when {
            privacy -> Verdict(null, Blind.PRIVACY)
            lensCovered -> Verdict(null, Blind.LENS_COVERED)
            portalState == SLEEP -> Verdict(null, Blind.SLEEP)
            seen != null && now - seen < ABSENT_AFTER_MILLIS -> Verdict(true, null)
            !heard && now - startedAt < ABSENT_AFTER_MILLIS -> Verdict(null, Blind.UNKNOWN)
            else -> Verdict(false, null)
        }
    }

    // Somebody is there, whatever the camera says: they touched the screen or
    // pressed a button. Evidence for `stuck`, ignored while the camera can't
    // see or is already reporting somebody.
    fun noteSomebody(at: Long) {
        update(at)
        if (verdict.occupied != false) {
            unseenSince = null
            return
        }
        if (unseenSince == null) unseenSince = at
        unseenLatest = at
    }

    // Whether the detection looks stuck, as it once was on a Portal+ for over
    // an hour until a reboot (Presence.md): somebody was demonstrably there at
    // least twice, STUCK_AFTER apart, with the camera able to see and not one
    // report in between. Twice and apart, so a single tap from somebody
    // standing out of frame isn't enough. Cleared by the next report.
    val stuck: Boolean
        get() {
            val since = unseenSince ?: return false
            val latest = unseenLatest ?: return false
            return verdict.blind == null && latest - since >= STUCK_AFTER_MILLIS
        }

    // When the verdict will change by itself if no line comes, or null
    fun nextChangeAt(now: Long): Long? {
        val seen = lastSeenAt
        return when {
            verdict.occupied == true && seen != null -> seen + ABSENT_AFTER_MILLIS
            verdict.blind == Blind.UNKNOWN && now - startedAt < ABSENT_AFTER_MILLIS -> startedAt + ABSENT_AFTER_MILLIS
            else -> null
        }
    }

    companion object {
        // Two missed reports and a margin: they come every 30s
        const val ABSENT_AFTER_MILLIS = 75_000L

        // Ten missed reports, with somebody there all along
        const val STUCK_AFTER_MILLIS = 5 * 60 * 1000L

        const val TAG_CAMERA = "aloha.CameraServiceController"
        const val TAG_PRESENCE = "PresenceManager"
        const val TAG_SHUTTER = "ShutterStateObserver"
        const val TAG_PRIVACY = "PrivacyModeManager"
        const val TAG_POWER = "PowerManagerService"

        // What woke the screen
        const val WAKE_PRESENCE = "presence"
        const val WAKE_BUTTON = "button"
        const val WAKE_SCREENSAVER_END = "screensaver_end"
        const val WAKE_APP = "app"

        // What put it to sleep. A lock is DevicePolicyManager.lockNow(),
        // ours (the night rule, force_off) or another device admin's.
        const val SLEEP_TIMEOUT = "timeout"
        const val SLEEP_BUTTON = "button"
        const val SLEEP_LOCK = "lock"
        const val SLEEP_OTHER = "other"

        // "Waking up from Dozing (uid=10068, reason=WAKE_REASON_APPLICATION, details=Full_Wakeup_PresenceManager)..."
        // on Android 10, "Waking up from dozing (uid=10004 reason=Full_Wakeup_PresenceManager)..."
        // on 9. The Portal's own "Waking up from screen off, start dreaming..."
        // that follows has no reason, and doesn't match.
        private val WAKE = Regex("""^Waking up from [^(]*\((uid=[^)]*)\)""")

        // "Going to sleep due to timeout (uid 1000)...", "... due to screen
        // timeout ..." on Android 9
        private val SLEEP_LINE = Regex("""^Going to sleep due to (.+?) \(uid""")

        // The cause of a wake line, with its details, or null for any other line
        fun wake(message: String): Pair<String, String>? {
            val details = WAKE.find(message)?.groupValues?.get(1) ?: return null
            val cause = when {
                details.contains("PresenceManager") -> WAKE_PRESENCE
                details.contains(":KEY") || details.contains(":POWER") || details.contains("POWER_BUTTON") -> WAKE_BUTTON
                details.contains("android.server.power:DREAM") -> WAKE_SCREENSAVER_END
                else -> WAKE_APP
            }
            return cause to details
        }

        fun sleep(message: String): Pair<String, String>? {
            val reason = SLEEP_LINE.find(message)?.groupValues?.get(1) ?: return null
            val cause = when {
                reason.contains("timeout") -> SLEEP_TIMEOUT
                reason.contains("power") -> SLEEP_BUTTON
                reason.contains("device_admin") || reason.contains("device admin") -> SLEEP_LOCK
                else -> SLEEP_OTHER
            }
            return cause to reason
        }

        // Everything PortalLog has to follow
        val TAGS = listOf(TAG_CAMERA, TAG_PRESENCE, TAG_SHUTTER, TAG_PRIVACY, TAG_POWER)

        private const val PEOPLE_SEEN = "Notify people presence"
        private const val SLEEP = "SLEEP"

        // "setCurrentGlobalState [AMBIENT] timeIn Previous State [STANDBY] [112] secs"
        private val GLOBAL_STATE = Regex("""setCurrentGlobalState \[(\w+)]""")

        // "shutter state is 1", 0 when open
        private val SHUTTER = Regex("""shutter state is (\d+)""")

        // "1791045491.004  2726  2790 I aloha.CameraServiceController: Notify people presence".
        // Some logcat versions pad the tag, hence the lazy match.
        private val LINE = Regex("""^\s*(\d+)\.(\d+)\s+\d+\s+\d+\s+[VDIWEFA]\s+(.*?)\s*: (.*)$""")

        fun parse(line: String): LogLine? {
            val m = LINE.matchEntire(line) ?: return null
            val (seconds, fraction, tag, message) = m.destructured
            return LogLine(seconds.toLong() * 1000 + fraction.padEnd(3, '0').take(3).toLong(), tag, message)
        }
    }
}
