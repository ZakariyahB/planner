package app.planner.routines

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

/** What the system settings and the Settings screen call a routine. */
data class RoutineInfo(val label: String, val description: String, val importance: Int)

val Routine.info: RoutineInfo
    get() = when (this) {
        Routine.PROVISIONAL_READINESS -> RoutineInfo(
            "Back to bed",
            "After you wake near Fajr: how much sleep you have had and whether to go back to bed",
            NotificationManager.IMPORTANCE_HIGH,
        )
        Routine.MORNING_BRIEF -> RoutineInfo(
            "Morning brief",
            "When you unlock your phone after waking: readiness and the day ahead",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        Routine.WORK_STOPS -> RoutineInfo(
            "Work stops",
            "Evening: what got done and what moved",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        Routine.PLAN_READY -> RoutineInfo(
            "Tomorrow's plan",
            "23:30: tomorrow's plan to approve, and the readiness forecast",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
        Routine.LIGHTS_OUT -> RoutineInfo(
            "Lights-out warning",
            "30 minutes before lights-out",
            NotificationManager.IMPORTANCE_HIGH,
        )
        Routine.SUNDAY_REVIEW -> RoutineInfo(
            "Sunday review",
            "Sunday 19:00: review the week and plan the next",
            NotificationManager.IMPORTANCE_DEFAULT,
        )
    }

/** One channel per routine, so each can be muted from the system settings too. */
object RoutineChannels {
    fun id(routine: Routine): String = "routine_${routine.name.lowercase()}"

    /** Safe to call on every start: creating a channel that exists changes nothing the user has set. */
    fun create(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        for (routine in Routine.entries) {
            val info = routine.info
            manager.createNotificationChannel(
                NotificationChannel(id(routine), info.label, info.importance).apply { description = info.description },
            )
        }
    }
}
