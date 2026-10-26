package app.planner.routines

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.Instant
import java.time.ZoneId

/**
 * A timed routine's exact alarm. It only hands over to a worker and sets the next occurrence; the worker
 * (which can read the calendar) then corrects that alarm for packed days.
 */
class RoutineAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val routine = Routine.parse(intent.getStringExtra(AlarmClock.EXTRA_ROUTINE))?.takeIf { it.timed } ?: return
        val app = context.applicationContext
        RoutineWork.fire(app, routine)
        // Safety net: the next one is set even if the worker never gets to run.
        runCatching { AlarmClock.set(app, routine, RoutineSchedule.next(routine, Instant.now(), ZoneId.systemDefault())) }
    }
}

/** Buttons on notifications. */
class RoutineActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_LIGHTEN) RoutineWork.lighten(context.applicationContext)
    }

    companion object {
        const val ACTION_LIGHTEN = "app.planner.routines.LIGHTEN"

        fun lightenAction(context: Context): NotificationAction = NotificationAction(
            "Lighten today",
            PendingIntent.getBroadcast(
                context,
                REQUEST_LIGHTEN,
                Intent(context, RoutineActionReceiver::class.java).setAction(ACTION_LIGHTEN),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )

        private const val REQUEST_LIGHTEN = 800
    }
}

/** Alarms do not survive a reboot, and their local time shifts with the zone, so set them again. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        RoutineWork.startPeriodic(app)
        RoutineWork.reschedule(app)
    }
}

/**
 * Registered at runtime by [RoutineBootstrap]: the system does not deliver ACTION_USER_PRESENT to receivers
 * declared in the manifest. The 10:00 fallback in the periodic job covers a process that was not alive.
 */
class UserPresentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_USER_PRESENT) RoutineWork.morningOnUnlock(context.applicationContext)
    }
}
