package com.nicobrailo.astrodock.mqtt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.util.Log
import androidx.core.content.ContextCompat
import com.nicobrailo.astrodock.R
import com.nicobrailo.astrodock.ScreenControl
import com.nicobrailo.astrodock.Settings
import com.nicobrailo.astrodock.immich.AlbumFilter
import com.nicobrailo.astrodock.immich.ImmichPictureInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.IMqttToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.time.Instant
import java.time.format.DateTimeFormatter

// Publishes what this device is doing to an MQTT broker, following the
// homeboard bridge's topics (see its README):
//
//   <prefix>state/bridge            online/offline record, offline also as the
//                                   last will, so a crash still says so
//   <prefix>state/occupancy         {"occupied":..,"ts":..,"source":..}
//   <prefix>state/slideshow_active  {"active":..}
//   <prefix>state/displayed_photo   the picture on screen, our own schema
//
// Everything is retained and QoS 0, as in the spec, so a client that subscribes
// later still sees the current state.
//
// It also subscribes to <prefix>cmd/# and carries out the commands that mean
// something here (see Command.kt): the screen ones itself, the slideshow ones
// through whichever slideshow is on screen.
//
// Differences from the spec, all because this is a Portal and not the
// homeboard: `distance_cm` is never published (no mmWave sensor, and occupancy
// is a guess from the screen — see Occupancy), and the render config fields of
// state/bridge are left out because nothing here has them.
//
// One instance per process. Its methods are safe to call from the main thread:
// the work happens on its own scope.
class StateReporter private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val powerManager = context.getSystemService(PowerManager::class.java)

    private var client: MqttAsyncClient? = null
    private val mainThread = Handler(Looper.getMainLooper())
    // The slideshow currently on screen, which carries out its commands
    private var commandListener: ((Command) -> Unit)? = null
    // What is wrong with the broker, null while it is fine. Nothing on screen
    // depends on MQTT, so a broker that can't be reached would otherwise only
    // show up in the log; the slideshow puts this in a corner instead.
    @Volatile
    var alert: String? = null
        private set
    @Volatile
    private var alertListener: ((String?) -> Unit)? = null
    private var settings: MqttSettings? = null
    private var occupancyJob: Job? = null
    private var connectWatchdog: Job? = null
    private var watchingScreen = false

    // The state we publish, kept so a reconnect can republish all of it.
    // The slideshow runs in two places (the home screen and the screensaver),
    // and they hand over in either order, so each says whether it is showing
    // and the topic reports whether any of them is.
    private val showing = mutableSetOf<String>()
    private var displayedPhoto: JSONObject? = null
    private var lastSlideshowActive: Boolean? = null
    private var lastGuess: Occupancy.Guess? = null
    private var screenOn = powerManager?.isInteractive != false
    private var screenChangedAt = SystemClock.elapsedRealtime()
    private val startedAt = System.currentTimeMillis() / 1000

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val on = intent.action == Intent.ACTION_SCREEN_ON
            if (on == screenOn) return
            screenOn = on
            screenChangedAt = SystemClock.elapsedRealtime()
            publishOccupancy()
        }
    }

    // Connects, or reconnects with the new settings if they changed. Called
    // whenever the slideshow becomes visible, so edits in the MQTT tab take
    // effect without a restart.
    fun applySettings() {
        val newSettings = MqttSettings.load(context)
        if (newSettings == settings && client?.isConnected == true) return
        settings = newSettings
        disconnect()
        if (!newSettings.isConfigured) {
            setAlert(null)
            return
        }

        watchScreen()
        scope.launch { connect(newSettings) }
    }

    // Set by the slideshow that is on screen; the screensaver and the home
    // screen take turns, so the last one to start wins and each only lets go of
    // its own registration
    fun setCommandListener(listener: (Command) -> Unit) {
        commandListener = listener
    }

    fun clearCommandListener(listener: (Command) -> Unit) {
        if (commandListener === listener) commandListener = null
    }

    // Called on the main thread, at once with whatever is wrong now and again
    // whenever that changes
    fun setAlertListener(listener: (String?) -> Unit) {
        alertListener = listener
        listener(alert)
    }

    fun clearAlertListener(listener: (String?) -> Unit) {
        if (alertListener === listener) alertListener = null
    }

    private fun setAlert(message: String?) {
        if (message == alert) return
        alert = message
        val listener = alertListener ?: return
        mainThread.post { if (alertListener === listener) listener(message) }
    }

    fun onSlideshowVisible(source: String, visible: Boolean) {
        synchronized(showing) {
            if (visible) showing += source else showing -= source
        }
        publishSlideshowActive()
        publishOccupancy()
    }

    // Handing over between the home screen and the screensaver stops one and
    // starts the other, which is two calls saying the same thing
    private fun publishSlideshowActive(force: Boolean = false) {
        val active = synchronized(showing) { showing.isNotEmpty() }
        if (!force && active == lastSlideshowActive) return
        lastSlideshowActive = active
        publish("state/slideshow_active", JSONObject().put("active", active))
    }

    // The picture on screen. info is null while its metadata hasn't arrived.
    //
    // The field names follow what the homeboard's photo provider publishes, so
    // the same renderer can read either device. Immich has no albums on disk,
    // so `albumname` is the Immich album and the paths are where the server
    // keeps the original. `reverse_geo` is filled from the picture's own EXIF
    // place names — nothing is looked up.
    fun onPhotoShown(pictureId: String, albumName: String, srcUrl: String?, info: ImmichPictureInfo?) {
        val photo = JSONObject()
            .put("id", pictureId)
            .put("albumname", albumName)
            .put("ts", System.currentTimeMillis() / 1000)
        if (srcUrl != null) photo.put("src_url", srcUrl)
        if (info != null) {
            photo.put("filename", info.fileName)
                .put("EXIF DateTimeOriginal", info.taken)
                .put("camera", listOf(info.cameraMake, info.cameraModel).filter { it.isNotBlank() }.joinToString(" "))
                .put("people", JSONArray(info.people))
            if (info.originalPath.isNotBlank()) {
                photo.put("local_path", info.originalPath)
                // The directory the original sits in on the server, which is
                // the closest thing Immich has to the other device's album
                // directory
                val directory = info.originalPath.substringBeforeLast('/', "")
                if (directory.isNotBlank()) photo.put("albumpath", directory)
            }
            if (info.description.isNotBlank()) photo.put("description", info.description)
            info.width?.let { photo.put("width", it) }
            info.height?.let { photo.put("height", it) }
            if (info.latitude != null && info.longitude != null) {
                photo.put("gps", JSONObject().put("lat", info.latitude).put("lon", info.longitude))
            }
            val place = JSONObject()
            if (info.city.isNotBlank()) place.put("city", info.city)
            if (info.state.isNotBlank()) place.put("state", info.state)
            if (info.country.isNotBlank()) place.put("country", info.country)
            if (place.length() > 0) photo.put("reverse_geo", place)
        }
        displayedPhoto = photo
        publish("state/displayed_photo", photo)
    }

    private fun connect(settings: MqttSettings) {
        val options = MqttConnectOptions().apply {
            isCleanSession = true
            isAutomaticReconnect = true
            connectionTimeout = CONNECT_TIMEOUT_SECONDS
            keepAliveInterval = KEEPALIVE_SECONDS
            if (settings.user.isNotBlank()) {
                userName = settings.user
                password = settings.password.toCharArray()
            }
            // Latched at connect time: the broker publishes this if we vanish
            setWill(
                settings.topicPrefix + "state/bridge",
                bridgePayload(online = false).toString().toByteArray(),
                QOS,
                true,
            )
        }

        try {
            val newClient = MqttAsyncClient(
                "tcp://${settings.host}:${settings.port}",
                settings.clientId,
                MemoryPersistence(),
            )
            newClient.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverUri: String?) {
                    Log.i(TAG, "Connected to $serverUri")
                    connectWatchdog?.cancel()
                    setAlert(null)
                    subscribeToCommands()
                    publishEverything()
                }

                override fun connectionLost(cause: Throwable?) {
                    Log.w(TAG, "Connection lost, reconnecting", cause)
                    setAlert(
                        context.getString(
                            R.string.mqtt_alert_lost, settings.host, settings.port, reason(cause)
                        )
                    )
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    if (topic != null && message != null) receive(topic, message)
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
            })
            client = newClient
            // Paho only reconnects by itself once it has been connected, so a
            // first connect that fails is the end of it until applySettings()
            // comes round again, which the slideshow does every time it appears.
            // Either way the failure is only reported here, asynchronously.
            newClient.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) = Unit

                override fun onFailure(asyncActionToken: IMqttToken?, e: Throwable?) {
                    Log.w(TAG, "Can't connect to ${settings.host}:${settings.port}", e)
                    cantConnect(settings, e)
                }
            })
            watchConnect(newClient, settings)
        } catch (e: MqttException) {
            // A bad host or a broken client: nothing was even attempted
            Log.w(TAG, "Can't connect to ${settings.host}:${settings.port}", e)
            cantConnect(settings, e)
        }
    }

    // Paho's connectionTimeout only covers opening the socket. Once that is
    // open it waits for the broker's CONNACK with no deadline of its own, so a
    // port that accepts the connection and then says nothing — an HTTP server
    // behind the wrong port number, a firewall that swallows the reply — leaves
    // the connect hanging for ever, with neither connectComplete nor onFailure.
    // Nothing would ever say so, so we give it a deadline and drop the stuck
    // client, which lets the next applySettings() start a fresh one.
    private fun watchConnect(newClient: MqttAsyncClient, settings: MqttSettings) {
        connectWatchdog?.cancel()
        connectWatchdog = scope.launch {
            delay(CONNECT_GIVE_UP_MILLIS)
            if (client !== newClient || newClient.isConnected) return@launch
            Log.w(TAG, "No answer from ${settings.host}:${settings.port}")
            setAlert(
                context.getString(R.string.mqtt_alert_no_answer, settings.host, settings.port)
            )
            try {
                newClient.disconnectForcibly(0, 0)
            } catch (e: MqttException) {
                Log.w(TAG, "Can't drop the stuck connection", e)
            }
        }
    }

    private fun cantConnect(settings: MqttSettings, cause: Throwable?) {
        setAlert(
            context.getString(
                R.string.mqtt_alert_connect, settings.host, settings.port, reason(cause)
            )
        )
    }

    // Paho's messages are short ("Unable to connect to server"); its cause says
    // what actually happened ("failed to connect to /10.0.0.10 (port 5000)")
    private fun reason(cause: Throwable?): String {
        val message = cause?.message?.takeIf { it.isNotBlank() }
        val inner = cause?.cause?.message?.takeIf { it.isNotBlank() && it != message }
        return listOfNotNull(message, inner).joinToString(": ").ifEmpty {
            cause?.javaClass?.simpleName.orEmpty()
        }
    }

    private fun subscribeToCommands() {
        val settings = settings ?: return
        try {
            client?.subscribe(settings.topicPrefix + Commands.TOPIC_FILTER, QOS)
        } catch (e: MqttException) {
            Log.w(TAG, "Can't subscribe to commands", e)
            setAlert(context.getString(R.string.mqtt_alert_subscribe, reason(e)))
        }
    }

    private fun disconnect() {
        occupancyJob?.cancel()
        connectWatchdog?.cancel()
        val old = client ?: return
        client = null
        scope.launch {
            try {
                if (old.isConnected) old.disconnect()
                old.close()
            } catch (e: MqttException) {
                Log.w(TAG, "Can't close the connection", e)
            }
        }
    }

    // Turns one message into a command and hands it to whoever carries it out.
    // Bad payloads are dropped with a log line, as the spec says.
    private fun receive(topic: String, message: MqttMessage) {
        val settings = settings ?: return
        val kind = Commands.kind(topic, settings.topicPrefix)
        if (kind == null) {
            Log.i(TAG, "Ignoring $topic")
            return
        }
        // A retained command is whatever was sent last, possibly days ago, and
        // it arrives again on every reconnect. Acting on it would replay it.
        if (message.isRetained) {
            Log.i(TAG, "Ignoring retained $topic")
            return
        }

        val command = try {
            toCommand(kind, String(message.payload))
        } catch (e: JSONException) {
            Log.w(TAG, "Bad payload for $topic", e)
            return
        }
        if (command == null) {
            Log.w(TAG, "Bad payload for $topic: ${String(message.payload).take(200)}")
            return
        }
        Log.i(TAG, "Command $topic")
        mainThread.post { carryOut(command) }
    }

    private fun toCommand(kind: CommandKind, payload: String): Command? = when (kind) {
        CommandKind.NEXT -> Command.Next
        CommandKind.PREVIOUS -> Command.Previous
        CommandKind.FORCE_ON -> Command.ForceOn
        CommandKind.FORCE_OFF -> Command.ForceOff
        CommandKind.TRANSITION_SECONDS -> {
            val seconds = JSONObject(payload).optInt("secs", -1)
            if (seconds in Settings.SLIDE_SECONDS_RANGE) Command.TransitionSeconds(seconds) else null
        }
        CommandKind.ANNOUNCE -> {
            val json = JSONObject(payload)
            // timeout 0 means it stays until something replaces it
            Command.Announce(json.optString("msg"), json.optInt("timeout", 0).coerceAtLeast(0))
        }
        // {"name":"holidays-*,Pets","exclude":"Screenshots","from_year":2019,"to_year":2021}
        // Every field is optional and the payload replaces the whole filter, so
        // "{}" shows every album again.
        CommandKind.ALBUM_FILTER -> {
            val json = JSONObject(payload)
            val from = json.optInt("from_year", 0)
            val to = json.optInt("to_year", 0)
            if (from !in Settings.YEAR_RANGE || to !in Settings.YEAR_RANGE) {
                null
            } else {
                Command.SetAlbumFilter(
                    AlbumFilter(
                        include = json.optString("name").trim(),
                        exclude = json.optString("exclude").trim(),
                        fromYear = from,
                        toYear = to,
                    )
                )
            }
        }
    }

    private fun carryOut(command: Command) {
        when (command) {
            // The screen doesn't belong to the slideshow, and these have to work
            // even when nothing is on screen
            Command.ForceOn -> ScreenControl.forceScreenOn(context, FORCE_ON_MILLIS)
            Command.ForceOff -> {
                ScreenControl.releaseForcedOn()
                if (ScreenControl.canTurnScreenOff(context)) {
                    ScreenControl.turnScreenOff(context)
                } else {
                    // Needs the device admin from the System tab
                    Log.w(TAG, "Can't turn the screen off: no device admin")
                }
            }
            else -> {
                val listener = commandListener
                if (listener == null) {
                    Log.i(TAG, "Dropping $command: no slideshow on screen")
                } else {
                    listener(command)
                }
            }
        }
    }

    // Everything a late subscriber should see, published on every connect
    private fun publishEverything() {
        publish("state/bridge", bridgePayload(online = true))
        publishSlideshowActive(force = true)
        displayedPhoto?.let { publish("state/displayed_photo", it) }
        publishOccupancy(force = true)
        startOccupancyUpdates()
    }

    // The spec's occupancy record. `ts` is what tells a consumer how fresh this
    // is, so it's refreshed on a timer even when the guess doesn't change.
    private fun publishOccupancy(force: Boolean = false) {
        val screensaverAfter = AndroidSettings.System.getInt(
            context.contentResolver, AndroidSettings.System.SCREEN_OFF_TIMEOUT, -1
        ).toLong()
        val guess = Occupancy.guess(screenOn, SystemClock.elapsedRealtime() - screenChangedAt, screensaverAfter)
        if (!force && guess == lastGuess) return
        lastGuess = guess
        publish(
            "state/occupancy",
            JSONObject()
                .put("occupied", guess.occupied)
                .put("ts", System.currentTimeMillis() / 1000)
                // Not in the spec: says how much the guess is worth, since
                // there's no real sensor behind it
                .put("source", guess.source)
        )
    }

    private fun startOccupancyUpdates() {
        occupancyJob?.cancel()
        occupancyJob = scope.launch {
            while (true) {
                delay(OCCUPANCY_REFRESH_MILLIS)
                publishOccupancy(force = true)
            }
        }
    }

    private fun watchScreen() {
        if (watchingScreen) return
        watchingScreen = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // Only the system sends these, and an app that targets Android 14 has
        // to say so explicitly
        ContextCompat.registerReceiver(context, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun bridgePayload(online: Boolean): JSONObject {
        // The offline payload carries only what can't go stale: no IP (DHCP
        // drifts) and nothing derived, as in the spec
        val payload = JSONObject()
            .put("state", if (online) "online" else "offline")
            .put("machine_id", MqttSettings.machineId(context))
            .put("hostname", hostname())
            .put("host_model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("started_at", startedAt)
        if (!online) return payload
        return payload
            .put("started_at_iso", DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(startedAt)))
            .put("ip", ipAddress() ?: JSONObject.NULL)
            .put("app", "astrodock")
    }

    private fun hostname(): String =
        AndroidSettings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    // The address of whichever interface is carrying traffic; there's no
    // hostname resolution on the device to ask instead
    private fun ipAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filterNot { it.isLoopback }
            .filter { it.isUp }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull()
            ?.hostAddress
    } catch (e: Exception) {
        Log.w(TAG, "Can't read the IP address", e)
        null
    }

    private fun publish(topicSuffix: String, payload: JSONObject) {
        val settings = settings ?: return
        val current = client ?: return
        val topic = settings.topicPrefix + topicSuffix
        scope.launch {
            try {
                if (!current.isConnected) return@launch
                current.publish(topic, MqttMessage(payload.toString().toByteArray()).apply {
                    qos = QOS
                    isRetained = true
                })
            } catch (e: MqttException) {
                Log.w(TAG, "Can't publish $topic", e)
            }
        }
    }

    companion object {
        private const val TAG = "StateReporter"
        private const val QOS = 0
        private const val KEEPALIVE_SECONDS = 30
        private const val CONNECT_TIMEOUT_SECONDS = 10
        // Twice the above, since that only covers opening the socket
        private const val CONNECT_GIVE_UP_MILLIS = 20_000L
        private const val OCCUPANCY_REFRESH_MILLIS = 60_000L
        // How long force_on holds the screen before the usual timeouts resume
        private const val FORCE_ON_MILLIS = 30 * 60 * 1000L

        @Volatile
        private var instance: StateReporter? = null

        fun get(context: Context): StateReporter = instance ?: synchronized(this) {
            instance ?: StateReporter(context.applicationContext).also { instance = it }
        }
    }
}
