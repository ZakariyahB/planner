package app.planner.calendar

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class AllDayTasksTest {
    private fun day(iso: String) = LocalDate.parse(iso)

    private fun allDay(id: String, title: String, from: String, untilExclusive: String, calendar: String = "main") =
        CalendarEvent(id, calendar, title, EventTiming.AllDay(day(from), day(untilExclusive)))

    private val submit = allDay("e1", "Submit OS coursework", "2026-10-28", "2026-10-29")
    private val revision = allDay("e2", "Revision week", "2026-10-26", "2026-10-31")
    private val selected = setOf("main")

    private fun on(date: String, done: Set<String> = emptySet(), events: List<CalendarEvent> = listOf(submit, revision)) =
        AllDayTasks.on(events, selected, plannerCalendarId = null, date = day(date), doneKeys = done)

    @Test
    fun `each all-day event covering the day is a task, by title`() {
        assertEquals(listOf("Revision week", "Submit OS coursework"), on("2026-10-28").map { it.title })
        assertEquals(listOf("Revision week"), on("2026-10-27").map { it.title })
    }

    @Test
    fun `a multi-day event is ticked off day by day`() {
        val wednesday = on("2026-10-28").single { it.title == "Revision week" }
        val thursday = on("2026-10-29", done = setOf(wednesday.key)).single()
        assertEquals(false, thursday.done)
        assertEquals(true, on("2026-10-28", done = setOf(wednesday.key)).single { it.title == "Revision week" }.done)
    }

    @Test
    fun `events on calendars that are not selected are not tasks`() {
        val other = allDay("e3", "Someone's birthday", "2026-10-28", "2026-10-29", calendar = "birthdays")
        assertEquals(listOf("Submit OS coursework"), on("2026-10-28", events = listOf(submit, other)).map { it.title })
    }

    @Test
    fun `ticks older than a month are forgotten, newer ones kept`() {
        val today = day("2026-10-28")
        val old = AllDayTasks.key(submit, day("2026-09-20"))
        val recent = AllDayTasks.key(submit, day("2026-10-20"))
        assertEquals(setOf(recent), AllDayTasks.prune(setOf(old, recent, "not a key"), today))
    }
}
