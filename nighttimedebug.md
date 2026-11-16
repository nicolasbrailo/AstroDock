# Night time debugging

Notes on the investigation into "night mode failing" (the night rule: the
screen covered and dimmed, then switched off, during the night hours; see
**Night screen off** in `AGENTS.md`). Written 2026-11-15 so another session
can pick it up. Update it as the investigation moves.

## The devices

All publish on the broker at `10.0.0.10:4500`. "Build" is what each one was
running on 2026-11-15, read from `app.started_at` in its retained `state`.

| Prefix | Model | IP | Running since | Night diagnostics |
|---|---|---|---|---|
| `portaloficina` | Portal+ (Android 10) | 10.0.0.95 | 2026-11-04 09:06 | Yes: the `night` record (from `b7c1a3c`) and the status line over the cover (from `06bd4d8`) |
| `portaloft` | the old Portal, "Loft Portal", Android 9 | 10.0.0.87 | 2026-10-14 17:44 | No: built around `5021b69`. Reports `portal_state` and `last_wake`/`last_sleep`, but no `night` record and no status line |
| `portalcocina` | Portal Go | 10.0.0.113 | 2026-10-10 18:24 | No. `portal_state` is null, so `PortalLog` isn't reading (probably no `READ_LOGS`; occupancy `source` is `presence`/`screen_on`/`screen_off`, not `camera`) |
| `portaltvroom` | Portal Go | 10.0.0.112 | 2026-10-17 16:24 | No |

Night hours: 20:00 to 08:00 on portaloficina (`night.start_hour`/`end_hour`),
and judging by when the cover goes up, the same on portaloft and portaltvroom,
22:00 to 08:00 on portalcocina. Zone `Europe/London`, which is UTC in
November, so the log times below are local times too.

None of the devices was connected over adb on 2026-11-15 (portaloficina: "No
route to host" on 5555). Network adb has to be enabled on a device before
`adb connect <ip>:5555` works.

## Where the logs are

**MQTT recordings.** `tools/record-mqtt.sh` runs on the broker host and has
recorded every device since 2026-11-04 09:09:

```
ssh 10.0.0.10
ls ~/run/portaldebug/mqtt-log/          # mqtt-YYYY-MM-DD.log, one per UTC day, ~9 MB each, 14 days kept
```

Each line is `ISO-time topic payload`. It holds every device's `state`,
`availability`, `state/displayed_photo` and `cmd/#` (not
`cmd/ambience/set_svg_overlay`). `state` is only published on a change, so the
gaps between lines are times nothing changed. Copy the files off rather than
working on the broker host:

```
S=<scratch dir>; mkdir -p $S
scp '10.0.0.10:run/portaldebug/mqtt-log/mqtt-2026-11-1*.log' $S/
```

**Current state only:** `mosquitto_sub -h 10.0.0.10 -p 4500 -t '+/state' -t '+/availability' -F '%t %p' -W 3`.
Retained, so it only shows the latest record, which by morning has replaced
everything from the night.

**On the device**, once adb is reachable, and before a reboot (the logcat
ring buffer rotates within hours):

```
adb logcat -b crash -d
adb logcat -d | grep -E 'PowerManagerService|DreamController|preferred activity'
adb logcat -d -s SlideshowController SlideshowActivity   # "Night hours: dark (rule)", "turning the screen off (camera: ...)"
adb shell dumpsys power | grep -E 'mWakefulness|mLastUserActivity|mWakeLockSummary'
```

`Presence.md` explains the Portal's own log lines, and `tools/capture-presence.sh OUT_DIR`
records an experiment.

## Reading a night from the recording

A timeline of the fields that matter, one line per change (`uniq -f1` drops
repeats):

```
d=portaloficina
grep -E "^2026-11-1(4T(2[0-3])|5T0[0-8])\S* $d/state " both.log | while read -r ts topic payload; do
  echo "$payload" | jq -r --arg ts "${ts:11:8}" '[$ts,
    "act="+(.slideshow.active|tostring), "cov="+(.slideshow.night_cover|tostring),
    "in="+(.slideshow.shown_in|tostring), "on="+(.screen.on|tostring),
    "ps="+(.screen.portal_state|tostring),
    "occ="+(.occupancy.occupied|tostring)+"/"+(.occupancy.blind_reason|tostring),
    "want="+(.screen.wanted|tostring)+":"+(.screen.wanted_reason|tostring),
    "wake="+((.screen.last_wake.cause//"-")|tostring),
    "sleep="+((.screen.last_sleep.cause//"-")+"/"+((.screen.last_sleep.by//"-")|tostring)),
    "night="+((.night.applies//"-")|tostring)] | join(" ")' 2>/dev/null
done | uniq -f1
```

Every moment of the night a Portal showed pictures, or was lit without the
cover, across all the files:

```
grep -E '^\S+T(2[2-3]|0[0-6]):\S+ portal[a-z]+/state \{' all.log | while read -r ts topic payload; do
  echo "$payload" | jq -r --arg ts "$ts" --arg t "$topic" \
    'select(.slideshow.active==true or (.screen.on==true and .slideshow.night_cover!=true)) | "\($ts) \($t)"'
done
```

What a normal night looks like (portaloficina, 2026-11-14): at 20:00 the cover
goes up (`cov=true`, `want=off:night`). Presence wakes the screen
(`on=true ps=AMBIENT occ=true`). The camera stops seeing anybody (`occ=false`),
and 5 to 30s later `sleep=lock/night`, `on=false ps=STANDBY`. At 08:00 the
cover comes down (`cover_change.reason: rule`).

How to tell which half failed, on a build with the `night` record:
- `night.applies` false during the hours: one of its conditions says why
  (`settings_loaded`, `in_hours`/`zone`, `admin`, `touch_grace`, `alarm_grace`).
- `applies` true but `night_cover` false: the dark half. `night.cover_change`
  says whether a `touch`, a `stop` or the `rule` took the cover down.
- Cover up but `screen.on` stays true: the off half. `last_wake` and
  `occupancy` say whether the camera kept seeing somebody, or whether the lock
  was waiting on the 10-minute relock fallback (no `PortalLog`).

## What the recordings show (2026-11-04 to 2026-11-15)

Every night on every device was checked with the second command above. The
only real hits are:

- **portalcocina at 22:00:00 most nights**: the moment the night starts, one
  record with pictures before the cover goes up a fraction of a second later.
  Expected.
- **portaloft, 2026-11-14 at 23:20:35**: the night of the reported repro.

### portaloft, night of 2026-11-14

| Time (UTC) | What the records say |
|---|---|
| 20:00 to 23:20 | Normal: presence wakes it into the covered screensaver, then `lock`/`night` once the camera loses the person. |
| 23:20:31 | Presence wakes it (`Full_Wakeup_PresenceManager`), still covered. |
| 23:20:35 | `last_wake` becomes `screensaver_end` (`uid=1000, DREAM_FINISHED`); `shown_in: home`, `night_cover: false`, `active: true`, `wanted: null`; two photos published within a second. |
| 23:21:42 | Last will (`offline`, no `started_at_iso`): an unclean disconnect. |
| after | Nothing. The device didn't answer ping the next morning. |

Explained: someone touched the covered screensaver (the screensaver finishes
itself on a touch, and a touch on it wakes to an uncovered home screen with the
5-minute touch grace), and **the user switched the device off** a minute later.
So, as far as the recordings show, the night rule did what it was designed to
do. What the user expected, and didn't get, was a message saying why the screen
was lit after the touch. portaloft's build has no status line at all, not even
over the cover.

### Not part of the night bug, but noticed

- **portalcocina and portaltvroom drop off the broker about once a minute**:
  10,344 and 10,595 `offline` availability records in 11 days, against 45 for
  portaloficina and 5 for portaloft. Both run old builds. Look at it before
  trusting anything timing related from them.
- **The broker host redelivers retained messages at 03:00 most days** (they
  show up in the recording, which suggests a broker restart). On 2026-11-15 at
  03:00:00, every portaloft topic (`availability`, `state`,
  `state/displayed_photo`) got an **empty** retained message. AstroDock only
  clears another prefix on a rename, and only if its `availability` is ours,
  which this one wasn't. Something else on the broker side tidies up offline
  devices (zmw_homeboard?). Unexplained.

## Changed so far

Uncommitted as of 2026-11-15. Built into `~/Downloads/AstroDock-debug.apk`
(sha256 `e3960cfc...`) and `AstroDock-release.apk` (`23704c3b...`), not
installed anywhere yet:

- **A line over the pictures while a grace holds the rule off at night**,
  centred at the top: "Night mode paused by a touch for another 4:32", or "by
  the alarm". `SlideshowState.nightPause` (unit tested in `NightPauseTest`),
  `SlideshowController.syncNightStatus`/`tickNightStatus`, `night_paused` in
  `slideshow.xml`.
- While either night line is up, the cover now follows the rule within the
  second rather than at the next 30s check: an alarm uncovers at once, and the
  cover returns when the countdown reaches zero. Locking still waits for the
  checks.

## Next steps

1. Install the current build on **all four** Portals and run
   `tools/setup-device.sh` on each (it restores the screensaver after an
   install, and grants `READ_LOGS`, which portalcocina seems to lack). Until
   then only portaloficina can explain its nights.
2. Check the new line on a device: tap the cover in the night hours, then
   expect about 5:00 counting down and the cover back at 0:00. For the alarm
   case, an alarm set inside the night hours (or "Test" in the Alarms app's
   editor).
3. On the next report, get the device, the time, and what was on screen
   (pictures, black, the Portal's own launcher or clock), then read that night
   with the commands above. With the `night` record, the cause should be in it.
4. If the screen is ever lit at night with **no** grace and no record
   explaining it, get the device's logcat before it rotates (see above).
5. Find out what clears offline devices' retained topics at 03:00, and why
   the two Gos reconnect every minute.
