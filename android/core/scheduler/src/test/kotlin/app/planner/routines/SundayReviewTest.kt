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

class SundayReviewTest {
    private val zone = ZoneId.of("Europe/London")

    private fun date(s: String) = LocalDate.parse(s)

    private fun at(date: String, time: String): Instant = date(date).atTime(LocalTime.parse(time)).atZone(zone).toInstant()

    private fun task(id: Long, title: String, status: TaskStatus) = Task(id = id, title = title, estMin = 60, status = status)

    private fun block(id: Long, taskId: Long?, date: String, start: String, end: String, kind: BlockKind = BlockKind.TASK) =
        Block(id = id, taskId = taskId, date = date(date), start = LocalTime.parse(start), end = LocalTime.parse(end), kind = kind)

    private val monday = date("2026-10-26")

    private val tasks = listOf(
        task(1, "PintOS", TaskStatus.DONE),
        task(2, "ADA", TaskStatus.OPEN),
        task(3, "Arabic", TaskStatus.PARTIAL),
        task(4, "SDE", TaskStatus.DROPPED),
        task(5, "Next week's", TaskStatus.OPEN),
    ).associateBy { it.id }

    @Test
    fun `report adds up planned and done minutes for the week`() {
        val blocks = listOf(
            block(1, 1, "2026-10-26", "09:00", "11:00"), // done, 120
            block(2, 2, "2026-10-27", "09:00", "10:00"), // open, 60
            block(3, 3, "2026-10-30", "14:00", "15:30"), // partial, 90
            block(4, 4, "2026-11-01", "10:00", "11:00"), // dropped, 60
        )
        val r = SundayReview.report(monday, blocks, tasks)
        assertEquals(120 + 60 + 90 + 60, r.plannedMin)
        assertEquals(120, r.doneMin)
        assertEquals(4, r.plannedTasks)
        assertEquals(1, r.doneTasks)
    }

    @Test
    fun `report lists what was left undone but not what was dropped on purpose`() {
        val blocks = listOf(
            block(1, 1, "2026-10-26", "09:00", "11:00"),
            block(2, 2, "2026-10-27", "09:00", "10:00"),
            block(3, 3, "2026-10-30", "14:00", "15:30"),
            block(4, 4, "2026-11-01", "10:00", "11:00"),
        )
        assertEquals(listOf("ADA", "Arabic"), SundayReview.report(monday, blocks, tasks).notDone.map { it.title })
    }

    @Test
    fun `report ignores other weeks, commitments and blocks without a known task`() {
        val blocks = listOf(
            block(1, 1, "2026-10-25", "09:00", "11:00"), // Sunday before
            block(2, 5, "2026-11-02", "09:00", "10:00"), // Monday after
            block(3, null, "2026-10-28", "10:00", "11:00", kind = BlockKind.FIXED),
            block(4, 99, "2026-10-28", "12:00", "13:00"),
        )
        val r = SundayReview.report(monday, blocks, tasks)
        assertEquals(0, r.plannedMin)
        assertEquals(0, r.plannedTasks)
        assertEquals(emptyList(), r.notDone)
    }

    @Test
    fun `a task with blocks on two days is counted once`() {
        val blocks = listOf(block(1, 2, "2026-10-26", "09:00", "10:00"), block(2, 2, "2026-10-28", "09:00", "10:00"))
        val r = SundayReview.report(monday, blocks, tasks)
        assertEquals(120, r.plannedMin)
        assertEquals(1, r.plannedTasks)
        assertEquals(1, r.notDone.size)
    }

    @Test
    fun `an empty week has a null ratio`() {
        assertNull(SundayReview.report(monday, emptyList(), tasks).doneRatio)
        assertEquals(0.5, SundayReview.WeekReport(100, 50, 2, 1, emptyList()).doneRatio)
    }

    // The card

    @Test
    fun `the review card appears at 19 00 on Sunday and is about the coming week`() {
        assertEquals(date("2026-11-02"), SundayReview.pendingWeek(at("2026-11-01", "19:00"), zone, opened = emptySet()))
        assertEquals(date("2026-11-02"), SundayReview.pendingWeek(at("2026-11-01", "23:00"), zone, opened = emptySet()))
    }

    @Test
    fun `the review card stays on Today until it is opened, through the week`() {
        assertEquals(date("2026-11-02"), SundayReview.pendingWeek(at("2026-11-04", "12:00"), zone, opened = emptySet()))
        assertEquals(date("2026-11-02"), SundayReview.pendingWeek(at("2026-11-07", "12:00"), zone, opened = emptySet()))
    }

    @Test
    fun `opening the review removes the card until the next Sunday`() {
        val opened = setOf(date("2026-11-02"))
        assertNull(SundayReview.pendingWeek(at("2026-11-01", "20:00"), zone, opened))
        assertNull(SundayReview.pendingWeek(at("2026-11-05", "20:00"), zone, opened))
        assertEquals(date("2026-11-09"), SundayReview.pendingWeek(at("2026-11-08", "19:30"), zone, opened))
    }

    @Test
    fun `a skipped review is replaced by the next one`() {
        assertEquals(date("2026-11-09"), SundayReview.pendingWeek(at("2026-11-08", "19:00"), zone, opened = emptySet()))
    }

    @Test
    fun `before 19 00 on Sunday the previous review is still the pending one`() {
        assertEquals(date("2026-11-02"), SundayReview.pendingWeek(at("2026-11-08", "18:59"), zone, opened = emptySet()))
    }

    @Test
    fun `no review card for a Sunday before the routines were switched on`() {
        val since = at("2026-11-03", "10:00")
        assertNull(SundayReview.pendingWeek(at("2026-11-04", "12:00"), zone, opened = emptySet(), since = since))
        assertEquals(date("2026-11-09"), SundayReview.pendingWeek(at("2026-11-08", "19:00"), zone, opened = emptySet(), since = since))
    }

    // Skipped check-in

    @Test
    fun `on Monday a week that was not generated by the review is generated from the template`() {
        assertEquals(date("2026-11-02"), SundayReview.catchUpWeek(date("2026-11-02"), generated = emptySet()))
    }

    @Test
    fun `a week the review already generated is not generated again`() {
        assertNull(SundayReview.catchUpWeek(date("2026-11-02"), generated = setOf(date("2026-11-02"))))
    }

    @Test
    fun `the catch-up only runs on Monday`() {
        assertNull(SundayReview.catchUpWeek(date("2026-11-01"), emptySet()))
        assertNull(SundayReview.catchUpWeek(date("2026-11-03"), emptySet()))
    }
}
