package com.nicobrailo.astrodock.mqtt

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import android.util.Log
import androidx.core.content.ContextCompat
import com.nicobrailo.astrodock.BuildConfig
import com.nicobrailo.astrodock.R
import com.nicobrailo.astrodock.ScreenControl
import com.nicobrailo.astrodock.Settings
import com.nicobrailo.astrodock.SlideshowState
import com.nicobrailo.astrodock.audio.AnnouncementPlayer
import com.nicobrailo.astrodock.call.CallPeer
import com.nicobrailo.astrodock.call.CallRouter
import com.nicobrailo.astrodock.call.CallSettings
import com.nicobrailo.astrodock.call.CallVerb
import com.nicobrailo.astrodock.call.Calls
import com.nicobrailo.astrodock.immich.AlbumFilter
import com.nicobrailo.astrodock.immich.ImmichPictureInfo
import com.nicobrailo.astrodock.presence.PortalLog
import com.nicobrailo.astrodock.presence.PortalPresence
import com.nicobrailo.astrodock.weather.millisToNextHour
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
import org.eclipse.paho.client.mqttv3.MqttClient
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

// Publishes what this device is doing to an MQTT broker, following the
// homeboard bridge's topics (see its README):
//
//   <prefix>availability            online/offline record, offline also as the
//                                   last will, so a crash still says so
//   <prefix>state                   everything else about the device, as one
//                                   record (see stateJson)
//   <prefix>state/displayed_photo   the picture on screen, our own schema
//
// Everything is retained and QoS 0, as in the spec, so a client that subscribes
// later still sees the current state. <prefix>state replaces the spec's
// state/occupancy and state/slideshow_active; it is published whenever any of
// it changes, and never just to say nothing did.
//
// It also subscribes to <prefix>cmd/# and carries out the commands that mean
// something here (see Command.kt): the screen ones itself, the slideshow ones
// through whichever slideshow is on screen, and the call ones through
// CallRouter.
//
// It also reads every device's availability record (`+/availability`), which
// is the list of devices that can be called, and sends the call messages to
// them (see CALLING.md).
//
// Differences from the spec, all because this is a Portal and not the
// homeboard: there is no `distance_cm` (no mmWave sensor; occupancy comes from
// the Portal's camera, read off the log by PortalLog, or failing that is a
// guess from the screen, see Occupancy), and the render config fields of
// availability are left out because nothing here has them.
//
// One instance per process. Its methods are safe to call from the main thread:
// the work happens on its own scope.
class StateReporter private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val powerManager = context.getSystemService(PowerManager::class.java)

    // From connect() until it is replaced or given up on, connected or not:
    // Paho keeps reconnecting one that has connected before
    @Volatile
    private var client: MqttAsyncClient? = null
    private val mainThread = Handler(Looper.getMainLooper())
    // The slideshow currently on screen, which carries out its commands
    private var commandListener: ((Command) -> Unit)? = null
    // What is wrong with the broker, null while it is fine. Nothing on screen
    // depends on MQTT, so a broker that can't be reached would otherwise only
    // show up in the log; the slideshow puts this in a corner instead. The
    // Portal's presence detection getting stuck goes there too, since the
    // only other sign of it is a screen that doesn't wake.
    @Volatile
    var alert: String? = null
        private set
    @Volatile
    private var brokerAlert: String? = null
    @Volatile
    private var presenceAlert: String? = null
    private var watchingPortal = false
    @Volatile
    private var alertListener: ((String?) -> Unit)? = null
    private var settings: MqttSettings? = null
    private var connectWatchdog: Job? = null
    // Checking the prefix and then connecting (see checkPrefix)
    private var connectJob: Job? = null
    // Settings whose prefix another device turned out to own. They aren't
    // tried again, since that would only find the same device again: changing
    // them, or restarting the app, does.
    @Volatile
    private var refused: MqttSettings? = null
    private var watchingDevice = false
    // A failure shows up the same way as a text announcement, on whichever
    // slideshow is on screen
    private val announcer = AnnouncementPlayer(context) { message ->
        carryOut(Command.Announce(message, ANNOUNCE_ERROR_SECONDS))
    }

    // What goes into <prefix>state, all of it used on the main thread. The
    // slideshow runs in two places (the home screen and the screensaver), and
    // they hand over in either order, so each says whether it is showing and
    // whether it is under the night cover, in the order they appeared.
    private val showing = LinkedHashMap<String, Boolean>()
    private var displayedPhoto: JSONObject? = null
    private var screenOn = powerManager?.isInteractive != false
    private var screenChangedAt = SystemClock.elapsedRealtime()
    // Wall clock, for the report; unknown until the screen first changes
    private var screenOnSince: Long? = null
    // Whether a screensaver (ours or the Portal's) is running; unknown until
    // one starts or stops
    private var dreaming: Boolean? = null
    // force_off switched the screen off, and it hasn't come back on since
    private var forcedOff = false
    // What is wrong, by where it comes from ("immich", "weather")
    private val errors = LinkedHashMap<String, String>()
    private var battery: Battery? = null
    // The readings as last published, which only move on a significant change
    private var rssi: Float? = null
    private var lux: Float? = null
    // What was last published, without its timestamp, to tell a change
    private var lastState: String? = null
    private val startedAt = System.currentTimeMillis() / 1000
    private val bootedAt = (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 1000
    // Every device's availability record, by prefix, ours included
    private val peers = ConcurrentHashMap<String, CallPeer>()
    // Whether the availability record we last published said we take calls
    @Volatile
    private var advertisedCalls: Boolean? = null
    private val recheck = Runnable { publishState() }
    private val publishNow = Runnable { publishState(force = false, now = true) }

    private data class Battery(
        val level: Int?,
        val status: String,
        val plugged: String,
        val health: String,
        val present: Boolean,
        val technology: String?,
        val temperatureC: Float?,
        val voltageV: Float?,
    )

    private val deviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF -> {
                    val on = intent.action == Intent.ACTION_SCREEN_ON
                    if (on == screenOn) return
                    screenOn = on
                    screenChangedAt = SystemClock.elapsedRealtime()
                    screenOnSince = System.currentTimeMillis() / 1000
                    // force_off is done with once anything wakes the screen
                    if (on) forcedOff = false
                }
                Intent.ACTION_DREAMING_STARTED -> dreaming = true
                Intent.ACTION_DREAMING_STOPPED -> dreaming = false
                Intent.ACTION_BATTERY_CHANGED -> battery = readBattery(intent)
                WifiManager.RSSI_CHANGED_ACTION, WifiManager.NETWORK_STATE_CHANGED_ACTION -> {
                    val reading = readRssi()
                    if (!DeviceState.significant(rssi, reading, RSSI_STEP_DBM)) return
                    rssi = reading
                }
            }
            publishState()
        }
    }

    private val lightListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val reading = event.values.firstOrNull() ?: return
            if (!DeviceState.significant(lux, reading, LUX_STEP, LUX_STEP_FRACTION)) return
            lux = reading
            publishState()
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    init {
        ScreenControl.onHoldChanged = { publishState() }
    }

    // Connects, or reconnects with the new settings if they changed. Called
    // whenever the slideshow becomes visible, so edits in the MQTT tab take
    // effect without a restart.
    fun applySettings() {
        // Every time rather than once, so a grant given over adb while the app
        // runs is picked up the next time a slideshow appears. Before the
        // broker settings, since the alert and the calls need it without MQTT.
        watchPortal()
        // Not part of MqttSettings, since it doesn't need a new connection:
        // the record only has to say so
        val calls = CallSettings.load(context).enabled
        if (advertisedCalls != null && calls != advertisedCalls) {
            publish(RetainedTopics.AVAILABILITY, availabilityPayload(online = true))
        }

        val newSettings = MqttSettings.load(context)
        // The home screen and the screensaver both call this as they appear,
        // often while the first connect is still under way. Replacing a client
        // that is still connecting used to leave it running, and two clients
        // with one client id take turns kicking each other off the broker.
        if (newSettings == settings && (client != null || connectJob?.isActive == true)) return
        if (newSettings == refused) return
        settings = newSettings
        disconnect()
        if (!newSettings.isConfigured) {
            setAlert(null)
            return
        }

        watchDevice()
        connectJob = scope.launch {
            // Settings that changed while this was checking have a check of
            // their own under way
            val stale = checkPrefix(newSettings) ?: return@launch
            if (settings === newSettings) connect(newSettings, stale)
        }
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
        brokerAlert = message
        showAlert()
    }

    private fun showAlert() {
        val message = listOfNotNull(brokerAlert, presenceAlert).joinToString("\n\n").ifEmpty { null }
        if (message == alert) return
        alert = message
        val listener = alertListener ?: return
        mainThread.post { if (alertListener === listener) listener(message) }
    }

    // Called on the main thread by each slideshow as it appears, goes under
    // the night cover or back, and goes away
    fun onSlideshowShown(source: String, shown: Boolean, dark: Boolean) {
        if (!shown) {
            showing.remove(source)
        } else {
            // Going dark and back leaves it where it was in the order
            showing[source] = dark
            // Our screensaver only runs as one
            if (source == SCREENSAVER) dreaming = true
        }
        publishState()
    }

    // Called on the main thread with what is wrong with `source`, or null once
    // it works again
    fun setError(source: String, message: String?) {
        if (message == null) errors.remove(source) else errors[source] = message
        publishState()
    }

    // Called on the main thread when something the report reads changed
    // without telling it, such as the album filter or a call
    fun refresh() = publishState()

    // Text over the pictures for the rest of the app (the alarms), shown the
    // way an MQTT announcement is, so the two replace each other like any two
    // announcements. Called on the main thread.
    fun announce(message: String, timeoutSeconds: Int, owner: Any? = null) =
        carryOut(Command.Announce(message, timeoutSeconds, owner))

    fun endAnnouncement(owner: Any, afterSeconds: Int) =
        carryOut(Command.EndAnnouncement(owner, afterSeconds))

    // Our prefix, which a call offer gives as where to answer, while there is
    // a connection to answer on
    val ownPrefix: String?
        get() = settings?.topicPrefix?.takeIf { client?.isConnected == true }

    // The devices this one can call now (see Calls.callable)
    fun callablePeers(): List<CallPeer> = Calls.callable(peers.values, settings?.topicPrefix)

    // Whether a screensaver (ours or the Portal's) is running, as far as the
    // system's broadcasts have said
    val isDreaming: Boolean get() = dreaming == true

    // Sends a call message to another device: not retained, since a retained
    // one would come back on every reconnect, and QoS 1, since a lost hangup
    // would leave a camera on. False if there is no connection to send it on;
    // a send that fails later only shows in the log, and the call gives up on
    // its own timeout.
    fun sendCall(peerPrefix: String, verb: CallVerb, payload: JSONObject): Boolean {
        val current = client ?: return false
        if (!current.isConnected) return false
        val topic = Calls.topic(peerPrefix, verb)
        scope.launch {
            try {
                current.publish(topic, MqttMessage(payload.toString().toByteArray()).apply {
                    qos = CALL_QOS
                    isRetained = false
                })
            } catch (e: MqttException) {
                Log.w(TAG, "Can't send $topic", e)
            }
        }
        return true
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

    private fun connectOptions(settings: MqttSettings) = MqttConnectOptions().apply {
        isCleanSession = true
        connectionTimeout = CONNECT_TIMEOUT_SECONDS
        keepAliveInterval = KEEPALIVE_SECONDS
        if (settings.user.isNotBlank()) {
            userName = settings.user
            password = settings.password.toCharArray()
        }
    }

    // Two devices under one prefix overwrite each other's retained records and
    // both carry out every command, so before publishing anything we read the
    // retained availability record: one with another machine_id means another
    // device has this prefix, and we stay off the broker and say so. An empty
    // or missing record is free, which is also how to hand a prefix over.
    //
    // It reads everything retained under the prefix while it's there, and
    // returns what isn't ours any more (topics an older version published, a
    // retained command; see RetainedTopics) for connect() to clear. If the
    // device was renamed since it last connected, it also returns what it
    // left under the old prefix (see leftUnder).
    //
    // It looks on a connection of its own, with no last will: the real
    // connection carries our offline record as its will, and if that
    // connection dropped while we looked, the broker would publish it over
    // the other device's record.
    //
    // Blocks, so it runs on the scope. Null when the prefix is taken, and also
    // when the broker can't be reached, which it reports the same way
    // connect() would.
    private fun checkPrefix(settings: MqttSettings): Set<String>? {
        val topic = settings.topicPrefix + RetainedTopics.AVAILABILITY
        val probe = try {
            MqttClient("tcp://${settings.host}:${settings.port}", "${settings.clientId}-check", MemoryPersistence())
        } catch (e: MqttException) {
            Log.w(TAG, "Can't connect to ${settings.host}:${settings.port}", e)
            cantConnect(settings, e)
            return null
        }
        // Covers the CONNACK too, which connectionTimeout doesn't (see
        // watchConnect)
        probe.timeToWait = CONNECT_GIVE_UP_MILLIS
        try {
            probe.connect(connectOptions(settings))
            val prefix = settings.topicPrefix
            val retained = retainedUnder(probe, prefix)
            val owner = otherOwner(retained[topic])
            if (owner == null) {
                val stale = RetainedTopics.toClear(retained.keys, prefix, keep = RetainedTopics.ours(prefix))
                val old = MqttSettings.previousPrefix(context, settings) ?: return stale
                return stale + leftUnder(probe, old, prefix)
            }
            Log.w(TAG, "${settings.topicPrefix} belongs to $owner, not connecting")
            refused = settings
            setAlert(context.getString(R.string.mqtt_alert_prefix_taken, settings.topicPrefix, owner, topic))
            return null
        } catch (e: MqttException) {
            Log.w(TAG, "Can't check ${settings.host}:${settings.port} for $topic", e)
            if (e.reasonCode == MqttException.REASON_CODE_CLIENT_TIMEOUT.toInt()) {
                setAlert(context.getString(R.string.mqtt_alert_no_answer, settings.host, settings.port))
            } else {
                cantConnect(settings, e)
            }
            return null
        } finally {
            try {
                if (probe.isConnected) probe.disconnect() else probe.disconnectForcibly(0, 0)
                probe.close()
            } catch (e: MqttException) {
                Log.w(TAG, "Can't close the check's connection", e)
            }
        }
    }

    // Everything retained under `prefix`, by topic. Blocks.
    private fun retainedUnder(probe: MqttClient, prefix: String): Map<String, ByteArray> {
        val retained = ConcurrentHashMap<String, ByteArray>()
        val lastArrival = AtomicLong(0)
        probe.subscribe(prefix + "#", QOS) { t, message ->
            if (message.isRetained && message.payload.isNotEmpty()) {
                retained[t] = message.payload
                lastArrival.set(SystemClock.elapsedRealtime())
            }
        }
        // The broker sends the retained messages straight after the
        // subscription, all together, so a short wait is enough to say there
        // are none, and a short quiet spell that there are no more
        val start = SystemClock.elapsedRealtime()
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val last = lastArrival.get()
            if (now - start >= RETAINED_WAIT_MILLIS) break
            if (last != 0L && now - last >= RETAINED_QUIET_MILLIS) break
            Thread.sleep(RETAINED_POLL_MILLIS)
        }
        probe.unsubscribe(prefix + "#")
        return retained
    }

    // What this device left under the prefix it used before being renamed,
    // to be cleared with the rest. Nothing if someone else has that prefix
    // now: what is there is theirs. The old connection was closed cleanly, so
    // the broker didn't publish its last will, and its availability still says
    // online until this clears it.
    private fun leftUnder(probe: MqttClient, oldPrefix: String, newPrefix: String): Set<String> {
        val retained = retainedUnder(probe, oldPrefix)
        val owner = otherOwner(retained[oldPrefix + RetainedTopics.AVAILABILITY])
        if (owner != null) {
            Log.i(TAG, "$oldPrefix belongs to $owner now, leaving it alone")
            return emptySet()
        }
        return RetainedTopics.toClear(retained.keys, oldPrefix, skipPrefix = newPrefix)
    }

    // Who a retained availability record says it belongs to, as its hostname
    // or machine id, or null if it is ours or nobody's. A record that doesn't
    // say whose it is still means some device publishes there.
    private fun otherOwner(record: ByteArray?): String? {
        if (record == null || record.isEmpty()) return null
        val json = try {
            JSONObject(String(record))
        } catch (e: JSONException) {
            return context.getString(R.string.mqtt_prefix_owner_unknown)
        }
        val machineId = json.optString("machine_id")
        if (machineId == MqttSettings.machineId(context)) return null
        return json.optString("hostname").ifBlank { machineId }
            .ifBlank { context.getString(R.string.mqtt_prefix_owner_unknown) }
    }

    // `stale` is what checkPrefix found retained that we don't publish any
    // more; it's cleared once, on the first connect, not on every reconnect
    private fun connect(settings: MqttSettings, stale: Set<String>) {
        var toClear = stale
        val options = connectOptions(settings).apply {
            isAutomaticReconnect = true
            // Latched at connect time: the broker publishes this if we vanish
            setWill(
                settings.topicPrefix + RetainedTopics.AVAILABILITY,
                availabilityPayload(online = false).toString().toByteArray(),
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
                    // One replaced while it connected; it's being shut down
                    if (client !== newClient) return
                    Log.i(TAG, "Connected to $serverUri")
                    connectWatchdog?.cancel()
                    setAlert(null)
                    subscribeToCommands()
                    publishEverything()
                    for (t in toClear) clearRetained(newClient, t)
                    toClear = emptySet()
                    // Whatever was under an older prefix is gone now
                    MqttSettings.notePublished(context, settings)
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
                    if (client !== newClient) return
                    Log.w(TAG, "Can't connect to ${settings.host}:${settings.port}", e)
                    cantConnect(settings, e)
                    // Paho won't try again, so the next applySettings() has
                    // to start a new one
                    giveUp(newClient)
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
            giveUp(newClient)
        }
    }

    // Forgets a client that isn't going anywhere, so applySettings() starts
    // afresh
    private fun giveUp(stuck: MqttAsyncClient) {
        if (client === stuck) client = null
        shutDown(stuck)
    }

    // Stops a client for good, whatever state it is in. Plain disconnect()
    // only works on a connected one, and close() refuses one that is still
    // connecting, which then carried on and connected anyway. A connected
    // one still says goodbye, so the broker doesn't publish its last will.
    private fun shutDown(old: MqttAsyncClient) {
        scope.launch {
            try {
                old.disconnectForcibly(QUIESCE_MILLIS, DISCONNECT_MILLIS)
            } catch (e: MqttException) {
                Log.w(TAG, "Can't disconnect", e)
            }
            try {
                old.close()
            } catch (e: MqttException) {
                Log.w(TAG, "Can't close the connection", e)
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
            // QoS 1 so the call messages keep theirs; the rest are sent at 0,
            // which a subscription can't raise
            client?.subscribe(settings.topicPrefix + Commands.TOPIC_FILTER, CALL_QOS)
            // The devices that can be called. One level, like the homeboard's
            // own listing: a prefix like home/kitchen/ isn't found.
            client?.subscribe("+/" + RetainedTopics.AVAILABILITY, QOS)
        } catch (e: MqttException) {
            Log.w(TAG, "Can't subscribe to commands", e)
            setAlert(context.getString(R.string.mqtt_alert_subscribe, reason(e)))
        }
    }

    private fun disconnect() {
        connectWatchdog?.cancel()
        connectJob?.cancel()
        val old = client ?: return
        client = null
        shutDown(old)
    }

    // Turns one message into a command and hands it to whoever carries it out.
    // Bad payloads are dropped with a log line, as the spec says.
    private fun receive(topic: String, message: MqttMessage) {
        val settings = settings ?: return
        Calls.peerPrefix(topic, RetainedTopics.AVAILABILITY)?.let { prefix ->
            notePeer(prefix, message.payload)
            return
        }
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
        // {"uri":"http://10.0.0.20:8080/tts/x.mp3","msg":"Dinner is ready","volume":40}.
        // Only the uri is required. A volume that is missing or makes no sense
        // still plays, at the default: the message matters more than how loud
        // it is.
        CommandKind.ANNOUNCE_AUDIO -> {
            val json = JSONObject(payload)
            val uri = json.optString("uri").trim()
            // optString would turn a JSON null into the text "null"
            val message = if (json.isNull("msg")) null else json.optString("msg").trim().ifEmpty { null }
            var volume = json.optInt("volume", -1)
            if (volume !in 0..100) {
                Log.w(TAG, "Bad announcement volume ${json.opt("volume")}, using $DEFAULT_ANNOUNCE_VOLUME%")
                volume = DEFAULT_ANNOUNCE_VOLUME
            }
            if (uri.isEmpty()) null else Command.AnnounceAudio(uri, message, volume)
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
        CommandKind.CALL_OFFER -> callSignal(CallVerb.OFFER, payload)
        CommandKind.CALL_ANSWER -> callSignal(CallVerb.ANSWER, payload)
        CommandKind.CALL_REJECT -> callSignal(CallVerb.REJECT, payload)
        CommandKind.CALL_HANGUP -> callSignal(CallVerb.HANGUP, payload)
    }

    // A device's availability record, or its removal
    private fun notePeer(prefix: String, payload: ByteArray) {
        if (payload.isEmpty()) {
            peers.remove(prefix)
            return
        }
        val json = try {
            JSONObject(String(payload))
        } catch (e: JSONException) {
            Log.i(TAG, "Unreadable availability record under $prefix")
            return
        }
        peers[prefix] = CallPeer(
            prefix = prefix,
            online = json.optString("state") == "online",
            acceptsCalls = json.optBoolean("calls", false),
        )
    }

    // {"call_id":"...","from":"kitchen-portal/","ts":1790000000,"sdp":"v=0..."}
    // for an offer; the others only need what their verb uses. The caller's
    // prefix is where the replies go, so it must be one we can publish under.
    private fun callSignal(verb: CallVerb, payload: String): Command? {
        val json = JSONObject(payload)
        fun text(key: String): String? = if (json.isNull(key)) null else json.optString(key).ifEmpty { null }
        val callId = text("call_id")?.trim()?.takeIf { it.length <= MAX_CALL_ID } ?: return null
        return when (verb) {
            CallVerb.OFFER -> {
                val from = text("from")
                    ?.takeIf { f -> f.none { it == '+' || it == '#' } }
                    ?.let { MqttSettings.normalizePrefix(it, "") }
                    ?.ifEmpty { null }
                    ?: return null
                val sdp = text("sdp") ?: return null
                Command.CallSignal(verb, callId, from = from, sentAt = json.optLong("ts", 0), sdp = sdp)
            }
            CallVerb.ANSWER -> Command.CallSignal(verb, callId, sdp = text("sdp") ?: return null)
            CallVerb.REJECT -> Command.CallSignal(verb, callId, reason = text("reason"))
            CallVerb.HANGUP -> Command.CallSignal(verb, callId, reason = text("reason"))
        }
    }

    private fun carryOut(command: Command) {
        when (command) {
            // The screen doesn't belong to the slideshow, and these have to work
            // even when nothing is on screen
            Command.ForceOn -> {
                forcedOff = false
                ScreenControl.forceScreenOn(context, FORCE_ON_MILLIS)
            }
            Command.ForceOff -> {
                ScreenControl.releaseForcedOn()
                if (ScreenControl.canTurnScreenOff(context)) {
                    forcedOff = true
                    publishState()
                    ScreenControl.turnScreenOff(context, ScreenControl.FORCE_OFF)
                } else {
                    // Needs the device admin from the System tab
                    Log.w(TAG, "Can't turn the screen off: no device admin")
                }
            }
            // Calls come in with nothing on screen, and bring their own
            is Command.CallSignal -> CallRouter.get(context).onSignal(command)
            // Played whether or not anything is on screen: that is when it's
            // most likely to matter
            is Command.AnnounceAudio -> {
                if (!MqttSettings.audioAnnouncementsAllowed(context)) {
                    Log.i(TAG, "Audio announcements are off, not playing ${command.uri}")
                    return
                }
                val message = command.message ?: context.getString(R.string.announce_audio_default_msg)
                // Stays up while it plays, since nobody knows how long that
                // is until it's over. Anything shown in the meantime wins,
                // which the owner lets the end of it check.
                val owner = Any()
                announcer.play(
                    command.uri,
                    command.volumePercent,
                    onStarted = { carryOut(Command.Announce(message, 0, owner)) },
                    onFinished = { carryOut(Command.EndAnnouncement(owner, ANNOUNCE_AUDIO_LINGER_SECONDS)) },
                )
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
        publish(RetainedTopics.AVAILABILITY, availabilityPayload(online = true))
        displayedPhoto?.let { publish("state/displayed_photo", it) }
        mainThread.post { publishState(force = true) }
    }

    // Publishes <prefix>state if anything in it changed. Also works out when
    // it could next change with nobody saying so (a hold timing out, the
    // screen staying on long enough to mean presence, the night starting or
    // ending), and looks again then.
    //
    // Changes are gathered for a moment first: handing over between the home
    // screen and the screensaver is several of them within a few
    // milliseconds, which would otherwise be as many records, the ones in
    // between saying nothing is on screen.
    private fun publishState(force: Boolean = false, now: Boolean = force) {
        if (!now) {
            mainThread.removeCallbacks(publishNow)
            mainThread.postDelayed(publishNow, GATHER_MILLIS)
            return
        }
        mainThread.removeCallbacks(publishNow)
        publishStateNow(force)
    }

    private fun publishStateNow(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val state = stateJson(now)
        scheduleRecheck(now)
        val text = state.toString()
        if (!force && text == lastState) return
        lastState = text
        publish("state", state.put("ts", System.currentTimeMillis() / 1000))
    }

    // Everything but the timestamp, so two records can be compared. Unknown
    // is null rather than left out, so a consumer can tell it from a field
    // this version doesn't send.
    private fun stateJson(now: Long): JSONObject {
        val slideshow = SlideshowState.shared
        val hold = ScreenControl.hold()
        val call = CallRouter.get(context)
        // A call holds the screen with its window, not a wake lock
        val holdReason = hold?.reason ?: CallRouter.SCREEN_REASON.takeIf { call.active }
        val wish = DeviceState.screenWish(holdReason, forcedOff, slideshow.nightRuleApplies(context, now))
        val filter = slideshow.currentSettings?.albumFilter
        val portal = PortalLog.presence.takeIf { PortalLog.isReading }

        return JSONObject()
            .put("occupancy", occupancyJson(now))
            .put(
                "slideshow",
                JSONObject()
                    // Nobody can see a slideshow under the night cover, nor
                    // the one the system starts while the screen is off
                    .put("active", screenOn && showing.values.any { dark -> !dark })
                    .put("shown_in", orNull(showing.keys.lastOrNull()))
                    .put("night_cover", showing.values.any { dark -> dark })
                    .put("album_filter", orNull(filter?.let { albumFilterJson(it) }))
            )
            .put(
                "screen",
                JSONObject()
                    .put("on", screenOn)
                    .put("since", orNull(screenOnSince))
                    .put("screensaver", orNull(dreaming))
                    .put("wanted", orNull(wish.wanted))
                    .put("wanted_reason", orNull(wish.reason))
                    // What the Portal says it is doing, and who last turned
                    // the screen on and off, from its log (PortalLog)
                    .put("portal_state", orNull(portal?.portalState))
                    .put("last_wake", orNull(portal?.lastWake?.let { screenChangeJson(it) }))
                    .put("last_sleep", orNull(portal?.lastSleep?.let { screenChangeJson(it) }))
            )
            .put("errors", JSONArray().apply {
                for ((source, message) in errors) put(JSONObject().put("source", source).put("message", message))
            })
            // A Portal without a battery reports one that is absent and empty
            .put("battery", orNull(battery?.takeIf { it.present }?.let { batteryJson(it) }))
            .put("call", call.stateJson())
            .put("wifi_rssi", orNull(rssi?.toInt()))
            .put("light_lux", orNull(lux?.toInt()))
            .put(
                "app",
                JSONObject()
                    .put("version", BuildConfig.VERSION_NAME)
                    .put("version_code", BuildConfig.VERSION_CODE)
                    // Timestamps rather than uptimes, which would change on
                    // every look
                    .put("started_at", startedAt)
                    .put("device_booted_at", bootedAt)
            )
    }

    // From the Portal's camera while its log can be read, with why it can't
    // see when it can't; otherwise the guess from the screen, which can't
    // tell when the camera is blind
    private fun occupancyJson(now: Long): JSONObject {
        if (PortalLog.isReading) {
            val presence = PortalLog.presence
            presence.update(System.currentTimeMillis())
            val verdict = presence.verdict
            return JSONObject()
                .put("occupied", orNull(verdict.occupied))
                .put("source", Occupancy.CAMERA)
                .put("since", orNull(presence.verdictSince?.let { it / 1000 }))
                .put("blind_reason", orNull(verdict.blind?.wire))
        }
        val guess = Occupancy.guess(screenOn, now - screenChangedAt, screensaverAfterMillis())
        return JSONObject()
            .put("occupied", guess.occupied)
            .put("source", guess.source)
            .put("since", JSONObject.NULL)
            .put("blind_reason", JSONObject.NULL)
    }

    // A lock is ours if we called lockNow() just before the line was logged,
    // and then `by` says why; another device admin's leaves it null
    private fun screenChangeJson(change: PortalPresence.ScreenChange): JSONObject {
        val ours = ScreenControl.lastLock?.takeIf { (_, at) -> abs(change.at - at) < OUR_LOCK_MILLIS }
        return JSONObject()
            .put("cause", change.cause)
            .put("at", change.at / 1000)
            .put("details", change.details)
            .put("by", orNull(ours?.first?.takeIf { change.cause == PortalPresence.SLEEP_LOCK }))
    }

    private fun scheduleRecheck(now: Long) {
        val slideshow = SlideshowState.shared
        val screensaverAfter = screensaverAfterMillis()
        val wallNow = System.currentTimeMillis()
        val deadlines = listOfNotNull(
            // The last camera report expiring, in this clock
            PortalLog.presence.nextChangeAt(wallNow)?.let { now + (it - wallNow) },
            ScreenControl.hold()?.until,
            // When an untouched screen would have slept, if it's still on
            if (screenOn && screensaverAfter > 0) screenChangedAt + screensaverAfter else null,
            slideshow.nightGraceEndsAt,
            // The night rule starts and ends on the hour
            now + millisToNextHour(Instant.now(), ZoneId.systemDefault()),
        ).filter { it > now }
        mainThread.removeCallbacks(recheck)
        // A moment late, so whatever was due is past rather than a millisecond short
        deadlines.minOrNull()?.let { mainThread.postDelayed(recheck, it - now + RECHECK_SLACK_MILLIS) }
    }

    private fun screensaverAfterMillis(): Long = AndroidSettings.System.getInt(
        context.contentResolver, AndroidSettings.System.SCREEN_OFF_TIMEOUT, -1
    ).toLong()

    // In the same field names set_album_filter takes, as the spec's
    // state/album_filter has them
    private fun albumFilterJson(filter: AlbumFilter) = JSONObject()
        .put("name", filter.include)
        .put("exclude", filter.exclude)
        .put("from_year", filter.fromYear)
        .put("to_year", filter.toYear)

    private fun batteryJson(b: Battery) = JSONObject()
        .put("level", orNull(b.level))
        .put("status", b.status)
        .put("plugged", b.plugged)
        .put("health", b.health)
        .put("technology", orNull(b.technology))
        .put("temperature_c", orNull(b.temperatureC?.toDouble()))
        .put("voltage_v", orNull(b.voltageV?.toDouble()))

    // The battery broadcast comes with every millivolt, so the temperature and
    // the voltage keep what was last published until they move enough
    private fun readBattery(intent: Intent): Battery {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val tenthsC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val millivolts = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)
        val temperature = if (tenthsC == Int.MIN_VALUE) null else tenthsC / 10f
        val voltage = if (millivolts <= 0) null else millivolts / 1000f
        val old = battery
        return Battery(
            level = if (level >= 0 && scale > 0) level * 100 / scale else null,
            status = when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
                BatteryManager.BATTERY_STATUS_FULL -> "full"
                else -> "unknown"
            },
            plugged = when (intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
                BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                0 -> "none"
                else -> "other"
            },
            health = when (intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
                BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over_voltage"
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "failure"
                BatteryManager.BATTERY_HEALTH_COLD -> "cold"
                else -> "unknown"
            },
            present = intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false),
            technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.takeIf { it.isNotBlank() },
            temperatureC = if (DeviceState.significant(old?.temperatureC, temperature, TEMPERATURE_STEP_C)) {
                temperature
            } else {
                old?.temperatureC
            },
            voltageV = if (DeviceState.significant(old?.voltageV, voltage, VOLTAGE_STEP_V)) voltage else old?.voltageV,
        )
    }

    // The signal of the Wi-Fi network the device is on, or null if it isn't
    // on one
    @Suppress("DEPRECATION") // The replacement needs a network callback, and API 29 has no other way to get it
    private fun readRssi(): Float? {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
        val info = wifi.connectionInfo ?: return null
        if (info.networkId == -1 || info.rssi <= NO_RSSI) return null
        return info.rssi.toFloat()
    }

    // Everything the report reads that the system announces. Registered once,
    // for the life of the process, since it all runs on the main thread and
    // costs nothing while nothing changes.
    private fun watchDevice() {
        if (watchingDevice) return
        watchingDevice = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_DREAMING_STARTED)
            addAction(Intent.ACTION_DREAMING_STOPPED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(WifiManager.RSSI_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
        }
        // Only the system sends these, and an app that targets Android 14 has
        // to say so explicitly. The battery broadcast is sticky, so this also
        // returns the current reading.
        ContextCompat.registerReceiver(context, deviceReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            ?.let { battery = readBattery(it) }
        rssi = readRssi()
        val sensors = context.getSystemService(SensorManager::class.java)
        sensors?.getDefaultSensor(Sensor.TYPE_LIGHT)?.let {
            sensors.registerListener(lightListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun watchPortal() {
        if (!watchingPortal) {
            watchingPortal = true
            PortalLog.addListener {
                checkPresenceStuck()
                publishState()
            }
        }
        PortalLog.start(context)
    }

    // Called on the main thread with every change PortalLog sees
    private fun checkPresenceStuck() {
        val message = if (PortalLog.isReading && PortalLog.presence.stuck) {
            context.getString(R.string.presence_stuck)
        } else {
            null
        }
        if (message == presenceAlert) return
        presenceAlert = message
        if (message == null) errors.remove(PRESENCE_ERROR) else errors[PRESENCE_ERROR] = message
        showAlert()
    }

    private fun availabilityPayload(online: Boolean): JSONObject {
        // The offline payload carries only what can't go stale: no IP (DHCP
        // drifts) and nothing derived, as in the spec
        val payload = JSONObject()
            .put("state", if (online) "online" else "offline")
            .put("machine_id", MqttSettings.machineId(context))
            .put("hostname", MqttSettings.systemName(context))
            .put("host_model", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("started_at", startedAt)
        if (!online) return payload
        val calls = CallSettings.load(context).enabled
        advertisedCalls = calls
        return payload
            .put("started_at_iso", DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochSecond(startedAt)))
            .put("ip", ipAddress() ?: JSONObject.NULL)
            .put("app", "astrodock")
            // Whether other devices can call this one (see CALLING.md)
            .put("calls", calls)
    }

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

    private fun orNull(value: Any?): Any = value ?: JSONObject.NULL

    // On the client that just connected, which may not be `client` by the
    // time this runs
    private fun clearRetained(via: MqttAsyncClient, topic: String) {
        Log.i(TAG, "Clearing the retained $topic")
        scope.launch {
            try {
                if (!via.isConnected) return@launch
                via.publish(topic, ByteArray(0), QOS, true)
            } catch (e: MqttException) {
                Log.w(TAG, "Can't clear $topic", e)
            }
        }
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
        private const val CALL_QOS = 1
        // A UUID is 36; anything much longer isn't one of ours
        private const val MAX_CALL_ID = 64
        private const val KEEPALIVE_SECONDS = 30
        private const val CONNECT_TIMEOUT_SECONDS = 10
        // Twice the above, since that only covers opening the socket
        private const val CONNECT_GIVE_UP_MILLIS = 20_000L
        // How long the prefix check waits for a retained record after
        // subscribing, before deciding there is none
        private const val RETAINED_WAIT_MILLIS = 2_000L
        // ...and how long without another one before deciding that was all
        private const val RETAINED_QUIET_MILLIS = 300L
        private const val RETAINED_POLL_MILLIS = 50L
        // How long a disconnect waits for work in flight, and for the broker
        private const val QUIESCE_MILLIS = 1_000L
        private const val DISCONNECT_MILLIS = 2_000L
        // How far a reading has to move before the state is published again
        private const val RSSI_STEP_DBM = 5f
        // A dark room reads anything from 0 to 3 lx from one second to the next
        private const val LUX_STEP = 5f
        private const val LUX_STEP_FRACTION = 0.25f
        private const val TEMPERATURE_STEP_C = 1f
        private const val VOLTAGE_STEP_V = 0.05f
        // What WifiManager reports for no signal at all
        private const val NO_RSSI = -127
        private const val RECHECK_SLACK_MILLIS = 500L
        private const val GATHER_MILLIS = 300L
        // The `errors` source for the Portal's presence detection
        private const val PRESENCE_ERROR = "presence"
        // How close to our lockNow() a lock in the log has to be to be ours
        private const val OUR_LOCK_MILLIS = 5_000L
        // How long force_on holds the screen before the usual timeouts resume
        private const val FORCE_ON_MILLIS = 30 * 60 * 1000L
        // How long an announcement that failed says so on screen
        private const val ANNOUNCE_ERROR_SECONDS = 30
        // For an audio announcement whose volume is missing or out of range
        private const val DEFAULT_ANNOUNCE_VOLUME = 40
        // How long an audio announcement's text stays after the audio ends
        private const val ANNOUNCE_AUDIO_LINGER_SECONDS = 10

        // The names the slideshows report themselves by, which the state
        // publishes as `shown_in`
        const val HOME = "home"
        const val SCREENSAVER = "screensaver"

        @Volatile
        private var instance: StateReporter? = null

        fun get(context: Context): StateReporter = instance ?: synchronized(this) {
            instance ?: StateReporter(context.applicationContext).also { instance = it }
        }
    }
}
