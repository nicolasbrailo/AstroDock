# Presence on the Portal

What the Portal does with its camera-based presence detection, and how an app
can see it. Measured on 2026-10-03 with the presence lab (`../PresenceLab`, a
separate app that follows the system log and records every screen change) on
a Portal+ (`aloha`, Android 9) and a Portal Go (Android 10), which behave the
same in everything below. `TODO.md` has the plan for using it in AstroDock.

This replaces two conclusions in AGENTS.md's Portal notes: that
`Notify people presence` is a periodic line that doesn't mean anyone was
seen, and that an app can't observe presence. Both were wrong.

## Reading it

Meta's presence store and its camera service are behind signature
permissions, so an app can't ask for presence. But the Portal logs every step,
and an app holding `READ_LOGS` (a development permission, granted over adb:
`pm grant <pkg> android.permission.READ_LOGS`) can follow the log live with
`logcat -v epoch`.

Read it live, not from the backlog: logd's "chatty" pruning drops
system_server's stored lines first, so the power manager's lines are usually
gone from the buffer within minutes, while a reader following along gets
them all.

Tag names differ between models (the Portal+ also logs as
`aloha.UserPresenceManager`, which is about app switches, not people), so
match the lines below by tag and text, and expect to check them again on a
new firmware.

## The signal

```
aloha.CameraServiceController: Notify people presence
PresenceManager: onNotifyPresence presence updated [APPLICATION]
PresenceManager: onNotifyPresence, <awake|dreaming|standby>
```

`Notify people presence` comes from the detection service
(`com.facebook.portal.aiservice`) every 30s **while it sees someone**, and
stops when it doesn't: with tape over the camera, with the lens cover closed,
in privacy mode, and with nobody in view. It resumed within a second of the
tape or cover coming off with someone in front.

The second and third lines are the presence service
(`com.facebook.alohaservices.presence`, logging as `PresenceManager`) taking
the report, and the word after the comma is what it does with it, which
depends on the screen:

| `onNotifyPresence, ...` | Screen | Effect |
|---|---|---|
| `awake` | on, an app in front | none: the screensaver timeout keeps counting from the last touch |
| `dreaming` | screensaver running | keeps it up: the power manager's `mLastUserActivityTimeNoChangeLights` is set to the report's time |
| `standby` | off after a timeout | wakes it, into the screensaver |

So with someone in view the screensaver still starts on time (presence
doesn't count while an app is in front), and then stays up for as long as
they are seen. `mLastUserActivityTime` only moves for touches and buttons;
presence shows up in `mLastUserActivityTimeNoChangeLights` and in the
attention detector's `mLastUserActivityTime(excludingAttention)` (the
attention service itself is off). AGENTS.md's measurement of "no detection"
watched the right field, but presence never moves it while the screen is
awake, which is probably what was measured.

## The Portal's states

`PresenceManager` names its state on every change:

```
PresenceManager: setCurrentGlobalState [AMBIENT] timeIn Previous State [STANDBY] [112] secs
```

- `AMBIENT`: the screen is on (awake or screensaver). The camera runs, its
  pose estimator at 20 fps (`AlohaComputerVisionManager: Set aloha CV
  configuration, profile: PERFORMANCE`).
- `STANDBY`: the screen went off on a timeout. The camera is kept in a low
  power mode (`transitionToState Turn Camera to standby`,
  `CameraServiceController: standby`, profile `STANDBY`, pose estimator at 2
  fps, a frame analysed every 5s), and a presence report wakes the screen.
- `SLEEP`: the screen was turned off with the power button. The camera is off
  (`transitionToState Turn Camera off`, `stopCameraService`), and even the
  camera becoming available is ignored (`onCameraAvailable Currently in
  [SLEEP], Ignore`). Only a button or a touch wakes it.

## The cycle

With somebody in view and nobody touching anything:

1. The screen is on, `AMBIENT`. Reports come every 30s (`awake`) and change
   nothing.
2. `screen_off_timeout` after the last touch, the screensaver starts
   (`PowerManagerService: Nap time`).
3. Reports (`dreaming`) keep it up for as long as somebody is seen.

When they leave:

4. `sleep_timeout` after the last report (or touch), the screen goes off
   (`Going to sleep due to timeout`), and the Portal goes to `STANDBY`.

When somebody comes back:

5. The first report (`standby`) wakes it:
   `Waking up from Dozing (uid=10068, reason=WAKE_REASON_APPLICATION, details=Full_Wakeup_PresenceManager)`
   (Android 9 words it `Waking up from dozing (uid=10004 reason=Full_Wakeup_PresenceManager)`).
   The Portal goes back to `AMBIENT`, into the screensaver.

The wake reasons in the power manager's log tell who woke the screen:
`Full_Wakeup_PresenceManager` is the camera, `android.policy:KEY` and
`WAKE_REASON_POWER_BUTTON` are buttons, `android.server.power:DREAM` is the
screensaver ending. Sleeps likewise: `due to timeout`, `due to power_button`,
`due to device_admin` (a `lockNow()`).

## When the camera can't see

| | Lens cover | Privacy mode | Power button off |
|---|---|---|---|
| Logged as | `ShutterStateObserver: shutter state is 1` (0 open), `shutter_state=CAMERA_LENS_COVERED` | `PrivacyModeManager: enterPrivacyMode [true]` (`exitPrivacyMode` to leave) | `Going to sleep due to power_button`, then `[SLEEP]` |
| `Privacy status updated` | `cameraEnabled=false, microphoneEnabled=true` | `cameraEnabled=false, microphoneEnabled=false` | (unchanged) |
| Camera | off: `Shutter state closed, ignoring onCameraAvailable` | torn down; the audio driver is muted too (`privacy_mode=1`) | off |
| Screen off on the timeout | `STANDBY` | `STANDBY`, but `transitionToState, Privacy mode is on` instead of the camera's standby | already off |
| Presence wakes it | no | no | no |

In all three, no reports means the Portal can't see, not that the room is
empty. Leaving privacy mode with the screen off puts the camera back in
standby, and the first report then wakes the screen (measured: half a second
later, with someone in view). Privacy mode also leaves the screen as it was.

## Failures

**The detector can get stuck.** On the Portal+, `aiservice`, up for days,
went on receiving frames (`AlohaCameraPipeline: Frame available for analysis
stream`) and reporting nobody for over an hour, with someone in front of it,
so nothing woke the screen from `STANDBY`. Its auto-framing logged `Forcing
brake movement` about once a second throughout, which may be the sign of
having nobody to frame. It had worked earlier the same day. The sign is no
`Notify people presence` at all while somebody is demonstrably there (touching
the screen).

**What clears it** (measured 2026-10-04 on the Portal+, caught stuck a second
time within 24h):
- **A reboot** clears it.
- **Turning the screen off with the power button and back on does not.** That
  does the deepest teardown short of a reboot: `transitionToState [SLEEP]`,
  `Turn Camera off`, `stopCameraService`, then on wake `CameraServiceController:
  start` with `profile: PERFORMANCE`. The camera service fully stopped and
  restarted, and it stayed stuck: still no `Notify people presence`, still
  `Forcing brake movement` once a second, and analysis frames still at the 5s
  STANDBY cadence despite the PERFORMANCE profile. So the wedge lives in the
  `aiservice` process, not in the camera session or the resource profile.
- **`adb shell am force-stop com.facebook.portal.aiservice` clears it.** The
  system respawns the process (its services are `startRequested=true
  stopIfKilled=true`), and detection was back within ~15s: `Notify people
  presence` on the healthy 30s beat, no more brake spam, and the app's
  `errors` cleared the `presence` entry on its own (PortalLog follows the new
  lines). This is the fastest recovery, but force-stop of a priv-app needs
  system privilege, so only adb can do it, not the app itself.

**An app still can't recover from it on its own.** Recovery needs the process
killed or the device rebooted:
- `am force-stop` needs system privilege: not callable by a third-party app.
- `DevicePolicyManager.reboot()` needs device **owner**; AstroDock is only a
  device **admin** (force-lock), and device owner can't be set on a
  provisioned Portal. So this API is out.
- A `com.facebook.aloha.adb.OTA_REBOOT_NOW` broadcast to
  `com.facebook.alohaapps.settings`'s `OtaIntentReceiver` looked like a way in
  (its intent filter showed no permission in the resolver), but it is **not
  usable**: tested 2026-10-04, the broadcast is delivered and then refused with
  `Permission Denial ... requires com.facebook.aloha.permission.ADB_INTENT`,
  and even adb shell (uid 2000) is refused. That permission is `prot=signature`
  (sourced from `com.facebook.alohaapps.settings`), so only apps signed with
  Facebook's certificate can send it. The device never rebooted.

So **no in-app reboot or restart path exists.** Recovery is a physical reboot,
or an adb-connected host running `am force-stop com.facebook.portal.aiservice`
or a reboot. Everything the app itself can reach (camera off/on, its own
broadcasts, device-admin APIs) is either proven useless or privilege-blocked,
so the app's only move is to reduce how often the wedge happens (prevention
below) and to flag it (`stuck`) when it does.

**The likely trigger is the camera handover a call forces.** AstroDock's call
opens camera **0**; `aiservice`'s presence detector runs on camera **1**, and
the Portal's camera HAL can't run both independently. So every call we place
force-closes and re-establishes aiservice's camera-1 session: the camera log
shows our `CONNECT device 0` and aiservice's `CONNECT`/`DISCONNECT device 1`
at the same instant (measured 14:44:35, and on earlier calls). That
reconfigure is the stress event, and occasionally aiservice fails to
re-establish its pose pipeline after it and wedges. It is **not** deterministic:
a controlled call on 2026-10-04 forced the churn and aiservice recovered
cleanly (presence back on the 30s beat, no brake spam). This fits the rate
seen (twice in 24h, not every call). The Portal's own camera apps (WhatsApp,
photobooth, superframe) cause the same churn, so camera contention in general
is the suspect, not our calls alone, and long aiservice uptime (~17h before
wedging here) may also contribute. The cheapest mitigation we control is to
open camera 0 as rarely and briefly as possible, cutting the number of these
stress events; the `stuck` alert then tells us when one lands anyway.

## Not yet explained

- On the Go, the screensaver started almost 10 minutes after the last touch,
  against a 5 minute `screen_off_timeout`. Closing the lens cover was at the
  5 minute mark, so the cover switch may count as activity.
- Whether `STANDBY` lasts for ever, or ends in `SLEEP` after a while: the
  presence service has a `presence_detection_standby_timeout`, which isn't a
  system setting on either device. Overnight `SLEEP` on the Portal+ is more
  likely the power button.
- How reliable detection is in the dark, from across the room, or from
  behind.
