package app.planner.calendar

import app.planner.model.Task
import app.planner.model.TaskSource
import app.planner.model.TaskStatus
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * All-day calendar events as real tasks, one per event per day, so the planner gives each a time in the day
 * instead of leaving it as a note with no slot. The task's [Task.templateKey] ties it to its event.
 */
object CalendarTasks {
    private const val KEY_PREFIX = "cal:"

    /** An event with no length in its title gets this much time. */
    const val DEFAULT_MIN = 60

    /** An unfinished item is kept this many days after its day, then let go. */
    private const val CARRY_DAYS = 2L

    fun templateKey(allDayKey: String): String = KEY_PREFIX + allDayKey

    fun isCalendarTask(task: Task): Boolean = task.source == TaskSource.CALENDAR

    /** The all-day key ([AllDayTasks.key]) a calendar task was made from. */
    fun allDayKey(task: Task): String? = task.templateKey?.takeIf { it.startsWith(KEY_PREFIX) }?.removePrefix(KEY_PREFIX)

    /** What to change so the tasks match the events: new ones to add, and ones whose event is gone to drop. */
    data class Sync(val create: List<Task>, val drop: List<Task>)

    fun sync(
        events: List<CalendarEvent>,
        selected: Set<String>,
        plannerCalendarId: String?,
        from: LocalDate,
        days: Int,
        zone: ZoneId,
        existing: List<Task>,
        doneKeys: Set<String>,
    ): Sync {
        val have = existing.mapNotNull { t -> t.templateKey?.takeIf { isCalendarTask(t) }?.let { it to t } }.toMap()
        val wanted = linkedMapOf<String, Task>()
        for (offset in 0 until days) {
            val date = from.plusDays(offset.toLong())
            for (event in FixedEvents.allDayOn(events, selected, plannerCalendarId, date)) {
                val key = AllDayTasks.key(event, date)
                val hints = AllDayHintParser.parse(event.title)
                wanted[templateKey(key)] = Task(
                    title = hints.title,
                    estMin = hints.estMin ?: DEFAULT_MIN,
                    startAt = hints.startAt,
                    dueAt = date.atTime(LocalTime.of(23, 59)).atZone(zone).toInstant(),
                    source = TaskSource.CALENDAR,
                    status = if (key in doneKeys) TaskStatus.DONE else TaskStatus.OPEN,
                    templateKey = templateKey(key),
                )
            }
        }

        val create = wanted.filterKeys { it !in have }.values.toList()

        // With no events at all the calendar is probably not connected or not loaded: leave the tasks alone.
        val calendarLoaded = events.isNotEmpty()
        val drop = have.values.filter { t ->
            val open = t.status == TaskStatus.OPEN || t.status == TaskStatus.PARTIAL
            val date = t.templateKey?.let(::dateOf)
            open && date != null && (
                date < from.minusDays(CARRY_DAYS) ||
                    (calendarLoaded && date >= from && date < from.plusDays(days.toLong()) && t.templateKey !in wanted)
                )
        }.map { it.copy(status = TaskStatus.DROPPED) }
        return Sync(create, drop)
    }

    private fun dateOf(key: String): LocalDate? = try {
        key.substringAfterLast('|', "").takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
    } catch (e: DateTimeParseException) {
        null
    }
}
