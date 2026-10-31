package app.planner.engine.checkin

import java.time.LocalDateTime

/**
 * One alarm the device should hold: a stable id, a local wall-clock time, and a fingerprint of what the
 * notification will say (so a renamed block is set again even when its time did not change).
 */
data class PlannedAlarm(val id: String, val at: LocalDateTime, val fingerprint: String = at.toString())

/**
 * What a reschedule does to the alarms already set. Setting an alarm under an id that is already set replaces it
 * (the app keys each alarm by its id), so nothing is ever duplicated; [cancel] removes alarms whose block moved
 * away, was removed, or is now merged into a "Next up" ping.
 */
data class AlarmDiff(
    val set: List<PlannedAlarm>,
    val cancel: Set<String>,
    val unchanged: Set<String>,
    /** What to remember for the next reschedule: id to fingerprint of every alarm that is now set. */
    val remembered: Map<String, String>,
) {
    companion object {
        /**
         * [previous] is what the last reschedule remembered (id to fingerprint). Only alarms strictly after [now] are
         * set: a ping whose time has passed is not replayed. Unchanged alarms (same id and fingerprint) are left alone,
         * unless [force]: after a reboot the system has dropped every alarm, and after a time zone or clock change the
         * same wall-clock time is a different instant, so everything is set again.
         */
        fun between(previous: Map<String, String>, planned: List<PlannedAlarm>, now: LocalDateTime, force: Boolean = false): AlarmDiff {
            val upcoming = planned.filter { it.at.isAfter(now) }.distinctBy { it.id }
            val ids = upcoming.map { it.id }.toSet()
            val unchanged = if (force) emptySet() else upcoming.filter { previous[it.id] == it.fingerprint }.map { it.id }.toSet()
            return AlarmDiff(
                set = upcoming.filter { it.id !in unchanged },
                cancel = previous.keys - ids,
                unchanged = unchanged,
                remembered = upcoming.associate { it.id to it.fingerprint },
            )
        }
    }
}

/** Everything the notification shows goes into the fingerprint, so any visible change re-sets the alarm. */
fun CheckIn.toAlarm(): PlannedAlarm = PlannedAlarm(
    id = id,
    at = at,
    fingerprint = listOf(at, kind, block.title, block.plannedStart, block.plannedEnd, next?.title, next?.plannedStart, prayer).joinToString("|"),
)
