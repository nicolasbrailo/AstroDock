package com.nicobrailo.alauncher.mqtt

import android.content.Context
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.preference.PreferenceManager
import java.util.UUID

// Where to publish the device's state. Edited in the MQTT tab of the settings
// (res/xml/mqtt_preferences.xml); the keys below must match that XML.
//
// The topics follow the homeboard bridge's spec (see its README), so a Portal
// running alauncher looks like another homeboard device on the broker, under
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

        const val DEFAULT_PORT = 1883
        val PORT_RANGE = 1..65535

        // Identifies this device on the broker. Generated once and kept, so it
        // survives reinstalls of nothing but stays stable while installed.
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

        // What the device calls itself ("PortalGo"), which is how it should
        // show up on the broker: "alauncher" is the software, not the unit
        fun systemName(context: Context): String =
            AndroidSettings.Global.getString(context.contentResolver, "device_name")
                ?.takeIf { it.isNotBlank() }
                ?: Build.MODEL

        fun defaultTopicPrefix(context: Context): String = "${topicName(systemName(context))}/"

        // The machine id keeps two devices of the same model apart: a broker
        // disconnects the older client when a second one uses its id
        fun defaultClientId(context: Context): String =
            "${topicName(systemName(context))}-${machineId(context).take(8)}"

        // Device names are free text ("Nico's Portal"), topics are not
        fun topicName(raw: String): String = raw.trim().lowercase()
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '-' }
            .joinToString("")
            .trim('-')
            .ifEmpty { "portal" }

        fun machineId(context: Context): String {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            prefs.getString(KEY_MACHINE_ID, null)?.let { return it }
            val id = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_MACHINE_ID, id).apply()
            return id
        }
    }
}
