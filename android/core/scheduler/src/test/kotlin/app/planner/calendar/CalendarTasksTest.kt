package app.planner.calendar

import app.planner.model.Task
import app.planner.model.TaskSource
import app.planner.model.TaskStatus
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CalendarTasksTest {
    private val zone = ZoneId.of("Europe/London")
    private val today = LocalDate.parse("2026-10-27")
    private val selected = setOf("main")

    private fun allDay(id: String, title: String, from: String, untilExclusive: String = LocalDate.parse(from).plusDays(1).toString(), calendar: String = "main") =
        CalendarEvent(id, calendar, title, EventTiming.AllDay(LocalDate.parse(from), LocalDate.parse(untilExclusive)))

    private val pintos = allDay("e1", "PintOS (3h)", "2026-10-27")
    private val lecture = allDay("e2", "Lecture - SDE (1h, from 14:00)", "2026-10-27")
    private val holiday = allDay("e3", "Eid", "2026-10-29", "2026-10-31")

    private fun sync(
        events: List<CalendarEvent>,
        existing: List<Task> = emptyList(),
        done: Set<String> = emptySet(),
        from: LocalDate = today,
        days: Int = 7,
    ) = CalendarTasks.sync(events, selected, null, from, days, zone, existing, done)

    @Test
    fun `each all-day event becomes a task with the length and time from its title`() {
        val created = sync(listOf(pintos, lecture)).create.associateBy { it.title }
        val pint = created.getValue("PintOS")
        assertEquals(180, pint.estMin)
        assertEquals(TaskSource.CALENDAR, pint.source)
        assertEquals(today, pint.dueAt!!.atZone(zone).toLocalDate())
        val lec = created.getValue("Lecture - SDE")
        assertEquals(60, lec.estMin)
        assertEquals(LocalTime.of(14, 0), lec.startAt)
    }

    @Test
    fun `an event with no length gets an hour`() {
        assertEquals(CalendarTasks.DEFAULT_MIN, sync(listOf(holiday)).create.first().estMin)
    }

    @Test
    fun `a multi-day event is a task on every day of the plan`() {
        val dates = sync(listOf(holiday)).create.map { it.dueAt!!.atZone(zone).toLocalDate() }.sorted()
        assertEquals(listOf(LocalDate.parse("2026-10-29"), LocalDate.parse("2026-10-30")), dates)
    }

    @Test
    fun `events beyond the days being planned wait`() {
        assertTrue(sync(listOf(allDay("far", "Later", "2026-11-20"))).create.isEmpty())
    }

    @Test
    fun `running it again adds nothing`() {
        val first = sync(listOf(pintos, lecture)).create.mapIndexed { i, t -> t.copy(id = i + 1L) }
        val second = sync(listOf(pintos, lecture), existing = first)
        assertTrue(second.create.isEmpty() && second.drop.isEmpty())
    }

    @Test
    fun `an item already ticked off starts as done`() {
        val key = AllDayTasks.key(pintos, today)
        assertEquals(TaskStatus.DONE, sync(listOf(pintos), done = setOf(key)).create.single().status)
    }

    @Test
    fun `a task he edited is not overwritten when the event is read again`() {
        val made = sync(listOf(pintos)).create.single().copy(id = 1, estMin = 240, title = "PintOS part 1")
        val again = sync(listOf(pintos), existing = listOf(made))
        assertTrue(again.create.isEmpty() && again.drop.isEmpty())
    }

    @Test
    fun `a task whose event was removed is dropped, unless he finished it`() {
        val tasks = sync(listOf(pintos, lecture)).create.mapIndexed { i, t -> t.copy(id = i + 1L) }
        val finished = tasks.first { it.title == "PintOS" }.copy(status = TaskStatus.DONE)
        val result = sync(events = listOf(holiday), existing = listOf(finished, tasks.first { it.title != "PintOS" }))
        assertEquals(listOf("Lecture - SDE"), result.drop.map { it.title })
        assertEquals(TaskStatus.DROPPED, result.drop.single().status)
    }

    @Test
    fun `with no events at all nothing is dropped, in case the calendar has not loaded`() {
        val tasks = sync(listOf(pintos)).create.map { it.copy(id = 1) }
        assertTrue(sync(emptyList(), existing = tasks).drop.isEmpty())
    }

    @Test
    fun `a task he dropped is not made again`() {
        val dropped = sync(listOf(pintos)).create.single().copy(id = 1, status = TaskStatus.DROPPED)
        assertTrue(sync(listOf(pintos), existing = listOf(dropped)).create.isEmpty())
    }

    @Test
    fun `an unfinished item is carried for two days and then let go`() {
        val old = allDay("old", "Old thing", "2026-10-23")
        val made = CalendarTasks.sync(listOf(old), selected, null, LocalDate.parse("2026-10-23"), 1, zone, emptyList(), emptySet()).create.single().copy(id = 1)
        assertTrue(sync(listOf(old), existing = listOf(made), from = LocalDate.parse("2026-10-25")).drop.isEmpty())
        assertEquals(1, sync(listOf(old), existing = listOf(made), from = LocalDate.parse("2026-10-26")).drop.size)
    }

    @Test
    fun `events on calendars that are not selected are ignored`() {
        assertTrue(sync(listOf(allDay("b", "Birthday", "2026-10-27", calendar = "birthdays"))).create.isEmpty())
    }

    @Test
    fun `a calendar task is recognised and gives back its all-day key`() {
        val task = sync(listOf(pintos)).create.single()
        assertTrue(CalendarTasks.isCalendarTask(task))
        assertEquals(AllDayTasks.key(pintos, today), CalendarTasks.allDayKey(task))
    }
}
