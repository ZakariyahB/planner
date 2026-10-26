package app.planner.scheduler

import app.planner.readiness.ReadinessBand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A task with a chosen start time ("do it at 14:00") is placed exactly there, and everything else works around it. */
class PinnedTaskTest {
    private val scheduler = Scheduler()
    private val now = instant("2026-10-26", "08:00")

    private fun ctx(day: String, fixed: List<app.planner.calendar.FixedEvent> = emptyList()) =
        DayContext(date(day), london, fixed, prayers("12:40", "15:00", "16:30", "18:00"))

    private fun week(days: List<DayContext>, tasks: List<app.planner.model.Task>) = scheduler.planWeek(days, tasks, now)

    @Test
    fun `a pinned task lands exactly at its chosen time, locked and owned by the user`() {
        val t = task(1, "Arabic", 45, due = "2026-10-27", startAt = time("19:00"))
        val plan = week(listOf(ctx("2026-10-26"), ctx("2026-10-27")), listOf(t))
        val block = plan.blocks.single()
        assertEquals(date("2026-10-27"), block.date)
        assertEquals("19:00-19:45", block.span())
        assertTrue(block.locked)
        assertEquals(BlockOrigin.USER, block.origin)
        assertTrue(plan.pinConflicts.isEmpty())
    }

    @Test
    fun `other tasks are planned around a pinned one`() {
        val pinned = task(1, "Pinned", 60, due = "2026-10-26", startAt = time("09:00"))
        val other = task(2, "Other", 60, due = "2026-10-26")
        val plan = week(listOf(ctx("2026-10-26")), listOf(pinned, other))
        assertEquals("09:00-10:00", plan.blocks.single { it.taskId == 1L }.span())
        // 08:45-09:00 is too short, so the next free slot is after the pinned block and its buffer.
        assertEquals("10:15-11:15", plan.blocks.single { it.taskId == 2L }.span())
    }

    @Test
    fun `the chosen time may be outside the usual work window`() {
        val t = task(1, "Late reading", 30, due = "2026-10-26", startAt = time("22:30"))
        assertEquals("22:30-23:00", week(listOf(ctx("2026-10-26")), listOf(t)).blocks.single().span())
    }

    @Test
    fun `a pinned time that clashes with a fixed event is reported and the task is placed normally`() {
        val halaqa = fixed("halaqa", "2026-10-26", "14:00", "16:30")
        val t = task(1, "Clashing", 60, due = "2026-10-26", startAt = time("15:00"))
        val plan = week(listOf(ctx("2026-10-26", listOf(halaqa))), listOf(t))
        assertEquals(1, plan.pinConflicts.size)
        assertEquals(1L, plan.pinConflicts.single().task.id)
        assertTrue("fixed" in plan.pinConflicts.single().reason)
        val block = plan.blocks.single()
        assertEquals("08:45-09:45", block.span())
        assertEquals(false, block.locked)
    }

    @Test
    fun `a pinned time on prayer time is reported`() {
        val t = task(1, "At Asr", 30, due = "2026-10-26", startAt = time("15:05"))
        val plan = week(listOf(ctx("2026-10-26")), listOf(t))
        assertTrue("prayer" in plan.pinConflicts.single().reason)
    }

    @Test
    fun `two pinned tasks that overlap - the earlier one wins and the other is reported`() {
        val a = task(1, "A", 60, due = "2026-10-26", startAt = time("10:00"))
        val b = task(2, "B", 60, due = "2026-10-26", startAt = time("10:30"))
        val plan = week(listOf(ctx("2026-10-26")), listOf(a, b))
        assertEquals("10:00-11:00", plan.blocks.single { it.taskId == 1L }.span())
        assertEquals(listOf(2L), plan.pinConflicts.map { it.task.id })
        assertTrue("another" in plan.pinConflicts.single().reason)
    }

    @Test
    fun `a pinned time already past today is reported`() {
        val today = ctx("2026-10-26").copy(notBefore = time("11:00"))
        val t = task(1, "Missed", 30, due = "2026-10-26", startAt = time("09:00"))
        val plan = week(listOf(today), listOf(t))
        assertTrue("passed" in plan.pinConflicts.single().reason)
        assertTrue(plan.blocks.single().start >= time("11:00"))
    }

    @Test
    fun `an undated pinned task goes on the first day`() {
        val t = task(1, "Undated", 30, startAt = time("16:00"))
        val plan = week(listOf(ctx("2026-10-26"), ctx("2026-10-27")), listOf(t))
        assertEquals(date("2026-10-26"), plan.blocks.single().date)
    }

    @Test
    fun `a pinned task due beyond the planned days is left alone, not pulled earlier`() {
        val t = task(1, "Far", 30, due = "2026-11-20", startAt = time("16:00"))
        val plan = week(listOf(ctx("2026-10-26"), ctx("2026-10-27")), listOf(t))
        assertTrue(plan.blocks.isEmpty())
        assertTrue(plan.unscheduled.isEmpty())
        assertTrue(plan.pinConflicts.isEmpty())
    }

    @Test
    fun `pinned minutes count towards the daily cap`() {
        val pinned = task(1, "Long", 200, due = "2026-10-26", startAt = time("09:00")) // ends 12:20, before Dhuhr
        val other = task(2, "Other", 200, due = "2026-10-26") // 200 + 200 would exceed the 360 minute cap
        val plan = week(listOf(ctx("2026-10-26")), listOf(pinned, other))
        assertNull(plan.blocks.firstOrNull { it.taskId == 2L })
    }

    @Test
    fun `a task that already has a block is not pinned again`() {
        val existing = Block(id = 5, taskId = 1, date = date("2026-10-26"), start = time("13:00"), end = time("14:00"), locked = true)
        val t = task(1, "Done once", 60, due = "2026-10-26", startAt = time("09:00"))
        val plan = week(listOf(ctx("2026-10-26").copy(existing = listOf(existing))), listOf(t))
        assertTrue(plan.blocks.isEmpty())
    }

    @Test
    fun `pinning works alongside a readiness load cut`() {
        val pinned = task(1, "Pinned", 60, due = "2026-10-26", startAt = time("09:00"))
        val plan = scheduler.planWeek(listOf(ctx("2026-10-26")), listOf(pinned), now, ReadinessBand.AMBER)
        assertEquals("09:00-10:00", plan.blocks.single().span())
    }
}
