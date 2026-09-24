package com.nicobrailo.astrodock.mqtt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.os.Build
import android.util.Log
import android.provider.Settings as AndroidSettings
import androidx.preference.PreferenceManager
import java.util.UUID

// Where to publish the device's state. Edited in the MQTT tab of the settings
// (res/xml/mqtt_preferences.xml); the keys below must match that XML.
//
// The topics follow the homeboard bridge's spec (see its README), so a Portal
// running astrodock looks like another homeboard device on the broker, under
// its own topic prefix.
data class MqttSettings(
    val enabled: Boolean,
    val host: String,
    val port: Int,
    val clientId: String,
    val user: String,
    val password: String,
    val topicPrefix: String,
) {
    val isConfigured: Boolean get() = enabled && host.isNotBlank()

    companion object {
        const val KEY_ENABLED = "mqtt_enabled"
        const val KEY_HOST = "mqtt_host"
        const val KEY_PORT = "mqtt_port"
        const val KEY_CLIENT_ID = "mqtt_client_id"
        const val KEY_USER = "mqtt_user"
        const val KEY_PASSWORD = "mqtt_password"
        const val KEY_TOPIC_PREFIX = "mqtt_topic_prefix"
        const val KEY_AUDIO_ANNOUNCEMENTS = "mqtt_audio_announcements"

        const val DEFAULT_PORT = 1883
        val PORT_RANGE = 1..65535

        private const val TAG = "MqttSettings"

        // Identifies this device on the broker when there is no ANDROID_ID.
        // Generated once and kept, so it only survives while installed.
        private const val KEY_MACHINE_ID = "mqtt_machine_id"

        fun load(context: Context): MqttSettings {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val port = prefs.getString(KEY_PORT, null)?.trim()?.toIntOrNull()?.takeIf { it in PORT_RANGE }
                ?: DEFAULT_PORT
            val clientId = prefs.getString(KEY_CLIENT_ID, null)?.trim().orEmpty()
            return MqttSettings(
                enabled = prefs.getBoolean(KEY_ENABLED, false),
                host = prefs.getString(KEY_HOST, null)?.trim().orEmpty(),
                port = port,
                clientId = clientId.ifBlank { defaultClientId(context) },
                user = prefs.getString(KEY_USER, null)?.trim().orEmpty(),
                password = prefs.getString(KEY_PASSWORD, null).orEmpty(),
                topicPrefix = normalizePrefix(
                    prefs.getString(KEY_TOPIC_PREFIX, null), defaultTopicPrefix(context)
                ),
            )
        }

        // The spec requires a prefix ending in "/", and a missing slash would
        // silently publish to a neighbouring topic ("portalgostate/occupancy")
        fun normalizePrefix(raw: String?, default: String): String {
            val trimmed = raw?.trim()?.trimStart('/').orEmpty()
            if (trimmed.isEmpty()) return default
            return if (trimmed.endsWith("/")) trimmed else "$trimmed/"
        }

        // The name the user gave the device, which is how it should show up on
        // the broker: "astrodock" is the software, not the unit. On a Portal
        // that is the Bluetooth name ("Portaloft Portal"), since setup stores
        // what was typed there; the global device_name is only the model
        // ("PortalGo"), the same on every Portal Go, so two of them would
        // share a topic prefix.
        fun systemName(context: Context): String =
            bluetoothName(context)
                ?: AndroidSettings.Global.getString(context.contentResolver, "device_name")
                    ?.takeIf { it.isNotBlank() }
                ?: Build.MODEL

        // The adapter answers even with Bluetooth off, from the same setting
        // read below. Reading the setting directly needs no permission, but
        // it isn't public API, and Android 12 stopped letting apps read such
        // keys, so it's only the fallback. From Android 12 the adapter needs
        // a runtime permission we don't ask for, which lands here as a
        // SecurityException.
        @SuppressLint("MissingPermission")
        private fun bluetoothName(context: Context): String? {
            val fromAdapter = try {
                @Suppress("DEPRECATION")
                BluetoothAdapter.getDefaultAdapter()?.name
            } catch (e: SecurityException) {
                Log.i(TAG, "Can't read the Bluetooth name", e)
                null
            }
            fromAdapter?.takeIf { it.isNotBlank() }?.let { return it }
            return try {
                AndroidSettings.Secure.getString(context.contentResolver, "bluetooth_name")
                    ?.takeIf { it.isNotBlank() }
            } catch (e: SecurityException) {
                null
            }
        }

        fun defaultTopicPrefix(context: Context): String = "${topicName(systemName(context))}/"

        // The machine id keeps two devices of the same model apart: a broker
        // disconnects the older client when a second one uses its id
        fun defaultClientId(context: Context): String =
            "${topicName(systemName(context))}-${machineId(context).take(8)}"

        // Whether announce_audio may play anything. Not part of MqttSettings,
        // since changing it has nothing to do with the connection: it is read
        // as each command arrives, so switching it off silences the next one
        // without reconnecting.
        fun audioAnnouncementsAllowed(context: Context): Boolean =
            PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY_AUDIO_ANNOUNCEMENTS, true)

        // Device names are free text ("Nico's Portal"), topics are not
        fun topicName(raw: String): String = raw.trim().lowercase()
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '-' }
            .joinToString("")
            .trim('-')
            .ifEmpty { "portal" }

        // Tells this device's records on the broker from anyone else's (see
        // StateReporter's check for a taken prefix). ANDROID_ID is per device
        // and signing key, so it survives reinstalls, which a generated id
        // doesn't: a reinstalled device would find its own old record and
        // take it for someone else's.
        fun machineId(context: Context): String {
            AndroidSettings.Secure.getString(context.contentResolver, AndroidSettings.Secure.ANDROID_ID)
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            prefs.getString(KEY_MACHINE_ID, null)?.let { return it }
            val id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_MACHINE_ID, id).apply()
            return id
        }
    }
}
