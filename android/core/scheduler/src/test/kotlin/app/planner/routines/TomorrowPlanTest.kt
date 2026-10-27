package app.planner.routines

import app.planner.model.Task
import app.planner.model.TaskStatus
import app.planner.scheduler.Block
import app.planner.scheduler.BlockKind
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TomorrowPlanTest {
    private val zone = ZoneId.of("Europe/London")
    private val tomorrow = LocalDate.parse("2026-10-28")

    private fun instant(date: String, time: String): Instant = LocalDate.parse(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant()

    private fun task(id: Long, title: String, status: TaskStatus = TaskStatus.OPEN, due: String? = null, from: Instant? = null) =
        Task(id = id, title = title, estMin = 60, status = status, dueAt = due?.let { instant(it, "23:59") }, availableFrom = from)

    private fun block(id: Long, taskId: Long?, start: String, end: String, date: LocalDate = tomorrow, kind: BlockKind = BlockKind.TASK) =
        Block(id = id, taskId = taskId, date = date, start = LocalTime.parse(start), end = LocalTime.parse(end), kind = kind)

    private val tasks = listOf(
        task(1, "PintOS", due = "2026-10-30"),
        task(2, "ADA"),
        task(3, "Arabic", due = "2026-10-28"),
        task(4, "Done already", status = TaskStatus.DONE),
        task(5, "Dropped", status = TaskStatus.DROPPED),
    ).associateBy { it.id }

    @Test
    fun `rows are tomorrow's task blocks in start order`() {
        val blocks = listOf(
            block(1, 2, "13:00", "14:00"),
            block(2, 1, "09:30", "11:30"),
            block(3, 1, "09:30", "10:30", date = tomorrow.plusDays(1)),
        )
        val rows = TomorrowPlan.rows(tomorrow, blocks, tasks, zone)
        assertEquals(listOf("PintOS", "ADA"), rows.map { it.title })
        assertEquals(LocalTime.of(9, 30), rows[0].start)
        assertEquals(LocalTime.of(11, 30), rows[0].end)
    }

    @Test
    fun `rows skip commitments, finished and dropped tasks and blocks without a task`() {
        val blocks = listOf(
            block(1, null, "10:00", "11:00", kind = BlockKind.FIXED),
            block(2, 4, "11:00", "12:00"),
            block(3, 5, "12:00", "13:00"),
            block(4, 99, "14:00", "15:00"),
            block(5, 2, "15:00", "16:00"),
        )
        assertEquals(listOf(2L), TomorrowPlan.rows(tomorrow, blocks, tasks, zone).map { it.taskId })
    }

    @Test
    fun `a row knows when the task is due tomorrow or earlier`() {
        val blocks = listOf(block(1, 1, "09:00", "10:00"), block(2, 3, "10:15", "11:00"), block(3, 2, "11:15", "12:00"))
        val rows = TomorrowPlan.rows(tomorrow, blocks, tasks, zone).associateBy { it.taskId }
        assertTrue(rows.getValue(3).dueByTomorrow)
        assertEquals(false, rows.getValue(1).dueByTomorrow)
        assertEquals(false, rows.getValue(2).dueByTomorrow)
    }

    @Test
    fun `the outline counts only the included rows`() {
        val blocks = listOf(block(1, 2, "13:00", "14:00"), block(2, 1, "09:30", "11:30"))
        val rows = TomorrowPlan.rows(tomorrow, blocks, tasks, zone)
        val all = TomorrowPlan.outline(rows, excluded = emptySet())
        assertEquals(DayOutline(2, LocalTime.of(9, 30), "PintOS"), all)
        assertEquals(DayOutline(1, LocalTime.of(13, 0), "ADA"), TomorrowPlan.outline(rows, excluded = setOf(1L)))
        assertEquals(DayOutline(0, null, null), TomorrowPlan.outline(rows, excluded = setOf(1L, 2L)))
    }

    @Test
    fun `excluding a task makes it available from the day after tomorrow`() {
        val deferred = TomorrowPlan.defer(tasks.getValue(1), tomorrow, zone)
        assertEquals(instant("2026-10-29", "00:00"), deferred.availableFrom)
        assertEquals(tasks.getValue(1).copy(availableFrom = deferred.availableFrom), deferred)
    }

    @Test
    fun `deferring never makes a task available earlier than it already was`() {
        val later = task(6, "Recording", from = instant("2026-11-02", "10:00"))
        assertEquals(later.availableFrom, TomorrowPlan.defer(later, tomorrow, zone).availableFrom)
    }

    @Test
    fun `deferrals are the excluded tasks that are still in the rows`() {
        val blocks = listOf(block(1, 2, "13:00", "14:00"), block(2, 1, "09:30", "11:30"))
        val rows = TomorrowPlan.rows(tomorrow, blocks, tasks, zone)
        val deferred = TomorrowPlan.deferrals(rows, excluded = setOf(1L, 42L), tasks, tomorrow, zone)
        assertEquals(listOf(1L), deferred.map { it.id })
        assertNull(TomorrowPlan.deferrals(rows, emptySet(), tasks, tomorrow, zone).firstOrNull())
    }

    @Test
    fun `a row from an all-day calendar item says so`() {
        val cal = Task(id = 7, title = "PintOS", estMin = 180, source = app.planner.model.TaskSource.CALENDAR, templateKey = "cal:x|y|2026-10-28")
        val rows = TomorrowPlan.rows(tomorrow, listOf(block(1, 7, "09:30", "12:30")), mapOf(7L to cal), zone)
        assertTrue(rows.single().fromCalendar)
        assertEquals(false, TomorrowPlan.rows(tomorrow, listOf(block(1, 2, "09:30", "10:30")), tasks, zone).single().fromCalendar)
    }

    @Test
    fun `the outline carries the calendar events and the tasks without a time`() {
        val rows = TomorrowPlan.rows(tomorrow, listOf(block(1, 2, "13:00", "14:00")), tasks, zone)
        val facts = DayFacts(fixed = listOf(FixedLine(LocalTime.of(10, 0), LocalTime.of(11, 0), "Tutorial")), unplaced = listOf("A", "B"))
        assertEquals(DayOutline(1, LocalTime.of(13, 0), "ADA", fixedCount = 1, unplacedCount = 2), TomorrowPlan.outline(rows, emptySet(), facts))
    }

    @Test
    fun `tasks with no block anywhere and due by tomorrow, or undated, are the ones without a time`() {
        val all = listOf(
            task(1, "Placed elsewhere", due = "2026-10-28"),
            task(2, "Due tomorrow, no room", due = "2026-10-28"),
            task(3, "Overdue", due = "2026-10-25"),
            task(4, "Undated"),
            task(5, "Due later", due = "2026-11-02"),
            task(6, "Done", status = TaskStatus.DONE),
            task(7, "Not yet available", from = instant("2026-11-01", "09:00")),
        )
        val unplaced = TomorrowPlan.unplaced(all, placedTaskIds = setOf(1L), date = tomorrow, zone = zone)
        assertEquals(listOf("Overdue", "Due tomorrow, no room", "Undated"), unplaced.map { it.title })
    }

    // The card on Today

    private fun pending(sentOn: String?, approvedFor: String?, now: Instant) =
        TomorrowPlan.pendingFor(sentOn?.let(LocalDate::parse), approvedFor?.let(LocalDate::parse), now, zone)

    @Test
    fun `the plan is pending from the 23 30 notice until 06 00 on the day it is for`() {
        assertEquals(LocalDate.parse("2026-10-28"), pending("2026-10-27", null, instant("2026-10-27", "23:40")))
        assertEquals(LocalDate.parse("2026-10-28"), pending("2026-10-27", null, instant("2026-10-28", "05:59")))
        assertNull(pending("2026-10-27", null, instant("2026-10-28", "06:00")))
    }

    @Test
    fun `an approved plan is no longer pending`() {
        assertNull(pending("2026-10-27", "2026-10-28", instant("2026-10-27", "23:40")))
    }

    @Test
    fun `an approval of another day does not count`() {
        assertEquals(LocalDate.parse("2026-10-28"), pending("2026-10-27", "2026-10-27", instant("2026-10-27", "23:40")))
    }

    @Test
    fun `without a notice there is nothing pending`() {
        assertNull(pending(null, null, instant("2026-10-27", "23:40")))
    }
}
