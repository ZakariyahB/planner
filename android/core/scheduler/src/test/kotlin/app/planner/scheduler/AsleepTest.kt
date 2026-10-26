package app.planner.scheduler

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Times he is asleep (from Health Connect) are off limits, and the day starts after he actually wakes. */
class AsleepTest {
    private val scheduler = Scheduler()
    private val now = instant("2026-10-26", "08:00")

    private fun span(startDate: String, start: String, endDate: String, end: String) =
        AsleepSpan(instant(startDate, start), instant(endDate, end))

    private fun ctx(day: String = "2026-10-26", vararg spans: AsleepSpan) =
        DayContext(date(day), london, asleep = spans.toList())

    @Test
    fun `the first task starts 15 minutes after a late morning wake-up`() {
        val c = ctx(spans = arrayOf(span("2026-10-26", "00:32", "2026-10-26", "05:14"), span("2026-10-26", "07:04", "2026-10-26", "09:14")))
        val plan = scheduler.plan(c, listOf(task(1, "A", 60)))
        assertEquals("09:30-10:30", plan.blocks.single().span())
    }

    @Test
    fun `sleep that ended before the usual wake time changes nothing`() {
        val c = ctx(spans = arrayOf(span("2026-10-26", "00:32", "2026-10-26", "05:14")))
        assertEquals("08:45-09:45", scheduler.plan(c, listOf(task(1, "A", 60))).blocks.single().span())
    }

    @Test
    fun `a night spanning midnight only blocks the part inside the day`() {
        val night = span("2026-10-25", "23:50", "2026-10-26", "07:20")
        assertEquals("08:45-09:45", scheduler.plan(ctx("2026-10-26", night), listOf(task(1, "A", 60))).blocks.single().span())
        // On the evening before, the sleep starts at 23:50, after work has stopped, so a 21:00 task is unaffected.
        val evening = DayContext(date("2026-10-25"), london, asleep = listOf(night))
        assertEquals("08:45-09:45", scheduler.plan(evening, listOf(task(1, "A", 60))).blocks.single().span())
    }

    @Test
    fun `a nap in the afternoon is planned around too`() {
        val c = ctx(spans = arrayOf(span("2026-10-26", "09:00", "2026-10-26", "09:40")))
        val plan = scheduler.plan(c, listOf(task(1, "A", 60)))
        assertEquals("09:55-10:55", plan.blocks.single().span())
    }

    @Test
    fun `sleep does not make the day count as packed`() {
        val c = ctx(spans = arrayOf(span("2026-10-26", "01:00", "2026-10-26", "08:40")))
        assertEquals(21 * 60, DayLayout(c, DayRules(), honourNotBefore = false).windowEnd)
    }

    @Test
    fun `a chosen time during sleep is reported`() {
        val c = ctx(spans = arrayOf(span("2026-10-26", "07:04", "2026-10-26", "09:14")))
        val t = task(1, "Early", 30, due = "2026-10-26", startAt = time("08:00"))
        val week = scheduler.planWeek(listOf(c), listOf(t), now)
        assertTrue("asleep" in week.pinConflicts.single().reason)
        assertTrue(week.blocks.single().start >= time("09:30"))
    }

    @Test
    fun `moving a block onto sleep is rejected`() {
        val sleepyCtx = ctx(spans = arrayOf(span("2026-10-26", "07:04", "2026-10-26", "09:14")))
        val block = Block(1, 1, date("2026-10-26"), time("10:00"), time("11:00"))
        val state = PlanState(listOf(block), mapOf(date("2026-10-26") to sleepyCtx), emptyMap())
        val move = ChangeSet(listOf(Op.Move(1, date("2026-10-26"), time("09:00"), time("10:00"))), Actor.USER)
        val v = scheduler.validate(move, state)
        assertTrue(ViolationKind.OVERLAP_SLEEP in v.violations.filter { it.hard }.map { it.kind })
    }
}
