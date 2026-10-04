# Calling between Portals

Any Portal running AstroDock can video call any other one on the same MQTT
broker. The callee rings, whatever it was doing (slideshow, screensaver,
another app, screen off), except at night, when it refuses. By default it
answers by itself after 3 seconds, which is the chance to decline; the
Portalcom app sets that delay (0 to 10s), or turns answering by itself off,
and the call then rings until someone taps Answer, for up to 45 seconds.
Calls are set up over MQTT and the media goes directly between the two
devices with WebRTC. The code is in `app/src/main/java/.../call/`.

Calling is off by default. It is turned on in the Portalcom app, in AstroDock's
app list ("Take part in calls"), or with `tools/push-config.sh --calls-enabled true`, which also
grants the camera and the microphone. `--calls-auto-answer` and
`--calls-auto-answer-seconds` set the answering.

## Protocol

### Messages

Every message is a command published to the **recipient's** topic prefix,
under the same `cmd/` convention as the other commands:
`<prefix>cmd/call/<verb>`. A device only listens to its own `cmd/#`.

| Topic (at the recipient) | Sent by | Payload |
|---|---|---|
| `cmd/call/offer` | caller | `{"call_id":"<uuid>","from":"<caller prefix>","ts":<epoch s>,"sdp":"v=0..."}` |
| `cmd/call/ringing` | callee | `{"call_id":"<uuid>"}` |
| `cmd/call/answer` | callee | `{"call_id":"<uuid>","sdp":"v=0..."}` |
| `cmd/call/reject` | callee | `{"call_id":"<uuid>","reason":"disabled"\|"not_allowed"\|"privacy"\|"unavailable"\|"night"\|"busy"\|"declined"\|"no_answer"}` |
| `cmd/call/hangup` | either | `{"call_id":"<uuid>"}`, plus `"reason":"no_connection"` if the media never got through |

- The offer is what makes the callee ring. Once its call screen is up
  (`CallActivity` attached to the main process) it sends `ringing`, which
  tells the caller two things: the callee is there, and from now on the wait
  is for a person rather than for a device. It then answers (by itself after
  its delay, or when someone taps Answer), or sends `reject` with `declined`
  if someone turned the call down, or with `no_answer` after ringing for 45s
  (`Calls.RING_TIMEOUT_SECONDS`). The callee only creates its answer, and so
  only turns on its camera and microphone, once it answers.
- Timeouts, so that waiting for a person doesn't eat into the time the media
  has to connect (`CallRouter.giveUp`): the caller gives up 30s after the
  offer if nothing came back ("didn't answer": offline, or its `:call`
  crashed starting); after `ringing`, 55s after that (the callee's 45s plus
  a margin, so its `no_answer` arrives first); and after `answer`, 30s for
  the media to connect. The callee times its ringing (45s), and then gives
  the media 30s from its answer.
- Older versions answered by themselves after 3s and had no `ringing`. A
  caller from before it ignores `ringing` (an unknown command) and keeps its
  single 30s timeout, so a call to a device that waits for someone to answer
  only rings for about 30s; one answered after that fails. A caller that
  never gets `ringing` keeps waiting 30s for the answer, which an older
  callee sends in time. An older caller shows a `no_answer` reject as
  "didn't answer", by luck of the names.
- `from` is where replies go, so the callee needs no directory to answer. It
  must be a prefix that can be published to (no `+` or `#`).
- `call_id` is on every message, and one that doesn't match the current call
  is ignored. That covers an answer arriving after the caller gave up, and a
  QoS 1 duplicate.
- An offer whose `ts` is more than 15s off, either way, is dropped with a log
  line and no reply: nothing should turn a camera on for a call nobody is
  making any more. The devices' clocks come from the network.
- All four are published at QoS 1 and not retained, and `cmd/#` is subscribed
  at QoS 1 so they keep it. A lost `hangup` would leave a camera on, and a
  retained message would be replayed on every reconnect (retained commands
  are ignored anyway).

### Refusals

The callee replies `reject` without starting anything when, in this order
(`Calls.refusal`):

1. calling is off on this device (`disabled`);
2. the caller isn't on the allow list, "Who can call" in the Portalcom app
   (`not_allowed`);
3. the Portal is in privacy mode, which turns its camera and microphone off
   (`privacy`);
4. the camera or microphone permission is missing, or the lens cover is
   closed (`unavailable`);
5. the night rule applies (`night`): the night hours, with the night rule on,
   and nobody having touched the slideshow for 5 minutes
   (`SlideshowState.nightRuleApplies`);
6. a call is already under way (`busy`).

Privacy mode and the lens cover are read off the Portal's log
(`presence/PortalLog.kt`, `Presence.md`), so they are only noticed with the
`READ_LOGS` grant. A caller on an older version doesn't know `privacy`, and
says the call ended.

Two devices calling each other at once are each busy, so each rejects the
other.

An offer that passes all of these can still be turned down by hand while it
rings (`declined`), or rung out with nobody answering (`no_answer`, see "On
screen").

### Who can be called

A device with calling on says `"calls": true` in its retained
`<prefix>availability` record, which is published again when the setting
changes. Every device subscribes to `+/availability` and keeps the records,
and the Portalcom app lists, while calling is on, every device that is online,
has `calls: true` and isn't itself, looking again every 2s while it's on
screen.

Devices are named by their topic prefix (`portaloficina`), which defaults to
the name the device was given at setup. The record's `hostname` is the
Bluetooth name, which on a Portal+ is just the model ("Portal+").

`+` is one topic level, so a device with a nested prefix like
`home/kitchen/` can't be found (it can still be called by a client that knows
its prefix).

### State

The `<prefix>state` record has a `call` field, and while a call is under way
its screen's `wanted` is `on` with `wanted_reason` `call`:

```json
"call": {"state": "idle" | "outgoing" | "incoming" | "in_call", "with": "<prefix>" | null, "since": <epoch s> | null}
```

## Connectivity

### No servers

`RTCConfiguration` has no ICE servers: every device is on the same network,
so the addresses each side finds for itself (host candidates) connect
directly. There is no STUN, no TURN, and nothing outside the house. Calls
between networks would need both, plus `cmd/call/ice` (below).

### No trickle ICE

Each side sends its offer or answer once it holds its own addresses, so the
whole handshake is one offer and one answer: no `ice` topic, and no
candidates arriving before the description they belong to.

"Once it holds its addresses" is not "once gathering is complete". WebRTC
learns the device's networks from Android asynchronously, and on the Portal
Go gathering completed before it knew of any, so the Go sent descriptions
with no address in them (`c=IN IP4 0.0.0.0`, no `a=candidate`). A call to the
Go still connected, since it could reach the address in the other side's
offer, but a call from the Go never did. So gathering carries on
(`GATHER_CONTINUALLY`, which also means it never reports `COMPLETE`), and the
description goes out 300ms after the first candidate turns up, or after 5s
without any. The log says how many it carried: `CallMedia: Sending our
description with 2 candidates`. Zero means the call only connects if the
other side's addresses are enough.

### Media

- Audio and video over one bundled connection, DTLS-SRTP encrypted, with the
  certificate fingerprints in the SDP.
- The front camera through Camera2 at 1280x720, 30 fps. The other device is
  shown full screen, scaled to fit, and our own camera small in a corner.
- Audio in `MODE_IN_COMMUNICATION` on the speaker, put back after the call.
  Neither the Portal+ nor the Portal Go offers a hardware echo canceller to an
  app, so WebRTC uses its own software one.
- WebRTC's library is `io.github.webrtc-sdk:android` (LiveKit's build, with
  the `org.webrtc` API), built for arm64 only; every Portal is arm64.

### Permissions

`CAMERA` and `RECORD_AUDIO` (runtime, from the System tab or
`push-config.sh`), `MODIFY_AUDIO_SETTINGS` for the call audio mode, and
`ACCESS_NETWORK_STATE`: WebRTC's network monitor needs it, and without it the
native code aborts the process as a call starts.

## On the device

### Two processes

```
             main process                                  :call process
MQTT ──► StateReporter ──► CallRouter ──startActivity(offer)──► CallActivity
    ◄─── publish ◄──────── CallSignalService ◄──── Messenger ──────┘
                           (bound service)                     CallMedia
```

The call runs in its own process (`:call`), because Android takes the default
home app away from one whose process crashes, and WebRTC is a lot of native
code. A crash in `:call` doesn't touch the home screen (checked with both a
native and a Java crash).

The main process holds the only MQTT connection. `CallRouter` decides about
offers and carries every message between the other device and
`CallActivity`, which only does the media (`CallMedia`) and talks to the main
process through `CallSignalService` (see `CallIpc` for the messages). The
service watches the activity's binder, so the router hangs up for a `:call`
process that died.

**Nothing in `:call` may touch `StateReporter`, `SlideshowState` or any other
singleton of the main process.** They would be second copies there, and a
second `StateReporter` is a second MQTT client with our client id, which
kicks ours off the broker.

### Waking up for a call

For an offer it takes, `CallRouter`:

1. wakes the device with a `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP`,
   held until the call connects or ends (at most the ringing and the setup,
   plus 5s). Waking ends a running screensaver
   (ours or the Portal's) as well as switching the screen on, and an activity
   started under a screensaver stays hidden behind it;
2. starts `CallActivity` with the offer, which it can do from the background
   because AstroDock holds `SYSTEM_ALERT_WINDOW`;
3. every second, up to 5 times, while the call is still setting up and a
   screensaver is running, wakes the device again. After `lockNow()` (the
   night rule, `force_off`) the system starts the screensaver with the screen
   off, and the Portal wakes *into* it, so the first wake puts the
   screensaver over the call. A second wake ends it.

`CallActivity` shows over the lock screen and turns the screen on, and keeps
it on with its window's keep-screen-on flag, not a wake lock: WebRTC takes
none, and the flag holds off both the screensaver and sleep for as long as
the window exists, so a call that crashes can't leave the screen on.

A call only lasts while its screen is in front. 3s after it is hidden (Home,
`force_off`, anything) the activity hangs up, so the camera is never on where
nobody can see that it is. Not at once, because of the screensaver above.

### Failures

- **No connection in time** (see the timeouts under "Messages"), on either
  side: the call is given up on and the other side is sent a `hangup`. The
  caller shows "didn't answer" if no answer came, which covers a callee that
  is offline or whose `:call` crashed while starting, and "Can't reach
  portaloft over the network" if one did but the media never got through
  within 30s of it: the two devices
  can't reach each other (Wi-Fi client isolation, say, or two mesh nodes
  that don't pass traffic between their clients), which `ping` between them
  confirms. The `hangup` then says `"reason":"no_connection"`.
- **A connection that drops** and stays `DISCONNECTED` for 5s, or goes
  `FAILED`, ends the call, since a device that vanishes sends no `hangup`.
  WebRTC takes about 7s to notice, so that is about 12s after the other side
  went. One that never connected goes `FAILED` after about 15s of checks
  that get no reply, on one side before the other, and that side's `hangup`
  says `"reason":"no_connection"`, so the other one shows the same failure
  rather than "hung up". Measured 2026-10-01 between two devices that
  couldn't ping each other.

### On screen

The call screen says what is going on at the top: "Calling portaloft…"
(until it says it's ringing), "Ringing portaloft…", "Call from portaloficina"
(called), "Connecting to portaloft…"
(answered), "Call connected" for 3s and then just the other device's name,
and "Connection lost, reconnecting…" while the connection is down. At the
end, for 2.5s before it closes, it says why: "portaloft hung up", "Call
disconnected", "portaloft didn't answer", "Can't reach portaloft over the network", or
the reason a call was refused ("It's night time at portaloft"). A called
device that rang out says "Missed call from portaloficina". Hanging up
yourself closes it at once.

A called device rings first, beeping once a second, with the camera and
microphone still off, and a green Answer button beside the hang up one.
Answering by itself, it says "Incoming call from portaloft, will connect in
3 seconds" (its delay), counting down. The countdown only runs while the
call can be seen (in front, with the focus, the screen on), and starts over
each time it comes back into view, so a screensaver covering the call as it
comes in (see "Waking up for a call") doesn't eat into it. With a delay of
0 it answers as soon as it can be seen. Not answering by itself, it says
"Incoming call from portaloft" until someone answers, or for 45s. Answer
answers at once either way, and the hang up button declines the call,
which sends `reject` with `declined` (the caller shows "portaloft declined
the call"). Only on answering does it start the media.

## Security

Answering by itself, a few seconds after it starts ringing (the default),
makes every Portal with calls on a camera and microphone that anyone who can
publish to the broker can turn on. Turning answering by itself off in the
Portalcom app closes that, at the price of someone having to be there. The app only checks
the allow list, and `from` is just a field in the payload, so it can't tell
who really sent an offer. **Restrict who can publish to `+/cmd/call/#` on the
broker**: that is the real control. The media's encryption only helps as far
as the signalling can be trusted.

## Testing

`tools/fake-call-peer.py` is a fake Portal, for testing without a second one.
It speaks the protocol above with real WebRTC (aiortc), sends magenta video,
and counts the frames the Portal sends back:

```
uv run tools/fake-call-peer.py --broker 10.0.0.10:1883 call portaloft/
uv run tools/fake-call-peer.py --broker 10.0.0.10:1883 listen [--reject night] [--ring-seconds 20]
```

`mosquitto_sub -v -t '+/cmd/call/#'` shows a handshake from any machine,
including the candidates in each description. The logs are
`adb logcat -s CallRouter CallActivity CallMedia`.
