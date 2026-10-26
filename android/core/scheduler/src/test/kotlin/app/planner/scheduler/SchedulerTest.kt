package app.planner.scheduler

import app.planner.calendar.FixedEvent
import app.planner.calendar.PrayerTimes
import app.planner.model.Anchor
import app.planner.model.Prayer
import app.planner.model.Priority
import app.planner.readiness.ReadinessBand
import java.time.DayOfWeek
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SchedulerTest {
    private val scheduler = Scheduler()
    private val now = instant("2026-10-26", "08:00")

    private fun ctx(day: String, fixed: List<FixedEvent> = emptyList(), prayers: Map<Prayer, LocalTime> = emptyMap()) =
        DayContext(date(day), london, fixed, prayers)

    // Mon 26 Oct: no fixed events.
    private val monPrayers = prayers("12:40", "15:00", "16:30", "18:00")

    @Test
    fun `monday places earliest deadline first with 15 minute buffers inside the window`() {
        val tasks = listOf(task(2, "P&S sheet", 60, due = "2026-10-27"), task(1, "OS worksheet", 90, due = "2026-10-26"))
        val plan = scheduler.plan(ctx("2026-10-26", prayers = monPrayers), tasks)
        assertEquals(listOf("08:45-10:15", "10:30-11:30"), plan.blocks.map { it.span() })
        assertEquals(listOf(1L, 2L), plan.blocks.map { it.taskId })
        assertTrue(plan.blocks.all { it.start >= time("08:45") && it.end <= time("21:00") })
    }

    @Test
    fun `a task is never split and skips prayer buffers to find one free stretch`() {
        val tasks = listOf(task(1, "OS worksheet", 90, due = "2026-10-26"), task(2, "PintOS CW", 150, due = "2026-10-27"))
        val plan = scheduler.plan(ctx("2026-10-26", prayers = monPrayers), tasks)
        val cw = plan.blocks.single { it.taskId == 2L }
        // Gaps before 18:15 are all shorter than 150 min once Dhuhr, Asr, Maghrib and Isha buffers are kept clear.
        assertEquals("18:15-20:45", cw.span())
    }

    @Test
    fun `wednesday with BoL and Halaqa is a packed day that works until 22 00`() {
        val plan = scheduler.plan(wednesday(halaqaEnd = "16:30"), wedTasks())
        assertEquals(
            mapOf(1L to "08:45-10:15", 2L to "16:50-18:05", 3L to "18:20-21:20"),
            plan.blocks.associate { it.taskId!! to it.span() },
        )
        assertTrue(plan.unplaced.isEmpty())
    }

    @Test
    fun `with Sunday as the only catch-up day, Halaqa running an hour over pushes the CW to Sunday and OS still fits before 21 00`() {
        val days = listOf(
            wednesday(halaqaEnd = "17:30"),
            ctx("2026-10-29"), ctx("2026-10-30"), ctx("2026-10-31"), ctx("2026-11-01"),
        )
        val week = sundayOnly.planWeek(days, wedTasks(), instant("2026-10-28", "08:00"))

        val os = week.blocks.single { it.taskId == 1L }
        assertEquals(date("2026-10-28"), os.date)
        assertTrue(os.end <= time("21:00"))

        assertEquals(listOf(3L), week.overflow.map { it.id })
        val cw = week.blocks.single { it.taskId == 3L }
        assertEquals(date("2026-11-01"), cw.date)
        assertEquals(DayOfWeek.SUNDAY, cw.date.dayOfWeek)
        assertTrue(week.unscheduled.isEmpty())
        assertTrue(week.catchUpExceeded) // 180 min of overflow against the 120 min Sunday allowance
    }

    @Test
    fun `friday after 17 00 stays empty`() {
        val rules = DayRules(maxTaskMinutesPerDay = 600)
        val tasks = (1L..4L).map { task(it, "Task $it", 120, due = "2026-10-30") }
        val plan = Scheduler(rules).plan(ctx("2026-10-30"), tasks)
        assertEquals(3, plan.blocks.size)
        assertTrue(plan.blocks.all { it.end <= time("17:00") })
        assertEquals(1, plan.unplaced.size)
    }

    @Test
    fun `saturday task anchored after Asr uses fridays Asr`() {
        val friday = mapOf(date("2026-10-30") to mapOf(Prayer.ASR to time("14:50"), Prayer.DHUHR to time("12:35")))
        val satTimes = PrayerTimes.forDate(date("2026-10-31"), friday, fajr = null)
        val t = task(1, "Arabic revision", 60, due = "2026-10-31", anchor = Anchor(Prayer.ASR, 15))
        val plan = scheduler.plan(DayContext(date("2026-10-31"), london, prayers = satTimes), listOf(t))
        assertEquals("15:05-16:05", plan.blocks.single().span())
    }

    @Test
    fun `recording availability delays a lecture catch-up until it is published`() {
        val t = task(1, "OS lecture", 60, due = "2026-10-26", from = instant("2026-10-26", "14:00"))
        val plan = scheduler.plan(ctx("2026-10-26"), listOf(t))
        assertEquals("14:00-15:00", plan.blocks.single().span())
    }

    @Test
    fun `a task available only tomorrow is not planned today`() {
        val t = task(1, "Tomorrow's recording", 60, from = instant("2026-10-27", "09:00"))
        val plan = scheduler.plan(ctx("2026-10-26"), listOf(t))
        assertTrue(plan.blocks.isEmpty())
        assertEquals(1, plan.unplaced.size)
    }

    @Test
    fun `replanning today never places before notBefore`() {
        val plan = scheduler.plan(ctx("2026-10-26").copy(notBefore = time("13:07")), listOf(task(1, "x", 30)))
        assertEquals("13:10-13:40", plan.blocks.single().span())
    }

    @Test
    fun `existing locked blocks are planned around and count towards the daily cap`() {
        val locked = Block(id = 9, taskId = 99, date = date("2026-10-26"), start = time("08:45"), end = time("11:45"), locked = true)
        val plan = scheduler.plan(ctx("2026-10-26").copy(existing = listOf(locked)), listOf(task(1, "a", 60), task(2, "b", 60)))
        assertEquals("12:00-13:00", plan.blocks.first().span())
        // 180 locked + 60 + 60 = 300 fits under 360; a 100 more would not.
        val big = scheduler.plan(ctx("2026-10-26").copy(existing = listOf(locked)), listOf(task(3, "c", 200)))
        assertTrue(big.blocks.isEmpty())
    }

    @Test
    fun `amber day suggests cutting 20 percent without applying it and puts the hardest task first`() {
        val tasks = amberTasks()
        val week = scheduler.planWeek(listOf(ctx("2026-10-26")), tasks, now, ReadinessBand.AMBER)
        assertEquals(listOf(2L), week.loadCut.map { it.id })
        assertTrue(week.blocks.any { it.taskId == 2L }) // proposal only
        assertEquals(1L, week.blocks.minBy { it.start }.taskId) // the 120 min task is hardest
    }

    @Test
    fun `applying an amber load cut removes the cut task from the day`() {
        val week = scheduler.planWeek(listOf(ctx("2026-10-26")), amberTasks(), now, ReadinessBand.AMBER, applyLoadCut = true)
        assertFalse(week.blocks.any { it.taskId == 2L })
        assertEquals(3, week.blocks.size)
    }

    @Test
    fun `red day keeps only tasks due within 48 hours`() {
        val week = scheduler.planWeek(listOf(ctx("2026-10-26")), amberTasks(), now, ReadinessBand.RED, applyLoadCut = true)
        assertEquals(listOf(1L), week.blocks.map { it.taskId })
        assertEquals(setOf(2L, 3L, 4L), week.loadCut.map { it.id }.toSet())
    }

    @Test
    fun `with Sunday as the only catch-up day, overflow skips the days before it`() {
        val week = sundayOnly.planWeek(mondayFullThenFree(), listOf(tooLongForMonday()), now)
        assertEquals(date("2026-11-01"), week.blocks.single().date)
    }

    @Test
    fun `by default any day is a catch-up day, so overflow goes to the next day with room`() {
        val week = scheduler.planWeek(mondayFullThenFree(), listOf(tooLongForMonday()), now)
        assertEquals(date("2026-10-27"), week.blocks.single().date)
        assertEquals(listOf(1L), week.overflow.map { it.id })
    }

    @Test
    fun `overflow waits for the next chosen catch-up day`() {
        val thursdayAndSunday = Scheduler(DayRules(catchUpDays = setOf(DayOfWeek.THURSDAY, DayOfWeek.SUNDAY)))
        val days = mondayFullThenFree().dropLast(1) + listOf(ctx("2026-10-28"), ctx("2026-10-29"), ctx("2026-11-01"))
        val week = thursdayAndSunday.planWeek(days, listOf(tooLongForMonday()), now)
        assertEquals(date("2026-10-29"), week.blocks.single().date)
    }

    @Test
    fun `overflow that misses a catch-up day waits for the next one`() {
        val thursdayAndSunday = Scheduler(DayRules(catchUpDays = setOf(DayOfWeek.THURSDAY, DayOfWeek.SUNDAY)))
        val fullThursday = ctx("2026-10-29").copy(existing = listOf(Block(id = 8, taskId = 98, date = date("2026-10-29"), start = time("08:45"), end = time("20:45"), locked = true)))
        val days = listOf(mondayFullThenFree().first(), ctx("2026-10-27"), fullThursday, ctx("2026-10-30"), ctx("2026-11-01"))
        val week = thursdayAndSunday.planWeek(days, listOf(tooLongForMonday()), now)
        assertEquals(date("2026-11-01"), week.blocks.single().date)
    }

    private val sundayOnly = Scheduler(DayRules(catchUpDays = setOf(DayOfWeek.SUNDAY)))

    private fun tooLongForMonday() = task(1, "Too long for Monday", 100, due = "2026-10-26")

    private fun mondayFullThenFree(): List<DayContext> {
        val locked = Block(id = 9, taskId = 99, date = date("2026-10-26"), start = time("08:45"), end = time("20:45"), locked = true)
        return listOf(ctx("2026-10-26").copy(existing = listOf(locked)), ctx("2026-10-27"), ctx("2026-11-01"))
    }

    @Test
    fun `placing the same inputs twice gives the same plan`() {
        val a = scheduler.plan(wednesday("16:30"), wedTasks())
        val b = scheduler.plan(wednesday("16:30"), wedTasks())
        assertEquals(a, b)
        assertNotNull(a.blocks.firstOrNull())
    }

    private fun wednesday(halaqaEnd: String) = ctx(
        "2026-10-28",
        fixed = listOf(
            fixed("bol", "2026-10-28", "10:15", "12:45"),
            fixed("halaqa", "2026-10-28", "14:00", halaqaEnd),
        ),
        prayers = prayers("12:40", "14:55", "16:35", "18:05", fajr = "05:45"),
    )

    private fun wedTasks() = listOf(
        task(1, "OS worksheet", 90, due = "2026-10-28", priority = Priority.HIGH),
        task(2, "Prob & Stats sheet", 75, due = "2026-10-28"),
        task(3, "PintOS CW", 180, due = "2026-10-28", priority = Priority.LOW),
    )

    // 120 due today, three 60s due in 3, 5 and 6 days: total 300, so an amber cut targets 240.
    private fun amberTasks() = listOf(
        task(1, "Hard one", 120, due = "2026-10-26", priority = Priority.HIGH),
        task(2, "Low priority far", 60, due = "2026-10-31", priority = Priority.LOW),
        task(3, "Normal far", 60, due = "2026-11-01"),
        task(4, "Normal mid", 60, due = "2026-10-29"),
    )
}
