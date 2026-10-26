package app.planner.routines

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.Instant

/** The AlarmManager side of the timed routines: one alarm per routine, delivered to [RoutineAlarmReceiver]. */
object AlarmClock {
    const val ACTION = "app.planner.routines.ALARM"
    const val EXTRA_ROUTINE = "routine"

    private const val REQUEST_BASE = 700

    /** Exact alarms need the user's say-so from Android 12; without it alarms can drift by minutes. */
    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
    }

    /** Sets the alarm for [routine], replacing the previous one. Returns true if it is exact. */
    fun set(context: Context, routine: Routine, at: Instant): Boolean {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        val pending = pending(context, routine, create = true) ?: return false
        val millis = at.toEpochMilli()
        if (canScheduleExact(context)) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
                return true
            } catch (e: SecurityException) {
                // Permission withdrawn between the check and the call: fall through to the inexact alarm.
            }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
        return false
    }

    fun cancel(context: Context, routine: Routine) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        pending(context, routine, create = false)?.let {
            manager.cancel(it)
            it.cancel()
        }
    }

    private fun pending(context: Context, routine: Routine, create: Boolean): PendingIntent? {
        val intent = Intent(context, RoutineAlarmReceiver::class.java)
            .setAction(ACTION)
            .putExtra(EXTRA_ROUTINE, routine.name)
        val flags = PendingIntent.FLAG_IMMUTABLE or if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        return PendingIntent.getBroadcast(context, REQUEST_BASE + routine.ordinal, intent, flags)
    }
}
