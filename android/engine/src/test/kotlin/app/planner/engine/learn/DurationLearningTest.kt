package app.planner.engine.learn

import app.planner.engine.model.CalendarCategory
import app.planner.engine.model.EventRef
import app.planner.engine.model.ExecutionLog
import app.planner.engine.model.ExecutionOutcome
import app.planner.engine.model.Task
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DurationLearningTest {
    private val day = LocalDate.of(2026, 11, 2)
    private var n = 0

    private fun log(
        title: String,
        plannedMin: Long,
        actualMin: Long?,
        outcome: ExecutionOutcome = ExecutionOutcome.DONE,
        dayOffset: Long = 0,
        focus: Int? = null,
        taskId: String? = null,
        calendarId: String = "cal-academic",
    ): ExecutionLog {
        val start = day.plusDays(dayOffset).atTime(10, 0)
        return ExecutionLog(
            id = "log-${n++}",
            taskId = taskId,
            eventRef = EventRef(calendarId, "e$n"),
            title = title,
            plannedStart = start,
            plannedEnd = start.plusMinutes(plannedMin),
            actualStart = actualMin?.let { start.plusMinutes(5) },
            actualEnd = actualMin?.let { start.plusMinutes(5 + it) },
            focus1to5 = focus,
            outcome = outcome,
        )
    }

    @Test
    fun `chunk labels, case and spacing do not split a title`() {
        assertEquals("pintos", DurationLearning.normaliseTitle("PintOS (1/2)"))
        assertEquals("pintos", DurationLearning.normaliseTitle("  pintos   (2/2) "))
        assertEquals("lecture - cp2", DurationLearning.normaliseTitle("Lecture  -  CP2"))
        assertEquals("p&s problem sheet (week 3)", DurationLearning.normaliseTitle("P&S problem sheet (week 3)"))
    }

    @Test
    fun `finished blocks give planned and actual totals and a median ratio`() {
        val logs = listOf(
            log("PintOS (1/2)", 120, 150, focus = 4),
            log("PintOS (2/2)", 120, 120, ExecutionOutcome.EXTENDED, dayOffset = 1, focus = 3),
            log("pintos", 60, 120, dayOffset = 2),
        )
        val s = DurationLearning.byTitle(logs).single()
        assertEquals("pintos", s.key)
        assertEquals("pintos", s.label) // the most recent title, without its chunk label
        assertEquals(3, s.samples)
        assertEquals(300, s.plannedMin)
        assertEquals(390, s.actualMin)
        assertEquals(1.25, s.medianRatio) // ratios 1.0, 1.25, 2.0
        assertEquals(3.5, s.meanFocus)
        assertEquals(100, s.meanPlannedMin)
        assertEquals(130, s.meanActualMin)
    }

    @Test
    fun `stopped, skipped and unfinished blocks are counted but not sampled`() {
        val logs = listOf(
            log("OS worksheet", 60, 70),
            log("OS worksheet", 60, 20, ExecutionOutcome.STOPPED),
            log("OS worksheet", 60, null, ExecutionOutcome.SKIPPED),
            log("OS worksheet", 60, null, ExecutionOutcome.STARTED),
            log("OS worksheet", 60, null, ExecutionOutcome.PENDING),
            // Marked done but never started: no actual duration to learn from.
            log("OS worksheet", 60, null, ExecutionOutcome.DONE),
        )
        val s = DurationLearning.byTitle(logs).single()
        assertEquals(1, s.samples)
        assertEquals(60, s.plannedMin)
        assertEquals(70, s.actualMin)
        assertEquals(1, s.stopped)
        assertEquals(1, s.skipped)
    }

    @Test
    fun `a title with no finished block has no ratio`() {
        val s = DurationLearning.byTitle(listOf(log("Gym", 60, null, ExecutionOutcome.SKIPPED))).single()
        assertEquals(0, s.samples)
        assertNull(s.medianRatio)
        assertNull(s.meanActualMin)
        assertNull(s.meanFocus)
    }

    @Test
    fun `titles are sorted by how much history they have`() {
        val logs = listOf(log("B", 60, 60), log("A", 60, 60), log("C", 60, 60), log("C", 60, 60))
        assertEquals(listOf("c", "a", "b"), DurationLearning.byTitle(logs).map { it.key })
    }

    @Test
    fun `categories come from the task first, then the event's calendar`() {
        val tasks = listOf(Task("t-arabic", "Arabic hw", CalendarCategory.QURAN_ARABIC, 60))
        val calendars = mapOf("cal-academic" to CalendarCategory.ACADEMIC, "cal-lectures" to CalendarCategory.LECTURES)
        val categoryOf = DurationLearning.categoryResolver(tasks, calendars::get)
        val logs = listOf(
            // Task wins even though the event sits in the Academic calendar.
            log("Arabic hw", 60, 90, taskId = "t-arabic"),
            log("Lecture - CP2", 60, 60, calendarId = "cal-lectures"),
            log("Lecture - ADA", 60, 80, calendarId = "cal-lectures"),
            log("PintOS", 120, 120),
            log("Mystery", 30, 30, calendarId = "cal-unknown"),
        )
        val byCat = DurationLearning.byCategory(logs, categoryOf)
        assertEquals(listOf(CalendarCategory.ACADEMIC, CalendarCategory.LECTURES, CalendarCategory.QURAN_ARABIC), byCat.keys.toList())
        assertEquals(1.5, byCat.getValue(CalendarCategory.QURAN_ARABIC).medianRatio)
        assertEquals(2, byCat.getValue(CalendarCategory.LECTURES).samples)
        assertEquals(140, byCat.getValue(CalendarCategory.LECTURES).actualMin)
        assertEquals("Lectures", byCat.getValue(CalendarCategory.LECTURES).label)
    }

    @Test
    fun `minutes read like the event conventions`() {
        assertEquals("45m", DurationLearning.formatMinutes(45))
        assertEquals("2h", DurationLearning.formatMinutes(120))
        assertEquals("2h05", DurationLearning.formatMinutes(125))
        assertEquals("0m", DurationLearning.formatMinutes(0))
    }

    @Test
    fun `summary lists titles with enough history and every category`() {
        val logs = listOf(
            log("PintOS (1/2)", 120, 150, focus = 4),
            log("PintOS (2/2)", 120, 150, focus = 3),
            log("PintOS", 120, 60, ExecutionOutcome.STOPPED),
            log("Gym", 60, 45, calendarId = "cal-fitness"),
        )
        val categoryOf = DurationLearning.categoryResolver(emptyList()) {
            mapOf("cal-academic" to CalendarCategory.ACADEMIC, "cal-fitness" to CalendarCategory.FITNESS)[it]
        }
        val text = DurationLearning.summary(DurationLearning.report(logs, categoryOf))
        assertEquals(
            """
            Actual vs planned (finished blocks):
            By task:
            - PintOS: planned 2h, took 2h30 on average (typically +25%), 2 done, 1 stopped early, focus 3.5/5
            By calendar:
            - Academic: planned 2h, took 2h30 on average (typically +25%), 2 done, 1 stopped early, focus 3.5/5
            - Fitness: planned 1h, took 45m on average (typically -25%), 1 done
            """.trimIndent(),
            text,
        )
        assertTrue("Gym" !in text.substringBefore("By calendar")) // one block is not enough history
    }

    @Test
    fun `summary says so when there is nothing to learn from`() {
        val text = DurationLearning.summary(DurationLearning.report(emptyList()) { null })
        assertEquals("Actual vs planned: not enough finished blocks logged yet.", text)
    }
}
