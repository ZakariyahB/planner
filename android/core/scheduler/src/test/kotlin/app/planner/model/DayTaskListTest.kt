package app.planner.model

import app.planner.scheduler.Block
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DayTaskListTest {
    private val today = LocalDate.of(2026, 10, 26)
    private val zone = ZoneId.of("Europe/London")
    private fun task(id: Long, due: LocalDate? = null, status: TaskStatus = TaskStatus.OPEN) = Task(id = id, title = "Task $id", estMin = 30, dueAt = due?.atTime(23, 59)?.atZone(zone)?.toInstant(), status = status)
    private fun block(id: Long, date: LocalDate) = Block(id = id, taskId = id, date = date, start = LocalTime.of(10, 0), end = LocalTime.of(10, 30))

    @Test fun `future deadline scheduled today belongs in today's list`() {
        val result = DayTaskList.on(today, today, zone, listOf(task(1, today.plusDays(4))), listOf(block(1, today)))
        assertEquals(listOf(1L), result.scheduled.map { it.id })
        assertTrue(result.untimed.isEmpty())
    }

    @Test fun `a placement elsewhere and Sunday catch-up do not become anytime today`() {
        val result = DayTaskList.on(today, today, zone, listOf(task(1, today), task(2, today), task(3, today)), listOf(block(1, today.plusDays(1))), setOf(2L))
        assertEquals(listOf(3L), result.untimed.map { it.id })
    }

    @Test fun `undated and overdue work appears today but does not repeat on future days`() {
        val tasks = listOf(task(1), task(2, today.minusDays(1)), task(3, today.plusDays(1)))
        assertEquals(setOf(1L, 2L), DayTaskList.on(today, today, zone, tasks, emptyList()).untimed.map { it.id }.toSet())
        assertEquals(listOf(3L), DayTaskList.on(today.plusDays(1), today, zone, tasks, emptyList()).untimed.map { it.id })
    }

    @Test fun `completion remains visible on its scheduled or due day and dropped work stays hidden`() {
        val tasks = listOf(task(1, status = TaskStatus.DONE), task(2, today, TaskStatus.DONE), task(3, today, TaskStatus.DROPPED))
        val result = DayTaskList.on(today, today, zone, tasks, listOf(block(1, today), block(3, today)))
        assertEquals(listOf(1L, 2L), result.all.map { it.id })
    }

    @Test fun `not-yet-available work is not offered as anytime`() {
        val task = task(1, today).copy(availableFrom = today.plusDays(1).atStartOfDay(zone).toInstant())
        assertTrue(DayTaskList.on(today, today, zone, listOf(task), emptyList()).all.isEmpty())
    }
}
