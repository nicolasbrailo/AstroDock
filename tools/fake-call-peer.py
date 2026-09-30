# /// script
# requires-python = ">=3.10"
# dependencies = ["aiortc", "paho-mqtt"]
# ///
"""A fake Portal, for testing AstroDock's calls without a second one (see
CALLING.md). It speaks the cmd/call/* protocol over MQTT and does real WebRTC
with aiortc, sending magenta video and silence, and counts the frames the
Portal sends back.

  uv run tools/fake-call-peer.py --broker 10.0.0.10:1883 call portaloft-portal/
      Calls the Portal under that prefix, holds the call for --seconds, then
      hangs up. --no-hangup leaves it to the Portal to notice; --ts-offset
      shifts the offer's timestamp, to test stale offers.
  uv run tools/fake-call-peer.py --broker 10.0.0.10:1883 listen
      Advertises itself as a device that takes calls ("Test peer", under
      --prefix, testpeer/ by default), answers whatever calls it, and hangs up
      after --call-seconds. --reject REASON refuses them instead.

Its availability record is cleared again when it exits normally.
"""

import argparse
import asyncio
import fractions
import json
import time
import uuid

import av
import paho.mqtt.client as mqtt
from aiortc import MediaStreamTrack, RTCPeerConnection, RTCSessionDescription
from aiortc.mediastreams import AudioStreamTrack

ME = "testpeer/"


def log(*a):
    print(f"[{time.strftime('%H:%M:%S')}]", *a, flush=True)


class ColourTrack(MediaStreamTrack):
    """Magenta frames, so the Portal's screen shows that video arrived"""
    kind = "video"

    def __init__(self):
        super().__init__()
        self.pts = 0

    async def recv(self):
        await asyncio.sleep(1 / 15)
        frame = av.VideoFrame(640, 360, "yuv420p")
        for plane, value in zip(frame.planes, (110, 200, 220)):
            plane.update(bytes([value]) * plane.buffer_size)
        frame.pts = self.pts
        frame.time_base = fractions.Fraction(1, 90000)
        self.pts += 6000
        return frame


class Peer:
    def __init__(self, loop):
        self.loop = loop
        self.inbox = asyncio.Queue()
        # A client id of its own each run: two clients with one id kick each
        # other off the broker
        self.client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="fake-call-peer-" + uuid.uuid4().hex[:8])
        self.client.on_message = lambda c, u, m: loop.call_soon_threadsafe(
            self.inbox.put_nowait, (m.topic, m.payload.decode() if m.payload else "", m.retain))
        host, _, port = BROKER.partition(":")
        self.client.connect(host, int(port or 1883))
        self.client.subscribe(ME + "cmd/call/#", qos=1)
        self.client.loop_start()
        self.frames = {"video": 0, "audio": 0}

    def send(self, prefix, verb, payload):
        log("->", prefix + "cmd/call/" + verb, {k: (v if k != "sdp" else f"<{len(v)} bytes>") for k, v in payload.items()})
        self.client.publish(prefix + "cmd/call/" + verb, json.dumps(payload), qos=1)

    def new_pc(self):
        pc = RTCPeerConnection()
        pc.addTrack(AudioStreamTrack())
        pc.addTrack(ColourTrack())

        @pc.on("track")
        def on_track(track):
            log("receiving", track.kind)

            async def count():
                while True:
                    try:
                        await track.recv()
                    except Exception:
                        return
                    self.frames[track.kind] += 1
            asyncio.ensure_future(count())

        @pc.on("connectionstatechange")
        def on_state():
            log("connection", pc.connectionState)
        return pc

    async def next_message(self, timeout):
        topic, payload, retained = await asyncio.wait_for(self.inbox.get(), timeout)
        verb = topic.rsplit("/", 1)[-1]
        data = json.loads(payload) if payload else {}
        log("<-", topic, {k: (v if k != "sdp" else f"<{len(v)} bytes>") for k, v in data.items()}, "retained" if retained else "")
        return verb, data

    async def hold(self, seconds, call_id):
        """Keeps the call up, reporting frames; returns early on a hangup"""
        end = time.time() + seconds
        while time.time() < end:
            try:
                verb, data = await self.next_message(min(5, max(0.1, end - time.time())))
                if verb == "hangup" and data.get("call_id") == call_id:
                    log("peer hung up")
                    return True
            except asyncio.TimeoutError:
                pass
            log("frames so far", self.frames)
        return False


async def call(args):
    peer = Peer(asyncio.get_running_loop())
    pc = peer.new_pc()
    await pc.setLocalDescription(await pc.createOffer())
    call_id = str(uuid.uuid4())
    sdp = "garbage" if args.sdp_garbage else pc.localDescription.sdp
    peer.send(args.target, "offer", {"call_id": call_id, "from": ME, "ts": int(time.time()) + args.ts_offset, "sdp": sdp})
    try:
        while True:
            verb, data = await peer.next_message(args.answer_timeout)
            if data.get("call_id") != call_id:
                continue
            if verb == "answer":
                await pc.setRemoteDescription(RTCSessionDescription(data["sdp"], "answer"))
                break
            if verb in ("reject", "hangup"):
                log("call over before it started:", verb, data.get("reason"))
                await pc.close()
                return
    except asyncio.TimeoutError:
        log("no answer")
        await pc.close()
        return
    hung_up = await peer.hold(args.seconds, call_id)
    log("final frames", peer.frames)
    if not hung_up and not args.no_hangup:
        peer.send(args.target, "hangup", {"call_id": call_id})
    await asyncio.sleep(1)
    await pc.close()


async def listen(args):
    peer = Peer(asyncio.get_running_loop())
    record = {"state": "online", "machine_id": "fake-call-peer", "hostname": "Test peer", "calls": True, "app": "test"}
    peer.client.publish(ME + "availability", json.dumps(record), retain=True)
    log("advertised as", ME)
    try:
        end = time.time() + args.seconds
        while time.time() < end:
            try:
                verb, data = await peer.next_message(end - time.time())
            except asyncio.TimeoutError:
                break
            if verb != "offer":
                continue
            call_id, caller = data["call_id"], data["from"]
            pc = peer.new_pc()
            await pc.setRemoteDescription(RTCSessionDescription(data["sdp"], "offer"))
            await pc.setLocalDescription(await pc.createAnswer())
            if args.reject:
                peer.send(caller, "reject", {"call_id": call_id, "reason": args.reject})
                continue
            peer.send(caller, "answer", {"call_id": call_id, "sdp": pc.localDescription.sdp})
            hung_up = await peer.hold(args.call_seconds, call_id)
            log("final frames", peer.frames)
            if not hung_up:
                peer.send(caller, "hangup", {"call_id": call_id})
            await pc.close()
            peer.frames = {"video": 0, "audio": 0}
    finally:
        peer.client.publish(ME + "availability", b"", retain=True).wait_for_publish()


def main():
    global BROKER, ME
    parser = argparse.ArgumentParser(description="A fake Portal for testing calls")
    parser.add_argument("--broker", required=True, help="host:port")
    parser.add_argument("--prefix", default=ME, help="our own topic prefix")
    sub = parser.add_subparsers(dest="mode", required=True)
    c = sub.add_parser("call")
    c.add_argument("target")
    c.add_argument("--seconds", type=float, default=20)
    c.add_argument("--answer-timeout", type=float, default=30)
    c.add_argument("--no-hangup", action="store_true")
    c.add_argument("--sdp-garbage", action="store_true")
    c.add_argument("--ts-offset", type=int, default=0)
    l = sub.add_parser("listen")
    l.add_argument("--seconds", type=float, default=120)
    l.add_argument("--call-seconds", type=float, default=20)
    l.add_argument("--reject", default=None)
    args = parser.parse_args()
    BROKER = args.broker
    ME = args.prefix if args.prefix.endswith("/") else args.prefix + "/"
    asyncio.run(call(args) if args.mode == "call" else listen(args))


if __name__ == "__main__":
    main()
