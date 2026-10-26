package app.planner.calendar

import java.time.Instant
import java.time.LocalDate
import kotlinx.serialization.Serializable

@Serializable
data class CalendarInfo(
    val id: String,
    val name: String,
    val colorHex: String? = null,
)

sealed interface EventTiming {
    data class Timed(val start: Instant, val end: Instant) : EventTiming

    /** [endExclusive] follows Google's convention: a one-day event ends the next day. */
    data class AllDay(val start: LocalDate, val endExclusive: LocalDate) : EventTiming
}

data class CalendarEvent(
    val id: String,
    val calendarId: String,
    val title: String,
    val timing: EventTiming,
)

/** A timed commitment the scheduler must work around. */
data class FixedEvent(
    val id: String,
    val calendarId: String,
    val title: String,
    val start: Instant,
    val end: Instant,
)

object FixedEvents {
    /** Calendars unticked by default: recordings are watched, not attended; prayer times anchor tasks instead. */
    private val unselectedByDefault = setOf("lectures", "prayer times")

    /** Never fixed: the app's own write calendar. */
    private const val PLANNER_NAME = "planner"

    fun defaultSelection(calendars: List<CalendarInfo>): Set<String> =
        calendars
            .filter { it.name.trim().lowercase().let { n -> n != PLANNER_NAME && n !in unselectedByDefault } }
            .map { it.id }
            .toSet()

    /** Timed events on selected calendars, excluding the Planner calendar, in start order. All-day events are ignored. */
    fun from(events: List<CalendarEvent>, selected: Set<String>, plannerCalendarId: String?): List<FixedEvent> =
        events
            .filter { it.calendarId in selected && it.calendarId != plannerCalendarId }
            .mapNotNull { e ->
                (e.timing as? EventTiming.Timed)?.let { FixedEvent(e.id, e.calendarId, e.title, it.start, it.end) }
            }
            .sortedBy { it.start }

    /** All-day events covering [date], shown as a context strip only. */
    fun allDayOn(events: List<CalendarEvent>, selected: Set<String>, plannerCalendarId: String?, date: LocalDate): List<CalendarEvent> =
        events.filter { e ->
            val t = e.timing
            e.calendarId in selected && e.calendarId != plannerCalendarId &&
                t is EventTiming.AllDay && !date.isBefore(t.start) && date.isBefore(t.endExclusive)
        }
}
