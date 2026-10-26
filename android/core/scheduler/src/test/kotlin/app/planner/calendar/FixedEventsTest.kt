package app.planner.calendar

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FixedEventsTest {
    private fun timed(id: String, cal: String, start: String, end: String) =
        CalendarEvent(id, cal, id, EventTiming.Timed(Instant.parse(start), Instant.parse(end)))

    private fun allDay(id: String, cal: String, start: String, endExclusive: String) =
        CalendarEvent(id, cal, id, EventTiming.AllDay(LocalDate.parse(start), LocalDate.parse(endExclusive)))

    private val bol = timed("bol", "main", "2026-10-28T10:15:00Z", "2026-10-28T12:45:00Z")
    private val halaqa = timed("halaqa", "main", "2026-10-28T14:00:00Z", "2026-10-28T16:30:00Z")
    private val lecture = timed("lecture", "lectures", "2026-10-28T09:00:00Z", "2026-10-28T10:00:00Z")
    private val planned = timed("planned", "planner", "2026-10-28T17:00:00Z", "2026-10-28T18:00:00Z")
    private val holiday = allDay("holiday", "main", "2026-10-28", "2026-10-29")

    @Test
    fun `timed events on selected calendars become fixed events in start order`() {
        val fixed = FixedEvents.from(listOf(halaqa, bol), setOf("main"), plannerCalendarId = "planner")
        assertEquals(listOf("bol", "halaqa"), fixed.map { it.id })
    }

    @Test
    fun `all-day events are ignored for planning`() {
        assertTrue(FixedEvents.from(listOf(holiday), setOf("main"), "planner").isEmpty())
    }

    @Test
    fun `deselected calendars are ignored`() {
        val fixed = FixedEvents.from(listOf(bol, lecture), setOf("main"), "planner")
        assertEquals(listOf("bol"), fixed.map { it.id })
    }

    @Test
    fun `planner calendar is never fixed even when selected`() {
        val fixed = FixedEvents.from(listOf(bol, planned), setOf("main", "planner"), plannerCalendarId = "planner")
        assertEquals(listOf("bol"), fixed.map { it.id })
    }

    @Test
    fun `all-day strip covers the date range with exclusive end`() {
        val multi = allDay("trip", "main", "2026-10-27", "2026-10-29")
        val strip = { d: String -> FixedEvents.allDayOn(listOf(multi), setOf("main"), null, LocalDate.parse(d)) }
        assertEquals(1, strip("2026-10-27").size)
        assertEquals(1, strip("2026-10-28").size)
        assertTrue(strip("2026-10-29").isEmpty())
        assertTrue(strip("2026-10-26").isEmpty())
    }

    @Test
    fun `default selection unticks Lectures, Prayer times and Planner`() {
        val cals = listOf("Personal", "Lectures", "Prayer times", "Planner", "Imperial").mapIndexed { i, n ->
            CalendarInfo(id = "c$i", name = n)
        }
        assertEquals(setOf("c0", "c4"), FixedEvents.defaultSelection(cals))
    }
}
