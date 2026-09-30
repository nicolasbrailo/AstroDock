package com.nicobrailo.astrodock.call

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera1Enumerator
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraEnumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RendererCommon
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule

// The media half of a call: camera, microphone and speaker, and the WebRTC
// connection that carries them to the other device. Only used in the :call
// process (see CallActivity).
//
// There are no ICE servers: every device is on the same network, so the
// addresses each side gathers for itself are enough, and nothing outside the
// house is involved. The offer and the answer are only sent once they hold
// those addresses, so there are no separate candidate messages to send or
// order.
//
// "Once they hold them" is not "once gathering is complete": WebRTC learns
// the device's networks from Android asynchronously, and on the Portal Go
// gathering finished before it knew of any, so the Go sent descriptions with
// no address at all (c=IN IP4 0.0.0.0), and calls from it never connected
// (measured 2026-09-30). So gathering carries on (GATHER_CONTINUALLY), and
// the description goes out a moment after the first candidate turns up.
//
// The listener is called on the main thread.
class CallMedia(
    private val context: Context,
    private val localView: SurfaceViewRenderer,
    private val remoteView: SurfaceViewRenderer,
    private val listener: Listener,
) {
    interface Listener {
        // Our offer or answer, complete, to send to the other device
        fun onLocalSdp(sdp: String)
        // Every time media starts flowing, including after an interruption
        fun onConnected()
        // The connection dropped; it may come back (onConnected) or not
        // (onFailed)
        fun onInterrupted()
        // The connection is gone for good, or never came up
        fun onFailed(why: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(AudioManager::class.java)
    private val egl = EglBase.create()
    private var factory: PeerConnectionFactory? = null
    private var connection: PeerConnection? = null
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var sdpSent = false
    private var released = false
    private var previousAudioMode: Int? = null
    private var previousSpeaker = false

    // A moment after the first candidate, for the rest (another interface,
    // IPv6) to join it
    private val sendSdpNow = Runnable { sendLocalSdp(force = false) }
    // In case no candidate ever turns up: the description goes without, and
    // the call only connects if the other side's addresses are enough
    private val sendSdpAnyway = Runnable { sendLocalSdp(force = true) }
    // A connection that drops for a moment (Wi-Fi roaming) comes back on its
    // own; one that stays down this long is gone
    private val giveUpDisconnected = Runnable { listener.onFailed("disconnected") }

    // Starts the camera and the microphone, and makes an offer, or, given the
    // other side's offer, an answer
    fun start(remoteOffer: String?) {
        initializeOnce(context)
        localView.init(egl.eglBaseContext, null)
        localView.setMirror(true)
        // Over the other device's picture, which is also a SurfaceView
        localView.setZOrderMediaOverlay(true)
        remoteView.init(egl.eglBaseContext, null)
        remoteView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)

        startAudioMode()
        // The Portal's hardware echo canceller works (checked on the device);
        // WebRTC uses its own where there isn't one
        val audioModule = JavaAudioDeviceModule.builder(context)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        val newFactory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        // The factory holds its own reference
        audioModule.release()
        factory = newFactory

        val config = PeerConnection.RTCConfiguration(emptyList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val pc = newFactory.createPeerConnection(config, observer)
        if (pc == null) {
            listener.onFailed("no peer connection")
            return
        }
        connection = pc

        val audioTrack = newAudioTrack(newFactory)
        pc.addTrack(audioTrack, listOf(STREAM_ID))
        newVideoTrack(newFactory)?.let { track ->
            track.addSink(localView)
            pc.addTrack(track, listOf(STREAM_ID))
        }

        if (remoteOffer == null) {
            pc.createOffer(created { setLocal(it) }, MediaConstraints())
        } else {
            pc.setRemoteDescription(
                done { pc.createAnswer(created { setLocal(it) }, MediaConstraints()) },
                SessionDescription(SessionDescription.Type.OFFER, remoteOffer),
            )
        }
    }

    fun setRemoteAnswer(sdp: String) {
        connection?.setRemoteDescription(done {}, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    fun release() {
        if (released) return
        released = true
        main.removeCallbacks(sendSdpNow)
        main.removeCallbacks(sendSdpAnyway)
        main.removeCallbacks(giveUpDisconnected)
        try {
            capturer?.stopCapture()
        } catch (e: InterruptedException) {
            Log.w(TAG, "Interrupted stopping the camera", e)
        }
        connection?.dispose()
        connection = null
        capturer?.dispose()
        capturer = null
        videoSource?.dispose()
        videoSource = null
        audioSource?.dispose()
        audioSource = null
        textureHelper?.dispose()
        textureHelper = null
        localView.release()
        remoteView.release()
        factory?.dispose()
        factory = null
        egl.release()
        stopAudioMode()
    }

    private fun newAudioTrack(factory: PeerConnectionFactory): AudioTrack {
        val source = factory.createAudioSource(MediaConstraints())
        audioSource = source
        return factory.createAudioTrack("audio", source)
    }

    // The front camera, which on a Portal is the only one. A call without one
    // still carries sound.
    private fun newVideoTrack(factory: PeerConnectionFactory): VideoTrack? {
        val enumerator: CameraEnumerator =
            if (Camera2Enumerator.isSupported(context)) Camera2Enumerator(context) else Camera1Enumerator()
        val name = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
            ?: enumerator.deviceNames.firstOrNull()
        if (name == null) {
            Log.w(TAG, "No camera")
            return null
        }
        val newCapturer = enumerator.createCapturer(name, null) ?: return null
        val helper = SurfaceTextureHelper.create("CallCapture", egl.eglBaseContext)
        val source = factory.createVideoSource(newCapturer.isScreencast)
        newCapturer.initialize(helper, context, source.capturerObserver)
        newCapturer.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)
        capturer = newCapturer
        textureHelper = helper
        videoSource = source
        return factory.createVideoTrack("video", source)
    }

    private fun setLocal(description: SessionDescription) {
        val pc = connection ?: return
        pc.setLocalDescription(done { main.postDelayed(sendSdpAnyway, GATHER_GIVE_UP_MILLIS) }, description)
    }

    private fun sendLocalSdp(force: Boolean) {
        if (sdpSent || released) return
        val sdp = connection?.localDescription?.description ?: return
        val candidates = sdp.lines().count { it.startsWith("a=candidate:") }
        if (candidates == 0 && !force) return
        if (candidates == 0) Log.w(TAG, "No address of our own to send; the other side's will have to do")
        Log.i(TAG, "Sending our description with $candidates candidates")
        sdpSent = true
        main.removeCallbacks(sendSdpNow)
        main.removeCallbacks(sendSdpAnyway)
        listener.onLocalSdp(sdp)
    }

    // A call sounds like one: the voice call stream, and the speaker, which
    // on a Portal is the only one anyway. Put back when the call ends.
    @Suppress("DEPRECATION") // setSpeakerphoneOn's replacement needs API 31
    private fun startAudioMode() {
        val am = audio ?: return
        previousAudioMode = am.mode
        previousSpeaker = am.isSpeakerphoneOn
        am.mode = AudioManager.MODE_IN_COMMUNICATION
        am.isSpeakerphoneOn = true
    }

    @Suppress("DEPRECATION")
    private fun stopAudioMode() {
        val am = audio ?: return
        val mode = previousAudioMode ?: return
        am.mode = mode
        am.isSpeakerphoneOn = previousSpeaker
    }

    // Called on WebRTC's own thread
    private val observer = object : PeerConnection.Observer {
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            Log.i(TAG, "Gathering $state")
        }

        override fun onIceCandidate(candidate: IceCandidate) {
            main.post {
                if (sdpSent) return@post
                main.removeCallbacks(sendSdpNow)
                main.postDelayed(sendSdpNow, CANDIDATE_SETTLE_MILLIS)
            }
        }

        override fun onConnectionChange(state: PeerConnection.PeerConnectionState) {
            Log.i(TAG, "Connection $state")
            main.post {
                if (released) return@post
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED -> {
                        main.removeCallbacks(giveUpDisconnected)
                        listener.onConnected()
                    }
                    PeerConnection.PeerConnectionState.DISCONNECTED -> {
                        main.removeCallbacks(giveUpDisconnected)
                        main.postDelayed(giveUpDisconnected, DISCONNECTED_GIVE_UP_MILLIS)
                        listener.onInterrupted()
                    }
                    PeerConnection.PeerConnectionState.FAILED -> listener.onFailed("failed")
                    else -> Unit
                }
            }
        }

        override fun onTrack(transceiver: RtpTransceiver) {
            (transceiver.receiver.track() as? VideoTrack)?.addSink(remoteView)
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
    }

    // SdpObserver does two jobs; these are one each. Failures end the call,
    // since there is no second attempt at a description.
    private fun created(then: (SessionDescription) -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) {
            main.post { then(description) }
        }
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) = fail("create", error)
        override fun onSetFailure(error: String?) = Unit
    }

    private fun done(then: () -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() {
            main.post { then() }
        }
        override fun onCreateFailure(error: String?) = Unit
        override fun onSetFailure(error: String?) = fail("set", error)
    }

    private fun fail(what: String, error: String?) {
        Log.w(TAG, "Can't $what a session description: $error")
        main.post { if (!released) listener.onFailed("$what: $error") }
    }

    companion object {
        private const val TAG = "CallMedia"
        private const val STREAM_ID = "call"
        // The Portal Go's screen is 1280x800; the other end scales to fit
        private const val CAPTURE_WIDTH = 1280
        private const val CAPTURE_HEIGHT = 720
        private const val CAPTURE_FPS = 30
        private const val GATHER_GIVE_UP_MILLIS = 5_000L
        private const val CANDIDATE_SETTLE_MILLIS = 300L
        private const val DISCONNECTED_GIVE_UP_MILLIS = 5_000L

        private var initialized = false

        // Once per process, and only in :call: the main process never loads
        // the library
        private fun initializeOnce(context: Context) {
            if (initialized) return
            PeerConnectionFactory.initialize(
                PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                    .createInitializationOptions()
            )
            initialized = true
        }
    }
}
