package com.nicobrailo.astrodock.presence

import com.nicobrailo.astrodock.presence.PortalPresence.Blind
import com.nicobrailo.astrodock.presence.PortalPresence.Companion.ABSENT_AFTER_MILLIS
import com.nicobrailo.astrodock.presence.PortalPresence.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The lines are as a Portal Go and a Portal+ logged them on 2026-10-03
// (Presence.md)
class PortalPresenceTest {
    private val start = 1_791_045_000_000L
    private val somebody = Verdict(true, null)
    private val nobody = Verdict(false, null)

    private fun line(at: Long, tag: String, message: String) = LogLine(at, tag, message)
    private fun seen(at: Long) = line(at, PortalPresence.TAG_CAMERA, "Notify people presence")
    private fun state(at: Long, state: String) = line(
        at, PortalPresence.TAG_PRESENCE, "setCurrentGlobalState [$state] timeIn Previous State [AMBIENT] [31] secs"
    )

    @Test
    fun parsesAnEpochLine() {
        assertEquals(
            LogLine(1_791_045_491_004, "aloha.CameraServiceController", "Notify people presence"),
            PortalPresence.parse("1791045491.004  2726  2790 I aloha.CameraServiceController: Notify people presence"),
        )
        assertEquals(
            LogLine(1_791_047_196_500, "ShutterStateObserver", "shutter state is 1"),
            PortalPresence.parse("  1791047196.5  1394  1394 V ShutterStateObserver : shutter state is 1"),
        )
        assertNull(PortalPresence.parse("--------- beginning of main"))
    }

    @Test
    fun unknownUntilItHearsOrHasWaitedLongEnough() {
        val p = PortalPresence(start)
        p.update(start + 1000)
        assertEquals(Verdict(null, Blind.UNKNOWN), p.verdict)
        assertEquals(start + ABSENT_AFTER_MILLIS, p.nextChangeAt(start + 1000))
        p.update(start + ABSENT_AFTER_MILLIS)
        assertEquals(nobody, p.verdict)
    }

    @Test
    fun somebodyWhileReportsComeAndNobodyOnceTheyStop() {
        val p = PortalPresence(start)
        p.onLine(seen(start + 1000))
        assertEquals(somebody, p.verdict)
        assertEquals(start + 1000, p.verdictSince)
        p.onLine(seen(start + 31_000))
        assertEquals(start + 1000, p.verdictSince)
        assertEquals(start + 31_000 + ABSENT_AFTER_MILLIS, p.nextChangeAt(start + 40_000))

        p.update(start + 31_000 + ABSENT_AFTER_MILLIS - 1)
        assertEquals(somebody, p.verdict)
        // Noticed late, but dated when the last report expired
        p.update(start + 500_000)
        assertEquals(nobody, p.verdict)
        assertEquals(start + 31_000 + ABSENT_AFTER_MILLIS, p.verdictSince)
        assertNull(p.nextChangeAt(start + 500_000))
    }

    @Test
    fun aBacklogIsJudgedAsItHappened() {
        // An hour of log read at startup: somebody, then a long gap, then
        // somebody again. The gap was an absence, dated as it happened.
        val p = PortalPresence(start + 3_600_000)
        p.onLine(seen(start))
        p.onLine(seen(start + 600_000))
        assertEquals(somebody, p.verdict)
        assertEquals(start + 600_000, p.verdictSince)
    }

    @Test
    fun theCoverBlindsTheCameraUntilItOpens() {
        val p = PortalPresence(start)
        p.onLine(seen(start + 1000))
        p.onLine(line(start + 2000, PortalPresence.TAG_SHUTTER, "shutter state is 1"))
        assertEquals(Verdict(null, Blind.LENS_COVERED), p.verdict)
        // Blind for longer than a report lasts: still blind, not nobody
        p.update(start + 600_000)
        assertEquals(Verdict(null, Blind.LENS_COVERED), p.verdict)
        p.onLine(line(start + 600_000, PortalPresence.TAG_SHUTTER, "shutter state is 0"))
        assertEquals(nobody, p.verdict)
        p.onLine(seen(start + 600_500))
        assertEquals(somebody, p.verdict)
    }

    @Test
    fun privacyModeBlindsTheCamera() {
        val p = PortalPresence(start)
        p.onLine(line(start, PortalPresence.TAG_PRIVACY, "enterPrivacyMode [true]"))
        assertEquals(Verdict(null, Blind.PRIVACY), p.verdict)
        assertEquals(true, p.privacy)
        p.onLine(line(start + 1000, PortalPresence.TAG_PRIVACY, "onPlatformStateChange listener [PRIVACY_OFF]"))
        assertEquals(false, p.privacy)

        p.onLine(line(start + 2000, PortalPresence.TAG_PRIVACY, "onPlatformStateChange listener [PRIVACY_ON]"))
        assertEquals(true, p.privacy)
        p.onLine(line(start + 3000, PortalPresence.TAG_PRIVACY, "exitPrivacyMode"))
        assertEquals(false, p.privacy)
        // "enterPrivacyMode" alone comes before the outcome, which is the [true]
        p.onLine(line(start + 4000, PortalPresence.TAG_PRIVACY, "enterPrivacyMode"))
        assertEquals(false, p.privacy)
    }

    @Test
    fun thePowerButtonsSleepIsBlindButAStandbyIsNot() {
        val p = PortalPresence(start)
        p.onLine(state(start, "SLEEP"))
        assertEquals(Verdict(null, Blind.SLEEP), p.verdict)
        assertEquals("SLEEP", p.portalState)
        p.onLine(state(start + 1000, "AMBIENT"))
        p.onLine(state(start + 2000, "STANDBY"))
        p.update(start + 2000 + ABSENT_AFTER_MILLIS)
        assertEquals(nobody, p.verdict)
    }

    @Test
    fun namesWhatWokeTheScreen() {
        fun cause(message: String) = PortalPresence.wake(message)?.first
        assertEquals(
            PortalPresence.WAKE_PRESENCE,
            cause("Waking up from Dozing (uid=10068, reason=WAKE_REASON_APPLICATION, details=Full_Wakeup_PresenceManager)..."),
        )
        // Android 9's wording
        assertEquals(PortalPresence.WAKE_PRESENCE, cause("Waking up from dozing (uid=10004 reason=Full_Wakeup_PresenceManager)..."))
        assertEquals(PortalPresence.WAKE_BUTTON, cause("Waking up from sleep (uid=1000 reason=android.policy:KEY)..."))
        assertEquals(
            PortalPresence.WAKE_BUTTON,
            cause("Waking up from Asleep (uid=1000, reason=WAKE_REASON_POWER_BUTTON, details=android.policy:POWER)..."),
        )
        assertEquals(
            PortalPresence.WAKE_SCREENSAVER_END,
            cause("Waking up from dream (uid=1000 reason=android.server.power:DREAM)..."),
        )
        assertEquals(PortalPresence.WAKE_APP, cause("Waking up from Asleep (uid=10123, reason=WAKE_REASON_APPLICATION, details=astrodock:force_on)..."))
        // The Portal's own line that follows a wake says nothing about it
        assertNull(cause("Waking up from screen off, start dreaming..."))
        assertEquals("uid=1000 reason=android.policy:KEY", PortalPresence.wake("Waking up from sleep (uid=1000 reason=android.policy:KEY)...")?.second)
    }

    @Test
    fun namesWhatPutItToSleep() {
        fun cause(message: String) = PortalPresence.sleep(message)?.first
        assertEquals(PortalPresence.SLEEP_TIMEOUT, cause("Going to sleep due to timeout (uid 1000)..."))
        assertEquals(PortalPresence.SLEEP_TIMEOUT, cause("Going to sleep due to screen timeout (uid 1000)..."))
        assertEquals(PortalPresence.SLEEP_BUTTON, cause("Going to sleep due to power_button (uid 1000)..."))
        assertEquals(PortalPresence.SLEEP_LOCK, cause("Going to sleep due to device_admin (uid 10123)..."))
        assertEquals(PortalPresence.SLEEP_LOCK, cause("Going to sleep due to device admin (uid 10123)..."))
        assertEquals(PortalPresence.SLEEP_OTHER, cause("Going to sleep due to application (uid 10123)..."))
        assertNull(cause("Sleeping (uid 1000)..."))
    }

    @Test
    fun keepsTheLastWakeAndSleep() {
        val p = PortalPresence(start)
        p.onLine(line(start, PortalPresence.TAG_POWER, "Going to sleep due to timeout (uid 1000)..."))
        p.onLine(line(start + 5000, PortalPresence.TAG_POWER, "Waking up from Dozing (uid=10068, reason=WAKE_REASON_APPLICATION, details=Full_Wakeup_PresenceManager)..."))
        p.onLine(line(start + 5001, PortalPresence.TAG_POWER, "Waking up from screen off, start dreaming..."))
        assertEquals(PortalPresence.SLEEP_TIMEOUT, p.lastSleep?.cause)
        assertEquals(start, p.lastSleep?.at)
        assertEquals(PortalPresence.WAKE_PRESENCE, p.lastWake?.cause)
        assertEquals(start + 5000, p.lastWake?.at)
    }

    @Test
    fun stuckWhenSomebodyKeepsUsingItUnseen() {
        val p = PortalPresence(start)
        p.onLine(state(start, "AMBIENT"))
        val t = start + ABSENT_AFTER_MILLIS
        p.noteSomebody(t)
        assertEquals(false, p.stuck)
        p.noteSomebody(t + PortalPresence.STUCK_AFTER_MILLIS - 1)
        assertEquals(false, p.stuck)
        p.noteSomebody(t + PortalPresence.STUCK_AFTER_MILLIS)
        assertEquals(true, p.stuck)
        // The next report clears it
        p.onLine(seen(t + PortalPresence.STUCK_AFTER_MILLIS + 1000))
        assertEquals(false, p.stuck)
    }

    @Test
    fun aButtonWakeIsEvidenceToo() {
        val p = PortalPresence(start)
        val t = start + ABSENT_AFTER_MILLIS
        val button = "Waking up from sleep (uid=1000 reason=android.policy:KEY)..."
        p.onLine(line(t, PortalPresence.TAG_POWER, button))
        p.onLine(line(t + PortalPresence.STUCK_AFTER_MILLIS, PortalPresence.TAG_POWER, button))
        assertEquals(true, p.stuck)
    }

    @Test
    fun notStuckWhileItCantSeeOrIsReporting() {
        val p = PortalPresence(start)
        val t = start + ABSENT_AFTER_MILLIS
        p.onLine(line(t, PortalPresence.TAG_SHUTTER, "shutter state is 1"))
        p.noteSomebody(t)
        p.noteSomebody(t + PortalPresence.STUCK_AFTER_MILLIS)
        assertEquals(false, p.stuck)

        // Touches while reports come say nothing against the camera
        val q = PortalPresence(start)
        q.onLine(seen(t))
        q.noteSomebody(t + 1000)
        q.noteSomebody(t + 60_000)
        assertEquals(false, q.stuck)
        // ...and the clock only starts once the reports have expired
        q.noteSomebody(t + ABSENT_AFTER_MILLIS)
        q.noteSomebody(t + ABSENT_AFTER_MILLIS + PortalPresence.STUCK_AFTER_MILLIS - 1)
        assertEquals(false, q.stuck)
    }

    @Test
    fun otherLinesChangeNothing() {
        val p = PortalPresence(start)
        p.onLine(line(start, PortalPresence.TAG_CAMERA, "standby"))
        p.onLine(line(start, PortalPresence.TAG_PRESENCE, "onNotifyPresence, dreaming"))
        p.onLine(line(start, PortalPresence.TAG_SHUTTER, "something else"))
        assertNull(p.lastSeenAt)
        assertEquals(false, p.lensCovered)
        assertNull(p.portalState)
    }
}
