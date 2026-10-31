package app.planner.scheduler

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The open stretches the assistant is shown, so it can pick a time for a task without guessing. */
class FreeWindowsTest {
    private val scheduler = Scheduler()

    private fun windows(ctx: DayContext) = scheduler.freeWindows(ctx).map { "${it.start}-${it.end}" }

    @Test
    fun `an empty day is free from wake-up to the end of work`() {
        assertEquals(listOf("08:45-21:00"), windows(DayContext(date("2026-10-28"), london)))
    }

    @Test
    fun `fixed events and their buffer are cut out`() {
        val ctx = DayContext(date("2026-10-28"), london, fixed = listOf(fixed("lecture", "2026-10-28", "10:00", "11:00")))
        assertEquals(listOf("08:45-10:00", "11:15-21:00"), windows(ctx))
    }

    @Test
    fun `prayers and existing blocks are cut out`() {
        val ctx = DayContext(
            date("2026-10-28"), london,
            prayers = prayers(dhuhr = "12:30", asr = "15:30", maghrib = "17:30", isha = "19:00"),
            existing = listOf(Block(taskId = 1, date = date("2026-10-28"), start = time("09:00"), end = time("10:00"))),
        )
        assertEquals(listOf("10:15-12:30", "12:45-15:30", "15:45-17:30", "17:45-19:00", "19:15-21:00"), windows(ctx).drop(0).filter { it != "08:45-09:00" })
    }

    @Test
    fun `nothing is offered before the time it is now`() {
        val ctx = DayContext(date("2026-10-28"), london, notBefore = LocalTime.of(14, 30))
        assertEquals(listOf("14:30-21:00"), windows(ctx))
    }

    @Test
    fun `gaps shorter than the minimum are not offered`() {
        val ctx = DayContext(
            date("2026-10-28"), london,
            fixed = listOf(fixed("a", "2026-10-28", "09:00", "10:00"), fixed("b", "2026-10-28", "10:20", "12:00")),
        )
        assertTrue(windows(ctx).none { it.startsWith("10:15") })
    }

    @Test
    fun `friday is clear from 17 00`() {
        assertEquals(listOf("08:45-17:00"), windows(DayContext(date("2026-10-30"), london)))
    }
}
