package com.nicobrailo.astrodock.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nicobrailo.astrodock.AlarmsActivity
import java.time.Instant
import java.time.ZoneId

// Rings the alarms, and keeps the system's one alarm for us pointing at the
// soonest of them. Only that one is ever scheduled: when it rings, or when
// anything changes (an edit, a reboot, the clock or the zone being set), the
// next is worked out again from the list. A reboot forgets every alarm, and
// they are in wall clock time, which a new zone moves.
//
// setAlarmClock rather than setExact: it is the one that wakes the device on
// time whatever doze thinks, and an alarm clock is what this is.
//
// An alarm for an app in Warmup.APPS has a second system alarm, a minute
// before it, which opens the app ahead of time (AlarmWarmup). It is set with
// the first and on the same occasions, and says which alarm it is for the
// same way.
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_RING -> ring(context, intent.getLongExtra(EXTRA_AT, 0))
            ACTION_WARM -> warm(context, intent.getLongExtra(EXTRA_AT, 0))
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> schedule(context)
        }
    }

    private fun ring(context: Context, at: Long) {
        val store = AlarmStore(context)
        val alarms = store.load()
        val due = AlarmSchedule.due(alarms, at, ZoneId.systemDefault())
        if (due.isEmpty()) {
            // Edited or deleted after it was scheduled, which would have
            // scheduled another; nothing to do but make sure of that
            Log.w(TAG, "Woken for an alarm at ${Instant.ofEpochMilli(at)} that no longer exists")
        } else {
            val dueIds = due.map { it.id }.toSet()
            store.save(alarms.map { if (it.id in dueIds && it.once) it.copy(enabled = false) else it })
            if (due.size > 1) Log.i(TAG, "${due.size} alarms at once, ringing the first")
            AlarmRinger.ring(context, due.first())
        }
        schedule(context)
    }

    private fun warm(context: Context, at: Long) {
        val alarm = AlarmSchedule.due(AlarmStore(context).load(), at, ZoneId.systemDefault()).firstOrNull()
        if (alarm == null) {
            Log.w(TAG, "Woken to warm up for an alarm at ${Instant.ofEpochMilli(at)} that no longer exists")
            return
        }
        AlarmWarmup.warm(context, alarm)
    }

    companion object {
        private const val TAG = "AlarmReceiver"
        private const val ACTION_RING = "com.nicobrailo.astrodock.alarm.RING"
        private const val ACTION_WARM = "com.nicobrailo.astrodock.alarm.WARM"
        // When it was scheduled for, which says which alarms it is for
        private const val EXTRA_AT = "at"

        // Schedules the soonest alarm, or cancels ours if none is on. Cheap,
        // so it is also called whenever a slideshow appears, which puts things
        // right after the app was force stopped (that drops our alarm too).
        fun schedule(context: Context) {
            val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
            val next = AlarmSchedule.soonest(AlarmStore(context).load(), System.currentTimeMillis(), ZoneId.systemDefault())
            if (next == null) {
                alarmManager.cancel(ringIntent(context, 0))
                alarmManager.cancel(warmIntent(context, 0))
                return
            }
            val (alarm, at) = next
            scheduleWarmup(alarmManager, context, alarm, at)
            // What the system opens from its own alarm icon, where it shows one
            val show = PendingIntent.getActivity(
                context,
                0,
                Intent(context, AlarmsActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            try {
                alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), ringIntent(context, at))
                Log.i(TAG, "Next alarm: ${alarm.time} (${alarm.id}) at ${Instant.ofEpochMilli(at)}")
            } catch (e: SecurityException) {
                // Only from Android 12, without the exact alarm permission
                Log.w(TAG, "Not allowed to set the alarm for ${alarm.time}", e)
            }
        }

        // Exact and allowed while idle rather than an alarm clock, which
        // would show the system's alarm icon for the wrong time. Not for an
        // alarm less than a minute away, whose minute has already begun.
        private fun scheduleWarmup(alarmManager: AlarmManager, context: Context, alarm: Alarm, at: Long) {
            val warmAt = at - Warmup.LEAD_MILLIS
            if (alarm.app !in Warmup.APPS || warmAt <= System.currentTimeMillis()) {
                alarmManager.cancel(warmIntent(context, 0))
                return
            }
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, warmAt, warmIntent(context, at))
            } catch (e: SecurityException) {
                Log.w(TAG, "Not allowed to set the warm up for ${alarm.time}", e)
            }
        }

        // One request code for all of them, so scheduling replaces what was
        // scheduled before
        private fun ringIntent(context: Context, at: Long): PendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, AlarmReceiver::class.java).setAction(ACTION_RING).putExtra(EXTRA_AT, at),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        // Another request code and action, so it doesn't replace the ring
        private fun warmIntent(context: Context, at: Long): PendingIntent = PendingIntent.getBroadcast(
            context,
            1,
            Intent(context, AlarmReceiver::class.java).setAction(ACTION_WARM).putExtra(EXTRA_AT, at),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
