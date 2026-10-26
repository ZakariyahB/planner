package app.planner.calendar

import java.time.LocalDate
import java.time.format.DateTimeParseException

/** An all-day calendar event as a task for one of its days: ticked off, never given a time block. */
data class AllDayTask(val key: String, val title: String, val date: LocalDate, val done: Boolean)

object AllDayTasks {
    private const val KEEP_DAYS = 31L

    /** The all-day events covering [date] on selected calendars, as tasks for that day, by title. */
    fun on(
        events: List<CalendarEvent>,
        selected: Set<String>,
        plannerCalendarId: String?,
        date: LocalDate,
        doneKeys: Set<String>,
    ): List<AllDayTask> =
        FixedEvents.allDayOn(events, selected, plannerCalendarId, date)
            .map { e -> key(e, date).let { AllDayTask(it, e.title, date, it in doneKeys) } }
            .sortedBy { it.title.lowercase() }

    /** One key per event per day, so a multi-day event is ticked off for each day separately. */
    fun key(event: CalendarEvent, date: LocalDate): String = "${event.calendarId}|${event.id}|$date"

    /** Drops ticks for days more than a month ago, and anything that isn't a key. */
    fun prune(doneKeys: Set<String>, today: LocalDate): Set<String> =
        doneKeys.filter { k -> dateOf(k)?.let { it >= today.minusDays(KEEP_DAYS) } == true }.toSet()

    private fun dateOf(key: String): LocalDate? = try {
        key.substringAfterLast('|', "").takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
    } catch (e: DateTimeParseException) {
        null
    }
}
