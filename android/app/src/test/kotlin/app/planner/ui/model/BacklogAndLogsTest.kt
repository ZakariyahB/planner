package app.planner.ui.model

import app.planner.engine.fakes.FakePlannerQueries
import app.planner.engine.fakes.ReferenceWeek
import app.planner.engine.model.CalendarCategory
import app.planner.engine.model.EventRef
import app.planner.engine.model.ExecutionOutcome
import app.planner.engine.model.Prayer
import app.planner.engine.model.Task
import app.planner.engine.model.TaskStatus
import app.planner.engine.model.UnparsedTemplate
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BacklogAndLogsTest {
    private val today = ReferenceWeek.days[1]

    @Test
    fun chipsListEveryConstraint() {
        val t = ReferenceWeek.tasks.first { it.id == "arabic-hw-tue" }.copy(deadline = LocalDate.of(2026, 11, 13), splittable = false, priority = 2)
        assertEquals(listOf("Tue 3 Nov", "after Asr", "due Fri 13 Nov", "one block", "priority 2"), BacklogItems.chips(t, today))
        val lecture = ReferenceWeek.tasks.first { it.id == "lec-os-thu" }
        assertEquals(listOf("Thu 5 Nov", "from 15:00", "passive"), BacklogItems.chips(lecture, today))
    }

    @Test
    fun backlogSplitsToPlanAndPlannedAndCountsNeedsALook() {
        val tasks = ReferenceWeek.tasks.take(3) + ReferenceWeek.tasks[3].copy(status = TaskStatus.SCHEDULED) +
            ReferenceWeek.tasks[4].copy(status = TaskStatus.DONE)
        val needs = listOf(
            UnparsedTemplate(EventRef("cal-academic", "x"), "5. ADA revision sometime", "Academic ", LocalDate.of(2026, 11, 12)),
            UnparsedTemplate(EventRef("cal-commute", "y"), "kahf", "Commute", LocalDate.of(2026, 11, 13)),
        )
        val ui = BacklogItems.build(tasks, needs, today)
        assertEquals(3, ui.toPlan.size)
        assertEquals(1, ui.planned.size)
        assertEquals("3 to plan · 2 need a look", ui.summary)
        assertEquals("“5. ADA revision sometime”", ui.needsALook[0].quoted)
        assertEquals("Academic · Thu 12 Nov · no duration", ui.needsALook[0].detail)
        assertEquals("Commute · Fri 13 Nov · not numbered", ui.needsALook[1].detail)
    }

    @Test
    fun fixingANeedsALookItemPrefillsTheForm() {
        val u = UnparsedTemplate(EventRef("cal-academic", "x"), "5. ADA revision (sometime)", "Academic ", LocalDate.of(2026, 11, 12))
        val d = BacklogItems.draftOf(u)
        assertEquals("ADA revision", d.title)
        assertEquals(CalendarCategory.ACADEMIC, d.category)
        assertEquals(LocalDate.of(2026, 11, 12), d.day)
        val task = BacklogItems.toTask(d.copy(durationMin = 90), existing = null, newId = { "new-1" })
        assertEquals("new-1", task.id)
        assertEquals(EventRef("cal-academic", "x"), task.sourceEvent)
        assertEquals(TaskStatus.BACKLOG, task.status)
        assertEquals(90, task.durationMin)
    }

    @Test
    fun formValidationAndEditingKeepsLinks() {
        assertFalse(TaskDraft(title = " ").canSave)
        assertFalse(TaskDraft(title = "x", durationMin = 10).canSave)
        assertTrue(TaskDraft(title = "x").canSave)
        val existing = Task("t1", "PintOS", CalendarCategory.ACADEMIC, 180, calendarEventIds = listOf(EventRef("c", "e")), status = TaskStatus.SCHEDULED)
        val edited = BacklogItems.toTask(BacklogItems.draftOf(existing).copy(title = "PintOS lab", afterPrayer = Prayer.ASR, earliestStart = LocalTime.of(15, 0), notes = " "), existing)
        assertEquals("PintOS lab", edited.title)
        assertEquals(listOf(EventRef("c", "e")), edited.calendarEventIds)
        assertEquals(TaskStatus.SCHEDULED, edited.status)
        assertNull(edited.notes)
    }

    @Test
    fun executionLogsReuseOneRecordPerBlock() = runBlocking {
        val at = today.atTime(10, 0)
        val block = FakePlannerQueries(now = { at }).observeToday().first().blocks.first { it.title == "PintOS" }
        val started = ExecutionLogs.start(block, null, today.atTime(9, 32))
        assertEquals(ExecutionOutcome.STARTED, started.outcome)
        assertEquals(today.atTime(9, 32), started.actualStart)
        assertEquals(DayTimelines.blockKey(block), started.id)
        val restarted = ExecutionLogs.start(block, started, today.atTime(9, 50))
        assertEquals(today.atTime(9, 32), restarted.actualStart)
        val done = ExecutionLogs.done(block, started, today.atTime(12, 40))
        assertEquals(started.id, done.id)
        assertEquals(today.atTime(9, 32), done.actualStart)
        assertEquals(ExecutionOutcome.DONE, done.outcome)
        assertEquals(5, ExecutionLogs.focus(done, 9).focus1to5)
        assertEquals(ExecutionOutcome.SKIPPED, ExecutionLogs.skip(block, null).outcome)
        assertNull(ExecutionLogs.done(block, null, at).actualStart)
    }

    @Test
    fun formatting() {
        assertEquals("3h", Fmt.duration(180))
        assertEquals("3h 30m", Fmt.duration(210))
        assertEquals("1h 09m", Fmt.duration(69))
        assertEquals("45m", Fmt.duration(45))
        assertEquals("Dhuhr, Asr and Isha", Fmt.prayers(listOf(Prayer.DHUHR, Prayer.ASR, Prayer.ISHA)))
        assertEquals("30 November – 6 December", Fmt.weekRange(LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 6)))
    }

    @Test
    fun launchExtras() {
        assertEquals(LaunchRequest.Approvals("cs-1"), LaunchRequest.parse("approvals", "cs-1"))
        assertEquals(LaunchRequest.Approvals(null), LaunchRequest.parse("approvals", ""))
        assertEquals(LaunchRequest.Week, LaunchRequest.parse("week", null))
        assertEquals(LaunchRequest.Chat, LaunchRequest.parse("chat", null))
        assertNull(LaunchRequest.parse("purple", null))
        assertNull(LaunchRequest.parse(null, "cs-1"))
    }
}
