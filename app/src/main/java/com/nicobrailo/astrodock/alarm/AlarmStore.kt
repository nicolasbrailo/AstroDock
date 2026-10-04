package com.nicobrailo.astrodock.alarm

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.DayOfWeek

// Saves the alarms in their own SharedPreferences file, as JSON:
// [{"id": ..., "enabled": true, "hour": 8, "minute": 0, "days": [1, 2, ...],
//   "app": "com.spotify.music", "play": "spotify:playlist:...",
//   "play_title": "Deep Focus", "shuffle": true, "volume": 30}]
// Days are ISO numbers, Monday 1 to Sunday 7; "app" is absent for the alarm
// sound alone.
class AlarmStore(context: Context) {
    private val prefs = context.getSharedPreferences("alarms", Context.MODE_PRIVATE)

    fun load(): List<Alarm> {
        val text = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(text)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                val days = obj.optJSONArray("days") ?: JSONArray()
                Alarm(
                    id = obj.getString("id"),
                    enabled = obj.optBoolean("enabled", true),
                    hour = obj.getInt("hour").coerceIn(0, 23),
                    minute = obj.getInt("minute").coerceIn(0, 59),
                    days = (0 until days.length()).map { DayOfWeek.of(days.getInt(it)) }.toSet(),
                    app = if (obj.isNull("app")) null else obj.optString("app").ifEmpty { null },
                    play = obj.optString("play"),
                    playTitle = obj.optString("play_title"),
                    shuffle = obj.optBoolean("shuffle", true),
                    volume = obj.optInt("volume", Alarm.DEFAULT_VOLUME).coerceIn(Alarm.VOLUME_RANGE),
                )
            }
        } catch (e: JSONException) {
            Log.w(TAG, "Ignoring unreadable alarms", e)
            emptyList()
        } catch (e: java.time.DateTimeException) {
            Log.w(TAG, "Ignoring alarms with a day that doesn't exist", e)
            emptyList()
        }
    }

    // Committed rather than applied: the receiver that rings an alarm saves
    // the one-off ones as switched off, and its process may not last long
    fun save(alarms: List<Alarm>) {
        val array = JSONArray()
        for (alarm in alarms) {
            array.put(
                JSONObject()
                    .put("id", alarm.id)
                    .put("enabled", alarm.enabled)
                    .put("hour", alarm.hour)
                    .put("minute", alarm.minute)
                    .put("days", JSONArray(alarm.days.sorted().map { it.value }))
                    .put("app", alarm.app ?: JSONObject.NULL)
                    .put("play", alarm.play)
                    .put("play_title", alarm.playTitle)
                    .put("shuffle", alarm.shuffle)
                    .put("volume", alarm.volume)
            )
        }
        prefs.edit().putString(KEY, array.toString()).commit()
    }

    private companion object {
        const val TAG = "AlarmStore"
        const val KEY = "alarms"
    }
}
